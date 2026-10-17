package com.codefrak.engine.smith

import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.Watcher
import io.fabric8.kubernetes.client.WatcherException
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * A Smith as a Kubernetes Job (engine/README.md, "Smith runs", "Hosting models"): the k3s model, beside
 * [DockerSandbox]. The Job's one pod runs the same Smith image with the same environment as a Docker
 * sandbox, as the same non-root user, every capability dropped, no privilege escalation, no
 * service-account token and no service links, capped at the same memory and CPU (its limits), with smaller
 * requests from the hosting settings so the pod is Burstable, in the lowest PriorityClass [SMITH_PRIORITY_CLASS]:
 * under node pressure a Smith is evicted first, and [evicted] tells that from a failure. The environment (the
 * repo's Smith token and the AI credential with it) is a Secret of the run's own, never in the Job's spec.
 *
 * - **inputs**: an init container (`smith-inputs`) waits on an emptyDir at [INPUT_DIR]; the engine
 *   untars the inputs into it over `exec`, as Docker's copy-to-container does, then lets it end;
 * - **outputs**: `/work/out` is an emptyDir shared with a `keeper` sidecar that only sleeps, so the files
 *   outlive the Smith's exit and are read over `exec` (`tar`) in the keeper until [remove];
 * - **exits**: a pod watch on the run label, reported once the `smith` container has terminated.
 *
 * Its handles are `k8s:<namespace>/<job>`, so `SmithSandboxes` routes them here. The client is built on
 * first use, never at startup: from the pod's ServiceAccount when the engine runs in a pod ([InCluster]),
 * else from the kubeconfig. An engine outside a cluster with no kubeconfig runs Docker only.
 */
class KubernetesSandbox(
    private val client: Lazy<KubernetesClient>,
    /** Whether the kubeconfig is there to build [client] from. */
    private val kubeconfigPresent: () -> Boolean,
    /** The hosting settings as they are now (`SmithHosting`): namespace, image and engine URL. */
    private val settings: () -> SmithHostingSettings,
    /** A Smith's environment for a spec and an engine URL, built by the shared [sandboxEnv] helper. */
    private val environment: (SmithSpec, String) -> List<String>,
    private val memoryBytes: Long,
    private val cpus: Double,
    /** The Job's `activeDeadlineSeconds`: past it the cluster kills the pod, the run's bound like the Docker sweep's. */
    private val activeDeadline: Duration,
    /** How long a start waits for the pod to be ready for its inputs (scheduling and the image pull). */
    private val startTimeout: Duration,
    /** The pause between looks while waiting; tests shorten it. */
    private val poll: Duration = Duration.ofSeconds(1),
    /** Whether the engine runs in a pod ([InCluster]): then [client] needs no kubeconfig. */
    private val inPod: () -> Boolean = { false },
) : SmithSandbox {
    private val log = LoggerFactory.getLogger(KubernetesSandbox::class.java)

    /** What is missing for a Kubernetes run, by name; empty when one can start. */
    fun missing(): List<String> = settings().missing() + (if (clusterConfigured()) emptyList() else listOf("kubeconfig"))

    /** In a pod the ServiceAccount is the cluster's credential; outside one, the kubeconfig. */
    private fun clusterConfigured(): Boolean = inPod() || kubeconfigPresent()

    /** Whether the cluster can be asked at all: a credential and a namespace. */
    private fun reachable(): Boolean = clusterConfigured() && settings().namespace.isNotBlank()

    override fun start(spec: SmithSpec): String {
        val missing = missing()
        check(missing.isEmpty()) { "kubernetes hosting needs ${missing.joinToString()} set (engine/README.md, \"Hosting models\")" }
        val s = settings()
        val ns = s.namespace
        val name = jobName(spec)
        val k = client.value
        val jobs = k.batch().v1().jobs().inNamespace(ns)
        jobs.withName(name).get()?.let {
            // This run's own Job from an attempt the engine did not finish, like Docker's reclaim.
            val pod = pod(ns, name)
            if (pod != null && smithRunning(pod)) {
                log.info("smith job adopted: {}/{} already running from an earlier attempt", ns, name)
                return handleOf(ns, name)
            }
            log.info("smith job orphan: {}/{} -- deleting and starting fresh", ns, name)
            delete(ns, name)
            jobs.withName(name).waitUntilCondition({ it == null }, 60, TimeUnit.SECONDS)
        }
        val env = environment(spec, s.engineUrl)
        val secret = envSecret(name, env, spec)
        k.secrets().inNamespace(ns).withName(secret.metadata.name).delete()
        k.secrets().inNamespace(ns).resource(secret).create()
        jobs.resource(smithJob(name, spec, s.image, secret.metadata.name, memoryBytes, cpus, activeDeadline, spec.inputs.isNotEmpty(),
            memoryRequestBytes = s.memoryRequestBytes, cpuRequest = s.cpuRequest)).create()
        if (spec.inputs.isNotEmpty()) {
            try {
                val pod = awaitInputs(ns, name)
                val dir = INPUT_DIR.removePrefix("/work/")
                val tar = inputsTar(spec.inputs, spec.inputModes)
                // Untarred beside the mount, then copied in: the emptyDir is root's, and tar would fail setting its times.
                // `head -c` reads exactly the archive's bytes: exec's stdin never reaches EOF in the pod (the
                // exec protocol cannot half-close it), so a bare `tar -f -` waited out the exec timeout.
                val (untar, err) = exec(ns, pod, INPUTS_CONTAINER, tar, "sh", "-c",
                    "mkdir -p /tmp/smith-in && head -c ${tar.size} | tar -x -f - -C /tmp/smith-in && cp -R /tmp/smith-in/$dir/. $INPUT_DIR/")
                check(untar == 0) { "inputs could not be put in $ns/$pod: tar exited $untar: ${String(err).trim()}" }
                exec(ns, pod, INPUTS_CONTAINER, null, "touch", "$INPUT_DIR/$READY")
            } catch (e: Exception) {
                remove(handleOf(ns, name))
                throw e
            }
        }
        log.info("smith sandbox started: job={}/{} {} run={} image={}",
            ns, name, runLabel(spec), spec.runId, s.image)
        return handleOf(ns, name)
    }

    /** Waits for the input init container to run; returns the pod's name. A pod that cannot get there fails the start, naming why. */
    private fun awaitInputs(ns: String, job: String): String {
        val deadline = Instant.now().plus(startTimeout)
        var why = "no pod yet"
        while (Instant.now().isBefore(deadline)) {
            val pod = pod(ns, job)
            if (pod != null) {
                val init = pod.status?.initContainerStatuses?.firstOrNull { it.name == INPUTS_CONTAINER }
                if (init?.state?.running != null) return pod.metadata.name
                why = init?.state?.waiting?.let { listOfNotNull(it.reason, it.message).joinToString(": ") }
                    ?: pod.status?.phase ?: "pending"
                if (podExit(pod) != null) break
            }
            Thread.sleep(poll.toMillis())
        }
        throw IllegalStateException("smith job $ns/$job was not ready for its inputs within $startTimeout: $why")
    }

    override fun status(handle: String): SandboxStatus {
        val (ns, name) = parse(handle)
        val job = client.value.batch().v1().jobs().inNamespace(ns).withName(name).get()
            ?: return SandboxStatus(running = false, exitCode = null)
        val pod = pod(ns, name)
            ?: return if ((job.status?.failed ?: 0) > 0) SandboxStatus(false, -1L) else SandboxStatus(true, null)
        val exit = podExit(pod)
        return SandboxStatus(exit == null, exit)
    }

    /**
     * A watch on the namespace's pods carrying [RUN_LABEL]: each pod is reported once, when its `smith`
     * container has terminated (or the pod failed without it running). Reopened with a capped backoff when
     * it closes, calling `onReconnect`; while the cluster cannot be asked (no kubeconfig or namespace) it
     * waits, so an engine on Docker alone opens nothing.
     */
    override fun watchExits(listener: (SandboxExit) -> Unit, onReconnect: () -> Unit): AutoCloseable {
        val open = java.util.concurrent.atomic.AtomicBoolean(true)
        val current = java.util.concurrent.atomic.AtomicReference<io.fabric8.kubernetes.client.Watch?>(null)
        val reported = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val thread = Thread.ofVirtual().name("smith-k8s-exits").start {
            var connections = 0
            var backoff = 1_000L
            while (open.get()) {
                try {
                    if (!reachable()) { Thread.sleep(IDLE_MILLIS); continue }
                    val ns = settings().namespace
                    val closed = java.util.concurrent.CountDownLatch(1)
                    val watch = client.value.pods().inNamespace(ns).withLabel(RUN_LABEL).watch(object : Watcher<Pod> {
                        override fun eventReceived(action: Watcher.Action, pod: Pod) {
                            if (action == Watcher.Action.DELETED) return
                            val exit = podExit(pod) ?: return
                            val job = pod.metadata?.labels?.get(JOB_NAME_LABEL) ?: return
                            if (!reported.add(pod.metadata.uid ?: pod.metadata.name)) return
                            val e = SandboxExit(handleOf(ns, job), pod.metadata?.annotations?.get(RUN_LABEL), exit)
                            log.info("smith exit event: job {}/{} run {} exit {}", ns, job, e.runId, exit)
                            runCatching { listener(e) }.onFailure { log.warn("smith exit listener failed for {}: {}", job, it.message) }
                        }
                        override fun onClose(cause: WatcherException?) {
                            if (open.get()) log.warn("smith k8s exit watch closed: {}", cause?.message)
                            closed.countDown()
                        }
                    })
                    current.set(watch)
                    if (connections++ > 0) {
                        log.info("smith k8s exit watch reconnected")
                        runCatching(onReconnect).onFailure { log.warn("reconnect scan failed: {}", it.message) }
                    } else log.info("smith k8s exit watch open on {}", ns)
                    backoff = 1_000L
                    closed.await()
                    current.set(null)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (open.get()) log.warn("smith k8s exit watch failed: {}", e.message)
                }
                if (!open.get()) break
                try { Thread.sleep(backoff) } catch (e: InterruptedException) { break }
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
        return AutoCloseable {
            open.set(false)
            runCatching { current.get()?.close() }
            thread.interrupt()
        }
    }

    /** The `smith` container's log, followed once it has started; the cluster ends it when the container exits. */
    override fun follow(handle: String, fromNow: Boolean, onLine: (String) -> Unit, onEnd: (Throwable?) -> Unit): AutoCloseable {
        val (ns, name) = parse(handle)
        val since = Instant.now().toString()
        val open = java.util.concurrent.atomic.AtomicBoolean(true)
        val current = java.util.concurrent.atomic.AtomicReference<io.fabric8.kubernetes.client.dsl.LogWatch?>(null)
        Thread.ofVirtual().name("smith-k8s-log-$name").start {
            val error = try {
                var started: Pod? = null
                while (open.get()) {
                    val found = pod(ns, name)
                    if (found == null && client.value.batch().v1().jobs().inNamespace(ns).withName(name).get() == null) break
                    if (found != null && smithStarted(found)) { started = found; break }
                    Thread.sleep(poll.toMillis())
                }
                if (started != null && open.get()) {
                    val container = client.value.pods().inNamespace(ns).withName(started.metadata.name).inContainer(SMITH_CONTAINER)
                    val watch = if (fromNow) container.sinceTime(since).watchLog() else container.watchLog()
                    current.set(watch)
                    watch.output.bufferedReader().useLines { lines -> lines.forEach { if (open.get()) onLine(it) } }
                }
                null
            } catch (e: Exception) { e }
            current.getAndSet(null)?.let { runCatching { it.close() } }
            if (open.getAndSet(false)) onEnd(error)
        }
        return AutoCloseable { open.set(false); current.getAndSet(null)?.let { runCatching { it.close() } } }
    }

    override fun logs(handle: String, maxLines: Int): String {
        val (ns, name) = parse(handle)
        val pod = pod(ns, name) ?: return ""
        return client.value.pods().inNamespace(ns).withName(pod.metadata.name).inContainer(SMITH_CONTAINER)
            .tailingLines(maxLines).log ?: ""
    }

    override fun output(handle: String, path: String): ByteArray? = readTar(handle, path)?.let(::firstTarEntry)

    override fun outputDir(handle: String, path: String, maxBytes: Long): ByteArray? {
        val tar = readTar(handle, path) ?: return null
        if (tar.size > maxBytes) throw OutputTooLarge(path, maxBytes)
        return tar
    }

    /** [path] as a tar archive (entries `<its name>/...`, like Docker's copy), read in the keeper; null when it is not there. */
    private fun readTar(handle: String, path: String): ByteArray? {
        val (ns, name) = parse(handle)
        val pod = pod(ns, name) ?: return null
        val file = File(path)
        val (code, _, out) = execOut(ns, pod.metadata.name, KEEPER_CONTAINER, null, "tar", "-c", "-f", "-", "-C", file.parent, file.name)
        return if (code == 0) out else null
    }

    override fun oomKilled(handle: String): Boolean? {
        val (ns, name) = parse(handle)
        val pod = pod(ns, name) ?: return null
        val terminated = smithStatus(pod)?.state?.terminated ?: return null
        return terminated.reason == "OOMKilled"
    }

    override fun evicted(handle: String): Boolean? {
        val (ns, name) = parse(handle)
        val pod = pod(ns, name) ?: return null
        return podEvicted(pod)
    }

    /** Deletes the Job (its pod with it) and the run's env Secret; a gone one is not an error. */
    override fun remove(handle: String) {
        val (ns, name) = parse(handle)
        runCatching { delete(ns, name) }
            .onFailure { log.warn("could not remove smith job {}/{}: {}", ns, name, it.message) }
    }

    /** The pod gets the cluster's grace period (SIGTERM, then killed), as Docker's stop. */
    override fun stop(handle: String) = remove(handle)

    override fun runAt(address: String): SmithCaller? {
        if (!reachable()) return null
        val ip = callerIp(address) ?: return null
        val pod = client.value.pods().inNamespace(settings().namespace).withLabel(RUN_LABEL).list().items
            .firstOrNull { it.status?.phase == "Running" && it.status?.podIP == ip } ?: return null
        val a = pod.metadata?.annotations ?: return null
        return SmithCaller.of(a) ?: return null
    }

    /** Every Smith Job in the namespace, as the sweep sees a container: id is the handle. Empty while the cluster cannot be asked. */
    fun jobs(): List<SmithContainer> {
        if (!reachable()) return emptyList()
        val ns = settings().namespace
        return client.value.batch().v1().jobs().inNamespace(ns).withLabel(RUN_LABEL).list().items.map { job ->
            val name = job.metadata.name
            val running = pod(ns, name)?.let { podExit(it) == null } ?: false
            val created = job.metadata.creationTimestamp?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH
            SmithContainer(handleOf(ns, name), name, running, created)
        }
    }

    private fun delete(ns: String, name: String) {
        val k = client.value
        k.batch().v1().jobs().inNamespace(ns).withName(name)
            .withPropagationPolicy(io.fabric8.kubernetes.api.model.DeletionPropagation.BACKGROUND).delete()
        k.pods().inNamespace(ns).withLabel(JOB_NAME_LABEL, name).delete()
        k.secrets().inNamespace(ns).withName("$name-env").delete()
    }

    private fun pod(ns: String, job: String): Pod? =
        client.value.pods().inNamespace(ns).withLabel(JOB_NAME_LABEL, job).list().items.firstOrNull()

    /** Runs [cmd] in [container]; returns its exit code and stderr. */
    private fun exec(ns: String, pod: String, container: String, stdin: ByteArray?, vararg cmd: String): Pair<Int, ByteArray> =
        execOut(ns, pod, container, stdin, *cmd).let { (code, err, _) -> code to err }

    private fun execOut(ns: String, pod: String, container: String, stdin: ByteArray?, vararg cmd: String): Triple<Int, ByteArray, ByteArray> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = client.value.pods().inNamespace(ns).withName(pod).inContainer(container)
            .readingInput(ByteArrayInputStream(stdin ?: ByteArray(0)))
            .writingOutput(out).writingError(err)
            .exec(*cmd).use { it.exitCode().get(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        return Triple(code ?: -1, err.toByteArray(), out.toByteArray())
    }

    companion object {
        const val HANDLE_PREFIX = "k8s:"
        const val SMITH_CONTAINER = "smith"
        const val INPUTS_CONTAINER = "smith-inputs"
        const val KEEPER_CONTAINER = "keeper"
        /** What the cluster's Job controller labels a Job's pods with. */
        const val JOB_NAME_LABEL = "job-name"
        /** The file the engine touches once the inputs are in; the init container ends on it. */
        const val READY = ".ready"
        private const val EXEC_TIMEOUT_SECONDS = 120L
        private const val IDLE_MILLIS = 30_000L

        fun handleOf(ns: String, job: String) = "$HANDLE_PREFIX$ns/$job"

        /** A handle as namespace and Job name. */
        fun parse(handle: String): Pair<String, String> {
            val rest = handle.removePrefix(HANDLE_PREFIX)
            require(rest != handle && '/' in rest) { "not a kubernetes sandbox handle: $handle" }
            return rest.substringBefore('/') to rest.substringAfter('/')
        }
    }
}

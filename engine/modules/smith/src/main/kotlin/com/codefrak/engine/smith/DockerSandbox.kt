package com.codefrak.engine.smith

import com.codefrak.engine.accounts.AiAccounts
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.model.Event
import com.github.dockerjava.api.model.EventType
import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient
import com.github.dockerjava.api.async.ResultCallback
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * A Smith as a sibling container (engine/README.md, "Smith runs"). The engine
 * talks to the Docker daemon over the mounted socket; the sandbox never sees
 * that socket, the engine's environment, or anything but its own inputs.
 */
@Component
@ConditionalOnProperty("engine.smith.sandbox", havingValue = "docker", matchIfMissing = true)
class DockerSandbox(
    @Value("\${engine.smith.image:frakworks-smith:main}") private val image: String,
    /** How often a mutable tag (`:main`) is re-pulled before a run (engine/README.md, "Smith runs"); PT0S: every run. */
    @Value("\${engine.smith.image-refresh-interval:PT5M}") private val imageRefreshInterval: Duration,
    /** The bound on one pull: past it the start fails into an incident rather than hanging. */
    @Value("\${engine.smith.image-pull-timeout:PT5M}") private val imagePullTimeout: Duration,
    @Value("\${engine.smith.network:}") private val network: String,
    @Value("\${engine.smith.memory-bytes:4294967296}") private val memoryBytes: Long,
    @Value("\${engine.smith.cpus:2}") private val cpus: Double,
    /** The ticket's repo's Smith token (engine/README.md, "Each repo's token"); a repo without one, or with one that reaches issues, starts no sandbox. */
    private val tokens: com.codefrak.engine.github.SmithTokens,
    /** Who the Smith's commits are by: that token's account; a run whose token GitHub will not name starts no sandbox. */
    private val identity: com.codefrak.engine.github.TokenIdentity,
    /** The engine as a sandbox reaches it, for the frakworks MCP server (engine/README.md, "The frakworks server in a Smith"); blank: none. */
    @Value("\${engine.smith.engine-url:}") private val engineUrl: String,
    @Value("\${engine.docker.host:unix:///var/run/docker.sock}") dockerHost: String,
    private val accounts: AiAccounts,
    /** The repo's Smith-audience MCP servers (#2449), into the container's env as `SMITH_MCP_SERVERS`, as the Kubernetes path's Secret carries them. */
    private val mcpServers: SmithMcpServers,
) : SmithSandbox {
    private val log = LoggerFactory.getLogger(DockerSandbox::class.java)

    private val config = DefaultDockerClientConfig.createDefaultConfigBuilder()
        .withDockerHost(dockerHost).build()

    private val docker: DockerClient = run {
        val http = ZerodepDockerHttpClient.Builder()
            .dockerHost(config.dockerHost)
            .connectionTimeout(Duration.ofSeconds(10))
            .responseTimeout(Duration.ofSeconds(60))
            .build()
        DockerClientImpl.getInstance(config, http)
    }

    /**
     * A second client for the long-lived streams only: `/events`, one response
     * that stays open for as long as the engine runs, and each run's followed
     * log ([follow]), open for as long as the run; the 60 s response timeout
     * above would cut either at the first quiet minute. Built once, on first use.
     */
    private val eventsDocker: DockerClient by lazy {
        val http = ZerodepDockerHttpClient.Builder()
            .dockerHost(config.dockerHost)
            .connectionTimeout(Duration.ofSeconds(10))
            .build()
        DockerClientImpl.getInstance(config, http)
    }

    /**
     * A third client for image pulls: a pull of a large layer outlasts the 60 s
     * response timeout above, so this one has none, and [pullImage]'s own
     * timeout (`engine.smith.image-pull-timeout`) bounds it. Built once, on first use.
     */
    private val pullDocker: DockerClient by lazy {
        val http = ZerodepDockerHttpClient.Builder()
            .dockerHost(config.dockerHost)
            .connectionTimeout(Duration.ofSeconds(10))
            .build()
        DockerClientImpl.getInstance(config, http)
    }

    /** When each image was last pulled or refreshed (attempted), for [ensureImage]'s refresh interval. */
    private val lastRefresh = java.util.concurrent.ConcurrentHashMap<String, Instant>()

    override fun start(spec: SmithSpec): String {
        val (credential, committer) = smithCredentialAndCommitter(spec, accounts, identity)
        val ghToken = tokens.forRun(spec.repo)
        val env = sandboxEnv(spec, ghToken, credential, engineUrl, committer, mcpServers.forRun(spec))

        val host = sandboxHostConfig(memoryBytes, cpus)
        if (network.isNotBlank()) host.withNetworkMode(network)
        // No mount on /home/smith/.claude: the login is the token in the env, and
        // Claude Code writes its session state (compaction summaries included)
        // there, so it must be the Smith's own and die with the container
        // (engine/README.md, "Smith runs").

        // A job run (no issue) is smith-job-<jobId>-<run8>, so the sweep's smith- prefix and the run8 hold for it too.
        val name = if (spec.issue != null) "smith-${spec.issue}-${spec.runId.take(8)}" else "smith-job-${spec.jobId}-${spec.runId.take(8)}"
        ensureImage(docker, image, imageRefreshInterval, imagePullTimeout, lastRefresh, Clock.systemUTC(), log, pullDocker)
        reclaim(docker, name, log)?.let { return it }
        val created = docker.createContainerCmd(image)
            .withName(name)
            .withEnv(env)
            .withHostConfig(host)
            .withLabels(sandboxLabels(spec))
            .exec()
        if (spec.inputs.isNotEmpty()) copyInputs(docker, created.id, "/work", spec.inputs, spec.inputModes)
        docker.startContainerCmd(created.id).exec()
        // The container holds the image id Docker resolved at create, so a later
        // pull of :main never touches it; this line records which build it runs.
        log.info("smith sandbox started: container={} name={} {} run={} image={} digest={}",
            created.id.take(12), name, runLabel(spec), spec.runId, image, imageDigest(docker, created.id, image) ?: "unknown")
        return created.id
    }

    override fun status(handle: String): SandboxStatus {
        val state = try {
            docker.inspectContainerCmd(handle).exec().state
        } catch (e: com.github.dockerjava.api.exception.NotFoundException) {
            return SandboxStatus(running = false, exitCode = null)
        }
        val running = state.running == true
        return SandboxStatus(running, if (running) null else state.exitCodeLong)
    }

    /**
     * Docker's event stream, filtered to `die` events on containers carrying
     * the Smith run label; the exit code rides in the event's attributes. The
     * stream is read on its own thread and reopened with a capped backoff
     * whenever it ends (daemon restart, socket hiccup); each reopen calls
     * `onReconnect`, since a `die` in the gap is gone for good.
     */
    override fun watchExits(listener: (SandboxExit) -> Unit, onReconnect: () -> Unit): AutoCloseable {
        val open = java.util.concurrent.atomic.AtomicBoolean(true)
        val current = java.util.concurrent.atomic.AtomicReference<ResultCallback<Event>?>(null)
        val thread = Thread.ofVirtual().name("smith-exit-events").start {
            var connections = 0
            var backoff = 1_000L
            while (open.get()) {
                if (connections > 0) {
                    Thread.sleep(backoff); backoff = (backoff * 2).coerceAtMost(30_000L)
                    if (!open.get()) break
                }
                val callback = object : ResultCallback.Adapter<Event>() {
                    override fun onStart(stream: java.io.Closeable) {
                        super.onStart(stream)
                        backoff = 1_000L
                        if (connections++ > 0) {
                            log.info("smith exit event stream reconnected")
                            runCatching(onReconnect).onFailure { log.warn("reconnect scan failed: {}", it.message) }
                        } else log.info("smith exit event stream open")
                    }
                    override fun onNext(event: Event) {
                        val exit = exitOf(event) ?: run {
                            log.warn("smith exit event without a container id, dropped: {}", event)
                            return
                        }
                        log.info("smith exit event: sandbox {} run {} exit {}", exit.handle.take(12), exit.runId, exit.exitCode)
                        runCatching { listener(exit) }
                            .onFailure { log.warn("smith exit listener failed for {}: {}", exit.handle.take(12), it.message) }
                    }
                    override fun onError(throwable: Throwable) {
                        if (open.get()) log.warn("smith exit event stream failed: {}", throwable.message)
                        super.onError(throwable)
                    }
                }
                current.set(callback)
                runCatching {
                    eventsDocker.eventsCmd()
                        .withEventTypeFilter(EventType.CONTAINER)
                        .withEventFilter("die")
                        .withLabelFilter(RUN_LABEL)
                        .exec(callback)
                        .awaitCompletion()
                }.onFailure { if (open.get()) log.warn("smith exit event stream ended: {}", it.message) }
                current.set(null)
            }
        }
        return AutoCloseable {
            open.set(false)
            runCatching { current.get()?.close() }
            thread.interrupt()
        }
    }

    /**
     * `docker logs --follow`, stdout only, on the stream client: Docker ends
     * the response when the container exits, which is `onEnd(null)`. Frames
     * do not respect lines, so [LineSplitter] reassembles them.
     */
    override fun follow(handle: String, fromNow: Boolean, onLine: (String) -> Unit, onEnd: (Throwable?) -> Unit): AutoCloseable {
        val lines = LineSplitter(onLine)
        val ended = java.util.concurrent.atomic.AtomicBoolean(false)
        fun end(error: Throwable?) { if (ended.compareAndSet(false, true)) { lines.flush(); onEnd(error) } }
        val cmd = eventsDocker.logContainerCmd(handle).withStdOut(true).withStdErr(false).withFollowStream(true)
        if (fromNow) cmd.withTail(0)
        val callback = object : ResultCallback.Adapter<Frame>() {
            override fun onNext(frame: Frame) { lines.accept(frame.payload) }
            override fun onError(throwable: Throwable) { end(throwable); super.onError(throwable) }
            override fun onComplete() { end(null); super.onComplete() }
        }
        cmd.exec(callback)
        return AutoCloseable { ended.set(true); runCatching { callback.close() } }
    }

    override fun logs(handle: String, maxLines: Int): String {
        val sb = StringBuilder()
        docker.logContainerCmd(handle).withStdOut(true).withStdErr(true).withTail(maxLines)
            .exec(object : ResultCallback.Adapter<Frame>() {
                override fun onNext(frame: Frame) { sb.append(String(frame.payload)) }
            }).awaitCompletion()
        return sb.toString()
    }

    override fun output(handle: String, path: String): ByteArray? {
        val tar = try {
            docker.copyArchiveFromContainerCmd(handle, path).exec().use { it.readAllBytes() }
        } catch (e: com.github.dockerjava.api.exception.NotFoundException) { return null }
        return firstTarEntry(tar)
    }

    override fun outputDir(handle: String, path: String, maxBytes: Long): ByteArray? = readDir(docker, handle, path, maxBytes)

    override fun oomKilled(handle: String): Boolean? = docker.inspectContainerCmd(handle).exec().state.oomKilled

    override fun remove(handle: String) {
        runCatching { docker.removeContainerCmd(handle).withForce(true).exec() }
            .onFailure { log.warn("could not remove smith container {}: {}", handle.take(12), it.message) }
    }

    override fun stop(handle: String) {
        runCatching { docker.stopContainerCmd(handle).withTimeout(STOP_GRACE_SECONDS).exec() }
            .onFailure { log.warn("could not stop smith container {}: {}", handle.take(12), it.message) }
        remove(handle)
    }

    override fun runAt(address: String): SmithCaller? =
        callerAt(docker.listContainersCmd().withLabelFilter(listOf(RUN_LABEL)).withStatusFilter(listOf("running")).exec().map { c ->
            SandboxAddresses(c.labels ?: emptyMap(), c.networkSettings?.networks?.mapValues { it.value.ipAddress } ?: emptyMap())
        }, network, address)

    /** Every `smith-*` container, running or not: what [SmithSweep] looks at. */
    fun containers(): List<SmithContainer> =
        docker.listContainersCmd().withShowAll(true).withNameFilter(listOf("smith-")).exec().mapNotNull { c ->
            val name = c.names.map { it.removePrefix("/") }.firstOrNull { it.startsWith("smith-") } ?: return@mapNotNull null
            SmithContainer(c.id, name, c.state == "running", java.time.Instant.ofEpochSecond(c.created ?: 0L))
        }

    private companion object { const val STOP_GRACE_SECONDS = 10 }
}

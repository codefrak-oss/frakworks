package com.codefrak.engine.smith

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

/**
 * The [SmithSandbox] the engine uses (engine/README.md, "Smith runs", "Hosting models"): a new run starts
 * where `smith.hosting` says at its start ([SmithHosting]), and every later call goes by the handle, so a
 * run keeps the model it started on after the setting moves. A [KubernetesSandbox] handle starts with
 * `k8s:`; anything else is a Docker container id.
 */
@Primary
@Component
@ConditionalOnProperty("engine.smith.sandbox", havingValue = "docker", matchIfMissing = true)
class SmithSandboxes(
    private val docker: SmithSandbox,
    private val kubernetes: SmithSandbox,
    /** Whether the engine runs in a pod ([InCluster]), where there is no Docker to start a run in. */
    private val inPod: () -> Boolean = { false },
    private val hosting: () -> HostingModel,
) : SmithSandbox {
    @org.springframework.beans.factory.annotation.Autowired
    constructor(docker: DockerSandbox, kubernetes: KubernetesSandbox, hosting: SmithHosting, inCluster: InCluster) :
        this(docker as SmithSandbox, kubernetes as SmithSandbox, inCluster::inPod, hosting::model)

    private fun of(handle: String): SmithSandbox = if (handle.startsWith(KubernetesSandbox.HANDLE_PREFIX)) kubernetes else docker

    override fun start(spec: SmithSpec): String = when (hosting()) {
        // Said once at startup (SmithHostingController); here the start fails naming the setting, not a socket.
        HostingModel.DOCKER -> { check(!inPod()) { InCluster.NO_DOCKER }; docker.start(spec) }
        HostingModel.KUBERNETES -> kubernetes.start(spec)
    }

    override fun status(handle: String) = of(handle).status(handle)

    /**
     * Both models' exits: the Kubernetes watch idles while no cluster is set up. In a pod there is no
     * Docker, so only the cluster's: Docker's event stream would retry a missing socket forever.
     */
    override fun watchExits(listener: (SandboxExit) -> Unit, onReconnect: () -> Unit): AutoCloseable {
        if (inPod()) return kubernetes.watchExits(listener, onReconnect)
        val d = docker.watchExits(listener, onReconnect)
        val k = kubernetes.watchExits(listener, onReconnect)
        return AutoCloseable { runCatching { d.close() }; k.close() }
    }

    override fun follow(handle: String, fromNow: Boolean, onLine: (String) -> Unit, onEnd: (Throwable?) -> Unit) =
        of(handle).follow(handle, fromNow, onLine, onEnd)

    override fun logs(handle: String, maxLines: Int) = of(handle).logs(handle, maxLines)
    override fun output(handle: String, path: String) = of(handle).output(handle, path)
    override fun outputDir(handle: String, path: String, maxBytes: Long) = of(handle).outputDir(handle, path, maxBytes)
    override fun oomKilled(handle: String) = of(handle).oomKilled(handle)
    override fun evicted(handle: String) = of(handle).evicted(handle)
    override fun remove(handle: String) = of(handle).remove(handle)
    override fun stop(handle: String) = of(handle).stop(handle)

    /**
     * The Smith at [address] in either model: Docker's network first, then the cluster's pods. One that
     * cannot be asked throws only if the other found nothing, so a caller is never taken for "not a Smith"
     * because the model it does not run on is down. In a pod there is no Docker and no Docker Smith, so
     * only the cluster is asked: an unreachable Docker would otherwise make every caller "cannot tell".
     */
    override fun runAt(address: String): SmithCaller? {
        val failures = mutableListOf<Throwable>()
        for (sandbox in if (inPod()) listOf(kubernetes) else listOf(docker, kubernetes)) {
            try {
                sandbox.runAt(address)?.let { return it }
            } catch (e: Exception) { failures += e }
        }
        failures.firstOrNull()?.let { throw it }
        return null
    }
}

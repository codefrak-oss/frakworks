package com.codefrak.engine.smith

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** `SmithSandboxes`: the setting picks the model a new run starts on; every later call goes by the handle. */
class SmithSandboxesTest {
    private class Kube : SmithSandbox {
        val started = mutableListOf<SmithSpec>()
        val removed = mutableListOf<String>()
        var caller: SmithCaller? = null
        override fun start(spec: SmithSpec): String { started += spec; return "k8s:smiths/smith-${spec.issue}" }
        override fun status(handle: String) = SandboxStatus(false, 7)
        override fun logs(handle: String, maxLines: Int) = "kube log"
        override fun output(handle: String, path: String): ByteArray? = "kube".toByteArray()
        override fun remove(handle: String) { removed += handle }
        override fun runAt(address: String) = caller
    }

    private val docker = FakeSandbox()
    private val kube = Kube()
    private var model = HostingModel.DOCKER
    private val sandboxes = SmithSandboxes(docker, kube) { model }
    private fun spec(issue: Long) = SmithSpec("run-$issue", "o/r", issue, "m", "e", dryRun = true)

    @Test
    fun `the setting at the start picks the model`() {
        assertEquals("container-1", sandboxes.start(spec(1)))
        model = HostingModel.KUBERNETES
        assertEquals("k8s:smiths/smith-2", sandboxes.start(spec(2)))
        assertEquals(listOf(1L), docker.started.map { it.issue })
        assertEquals(listOf(2L), kube.started.map { it.issue })
    }

    @Test
    fun `a run keeps the model it started on after the setting moves`() {
        model = HostingModel.KUBERNETES
        assertEquals(SandboxStatus(true, null), sandboxes.status("container-1"))
        assertEquals(SandboxStatus(false, 7), sandboxes.status("k8s:smiths/smith-2"))
        assertEquals("kube log", sandboxes.logs("k8s:smiths/smith-2"))
        sandboxes.remove("k8s:smiths/smith-2")
        sandboxes.remove("container-1")
        assertEquals(listOf("k8s:smiths/smith-2"), kube.removed)
        assertEquals(listOf("container-1"), docker.removed)
    }

    @Test
    fun `runAt asks both, and a failure throws only when the other found nobody`() {
        val throwing = object : SmithSandbox by docker {
            override fun runAt(address: String): SmithCaller? = throw IllegalStateException("docker down")
        }
        val routed = SmithSandboxes(throwing, kube) { model }
        kube.caller = SmithCaller("run-9", 9, "o/r")
        assertEquals("run-9", routed.runAt("10.42.0.9")?.runId)
        kube.caller = null
        assertFailsWith<IllegalStateException> { routed.runAt("10.42.0.9") }
        assertEquals(null, sandboxes.runAt("10.42.0.9"))
    }

    @Test
    fun `in a pod a docker start fails naming the setting, never reaching docker`() {
        val inPod = SmithSandboxes(docker, kube, inPod = { true }) { model }
        val e = assertFailsWith<IllegalStateException> { inPod.start(spec(3)) }
        assertEquals(InCluster.NO_DOCKER, e.message)
        assertEquals(emptyList(), docker.started)
        model = HostingModel.KUBERNETES
        assertEquals("k8s:smiths/smith-4", inPod.start(spec(4)))
    }

    @Test
    fun `in a pod runAt never asks docker, so a missing socket is not "cannot tell"`() {
        val noSocket = object : SmithSandbox by docker {
            override fun runAt(address: String): SmithCaller? = throw IllegalStateException("no docker socket")
            override fun watchExits(listener: (SandboxExit) -> Unit, onReconnect: () -> Unit): AutoCloseable =
                throw IllegalStateException("watched docker in a pod")
        }
        val inPod = SmithSandboxes(noSocket, kube, inPod = { true }) { model }
        assertEquals(null, inPod.runAt("10.42.0.9"))
        kube.caller = SmithCaller("run-9", 9, "o/r")
        assertEquals("run-9", inPod.runAt("10.42.0.9")?.runId)
        inPod.watchExits({}, {}).close()
    }
}

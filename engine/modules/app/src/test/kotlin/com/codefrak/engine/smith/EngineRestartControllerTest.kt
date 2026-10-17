package com.codefrak.engine.smith

import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.server.ResponseStatusException
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `POST /engine/restart`: rolls the engine's own Deployment in a pod, after Argo CD has synced when asked to wait. */
class EngineRestartControllerTest {
    @TempDir lateinit var dir: File

    private val restarted = mutableListOf<String>()
    private val waited = mutableListOf<String>()
    private var argoSynced = true
    private val sandbox = FakeSandbox()
    private val argo = ArgoSync { ns, name, _ -> waited += "$ns/$name"; argoSynced }
    private fun controller(inPod: Boolean, box: SmithSandbox = sandbox) = EngineRestartController(
        InCluster(File(dir, "token").also { if (inPod) it.writeText("t") }), box, { restarted += it }, argo, { "frakworks" })
    private fun from(address: String) = MockHttpServletRequest().apply { remoteAddr = address }

    @Test
    fun `in a pod it rolls the engine's own deployment`() {
        assertEquals(EngineRestartResponse("frakworks", "engine"), controller(inPod = true).restart(from("192.0.2.12")))
        assertEquals(listOf("frakworks"), restarted)
        assertEquals(emptyList(), waited, "no argoApp: no wait")
    }

    @Test
    fun `with argoApp it waits for that Application to sync, then rolls`() {
        controller(inPod = true).restart(from("192.0.2.12"), "argocd/frakworks")
        assertEquals(listOf("argocd/frakworks"), waited)
        assertEquals(listOf("frakworks"), restarted)
    }

    @Test
    fun `an Application that does not sync in time is a 504 and rolls nothing`() {
        argoSynced = false
        val e = assertFailsWith<ResponseStatusException> { controller(inPod = true).restart(from("192.0.2.12"), "argocd/frakworks") }
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, e.statusCode)
        assertEquals(emptyList(), restarted)
    }

    @Test
    fun `a malformed argoApp is a 400`() {
        val e = assertFailsWith<ResponseStatusException> { controller(inPod = true).restart(from("192.0.2.12"), "frakworks") }
        assertEquals(HttpStatus.BAD_REQUEST, e.statusCode)
        assertEquals(emptyList(), restarted)
    }

    @Test
    fun `outside a cluster it is a conflict and rolls nothing`() {
        val e = assertFailsWith<ResponseStatusException> { controller(inPod = false).restart(from("127.0.0.1")) }
        assertEquals(HttpStatus.CONFLICT, e.statusCode)
        assertEquals(emptyList(), restarted)
    }

    @Test
    fun `a Smith sandbox may not restart the engine`() {
        val smith = object : SmithSandbox by sandbox {
            override fun runAt(address: String) = SmithCaller("run-1", 1, "o/r")
        }
        val e = assertFailsWith<ResponseStatusException> { controller(inPod = true, box = smith).restart(from("10.42.0.9")) }
        assertEquals(HttpStatus.FORBIDDEN, e.statusCode)
        assertEquals(emptyList(), restarted)
    }

    private fun app(reconciledAt: String?, sync: String?, phase: String?) = GenericKubernetesResource().apply {
        additionalProperties["status"] = buildMap {
            reconciledAt?.let { put("reconciledAt", it) }
            put("sync", mapOf("status" to sync))
            phase?.let { put("operationState", mapOf("phase" to it)) }
        }
    }

    @Test
    fun `synced means reconciled since the ask, Synced, and no operation running`() {
        val asked = Instant.parse("2026-10-07T04:00:00.400Z")
        assertTrue(KubernetesArgoSync.synced(app("2026-10-07T04:00:00Z", "Synced", "Succeeded"), asked), "same second counts")
        assertTrue(KubernetesArgoSync.synced(app("2026-10-07T04:00:05Z", "Synced", null), asked))
        assertFalse(KubernetesArgoSync.synced(app("2026-10-07T03:59:59Z", "Synced", "Succeeded"), asked), "reconciled before the ask")
        assertFalse(KubernetesArgoSync.synced(app("2026-10-07T04:00:05Z", "OutOfSync", null), asked))
        assertFalse(KubernetesArgoSync.synced(app("2026-10-07T04:00:05Z", "Synced", "Running"), asked))
        assertFalse(KubernetesArgoSync.synced(app(null, "Synced", null), asked))
        assertFalse(KubernetesArgoSync.synced(null, asked))
    }
}

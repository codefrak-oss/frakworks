package com.codefrak.engine.firstmate

import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.events.EventRecorder
import com.codefrak.engine.smith.SmithHostingSettings
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [KubernetesFirstMateSessionPods] against fabric8's mock API server in CRUD mode: start begins a new pod epoch, mints
 * the pod's token into the Secret and creates Secret and pod in frakworks-firstmate; stop deletes both and revokes the
 * token. The engine never attaches: the pod's fm-relay connects out (engine/smith/tests/test_fm_relay.py).
 */
@EnableKubernetesMockClient(crud = true)
class FirstMatePodsTest {
    lateinit var client: KubernetesClient

    private val session = FirstMateSession("s-1", "lundmatt1", "tok-1", "acme/tickets", "open", OffsetDateTime.now(), null)
    private val mcp = FirstMateMcp(mapOf("frakworks" to mapOf("type" to "http")), emptyList())
    @Volatile private var epoch = 0
    @Volatile private var firstLine = false
    private val sessions = object : FirstMateSessions(mock(JdbcClient::class.java)) {
        override fun nextEpoch(sessionId: String): Int = ++epoch
        override fun get(id: String): FirstMateSession = session.copy(podEpoch = epoch)
        override fun hasOut(sessionId: String, epoch: Int, outSeq: Long): Boolean = firstLine
    }
    private val minted = mutableListOf<String>()
    private val revoked = mutableListOf<String>()
    private val bus = EngineEvents(jacksonObjectMapper())
    private var configured = true
    private val askedTiers = mutableListOf<Int?>()

    private fun pods() = KubernetesFirstMateSessionPods(
        client = lazy { client }, clusterConfigured = { configured },
        settings = { SmithHostingSettings("kubernetes", "frakworks-smith", "registry.example:5000/frakworks-smith:main", "http://engine") },
        account = { t -> askedTiers += t; if (t == 3) FirstMateAccount("ANTHROPIC_API_KEY", "sk-secret", "claude-sonnet-5-5", "low") else FirstMateAccount("ANTHROPIC_API_KEY", "sk-secret", "claude-opus-5-5", "high") },
        sessions = sessions, events = bus, memoryBytes = 3221225472, cpus = 2.0, startTimeout = Duration.ofSeconds(5),
        mintToken = { s -> minted += s.id; "fw_pod-${minted.size}" }, revokeTokens = { revoked += it },
        prompt = FirstMatePrompt("prompt", listOf("Read"), emptyList()), resumingEvery = Duration.ofMillis(50),
    )

    private fun pod(s: FirstMateSession) = client.pods().inNamespace("frakworks-firstmate").withName(firstMatePodName(s)).get()

    @Test
    fun `start creates the secret and pod in frakworks-firstmate and none in frakworks-smith, and stop deletes both`() {
        val pods = pods()
        pods.start(session, mcp)
        val name = firstMatePodName(session)
        assertNotNull(pod(session))
        assertNotNull(client.secrets().inNamespace("frakworks-firstmate").withName(name).get())
        assertTrue(client.pods().inNamespace("frakworks-smith").list().items.isEmpty())
        assertTrue(client.secrets().inNamespace("frakworks-smith").list().items.isEmpty())

        pods.stop(session)
        assertNull(pod(session))
        assertNull(client.secrets().inNamespace("frakworks-firstmate").withName(name).get())
    }

    @Test
    fun `with no cluster nothing is created, no epoch begins and no token is minted`() {
        configured = false
        pods().start(session, mcp)
        assertTrue(client.pods().inNamespace("frakworks-firstmate").list().items.isEmpty())
        assertEquals(0, epoch)
        assertTrue(minted.isEmpty())
    }

    @Test
    fun `each pod start begins a new pod epoch`() {
        val pods = pods()
        pods.start(session, mcp)
        assertEquals(1, epoch)
        pods.stop(session)
        pods.start(session, mcp, FirstMateSnapshot(null, null, 0))
        assertEquals(2, epoch)
        pods.stop(session)
    }

    @Test
    fun `the pod's token is minted for the session into its Secret and revoked when the pod is deleted`() {
        val pods = pods()
        pods.start(session, mcp)
        assertEquals(listOf("s-1"), minted)
        val secret = client.secrets().inNamespace("frakworks-firstmate").withName(firstMatePodName(session)).get()
        val data = secret.stringData?.get(FIRST_MATE_TOKEN_KEY)
            ?: secret.data?.get(FIRST_MATE_TOKEN_KEY)?.let { String(java.util.Base64.getDecoder().decode(it)) }
        assertEquals("fw_pod-1", data)
        assertFalse(pod(session).toString().contains("fw_pod-1"))
        assertTrue(revoked.isEmpty())
        pods.stop(session)
        assertEquals(listOf("s-1"), revoked)
    }

    @Test
    fun `the container runs fm-relay with claude behind it and no stdin for an attach`() {
        pods().start(session, mcp)
        val container = pod(session).spec.containers.single()
        assertEquals(listOf("fm-relay", "--", "claude"), container.command.take(3))
        assertTrue("--resume" !in container.command)
        assertEquals("http://engine", container.env.single { it.name == "FM_ENGINE_URL" }.value)
        assertTrue(container.env.none { it.name == "FM_RESUME" })
        assertFalse(container.stdin == true)
    }

    @Test
    fun `the session's tier picks the account, model and effort at every pod start, a resume included, and null asks for the default`() {
        val pods = pods()
        pods.start(session, mcp)
        assertEquals(listOf<Int?>(null), askedTiers)
        assertTrue(pod(session).spec.containers.single().command.containsAll(listOf("--model", "claude-opus-5-5", "--effort", "high")))
        pods.stop(session)

        val tiered = session.copy(id = "s-3", tier = 3)
        pods.start(tiered, mcp)
        assertEquals(listOf(null, 3), askedTiers)
        val argv = pod(tiered).spec.containers.single().command
        assertEquals("claude-sonnet-5-5", argv[argv.indexOf("--model") + 1])
        assertEquals("low", argv[argv.indexOf("--effort") + 1])
        pods.stop(tiered)
        pods.start(tiered.copy(claudeSessionId = "0b5e-77"), mcp, FirstMateSnapshot(null, null, 0))
        assertEquals(listOf(null, 3, 3), askedTiers, "a resume picks by the same tier")
        pods.stop(tiered)
    }

    @Test
    fun `a user message is one stream-json user line`() {
        assertEquals("""{"type":"user","message":{"role":"user","content":"file it"}}""" + "\n", KubernetesFirstMateSessionPods.userLine("file it"))
    }

    @Test
    fun `the Claude id is read from claude's init line only`() {
        assertEquals("0b5e-77", KubernetesFirstMateSessionPods.claudeId(mapOf("type" to "system", "subtype" to "init", "session_id" to "0b5e-77")))
        assertNull(KubernetesFirstMateSessionPods.claudeId(mapOf("type" to "assistant", "session_id" to "x")))
    }

    @Test
    fun `a resumed session's relay restores itself, and resuming frames go out until the new pod's first outbox line`() {
        val frozen = session.copy(claudeSessionId = "0b5e-77", cwd = FIRST_MATE_CWD)
        EventRecorder(bus, "acme/tickets").use { rec ->
            val pods = pods()
            pods.start(frozen, mcp, FirstMateSnapshot("{}".toByteArray(), null, 10))
            val container = pod(frozen).spec.containers.single()
            assertEquals(listOf("fm-relay", "--", "claude"), container.command.take(3))
            assertEquals("1", container.env.single { it.name == "FM_RESUME" }.value)
            val resuming = rec.of("first-mate", Duration.ofSeconds(1))
            assertTrue(resuming.isNotEmpty())
            assertEquals("resuming", resuming[0].data["line"]["type"].asString())
            assertEquals(0L, resuming[0].long("seq"))

            firstLine = true
            Thread.sleep(200)
            val before = rec.of("first-mate", Duration.ofMillis(100)).count { it.data["line"]["type"].asString() == "resuming" }
            Thread.sleep(300)
            val after = rec.of("first-mate", Duration.ofMillis(100)).count { it.data["line"]["type"].asString() == "resuming" }
            assertEquals(before, after, "no resuming frame after the pod's first outbox line")
            pods.stop(frozen)
        }
    }
}

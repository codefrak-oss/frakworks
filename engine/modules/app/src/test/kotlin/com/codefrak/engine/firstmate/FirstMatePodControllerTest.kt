package com.codefrak.engine.firstmate

import com.codefrak.engine.ProcessFakes
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.EngineTokens
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.util.Base64
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `/first-mate/pod/{id}/` (engine/README.md, "First-mate sessions", "The pod relay protocol"):
 * the inbox in seq order and its long-poll, the monotonic ack, the idempotent outbox per pod epoch, status, snapshot,
 * and the session's own pod token only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ProcessFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class FirstMatePodControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var tokens: EngineTokens
    @Autowired lateinit var access: Access
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var sessions: FirstMateSessions
    @Autowired lateinit var snapshots: FirstMateSnapshots

    private val json = jacksonObjectMapper()
    private lateinit var session: FirstMateSession
    private lateinit var pod: String

    @BeforeEach fun setUp() {
        listOf("engine.first_mate_snapshot", "engine.first_mate_message", "engine.first_mate_session").forEach { jdbc.sql("DELETE FROM $it").update() }
        if (!access.trusted("alice")) access.add("alice", "test")
        session = sessions.start("alice", "phone-token", "acme/firstmate")
        pod = tokens.mint("alice", "pod", null, "engine", EngineTokens.FIRST_MATE_POD, session.id).token
    }

    private fun base() = "/first-mate/pod/${session.id}"
    private fun post(path: String, body: String, token: String = pod): MvcResult = mvc.post(path) {
        contentType = MediaType.APPLICATION_JSON; content = body; header("Authorization", "Bearer $token")
    }.andReturn()
    private fun get(path: String, token: String = pod): MvcResult = mvc.get(path) { header("Authorization", "Bearer $token") }.andReturn()
    private fun <T> body(r: MvcResult): T = json.readValue<Any?>(r.response.contentAsString) as T
    private fun inbox(after: Long, wait: Int = 0): List<Map<String, Any?>> = body(get("${base()}/inbox?after=$after&wait=$wait"))

    @Test
    fun `the inbox answers the phone's messages after a seq, oldest first, and nothing else`() {
        sessions.append(session.id, "system", mapOf("text" to "first line"))
        val one = sessions.appendUser(session.id, mapOf("text" to "one"), null).first
        sessions.append(session.id, "assistant", mapOf("type" to "x"))
        val two = sessions.appendUser(session.id, mapOf("text" to "two"), null).first
        assertEquals(listOf(one.seq, two.seq), inbox(0).map { it["seq"] })
        assertEquals(listOf(two.seq), inbox(one.seq.toLong()).map { it["seq"] })
        assertEquals(emptyList(), inbox(two.seq.toLong()))
    }

    @Test
    fun `a long poll wakes on a posted message, and times out empty`() {
        val started = System.nanoTime()
        assertEquals(emptyList(), inbox(0, wait = 1))
        assertTrue(System.nanoTime() - started >= 900_000_000, "it waited")
        val waiting = CompletableFuture.supplyAsync { System.nanoTime() to inbox(0, wait = 20) }
        Thread.sleep(300)
        val phone = tokens.mint("alice", "iPhone", null, "alice", EngineTokens.FIRST_MATE).token
        val posted = System.nanoTime()
        assertEquals(201, post("/first-mate/sessions/${session.id}/messages", """{"text":"wake up"}""", phone).response.status)
        val (_, rows) = waiting.get()
        assertEquals(listOf(mapOf("text" to "wake up")), rows.map { it["body"] })
        assertTrue(System.nanoTime() - posted < 1_500_000_000, "woken by the frame, not the 2 s backstop")
    }

    @Test
    fun `a freezing session's inbox answers freeze`() {
        jdbc.sql("UPDATE engine.first_mate_session SET state = 'freezing' WHERE id = :id").param("id", session.id).update()
        assertEquals(mapOf("freeze" to true), body<Map<String, Any?>>(get("${base()}/inbox?after=0&wait=5")))
    }

    @Test
    fun `ack is monotonic`() {
        assertEquals(5, body<Map<String, Any?>>(post("${base()}/inbox/ack", """{"seq":5}"""))["deliveredSeq"])
        assertEquals(5, body<Map<String, Any?>>(post("${base()}/inbox/ack", """{"seq":3}"""))["deliveredSeq"])
        assertEquals(5L, sessions.get(session.id)!!.deliveredSeq)
        assertEquals(400, post("${base()}/inbox/ack", """{"seq":"x"}""").response.status)
    }

    @Test
    fun `a retried outbox batch stores nothing twice, an overlapping one only its new lines, and a new epoch starts over`() {
        val batch = """{"from":1,"lines":["{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"claude-1\"}","plain text"]}"""
        assertEquals(2, body<Map<String, Any?>>(post("${base()}/outbox", batch))["accepted"])
        assertEquals(2, body<Map<String, Any?>>(post("${base()}/outbox", batch))["accepted"])
        assertEquals(3, body<Map<String, Any?>>(post("${base()}/outbox", """{"from":2,"lines":["plain text","third"]}"""))["accepted"])
        val rows = sessions.transcript(session.id)
        assertEquals(listOf("system", "plain text", "third"), rows.map { (it.body as? Map<*, *>)?.get("type") ?: it.body })
        assertEquals("claude-1", sessions.get(session.id)!!.claudeSessionId)

        assertEquals(1, sessions.nextEpoch(session.id))
        assertEquals(1, body<Map<String, Any?>>(post("${base()}/outbox", """{"from":1,"lines":["new pod"]}"""))["accepted"])
        assertEquals(4, sessions.transcript(session.id).size)
        assertEquals(400, post("${base()}/outbox", """{"from":0,"lines":["x"]}""").response.status)
    }

    @Test
    fun `seqs stay unique when outbox lines and phone messages interleave`() {
        val (stored, accepted) = sessions.appendOutbox(session.id, 1, listOf("a", "b"))
        val user = sessions.appendUser(session.id, mapOf("text" to "q"), "c").first
        val (more, _) = sessions.appendOutbox(session.id, 2, listOf("b", "c"))
        assertEquals(2, accepted)
        assertEquals(listOf(1, 2, 3, 4), (stored + user + more).map { it.seq })
        assertEquals(listOf(1, 2, 3, 4), sessions.transcript(session.id).map { it.seq })
        assertEquals(false, sessions.appendUser(session.id, mapOf("text" to "q"), "c").second)
    }

    @Test
    fun `status is a system row, and snapshot answers what a resume restores`() {
        assertEquals(200, post("${base()}/status", """{"state":"exited","code":3}""").response.status)
        assertEquals(mapOf("type" to "pod-status", "state" to "exited", "code" to 3), sessions.transcript(session.id).last().body)
        assertEquals(400, post("${base()}/status", """{"state":"napping"}""").response.status)

        assertEquals(404, get("${base()}/snapshot").response.status)
        snapshots.save(session.id, FirstMateSnapshot("file".toByteArray(), "tar".toByteArray(), 3))
        val snap = body<Map<String, Any?>>(get("${base()}/snapshot"))
        assertEquals("file", String(Base64.getDecoder().decode(snap["sessionFile"] as String)))
        assertEquals("tar", String(Base64.getDecoder().decode(snap["workdirTar"] as String)))
    }

    @Test
    fun `another session's pod token, a phone token and no token are refused`() {
        val other = sessions.start("alice", "phone-token", "acme/firstmate")
        val otherPod = tokens.mint("alice", "pod", null, "engine", EngineTokens.FIRST_MATE_POD, other.id).token
        assertEquals(403, get("${base()}/inbox?wait=0", otherPod).response.status)
        assertEquals(403, get("${base()}/inbox?wait=0", tokens.mint("alice", "iPhone", null, "alice", EngineTokens.FIRST_MATE).token).response.status)
        assertEquals(200, get("${base()}/inbox?wait=0").response.status)
        assertEquals(1, tokens.revokeForSession(session.id))
        assertEquals(401, get("${base()}/inbox?wait=0").response.status)
    }
}

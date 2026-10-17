package com.codefrak.engine.firstmate

import com.codefrak.engine.ProcessFakes
import com.codefrak.engine.TestTokens
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.events.EventRecorder
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.EngineTokens
import com.codefrak.engine.repos.TargetRepos
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockingDetails
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** `/first-mate/sessions` (engine/README.md, "First-mate sessions"): first-mate tokens only, target repos only, owner only. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ProcessFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class FirstMateSessionControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var tokens: EngineTokens
    @Autowired lateinit var access: Access
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var sessions: FirstMateSessions
    @Autowired lateinit var snapshots: FirstMateSnapshots
    @MockitoSpyBean lateinit var pods: FirstMateSessionPods
    @Autowired lateinit var bus: EngineEvents

    private val json = jacksonObjectMapper()
    private val repo = "acme/firstmate"

    @BeforeEach fun setUp() {
        listOf("engine.first_mate_message", "engine.first_mate_session").forEach { jdbc.sql("DELETE FROM $it").update() }
        repos.remove(repo); repos.add(repo)
        listOf("alice", "bob").forEach { if (!access.trusted(it)) access.add(it, "test") }
    }
    @AfterEach fun tearDown() { repos.remove(repo) }

    private fun phone(login: String) = tokens.mint(login, "iPhone", null, login, EngineTokens.FIRST_MATE).token
    private fun laptop(login: String) = tokens.mint(login, "laptop", null, login).token

    private fun post(path: String, body: String, token: String): MvcResult = mvc.post(path) {
        contentType = MediaType.APPLICATION_JSON; content = body; header("Authorization", "Bearer $token")
    }.andReturn()
    private fun get(path: String, token: String): MvcResult = mvc.get(path) { header("Authorization", "Bearer $token") }.andReturn()
    private fun delete(path: String, token: String): MvcResult = mvc.delete(path) { header("Authorization", "Bearer $token") }.andReturn()
    private fun body(r: MvcResult): Map<String, Any?> = json.readValue(r.response.contentAsString)

    private fun podToken(login: String, sessionId: String) = tokens.mint(login, "first-mate pod $sessionId", null, "engine", EngineTokens.FIRST_MATE_POD, sessionId).token

    @Test
    fun `a plain sign-in bearer starts, posts a message, reads the transcript and ends a session`() {
        val alice = laptop("alice")
        val started = post("/first-mate/sessions", """{"repo":"$repo"}""", alice)
        assertEquals(201, started.response.status, started.response.contentAsString)
        val id = body(started)["id"] as String
        assertEquals("alice", sessions.get(id)!!.login)
        assertEquals(tokens.list().first { it.purpose == null && it.login == "alice" && it.name == "laptop" }.id, sessions.get(id)!!.tokenId)
        assertEquals(201, post("/first-mate/sessions/$id/messages", """{"text":"hello from the desktop"}""", alice).response.status)
        val t = body(get("/first-mate/sessions/$id/transcript", alice))
        @Suppress("UNCHECKED_CAST") val messages = t["messages"] as List<Map<String, Any?>>
        assertEquals(listOf(mapOf("text" to "hello from the desktop")), messages.map { it["body"] })
        assertEquals(204, delete("/first-mate/sessions/$id", alice).response.status)
        assertEquals("ended", body(get("/first-mate/sessions/$id/transcript", alice))["state"])
    }

    @Test
    fun `a first-mate-pod token is 403 on starting a session and on its transcript, messages and end`() {
        val alice = phone("alice")
        val id = body(post("/first-mate/sessions", """{"repo":"$repo"}""", alice))["id"] as String
        val pod = podToken("alice", id)
        for (r in listOf(
            post("/first-mate/sessions", """{"repo":"$repo"}""", pod),
            get("/first-mate/sessions/$id/transcript", pod),
            post("/first-mate/sessions/$id/messages", """{"text":"hi"}""", pod),
            delete("/first-mate/sessions/$id", pod),
        )) assertEquals(403, r.response.status, r.response.contentAsString)
        assertEquals("open", sessions.get(id)!!.state)
    }

    @Test
    fun `no token is 401 on starting a session and on its transcript, messages and end`() {
        val id = body(post("/first-mate/sessions", """{"repo":"$repo"}""", phone("alice")))["id"] as String
        assertEquals(401, mvc.post("/first-mate/sessions") { contentType = MediaType.APPLICATION_JSON; content = """{"repo":"$repo"}"""; with(TestTokens.noToken) }.andReturn().response.status)
        assertEquals(401, mvc.get("/first-mate/sessions/$id/transcript") { with(TestTokens.noToken) }.andReturn().response.status)
        assertEquals(401, mvc.post("/first-mate/sessions/$id/messages") { contentType = MediaType.APPLICATION_JSON; content = """{"text":"hi"}"""; with(TestTokens.noToken) }.andReturn().response.status)
        assertEquals(401, mvc.delete("/first-mate/sessions/$id") { with(TestTokens.noToken) }.andReturn().response.status)
    }

    @Test
    fun `a repo that is not a target repo is 400 naming the target repos`() {
        val r = post("/first-mate/sessions", """{"repo":"acme/nope"}""", phone("alice"))
        assertEquals(400, r.response.status)
        assertContains(r.response.errorMessage ?: "", "acme/nope is not a target repo")
        assertContains(r.response.errorMessage ?: "", repo)
    }

    @Test
    fun `the owner starts, posts, reads and ends a session, and another login gets 404 for it`() {
        val alice = phone("alice")
        val started = post("/first-mate/sessions", """{"repo":"$repo"}""", alice)
        assertEquals(201, started.response.status, started.response.contentAsString)
        val id = body(started)["id"] as String
        assertEquals("open", body(started)["state"]); assertEquals(repo, body(started)["repo"])

        assertEquals(201, post("/first-mate/sessions/$id/messages", """{"text":"what's on the board?"}""", alice).response.status)
        val t = body(get("/first-mate/sessions/$id/transcript", alice))
        @Suppress("UNCHECKED_CAST") val messages = t["messages"] as List<Map<String, Any?>>
        assertEquals(listOf("user"), messages.map { it["role"] })
        assertEquals(mapOf("text" to "what's on the board?"), messages[0]["body"])

        val bob = phone("bob")
        assertEquals(404, get("/first-mate/sessions/$id/transcript", bob).response.status)
        assertEquals(404, post("/first-mate/sessions/$id/messages", """{"text":"hi"}""", bob).response.status)
        assertEquals(404, delete("/first-mate/sessions/$id", bob).response.status)

        assertEquals(204, delete("/first-mate/sessions/$id", alice).response.status)
        assertEquals(200, delete("/first-mate/sessions/$id", alice).response.status)
        assertEquals(409, post("/first-mate/sessions/$id/messages", """{"text":"hi"}""", alice).response.status)
        assertEquals("ended", body(get("/first-mate/sessions/$id/transcript", alice))["state"])
    }

    @Test
    fun `a retried message with the same clientId stores one row and answers it again`() {
        val alice = phone("alice")
        val id = body(post("/first-mate/sessions", """{"repo":"$repo"}""", alice))["id"] as String
        val first = post("/first-mate/sessions/$id/messages", """{"text":"hi","clientId":"c-1"}""", alice)
        assertEquals(201, first.response.status, first.response.contentAsString)
        val again = post("/first-mate/sessions/$id/messages", """{"text":"hi","clientId":"c-1"}""", alice)
        assertEquals(200, again.response.status)
        assertEquals(body(first)["seq"], body(again)["seq"])
        assertEquals(201, post("/first-mate/sessions/$id/messages", """{"text":"hi","clientId":"c-2"}""", alice).response.status)
        assertEquals(201, post("/first-mate/sessions/$id/messages", """{"text":"hi"}""", alice).response.status)
        assertEquals(3, sessions.transcript(id).count { it.role == "user" })
        assertEquals(400, post("/first-mate/sessions/$id/messages", """{"text":"hi","clientId":7}""", alice).response.status)
    }

    @Test
    fun `tier is optional, stored on the session and handed to the pod, and one outside the map or not a whole number is 400 naming it`() {
        val alice = phone("alice")
        val started = post("/first-mate/sessions", """{"repo":"$repo","tier":3}""", alice)
        assertEquals(201, started.response.status, started.response.contentAsString)
        assertEquals(3, body(started)["tier"])
        val id = body(started)["id"] as String
        assertEquals(3, sessions.get(id)!!.tier)
        val start = mockingDetails(pods).invocations.single { it.method.name == "start" && (it.arguments[0] as FirstMateSession).id == id }
        assertEquals(3, (start.arguments[0] as FirstMateSession).tier)

        val plain = post("/first-mate/sessions", """{"repo":"$repo"}""", alice)
        assertEquals(201, plain.response.status)
        assertEquals(null, body(plain)["tier"])
        assertEquals(null, sessions.get(body(plain)["id"] as String)!!.tier)

        for (tier in listOf("11", "0", "\"8\"", "2.5", "null")) {
            val r = post("/first-mate/sessions", """{"repo":"$repo","tier":$tier}""", alice)
            if (tier == "null") { assertEquals(201, r.response.status, tier); continue }
            assertEquals(400, r.response.status, tier)
            assertContains(r.response.errorMessage ?: "", "tier")
        }
    }

    @Test
    fun `images go with a message under the allowlist, the row keeps them and the frame carries their count only`() {
        val alice = phone("alice")
        val id = body(post("/first-mate/sessions", """{"repo":"$repo"}""", alice))["id"] as String
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
        val image = """{"media_type":"image/png","data":"$png"}"""
        EventRecorder(bus, repo).use { rec ->
            val r = post("/first-mate/sessions/$id/messages", """{"text":"what is this?","images":[$image]}""", alice)
            assertEquals(201, r.response.status, r.response.contentAsString)
            val frame = rec.await("first-mate") { it.str("sessionId") == id }
            assertEquals("user", frame.data["line"]["type"].asString())
            assertEquals("what is this?", frame.data["line"]["text"].asString())
            assertEquals(1, frame.data["line"]["images"].asInt())
            assertEquals(false, frame.data.toString().contains(png), "the frame never carries the data")
        }
        val row = sessions.transcript(id).single { it.role == "user" }
        assertEquals(mapOf("text" to "what is this?", "images" to listOf(mapOf("media_type" to "image/png", "data" to png))), row.body)
        assertEquals(mapOf("text" to "what is this?", "images" to listOf(mapOf("media_type" to "image/png", "data" to png))), sessions.userAfter(id, 0).single().body)

        val six = List(6) { image }.joinToString(",")
        for ((images, word) in listOf("[$six]" to "6 images", """[{"media_type":"text/plain","data":"$png"}]""" to "text/plain",
                """[{"media_type":"image/jpeg","data":"$png"}]""" to "not a JPEG file", "\"x\"" to "images must be a list")) {
            val r = post("/first-mate/sessions/$id/messages", """{"text":"hi","images":$images}""", alice)
            assertEquals(400, r.response.status, word)
            assertContains(r.response.errorMessage ?: "", word)
        }
        assertEquals(1, sessions.transcript(id).count { it.role == "user" })
        val plain = post("/first-mate/sessions/$id/messages", """{"text":"no images","images":[]}""", alice)
        assertEquals(201, plain.response.status)
        assertEquals(mapOf("text" to "no images"), sessions.transcript(id).last().body)
    }

    @Test
    fun `a message to a frozen session thaws it, starts a pod from its snapshot, notes a dropped folder, then forwards the message`() {
        val alice = phone("alice")
        val id = body(post("/first-mate/sessions", """{"repo":"$repo","tier":2}""", alice))["id"] as String
        sessions.setClaudeSessionId(id, "0b5e-77")
        val file = "{}".toByteArray()
        snapshots.save(id, FirstMateSnapshot(file, null, FirstMateSnapshot.CAP_BYTES + 1))
        jdbc.sql("UPDATE engine.first_mate_session SET state = 'frozen', frozen_at = CURRENT_TIMESTAMP, workdir_dropped = TRUE WHERE id = :id").param("id", id).update()
        assertEquals("frozen", body(get("/first-mate/sessions/$id/transcript", alice))["state"])

        assertEquals(201, post("/first-mate/sessions/$id/messages", """{"text":"where were we?"}""", alice).response.status)
        val t = body(get("/first-mate/sessions/$id/transcript", alice))
        assertEquals("open", t["state"])
        @Suppress("UNCHECKED_CAST") val messages = t["messages"] as List<Map<String, Any?>>
        assertEquals(listOf("system", "user"), messages.map { it["role"] })
        assertEquals(mapOf("text" to FirstMateSessionController.DROPPED_NOTE), messages[0]["body"])

        val calls = mockingDetails(pods).invocations.map { it.method.name to it.arguments }
        val starts = calls.filter { it.first == "start" && (it.second[0] as FirstMateSession).id == id }
        assertEquals(2, starts.size, "the session's first pod and the resumed one")
        val restore = starts[1].second[2] as FirstMateSnapshot
        assertEquals("0b5e-77", (starts[1].second[0] as FirstMateSession).claudeSessionId)
        assertEquals(2, (starts[1].second[0] as FirstMateSession).tier, "the resumed pod runs at the session's tier")
        assertEquals(file.toList(), restore.sessionFile!!.toList())
    }
}

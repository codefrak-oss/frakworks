package com.codefrak.engine.deploy

import com.codefrak.engine.ProcessFakes
import com.codefrak.engine.TestTokens
import com.codefrak.engine.chore.ScriptedSandbox
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.repos.TargetRepos
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `POST` and `GET /repos/{owner}/{name}/deploys` (#100008): the row, the `deploy` frame and the `DeployWaits` hook. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ProcessFakes::class, DeployControllerTest.Waits::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class DeployControllerTest {
    /** A registered hook: what the flow part will add; here it records what woke it. */
    class RecordingWaits : DeployWaits {
        val woken = mutableListOf<Pair<String, String>>()
        override fun wake(repo: String, sha: String) { woken += repo to sha }
    }
    @TestConfiguration
    class Waits { @Bean fun recordingWaits() = RecordingWaits() }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var deploys: DeployEvents
    @Autowired lateinit var events: EngineEvents
    @Autowired lateinit var waits: RecordingWaits
    @Autowired lateinit var sandbox: ScriptedSandbox

    private val json = jacksonObjectMapper()
    private val repo = "acme/deploys"
    private val now = Instant.parse("2026-10-13T12:00:00Z")
    private val smithAddress = "172.18.0.9"

    @BeforeEach fun setUp() {
        repos.remove(repo); repos.add(repo); waits.woken.clear()
        sandbox.reset(); sandbox.callers[smithAddress] = SmithCaller("run-9", 9, repo)
        jdbc.sql("DELETE FROM engine.deploy_event WHERE repo = :r").param("r", repo).update()
        deploys.clock = Clock.fixed(now, ZoneOffset.UTC)
    }
    @AfterEach fun tearDown() { repos.remove(repo); deploys.clock = Clock.systemUTC() }

    private fun post(body: String, path: String = repo): MvcResult = mvc.post("/repos/$path/deploys") {
        contentType = MediaType.APPLICATION_JSON; content = body
    }.andReturn()

    private fun list(query: String = ""): List<Map<String, Any?>> = json.readValue(mvc.get("/repos/$repo/deploys$query").andExpect { status { isOk() } }.andReturn().response.contentAsString)

    @Test
    fun `a deploy is recorded, listed newest first, published as a deploy frame and wakes the hook`() {
        val sub = events.subscribe(repo)
        val created = post("""{"sha":"ABCDEF0123456789abcdef0123456789abcdef01","source":"github-actions"}""")
        assertEquals(201, created.response.status, created.response.errorMessage)
        val row: Map<String, Any?> = json.readValue(created.response.contentAsString)
        assertEquals("abcdef0123456789abcdef0123456789abcdef01", row["sha"], "lowercased"); assertEquals("github-actions", row["source"]); assertEquals("2026-10-13T12:00:00Z", row["at"])

        val frame = assertNotNull(sub.next(Duration.ofSeconds(5)), "the deploy frame")
        assertTrue(frame.startsWith("event: deploy\ndata: "), frame)
        val data: Map<String, Any?> = json.readValue(frame.removePrefix("event: deploy\ndata: ").trim())
        assertEquals(mapOf("repo" to repo, "sha" to "abcdef0123456789abcdef0123456789abcdef01", "source" to "github-actions", "at" to "2026-10-13T12:00:00Z"), data)
        assertEquals(listOf(repo to "abcdef0123456789abcdef0123456789abcdef01"), waits.woken)

        deploys.clock = Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC)
        assertEquals(201, post("""{"sha":"1234567"}""").response.status)
        assertEquals(listOf("1234567", "abcdef0123456789abcdef0123456789abcdef01"), list().map { it["sha"] }, "newest first")
        assertNull(list().first()["source"], "no source: null")
        assertEquals(listOf("1234567"), list("?limit=1").map { it["sha"] })
        assertEquals(400, mvc.get("/repos/$repo/deploys?limit=0").andReturn().response.status)
    }

    @Test
    fun `a non-target repo is 404, a bad sha or body 400, and no token 401`() {
        assertEquals(404, post("""{"sha":"abcdef0"}""", path = "acme/nowhere").response.status)
        mvc.get("/repos/acme/nowhere/deploys").andExpect { status { isNotFound() } }
        for (body in listOf("""{}""", """{"sha":"abc"}""", """{"sha":"xyzxyzx"}""", """{"sha":""}""", """{"sha":7}""")) {
            val r = post(body)
            assertEquals(400, r.response.status, body); assertEquals(DeployEvents.SHA_400, r.response.errorMessage, body)
        }
        assertEquals(400, post("""{"sha":"abcdef0","by":"me"}""").response.status, "an unknown field")
        assertEquals(400, post("""{"sha":"abcdef0","source":3}""").response.status, "source is a string")
        assertEquals(401, mvc.post("/repos/$repo/deploys") { contentType = MediaType.APPLICATION_JSON; content = """{"sha":"abcdef0"}"""; with(TestTokens.noToken) }.andReturn().response.status)
        assertEquals(emptyList(), list())
        assertEquals(emptyList(), waits.woken)
    }

    @Test
    fun `a Smith sandbox gets 403 on the post and the list, it may not mark its own merge deployed`() {
        val asSmith = RequestPostProcessor { it.remoteAddr = smithAddress; it }
        val r = mvc.post("/repos/$repo/deploys") { contentType = MediaType.APPLICATION_JSON; content = """{"sha":"abcdef0"}"""; with(asSmith) }.andReturn()
        assertEquals(403, r.response.status)
        assertContains(r.response.errorMessage!!, "a Smith sandbox (run run-9) may not record a repo's deploy")
        mvc.get("/repos/$repo/deploys") { with(asSmith) }.andExpect { status { isForbidden() } }
        assertEquals(emptyList(), list(), "nothing recorded")
        assertEquals(emptyList(), waits.woken, "nothing woken")
    }
}

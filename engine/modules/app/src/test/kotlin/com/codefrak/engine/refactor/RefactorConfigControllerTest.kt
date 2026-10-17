package com.codefrak.engine.refactor

import com.codefrak.engine.TestTokens
import com.codefrak.engine.ProcessFakes
import com.codefrak.engine.chore.ScriptedSandbox
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.events.EventRecorder
import com.codefrak.engine.jobs.JobKind
import com.codefrak.engine.jobs.JobOwner
import com.codefrak.engine.jobs.JobStore
import com.codefrak.engine.jobs.Signal
import com.codefrak.engine.mcp.EngineMcpController
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.SmithCaller
import org.junit.jupiter.api.AfterEach
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
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `GET`/`PUT /repos/{owner}/{name}/refactor` (engine/README.md, "Refactoring jobs") over MockMvc, and the one system job it keeps. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ProcessFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class RefactorConfigControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var store: JobStore
    @Autowired lateinit var configs: RefactorConfigStore
    @Autowired lateinit var sandbox: ScriptedSandbox
    @Autowired lateinit var bus: EngineEvents
    @Autowired lateinit var jdbc: JdbcClient

    private val json = jacksonObjectMapper()
    private val repo = "acme/refcfg"
    private val login = "alice"
    private val smithAddress = "172.18.0.7"

    @BeforeEach fun setUp() {
        repos.remove(repo); repos.add(repo)
        sandbox.reset(); sandbox.callers[smithAddress] = SmithCaller("run-7", 7, repo)
    }
    @AfterEach fun tearDown() { repos.remove(repo) }

    private fun put(body: String, who: String? = login, from: String? = null): MvcResult = mvc.put("/repos/$repo/refactor") {
        contentType = MediaType.APPLICATION_JSON; content = body
        with(who?.let { TestTokens.bearer(it) } ?: TestTokens.noToken)
        if (from != null) with(RequestPostProcessor { it.remoteAddr = from; it })
    }.andReturn()

    private fun ok(body: String, who: String? = login): Map<String, Any?> {
        val result = put(body, who)
        assertEquals(200, result.response.status, result.response.errorMessage ?: result.response.contentAsString)
        return json.readValue(result.response.contentAsString)
    }

    private fun get(): Map<String, Any?> = json.readValue(mvc.get("/repos/$repo/refactor").andExpect { status { isOk() } }.andReturn().response.contentAsString)

    @Test
    fun `GET on a repo with no row answers the defaults with the shipped values, and 404 for a non-target repo`() {
        val view = get()
        assertEquals(mapOf("repo" to repo, "enabled" to false, "schedule" to 604800, "values" to RefactorValues.DEFAULT, "valuesDefault" to true,
            "maxTickets" to 2, "maxLines" to 300, "approval" to "human", "jobId" to null, "updatedAt" to null, "updatedBy" to null, "tier" to null), view)
        mvc.get("/repos/acme/nowhere/refactor").andExpect { status { isNotFound() } }
        mvc.put("/repos/acme/nowhere/refactor") { contentType = MediaType.APPLICATION_JSON; content = """{"enabled":true}""" }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `enabling creates exactly one system job, a second enable none, a schedule change rewrites its signal, and disabling deletes it`() = EventRecorder(bus, repo).use { rec ->
        val enabled = ok("""{"enabled":true}""")
        val jobId = enabled["jobId"] as String
        assertEquals(true, enabled["enabled"]); assertEquals(login, enabled["updatedBy"]); assertNotNull(enabled["updatedAt"])
        val job = store.get(jobId)!!
        assertEquals(JobOwner.SYSTEM, job.owner); assertEquals(JobKind.REFACTOR, job.kind); assertEquals(repo, job.repo)
        assertEquals(Signal.Every(604800), job.parsedSignal()); assertEquals("refactor $repo", job.name); assertEquals(login, job.createdBy)
        val listed = json.readValue<List<Map<String, Any?>>>(mvc.get("/jobs?repo=$repo").andReturn().response.contentAsString)
        assertEquals(listOf(jobId to "system"), listed.map { it["id"] to it["owner"] })

        assertEquals(jobId, ok("""{"enabled":true}""")["jobId"], "the same job")
        assertEquals(1, store.list(repo).size, "no second job")

        assertEquals(86400, ok("""{"schedule":86400}""")["schedule"])
        assertEquals(Signal.Every(86400), store.get(jobId)!!.parsedSignal(), "the job's signal follows the schedule")
        assertEquals(jobId, get()["jobId"])

        val disabled = ok("""{"enabled":false}""")
        assertNull(disabled["jobId"]); assertEquals(false, disabled["enabled"])
        assertNull(store.get(jobId), "disabling deletes the system job")
        assertEquals(86400, get()["schedule"], "the other settings stay")
        assertNull(configs.row(repo)!!.jobId)

        val frames = rec.of("repo", Duration.ofSeconds(2)).filter { it.str("repo") == repo }
        assertEquals(4, frames.size, frames.toString())
        frames.forEach { assertEquals("changed", it.state); assertEquals(listOf("refactor"), it.data["fields"]!!.toList().map { f -> f.asString() }) }
    }

    @Test
    fun `values are stored as given and null returns to the shipped text, every key is optional and absent keeps the value`() {
        val own = ok("""{"values":"# Ours\n\n- Fewer layers.\n","maxTickets":5,"maxLines":120,"approval":"none"}""")
        assertEquals("# Ours\n\n- Fewer layers.\n", own["values"]); assertEquals(false, own["valuesDefault"])
        assertEquals(5, own["maxTickets"]); assertEquals(120, own["maxLines"]); assertEquals("none", own["approval"]); assertEquals(false, own["enabled"])
        val row = configs.row(repo)!!
        assertEquals("# Ours\n\n- Fewer layers.\n", row.values); assertEquals(RefactorApproval.NONE, row.approval)
        assertEquals("# Ours\n\n- Fewer layers.\n", jdbc.sql("SELECT values_text FROM engine.refactor_config WHERE repo = :r").param("r", repo).query(String::class.java).single())

        val kept = ok("""{"approval":"human"}""")
        assertEquals(5, kept["maxTickets"]); assertEquals("# Ours\n\n- Fewer layers.\n", kept["values"]); assertEquals("human", kept["approval"])

        val shipped = ok("""{"values":null}""")
        assertEquals(RefactorValues.DEFAULT, shipped["values"]); assertEquals(true, shipped["valuesDefault"])
        assertNull(jdbc.sql("SELECT values_text FROM engine.refactor_config WHERE repo = :r").param("r", repo).query(String::class.java).optional().orElse(null),
            "NULL in the row: a later edit of the shipped text reaches the repo")
        assertEquals(401, put("""{"maxLines":10}""", who = null).response.status, "no token: refused before the controller")
        assertEquals(login, ok("""{"maxLines":10}""")["updatedBy"])
    }

    @Test
    fun `tier is stored and answered, absent keeps it, and null clears it`() {
        assertEquals(7, ok("""{"tier":7}""")["tier"])
        assertEquals(7, configs.row(repo)!!.tier)
        assertEquals(7, ok("""{"maxTickets":4}""")["tier"], "absent keeps the tier")
        assertNull(ok("""{"tier":null}""")["tier"])
        assertNull(configs.row(repo)!!.tier)
    }

    @Test
    fun `each field is validated with a 400 naming the reason, and nothing changes`() {
        ok("""{"maxTickets":3}""")
        val cases = listOf(
            """{"schedule":59}""" to "schedule is a whole number of seconds, at least 60",
            """{"schedule":"daily"}""" to "schedule is a whole number of seconds, at least 60",
            """{"maxTickets":0}""" to "maxTickets is a whole number from 1 to 20",
            """{"maxTickets":21}""" to "maxTickets is a whole number from 1 to 20",
            """{"maxLines":5001}""" to "maxLines is a whole number from 10 to 5000",
            """{"maxLines":9}""" to "maxLines is a whole number from 10 to 5000",
            """{"approval":"maybe"}""" to "approval is human or none, not 'maybe'",
            """{"enabled":"yes"}""" to "enabled is true or false",
            """{"values":"   "}""" to "values is a non-blank text of at most 20000 characters, or null for the shipped text",
            """{"values":42}""" to "values is a non-blank text of at most 20000 characters, or null for the shipped text",
            """{"colour":"blue"}""" to "unknown field(s): colour; only enabled, schedule, values, maxTickets, maxLines, approval, tier",
            """[1]""" to "the body is an object with enabled, schedule, values, maxTickets, maxLines, approval and tier",
            """{"tier":0}""" to TIER_REASON,
            """{"tier":11}""" to TIER_REASON,
            """{"tier":"high"}""" to TIER_REASON,
            """{"tier":5.5}""" to TIER_REASON,
        )
        for ((body, reason) in cases) {
            val result = put(body)
            assertEquals(400, result.response.status, body)
            assertEquals(reason, result.response.errorMessage, body)
        }
        assertEquals(3, get()["maxTickets"]); assertEquals(false, get()["enabled"]); assertEquals(emptyList(), store.list(repo))
    }

    private companion object {
        const val TIER_REASON = "tier is a whole number from 1 to 10, or null for the job run's default"
    }

    @Test
    fun `a Smith sandbox may not write, Docker not answering is 503, and the read is open to it`() {
        val smith = put("""{"enabled":true}""", from = smithAddress)
        assertEquals(403, smith.response.status)
        assertContains(smith.response.errorMessage!!, "a Smith sandbox (run run-7) may not set a repo's refactoring")
        assertEquals(emptyList(), store.list(repo)); assertNull(configs.row(repo))
        sandbox.runAtFails = true
        assertEquals(503, put("""{"enabled":true}""", from = "10.0.0.9").response.status)
        sandbox.runAtFails = false
        mvc.get("/repos/$repo/refactor") { with(RequestPostProcessor { it.remoteAddr = smithAddress; it }) }.andExpect { status { isOk() } }
        assertTrue(get()["enabled"] == false)
    }
}

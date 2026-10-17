package com.codefrak.engine.camunda

import com.codefrak.engine.TestTokens
import com.codefrak.engine.ProcessFakes
import com.codefrak.engine.accounts.AiAccounts
import com.codefrak.engine.chore.FlowStarter
import com.codefrak.engine.chore.ScriptedSandbox
import com.codefrak.engine.github.IssueSnapshot
import com.codefrak.engine.mcp.FrakworksEngine
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.Finding
import com.codefrak.engine.smith.ReviewResult
import com.codefrak.engine.smith.SmithRunStat
import com.codefrak.engine.smith.SmithRunStats
import com.codefrak.engine.smith.Ticket
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.ensureTestAccount
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.impl.util.ClockUtil
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.lang.reflect.Proxy
import java.time.OffsetDateTime
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The timeline served from the projection (#1966): no `HistoryService` query while serving a ticket, counted through a
 * `@Primary` proxy; the backfill reads back identically; the per-occurrence rows the history could not keep.
 */
@SpringBootTest
@Import(ProcessFakes::class, TicketTimelineProjectionTest.CountingHistory::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class TicketTimelineProjectionTest {
    /** The real history service behind a proxy that counts every query it is asked to create. */
    @TestConfiguration
    class CountingHistory {
        companion object { val queries = AtomicInteger() }
        @Bean @Primary fun countingHistoryService(engine: org.operaton.bpm.engine.ProcessEngine): HistoryService {
            val real = engine.historyService
            return Proxy.newProxyInstance(HistoryService::class.java.classLoader, arrayOf(HistoryService::class.java)) { _, method, args ->
                if (method.name.startsWith("create")) queries.incrementAndGet()
                method.invoke(real, *(args ?: emptyArray()))
            } as HistoryService
        }
    }

    @Autowired lateinit var timelines: TicketTimelineController
    @Autowired lateinit var projection: TimelineProjection
    @Autowired lateinit var backfill: TimelineBackfill
    @Autowired lateinit var stats: SmithRunStats
    @Autowired lateinit var engine: FrakworksEngine
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var exits: com.codefrak.engine.smith.SandboxExitNotifier
    @Autowired lateinit var starter: FlowStarter
    @Autowired lateinit var sandbox: ScriptedSandbox
    @Autowired lateinit var accounts: AiAccounts
    @Autowired lateinit var access: Access
    @Autowired lateinit var targetRepos: TargetRepos
    @Autowired lateinit var handled: com.codefrak.engine.repos.HandledIssues
    @Autowired lateinit var incidents: IncidentController
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcClient

    private val repo = "acme/projection"
    private var now = 1_789_900_000_000L

    @BeforeEach
    fun reset() {
        runtime.createProcessInstanceQuery().rootProcessInstances().list().forEach { runtime.deleteProcessInstance(it.id, "test reset") }
        accounts.ensureTestAccount()
        now = 1_789_900_000_000L
        ClockUtil.setCurrentTime(Date(now))
    }

    @AfterEach
    fun restore() { ClockUtil.reset(); accounts.remove("test-anthropic") }

    private fun tick(seconds: Long) { now += seconds * 1000; ClockUtil.setCurrentTime(Date(now)) }

    private fun runJobs(until: () -> Boolean = { false }) {
        repeat(300) {
            if (until()) return
            exits.scanExits()
            val job = management.createJobQuery().withRetriesLeft().list().firstOrNull() ?: return
            tick(60)
            runCatching { management.executeJob(job.id) }
        }
        error("no quiescence in 300 jobs")
    }

    private fun timeline(n: Long): JsonNode = mapper.valueToTree(timelines.timeline(repo.substringBefore('/'), repo.substringAfter('/'), n).body)

    private fun result(cost: Double, turns: Int) = """{"type":"result","total_cost_usd":$cost,"num_turns":$turns,"duration_ms":1000}"""
    private val done = """{"outcome":"done","title":"Tidy the readme","summary":"Tidied it.","addressed":[]}"""

    /** TicketTimelineControllerTest's reworked chore: a failed run and its rerun, an incident retried, a push, security, changes from review, a running rework. */
    private fun reworkedChore(issue: Long): String {
        targetRepos.add(repo)
        access.list().forEach { access.remove(it.login, null) }
        access.add("alice", "test")
        sandbox.reset("work" to done,
            "security" to """{"outcome":"passed","summary":"fine","findings":[]}""",
            "review" to """{"outcome":"changes","findings":[{"ask":"fix the typo","file":"README.md","line":3}]}""",
            "work" to done)
        sandbox.failNext = 2
        sandbox.logByMode["security"] = result(0.31, 9); sandbox.logByMode["review"] = result(0.62, 15)
        val chore = starter.start("chore", repo, IssueSnapshot(issue, "Tidy the readme", "## Goal\n\nTidy.\n\nTier: 5\n", "author"), "test")!!
        runJobs()
        val incident = runtime.createIncidentQuery().list().first { it.processInstanceId == chore }
        tick(600)
        incidents.retry(incident.id, RetryRequest(), TestTokens.asLogin("alice"))
        runJobs(until = { sandbox.started.size == 6 })
        tick(300)
        return chore
    }

    /** A chore that runs to its end: work, security and review approve. */
    private fun endedChore(issue: Long): String {
        targetRepos.add(repo)
        sandbox.reset("work" to done, "security" to """{"outcome":"passed","summary":"fine","findings":[]}""", "review" to """{"outcome":"approved","findings":[]}""")
        val chore = starter.start("chore", repo, IssueSnapshot(issue, "Tidy the readme", "## Goal\n\nTidy.\n\nTier: 5\n", "author"), "test")!!
        runJobs()
        return chore
    }

    private fun servedWithoutHistory(n: Long): JsonNode {
        timeline(n) // the first request fills the ticket from history, once
        assertTrue(projection.backfilled(repo, n), "the first request recorded the backfill")
        CountingHistory.queries.set(0)
        val t = timeline(n)
        val runs = timelines.runs(repo, n)
        assertEquals(0, CountingHistory.queries.get(), "no HistoryService query serving #$n")
        assertNotNull(runs)
        return t
    }

    @Test
    fun `a ticket whose flows all ended, and one with a running smith-run, are served with zero history queries`() {
        endedChore(51)
        val ended = servedWithoutHistory(51)
        assertEquals("CLOSED", ended["ticket"]["state"].asString())
        assertEquals<List<String>>(listOf("coding", "security", "correctness"), ended["steps"].toList().map { it["role"].asString() })

        reworkedChore(52)
        val open = servedWithoutHistory(52)
        assertEquals("OPEN", open["ticket"]["state"].asString())
        assertTrue(open["steps"].last()["running"].asBoolean())
        assertEquals<List<String>>(listOf("coding", "coding", "coding", "security", "correctness", "coding"), open["steps"].toList().map { it["role"].asString() })
        assertEquals(0.93, open["totals"]["costUsd"].asDouble(), 1e-9, "cost from engine.claude_usage")
        assertEquals("alice", open["waits"].single { it["cause"].asString() == "stuck" }["payload"]["retrier"].asString())
    }

    /** The projection's rows of the ticket, gone; its run rows stay (#1976: history has no model, effort or turns to rebuild them from). */
    private fun clearProjection(n: Long) {
        val roots = runtime.createHistoricRoots(n)
        projection.clear(roots)
        jdbc.sql("DELETE FROM engine.timeline_backfill WHERE repo = :r AND issue = :i").param("r", repo).param("i", n).update()
        assertTrue(roots.all { projection.instance(it) == null })
        assertTrue(roots.all { stats.forRoot(it).isNotEmpty() }, "the run rows survive the clear")
    }

    private fun RuntimeService.createHistoricRoots(n: Long): List<String> =
        jdbc.sql("SELECT root FROM engine.ticket_flow WHERE repo = :r AND issue = :i").param("r", repo).param("i", n).query(String::class.java).list().filterNotNull()

    @Test
    fun `the backfill reads a ticket back identically, through the controller's first request and through the sweep`() {
        val chore = reworkedChore(53)
        // The scan's record of the approval: what the sweep lists the ticket from once its run rows are cleared.
        handled.claim(repo, 53, 530L, "chore", "started", null); handled.started(repo, 53, 530L, chore)
        val live = timeline(53)
        assertTrue(live["steps"].size() == 6 && live["waits"].any { it["cause"].asString() == "stuck" }, live.toString())

        clearProjection(53)
        assertEquals(live, timeline(53), "the first request's backfill")
        assertEquals("request", jdbc.sql("SELECT source FROM engine.timeline_backfill WHERE repo = :r AND issue = :i").param("r", repo).param("i", 53).query(String::class.java).single())

        clearProjection(53)
        assertTrue(backfill.sweep() >= 1)
        assertEquals("startup", jdbc.sql("SELECT source FROM engine.timeline_backfill WHERE repo = :r AND issue = :i").param("r", repo).param("i", 53).query(String::class.java).single())
        assertEquals(live, timeline(53), "the sweep's backfill")
        assertEquals(engine.ticketRuns(repo, 53)!!.map { Triple(it.run.runId, it.run.mode, it.run.findings) },
            timelines.runs(repo, 53)!!.map { Triple(it.runId, it.mode, it.findings) })
    }

    @Test
    fun `read_ticket_runs returns the same runs, and stat is null until the run is collected`() {
        reworkedChore(54)
        val rows = engine.ticketRuns(repo, 54)!!
        assertEquals(listOf("work", "work", "work", "security", "review", "work"), rows.map { it.run.mode })
        assertEquals(listOf(1, 1, 1, 1, 1, 2), rows.map { it.run.round })
        assertEquals(listOf("review-2-1"), rows[4].run.findings!!.map { it.id }, "the correctness review is review 2, after security")
        assertEquals("correctness", rows[4].run.findings!!.single().review)
        assertNull(rows[3].run.findings!!.firstOrNull(), "security passed: no findings")
        assertNull(rows.last().stat, "running: not collected")
        assertTrue(rows.dropLast(1).all { it.stat != null && it.stat!!.ended != null })
        assertEquals(2, rows[4].stat!!.reviewNo)
        assertEquals(1, rows[3].stat!!.reviewNo)
        // One number per review (#1976): the id's `<n>` is the row's review_no, and no `reviews` counter is kept as a variable.
        rows[4].run.findings!!.forEach { assertEquals("review-${rows[4].stat!!.reviewNo}-1", it.id) }
        assertEquals(0L, runtime.createVariableInstanceQuery().variableName("reviews").count(), "reviews is not a process variable")
    }

    private fun at(seconds: Long) = Date(now + seconds * 1000)

    @Test
    fun `two receive-ci-check waits in one flow read back rerun then passed`() {
        val root = "root-${UUID.randomUUID()}"; val ci = "ci-${UUID.randomUUID()}"
        projection.processStarted(root, root, null, "chore", at(0))
        projection.processStarted(ci, root, root, "reusable-ci-check", at(1))
        projection.variable(ci, root, Vars.CI_CHECK, "engine", true)
        projection.activityStarted("w1", ci, root, TicketTimelineController.CI_WAIT, "receiveTask", 1, at(2))
        projection.variable(ci, root, Vars.CI_CONCLUSION, "failure", true)
        projection.activityEnded("w1", ci, root, TicketTimelineController.CI_WAIT, "receiveTask", 1, at(10))
        projection.variable(ci, root, Vars.CI_OUTCOME, "rerun", true)
        projection.activityStarted("w2", ci, root, TicketTimelineController.CI_WAIT, "receiveTask", 2, at(11))
        projection.variable(ci, root, Vars.CI_CONCLUSION, "passed", true)
        projection.activityEnded("w2", ci, root, TicketTimelineController.CI_WAIT, "receiveTask", 2, at(20))
        projection.variable(ci, root, Vars.CI_OUTCOME, "passed", true)
        val waits = projection.ciWaits(root)
        assertEquals(listOf("w1", "w2"), waits.map { it.id })
        assertEquals(listOf("engine", "engine"), waits.map { it.check })
        assertEquals(listOf("rerun", "passed"), waits.map { it.outcome })
        assertEquals(listOf("failure", "passed"), waits.map { it.conclusion })
    }

    @Test
    fun `a rejected work run reads rejected and the review accepted after it carries review_no 2`() {
        val root = "root-${UUID.randomUUID()}"
        fun run(mode: String, wrapper: String) = "run-${UUID.randomUUID()}".also {
            stats.start(SmithRunStat(it, null, repo, 55, 5, "m", "low", null, OffsetDateTime.now(), null, null, null, null, mode = mode, root = root, wrapper = wrapper))
        }
        val security = run("security", "w-sec")
        stats.reviewed(security, stats.nextReviewNo(root))
        val work = run("work", "w-work")
        stats.rejected(stats.latestOfWrapper("w-work")!!.runId)
        val review = run("review", "w-rev")
        val no = stats.nextReviewNo(root)
        stats.reviewed(review, no)
        stats.findings(review, no, ReviewResult.parse("""{"outcome":"changes","findings":[{"ask":"fix it","file":"a.kt"}]}""".toByteArray()).stamped("review", no))
        assertEquals("rejected", stats.get(work)!!.outcome)
        assertEquals(1, stats.get(security)!!.reviewNo)
        assertEquals(2, stats.get(review)!!.reviewNo)
        assertEquals(listOf(Finding("fix it", file = "a.kt", id = "review-2-1", review = "correctness")), stats.findings(review))
    }

    @Test
    fun `an amendment rewriting approvedTicket leaves the title unchanged`() {
        val root = "root-${UUID.randomUUID()}"
        projection.processStarted(root, root, null, "chore", at(0))
        projection.variable(root, root, Vars.TICKET, Brief.ticketBytes(Ticket(56, "First title", "## Goal\n\nA.\n", "author")), true)
        projection.variable(root, root, Vars.TICKET, Brief.ticketBytes(Ticket(56, "Amended title", "## Goal\n\nA.\n\n## Amendments\n\n- more\n", "author")), true)
        assertEquals("First title", projection.ticketFlow(root)!!.title)
    }
}

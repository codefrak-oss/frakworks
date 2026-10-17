package com.codefrak.engine.jobs

import com.codefrak.engine.TestTokens
import com.codefrak.engine.ProcessFakes
import com.codefrak.engine.chore.ScriptedSandbox
import com.codefrak.engine.mcp.EngineMcpController
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.runAllJobs
import com.codefrak.engine.smith.SandboxExitNotifier
import com.codefrak.engine.smith.ensureTestAccount
import com.codefrak.engine.smith.dropTestAccount
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RuntimeService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ticker (engine/README.md, "Custom jobs and the SDK"), driven by hand with its clock stepped, on a prompt job
 * with `every: 1` and a scripted sandbox. The scheduled clock is off in every test (`engine.jobs.tick-enabled=false`
 * in the test properties), and no job executor runs, so a started instance stays where it is until the test drives it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ProcessFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class JobTickerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var ticker: JobTicker
    @Autowired lateinit var store: JobStore
    @Autowired lateinit var starter: JobStarter
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var history: HistoryService
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var exits: SandboxExitNotifier
    @Autowired lateinit var sandbox: ScriptedSandbox
    @Autowired lateinit var accounts: com.codefrak.engine.accounts.AiAccounts
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var access: Access
    @Autowired lateinit var jdbc: JdbcClient

    private val json = jacksonObjectMapper()
    private val repo = "acme/jobtick"
    private val login = "alice"
    private val t0 = Instant.parse("2026-10-06T10:00:00Z")

    @BeforeEach fun setUp() {
        // The H2 is shared: a due job another test left would be ticked here and eat this test's script.
        runtime.createProcessInstanceQuery().processDefinitionKey(JobStarter.PROCESS_KEY).list().forEach { runtime.deleteProcessInstance(it.id, "test reset") }
        jdbc.sql("DELETE FROM engine.job").update()
        repos.remove(repo); repos.add(repo)
        access.add(login, "test")
        sandbox.reset(); accounts.ensureTestAccount()
    }
    @AfterEach fun dropTestAccount() { accounts.dropTestAccount() }

    private fun runAllJobs() = management.runAllJobs(exits)

    private fun runsOf(id: String) = history.createHistoricProcessInstanceQuery().processDefinitionKey(JobStarter.PROCESS_KEY).variableValueEquals("jobId", id).count()
    private fun at(seconds: Long): Instant = t0.plusSeconds(seconds)
    private fun OffsetDateTime?.instant() = this?.toInstant()

    @Test
    fun `an every job starts on each due tick, is skipped while its last run still runs, and starts no more once deleted`() {
        repeat(3) { sandbox.script.getOrPut("job") { ArrayDeque() }.addLast("""{"outcome":"done","summary":"ran $it"}""") }
        val job = store.insert("every second", JobKind.PROMPT, repo, json.readTree("""{"prompt":"Say hi."}"""), Signal.Every(1), login, at(0))
        assertEquals(at(1), store.get(job.id)!!.nextRunAt.instant(), "due one second after registration")

        assertEquals(emptyList(), ticker.tick(at(0)), "not due yet")
        assertEquals(0L, runsOf(job.id))

        val first = ticker.tick(at(1)).single()
        assertEquals(1L, runsOf(job.id))
        store.get(job.id)!!.let { assertEquals(first, it.lastInstance); assertEquals(at(1), it.lastRunAt.instant()); assertEquals(at(2), it.nextRunAt.instant()) }

        // The first run still runs (no executor drove it): the tick skips it and moves the clock on, so nothing is caught up later.
        assertEquals(emptyList(), ticker.tick(at(2)), "skipped while $first runs")
        assertEquals(1L, runsOf(job.id))
        store.get(job.id)!!.let { assertEquals(first, it.lastInstance); assertEquals(at(3), it.nextRunAt.instant()) }
        assertEquals(emptyList(), ticker.tick(at(2)), "not due again before the pushed time")

        runAllJobs()
        assertEquals(1L, history.createHistoricProcessInstanceQuery().processInstanceId(first).finished().count(), "the first run ended")

        // Three ticks were missed (the clock jumps to t0+7): they collapse to one start.
        val second = ticker.tick(at(7)).single()
        assertEquals(2L, runsOf(job.id))
        store.get(job.id)!!.let { assertEquals(second, it.lastInstance); assertEquals(at(7), it.lastRunAt.instant()); assertEquals(at(8), it.nextRunAt.instant()) }
        assertEquals(emptyList(), ticker.tick(at(7)), "just started")
        runAllJobs()
        assertEquals(2, sandbox.started.size, "two sandboxes ran")

        mvc.delete("/jobs/${job.id}") { with(TestTokens.bearer(login)) }.andExpect { status { isNoContent() } }
        assertNull(store.get(job.id))
        assertEquals(emptyList(), ticker.tick(at(8)), "a deleted job starts no more")
        assertEquals(emptyList(), ticker.tick(at(60)))
        assertEquals(2L, runsOf(job.id))
        assertEquals(2, sandbox.started.size)
        assertTrue(sandbox.started.all { it.jobId == job.id && it.repo == repo && it.mode == "job" }, sandbox.started.toString())
    }

    @Test
    fun `a due system refactor job is started by a tick and its next run moves by the schedule`() {
        val job = store.insert("refactor $repo", JobKind.REFACTOR, repo, json.readTree("{}"), Signal.Every(604800), login, at(0), owner = JobOwner.SYSTEM)
        assertEquals(at(604800), store.get(job.id)!!.nextRunAt.instant())
        assertEquals(emptyList(), ticker.tick(at(604799)), "not due yet")
        val run = ticker.tick(at(604800)).single()
        val stored = store.get(job.id)!!
        assertEquals(run, stored.lastInstance); assertEquals(JobOwner.SYSTEM, stored.owner)
        assertEquals(at(604800 * 2), stored.nextRunAt.instant(), "the next run is a schedule on")
        assertEquals("refactor", runtime.getVariable(run, JobStarter.KIND)); assertEquals("system", runtime.getVariable(run, JobStarter.OWNER))
        runtime.deleteProcessInstance(run, "test done")
    }

    @Test
    fun `an execute job is never ticked, and a tick on no due job starts nothing`() {
        val job = store.insert("by hand only", JobKind.PROMPT, repo, json.readTree("""{"prompt":"Say hi."}"""), Signal.Execute, login, at(0))
        assertNull(store.get(job.id)!!.nextRunAt)
        assertEquals(emptyList(), ticker.tick(at(3600)))
        assertEquals(0L, runsOf(job.id))
        assertTrue(store.due(at(3600)).none { it.id == job.id })
    }

    @Test
    fun `an at job starts once at its instant, stays due while a run still runs, and is kept with no next run`() {
        sandbox.script.getOrPut("job") { ArrayDeque() }.addLast("""{"outcome":"done","summary":"ran later"}""")
        val job = store.insert("later", JobKind.PROMPT, repo, json.readTree("""{"prompt":"Say hi."}"""), Signal.At(at(10)), login, at(0))
        assertEquals(at(10), store.get(job.id)!!.nextRunAt.instant(), "due at its instant")

        assertEquals(emptyList(), ticker.tick(at(9)), "not due yet")
        assertEquals(0L, runsOf(job.id))

        val run = ticker.tick(at(11)).single()
        assertEquals(1L, runsOf(job.id))
        store.get(job.id)!!.let { assertEquals(run, it.lastInstance); assertEquals(at(11), it.lastRunAt.instant()); assertNull(it.nextRunAt) }
        runAllJobs()
        assertEquals(emptyList(), ticker.tick(at(12)))
        assertEquals(emptyList(), ticker.tick(at(3600)), "never again")
        assertEquals(1L, runsOf(job.id))
        assertTrue(sandbox.started.single().let { it.jobId == job.id && it.repo == repo && it.mode == "job" }, sandbox.started.toString())
        assertTrue(store.list(repo).any { it.id == job.id }, "a fired at job is kept as history")
    }

    @Test
    fun `an at job whose last run still runs at its instant stays due and starts on a later tick`() {
        repeat(2) { sandbox.script.getOrPut("job") { ArrayDeque() }.addLast("""{"outcome":"done","summary":"ran $it"}""") }
        val job = store.insert("later", JobKind.PROMPT, repo, json.readTree("""{"prompt":"Say hi."}"""), Signal.At(at(10)), login, at(0))
        // Executed by hand before its instant: the scheduled run stays.
        starter.start(store.get(job.id)!!, at(5))
        assertEquals(at(10), store.get(job.id)!!.nextRunAt.instant())

        assertEquals(emptyList(), ticker.tick(at(10)), "skipped while the manual run runs")
        assertEquals(at(10), store.get(job.id)!!.nextRunAt.instant(), "still due")
        runAllJobs()
        val run = ticker.tick(at(11)).single()
        assertEquals(run, store.get(job.id)!!.lastInstance)
        assertNull(store.get(job.id)!!.nextRunAt)
        assertEquals(2L, runsOf(job.id))
    }
}

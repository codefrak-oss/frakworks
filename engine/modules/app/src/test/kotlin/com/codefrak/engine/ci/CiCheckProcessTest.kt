package com.codefrak.engine.ci

import com.codefrak.engine.ProcessFakes
import com.codefrak.engine.finished
import com.codefrak.engine.brief
import com.codefrak.engine.accounts.AiAccounts
import com.codefrak.engine.chore.FakeGitHubPulls
import com.codefrak.engine.github.MergeResult
import com.codefrak.engine.intake.TicketRecorder
import com.codefrak.engine.chore.FakeGitPush
import com.codefrak.engine.chore.ScriptedSandbox
import com.codefrak.engine.jev.FakeJevDecider
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.runAllJobs
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
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Check runs by sha, set by the test; a sha with none has not started. */
class FakeCheckRuns : CheckRuns {
    val bySha = mutableMapOf<String, CheckRun>()
    var logText: String? = "#11 190.1 ChoreProcessTest > a chore is worked() FAILED\n#11 195.0 FAILURE: Build failed with an exception."
    val asked = mutableListOf<Triple<String, String, String>>()
    /** Shas whose read fails the way a GitHub error would. */
    val failing = mutableSetOf<String>()
    override fun latest(repo: String, sha: String, name: String): CheckRun? {
        asked += Triple(repo, sha, name)
        if (sha in failing) error("502 Bad Gateway")
        return bySha[sha]
    }
    override fun log(repo: String, checkRunId: Long): String = logText ?: error("403 Forbidden")
    /** The check runs re-run, in order; [refuseRerun] fails the call the way a GitHub 403 would. */
    val reruns = mutableListOf<Long>()
    var refuseRerun = false
    override fun rerun(repo: String, checkRunId: Long) {
        if (refuseRerun) error("403 Forbidden")
        reruns += checkRunId
    }
    /** What [workflowRuns] answers, and its calls as (repo, workflow, branch, limit). */
    var workflowRunRows = listOf<WorkflowRun>()
    val workflowRunsAsked = mutableListOf<List<Any?>>()
    override fun workflowRuns(repo: String, workflow: String?, branch: String?, limit: Int): List<WorkflowRun> {
        workflowRunsAsked += listOf(repo, workflow, branch, limit)
        workflowRunsFailure?.let { error(it) }
        return workflowRunRows
    }
    /** A message [workflowRuns] fails with, the way a GitHub error would; null answers [workflowRunRows]. */
    var workflowRunsFailure: String? = null
    /** What [defaultBranch] answers; null fails the read. */
    var defaultBranchName: String? = "main"
    override fun defaultBranch(repo: String): String = defaultBranchName ?: error("403 Forbidden")
    fun reset() {
        workflowRunRows = emptyList(); workflowRunsAsked.clear(); workflowRunsFailure = null; defaultBranchName = "main"
        bySha.clear(); asked.clear(); failing.clear(); reruns.clear(); refuseRerun = false
        logText = "#11 190.1 ChoreProcessTest > a chore is worked() FAILED\n#11 195.0 FAILURE: Build failed with an exception."
    }
}

/**
 * `wait for the engine check` (bpmn/reusable-ci-check.bpmn) inside a chore, between the push and the
 * security review: skipped when nothing needs it, a pass on to the security review and then the
 * correctness review, a failure back to the work as a `ci` finding with no security run, and the incidents;
 * and Jev's triage of a failure (stubbed: no Jev call is made), with its re-run and its fallback.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ProcessFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class CiCheckProcessTest {
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var accounts: AiAccounts
    @Autowired lateinit var history: HistoryService
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var exits: com.codefrak.engine.smith.SmithExitNotifier
    @Autowired lateinit var pulls: FakeGitHubPulls
    @Autowired lateinit var github: TicketRecorder
    @Autowired lateinit var git: FakeGitPush
    @Autowired lateinit var sandbox: ScriptedSandbox
    @Autowired lateinit var runs: FakeCheckRuns
    @Autowired lateinit var waits: CiCheckWaits
    @Autowired lateinit var jev: FakeJevDecider
    @Autowired lateinit var jevCalls: JevCalls
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var tickets: com.codefrak.engine.workitems.FakeTickets
    @Autowired lateinit var retrier: com.codefrak.engine.camunda.IncidentRetrier

    @AfterEach
    fun dropTestAccount() { accounts.dropTestAccount() }

    @BeforeEach
    fun clear() {
        pulls.opened.clear(); pulls.merged.clear(); pulls.closed.clear(); pulls.reopened.clear(); pulls.heads.clear(); pulls.failReopen = false; pulls.comments.clear(); git.reset(); runs.reset(); jev.reset(); accounts.ensureTestAccount(); github.comments.clear()
        git.paths = listOf("README.md", "engine/src/main/kotlin/X.kt")
        repos.add("acme/tickets"); repos.update("acme/tickets", "engine", listOf("engine/"), true)
        repos.setDoctorBugs("acme/tickets", false, false)
    }

    /** Async continuations only: the check's boundary timer fires when a test says so. */
    private fun runAllJobs() = management.runAllJobs(exits, messages = true)

    private fun start(issue: Long) = runtime.createMessageCorrelation("chore filed")
        .setVariables(mapOf("repo" to "acme/tickets", "issue" to issue)).correlateStartMessage().also { runAllJobs() }

    /** The wait's timer job on `activity`: the timeout, or the re-trigger cycle's next firing; null when none is due. */
    /** By the job's own instance, not its rootProcessInstanceId: a cycle's next timer is a new job. */
    private fun timer(root: String, activity: String) =
        management.createJobQuery().timers().activityId(activity).list().singleOrNull { rootOf(it.processInstanceId) == root }

    private fun rootOf(instance: String) = history.createHistoricProcessInstanceQuery().processInstanceId(instance).singleResult().rootProcessInstanceId
    private fun waiting(root: String) = runtime.createExecutionQuery().messageEventSubscriptionName(CI_CHECK_MESSAGE).list()
        .count { rootOf(it.processInstanceId) == root } == 1
    /** The failed job's own incident, not its copies up the call activities. */
    private fun incidentsUnder(root: String) = runtime.createIncidentQuery().list()
        .filter { rootOf(it.processInstanceId) == root && it.causeIncidentId == it.id }

    private fun done(vararg addressed: String) =
        """{"outcome":"done","title":"Tidy the engine","summary":"Tidied it.","addressed":[${addressed.joinToString { """{"finding":"$it","how":"fixed"}""" }}]}"""
    private val approved = """{"outcome":"approved","summary":"fine","findings":[]}"""
    private val passed = """{"outcome":"passed","summary":"fine","findings":[]}"""
    private fun completed(sha: String, conclusion: String, id: Long = 11) {
        runs.bySha[sha] = CheckRun(id, "completed", conclusion, "https://github.com/acme/tickets/actions/runs/1/job/$id")
    }

    @Test
    fun `a branch that changes nothing under engine goes from the push straight to the security review`() {
        git.paths = listOf("README.md", "scripts/run.sh")
        sandbox.reset("work" to done(), "security" to passed, "review" to approved)
        val chore = start(21)

        assertTrue(history.finished(chore.id))
        assertEquals(listOf("work", "security", "review"), sandbox.started.map { it.mode })
        assertEquals(emptyList(), runs.asked, "no check was read")
    }

    @Test
    fun `a repo whose row names no check goes from the push straight to the security review, even when the branch changes engine`() {
        repos.update("acme/tickets", null, emptyList(), true)
        sandbox.reset("work" to done(), "security" to passed, "review" to approved)
        val chore = start(64)

        assertTrue(history.finished(chore.id))
        assertEquals(listOf("work", "security", "review"), sandbox.started.map { it.mode })
        assertEquals(emptyList(), runs.asked, "no check was read")
        assertFalse(waiting(chore.id))
    }

    @Test
    fun `the flow waits for the check on the pushed head before any review, and a pass goes on to security, review and merge`() {
        sandbox.reset("work" to done(), "security" to passed, "review" to approved)
        val chore = start(22)

        assertTrue(waiting(chore.id), "parked at the check after the push")
        assertEquals(listOf("work"), sandbox.started.map { it.mode }, "no security run before the check")
        assertEquals(listOf("bundle of container-1"), git.pushes.map { it.third }, "pushed before any review")
        assertEquals(1, pulls.opened.size, "the PR is open while the check runs")

        runs.bySha["sha-1"] = CheckRun(11, "in_progress", null, "")
        assertEquals(emptyList(), waits.poll(), "a running check wakes nothing")
        assertTrue(waiting(chore.id))

        completed("sha-1", "success")
        val woke = waits.poll().single()
        assertEquals(listOf("acme/tickets", "ci-check", "checked"), listOf(woke.repo, woke.flow, woke.outcome))
        assertEquals(22L, woke.issue)
        assertEquals(Triple("acme/tickets", "sha-1", "engine"), runs.asked.last())
        assertEquals(emptyList(), waits.poll(), "a second pass finds no one waiting")
        runAllJobs()

        assertTrue(history.finished(chore.id))
        assertEquals(listOf("work", "security", "review"), sandbox.started.map { it.mode })
        assertEquals(listOf(7L to "Tidy the engine"), pulls.merged)
    }

    @Test
    fun `a passing check with a retried test files nothing and hands the test to the correctness review alone`() {
        repos.setDoctorBugs("acme/tickets", true, true)
        sandbox.reset("work" to done(), "security" to passed, "review" to """{"outcome":"changes","summary":"no","findings":[{"ask":"fix","severity":"low","file":"a","line":1}]}""",
            "work" to done("review-2-1"), "security" to passed, "review" to approved)
        val chore = start(90)
        runs.logText = "#11 90.1 FLAKY-TEST: com.x.FooTest > does a thing() passed on retry\n#11 99.0 BUILD SUCCESSFUL in 5m"
        completed("sha-1", "success")
        waits.poll(); runAllJobs()

        // The fake tickets are shared across tests and the flow files its own items, so no count: no
        // ticket is titled for the flaky test, and no ticket is commented with it.
        val open = tickets.open("acme/tickets")
        assertTrue(open.none { it.title.startsWith(FlakyTests.TITLE_PREFIX) }, "no flaky-test ticket from a pull request")
        assertTrue(open.flatMap { tickets.conversation(it.id) }.none { "FooTest" in it.body }, "no ticket commented with the flaky test")
        val review = sandbox.started.first { it.mode == "review" }
        val ci = assertNotNull(brief(review)!!.ci)
        assertEquals(listOf("com.x.FooTest > does a thing()"), ci["flaky"])
        assertEquals("passed", ci["outcome"])
        assertEquals("engine", ci["check"])
        val security = sandbox.started.first { it.mode == "security" }
        assertNull(brief(security)?.ci, "the security review is not told")

        // The next work round is not told either, and its push clears the lines.
        val work = sandbox.started.filter { it.mode == "work" }[1]
        assertNull(brief(work)!!.ci, "the work Smith is not told it got lucky")
        runs.logText = "#11 99.0 BUILD SUCCESSFUL in 5m"
        completed("sha-2", "success", id = 12)
        waits.poll(); runAllJobs()
        assertEquals(listOf("work", "security", "review", "work", "security", "review"), sandbox.started.map { it.mode })
        assertNull(brief(sandbox.started.last { it.mode == "review" })!!.ci, "a new push's check had no retried test")
        assertTrue(history.finished(chore.id))
    }

    @Test
    fun `a passing check with no retried test leaves the review brief's ci field absent`() {
        repos.setDoctorBugs("acme/tickets", true, true)
        sandbox.reset("work" to done(), "security" to passed, "review" to approved)
        val chore = start(91)
        runs.logText = "#11 99.0 BUILD SUCCESSFUL in 5m"
        completed("sha-1", "success")
        waits.poll(); runAllJobs()

        assertTrue(history.finished(chore.id))
        assertNull(brief(sandbox.started.single { it.mode == "review" })!!.ci)
    }

    @Test
    fun `a failed check goes back to the work with a ci finding naming the failing tests, and the fix is checked again`() {
        sandbox.reset("work" to done(), "work" to done("ci-1-1"), "security" to passed, "review" to approved)
        val chore = start(23)
        completed("sha-1", "failure")
        waits.poll(); runAllJobs()

        val work = sandbox.started.last { it.mode == "work" }
        assertEquals(listOf("work", "work"), sandbox.started.map { it.mode }, "no security or correctness review of a failing branch")
        val finding = brief(work)!!.findings.single()
        assertEquals("ci-1-1", finding.id)
        assertEquals("ci", finding.review)
        assertTrue("ChoreProcessTest > a chore is worked() FAILED" in finding.ask, finding.ask)
        assertTrue("https://github.com/acme/tickets/actions/runs/1/job/11" in finding.ask)
        assertTrue(waiting(chore.id), "the fix is pushed and waits for its own check")
        assertEquals(emptyList(), waits.poll())
        assertEquals(Triple("acme/tickets", "sha-2", "engine"), runs.asked.last(), "the new head's check, not the failed one")

        completed("sha-2", "success", 12)
        waits.poll(); runAllJobs()
        assertTrue(history.finished(chore.id))
        assertEquals(listOf("work", "work", "security", "review"), sandbox.started.map { it.mode })
        val review = brief(sandbox.started.last())!!
        assertEquals(listOf("ci-1-1"), review.raised.map { it.id }, "the reviewer can read what the check raised")
    }

    @Test
    fun `an unreadable log still sends the failure back, pointing at the check's page`() {
        runs.logText = null
        sandbox.reset("work" to done(), "work" to done("ci-1-1"))
        val chore = start(24)
        completed("sha-1", "timed_out")
        waits.poll(); runAllJobs()

        val finding = brief(sandbox.started.last { it.mode == "work" })!!.findings.single()
        assertTrue("The job log could not be read" in finding.ask, finding.ask)
        runtime.deleteProcessInstance(chore.id, "test done")
    }

    @Test
    fun `a check that never completes is an incident at the timeout, and a retry once it has goes on`() {
        sandbox.reset("work" to done(), "security" to passed, "review" to approved)
        val chore = start(25)
        val timeout = timer(chore.id, "event-ci-check-timeout")!!
        runs.bySha["sha-1"] = CheckRun(11, "queued", null, "")
        management.executeJob(timeout.id); runAllJobs()

        val incident = incidentsUnder(chore.id).single()
        assertEquals("task-ci-check-timed-out", incident.activityId)
        assertTrue("did not complete within PT3H (queued)" in incident.incidentMessage, incident.incidentMessage)
        assertFalse(waiting(chore.id))

        completed("sha-1", "success")
        management.setJobRetries(incident.configuration, 1); runAllJobs()
        assertTrue(history.finished(chore.id))
        assertEquals(listOf("work", "security", "review"), sandbox.started.map { it.mode })
    }

    @Test
    fun `a cancelled check says nothing about the code and is an incident`() {
        sandbox.reset("work" to done())
        val chore = start(26)
        completed("sha-1", "cancelled")
        waits.poll(); runAllJobs()

        val incident = incidentsUnder(chore.id).single()
        assertEquals("task-read-ci-check", incident.activityId)
        assertTrue("ended cancelled" in incident.incidentMessage, incident.incidentMessage)
        assertEquals(listOf("work"), sandbox.started.map { it.mode }, "no review after a check that says nothing")
        assertEquals(emptyList(), pulls.merged)
        runtime.deleteProcessInstance(chore.id, "test done")
    }

    @Test
    fun `a flow with no check run yet stays waiting`() {
        sandbox.reset("work" to done())
        val chore = start(27)

        assertEquals(emptyList(), waits.poll(), "no check run on the head yet")
        assertEquals(Triple("acme/tickets", "sha-1", "engine"), runs.asked.last())
        assertTrue(waiting(chore.id))
        runtime.deleteProcessInstance(chore.id, "test done")
    }

    // ---- mergeable? (CiCheckConflictDelegate): GitHub makes no check run for a PR it cannot merge ----

    @Test
    fun `a pushed branch that conflicts with main goes back to the work with a rebase finding before any wait, and the fix waits for its own check`() {
        sandbox.reset("work" to done(), "work" to done("rebase-1-1"), "security" to passed, "review" to approved)
        git.merges.addLast(MergeResult.Conflict(listOf("engine/README.md", "engine/src/main/kotlin/X.kt")))
        val chore = start(30)

        assertEquals(listOf("work", "work"), sandbox.started.map { it.mode }, "the conflict round ran, nothing waited for a check")
        assertTrue(runs.asked.none { it.second == "sha-1" }, "no check was read on the head GitHub cannot merge")
        val finding = brief(sandbox.started[1])!!.findings.single()
        assertEquals("rebase-1-1", finding.id)
        assertEquals("rebase", finding.review)
        assertTrue(finding.ask.startsWith("merge conflict with the default branch in engine/README.md, engine/src/main/kotlin/X.kt"), finding.ask)
        assertEquals(2, git.mergeCalls.size, "each push is probed")
        assertEquals(listOf("bundle of container-1", "bundle of container-2"), git.pushes.map { it.third })
        assertTrue(waiting(chore.id), "the merged round waits for its check")
        assertEquals(emptyList(), incidentsUnder(chore.id))

        completed("sha-2", "success", 12)
        waits.poll(); runAllJobs()
        assertTrue(history.finished(chore.id))
        assertEquals(listOf("work", "work", "security", "review"), sandbox.started.map { it.mode })
        assertEquals(listOf("rebase-1-1"), brief(sandbox.started.last())!!.raised.map { it.id })
        assertEquals(listOf(7L to "Tidy the engine"), pulls.merged)
    }

    @Test
    fun `retrying the mergeable incident past the cap sends the flow back to the work`() {
        sandbox.reset("work" to done(), "work" to done("rebase-1-1"), "work" to done("rebase-2-1"), "work" to done("rebase-3-1"))
        repeat(10) { git.merges.addLast(MergeResult.Conflict(listOf("a.txt"))) }
        val chore = start(32)
        val incident = incidentsUnder(chore.id).single()
        assertEquals("task-ci-conflict", incident.activityId)

        git.merges.clear()
        git.merges.addLast(MergeResult.Conflict(listOf("a.txt")))
        val retried = retrier.retryAsSystem(incident.id, "the doctor")
        runAllJobs()

        assertEquals("job", retried.action)
        assertEquals(4, sandbox.started.count { it.mode == "work" }, "the retry granted one more work round")
        val finding = brief(sandbox.started.last { it.mode == "work" })!!.findings.single()
        assertEquals("rebase-3-1", finding.id)
        assertEquals("rebase", finding.review)
        assertEquals(emptyList(), incidentsUnder(chore.id))
        runtime.deleteProcessInstance(chore.id, "test done")
    }

    @Test
    fun `the third conflict at the push is an incident on mergeable, and nothing waits`() {
        sandbox.reset("work" to done(), "work" to done("rebase-1-1"), "work" to done("rebase-2-1"))
        repeat(10) { git.merges.addLast(MergeResult.Conflict(listOf("a.txt"))) }
        val chore = start(31)

        assertEquals(3, sandbox.started.count { it.mode == "work" }, "the first round and two conflict rounds")
        val incident = incidentsUnder(chore.id).single()
        assertEquals("task-ci-conflict", incident.activityId)
        assertTrue("after 2 conflict rounds" in incident.incidentMessage, incident.incidentMessage)
        assertFalse(waiting(chore.id))
        assertEquals(emptyList(), pulls.merged)
        runtime.deleteProcessInstance(chore.id, "test done")
    }

    @Test
    fun `a check GitHub never made says so in the timeout's incident`() {
        sandbox.reset("work" to done())
        val chore = start(32)
        val timeout = timer(chore.id, "event-ci-check-timeout")!!
        management.executeJob(timeout.id); runAllJobs()

        val incident = incidentsUnder(chore.id).single()
        assertEquals("task-ci-check-timed-out", incident.activityId)
        assertTrue("(not started)" in incident.incidentMessage, incident.incidentMessage)
        assertTrue("GitHub makes no run for a pull request it cannot merge" in incident.incidentMessage, incident.incidentMessage)
        assertTrue("closing and reopening the PR" in incident.incidentMessage, incident.incidentMessage)
        runtime.deleteProcessInstance(chore.id, "test done")
    }

    // ---- re-trigger if not started (CiCheckRetriggerDelegate): GitHub dropped the pull_request event ----

    @Test
    fun `a check with no run is re-triggered by reopening the PR, and the run that starts ends the wait as usual`() {
        sandbox.reset("work" to done(), "security" to passed, "review" to approved)
        val chore = start(40)
        management.executeJob(timer(chore.id, "event-ci-check-retrigger")!!.id); runAllJobs()

        assertEquals(listOf(7L), pulls.closed)
        assertEquals(listOf(7L), pulls.reopened)
        assertTrue(waiting(chore.id), "the wait goes on")
        assertEquals(emptyList(), incidentsUnder(chore.id))

        completed("sha-1", "success")
        waits.poll(); runAllJobs()
        assertTrue(history.finished(chore.id))
        assertEquals(listOf("work", "security", "review"), sandbox.started.map { it.mode })
        assertEquals(listOf(7L), pulls.reopened, "re-triggered once")
    }

    @Test
    fun `a check that never starts is re-triggered a bounded number of times, then the timeout's incident says so`() {
        sandbox.reset("work" to done())
        val chore = start(41)
        try {
            repeat(4) { n ->
                val job = assertNotNull(timer(chore.id, "event-ci-check-retrigger"), "the cycle's timer is there for firing ${n + 1}")
                management.executeJob(job.id); runAllJobs()
            }
            assertEquals(listOf(7L, 7L), pulls.reopened, "engine.ci-check.retriggers bounds the tries, however often the cycle fires")

            management.executeJob(timer(chore.id, "event-ci-check-timeout")!!.id); runAllJobs()
            val incident = incidentsUnder(chore.id).single()
            assertEquals("task-ci-check-timed-out", incident.activityId)
            assertTrue("(not started)" in incident.incidentMessage, incident.incidentMessage)
            assertTrue("re-triggered the check 2 time(s)" in incident.incidentMessage, incident.incidentMessage)
        } finally {
            runtime.deleteProcessInstance(chore.id, "test done")
        }
    }

    // ---- the PR's head moves during the wait (#1586): a merge of main or a human's push ----

    @Test
    fun `when the PR head moves during the wait, the wait reads the check on the new head`() {
        sandbox.reset("work" to done(), "security" to passed, "review" to approved)
        val chore = start(43)
        runs.bySha["sha-1"] = CheckRun(11, "in_progress", null, "")
        pulls.heads[7L] = "sha-moved"
        runs.bySha["sha-moved"] = CheckRun(12, "completed", "success", "https://github.com/acme/tickets/actions/runs/1/job/12")
        waits.poll(); runAllJobs()

        assertEquals(Triple("acme/tickets", "sha-moved", "engine"), runs.asked.first { it.second == "sha-moved" })
        assertTrue(runs.asked.none { it.second == "sha-1" }, "the superseded head is not read")
        assertTrue(history.finished(chore.id))
    }

    @Test
    fun `a check that never started on the moved head is re-triggered, even when the old head has a run`() {
        sandbox.reset("work" to done())
        val chore = start(44)
        runs.bySha["sha-1"] = CheckRun(11, "in_progress", null, "")
        pulls.heads[7L] = "sha-moved"
        management.executeJob(timer(chore.id, "event-ci-check-retrigger")!!.id); runAllJobs()

        assertEquals(listOf(7L), pulls.reopened, "the moved head had no run: the PR is reopened")
        assertEquals("sha-moved", runs.asked.last().second, "the re-trigger asked about the PR's current head")
        assertTrue(waiting(chore.id))
        runtime.deleteProcessInstance(chore.id, "test done")
    }

    @Test
    fun `a check that has started but is slow is not re-triggered`() {
        sandbox.reset("work" to done())
        val chore = start(42)
        runs.bySha["sha-1"] = CheckRun(11, "in_progress", null, "")
        management.executeJob(timer(chore.id, "event-ci-check-retrigger")!!.id); runAllJobs()

        assertEquals(emptyList(), pulls.closed)
        assertEquals(emptyList(), pulls.reopened)
        assertTrue(waiting(chore.id))
        assertEquals(emptyList(), incidentsUnder(chore.id))
        runtime.deleteProcessInstance(chore.id, "test done")
    }

    @Test
    fun `a GitHub error for one waiting flow leaves it waiting and does not stop the others`() {
        sandbox.reset("work" to done(), "security" to passed, "review" to approved, "work" to done(), "security" to passed, "review" to approved)
        val broken = start(28)
        val other = start(29)
        assertTrue(waiting(broken.id) && waiting(other.id))

        runs.failing += "sha-1"
        completed("sha-2", "success")
        val woke = waits.poll().single()
        assertEquals(29L, woke.issue, "the other flow is correlated")
        assertEquals(setOf("sha-1", "sha-2"), runs.asked.map { it.second }.toSet(), "both were read")
        runAllJobs()
        assertTrue(history.finished(other.id))
        assertTrue(waiting(broken.id), "the failed read leaves its flow at the wait for the next pass")
        assertEquals(emptyList(), incidentsUnder(broken.id))

        runs.failing.clear()
        completed("sha-1", "success")
        assertEquals(28L, waits.poll().single().issue)
        runAllJobs()
        assertTrue(history.finished(broken.id))
    }

    // ---- Jev's triage ----

    private fun call(n: Int) = jevCalls.get(jev.ids[n])!!

    @Test
    fun `a failure jev reads as flaky is re-run once, the old run wakes nothing, and a passing re-run goes on to the reviews`() {
        jev.choice = CiTriage.FLAKY to 0.9
        sandbox.reset("work" to done(), "security" to passed, "review" to approved)
        val chore = start(61)
        completed("sha-1", "failure")
        waits.poll(); runAllJobs()

        assertEquals(listOf(11L), runs.reruns, "the failed job is re-run")
        assertEquals(listOf("work"), sandbox.started.map { it.mode }, "no work run for a flaky failure")
        assertTrue(waiting(chore.id), "back at the wait")
        val (useCase, context, state) = jev.asked.single()
        assertEquals(CiTriage.USE_CASE, useCase)
        assertEquals("acme/tickets" to 61L, context.repo to context.issue)
        assertTrue("ChoreProcessTest > a chore is worked() FAILED" in state.toString(), "the finding's text is the state")
        assertEquals("flaky: re-ran the check" to 0.8, call(0).let { it.action to it.threshold })
        assertNull(call(0).outcome)

        assertEquals(emptyList(), waits.poll(), "the run that was re-run wakes nothing")
        assertTrue(waiting(chore.id))

        completed("sha-1", "success", 13)
        waits.poll(); runAllJobs()
        assertTrue(history.finished(chore.id))
        assertEquals(listOf("work", "security", "review"), sandbox.started.map { it.mode })
        assertEquals("the re-run passed (success): not the code", call(0).outcome)
        assertEquals(1, jev.asked.size, "a pass asks jev nothing")
    }

    @Test
    fun `a re-run that fails too goes to the work with jev's pick, and the work Smith's answer is the call's outcome`() {
        jev.choice = CiTriage.INFRASTRUCTURE to 0.95
        sandbox.reset("work" to done(), "work" to done("ci-1-1"), "security" to passed, "review" to approved)
        val chore = start(62)
        completed("sha-1", "failure")
        waits.poll(); runAllJobs()
        completed("sha-1", "failure", 13)
        waits.poll(); runAllJobs()

        assertEquals(listOf(11L), runs.reruns, "one re-run per round")
        assertEquals(listOf("work", "work"), sandbox.started.map { it.mode })
        val finding = brief(sandbox.started.last { it.mode == "work" })!!.findings.single()
        assertEquals("ci-1-1", finding.id)
        assertTrue("re-ran this check once already" in finding.ask, finding.ask)
        assertTrue("read this failure as `infrastructure` (confidence 0.95" in finding.ask, finding.ask)
        assertEquals("the re-run failed too (failure)", call(0).outcome)
        assertEquals("infrastructure, re-ran this round already: to the work", call(1).action)
        assertEquals("the work Smith's answer to ci-1-1: addressed: fixed", call(1).outcome)

        completed("sha-2", "success", 14)
        waits.poll(); runAllJobs()
        assertTrue(history.finished(chore.id))
    }

    private fun declined(vararg findings: String) =
        """{"outcome":"done","title":"Tidy the engine","summary":"Nothing to change.","declined":[${findings.joinToString { """{"finding":"$it","why":"a flaky test this branch does not touch"}""" }}]}"""

    @Test
    fun `a work round that declines the check's failure as not its change's re-runs the check on the same commits`() {
        jev.choice = CiTriage.CODE to 0.97
        sandbox.reset("work" to done(), "work" to declined("ci-1-1"), "security" to passed, "review" to approved)
        val chore = start(64)
        sandbox.bundles = false // the second work run commits nothing
        completed("sha-1", "failure")
        waits.poll(); runAllJobs()

        assertEquals(listOf("work", "work"), sandbox.started.map { it.mode })
        assertEquals(emptyList(), incidentsUnder(chore.id).map { it.incidentMessage }, "done without commits is taken when every ci finding is declined")
        assertEquals(listOf(11L), runs.reruns, "the failed check is re-run")
        assertEquals(listOf("bundle of container-1"), git.pushes.map { it.third }, "nothing new is pushed")
        assertTrue(pulls.commentsOn(7).any { "no changes" in it && "a flaky test this branch does not touch" in it }, pulls.commentsOn(7).toString())
        assertTrue(github.commentsOn(7).isEmpty(), "the not-mine comment goes through GitHubPulls.comment, not the ticket")
        assertTrue(waiting(chore.id), "back at the wait")
        assertEquals(emptyList(), waits.poll(), "the failed run wakes nothing")

        completed("sha-1", "success", 13)
        waits.poll(); runAllJobs()
        assertTrue(history.finished(chore.id))
        assertEquals(listOf("work", "work", "security", "review"), sandbox.started.map { it.mode }, "the reviews read the kept bundle")
        assertEquals("the work Smith's answer to ci-1-1: declined: a flaky test this branch does not touch", call(0).outcome)
    }

    /**
     * A ticket's first commit whose kept bundle is gone (an incident's rerun drops both, IncidentController): #1537.
     * Dropped in every instance under the root: the waiting ci check holds its own copies, and its `out` would restore them.
     */
    private fun dropBundles(root: String) = runtime.createProcessInstanceQuery().active().list()
        .filter { rootOf(it.id) == root }
        .forEach { instance -> listOf("bundle", "previousBundle").forEach { runtime.setVariable(instance.id, it, null) } }

    @Test
    fun `a decline on a ticket's first commit with no kept bundle re-runs the check and bundles the head for the reviews`() {
        jev.choice = CiTriage.CODE to 0.97
        sandbox.reset("work" to done(), "work" to declined("ci-1-1"), "security" to passed, "review" to approved)
        val chore = start(70)
        sandbox.bundles = false
        dropBundles(chore.id)
        completed("sha-1", "failure")
        waits.poll(); runAllJobs()

        assertEquals(emptyList(), incidentsUnder(chore.id).map { it.incidentMessage }, "no 'no earlier commits to re-check'")
        assertEquals(listOf(11L), runs.reruns, "still failing: the check is re-run on the head")
        assertEquals(listOf("smith/issue-70"), git.headBundles)
        assertTrue(waiting(chore.id))

        completed("sha-1", "success", 13)
        waits.poll(); runAllJobs()
        assertTrue(history.finished(chore.id))
        assertEquals(listOf("work", "work", "security", "review"), sandbox.started.map { it.mode })
        assertEquals("head of smith/issue-70", sandbox.started.single { it.mode == "security" }.inputs["commits.bundle"]?.let { String(it) })
    }

    @Test
    fun `a decline on a ticket's first commit whose check has passed since is taken with no re-run`() {
        jev.choice = CiTriage.CODE to 0.97
        sandbox.reset("work" to done(), "work" to declined("ci-1-1"), "security" to passed, "review" to approved)
        val chore = start(71)
        sandbox.bundles = false
        dropBundles(chore.id)
        completed("sha-1", "failure")
        waits.poll()
        completed("sha-1", "success", 13) // re-ran and passed on the same head before the work answered
        runAllJobs()
        waits.poll(); runAllJobs()

        assertEquals(emptyList(), incidentsUnder(chore.id).map { it.incidentMessage })
        assertEquals(emptyList(), runs.reruns, "nothing re-run")
        assertTrue(history.finished(chore.id))
        assertEquals(listOf("work", "work", "security", "review"), sandbox.started.map { it.mode })
        assertEquals(listOf("bundle of container-1"), git.pushes.map { it.third }, "nothing new is pushed")
    }

    @Test
    fun `a third declining round in a row is the incident, so a check that keeps failing stops for a person`() {
        jev.choice = CiTriage.CODE to 0.97
        sandbox.reset("work" to done(), "work" to declined("ci-1-1"), "work" to declined("ci-2-1"), "work" to declined("ci-3-1"))
        val chore = start(65)
        sandbox.bundles = false
        completed("sha-1", "failure")
        waits.poll(); runAllJobs()
        completed("sha-1", "failure", 13)
        waits.poll(); runAllJobs()
        assertEquals(emptyList(), incidentsUnder(chore.id), "two in a row are re-runs")
        assertEquals(listOf(11L, 13L), runs.reruns)

        completed("sha-1", "failure", 15)
        waits.poll(); runAllJobs()
        assertTrue(incidentsUnder(chore.id).single().incidentMessage.contains("2 rounds in a row"), incidentsUnder(chore.id).toString())
        assertEquals(listOf(11L, 13L), runs.reruns, "no third re-run")
        runtime.deleteProcessInstance(chore.id, "test done")
    }

    @Test
    fun `a rerun_ci retry of the third-decline incident re-runs the check on the same head with no new Smith`() {
        jev.choice = CiTriage.CODE to 0.97
        sandbox.reset("work" to done(), "work" to declined("ci-1-1"), "work" to declined("ci-2-1"), "work" to declined("ci-3-1"))
        val chore = start(72)
        sandbox.bundles = false
        completed("sha-1", "failure")
        waits.poll(); runAllJobs()
        completed("sha-1", "failure", 13)
        waits.poll(); runAllJobs()
        completed("sha-1", "failure", 15)
        waits.poll(); runAllJobs()
        val incident = incidentsUnder(chore.id).single()
        val started = sandbox.started.size

        val retried = retrier.retry(incident.id, com.codefrak.engine.camunda.Actor.System("test"), rerunCi = true)
        runAllJobs()
        assertEquals("ci", retried.action)
        assertEquals(emptyList(), incidentsUnder(chore.id).map { it.incidentMessage }, "the stored decline is taken with a fresh streak")
        assertEquals(listOf(11L, 13L, 15L), runs.reruns, "the failed check is re-run on the same head")
        assertEquals(started, sandbox.started.size, "no Smith is started again")
        assertTrue(waiting(chore.id))
        assertTrue(github.commentsOn(72).any { "re-ran the failed engine check" in it }, github.commentsOn(72).toString())
        runtime.deleteProcessInstance(chore.id, "test done")
    }

    @Test
    fun `a rerun_ci retry of any other incident is refused and touches nothing`() {
        sandbox.reset("work" to done())
        val chore = start(73)
        try {
            val parked = runtime.createExecutionQuery().messageEventSubscriptionName(CI_CHECK_MESSAGE).list()
                .single { rootOf(it.processInstanceId) == chore.id }
            val other = runtime.createIncident(org.operaton.bpm.engine.runtime.Incident.FAILED_JOB_HANDLER_TYPE, parked.id, "cfg", "boom")
            val e = kotlin.test.assertFailsWith<org.springframework.web.server.ResponseStatusException> {
                retrier.retry(other.id, com.codefrak.engine.camunda.Actor.System("test"), rerunCi = true)
            }
            assertEquals(400, e.statusCode.value())
            assertEquals(emptyList(), runs.reruns)
        } finally {
            runtime.deleteProcessInstance(chore.id, "test done")
        }
    }

    @Test
    fun `a failure jev reads as the code goes to the work carrying the pick, with no re-run`() {
        jev.choice = CiTriage.CODE to 0.97
        sandbox.reset("work" to done(), "work" to done("ci-1-1"))
        val chore = start(63)
        completed("sha-1", "failure")
        waits.poll(); runAllJobs()

        assertEquals(emptyList(), runs.reruns)
        val finding = brief(sandbox.started.last { it.mode == "work" })!!.findings.single()
        assertTrue("read this failure as `code` (confidence 0.97" in finding.ask, finding.ask)
        assertFalse("re-ran this check" in finding.ask)
        assertEquals("code: to the work", call(0).action)
        assertEquals("the work Smith's answer to ci-1-1: addressed: fixed", call(0).outcome)
        runtime.deleteProcessInstance(chore.id, "test done")
    }

    @Test
    fun `with no jev answer, below the floor, or the re-run refused, the finding goes to the work as before`() {
        val plain = CiLog.ask("engine", "failure", "sha-1", "https://github.com/acme/tickets/actions/runs/1/job/11", runs.logText, 11)
        listOf(Triple(64L, null as Pair<String, Double>?, "no answer: to the work"),
            Triple(65L, CiTriage.FLAKY to 0.5, "flaky below floor 0.8: to the work"),
            Triple(66L, CiTriage.INFRASTRUCTURE to 0.9, "infrastructure: re-run refused: to the work")).forEach { (issue, choice, action) ->
            jev.reset(); jev.choice = choice; runs.reset(); runs.refuseRerun = true; git.pushes.clear()
            sandbox.reset("work" to done(), "work" to done("ci-1-1"))
            val chore = start(issue)
            completed("sha-1", "failure")
            waits.poll(); runAllJobs()

            assertEquals(listOf("work", "work"), sandbox.started.map { it.mode }, "issue $issue")
            val finding = brief(sandbox.started.last { it.mode == "work" })!!.findings.single()
            assertEquals("ci-1-1", finding.id)
            if (choice == null) assertEquals(plain, finding.ask, "with no answer the finding is as it was before jev")
            else assertTrue(finding.ask.startsWith(plain), finding.ask)
            assertEquals(action, call(0).action)
            assertEquals(if (choice == null) null else "the work Smith's answer to ci-1-1: addressed: fixed", call(0).outcome)
            runtime.deleteProcessInstance(chore.id, "test done")
        }
    }
}

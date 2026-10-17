package com.codefrak.engine.postdeploy

import com.codefrak.engine.ProcessFakes
import com.codefrak.engine.accounts.AiAccounts
import com.codefrak.engine.brief
import com.codefrak.engine.chore.ScriptedSandbox
import com.codefrak.engine.finished
import com.codefrak.engine.hitlp.HitlpPoller
import com.codefrak.engine.hitlp.HitlpRequests
import com.codefrak.engine.hitlp.SharedHitlpStub
import com.codefrak.engine.hitlp.decideWait
import com.codefrak.engine.intake.TicketRecorder
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.runAllJobs
import com.codefrak.engine.smith.SmithExitNotifier
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.dropTestAccount
import com.codefrak.engine.smith.ensureTestAccount
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.impl.util.ClockUtil
import org.operaton.bpm.engine.runtime.Job
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.time.Instant
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What is in production, by sha, set by the test; every question is recorded. */
class FakeProductionCheck : ProductionCheck {
    val deployedShas = mutableSetOf<String>()
    val asked = mutableListOf<Pair<String, String>>()
    override fun deployed(repo: String, sha: String): Boolean { asked += repo to sha; return sha in deployedShas }
}

/**
 * bpmn/post-deploy-check.bpmn on the fake sandbox and the engine clock (ClockUtil): the wait for the deploy (its wake
 * and its safety poll), the check loop (passed, failed, and not_yet by the Smith's time, by a bus frame and by the
 * ladder), the deadline's question answered wait, drop and bug, and a redeploy mid-wait, as an engine restart on a
 * new build does, that keeps the timers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ProcessFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class PostDeployCheckProcessTest {
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var history: HistoryService
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var repository: RepositoryService
    @Autowired lateinit var accounts: AiAccounts
    @Autowired lateinit var exits: SmithExitNotifier
    @Autowired lateinit var github: TicketRecorder
    @Autowired lateinit var sandbox: ScriptedSandbox
    @Autowired lateinit var production: FakeProductionCheck
    @Autowired lateinit var deploys: DeployReachedWaits
    @Autowired lateinit var frames: CheckEventWaits
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var hitlpRequests: HitlpRequests
    @Autowired lateinit var hitlpPoller: HitlpPoller

    private val repo = "acme/tickets"
    private val t0: Instant = Instant.parse("2031-03-03T10:00:00Z")
    private val started = mutableListOf<String>()

    @BeforeEach
    fun clear() {
        ClockUtil.setCurrentTime(Date.from(t0))
        accounts.ensureTestAccount(); repos.add(repo)
        github.comments.clear(); github.closed.clear(); github.created.clear()
        production.deployedShas.clear(); production.asked.clear()
    }

    @AfterEach
    fun cleanUp() {
        started.forEach { id -> runCatching { runtime.deleteProcessInstance(id, "test done") } }
        ClockUtil.reset()
        accounts.dropTestAccount()
    }

    private fun runAllJobs() = management.runAllJobs(exits, messages = true)

    private fun start(issue: Long, sha: String, section: PostDeployCheck = PostDeployCheck("The board shows the new column.")): String =
        runtime.startProcessInstanceByKey("post-deploy-check", mapOf(
            Vars.REPO to repo, Vars.ISSUE to issue, Vars.PR to issue + 1000, Vars.PR_URL to "https://github.com/$repo/pull/${issue + 1000}",
            Vars.ROUND to 1, Vars.MERGE_SHA to sha, Vars.POST_DEPLOY_CHECK to section.toBytes(),
        )).id.also { started += it; runAllJobs() }

    private fun active(id: String): List<String> = runtime.getActiveActivityIds(id)
    private fun timer(id: String, activity: String): Job? =
        management.createJobQuery().timers().processInstanceId(id).activityId(activity).singleResult()
    private fun fire(job: Job?) { management.executeJob(assertNotNull(job).id); runAllJobs() }
    private fun checks() = sandbox.started.count { it.mode == "check" }
    private fun deployedAndChecking(issue: Long, sha: String): String {
        val id = start(issue, sha)
        deploys.wake(repo, sha); runAllJobs()
        return id
    }

    private val passed = """{"outcome":"passed","summary":"The column is there."}"""
    private fun notYet(wake: String? = null) = """{"outcome":"not_yet","summary":"No board load since the deploy."${wake?.let { ""","wake":$it""" }.orEmpty()}}"""

    @Test
    fun `a deploy of the merge reaches the check, which passes, and the ticket closes naming it`() {
        sandbox.reset("check" to passed)
        val id = start(701, "a701a701")
        assertEquals(listOf("receive-deploy-reached"), active(id), "not in production: the flow waits, no Smith")
        assertEquals(listOf(repo to "a701a701"), production.asked)
        assertNotNull(timer(id, "event-safety-poll"))
        assertEquals(0, checks())

        assertEquals(emptyList(), deploys.wake("acme/other", "a701a701"))
        assertEquals(listOf(id), deploys.wake(repo, "A701A701FFFF"))
        runAllJobs()

        val spec = sandbox.started.single()
        assertEquals("check", spec.mode)
        val check = assertNotNull(brief(spec)!!.postDeployCheck)
        assertEquals("a701a701", check["mergeSha"])
        assertEquals("a701a701ffff", (check["deployEvent"] as Map<*, *>)["sha"])
        assertTrue(history.finished(id))
        assertEquals(listOf(repo to 701L), github.closed)
        val close = github.commentsOn(701).last()
        assertTrue("Merged #1701" in close && "Post-deployment check passed — The column is there." in close, close)
    }

    @Test
    fun `the safety poll finds the merge deployed and reaches the check`() {
        sandbox.reset("check" to passed)
        val id = start(702, "a702a702")
        fire(timer(id, "event-safety-poll"))
        assertEquals(listOf("receive-deploy-reached"), active(id), "still not deployed: back to the wait")
        assertNotNull(timer(id, "event-safety-poll"), "a new 12 h poll")
        assertEquals(0, checks())

        production.deployedShas += "a702a702"
        val poll = assertNotNull(timer(id, "event-safety-poll"))
        assertEquals(t0.plus(Duration.ofHours(12)), poll.duedate.toInstant())
        fire(poll)
        assertEquals(1, checks())
        assertEquals("poll", (brief(sandbox.started.single())!!.postDeployCheck!!["deployEvent"] as Map<*, *>)["type"])
        assertTrue(history.finished(id))
    }

    @Test
    fun `a merge already in production at the start goes straight to the check`() {
        sandbox.reset("check" to passed)
        production.deployedShas += "a703a703"
        val id = start(703, "a703a703")
        assertEquals(1, checks())
        assertTrue(history.finished(id))
    }

    @Test
    fun `a failed check files its bug and the ticket closes naming the bug`() {
        sandbox.reset("check" to """{"outcome":"failed","summary":"The column is missing.","bug":{"title":"Board column missing in production","body":"It is not there."}}""")
        val id = deployedAndChecking(704, "a704a704")
        assertTrue(history.finished(id))
        val (bugRepo, bug) = github.created.single()
        assertEquals(repo, bugRepo)
        assertEquals("Board column missing in production", bug.title)
        val close = github.commentsOn(704).last()
        assertTrue(Regex("""Post-deployment check failed; bug filed as #\d+ — The column is missing\.""").containsMatchIn(close), close)
        assertEquals(listOf(repo to 704L), github.closed)
    }

    @Test
    fun `not yet with the Smith's time waits for it, then checks again`() {
        sandbox.reset("check" to notYet("""{"at":"2031-03-05T08:00:00Z"}"""), "check" to passed)
        val id = deployedAndChecking(705, "a705a705")
        assertEquals(listOf("event-check-at"), active(id))
        val at = assertNotNull(timer(id, "event-check-at"))
        assertEquals(Instant.parse("2031-03-05T08:00:00Z"), at.duedate.toInstant())
        assertNotNull(timer(id, "event-deadline"), "the deadline bounds the wait")
        fire(at)
        assertEquals(2, checks())
        assertEquals(1, brief(sandbox.started.last())!!.postDeployCheck!!["history"].let { it as List<*> }.size)
        assertTrue(history.finished(id))
    }

    @Test
    fun `not yet on an event waits for a matching bus frame, with the ladder's time as the fallback`() {
        sandbox.reset("check" to notYet("""{"event":"needs:kind=question"}"""), "check" to passed)
        val id = deployedAndChecking(706, "a706a706")
        assertEquals(listOf("receive-check-event"), active(id))
        assertNotNull(timer(id, "event-check-fallback"), "the ladder's time, should the frame never come")
        assertEquals(emptyList(), frames.wake(repo, "event: needs\ndata: {\"kind\":\"merge\"}\n\n"))
        assertEquals(emptyList(), frames.wake("acme/other", "event: needs\ndata: {\"kind\":\"question\"}\n\n"))
        assertEquals(1, checks())
        assertEquals(listOf(id), frames.wake(repo, "event: needs\ndata: {\"kind\":\"question\"}\n\n"))
        runAllJobs()
        assertEquals(2, checks())
        assertTrue(history.finished(id))
    }

    @Test
    fun `not yet with no hint waits by the ladder, an hour after the first run`() {
        sandbox.reset("check" to notYet(), "check" to passed)
        val before = Instant.now()
        val id = deployedAndChecking(707, "a707a707")
        assertEquals(listOf("event-check-at"), active(id))
        val at = assertNotNull(timer(id, "event-check-at")).duedate.toInstant()
        // nextCheck's own clock is the wall clock's: an hour after the run, give or take the test's own time.
        assertTrue(at >= before.plus(Duration.ofMinutes(59)) && at <= Instant.now().plus(Duration.ofMinutes(61)), "$at")
        fire(timer(id, "event-check-at"))
        assertEquals(2, checks())
        assertTrue(history.finished(id))
    }

    private fun atDeadline(issue: Long, sha: String): String {
        val id = deployedAndChecking(issue, sha)
        assertEquals(listOf("event-check-at"), active(id))
        val deadline = assertNotNull(timer(id, "event-deadline"))
        assertEquals(t0.plus(Duration.ofDays(7)), deadline.duedate.toInstant(), "no Deadline: in the section: the start plus 7 days")
        ClockUtil.setCurrentTime(Date.from(t0.plus(Duration.ofDays(7))))
        fire(deadline)
        assertEquals(listOf("user-task-answer-deadline"), active(id))
        assertNull(timer(id, "event-check-at"), "the check loop is over while the user decides")
        val asked = github.commentsOn(issue).last()
        assertTrue("reached its deadline without passing after 1 run(s)" in asked && "id: wait" in asked && "id: drop" in asked && "id: bug" in asked, asked)
        val request = hitlpRequests.openFor(id).single()
        assertEquals("DECISION", request.kind)
        assertEquals("user-task-answer-deadline", request.userTaskKey)
        return id
    }

    @Test
    fun `at the deadline the user answers wait, and the check goes on with a new deadline`() {
        sandbox.reset("check" to notYet(), "check" to notYet())
        val id = atDeadline(708, "a708a708")
        decideWait(SharedHitlpStub.STUB, hitlpRequests, hitlpPoller, id, "wait")
        runAllJobs()
        assertEquals(2, checks(), "a wait checks again at once")
        assertEquals(listOf("event-check-at"), active(id))
        assertEquals(t0.plus(Duration.ofDays(14)), assertNotNull(timer(id, "event-deadline")).duedate.toInstant())
        assertEquals("wait", runtime.getVariable(id, Vars.CHECK_DEADLINE_ANSWER))
        assertEquals(emptyList(), github.closed)
    }

    @Test
    fun `at the deadline the user answers drop, and the ticket closes saying so`() {
        sandbox.reset("check" to notYet())
        val id = atDeadline(709, "a709a709")
        decideWait(SharedHitlpStub.STUB, hitlpRequests, hitlpPoller, id, "drop")
        runAllJobs()
        assertTrue(history.finished(id))
        assertEquals(1, checks())
        assertEquals(emptyList(), github.created)
        val close = github.commentsOn(709).last()
        assertTrue("Post-deployment check dropped at its deadline by the user; its last run: not_yet" in close, close)
        assertEquals(listOf(repo to 709L), github.closed)
    }

    @Test
    fun `at the deadline the user answers bug, and a bug is filed and the ticket closes naming it`() {
        sandbox.reset("check" to notYet())
        val id = atDeadline(710, "a710a710")
        decideWait(SharedHitlpStub.STUB, hitlpRequests, hitlpPoller, id, "bug")
        runAllJobs()
        assertTrue(history.finished(id))
        val bug = github.created.single().second
        assertEquals("Post-deployment check of #710 never passed", bug.title)
        val close = github.commentsOn(710).last()
        assertTrue(Regex("""Post-deployment check did not pass by its deadline; bug filed as #\d+""").containsMatchIn(close), close)
    }

    @Test
    fun `a redeploy mid-wait keeps the flow on its version and its timers, which still fire`() {
        sandbox.reset("check" to notYet("""{"at":"2031-03-04T09:00:00Z"}"""), "check" to passed)
        val id = deployedAndChecking(711, "a711a711")
        val definition = runtime.createProcessInstanceQuery().processInstanceId(id).singleResult().processDefinitionId
        val wake = assertNotNull(timer(id, "event-check-at"))
        val deadline = assertNotNull(timer(id, "event-deadline"))

        // What a restart on a new build does: the diagram is deployed again, as a new version.
        val deployment = repository.createDeployment().name("restart").addClasspathResource("post-deploy-check.bpmn").deploy()
        try {
            assertTrue(repository.createProcessDefinitionQuery().processDefinitionKey("post-deploy-check").latestVersion().singleResult().id != definition)
            assertEquals(definition, runtime.createProcessInstanceQuery().processInstanceId(id).singleResult().processDefinitionId)
            assertEquals(wake.duedate, assertNotNull(timer(id, "event-check-at")).duedate)
            assertEquals(deadline.duedate, assertNotNull(timer(id, "event-deadline")).duedate)
            assertEquals(listOf("event-check-at"), active(id))
            fire(timer(id, "event-check-at"))
            assertEquals(2, checks())
            assertTrue(history.finished(id))
        } finally {
            repository.deleteDeployment(deployment.id, true)
        }
    }
}

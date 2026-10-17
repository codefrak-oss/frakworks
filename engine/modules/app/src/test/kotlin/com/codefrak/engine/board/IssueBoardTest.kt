package com.codefrak.engine.board

import com.codefrak.engine.github.Blocker
import com.codefrak.engine.github.IssueSnapshot
import com.codefrak.engine.intake.IntakeQuestions
import com.codefrak.engine.intake.IntakeView
import com.codefrak.engine.repos.Deferred
import com.codefrak.engine.repos.DeferredIssues
import com.codefrak.engine.repos.HandledIssues
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.workitems.ListedTickets
import com.codefrak.engine.workitems.WorkItemStore
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.variable.Variables
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import com.codefrak.engine.intake.IntakeTickets
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Jobs are not executed, so a started `smith-run` stays at its first (async) step: a flow that waits. */
@SpringBootTest
class IssueBoardTest {
    @TestConfiguration
    class Fakes {
        @Bean @Primary fun tickets(store: WorkItemStore, jdbc: JdbcClient) = ListedTickets(store, jdbc)
    }

    @Autowired lateinit var board: IssueBoard
    @Autowired lateinit var controller: IssueBoardController
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var tickets: ListedTickets
    @Autowired lateinit var handled: HandledIssues
    @Autowired lateinit var deferred: DeferredIssues
    @Autowired lateinit var questions: IntakeQuestions
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var links: IntakeTickets

    private val repo = "acme/widgets"
    private val t0 = OffsetDateTime.parse("2026-09-24T10:00:00Z")

    @BeforeEach
    fun reset() {
        listOf("engine.handled_issue", "engine.deferred_issue", "engine.intake_question", "engine.intake_ticket", "engine.target_repo", "engine.smith_run_stats").forEach { jdbc.sql("DELETE FROM $it").update() }
        tickets.reset()
        repos.add(repo)
    }

    private fun issue(n: Long, vararg labels: String, minutes: Long = n) {
        tickets.list(repo, n, "Issue $n", labels.toList(), t0, t0.plusMinutes(minutes))
    }

    @Test
    fun `lists every open issue of every target repo, newest update first, with its kind and approval`() {
        issue(1, "chore"); issue(2, "feature"); issue(3, "chore", "authorized")
        val issues = board.board().issues
        assertEquals(listOf(3L, 2L, 1L), issues.map { it.number })
        assertEquals(listOf("chore", "feature", "chore"), issues.map { it.kind })
        assertEquals(listOf(true, false, false), issues.map { it.authorized })
        assertTrue(issues.all { it.flow == null && it.pendingQuestion == null })
        assertEquals("https://github.com/$repo/issues/2", issues[1].url)
    }

    @Test
    fun `repo= keeps only that target repo's open issues and reads only its listing`() {
        issue(1, "chore")
        repos.add("acme/gadgets")
        tickets.list("acme/gadgets", 7, "Gadget 7", listOf("chore"), t0, t0)

        tickets.listed.clear()
        val only = controller.issues("open", 100, "Acme/Gadgets", null) as Board
        assertEquals(listOf("acme/gadgets" to 7L), only.issues.map { it.repo to it.number })
        assertEquals(listOf("acme/gadgets"), tickets.listed, "the other repo is not read")

        tickets.listed.clear()
        val all = controller.issues("open", 100, null, null) as Board
        assertEquals(setOf(repo to 1L, "acme/gadgets" to 7L), all.issues.map { it.repo to it.number }.toSet())
        assertEquals(setOf(repo, "acme/gadgets"), tickets.listed.toSet())

        assertFailsWith<ResponseStatusException> { controller.issues("open", 100, "acme/nowhere", null) }
            .also { assertEquals(400, it.statusCode.value()) }
    }

    @Test
    fun `joins the latest approval and where its flow is`() {
        issue(4, "chore", "authorized")
        handled.claim(repo, 4, 100, "chore", "refused", "no access")
        assertEquals("refused" to "no access", board.board().issues.single().flow!!.let { it.outcome to it.detail })

        // A later approval started a flow: a running process whose current step the board reports.
        val running = runtime.startProcessInstanceByKey("smith-run")
        handled.claim(repo, 4, 101, "chore", "started", null)
        handled.started(repo, 4, 101, running.id)
        val flow = board.board().issues.single().flow!!
        assertEquals("started", flow.outcome)
        assertEquals(running.id, flow.instance)
        assertNotNull(flow.startedAt)
        assertFalse(flow.ended)
        assertEquals("start smith", flow.step)
        assertNull(flow.prUrl)
        assertEquals("task-start-smith", flow.stage, "a flow with no called process: its own activity")

        assertNull(board.board().issues.single().askedBy, "no row says who asked yet")

        // And one that ran through: ended, with no step, and its variables where they apply.
        issue(5, "chore", "authorized")
        val done = runtime.startProcessInstanceByKey("hello", mapOf("prUrl" to "https://github.com/$repo/pull/9"))
        handled.claim(repo, 5, 102, "chore", "started", null, "alice")
        assertEquals("alice", board.board().issues.first { it.number == 5L }.askedBy, "the latest row's asker")
        handled.started(repo, 5, 102, done.id)
        val ended = board.board().issues.first { it.number == 5L }.flow!!
        assertTrue(ended.ended)
        assertNotNull(ended.endedAt)
        assertNull(ended.step)
        assertNull(ended.stage)
        assertEquals("https://github.com/$repo/pull/9", ended.prUrl)
    }

    @Test
    fun `points is the latest row's that has them, and null for an unpointed issue or one the engine has not seen`() {
        issue(60, "chore"); issue(61, "chore"); issue(62, "chore")
        handled.claim(repo, 60, 600, "chore", "refused", null, null, 5)
        handled.claim(repo, 60, 601, "chore", "started", null)
        handled.claim(repo, 61, 602, "chore", "started", null)
        val rows = board.board().issues.associateBy { it.number }
        assertEquals(5, rows.getValue(60).points, "the latest row that has them, though a later row has none")
        assertNull(rows.getValue(61).points, "filed unpointed")
        assertNull(rows.getValue(62).points, "the engine has not seen it")
    }

    @Test
    fun `a proposal flow's pitch is the page its stored propose result names, and any other flow has none`() {
        issue(12, "proposal", "authorized"); issue(13, "chore", "authorized")
        val result = """{"outcome":"drafted","path":"docs/proposals/x.md","pitch":"docs/proposals/x.html","summary":"X."}"""
        val proposal = runtime.startProcessInstanceByKey("hello", mapOf("proposal" to Variables.byteArrayValue(result.toByteArray())))
        handled.claim(repo, 12, 400, "proposal", "started", null)
        handled.started(repo, 12, 400, proposal.id)
        val chore = runtime.startProcessInstanceByKey("hello", mapOf("proposal" to Variables.byteArrayValue(result.toByteArray())))
        handled.claim(repo, 13, 401, "chore", "started", null)
        handled.started(repo, 13, 401, chore.id)
        val issues = board.board().issues.associateBy { it.number }
        assertEquals("docs/proposals/x.html", issues[12L]!!.flow!!.pitch)
        assertNull(issues[13L]!!.flow!!.pitch)
    }

    @Test
    fun `stage is the outer flow's activity while its called Smith run waits at its own step`() {
        issue(11, "chore", "authorized")
        val chore = runtime.createProcessInstanceByKey("chore").startBeforeActivity("call-do-work").execute()
        handled.claim(repo, 11, 300, "chore", "started", null)
        handled.started(repo, 11, 300, chore.id)
        assertEquals("call-do-work", board.board().issues.single().flow!!.stage, "waiting on its async job, before the call")

        // Run the call activity's job: it starts the Smith run, which stops at its own first (async) step.
        management.executeJob(management.createJobQuery().processInstanceId(chore.id).singleResult().id)
        assertNotNull(runtime.createProcessInstanceQuery().superProcessInstanceId(chore.id).singleResult())
        val flow = board.board().issues.single().flow!!
        assertEquals("call-do-work", flow.stage)
        assertEquals("start smith", flow.step)
        runtime.deleteProcessInstance(chore.id, "test cleanup")
    }

    @Test
    fun `a running flow says when its stage started, how long it should take and which round it is, and an ended one does not`() {
        issue(13, "chore", "authorized")
        val chore = runtime.createProcessInstanceByKey("chore").startBeforeActivity("call-do-work").execute()
        handled.claim(repo, 13, 500, "chore", "started", null)
        handled.started(repo, 13, 500, chore.id)
        val waiting = board.board().issues.single().flow!!
        assertNotNull(waiting.stageStartedAt, "waiting on its async job: since the flow started")
        assertEquals(StageEstimates.DEFAULTS.getValue("work"), waiting.stageEstimateMs, "an empty smith_run_stats: the default for work")
        assertEquals("default", waiting.stageEstimateBasis); assertEquals(0, waiting.stageEstimateRuns)
        assertEquals(1, waiting.stageRound)

        management.executeJob(management.createJobQuery().processInstanceId(chore.id).singleResult().id)
        val running = board.board().issues.single().flow!!
        assertNotNull(running.stageStartedAt)
        assertTrue(!running.stageStartedAt!!.isBefore(waiting.stageStartedAt), "the call activity's own start")
        assertEquals(StageEstimates.DEFAULTS.getValue("work"), running.stageEstimateMs)
        assertEquals(1, running.stageRound)
        runtime.deleteProcessInstance(chore.id, "test cleanup")

        issue(14, "chore", "authorized")
        val done = runtime.startProcessInstanceByKey("hello")
        handled.claim(repo, 14, 501, "chore", "started", null)
        handled.started(repo, 14, 501, done.id)
        val ended = board.board().issues.first { it.number == 14L }.flow!!
        assertTrue(ended.ended)
        assertNull(ended.stageStartedAt); assertNull(ended.stageEstimateMs); assertNull(ended.stageRound)
        assertNull(ended.stageEstimateBasis); assertNull(ended.stageEstimateRuns)
    }

    @Test
    fun `a flow stuck on an incident, in itself or a process it called, carries it, and one that is not carries none`() {
        issue(12, "chore", "authorized")
        val chore = runtime.createProcessInstanceByKey("chore").startBeforeActivity("call-do-work").execute()
        handled.claim(repo, 12, 400, "chore", "started", null)
        handled.started(repo, 12, 400, chore.id)
        assertNull(board.board().issues.single().flow!!.incident)

        // The call starts the Smith run; its first job then fails for good: an incident in the called process.
        management.executeJob(management.createJobQuery().processInstanceId(chore.id).singleResult().id)
        val job = management.createJobQuery().rootProcessInstanceId(chore.id).singleResult()
        management.setJobRetries(job.id, 0)
        val incident = board.board().issues.single().flow!!.incident!!
        assertEquals(job.processInstanceId, incident.processInstanceId)
        assertTrue(incident.processInstanceId != chore.id, "the called Smith run's, followed down from the chore")
        assertEquals("task-start-smith", incident.activityId)
        assertEquals("failedJob", incident.type)
        assertNotNull(incident.createdAt)
        runtime.deleteProcessInstance(chore.id, "test cleanup")
    }

    @Test
    fun `a start whose instance the history no longer has still shows its outcome`() {
        issue(5, "chore", "authorized")
        handled.claim(repo, 5, 200, "chore", "started", null)
        handled.started(repo, 5, 200, "gone")
        val flow = board.board().issues.single().flow!!
        assertEquals("started" to "gone", flow.outcome to flow.instance)
        assertNull(flow.startedAt)
    }

    @Test
    fun `an intake waiting on the user carries its question`() {
        issue(6, "intake")
        questions.ask("intake-6", repo, 6, "Which readme?")
        assertEquals("Which readme?", board.board().issues.single().pendingQuestion!!.question)
        questions.answer(questions.pending("intake-6")!!.id, "the top one", "lundmatt1")
        assertNull(board.board().issues.single().pendingQuestion)
    }

    @Test
    fun `an intake started by POST-intake with no handled_issue row still shows its flow`() {
        issue(8, "intake")
        val url = "https://github.com/$repo/issues/8"
        val running = runtime.createProcessInstanceByKey("intake")
            .startBeforeActivity("event-intake-authorized").setVariable("issueUrl", url).execute()
        links.opened(running.id, repo, 8, url)
        val flow = board.board().issues.single().flow!!
        assertEquals("intake", flow.flow)
        assertEquals(running.id, flow.instance)
        assertNull(flow.outcome)
        assertNull(flow.detail)
        assertNotNull(flow.startedAt)
        assertFalse(flow.ended)
        assertNotNull(flow.step)

        runtime.deleteProcessInstance(running.id, "test cleanup")
        issue(9, "intake", minutes = 20)
        val doneUrl = "https://github.com/$repo/issues/9"
        val ended = runtime.createProcessInstanceByKey("intake")
            .startBeforeActivity("end-intake-closed").setVariable("issueUrl", doneUrl).execute()
        links.opened(ended.id, repo, 9, doneUrl)
        val endedFlow = board.board().issues.first { it.number == 9L }.flow!!
        assertEquals("intake", endedFlow.flow)
        assertEquals(ended.id, endedFlow.instance)
        assertTrue(endedFlow.ended)
        assertNotNull(endedFlow.endedAt)
        assertNull(endedFlow.step)
    }

    @Test
    fun `no handled_issue row and no matching intake instance leaves flow null`() {
        issue(10, "feature")
        assertNull(board.board().issues.single().flow)
    }

    @Test
    fun `a repo GitHub cannot list is reported, and the others still are`() {
        repos.add("acme/broken"); tickets.unlistable += "acme/broken"
        issue(7)
        val b = board.board()
        assertEquals(listOf(7L), b.issues.map { it.number })
        assertEquals(listOf("acme/broken: GitHub 502"), b.failures)
    }

    @Test
    fun `shows each issue's blockers, and an approval waiting on one as deferred`() {
        issue(30, "chore", "authorized"); issue(31, "chore"); issue(32, "chore")
        issue(29, "chore"); tickets.blockBy(repo, 30, 31); tickets.blockBy(repo, 30, 29); tickets.close(repo, 29)
        handled.claim(repo, 30, 100, "chore", "refused", "an older approval")
        deferred.defer(Deferred(repo, 30, 101, "lundmatt1", "chore", null, IssueSnapshot(30, "Issue 30", "", "someone"),
            listOf(Blocker(repo, 31, "open"))))
        val issues = board.board().issues.associateBy { it.number }
        val waiting = issues.getValue(30)
        assertEquals(setOf(BlockedBy(31, "open"), BlockedBy(29, "closed")), waiting.blockedBy.toSet())
        assertTrue(waiting.deferred)
        assertEquals(Triple("chore", "deferred", "waiting on #31"), waiting.flow!!.let { Triple(it.flow, it.outcome, it.detail) })
        assertFalse(waiting.flow!!.ended)
        assertEquals(emptyList(), issues.getValue(31).blockedBy)
        assertFalse(issues.getValue(31).deferred)
        assertNull(issues.getValue(32).flow)
    }

    @Test
    fun `row() is the matching entry of board() for a flow, a deferred approval and a split parent, and null for one GitHub does not list`() {
        // A running flow at its first step.
        issue(50, "chore", "authorized")
        val running = runtime.startProcessInstanceByKey("smith-run", mapOf("repo" to repo, "issue" to 50L))
        handled.claim(repo, 50, 500, "chore", "started", null)
        handled.started(repo, 50, 500, running.id)
        // An approval waiting on a blocker.
        issue(51, "chore", "authorized"); issue(52, "chore")
        tickets.blockBy(repo, 51, 52)
        deferred.defer(Deferred(repo, 51, 501, "lundmatt1", "chore", null, IssueSnapshot(51, "Issue 51", "", "someone"),
            listOf(Blocker(repo, 52, "open"))))
        // A split parent with a filed child, which carries the parent back.
        issue(53, "feature", "authorized"); issue(54, "chore", "authorized")
        val split = com.codefrak.engine.feature.SplitState(filed = listOf(
            com.codefrak.engine.feature.FiledChild(0, "Part one", 54, "https://github.com/$repo/issues/54", "chore"),
            com.codefrak.engine.feature.FiledChild(1, "Part two", 57, "https://github.com/$repo/issues/57", "chore")),
            drafts = listOf(com.codefrak.engine.feature.ChildDraft("Part three", "", "chore", dependsOn = emptyList())))
        // #57 was filed with story points; #54 unpointed; the draft is not filed yet.
        handled.claim(repo, 57, 504, "chore", "ignored", null, null, 5)
        val parent = runtime.startProcessInstanceByKey("hello", mapOf("split" to Variables.byteArrayValue(split.bytes())))
        handled.claim(repo, 53, 502, "feature", "started", null)
        handled.started(repo, 53, 502, parent.id)
        // A chore approval on an issue GitHub no longer lists, whose instance happens to carry a `split` naming #54: only a
        // feature's split is read for a parent, so row(54) gets #53 back as board() does, and this costs row() no history read.
        val stray = com.codefrak.engine.feature.SplitState(filed = listOf(
            com.codefrak.engine.feature.FiledChild(0, "Not a part", 54, "https://github.com/$repo/issues/54", "chore")))
        val chore = runtime.startProcessInstanceByKey("hello", mapOf("split" to Variables.byteArrayValue(stray.bytes())))
        handled.claim(repo, 55, 503, "chore", "started", null)
        handled.started(repo, 55, 503, chore.id)

        val listed = board.board().issues.associateBy { it.number }
        for (n in listOf(50L, 51L, 53L, 54L)) assertEquals(listed.getValue(n), board.row(repo, n), "row of #$n")
        assertEquals("start smith", board.row(repo, 50)!!.flow!!.step)
        assertTrue(board.row(repo, 51)!!.deferred)
        assertEquals(listOf(null, 5, null), board.row(repo, 53)!!.children.map { it.points }, "points per part: unpointed, pointed, pending")
        assertEquals(ParentView(53, "https://github.com/$repo/issues/53"), board.row(repo, 54)!!.parent)

        tickets.close(repo, 50)
        assertNull(board.row(repo, 50), "closed on GitHub")
        assertNull(board.row(repo, 99), "never listed")
        tickets.unlistable += repo
        assertFailsWith<IllegalStateException> { board.row(repo, 51) }
    }

    @Test
    fun `a ticket an intake filed carries that intake, and a hand-filed one carries none`() {
        issue(40, "feature", "authorized"); issue(41, "chore")
        val intakeUrl = "https://github.com/$repo/issues/39"
        val filed = runtime.createProcessInstanceByKey("intake").startBeforeActivity("end-intake-closed")
            .setVariables(mapOf("repo" to repo, "issue" to 39L, "issueUrl" to intakeUrl, "ticket" to 40L,
                "ticketUrl" to "https://github.com/$repo/issues/40")).execute()
        links.opened(filed.id, repo, 39, intakeUrl); links.filed(filed.id, 40)
        // An intake of another repo naming the same number is not this ticket's.
        val other = runtime.createProcessInstanceByKey("intake").startBeforeActivity("end-intake-closed")
            .setVariables(mapOf("repo" to "acme/elsewhere", "issue" to 5L, "issueUrl" to "https://github.com/acme/elsewhere/issues/5",
                "ticket" to 41L)).execute()
        links.opened(other.id, "acme/elsewhere", 5, "https://github.com/acme/elsewhere/issues/5"); links.filed(other.id, 41)
        val issues = board.board().issues.associateBy { it.number }
        assertEquals(IntakeView(filed.id, 39L, intakeUrl), issues.getValue(40).intake)
        assertNull(issues.getValue(41).intake)
    }
}

package com.codefrak.engine.research

import com.codefrak.engine.ProcessFakes
import com.codefrak.engine.finished
import com.codefrak.engine.brief
import com.codefrak.engine.accounts.AiAccounts
import com.codefrak.engine.board.PitchView
import com.codefrak.engine.board.TicketDetailController
import com.codefrak.engine.chore.FakeGitPush
import com.codefrak.engine.chore.FlowStarter
import com.codefrak.engine.chore.ProcessFlowStarter
import com.codefrak.engine.chore.ScriptedSandbox
import com.codefrak.engine.github.COMPLETED
import com.codefrak.engine.github.IssueSnapshot
import com.codefrak.engine.github.NOT_PLANNED
import com.codefrak.engine.hitlp.SharedHitlpStub
import com.codefrak.engine.hitlp.decideWait
import com.codefrak.engine.intake.TicketRecorder
import com.codefrak.engine.intake.IntakeController
import com.codefrak.engine.intake.IntakeQuestions
import com.codefrak.engine.jev.FakeJevDecider
import com.codefrak.engine.jev.JevCall
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.proposal.PitchRulesTest
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.runAllJobs
import com.codefrak.engine.smith.BUNDLE_FILE
import com.codefrak.engine.smith.ensureTestAccount
import com.codefrak.engine.smith.dropTestAccount
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.TaskService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** bpmn/research.bpmn end to end: GitHub, git and Docker faked, jobs driven by hand. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ProcessFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class ResearchProcessTest {
    @Autowired lateinit var hitlpRequests: com.codefrak.engine.hitlp.HitlpRequests
    @Autowired lateinit var hitlpPoller: com.codefrak.engine.hitlp.HitlpPoller
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var tasks: TaskService
    @Autowired lateinit var accounts: AiAccounts
    @Autowired lateinit var history: HistoryService
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var exits: com.codefrak.engine.smith.SmithExitNotifier
    @Autowired lateinit var github: TicketRecorder
    @Autowired lateinit var git: FakeGitPush
    @Autowired lateinit var sandbox: ScriptedSandbox
    @Autowired lateinit var starter: FlowStarter
    @Autowired lateinit var intakes: IntakeController
    @Autowired lateinit var questions: IntakeQuestions
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var access: Access
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var jev: FakeJevDecider
    @Autowired lateinit var jevCalls: JevCalls
    @Autowired lateinit var answers: ResearchAnswers
    @Autowired lateinit var detail: TicketDetailController

    private val json = jacksonObjectMapper()
    private val today = LocalDate.now()

    @AfterEach
    fun dropTestAccount() { accounts.dropTestAccount() }

    @BeforeEach
    fun clear() {
        git.reset(); jev.reset(); sandbox.reset()
        github.comments.clear(); github.closed.clear(); github.closedAs.clear(); github.created.clear(); github.labelled.clear()
        listOf("engine.handled_issue", "engine.engine_access", "engine.engine_access_audit", "engine.target_repo", "engine.intake_question", "engine.research_answer")
            .forEach { jdbc.sql("DELETE FROM $it").update() }
        repos.add("acme/tickets")
        access.add("lundmatt1", "test")
        accounts.ensureTestAccount()
    }

    private fun runAllJobs() = management.runAllJobs(exits)

    private fun start(issue: Long, body: String = "How does the engine keep a proposal's draft?"): String =
        starter.start("research", "acme/tickets", IssueSnapshot(issue, "Where drafts live", body, "lundmatt1"), "lundmatt1")!!.also { runAllJobs() }

    /** Decides the HITLP wait [id] is parked on, as [by], through the shared stub and the poller, then runs the flow's jobs. */
    private fun answer(id: String, text: String, by: String = "lundmatt1") {
        decideWait(SharedHitlpStub.STUB, hitlpRequests, hitlpPoller, id, text, by); runAllJobs()
    }
    private fun waitingAt(id: String) = tasks.createTaskQuery().processInstanceId(id).list().map { it.taskDefinitionKey }.toSet()
    private fun pitch(issue: Long) = detail.pitch("acme", "tickets", issue).body as? PitchView
    private fun jevCalls(id: String): List<JevCall> =
        jdbc.sql("SELECT id FROM engine.jev_call WHERE process_instance = :p ORDER BY called_at, id").param("p", id)
            .query(String::class.java).list().filterNotNull().map { jevCalls.get(it)!! }

    private fun js(s: String) = json.writeValueAsString(s)
    private fun answered(round: Int) = """{"outcome":"answered","summary":"Round $round: as process variables.","answer":{"md":${js("# Where drafts live\n\nRound $round: in Vars.PROPOSAL.\n\nCitations\n- Pitches.kt\n\n$today")},"html":${js(PitchRulesTest.EXAMPLE.replace("<h1>", "<h1>Round $round "))}},"questions":[]}"""
    private val ask = """{"outcome":"ask","question":"Which engine, the local or the fleet's?"}"""

    @Test
    fun `research starts on its own authorized label, is a kind a grant can be of, and carries over from an intake`() {
        assertTrue("research" in ProcessFlowStarter.FLOWS)
        assertTrue("research" in ProcessFlowStarter.FROM_INTAKE, "an authorized intake refined as research starts it")
        assertFalse("research" in ProcessFlowStarter.WORK_FLOWS)
    }

    @Test
    fun `a question, an answer kept and served, the ack as a decision, and accept closes completed with nothing pushed`() {
        sandbox.reset("research" to ask, "research" to answered(1))
        val id = start(12)
        assertEquals(setOf("user-task-answer-question"), waitingAt(id))
        assertTrue(github.commentsOn(12).any { "Which engine, the local or the fleet's?" in it }, github.commentsOn(12).toString())
        answer(id, "the local")

        assertEquals(listOf("research", "research"), sandbox.started.map { it.mode })
        assertEquals("the local", brief(sandbox.started[1])!!.answers.single().answer)
        assertNull(sandbox.started[1].inputs[BUNDLE_FILE])
        assertEquals(setOf("user-task-answer-decision"), waitingAt(id))
        assertTrue(github.commentsOn(12).any { it.startsWith("Answer, round 1: Round 1: as process variables.") }, github.commentsOn(12).toString())
        val asked = github.commentsOn(12).last()
        assertTrue("Answer ready (round 1)" in asked && "```ask" in asked && "`save`" in asked && "follow-up" in asked, asked)
        assertNotNull(questions.pending(id))
        val row = answers.latest("acme/tickets", 12)!!
        assertEquals(1, row.round)
        assertTrue("Round 1: in Vars.PROPOSAL" in row.md)
        val page = pitch(12)!!
        assertEquals(listOf("html", null, "", 1), listOf(page.kind, page.path, page.prUrl, page.round))
        assertTrue("<h1>Round 1 " in page.content)

        answer(id, "accept: that settles it")
        assertTrue(history.finished(id))
        assertEquals(COMPLETED, github.closedAs["acme/tickets" to 12L])
        assertTrue("Decided: accepted — that settles it, after 1 round(s)" in github.commentsOn(12).last(), github.commentsOn(12).last())
        assertTrue(git.pushes.isEmpty() && git.commits.isEmpty() && git.edits.isEmpty(), "nothing is pushed")
        assertEquals(1, pitch(12)!!.round, "the page is served after the close")
        assertEquals("verb as written: accept", jevCalls(id).single().outcome)
    }

    @Test
    fun `drop closes not planned`() {
        sandbox.reset("research" to answered(1))
        val id = start(13)
        answer(id, "drop: answered elsewhere")
        assertTrue(history.finished(id))
        assertEquals(NOT_PLANNED, github.closedAs["acme/tickets" to 13L])
        assertTrue("Decided: dropped — answered elsewhere" in github.commentsOn(13).last(), github.commentsOn(13).last())
        assertTrue(git.commits.isEmpty())
    }

    @Test
    fun `save commits the pair on the default branch by the engine, links the commit and closes`() {
        sandbox.reset("research" to answered(1))
        val id = start(14)
        answer(id, "save")
        assertTrue(history.finished(id))
        val commit = git.commits.single()
        assertEquals(listOf("acme/tickets", "main", "main", "Research #14: Where drafts live"), listOf(commit.repo, commit.branch, commit.default, commit.message))
        assertEquals(listOf("bots/research/$today-where-drafts-live.md", "bots/research/$today-where-drafts-live.html"), commit.files.keys.toList())
        assertTrue("Round 1: in Vars.PROPOSAL" in commit.files.values.first())
        assertTrue(github.commentsOn(14).any { "Saved as `bots/research/$today-where-drafts-live.md`" in it && "commit/commit-1" in it }, github.commentsOn(14).toString())
        assertTrue("saved in https://github.com/acme/tickets/commit/commit-1" in github.commentsOn(14).last(), github.commentsOn(14).last())
        assertEquals(COMPLETED, github.closedAs["acme/tickets" to 14L])
        assertTrue(git.pushes.isEmpty())
    }

    @Test
    fun `save under docs is taken, and a path elsewhere is refused and the ack asked again`() {
        sandbox.reset("research" to answered(1))
        val id = start(15)
        answer(id, "save: engine/x")
        assertEquals(setOf("user-task-answer-decision"), waitingAt(id))
        assertTrue(git.commits.isEmpty())
        assertTrue(github.commentsOn(15).any { it.startsWith("Not saved: `engine/x`") }, github.commentsOn(15).toString())
        assertTrue(github.commentsOn(15).last().startsWith("Not saved: `engine/x`"), "the re-ask says why first: ${github.commentsOn(15).last()}")
        assertNotNull(questions.pending(id), "the decision need is back")
        answer(id, "save: docs/notes/")
        assertTrue(history.finished(id))
        assertEquals("docs/notes/$today-where-drafts-live.md", git.commits.single().files.keys.first())
        assertEquals(COMPLETED, github.closedAs["acme/tickets" to 15L])
    }

    @Test
    fun `free text, a pick below the floor and a failed jev call are follow-ups, and round 2 carries the thread, never a re-ask`() {
        sandbox.reset("research" to answered(1), "research" to answered(2), "research" to answered(3), "research" to answered(4))
        val id = start(16)
        answer(id, "And on H2, where the history query differs?")
        assertEquals(listOf("research", "research"), sandbox.started.map { it.mode })
        val second = brief(sandbox.started[1])!!
        assertEquals(1, second.research!!.single()["round"])
        assertTrue("Round 1: in Vars.PROPOSAL" in second.research!!.single()["md"] as String)
        assertEquals("And on H2, where the history query differs?", second.answers.last().answer)
        assertEquals(setOf("user-task-answer-decision"), waitingAt(id))
        assertEquals(2, pitch(16)!!.round)
        assertTrue(github.commentsOn(16).any { it.startsWith("Answer, round 2:") })
        assertTrue(github.commentsOn(16).none { "was not understood" in it })
        val first = jevCalls(id).single()
        assertEquals(listOf("research-decision", "no answer, rule: follow-up"), listOf(first.useCase, first.action))

        jev.choice = "accept" to 0.5
        answer(id, "Looks about right")
        assertEquals(3, sandbox.started.size, "below the floor: a third round, not a re-ask")
        assertEquals(2, brief(sandbox.started[2])!!.research!!.size)
        assertEquals("below floor 0.8, rule: follow-up", jevCalls(id).last().action)

        jev.noAccount = true
        answer(id, "And the desktop?")
        assertEquals(4, sandbox.started.size, "no jev answer: a fourth round")
        assertEquals(4, pitch(16)!!.round)
        jev.noAccount = false

        jev.choice = "accept" to 0.9
        answer(id, "Looks right, thanks")
        assertTrue(history.finished(id))
        assertEquals("took accept", jevCalls(id).last().action)
        assertEquals(COMPLETED, github.closedAs["acme/tickets" to 16L])
        assertEquals(4, answers.thread("acme/tickets", 16).size)
        assertTrue(git.pushes.isEmpty() && git.commits.isEmpty())
    }
}

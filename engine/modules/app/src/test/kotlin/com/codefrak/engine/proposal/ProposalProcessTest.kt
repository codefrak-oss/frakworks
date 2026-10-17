package com.codefrak.engine.proposal

import com.codefrak.engine.TestTokens
import com.codefrak.engine.ProcessFakes
import com.codefrak.engine.finished
import com.codefrak.engine.brief
import com.codefrak.engine.accounts.AiAccounts
import com.codefrak.engine.runAllJobs
import com.codefrak.engine.chore.FakeGitHubPulls
import com.codefrak.engine.chore.FakeGitPush
import com.codefrak.engine.chore.FlowStarter
import com.codefrak.engine.chore.ScriptedSandbox
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.events.EventRecorder
import com.codefrak.engine.github.BundleChanges
import com.codefrak.engine.github.IssueSnapshot
import com.codefrak.engine.github.MergeResult
import com.codefrak.engine.hitlp.SharedHitlpStub
import com.codefrak.engine.hitlp.decideWait
import com.codefrak.engine.intake.CancelRequest
import com.codefrak.engine.github.NOT_PLANNED
import com.codefrak.engine.intake.TicketRecorder
import com.codefrak.engine.intake.IntakeController
import com.codefrak.engine.intake.IntakeQuestions
import com.codefrak.engine.jev.FakeJevDecider
import com.codefrak.engine.jev.JevCall
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.HandledIssues
import com.codefrak.engine.repos.TargetRepos
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
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** bpmn/proposal.bpmn end to end: GitHub, git and Docker faked, jobs driven by hand. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ProcessFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class ProposalProcessTest {
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var tasks: TaskService
    @Autowired lateinit var accounts: AiAccounts
    @Autowired lateinit var history: HistoryService
    @Autowired lateinit var handled: HandledIssues
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var exits: com.codefrak.engine.smith.SandboxExitNotifier
    @Autowired lateinit var github: TicketRecorder
    @Autowired lateinit var pulls: FakeGitHubPulls
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
    @Autowired lateinit var bus: EngineEvents
    @Autowired lateinit var hitlpRequests: com.codefrak.engine.hitlp.HitlpRequests
    @Autowired lateinit var hitlpPoller: com.codefrak.engine.hitlp.HitlpPoller

    private val path = "docs/proposals/widgets.md"
    private val today = LocalDate.now()

    @AfterEach
    fun dropTestAccount() { accounts.dropTestAccount() }

    @BeforeEach
    fun clear() {
        pulls.opened.clear(); pulls.merged.clear(); pulls.closed.clear(); pulls.conflictNext = 0; pulls.comments.clear(); git.reset(); jev.reset()
        github.comments.clear(); github.closed.clear(); github.created.clear(); github.labelled.clear()
        listOf("engine.handled_issue", "engine.engine_access", "engine.engine_access_audit", "engine.target_repo", "engine.intake_question")
            .forEach { jdbc.sql("DELETE FROM $it").update() }
        repos.add("acme/tickets")
        access.add("lundmatt1", "test")
        accounts.ensureTestAccount()
    }

    private fun runAllJobs() = management.runAllJobs(exits)

    /** Started the way the scan and the intake start it; `body` may carry an engine trailer with a tier. */
    private fun start(issue: Long, body: String = "Should we have widgets?"): String =
        starter.start("proposal", "acme/tickets", IssueSnapshot(issue, "Widgets", body, "alice"), "lundmatt1")!!.also { runAllJobs() }

    /** Decides the HITLP wait [id] is parked on, as [by], through the shared stub and the poller, then runs the flow's jobs. */
    private fun answer(id: String, text: String, by: String = "lundmatt1") {
        decideWait(SharedHitlpStub.STUB, hitlpRequests, hitlpPoller, id, text, by); runAllJobs()
    }

    private val pitch = "docs/proposals/widgets.html"

    /** Each drafted run's bundle changes the doc and its pitch alone. */
    private fun drafts(n: Int) = repeat(n) {
        git.changes.addLast(BundleChanges(listOf(path, pitch), null, "# Widgets\n\nWe should.\n", PitchRulesTest.EXAMPLE))
    }

    private fun waitingAt(id: String) = tasks.createTaskQuery().processInstanceId(id).list().map { it.taskDefinitionKey }.toSet()
    private fun ended(id: String) = history.createHistoricActivityInstanceQuery().processInstanceId(id).activityType("noneEndEvent")
        .list().single().activityId

    /** The flow's `engine.jev_call` rows, oldest first. */
    private fun jevCalls(id: String): List<JevCall> =
        jdbc.sql("SELECT id FROM engine.jev_call WHERE process_instance = :p ORDER BY called_at, id").param("p", id)
            .query(String::class.java).list().filterNotNull().map { jevCalls.get(it)!! }

    private val ask = """{"outcome":"ask","question":"Which widgets?"}"""
    private val drafted = """{"outcome":"drafted","path":"docs/proposals/widgets.md","pitch":"docs/proposals/widgets.html","summary":"Widgets, with a cost.","questions":[]}"""
    private val approved = """{"outcome":"approved","summary":"fine","findings":[]}"""
    private val changes = """{"outcome":"changes","summary":"no alternative","findings":[{"ask":"add a real alternative","file":"docs/proposals/widgets.md","line":3}]}"""

    @Test
    fun `a proposal cancelled at its propose run, or at its decision, ends with its issue closed as not planned and an open PR closed`() {
        sandbox.reset("propose" to drafted); sandbox.holdMode = "propose"
        val drafting = start(13)
        assertTrue(intakes.cancel(drafting, CancelRequest(reason = "dropped the idea"), TestTokens.asLogin("lundmatt1")).ended)
        assertEquals(listOf("container-1"), sandbox.stopped)
        assertEquals(0L, runtime.createProcessInstanceQuery().variableValueEquals("issue", 13L).count())
        assertEquals(NOT_PLANNED, github.closedAs["acme/tickets" to 13L])
        assertTrue("@lundmatt1 cancelled this proposal" in github.commentsOn(13).last() && "dropped the idea" in github.commentsOn(13).last())

        sandbox.reset("propose" to drafted, "review" to approved)
        drafts(1)
        val deciding = start(14)
        assertEquals(setOf("user-task-answer-decision"), waitingAt(deciding))
        assertTrue(intakes.cancel(deciding, CancelRequest(reason = "no longer relevant"), TestTokens.asLogin("lundmatt1")).ended)
        assertEquals(listOf(7L), pulls.closed)
        assertEquals(emptyList(), pulls.merged)
        assertEquals(NOT_PLANNED, github.closedAs["acme/tickets" to 14L])
        assertNull(questions.pending(deciding), "no question waits on a cancelled flow")
    }

    @Test
    fun `a question, a draft reviewed before its push, and accept logs the decision, merges and closes`() {
        sandbox.reset("propose" to ask, "propose" to drafted, "review" to approved)
        drafts(1)
        val id = start(12)

        assertEquals(setOf("user-task-answer-question"), waitingAt(id))
        assertTrue(github.commentsOn(12).any { "Which widgets?" in it && "— engine proposal `$id`" in it }, github.commentsOn(12).toString())
        answer(id, "blue ones")

        assertEquals(listOf("propose", "propose", "review"), sandbox.started.map { it.mode })
        assertEquals("blue ones", brief(sandbox.started[1])!!.answers.single().answer, "the answer binds the next draft")
        val review = sandbox.started[2]
        assertEquals("bundle of container-2", review.inputs[BUNDLE_FILE]?.let { String(it) }, "review reads the unpushed draft")
        assertEquals(path, brief(review)!!.proposal!!["path"])
        assertEquals(listOf("acme/tickets", "smith/issue-12", "bundle of container-2"), git.pushes.single().toList(), "pushed once reviewed")
        assertEquals("Proposal: Widgets", pulls.opened.single()[3])
        assertEquals(setOf("user-task-answer-decision"), waitingAt(id))
        val asked = github.commentsOn(12).last()
        assertTrue("ready for your decision: https://github.com/acme/tickets/pull/7 (`$path`)" in asked && "```ask" in asked, asked)
        assertTrue("The pitch: https://github.com/acme/tickets/blob/smith/issue-12/$pitch\n" in asked, asked)
        assertNotNull(questions.pending(id))

        answer(id, "accept: cheap enough")

        assertTrue(history.finished(id))
        assertEquals("end-proposal-decided", ended(id))
        val (repo, branch, file, message, after) = git.edits.single()
        assertEquals(listOf("acme/tickets", "smith/issue-12", path, "Decision log: accepted (#12)"), listOf(repo, branch, file, message))
        assertTrue("Status: accepted" in after && "- $today accepted (#12) — cheap enough" in after, after)
        assertEquals(listOf(7L to "Proposal: Widgets"), pulls.merged)
        assertEquals("acme/tickets" to 12L, github.closed.last())
        assertTrue("Decided: accepted — cheap enough" in github.commentsOn(12).last(), github.commentsOn(12).last())
        assertTrue(github.created.isEmpty(), "no build filed")
    }

    private val withOptions = """{"outcome":"drafted","path":"docs/proposals/widgets.md","pitch":"docs/proposals/widgets.html","summary":"Widgets.","questions":[],"options":[{"id":"a","label":"Blue widgets"},{"id":"b","label":"Red widgets"}]}"""
    private val optionsPage = PitchRulesTest.EXAMPLE.replace("</main>",
        """<section data-option-id="a"><button disabled data-choose="a">Choose this one</button></section>
        <section data-option-id="b"><button disabled data-choose="b">Choose this one</button></section></main>""")


    /** A proposal with options waiting on its decision, on ticket [n], with the scan's row a choice finds it by. */
    private fun waitingWithOptions(n: Long): String {
        sandbox.reset("propose" to withOptions, "review" to approved)
        git.changes.addLast(BundleChanges(listOf(path, pitch), null, "# Widgets\n\nWe should.\n", optionsPage))
        val id = start(n)
        // The scan's row for the start (IssueScanner: claim, then started); starter.start alone writes none.
        handled.claim("acme/tickets", n, 1, "proposal", "started", null, "alice"); handled.started("acme/tickets", n, 1, id)
        assertEquals(setOf("user-task-answer-decision"), waitingAt(id))
        return id
    }

    /** The decision card's accept (engine/README.md, "Proposal"): a structured accept from the decision record, never parsed. */
    @Test
    fun `an accept decided on the hitlp server accepts with no jev call`() {
        val id = waitingWithOptions(15)
        answer(id, "accept")
        assertTrue(github.commentsOn(15).any { "@lundmatt1 answered:\n\naccept" in it }, github.commentsOn(15).toString())
        assertTrue(jev.asked.none { it.component1() == "proposal-decision" }, "a structured decision asks no Jev")
        assertTrue(history.finished(id))
        assertEquals("end-proposal-decided", ended(id))
        assertTrue("Decided: accepted" in github.commentsOn(15).last(), github.commentsOn(15).last())
        assertTrue(github.created.isEmpty(), "nothing built")
        assertEquals("acme/tickets" to 15L, github.closed.last())
    }

    /** The decision card's accept and build auto: the build filed in auto mode and started, with no jev call. */
    @Test
    fun `an accept and build auto decided on the hitlp server files the build in auto mode`() {
        access.add("lundmatt1", "test")
        val id = waitingWithOptions(17)
        answer(id, "accept and build auto")
        assertTrue(jev.asked.none { it.component1() == "proposal-decision" }, "a structured decision asks no Jev")
        assertTrue(history.finished(id))
        val (_, filed) = github.created.single()
        assertEquals(listOf("feature", "feature:auto"), filed.labels)
        assertTrue("Decided: accepted" in github.commentsOn(17).last(), github.commentsOn(17).last())
        val feature = runtime.createProcessInstanceQuery().processDefinitionKey("feature").variableValueEquals("issue", 100L).singleResult()
        assertNotNull(feature, "the build started")
        assertEquals("auto", runtime.getVariable(feature.id, "featureMode"))
        runtime.deleteProcessInstance(feature.id, "test done")
    }

    private val markedA = PitchRulesTest.EXAMPLE.replace("</main>",
        """<section data-option-id="a"><button disabled data-choose="a">Choose this one</button></section></main>""")
    private val withA = """{"outcome":"drafted","path":"docs/proposals/widgets.md","pitch":"docs/proposals/widgets.html","summary":"Widgets.","questions":[],"options":[{"id":"a","label":"Blue widgets"}]}"""
    private fun incidentsUnder(root: String) = runtime.createIncidentQuery().list()
        .filter { history.createHistoricProcessInstanceQuery().processInstanceId(it.processInstanceId).singleResult().rootProcessInstanceId == root }

    /** #1742: a propose result the rules refuse reruns the draft once, the new Smith told why (engine/README.md, "Incidents"). */
    @Test
    fun `a drafted result the rules refuse reruns the draft once, and a conforming redraft goes to review`() {
        sandbox.reset("propose" to drafted, "propose" to withA, "review" to approved)
        repeat(2) { git.changes.addLast(BundleChanges(listOf(path, pitch), null, "# Widgets\n\nWe should.\n", markedA)) }
        val id = start(16)

        assertEquals(emptyList(), incidentsUnder(id), "a first refusal is no incident")
        assertEquals(listOf("propose", "propose", "review"), sandbox.started.map { it.mode })
        val rules = brief(sandbox.started[1])!!.findings.single { it.review == "rules" }
        assertTrue("option 'a' is in the page but not among the result's options" in rules.ask, rules.ask)
        assertTrue(brief(sandbox.started[2])?.findings.orEmpty().none { it.review == "rules" }, "the review is not told the engine's refusal")
        assertTrue(github.commentsOn(16).any { "The engine rejected the draft result" in it && "option 'a' is in the page" in it && "parks on an incident" in it },
            github.commentsOn(16).toString())
        assertEquals(setOf("user-task-answer-decision"), waitingAt(id))
    }

    @Test
    fun `two refused drafted results in a row park on the rules incident`() {
        sandbox.reset("propose" to drafted, "propose" to drafted)
        // Every attempt of the failing job reads the bundle's changes again: two runs, and the incident's retries.
        repeat(6) { git.changes.addLast(BundleChanges(listOf(path, pitch), null, "# Widgets\n\nWe should.\n", markedA)) }
        val id = start(17)

        assertEquals(listOf("propose", "propose"), sandbox.started.map { it.mode })
        // The smith-run's job incident, and the cause incident Operaton raises on the call activity above it.
        val incident = incidentsUnder(id).single { it.id == it.rootCauseIncidentId }
        assertEquals("failedJob", incident.incidentType)
        assertTrue(incident.incidentMessage!!.startsWith("the smith's result breaks the rules: ") &&
            "option 'a' is in the page but not among the result's options" in incident.incidentMessage, incident.incidentMessage)
        runtime.deleteProcessInstance(id, "test done")
    }

    /** The stream (engine/README.md, "Events"): the decision is a `needs` row from its post until its answer. */
    @Test
    fun `the decision is a need from the post until the answer`() = EventRecorder(bus).use { rec ->
        sandbox.reset("propose" to drafted, "review" to approved)
        drafts(1)
        val id = start(14)
        assertEquals(setOf("user-task-answer-decision"), waitingAt(id))
        // Only this flow's frames: another test's flow can end in the same window and announce its own row gone.
        val added = rec.of("needs").filter { it.str("actionId") == id }.single()
        assertEquals(listOf("acme/tickets", "decision", "added", 14L, id), listOf(added.str("repo"), added.str("kind"), added.state, added.long("number"), added.str("actionId")))
        answer(id, "accept")
        assertTrue(history.finished(id))
        rec.await("flow") { it.state == "ended" }
        rec.await("needs") { it.str("kind") == "question" && it.state == "removed" && it.str("actionId") == id }
        val removed = rec.of("needs").filter { it.str("actionId") == id }.drop(1)
        assertEquals(listOf("decision", "removed", id), removed[0].let { listOf(it.str("kind"), it.state, it.str("actionId")) })
        // The ended flow announces its answer row gone too (FlowEvents.flush), after the commit.
        assertEquals(listOf("question", "removed", id), removed.last().let { listOf(it.str("kind"), it.state, it.str("actionId")) })
        assertEquals(emptyList(), rec.of("intake"), "a proposal's answer is no intake step")
    }

    @Test
    fun `review findings redraft before anything is pushed, and revise redrafts from the user's text`() {
        sandbox.reset("propose" to drafted, "review" to changes, "propose" to drafted, "review" to approved,
            "propose" to drafted, "review" to approved)
        drafts(3)
        val id = start(12)

        assertEquals(listOf("propose", "review", "propose", "review"), sandbox.started.map { it.mode })
        assertEquals("add a real alternative", brief(sandbox.started[2])!!.findings.single().ask)
        assertEquals(1, git.pushes.size, "the draft with findings was never pushed")
        assertEquals(setOf("brief.json"), sandbox.started[0].inputs.keys, "round 1 has no draft yet")
        assertEquals("bundle of container-1", sandbox.started[2].inputs[BUNDLE_FILE]?.let { String(it) }, "the redraft starts from the unpushed draft")
        answer(id, "revise: add the cost of a review round")

        assertEquals(listOf("propose", "review", "propose", "review", "propose", "review"), sandbox.started.map { it.mode })
        assertEquals("revise: add the cost of a review round", brief(sandbox.started[4])!!.answers.last().answer)
        assertEquals("bundle of container-3", sandbox.started[4].inputs[BUNDLE_FILE]?.let { String(it) }, "revise starts from the latest draft")
        assertEquals(2, git.pushes.size)
        assertEquals(listOf("Round 2, redrafted: Widgets, with a cost."), pulls.commentsOn(7))
        assertTrue(git.edits.isEmpty(), "revise logs nothing")
        assertEquals(setOf("user-task-answer-decision"), waitingAt(id))

        answer(id, "reject: too big for now")
        assertTrue(history.finished(id))
        assertTrue("Status: rejected" in git.edits.single()[4])
        assertTrue("Decided: rejected — too big for now" in github.commentsOn(12).last())
    }

    @Test
    fun `an answer that is no verb is asked again, quoting it, and shelve logs and merges`() {
        sandbox.reset("propose" to drafted, "review" to approved)
        drafts(1)
        val id = start(12)

        answer(id, "sounds good")
        assertEquals(setOf("user-task-answer-decision"), waitingAt(id))
        assertTrue("was not understood: `sounds good`" in github.commentsOn(12).last(), github.commentsOn(12).last())
        assertTrue(git.edits.isEmpty())

        answer(id, "shelve: after the usage meter")
        assertTrue(history.finished(id))
        val after = git.edits.single()[4]
        assertTrue("Status: shelved" in after && "shelved (#12) — after the usage meter" in after, after)
        assertEquals(listOf(7L to "Proposal: Widgets"), pulls.merged)
    }

    @Test
    fun `accept and build files a feature at the proposal's tier and starts it for a decider with feature access`() {
        access.add("lundmatt1", "test")
        sandbox.reset("propose" to drafted, "review" to approved, "design" to """{"outcome":"ask","question":"Which pane?"}""")
        drafts(1)
        val id = start(12, "Should we have widgets?\n\n<!-- engine: intake #3 · tier 3 · p2 -->\n")
        answer(id, "accept and build")

        assertTrue(history.finished(id))
        val (repo, filed) = github.created.single()
        assertEquals("acme/tickets", repo)
        assertEquals("Widgets", filed.title)
        assertEquals(listOf("feature", "feature:confirm"), filed.labels)
        assertTrue(filed.body.startsWith("Builds: $path\n"), filed.body)
        assertTrue("<!-- engine: proposal #12 · tier 3 · p3 · by alice -->" in filed.body, "the build inherits the proposal's asker (its author): ${filed.body}")
        assertEquals("alice", history.createHistoricVariableInstanceQuery().processInstanceId(id).variableName("askedBy").singleResult()?.value,
            "the proposal's start put its asker (no trailer `by`: the issue's author) on the flow")
        assertTrue("- $today accepted (#12); build filed as #100" in git.edits.single()[4])
        assertTrue("Decided: accepted; build filed as #100" in github.commentsOn(12).last())

        val feature = runtime.createProcessInstanceQuery().processDefinitionKey("feature").variableValueEquals("issue", 100L).singleResult()
        assertNotNull(feature, "the build started")
        assertEquals(3, runtime.getVariable(feature.id, "tier"), "at the proposal's tier")
        assertEquals("confirm", runtime.getVariable(feature.id, "featureMode"))
        assertTrue(github.commentsOn(100).any { "Feature started in confirm mode: authorized by @lundmatt1 via proposal #12" in it }, github.commentsOn(100).toString())
        assertEquals("alice", handled.list("acme/tickets").single { it.issue == 100L }.askedBy, "the build's row carries the asker")
        assertEquals("alice", runtime.getVariable(feature.id, "askedBy"), "and so does its flow")
        runtime.deleteProcessInstance(feature.id, "test done")
    }

    @Test
    fun `a conflict on the way to the merge goes back to the draft, and the redraft, behind but clean, merges as is`() {
        sandbox.reset("propose" to drafted, "review" to approved, "propose" to drafted, "review" to approved)
        drafts(2)
        git.merges.addLast(MergeResult.Conflict(listOf(path)))
        git.merges.addLast(MergeResult.Clean("old-head", "merge-head", "merge bundle".toByteArray()))
        val id = start(12)
        answer(id, "accept")

        assertEquals(listOf("propose", "review", "propose", "review"), sandbox.started.map { it.mode })
        assertEquals("rebase-1-1", brief(sandbox.started[2])!!.findings.single().id, "the conflict goes to the next draft")
        assertEquals("bundle of container-1", sandbox.started[2].inputs[BUNDLE_FILE]?.let { String(it) }, "the conflict's redraft starts from the draft")
        assertEquals(setOf("user-task-answer-decision"), waitingAt(id), "a redraft is decided again")
        assertEquals(1, git.edits.size)

        answer(id, "accept")
        assertTrue(history.finished(id))
        assertEquals(1, git.edits.size, "the same day's line is not logged twice")
        assertTrue(git.pushes.none { it.third == "merge bundle" }, "no catch-up merge pushed")
        assertTrue(pulls.commentsOn(7).none { "catch-up" in it }, pulls.commentsOn(7).toString())
        assertEquals(listOf(7L to "Proposal: Widgets"), pulls.merged)
        assertNull(questions.pending(id))
        assertNull(runtime.createProcessInstanceQuery().processInstanceId(id).singleResult())
    }

    @Test
    fun `jev reads an answer that is no verb, a build verb needs the higher floor, and the re-ask fills the call's outcome`() {
        access.add("lundmatt1", "test")
        sandbox.reset("propose" to drafted, "review" to approved, "design" to """{"outcome":"ask","question":"Which pane?"}""")
        drafts(1)
        val id = start(12)

        // Below the build floor (0.95): the rule's reading stands, so it is asked again as before.
        jev.choice = "accept and build" to 0.9
        answer(id, "Looks right, go ahead and build it")
        assertEquals(setOf("user-task-answer-decision"), waitingAt(id))
        assertTrue("was not understood: `Looks right, go ahead and build it`" in github.commentsOn(12).last(), github.commentsOn(12).last())
        val (useCase, context, state) = jev.asked.single()
        assertEquals("proposal-decision", useCase)
        assertEquals(12L, context.issue)
        assertEquals(mapOf("answer" to "Looks right, go ahead and build it", "proposal" to "Widgets, with a cost."), state)
        val first = jevCalls(id).single()
        assertEquals("below floor 0.95, rule: unclear", first.action)
        assertEquals(0.95, first.threshold)
        assertNull(first.outcome)

        // At the build floor: taken, the answer is the why, the build is filed; the first call learns how the re-ask read.
        jev.choice = "accept and build" to 0.97
        answer(id, "Yes, build it")
        assertTrue(history.finished(id))
        assertEquals(listOf("feature", "feature:confirm"), github.created.single().second.labels)
        assertTrue("- $today accepted (#12) — Yes, build it; build filed as #100" in git.edits.single()[4], git.edits.single()[4])
        val (was, now) = jevCalls(id)
        assertEquals("the answer to the re-ask read as build: @lundmatt1: Yes, build it", jevCalls.get(was.id)!!.outcome)
        assertEquals("took build", now.action)
        assertNull(now.outcome, "no verb written, so nothing learned yet")
        runtime.createProcessInstanceQuery().processDefinitionKey("feature").list().forEach { runtime.deleteProcessInstance(it.id, "test done") }
    }

    @Test
    fun `a confident jev pick never overrides a verb as written`() {
        sandbox.reset("propose" to drafted, "review" to approved)
        drafts(1)
        val id = start(12)
        jev.choice = "reject" to 0.99
        answer(id, "shelve: after the usage meter")
        assertTrue(history.finished(id))
        assertTrue("Status: shelved" in git.edits.single()[4], "the verb as written wins")
        val call = jevCalls(id).single()
        assertEquals("kept the verb as written: shelve", call.action)
        assertEquals("verb as written: shelve (jev picked reject)", call.outcome)
    }

    @Test
    fun `a confident jev pick of an answer with no verb is taken, the answer its why`() {
        sandbox.reset("propose" to drafted, "review" to approved)
        drafts(1)
        val id = start(12)
        jev.choice = "shelve" to 0.85
        answer(id, "Park this for now")
        assertTrue(history.finished(id))
        assertTrue("shelved (#12) — Park this for now" in git.edits.single()[4], git.edits.single()[4])
        assertEquals("took shelve", jevCalls(id).single().action)
    }

    @Test
    fun `with no jev answer the rule reads the answer, as before`() {
        sandbox.reset("propose" to drafted, "review" to approved)
        drafts(1)
        val id = start(12)
        answer(id, "accept: the cost is fine")
        assertTrue(history.finished(id))
        val call = jevCalls(id).single()
        assertEquals("no answer, rule: accept", call.action)
        assertEquals("verb as written: accept", call.outcome)
    }
}

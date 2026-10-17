package com.codefrak.engine.hitlp

import com.codefrak.engine.ProcessFakes
import com.codefrak.engine.TestTokens
import com.codefrak.engine.accounts.AiAccounts
import com.codefrak.engine.board.Needs
import com.codefrak.engine.chore.FlowStarter
import com.codefrak.engine.chore.ScriptedSandbox
import com.codefrak.engine.github.IssueSnapshot
import com.codefrak.engine.intake.CancelRequest
import com.codefrak.engine.intake.HitlpAnswers
import com.codefrak.engine.intake.IntakeController
import com.codefrak.engine.intake.IntakeProcessTest
import com.codefrak.engine.intake.IntakeQuestions
import com.codefrak.engine.intake.RefiningSandbox
import com.codefrak.engine.jev.FakeJevDecider
import com.codefrak.engine.proposal.DecisionVerb
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.runAllJobs
import com.codefrak.engine.smith.BRIEF_FILE
import com.codefrak.engine.smith.SandboxExitNotifier
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.dropTestAccount
import com.codefrak.engine.smith.ensureTestAccount
import com.codefrak.engine.github.BundleChanges
import com.codefrak.engine.intake.TicketRecorder
import com.codefrak.engine.chore.FakeGitPush
import com.codefrak.engine.chore.FakeGitHubPulls
import com.codefrak.engine.feature.DesignResult
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.variable.Variables
import com.codefrak.engine.proposal.PitchRulesTest
import org.junit.jupiter.api.AfterAll
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
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A §6 `approve` decision record with [outcome] (`approved`, `rejected`, `timed_out`) and [reason]. */
internal fun approveRecord(taskId: String, outcome: String, reason: String? = null, decidedBy: Map<String, Any?> = mapOf("type" to "human", "id" to "lundmatt1")) =
    buildMap<String, Any?> {
        put("requestId", taskId); put("idempotencyKey", "k"); put("primitive", "approve"); put("outcome", outcome)
        reason?.let { put("reason", it) }
        put("decidedBy", decidedBy); put("decidedAt", "2026-10-11T12:00:00Z"); put("channel", "desktop")
    }

/** A §6 `ask` decision record answering [answer], decided by [decidedBy]. */
internal fun askRecord(taskId: String, answer: Any?, decidedBy: Map<String, Any?> = mapOf("type" to "human", "id" to "lundmatt1"),
                       channel: String? = "desktop", outcome: String = "answered") = buildMap<String, Any?> {
    put("requestId", taskId); put("idempotencyKey", "k"); put("primitive", "ask"); put("outcome", outcome)
    if (outcome == "answered") put("answer", answer)
    put("decidedBy", decidedBy); put("decidedAt", "2026-10-11T12:00:00Z")
    channel?.let { put("channel", it) }
}

/**
 * The intake's ask waits filed as HITLP tasks (#2462) against [StubHitlpServer], the poller ticked by hand: the
 * question filed with its hint on the issue, a record from each `decidedBy.type` naming its decider and refining again
 * with the answer, a `timed_out` record filed again as `:r1`, and a cancelled flow's task cancelled on the server.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntakeProcessTest.Fakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class HitlpFlowWaitTest {
    companion object {
        val stub = StubHitlpServer()
        @JvmStatic @DynamicPropertySource fun hitlp(registry: DynamicPropertyRegistry) = stub.register(registry)
        @JvmStatic @AfterAll fun stop() = stub.stop()
    }

    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var history: HistoryService
    @Autowired lateinit var tasks: TaskService
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var exits: SandboxExitNotifier
    @Autowired lateinit var accounts: AiAccounts
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var access: Access
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var github: TicketRecorder
    @Autowired lateinit var sandbox: RefiningSandbox
    @Autowired lateinit var questions: IntakeQuestions
    @Autowired lateinit var intakes: IntakeController
    @Autowired lateinit var requests: HitlpRequests
    @Autowired lateinit var poller: HitlpPoller

    @BeforeEach
    fun reset() {
        runtime.createProcessInstanceQuery().rootProcessInstances().list().forEach { runtime.deleteProcessInstance(it.id, "test reset") }
        listOf("engine.handled_issue", "engine.deferred_issue", "engine.engine_access", "engine.engine_access_audit", "engine.target_repo",
            "engine.intake_question", "engine.hitlp_request").forEach { jdbc.sql("DELETE FROM $it").update() }
        repos.add("acme/tickets")
        access.add("lundmatt1", "test")
        accounts.ensureTestAccount()
        sandbox.started.clear(); sandbox.running = false
        stub.reset()
    }

    @AfterEach
    fun dropTestAccount() { accounts.dropTestAccount() }

    private fun runAllJobs() = management.runAllJobs(exits, rounds = 50)

    /** An intake waiting at its refiner's question: its id and issue. */
    private fun asking(): Pair<String, Long> {
        val intake = runtime.createMessageCorrelation("ask submitted")
            .setVariables(mapOf("submittedBy" to "lundmatt1", "repo" to "acme/tickets", "ask" to "hello", "fakeOutcome" to "ask")).correlateStartMessage().id
        runAllJobs()
        assertEquals(setOf("user-task-answer-question"), tasks.createTaskQuery().processInstanceId(intake).list().map { it.taskDefinitionKey }.toSet())
        return intake to (runtime.getVariable(intake, "issue") as Number).toLong()
    }

    private fun taskOf(id: String) = runtime.getVariable(id, Vars.HITLP_TASK_ID) as String

    /** One poller pass past every row's next poll, then the flow's jobs. */
    private fun tick() { poller.poll(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1)); runAllJobs() }

    private fun commentsOn(issue: Long) = github.comments.filter { it.first == "acme/tickets" && it.second == issue }.map { it.third }

    @Test
    fun `the question is filed as a human ask, its hint on the issue, and a person's answer refines again with it`() {
        val (intake, issue) = asking()
        val task = taskOf(intake)
        val filed = stub.calls("tools/call").single()
        assertEquals("human.ask", filed.params["name"])
        assertTrue(commentsOn(issue).any { "Decide it on ${StubHitlpServer.DECISION_BASE}$task" in it }, commentsOn(issue).toString())
        assertEquals("user-task-answer-question", requests.get(task)!!.userTaskKey)

        stub.decide(task, askRecord(task, "the blue one"))
        tick()
        assertTrue(commentsOn(issue).any { it.startsWith("@lundmatt1 (desktop) answered:\n\nthe blue one\n\n— engine intake `$intake`, refining again.") },
            commentsOn(issue).toString())
        val refines = sandbox.started.filter { it.mode == "refine" }
        assertEquals(2, refines.size, "refined again")
        val brief = String(refines.last().inputs.getValue(BRIEF_FILE))
        assertTrue("\"answer\":\"the blue one\",\"by\":\"lundmatt1\"" in brief, brief)
        assertNotNull(requests.get(task)!!.record, "the row is finished")
    }

    @Test
    fun `a rule, a model and a policy are each named as the decider`() {
        for ((by, name) in listOf(
            mapOf("type" to "rule", "id" to "always-blue") to "rule always-blue",
            mapOf("type" to "model", "id" to "jev", "confidence" to 0.9) to "model jev (0.9)",
            mapOf("type" to "policy", "id" to "p-1") to "policy p-1",
        )) {
            val (intake, issue) = asking()
            val task = taskOf(intake)
            stub.decide(task, askRecord(task, "blue", by, channel = null))
            tick()
            assertTrue(commentsOn(issue).any { it.startsWith("$name answered:\n\nblue\n\n") }, "$name: ${commentsOn(issue)}")
            assertEquals(name, questions.list(intake).first().answeredBy)
        }
        assertEquals("@lundmatt1 (desktop)", HitlpAnswers.decider(askRecord("t", "x")))
        assertEquals("@lundmatt1", HitlpAnswers.decider(askRecord("t", "x", channel = null)))
    }

    @Test
    fun `a timed out question is filed again as r1 with one line on the issue, and the new task answers it`() {
        val (intake, issue) = asking()
        val first = taskOf(intake)
        stub.decide(first, askRecord(first, null, outcome = "timed_out"), HitlpPrimitives.FAILED)
        tick()
        val again = taskOf(intake)
        assertNotEquals(first, again)
        assertTrue(requests.get(again)!!.idempotencyKey.endsWith(":r1"), requests.get(again)!!.idempotencyKey)
        assertEquals(1, commentsOn(issue).count {
            it == "The question timed out on the HITLP server after 7 days; filed again as ${StubHitlpServer.DECISION_BASE}$again." })
        assertNotNull(questions.pending(intake), "still waiting")

        stub.decide(again, askRecord(again, "blue"))
        tick()
        assertNull(questions.pending(intake))
        assertEquals(2, sandbox.started.count { it.mode == "refine" })
    }

    @Test
    fun `a cancelled flow cancels its open task on the server and marks the row cancelled`() {
        val (intake, _) = asking()
        val task = taskOf(intake)
        intakes.cancel(intake, CancelRequest(reason = "not needed"), TestTokens.asLogin("lundmatt1"))
        assertEquals(listOf(task), stub.calls("tasks/cancel").map { it.params["taskId"] })
        assertEquals("cancelled", requests.get(task)!!.record!!["outcome"])
        assertEquals(emptyList(), requests.openFor(intake))
    }

    @Test
    fun `an approve record and a record of another kind are not this answerer's`() {
        val (intake, _) = asking()
        val task = taskOf(intake)
        stub.decide(task, askRecord(task, "x") + ("primitive" to "approve"))
        tick()
        assertNotNull(questions.pending(intake), "an approve record answers no question")
    }
}

/**
 * The ticket flows' ask waits filed as HITLP tasks (#2462): a feature's design question answered from its record, and a
 * proposal's decision taken from a record's option with no Jev call; and the approve waits (#2467): a hitlp change's merge
 * approved (merged) or rejected (a `merge-<round>-1` finding with the reason), and a legacy feature acceptance on an
 * instance parked before #583.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ProcessFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class HitlpTicketFlowWaitTest {
    companion object {
        val stub = StubHitlpServer()
        @JvmStatic @DynamicPropertySource fun hitlp(registry: DynamicPropertyRegistry) = stub.register(registry)
        @JvmStatic @AfterAll fun stop() = stub.stop()
    }

    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var history: HistoryService
    @Autowired lateinit var tasks: TaskService
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var exits: SandboxExitNotifier
    @Autowired lateinit var accounts: AiAccounts
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var access: Access
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var github: TicketRecorder
    @Autowired lateinit var git: FakeGitPush
    @Autowired lateinit var sandbox: ScriptedSandbox
    @Autowired lateinit var starter: FlowStarter
    @Autowired lateinit var jev: FakeJevDecider
    @Autowired lateinit var poller: HitlpPoller
    @Autowired lateinit var pulls: FakeGitHubPulls
    @Autowired lateinit var repository: RepositoryService

    @BeforeEach
    fun reset() {
        listOf("engine.handled_issue", "engine.engine_access", "engine.engine_access_audit", "engine.target_repo", "engine.intake_question",
            "engine.hitlp_request").forEach { jdbc.sql("DELETE FROM $it").update() }
        github.comments.clear(); git.reset(); jev.reset(); pulls.opened.clear(); pulls.merged.clear()
        repos.add("acme/tickets")
        access.add("lundmatt1", "test")
        accounts.ensureTestAccount()
        stub.reset()
    }

    @AfterEach
    fun dropTestAccount() { accounts.dropTestAccount() }

    private fun runAllJobs() = management.runAllJobs(exits)
    private fun tick() { poller.poll(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1)); runAllJobs() }
    private fun waitingAt(id: String) = tasks.createTaskQuery().processInstanceId(id).list().map { it.taskDefinitionKey }.toSet()
    private fun commentsOn(issue: Long) = github.comments.filter { it.first == "acme/tickets" && it.second == issue }.map { it.third }
    private fun taskOf(id: String) = runtime.getVariable(id, Vars.HITLP_TASK_ID) as String

    @Test
    fun `a feature's design question is filed and its record's answer goes on to the next design run`() {
        val ask = """{"outcome":"ask","question":"Which widgets?"}"""
        sandbox.reset("design" to ask, "design" to ask)
        val id = runtime.createMessageCorrelation("feature filed")
            .setVariables(mapOf("repo" to "acme/tickets", "issue" to 31L, "featureMode" to "confirm")).correlateStartMessage().id.also { runAllJobs() }
        assertEquals(setOf("user-task-answer-question"), waitingAt(id))
        val task = taskOf(id)
        assertTrue(commentsOn(31).any { "Decide it on ${StubHitlpServer.DECISION_BASE}$task" in it }, commentsOn(31).toString())

        stub.decide(task, askRecord(task, "blue ones"))
        tick()
        assertTrue(commentsOn(31).any { it.startsWith("@lundmatt1 (desktop) answered:\n\nblue ones\n\n— engine feature `$id`, going on.") }, commentsOn(31).toString())
        assertEquals(2, sandbox.started.count { it.mode == "design" })
        assertTrue("\"answer\":\"blue ones\"" in String(sandbox.started.last().inputs.getValue(BRIEF_FILE)))
        runtime.deleteProcessInstance(id, "test done")
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `a choice question is filed with a plain string schema and a free answer reaches the brief and the comment as written`() {
        val ask = """{"outcome":"ask","question":"Which widgets?\n\n```ask\nkind: choice\noptions:\n  - id: blue\n    label: Blue ones\n  - id: red\n    label: Red ones\n```"}"""
        sandbox.reset("design" to ask, "design" to ask)
        val id = runtime.createMessageCorrelation("feature filed")
            .setVariables(mapOf("repo" to "acme/tickets", "issue" to 32L, "featureMode" to "confirm")).correlateStartMessage().id.also { runAllJobs() }
        val task = taskOf(id)
        val args = stub.calls("tools/call").last().params["arguments"] as Map<String, Any?>
        assertEquals(mapOf("type" to "string"), args["responseSchema"], "no enum: the user may answer in their own words")
        assertEquals(listOf("blue", "red"), (args["options"] as List<Map<String, Any?>>).map { it["value"] })

        stub.decide(task, askRecord(task, "green ones, neither of those"))
        tick()
        assertTrue(commentsOn(32).any { it.startsWith("@lundmatt1 (desktop) answered:\n\ngreen ones, neither of those\n\n") }, commentsOn(32).toString())
        assertTrue("\"answer\":\"green ones, neither of those\"" in String(sandbox.started.last().inputs.getValue(BRIEF_FILE)))
        runtime.deleteProcessInstance(id, "test done")
    }

    @Test
    fun `a proposal decision's accept option is taken as given, with no jev call`() {
        val drafted = """{"outcome":"drafted","path":"docs/proposals/widgets.md","pitch":"docs/proposals/widgets.html","summary":"Widgets, with a cost.","questions":[]}"""
        sandbox.reset("propose" to drafted, "review" to """{"outcome":"approved","summary":"fine","findings":[]}""")
        git.changes.addLast(BundleChanges(listOf("docs/proposals/widgets.md", "docs/proposals/widgets.html"), null, "# Widgets\n\nWe should.\n", PitchRulesTest.EXAMPLE))
        val id = starter.start("proposal", "acme/tickets", IssueSnapshot(33, "Widgets", "Should we have widgets?", "alice"), "lundmatt1")!!.also { runAllJobs() }
        assertEquals(setOf("user-task-answer-decision"), waitingAt(id))
        val task = taskOf(id)
        @Suppress("UNCHECKED_CAST")
        val options = (stub.calls("tools/call").last().params["arguments"] as Map<String, Any?>)["options"] as List<Map<String, Any?>>
        assertEquals(listOf("accept", "accept and build", "accept and build auto", "revise", "shelve", "reject"), options.map { it["value"] })

        stub.decide(task, askRecord(task, "accept"))
        tick()
        assertTrue(jev.asked.none { it.component1() == "proposal-decision" }, "a decision option asks no Jev")
        assertEquals(1L, history.createHistoricProcessInstanceQuery().processInstanceId(id).finished().count())
        assertEquals(DecisionVerb.ACCEPT, HitlpAnswers.decisionChoice("accept"))
        assertEquals(DecisionVerb.BUILD_AUTO, HitlpAnswers.decisionChoice("accept and build auto"))
        assertNull(HitlpAnswers.decisionChoice("accept and build"), "parsed by task-read-decision")
    }

    private val approved = """{"outcome":"approved","summary":"fine","findings":[]}"""
    private val passed = """{"outcome":"passed","summary":"fine","findings":[]}"""
    private fun done(vararg addressed: String) =
        """{"outcome":"done","title":"Tidy the readme","summary":"Tidied it.","addressed":[${addressed.joinToString { """{"finding":"$it","how":"fixed"}""" }}]}"""

    /** A chore whose push changes the hitlp module, reviewed and approved, waiting at its merge acceptance. */
    private fun waitingOnMerge(issue: Long, vararg runs: Pair<String, String>): String {
        repos.update("acme/tickets", null, emptyList(), true)
        git.paths = listOf("engine/modules/hitlp/src/main/kotlin/com/codefrak/engine/hitlp/HitlpTasks.kt")
        sandbox.reset("work" to done(), "security" to passed, "review" to approved, *runs)
        val id = runtime.createMessageCorrelation("chore filed").setVariables(mapOf("repo" to "acme/tickets", "issue" to issue))
            .correlateStartMessage().id.also { runAllJobs() }
        assertEquals(setOf("user-task-answer-merge"), waitingAt(id))
        return id
    }

    @Test
    fun `a hitlp change's merge is filed as a human approve and an approved record merges it`() {
        val id = waitingOnMerge(51)
        val task = taskOf(id)
        val call = stub.calls("tools/call").last()
        assertEquals("human.approve", call.params["name"])
        @Suppress("UNCHECKED_CAST")
        val args = call.params["arguments"] as Map<String, Any?>
        assertEquals("merge PR #7 of acme/tickets", args["action"])
        assertEquals(mapOf("repo" to "acme/tickets", "pr" to 7, "head" to runtime.getVariable(id, Vars.HEAD_SHA)), args["payload"])
        assertEquals(true, args["requiresHuman"])
        assertTrue(commentsOn(51).any { "merges only once you accept it" in it && "Decide it on ${StubHitlpServer.DECISION_BASE}$task" in it }, commentsOn(51).toString())
        assertEquals(emptyList(), pulls.merged)

        stub.decide(task, approveRecord(task, "approved"))
        tick()
        assertTrue(commentsOn(51).any { it.startsWith("@lundmatt1 (desktop) answered:\n\napproved\n\n") }, commentsOn(51).toString())
        assertEquals(1L, history.createHistoricProcessInstanceQuery().processInstanceId(id).finished().count())
        assertEquals(listOf(7L to "Tidy the readme"), pulls.merged)
    }

    @Test
    fun `a rejected merge record goes back to the work with a merge finding carrying its reason`() {
        val id = waitingOnMerge(52, "work" to done("merge-1-1"), "security" to passed, "review" to approved)
        val task = taskOf(id)
        stub.decide(task, approveRecord(task, "rejected", "keep the old table"))
        tick()
        assertEquals(emptyList(), pulls.merged)
        assertEquals(listOf("work", "security", "review", "work"), sandbox.started.map { it.mode }.take(4))
        val brief = String(sandbox.started[3].inputs.getValue(BRIEF_FILE))
        assertTrue("merge-1-1" in brief && "keep the old table" in brief, brief)
        assertEquals(setOf("user-task-answer-merge"), waitingAt(id), "the new head is asked again")
        assertNotEquals(task, taskOf(id), "on a new task")
        runtime.deleteProcessInstance(id, "test done")
    }

    @Test
    fun `a legacy acceptance on an instance parked before #583 is filed as a human approve and an approved record closes the ticket`() {
        val plan = """{"outcome":"planned","plan":{"summary":"A strip.","files":["a"],"stages":[{"title":"t","detail":"d"}],"tests":["x"],"acceptance":["The strip is on every screen"]}}"""
        val service = { sid: String, bean: String -> """<bpmn:serviceTask id="$sid" camunda:delegateExpression="${'$'}{$bean}" />""" }
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" targetNamespace="t">
  <bpmn:process id="feature" isExecutable="true" camunda:historyTimeToLive="1">
    <bpmn:startEvent id="s" />
    ${service("task-post-acceptance", "postAcceptance")}
    <bpmn:userTask id="user-task-answer-acceptance" />
    ${service("task-read-acceptance", "readAcceptance")}
    <bpmn:exclusiveGateway id="gateway-user-accepted" default="flow-user-rejected" />
    ${service("task-close-ticket", "closeTicket")}
    <bpmn:endEvent id="end-not-accepted" />
    <bpmn:endEvent id="end-feature-done" />
    <bpmn:sequenceFlow id="f0" sourceRef="s" targetRef="task-post-acceptance" />
    <bpmn:sequenceFlow id="f1" sourceRef="task-post-acceptance" targetRef="user-task-answer-acceptance" />
    <bpmn:sequenceFlow id="f2" sourceRef="user-task-answer-acceptance" targetRef="task-read-acceptance" />
    <bpmn:sequenceFlow id="f3" sourceRef="task-read-acceptance" targetRef="gateway-user-accepted" />
    <bpmn:sequenceFlow id="flow-user-accepted" sourceRef="gateway-user-accepted" targetRef="task-close-ticket">
      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${'$'}{outcome == 'accepted'}</bpmn:conditionExpression>
    </bpmn:sequenceFlow>
    <bpmn:sequenceFlow id="flow-user-rejected" sourceRef="gateway-user-accepted" targetRef="end-not-accepted" />
    <bpmn:sequenceFlow id="f6" sourceRef="task-close-ticket" targetRef="end-feature-done" />
  </bpmn:process>
</bpmn:definitions>"""
        val old = repository.createProcessDefinitionQuery()
            .deploymentId(repository.createDeployment().addString("feature-before-583.bpmn", xml).deploy().id).singleResult()
        repository.createDeployment().addClasspathResource("feature.bpmn").deploy()
        val id = runtime.startProcessInstanceById(old.id, mapOf("repo" to "acme/tickets", "issue" to 53L, "featureMode" to "confirm",
            "pr" to 7L, "round" to 1, Vars.HEAD_SHA to "abc123",
            "plan" to Variables.byteArrayValue(DesignResult.planBytes(DesignResult.parse(plan.toByteArray()).plan!!)))).id
        runAllJobs()
        assertEquals(setOf("user-task-answer-acceptance"), waitingAt(id))
        val task = taskOf(id)
        @Suppress("UNCHECKED_CAST")
        val args = stub.calls("tools/call").last().params["arguments"] as Map<String, Any?>
        assertEquals("accept the merged feature #53 of acme/tickets", args["action"])
        assertEquals(mapOf("repo" to "acme/tickets", "pr" to 7, "head" to "abc123"), args["payload"])
        assertTrue(commentsOn(53).any { "Decide it on ${StubHitlpServer.DECISION_BASE}$task" in it }, commentsOn(53).toString())

        stub.decide(task, approveRecord(task, "approved"))
        tick()
        assertTrue(jev.asked.isEmpty(), "a record asks no Jev")
        assertEquals(1L, history.createHistoricProcessInstanceQuery().processInstanceId(id).finished().count())
        assertEquals("end-feature-done", history.createHistoricActivityInstanceQuery().processInstanceId(id).activityType("noneEndEvent").singleResult().activityId)
        assertTrue(github.closed.any { it == "acme/tickets" to 53L })
    }
}

/**
 * With no HITLP server configured (the settings row cleared, #2500: `configured` is live), an intake's question parks on a
 * failedJob incident naming `PUT /settings/hitlp`, a row of `GET /needs`. The row is put back after, for the context's other tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntakeProcessTest.Fakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class HitlpUnsetFlowWaitTest {
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var exits: SandboxExitNotifier
    @Autowired lateinit var accounts: AiAccounts
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var access: Access
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var questions: IntakeQuestions
    @Autowired lateinit var needs: Needs
    @Autowired lateinit var settings: HitlpServerSettings
    @Autowired lateinit var seeder: HitlpStubSeeder

    @Test
    fun `the question's job fails into an incident naming the unset URL`() {
        listOf("engine.target_repo", "engine.engine_access", "engine.intake_question").forEach { jdbc.sql("DELETE FROM $it").update() }
        repos.add("acme/tickets"); access.add("lundmatt1", "test"); accounts.ensureTestAccount()
        settings.set(mcpUrl = HitlpServerSettings.Field.Set(null), requesterToken = HitlpServerSettings.Field.Set(null))
        try {
            val intake = runtime.createMessageCorrelation("ask submitted")
                .setVariables(mapOf("submittedBy" to "lundmatt1", "repo" to "acme/tickets", "ask" to "hello", "fakeOutcome" to "ask")).correlateStartMessage().id
            management.runAllJobs(exits, rounds = 50)
            val incident = runtime.createIncidentQuery().processInstanceId(intake).singleResult()
            assertNotNull(incident, "parked on an incident")
            assertEquals("failedJob", incident.incidentType)
            assertEquals("task-post-question", incident.activityId)
            assertTrue("PUT /settings/hitlp" in incident.incidentMessage.orEmpty(), incident.incidentMessage)
            assertNull(questions.pending(intake), "the row rolled back with the job")
            assertTrue(needs.list().any { it.kind == "incident" && it.actionId == incident.id }, needs.list().toString())
            runtime.deleteProcessInstance(intake, "test done")
        } finally { accounts.dropTestAccount(); seeder.seed() }
    }
}

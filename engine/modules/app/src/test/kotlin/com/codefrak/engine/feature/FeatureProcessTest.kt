package com.codefrak.engine.feature

import com.codefrak.engine.TestTokens
import com.codefrak.engine.ProcessFakes
import com.codefrak.engine.finished
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.brief
import com.codefrak.engine.camunda.IncidentController
import com.codefrak.engine.camunda.RetryRequest
import com.codefrak.engine.ci.CheckRun
import com.codefrak.engine.ci.CI_CHECK_MESSAGE
import com.codefrak.engine.ci.CiCheckWaits
import com.codefrak.engine.ci.FakeCheckRuns
import com.codefrak.engine.accounts.AiAccounts
import com.codefrak.engine.chore.FakeGitHubPulls
import com.codefrak.engine.chore.FakeGitPush
import com.codefrak.engine.ci.ConflictRounds
import com.codefrak.engine.github.MergeResult
import com.codefrak.engine.chore.ScriptedSandbox
import com.codefrak.engine.intake.CancelRequest
import com.codefrak.engine.github.NOT_PLANNED
import com.codefrak.engine.intake.TicketRecorder
import com.codefrak.engine.intake.IntakeController
import com.codefrak.engine.intake.IntakeQuestions
import com.codefrak.engine.hitlp.SharedHitlpStub
import com.codefrak.engine.hitlp.decideWait
import com.codefrak.engine.jev.FakeJevDecider
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.Ticket
import com.codefrak.engine.smith.ensureTestAccount
import com.codefrak.engine.smith.dropTestAccount
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.variable.Variables
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(ProcessFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class FeatureProcessTest {
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var accounts: AiAccounts
    @Autowired lateinit var history: HistoryService
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var repository: RepositoryService
    @Autowired lateinit var exits: com.codefrak.engine.smith.SandboxExitNotifier
    @Autowired lateinit var github: TicketRecorder
    @Autowired lateinit var pulls: FakeGitHubPulls
    @Autowired lateinit var git: FakeGitPush
    @Autowired lateinit var sandbox: ScriptedSandbox
    @Autowired lateinit var runs: FakeCheckRuns
    @Autowired lateinit var waits: CiCheckWaits
    @Autowired lateinit var intakes: IntakeController
    @Autowired lateinit var questions: IntakeQuestions
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var access: Access
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var jev: FakeJevDecider
    @Autowired lateinit var hitlpRequests: com.codefrak.engine.hitlp.HitlpRequests
    @Autowired lateinit var hitlpPoller: com.codefrak.engine.hitlp.HitlpPoller
    @Autowired lateinit var jevCalls: JevCalls
    @Autowired lateinit var incidentRetries: IncidentController

    @AfterEach
    fun dropTestAccount() { accounts.dropTestAccount() }

    @BeforeEach
    fun clear() {
        pulls.opened.clear(); pulls.merged.clear(); pulls.closed.clear(); pulls.conflictNext = 0; git.reset(); runs.reset(); jev.reset(); github.comments.clear(); github.closed.clear()
        listOf("engine.engine_access", "engine.engine_access_audit", "engine.target_repo", "engine.intake_question").forEach { jdbc.sql("DELETE FROM $it").update() }
        repos.add("acme/tickets")
        repos.update("acme/tickets", "engine", listOf("engine/"), true)
        access.add("lundmatt1", "test")
        accounts.ensureTestAccount()
    }

    /** Decides the HITLP wait [id] is parked on, as [by], through the shared stub and the poller. */
    private fun decide(id: String, answer: String, by: String = "lundmatt1") =
        decideWait(SharedHitlpStub.STUB, hitlpRequests, hitlpPoller, id, answer, by)

    private fun runAllJobs() {
        repeat(100) {
            // Exited sandboxes reach their waiting runs through the backstop scan: no event stream in tests.
            exits.scanExits()
            // The engine check's 3-hour timeout and its re-trigger cycle fire only when a test says so.
            val timeouts = listOf("event-ci-check-timeout", "event-ci-check-retrigger")
                .flatMap { management.createJobQuery().activityId(it).list() }.map { it.id }.toSet() +
                // So do the post-deployment check's: a flow that reaches call-post-deploy-check waits for a deploy.
                management.createJobQuery().timers().processDefinitionKey("post-deploy-check").list().map { it.id }
            val jobs = management.createJobQuery().withRetriesLeft().list().filterNot { it.id in timeouts }
            if (jobs.isEmpty()) return
            jobs.forEach { runCatching { management.executeJob(it.id) } }
        }
        error("no quiescence in 100 job rounds")
    }

    private fun start(issue: Long, mode: String) = runtime.createMessageCorrelation("feature filed")
        .setVariables(mapOf("repo" to "acme/tickets", "issue" to issue, "featureMode" to mode)).correlateStartMessage().also { runAllJobs() }

    private fun ended(id: String) = history.createHistoricActivityInstanceQuery().processInstanceId(id).activityType("noneEndEvent")
        .list().single().activityId

    private val plan = """"plan":{"summary":"A third fixed pane.","files":["src/claude-session.js"],"stages":[{"title":"the pane","detail":"adapter"}],"tests":["npm test"],"acceptance":["The strip is on every screen","Replies are cut at fifty words"]}"""
    private val planned = """{"outcome":"planned",$plan,"questions":[]}"""
    private val sure = """{"outcome":"planned",$plan,"questions":[{"question":"Left or right?","assumption":"right","confidence":0.9,"impact":"low"}]}"""
    private val mockup = "<svg viewBox=\\\"0 0 40 10\\\"><rect width=\\\"40\\\" height=\\\"10\\\"/></svg>"
    private val unsure = """{"outcome":"planned","plan":{"summary":"A third fixed pane, drawn:\n\n$mockup","acceptance":["The strip is on every screen"]},"questions":[{"question":"Which model?","assumption":"fable low","confidence":0.4,"impact":"high"}]}"""
    private val done = """{"outcome":"done","title":"First mate strip","summary":"Built it."}"""
    private fun done(addressed: String) =
        """{"outcome":"done","title":"First mate strip","summary":"Built it.","addressed":[{"finding":"$addressed","how":"fixed"}]}"""
    private val passed = """{"outcome":"passed","summary":"fine","findings":[]}"""
    private val approved = """{"outcome":"approved","summary":"fine","findings":[]}"""
    private val accepted = """{"outcome":"accepted","summary":"Both hold.","criteria":[{"criterion":"The strip is on every screen","met":true,"how":"ran the app"},{"criterion":"Replies are cut at fifty words","met":true,"how":"npm test"}]}"""

    @Test
    fun `auto mode plans, builds through the chore's reviews, merges, checks acceptance and closes`() {
        sandbox.reset("design" to planned, "work" to done, "security" to passed, "review" to approved, "accept" to accepted)
        val feature = start(31, "auto")

        assertTrue(history.finished(feature.id))
        assertEquals("end-feature-done", ended(feature.id))
        assertEquals(listOf("design", "work", "security", "review", "accept"), sandbox.started.map { it.mode })
        // The design run is told only the repo's child-work mode (#2501): no plan, no findings, nothing else yet.
        val design = brief(sandbox.started[0])!!
        assertEquals("serial", design.childMode, "the design run is told the repo's child-work mode, serial by default")
        assertNull(design.plan, "the design run has no plan to be told yet")
        assertEquals(0, design.round)
        sandbox.started.drop(1).forEach {
            assertEquals("A third fixed pane.", brief(it)!!.plan!!["summary"], "${it.mode} is told the plan")
        }
        assertTrue("engine: feature " in pulls.opened.single()[4], "the PR names the feature flow")
        assertEquals(7L, brief(sandbox.started[2])!!.pr?.number, "security reviews the round after its push")
        assertEquals(listOf(7L to "First mate strip"), pulls.merged)
        assertEquals("acme/tickets" to 31L, github.closed.last())
        val closing = github.commentsOn(31).last()
        assertTrue("Merged #7" in closing && "Accepted: Both hold." in closing && "- [x] The strip is on every screen: ran the app" in closing, closing)
        assertNull(questions.pending(feature.id), "auto mode asked nothing")
        assertNull(history.createHistoricVariableInstanceQuery().processInstanceId(feature.id).variableName(Vars.DESIGN_DECK).singleResult(), "auto mode keeps no design deck")
    }

    @Autowired lateinit var mcpServers: com.codefrak.engine.repos.RepoMcpServers

    /** Flows left waiting at call-post-deploy-check, deleted after each test: their waits would outlive it. */
    private val checking = mutableListOf<String>()

    @AfterEach
    fun deleteChecking() { checking.forEach { runCatching { runtime.deleteProcessInstance(it, "test done") } }; checking.clear() }

    private fun addOpsServer() { mcpServers.put("acme/tickets", "ops", mapOf("kind" to "http", "audience" to "smith", "role" to "ops", "url" to "https://ops.example/mcp"), "test") }

    /** Runs [block] with an ops-role MCP server on acme/tickets, removed after: the repo row is shared by every flow test. */
    private fun <T> withOpsServer(block: () -> T): T {
        addOpsServer()
        try { return block() } finally { mcpServers.delete("acme/tickets", "ops") }
    }

    /** The PR body's post-deployment check (bots/smith.md, "The post-deployment check"), as a JSON string's escapes. */
    private val postDeploySection = """\n\n## POST-DEPLOYMENT-CHECK\n\nThe next answer lands in the new table.\nRe-check: 4h"""

    @Test
    fun `a feature whose pr has a post-deployment check is merged, accepted, and waits at the check with the ticket open`() = withOpsServer {
        sandbox.reset("design" to planned, "work" to """{"outcome":"done","title":"First mate strip","summary":"Built it.$postDeploySection"}""",
            "security" to passed, "review" to approved, "accept" to accepted)
        val feature = start(138, "auto").also { checking += it.id }

        assertEquals(listOf("design", "work", "security", "review", "accept"), sandbox.started.map { it.mode })
        assertEquals(listOf(7L to "First mate strip"), pulls.merged)
        assertEquals(listOf("call-post-deploy-check"), runtime.getActiveActivityIds(feature.id).filter { it.startsWith("call-") })
        assertTrue(("acme/tickets" to 138L) !in github.closed, "the post-deployment check closes the ticket")
    }

    @Test
    fun `a feature's done with no commits reruns the work once with a rules finding, and the flow goes on`() {
        sandbox.reset("design" to planned, "work" to done, "work" to done, "security" to passed, "review" to approved, "accept" to accepted)
        sandbox.unbundledWork = 1
        val feature = start(78, "auto")

        assertTrue(history.finished(feature.id))
        assertEquals(listOf("design", "work", "work", "security", "review", "accept"), sandbox.started.map { it.mode })
        assertEquals(listOf("rules-0-1"), brief(sandbox.started[2])!!.findings.map { it.id })
        assertTrue(github.commentsOn(78).any { "rejected the work result" in it })
    }

    private val uncheckedAccept = """{"outcome":"accepted","summary":"Both hold.","criteria":[{"criterion":"The strip is on every screen","met":true,"how":"ran the app"},{"criterion":"Replies are cut at fifty words","met":null,"how":"not run"}]}"""

    private fun incidentsUnder(root: String) = runtime.createIncidentQuery().list()
        .filter { history.createHistoricProcessInstanceQuery().processInstanceId(it.processInstanceId).singleResult().rootProcessInstanceId == root }

    @Test
    fun `a first accept result the rules refuse reruns the accept smith with a rules finding, and a valid rerun closes the ticket`() {
        sandbox.reset("design" to planned, "work" to done, "security" to passed, "review" to approved, "accept" to uncheckedAccept, "accept" to accepted)
        val feature = start(79, "auto")

        assertTrue(history.finished(feature.id))
        assertEquals("end-feature-done", ended(feature.id))
        assertEquals(emptyList(), incidentsUnder(feature.id))
        assertEquals(listOf("design", "work", "security", "review", "accept", "accept"), sandbox.started.map { it.mode })
        val rules = brief(sandbox.started[5])!!.findings.single { it.id.startsWith("rules-") && it.id.endsWith("-1") }
        assertEquals("rules", rules.review)
        assertTrue("a criterion was not checked; the outcome is 'unverified'" in rules.ask, rules.ask)
        assertTrue(github.commentsOn(79).any { "The engine rejected the accept result" in it && "A second rejection parks on an incident." in it })
        assertEquals("acme/tickets" to 79L, github.closed.last())
    }

    @Test
    fun `two broken accept results in a row park on the incident, and its retry runs a new smith`() {
        sandbox.reset("design" to planned, "work" to done, "security" to passed, "review" to approved,
            "accept" to uncheckedAccept, "accept" to uncheckedAccept, "accept" to accepted)
        val feature = start(80, "auto")

        assertEquals(2, sandbox.started.count { it.mode == "accept" })
        val incident = incidentsUnder(feature.id).first { it.processInstanceId == feature.id }
        assertTrue(incidentsUnder(feature.id).all { it.incidentType == "failedJob" })
        assertTrue(incidentsUnder(feature.id).any { it.incidentMessage?.startsWith("the smith's result breaks the rules:") == true })

        incidentRetries.retry(incident.id, RetryRequest(), TestTokens.asLogin("lundmatt1"))
        runAllJobs()

        assertEquals(3, sandbox.started.count { it.mode == "accept" })
        assertTrue(history.finished(feature.id))
    }

    @Test
    fun `a feature cancelled at its design run has that smith stopped, and one cancelled after its push has its PR closed unmerged`() {
        sandbox.reset("design" to planned); sandbox.holdMode = "design"
        val designing = start(71, "auto")
        val first = intakes.cancel(designing.id, CancelRequest(reason = "not this quarter"), TestTokens.asLogin("lundmatt1"))
        assertTrue(first.ended)
        assertEquals(listOf("container-1"), sandbox.stopped)
        assertEquals(0L, runtime.createProcessInstanceQuery().variableValueEquals("issue", 71L).count())
        assertEquals(NOT_PLANNED, github.closedAs["acme/tickets" to 71L])
        assertTrue(github.commentsOn(71).last().let { "@lundmatt1 cancelled this feature" in it && "not this quarter" in it }, github.commentsOn(71).toString())
        assertEquals(emptyList(), pulls.closed)

        sandbox.reset("design" to planned, "work" to done, "security" to passed); sandbox.holdMode = "security"
        val pushed = start(72, "auto")
        assertEquals(1, pulls.opened.size)
        val second = intakes.cancel(pushed.id, CancelRequest(reason = "superseded"), TestTokens.asLogin("lundmatt1"))
        assertTrue(second.ended)
        assertEquals(listOf("container-3"), sandbox.stopped)
        assertEquals(listOf(7L), pulls.closed)
        assertEquals(emptyList(), pulls.merged)
        val said = github.commentsOn(72).last()
        assertTrue("PR #7 is closed unmerged." in said && "Kept: branch `smith/issue-72` and every commit on it." in said, said)
        assertEquals(NOT_PLANNED, github.closedAs["acme/tickets" to 72L])
    }

    @Test
    fun `a feature that builds a proposal tells its design and work runs the doc's path, and one without the line tells none`() {
        val four = arrayOf("design" to planned, "work" to done, "security" to passed, "review" to approved, "accept" to accepted)
        sandbox.reset(*four, *four)
        fun startWith(issue: Long, body: String) = runtime.createMessageCorrelation("feature filed")
            .setVariables(mapOf("repo" to "acme/tickets", "issue" to issue, "featureMode" to "auto",
                "approvedTicket" to Variables.byteArrayValue(Brief.ticketBytes(Ticket(issue, "Build the doc", body, "lundmatt1")))))
            .correlateStartMessage().also { runAllJobs() }

        assertTrue(history.finished(startWith(35, "Builds: docs/proposals/x.md\n\nThe build of proposal #20.\n\n## Goal\n\nBuild it.\n").id))
        val built = sandbox.started.toList()
        listOf("design", "work").forEach { mode ->
            assertEquals("docs/proposals/x.md", brief(built.first { it.mode == mode })!!.builds, "the $mode run is told the doc")
        }

        sandbox.started.clear()
        assertTrue(history.finished(startWith(36, "## Goal\n\nBuilds on #200, but builds no proposal.\n").id))
        listOf("design", "work").forEach { mode -> assertNull(brief(sandbox.started.first { it.mode == mode })!!.builds, "no doc for $mode") }
    }

    @Test
    fun `auto mode lets every assumption stand, however unsure`() {
        sandbox.reset("design" to unsure, "work" to done, "security" to passed, "review" to approved, "accept" to accepted)
        val feature = start(32, "auto")
        assertTrue(history.finished(feature.id))
        assertEquals("end-feature-done", ended(feature.id))
        assertEquals(1, sandbox.started.count { it.mode == "design" })
        assertEquals(emptyList(), questions.list(feature.id))
    }

    /** The user tasks the flow reached, in order. */
    private fun userTasks(id: String) = history.createHistoricActivityInstanceQuery().processInstanceId(id).activityType("userTask")
        .orderByHistoricActivityInstanceStartTime().asc().list().map { it.activityId }

    @Test
    fun `confirm mode stops for an important plan question, redesigns from the answer, and after the merge an accept smith checks it as in auto mode`() {
        sandbox.reset("design" to unsure, "design" to planned, "work" to done, "security" to passed, "review" to approved, "accept" to accepted)
        val feature = start(33, "confirm")

        // Waiting on the user, with the question posted: confidence 0.4 is below the line.
        assertTrue(!history.finished(feature.id))
        assertEquals(listOf("user-task-answer-question"), runtime.getActiveActivityIds(feature.id))
        val pending = assertNotNull(questions.pending(feature.id))
        assertTrue("### 1. Which model?" in pending.question && "_Confidence 0.4, impact high._" in pending.question && "assume: fable low" in pending.question, pending.question)
        assertTrue("```ask\nkind: text\nquestion: Which model?\n```" in pending.question, pending.question)
        assertTrue(github.commentsOn(33).last().let { "The design Smith has a question (1)" in it && "Decide it on " in it })
        // The plan is kept for the design deck, and the comment points at it (#100114).
        assertNotNull(runtime.getVariable(feature.id, Vars.DESIGN_DECK))
        assertTrue("Open presentation" in github.commentsOn(33).last())
        val summary = "The plan:\n\nA third fixed pane, drawn:\n\n<svg viewBox=\"0 0 40 10\"><rect width=\"40\" height=\"10\"/></svg>"
        assertTrue(summary in pending.question, pending.question)
        assertTrue(summary in github.commentsOn(33).last(), github.commentsOn(33).last())
        assertEquals(0L, runtime.createIncidentQuery().processInstanceId(feature.id).count())

        decide(feature.id, "fable low")
        runAllJobs()
        val redesign = sandbox.started.filter { it.mode == "design" }.last()
        val answer = brief(redesign)!!.answers.single()
        assertTrue("### 1. Which model?" in answer.question && answer.answer == "fable low" && answer.by == "lundmatt1", "the second design run is told the answer: $answer")

        // Merged, then the accept Smith's check and the close: nothing after the merge waits on the user.
        assertTrue(history.finished(feature.id))
        assertEquals("end-feature-done", ended(feature.id))
        assertEquals(listOf("design", "design", "work", "security", "review", "accept"), sandbox.started.map { it.mode })
        assertEquals(listOf(7L to "First mate strip"), pulls.merged)
        assertEquals(listOf("user-task-answer-question"), userTasks(feature.id), "the design question is the only user task")
        assertEquals(1, questions.list(feature.id).size)
        assertEquals("acme/tickets" to 33L, github.closed.last())
        val closing = github.commentsOn(33).last()
        assertTrue("Merged #7" in closing && "Accepted: Both hold." in closing && "- [x] The strip is on every screen: ran the app" in closing, closing)
        assertEquals(emptyList(), questions.open())
        assertTrue(jev.asked.isEmpty())
    }

    @Test
    fun `confirm mode goes straight to the work when every question is confident enough, and closes on the accept smith's check`() {
        sandbox.reset("design" to sure, "work" to done, "security" to passed, "review" to approved, "accept" to accepted)
        val feature = start(34, "confirm")
        assertEquals(listOf("design", "work", "security", "review", "accept"), sandbox.started.map { it.mode })
        assertTrue(history.finished(feature.id))
        assertEquals("end-feature-done", ended(feature.id))
        assertEquals(emptyList(), userTasks(feature.id))
        assertNull(questions.pending(feature.id))
    }

    @Test
    fun `an unmet criterion in confirm mode leaves the ticket open, as in auto mode, with no user task after the merge`() {
        sandbox.reset("design" to planned, "work" to done, "security" to passed, "review" to approved,
            "accept" to """{"outcome":"unmet","summary":"One misses.","criteria":[{"criterion":"The strip is on every screen","met":false,"how":"not on the issue detail"}]}""")
        val feature = start(35, "confirm")
        assertTrue(history.finished(feature.id))
        assertEquals("end-not-accepted", ended(feature.id))
        assertEquals(emptyList(), userTasks(feature.id))
        assertTrue(github.closed.none { it.second == 35L }, "left open for a human")
        val report = github.commentsOn(35).last()
        assertTrue("found a criterion unmet" in report && "- [ ] The strip is on every screen: not on the issue detail" in report, report)
    }

    // ---- an instance started before #583, parked at the user's acceptance: still answerable ----

    /**
     * The tail of the feature process as deployed before #583, when confirm mode's acceptance was the user's:
     * deployed as a version of `feature`, one instance started on it at `task-post-acceptance`, then the current
     * feature.bpmn deployed again so every other start runs the current model. Returns the instance, waiting at
     * `user-task-answer-acceptance` with the checklist posted.
     */
    private fun waitingOnAcceptance(issue: Long): String {
        val service = { id: String, bean: String -> """<bpmn:serviceTask id="$id" camunda:delegateExpression="${'$'}{$bean}" />""" }
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" targetNamespace="t">
  <bpmn:process id="feature" isExecutable="true" camunda:historyTimeToLive="1">
    <bpmn:startEvent id="s" />
    ${service("task-post-acceptance", "postAcceptance")}
    <bpmn:userTask id="user-task-answer-acceptance" />
    ${service("task-read-acceptance", "readAcceptance")}
    <bpmn:exclusiveGateway id="gateway-user-accepted" default="flow-user-rejected" />
    ${service("task-post-acceptance-reask", "postAcceptanceReask")}
    ${service("task-report-unmet", "reportUnmet")}
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
    <bpmn:sequenceFlow id="flow-user-reask" sourceRef="gateway-user-accepted" targetRef="task-post-acceptance-reask">
      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${'$'}{outcome == 'reask'}</bpmn:conditionExpression>
    </bpmn:sequenceFlow>
    <bpmn:sequenceFlow id="flow-user-rejected" sourceRef="gateway-user-accepted" targetRef="task-report-unmet" />
    <bpmn:sequenceFlow id="f4" sourceRef="task-post-acceptance-reask" targetRef="user-task-answer-acceptance" />
    <bpmn:sequenceFlow id="f5" sourceRef="task-report-unmet" targetRef="end-not-accepted" />
    <bpmn:sequenceFlow id="f6" sourceRef="task-close-ticket" targetRef="end-feature-done" />
  </bpmn:process>
</bpmn:definitions>"""
        val oldDeployment = repository.createDeployment().addString("feature-before-583.bpmn", xml).deploy()
        val old = repository.createProcessDefinitionQuery().deploymentId(oldDeployment.id).singleResult()
        repository.createDeployment().addClasspathResource("feature.bpmn").deploy()
        val feature = runtime.startProcessInstanceById(old.id, mapOf("repo" to "acme/tickets", "issue" to issue, "featureMode" to "confirm",
            "pr" to 7L, "round" to 1, "plan" to Variables.byteArrayValue(DesignResult.planBytes(DesignResult.parse(planned.toByteArray()).plan!!))))
        assertEquals(listOf("user-task-answer-acceptance"), runtime.getActiveActivityIds(feature.id))
        val checklist = assertNotNull(questions.pending(feature.id))
        assertTrue("Merged #7" in checklist.question && "- [ ] The strip is on every screen" in checklist.question, checklist.question)
        assertTrue(repository.createProcessDefinitionQuery().processDefinitionKey("feature").latestVersion().singleResult().id != old.id,
            "every other start runs the current model")
        return feature.id
    }

    @Test
    fun `on an instance parked before #583, an approve record closes the ticket with no jev call`() {
        val feature = waitingOnAcceptance(36)
        decide(feature, "accepted")
        runAllJobs()
        assertTrue(history.finished(feature))
        assertEquals("end-feature-done", ended(feature))
        assertEquals("acme/tickets" to 36L, github.closed.last())
        assertTrue(jev.asked.isEmpty(), "a decision record asks no jev")
    }

    @Test
    fun `on an instance parked before #583, a user who does not accept leaves the ticket open with the answer on it`() {
        val feature = waitingOnAcceptance(35)
        decide(feature, "No: the strip is missing from the issue detail.")
        runAllJobs()
        assertTrue(history.finished(feature))
        assertEquals("end-not-accepted", ended(feature))
        assertTrue(github.closed.none { it.second == 35L }, "left open for a human")
        val report = github.commentsOn(35).last()
        assertTrue("the user did not accept it" in report && "the strip is missing from the issue detail" in report, report)
        assertTrue(jev.asked.isEmpty(), "a decision record asks no jev")
    }

    @Test
    fun `an unmet criterion in auto mode leaves the ticket open`() {
        sandbox.reset("design" to planned, "work" to done, "security" to passed, "review" to approved,
            "accept" to """{"outcome":"unmet","summary":"One misses.","criteria":[{"criterion":"The strip is on every screen","met":false,"how":"not on the issue detail"}]}""")
        val feature = start(36, "auto")
        assertTrue(history.finished(feature.id))
        assertEquals("end-not-accepted", ended(feature.id))
        assertTrue(github.closed.none { it.second == 36L })
        val report = github.commentsOn(36).last()
        assertTrue("found a criterion unmet" in report && "- [ ] The strip is on every screen: not on the issue detail" in report, report)
    }

    @Test
    fun `a work smith's question is posted and answered, and the next work run is told`() {
        sandbox.reset("design" to planned, "work" to """{"outcome":"ask","question":"Keep the two Chats panes?"}""", "work" to done,
            "security" to passed, "review" to approved, "accept" to accepted)
        val feature = start(37, "auto")
        assertEquals(listOf("user-task-answer-work"), runtime.getActiveActivityIds(feature.id))
        assertTrue("The work Smith has a question (1)" in github.commentsOn(37).last())
        decide(feature.id, "yes")
        runAllJobs()
        assertTrue(history.finished(feature.id))
        assertEquals("end-feature-done", ended(feature.id))
        assertEquals("yes", brief(sandbox.started.filter { it.mode == "work" }.last())!!.answers.single().answer)
    }

    @Test
    fun `a design result that breaks a rule stops the feature before any github write`() {
        sandbox.reset("design" to """{"outcome":"planned","plan":{"summary":"x","acceptance":[]},"questions":[{"question":"?","confidence":0.5}]}""")
        val incidents = runtime.createIncidentQuery().count()
        val feature = start(38, "auto")
        assertTrue(runtime.createIncidentQuery().count() > incidents, "an incident, propagated up the called processes")
        assertTrue(!history.finished(feature.id))
        assertEquals(emptyList(), github.commentsOn(38))
        runtime.deleteProcessInstance(feature.id, "test done")
    }

    @Test
    fun `the engine's rules for design and accept results`() {
        assertEquals(emptyList(), DesignResult.parse(planned.toByteArray()).problems())
        assertTrue(DesignResult.parse("""{"outcome":"planned","plan":{"summary":"x","acceptance":[]}}""".toByteArray()).problems().single().contains("acceptance is empty"))
        assertTrue(DesignResult.parse("""{"outcome":"planned",$plan,"questions":[{"question":"?","confidence":0.9}]}""".toByteArray()).problems().single().contains("no assumption"))
        assertTrue(DesignResult.parse("""{"outcome":"ask"}""".toByteArray()).problems().single().contains("needs a question"))
        assertNull(DesignResult.parse(sure.toByteArray()).userQuestion(), "confident and low impact: nothing to ask")
        assertNotNull(DesignResult.parse("""{"outcome":"planned",$plan,"questions":[{"question":"Delete the old panes?","assumption":"no","confidence":0.95,"impact":"high"}]}""".toByteArray()).userQuestion(),
            "high impact is asked however confident")

        assertEquals(emptyList(), AcceptResult.parse(accepted.toByteArray()).problems())
        assertTrue(AcceptResult.parse("""{"outcome":"unmet","summary":"x","criteria":[{"criterion":"a","met":true}]}""".toByteArray()).problems().single().contains("met: false"))
        assertTrue(AcceptResult.parse("""{"outcome":"accepted","summary":"x","criteria":[{"criterion":"a","met":null}]}""".toByteArray()).problems().single().contains("unverified"))
        assertTrue(AcceptResult.parse("""{"outcome":"accepted","summary":"x","criteria":[{"criterion":"a","met":false}]}""".toByteArray()).problems().single().contains("'unmet'"))
        assertTrue(ReadAcceptanceDelegate.ACCEPTED.containsMatchIn("Accepted, both hold."))
        assertTrue(ReadAcceptanceDelegate.ACCEPTED.containsMatchIn("ok"))
        assertTrue(!ReadAcceptanceDelegate.ACCEPTED.containsMatchIn("Not accepted: the strip is missing"))
    }

    // ---- bringing the PR up to date: the chore's step, shared (engine/README.md, "Bringing the PR up to date") ----

    private fun clean(n: Int) = MergeResult.Clean("head-$n", "merge-$n", "merge bundle $n".toByteArray())
    /** Behind and clean merges as is; GitHub's 405 then brings the catch-up round on the same clean merge. */
    private fun cleanAfter405(n: Int) { pulls.conflictNext = 1; git.merges.addLast(clean(n)); git.merges.addLast(clean(n)) }

    @Test
    fun `after github's 405 a feature branch that merges cleanly is pushed, security-reviewed with the merge entry, reviewed again, then merged and accepted`() {
        sandbox.reset("design" to planned, "work" to done, "security" to passed, "review" to approved,
            "security" to passed, "review" to approved, "accept" to accepted)
        cleanAfter405(1)
        val feature = start(41, "auto")

        assertTrue(history.finished(feature.id))
        assertEquals("end-feature-done", ended(feature.id))
        assertEquals(listOf("design", "work", "security", "review", "security", "review", "accept"), sandbox.started.map { it.mode })
        val security = sandbox.started[4]
        assertEquals("merge bundle 1", String(security.inputs.getValue("commits.bundle")), "security reviews the pushed merge")
        assertEquals(2, brief(security)!!.round, "the catch-up was pushed as round 2 before its security review")
        assertEquals(mapOf("default" to "main", "oldHead" to "head-1", "merge" to "merge-1"), brief(security)!!.merge)
        assertNull(brief(sandbox.started[5])!!.merge, "only a security run is told of the merge")
        assertEquals(listOf("bundle of container-2", "merge bundle 1"), git.pushes.map { it.third })
        assertEquals(3, git.mergeCalls.size, "rebase, then again after the 405, then after the merge's review")
        assertEquals(listOf(7L to "First mate strip"), pulls.merged)
        assertEquals("acme/tickets" to 41L, github.closed.last())
    }

    @Test
    fun `a feature in a repo with security reviews off goes from the push to review pr, merges and is accepted`() {
        repos.add("acme/tickets"); repos.update("acme/tickets", null, emptyList(), false)
        try {
            sandbox.reset("design" to planned, "work" to done, "review" to approved, "review" to approved, "accept" to accepted)
            cleanAfter405(1)
            val feature = start(48, "auto")

            assertTrue(history.finished(feature.id))
            assertEquals("end-feature-done", ended(feature.id))
            assertEquals(listOf("design", "work", "review", "review", "accept"), sandbox.started.map { it.mode },
                "no security run, the catch-up's included")
            assertEquals(listOf("bundle of container-2", "merge bundle 1"), git.pushes.map { it.third })
            assertEquals(listOf(7L to "First mate strip"), pulls.merged)
            assertEquals("acme/tickets" to 48L, github.closed.last())
        } finally {
            repos.update("acme/tickets", null, emptyList(), true)
        }
    }

    @Test
    fun `a conflicted feature merge goes back to a work round with a finding naming the files`() {
        sandbox.reset("design" to planned, "work" to done, "security" to passed, "review" to approved,
            "work" to done("rebase-1-1"), "security" to passed, "review" to approved, "accept" to accepted)
        git.merges.addLast(MergeResult.Conflict(listOf("a.txt", "b.txt")))
        val feature = start(42, "auto")

        assertTrue(history.finished(feature.id))
        assertEquals("end-feature-done", ended(feature.id))
        assertEquals(listOf("design", "work", "security", "review", "work", "security", "review", "accept"), sandbox.started.map { it.mode })
        val finding = brief(sandbox.started[4])!!.findings.single()
        assertEquals("rebase-1-1", finding.id)
        assertEquals("rebase", finding.review)
        assertEquals(ConflictRounds.conflictAsk("main", listOf("a.txt", "b.txt")), finding.ask)
        assertTrue(finding.ask.startsWith("merge conflict with the default branch in a.txt, b.txt"), finding.ask)
        assertEquals(listOf("bundle of container-2", "bundle of container-5"), git.pushes.map { it.third })
        assertEquals(listOf(7L to "First mate strip"), pulls.merged)
    }

    @Test
    fun `the third conflict parks the feature on an incident, and nothing is merged or accepted`() {
        sandbox.reset("design" to planned, "work" to done, "security" to passed, "review" to approved,
            "work" to done("rebase-1-1"), "security" to passed, "review" to approved,
            "work" to done("rebase-2-1"), "security" to passed, "review" to approved)
        repeat(10) { git.merges.addLast(MergeResult.Conflict(listOf("a.txt"))) } // the conflict stays for the failed job's retries
        val feature = start(43, "auto")

        assertEquals(3, sandbox.started.count { it.mode == "work" }, "the first round and two conflict rounds")
        val incident = runtime.createIncidentQuery().processInstanceId(feature.id).singleResult()
        assertEquals("task-rebase-pr", incident.activityId)
        assertTrue("after 2 conflict rounds" in incident.incidentMessage, incident.incidentMessage)
        assertEquals(emptyList(), pulls.merged)
        assertTrue(sandbox.started.none { it.mode == "accept" })
        assertTrue(github.closed.none { it.second == 43L })
        runtime.deleteProcessInstance(feature.id, "test done")
    }

    @Test
    fun `github's merge conflict at the feature's merge pr goes back through the rebase step`() {
        sandbox.reset("design" to planned, "work" to done, "security" to passed, "review" to approved,
            "security" to passed, "review" to approved, "accept" to accepted)
        pulls.conflictNext = 1
        git.merges.addLast(MergeResult.Current) // git says current, GitHub then refuses: main moved in between
        git.merges.addLast(clean(2))
        val feature = start(44, "auto")

        assertTrue(history.finished(feature.id))
        assertEquals("end-feature-done", ended(feature.id))
        assertEquals(3, git.mergeCalls.size, "rebase, then again after the 405, then after the merge's review")
        assertEquals(listOf("bundle of container-2", "merge bundle 2"), git.pushes.map { it.third })
        assertEquals(listOf(7L to "First mate strip"), pulls.merged)
    }

    // ---- the engine check after the push (bpmn/reusable-ci-check.bpmn; engine/README.md, "Chore") ----

    private fun waitingForCheck(id: String) = runtime.createExecutionQuery().messageEventSubscriptionName(CI_CHECK_MESSAGE).list()
        .any { history.createHistoricProcessInstanceQuery().processInstanceId(it.processInstanceId).singleResult().rootProcessInstanceId == id }
    private fun completed(sha: String, conclusion: String, id: Long) {
        runs.bySha[sha] = CheckRun(id, "completed", conclusion, "https://github.com/acme/tickets/actions/runs/1/job/$id")
    }

    @Test
    fun `a feature branch touching engine parks at the check after the push, before any review, and a pass goes on to security and review`() {
        git.paths = listOf("engine/src/main/kotlin/X.kt")
        sandbox.reset("design" to planned, "work" to done, "security" to passed, "review" to approved, "accept" to accepted)
        val feature = start(61, "auto")

        assertTrue(waitingForCheck(feature.id), "parked at the check with no Smith running")
        assertEquals(listOf("design", "work"), sandbox.started.map { it.mode }, "no security run before the check")
        assertEquals(emptyList(), waits.poll(), "no check run yet")
        completed("sha-1", "success", 11)
        assertEquals(61L, waits.poll().single().issue)
        runAllJobs()

        assertTrue(history.finished(feature.id))
        assertEquals(listOf("design", "work", "security", "review", "accept"), sandbox.started.map { it.mode })
        assertEquals(listOf(7L to "First mate strip"), pulls.merged)
    }

    @Test
    fun `a failed check on a feature branch goes back to the work with the ci finding, and the fix is checked again`() {
        git.paths = listOf("engine/src/main/kotlin/X.kt")
        sandbox.reset("design" to planned, "work" to done, "work" to done("ci-1-1"), "security" to passed,
            "review" to approved, "accept" to accepted)
        val feature = start(62, "auto")
        completed("sha-1", "failure", 11)
        waits.poll(); runAllJobs()

        assertEquals(listOf("design", "work", "work"), sandbox.started.map { it.mode }, "no security or correctness review of a failing branch")
        val finding = brief(sandbox.started.last { it.mode == "work" })!!.findings.single()
        assertEquals("ci-1-1", finding.id)
        assertEquals("ci", finding.review)
        assertTrue("ChoreProcessTest > a chore is worked() FAILED" in finding.ask, finding.ask)
        assertTrue(waitingForCheck(feature.id), "the fix waits for its own check")

        completed("sha-2", "success", 12)
        waits.poll(); runAllJobs()
        assertTrue(history.finished(feature.id))
        assertEquals(listOf("design", "work", "work", "security", "review", "accept"), sandbox.started.map { it.mode })
    }

    @Test
    fun `a feature branch touching nothing under engine skips the check`() {
        sandbox.reset("design" to planned, "work" to done, "security" to passed, "review" to approved, "accept" to accepted)
        val feature = start(63, "auto")

        assertTrue(history.finished(feature.id))
        assertEquals(listOf("design", "work", "security", "review", "accept"), sandbox.started.map { it.mode })
        assertEquals(emptyList(), runs.asked, "no check was read")
    }

    // ---- amend_ticket (#1362) ----

    @Autowired lateinit var ticketAmender: com.codefrak.engine.intake.TicketAmender

    @Test
    fun `an amendment at the design stops it and designs again with it, the work and the reviews after carry it`() {
        sandbox.reset("design" to planned, "design" to planned, "work" to done, "security" to passed, "review" to approved, "accept" to accepted)
        sandbox.holdMode = "design"
        val feature = start(91, "auto")
        val amended = ticketAmender.amend("acme/tickets", 91, "a dark theme too", "lundmatt1")
        assertEquals("restarted", amended.work)
        assertNull(amended.lost, "a design run pushes nothing")
        assertEquals(listOf("container-1"), sandbox.stopped)
        runAllJobs()
        assertTrue(history.finished(feature.id))
        assertEquals(listOf("design", "design", "work", "security", "review", "accept"), sandbox.started.map { it.mode })
        sandbox.started.drop(1).forEach { assertEquals(listOf("a dark theme too"), brief(it)!!.amendments.map { a -> a.text }, it.mode) }
        assertTrue(github.commentsOn(91).any { it == "@lundmatt1 added:\n\na dark theme too\n\n— engine, the work runs again with it" }, github.commentsOn(91).toString())
    }

    @Test
    fun `an amendment at a feature's work runs the work again, as on a chore`() {
        sandbox.reset("design" to planned, "work" to done, "work" to done, "security" to passed, "review" to approved, "accept" to accepted)
        sandbox.holdMode = "work"
        val feature = start(92, "auto")
        assertEquals("restarted", ticketAmender.amend("acme/tickets", 92, "cut at forty words", "lundmatt1").work)
        runAllJobs()
        assertTrue(history.finished(feature.id))
        assertEquals(listOf("design", "work", "work", "security", "review", "accept"), sandbox.started.map { it.mode })
        assertEquals(listOf("cut at forty words"), brief(sandbox.started[2])!!.amendments.map { it.text })
    }

}

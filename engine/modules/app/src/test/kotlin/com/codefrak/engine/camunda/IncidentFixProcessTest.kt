package com.codefrak.engine.camunda

import com.codefrak.engine.TestTokens
import com.codefrak.engine.ProcessFakes
import com.codefrak.engine.finished
import com.codefrak.engine.accounts.AiAccounts
import com.codefrak.engine.chore.ScriptedSandbox
import com.codefrak.engine.intake.TicketRecorder
import com.codefrak.engine.jev.FakeJevDecider
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.runAllJobs
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.smith.SandboxExitNotifier
import com.codefrak.engine.smith.SmithSpec
import com.codefrak.engine.smith.ensureTestAccount
import com.codefrak.engine.smith.dropTestAccount
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.runtime.ProcessInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.context.TestPropertySource
import org.springframework.web.server.ResponseStatusException
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fix and Propose a fix (bpmn/incident-fix.bpmn) on a chore whose work run failed, with scripted Smiths. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ProcessFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class IncidentFixProcessTest {
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var history: HistoryService
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var accounts: AiAccounts
    @Autowired lateinit var exits: SandboxExitNotifier
    @Autowired lateinit var github: TicketRecorder
    @Autowired lateinit var sandbox: ScriptedSandbox
    @Autowired lateinit var fixes: IncidentFixController
    @Autowired lateinit var access: Access
    @Autowired lateinit var targetRepos: TargetRepos
    @Autowired lateinit var jev: FakeJevDecider
    @Autowired lateinit var calls: JevCalls

    private val json = jacksonObjectMapper()
    private val repo = "acme/tickets"

    @BeforeEach
    fun setUp() {
        accounts.ensureTestAccount()
        targetRepos.add(repo)
        access.add("lundmatt1", "test")
        jev.reset()
    }

    @AfterEach
    fun dropTestAccount() { accounts.dropTestAccount() }

    private fun runAllJobs() = management.runAllJobs(exits)

    private val done = """{"outcome":"done","title":"Tidy the readme","summary":"Tidied it.","addressed":[]}"""
    private val passed = """{"outcome":"passed","summary":"fine","findings":[]}"""
    private val approved = """{"outcome":"approved","summary":"fine","findings":[]}"""
    private val retry = """{"outcome":"retry","diagnosis":"The work run's sandbox lost its network; a new run will do."}"""
    private val unfixable = """{"outcome":"unfixable","diagnosis":"The ticket asks for a file that does not exist."}"""

    /** A chore whose first work run exits 1, and its automatic rerun too: it stops on an incident. */
    private fun failedChore(issue: Long): Pair<ProcessInstance, IncidentView> {
        sandbox.failNext = 2
        val chore = runtime.createMessageCorrelation("chore filed")
            .setVariables(mapOf("repo" to repo, "issue" to issue)).correlateStartMessage()
        runAllJobs()
        return chore to assertNotNull(fixes.fixes(url(issue)).incident, "the failed work run left an incident")
    }

    private fun url(issue: Long) = "https://github.com/$repo/issues/$issue"
    private fun brief(spec: SmithSpec): Brief = json.readValue(spec.inputs.getValue("brief.json"))
    private fun fixVar(id: String, name: String) =
        history.createHistoricVariableInstanceQuery().processInstanceId(id).variableName(name).singleResult()?.value

    @Test
    fun `a Fix whose Smith says retry retries the incident, and the flow goes on to the end`() {
        sandbox.reset("work" to done, "fix" to retry, "work" to done, "security" to passed, "review" to approved)
        val (chore, incident) = failedChore(40)

        val started = fixes.fix(incident.id, RetryRequest(), TestTokens.asLogin("lundmatt1"))
        assertEquals("fix", started.mode)
        assertEquals(incident.id, started.incidentId)
        runAllJobs()

        assertEquals(listOf("work", "work", "fix", "work", "security", "review"), sandbox.started.map { it.mode })
        val told = brief(sandbox.started[2]).incident!!
        assertEquals(incident.id, told["id"])
        assertEquals(chore.id, told["rootInstanceId"])
        assertEquals("chore", told["rootFlow"])
        assertTrue(history.finished(started.fixId))
        assertEquals("retried", fixVar(started.fixId, FixVars.OUTCOME))
        assertTrue(history.finished(chore.id), "the retried chore went on to the end")
        assertTrue(github.comments.any { "started its Smith run again" in it.third })

        val view = fixes.fixes(url(40))
        assertNull(view.incident)
        assertEquals("retried", view.fix!!.state)
        assertFalse(view.fix!!.fixFailed)
        assertEquals("The work run's sandbox lost its network; a new run will do.", view.fix!!.diagnosis)
        assertTrue(fixes.fixes(url(40)).let { it.incident == null && it.propose == null })
        assertTrue(runtime.createProcessInstanceQuery().variableValueEquals("repo", repo).variableValueEquals("issue", 40L).list()
            .none { it.processDefinitionId.startsWith("incident-fix") }, "the fix does not pass for the ticket's flow")
    }

    @Test
    fun `a Fix whose retry fails again, or whose Smith finds it unfixable, reads fix failed`() {
        sandbox.reset("work" to done, "fix" to retry, "work" to done, "fix" to unfixable)
        val (chore, incident) = failedChore(41)

        fixes.fix(incident.id, RetryRequest(), TestTokens.asLogin("lundmatt1"))
        sandbox.failMode = "work"
        runAllJobs()
        val again = fixes.fixes(url(41))
        assertNotNull(again.incident, "the retried work run failed too")
        assertEquals("retried", again.fix!!.state)
        assertTrue(again.fix!!.fixFailed, "an incident since the fix ended: it did not fix it")

        fixes.fix(again.incident!!.id, RetryRequest(), TestTokens.asLogin("lundmatt1"))
        runAllJobs()
        val refused = fixes.fixes(url(41))
        assertEquals("failed", refused.fix!!.state)
        assertTrue(refused.fix!!.fixFailed)
        assertEquals("the smith found it unfixable", refused.fix!!.detail)
        assertEquals(again.incident!!.id, refused.incident!!.id, "unfixable leaves the incident alone")
        runtime.deleteProcessInstance(chore.id, "test done")
    }

    @Test
    fun `Jev's confident retry retries the incident at once, with no Smith run, and records it`() {
        sandbox.reset("work" to done, "work" to done, "security" to passed, "review" to approved)
        val (chore, incident) = failedChore(48)
        jev.choice = IncidentTriage.RETRY to 0.95

        val started = fixes.fix(incident.id, RetryRequest(), TestTokens.asLogin("lundmatt1"))
        runAllJobs()

        assertEquals(listOf("work", "work", "work", "security", "review"), sandbox.started.map { it.mode }, "no fix Smith ran")
        val (useCase, context, state) = jev.asked.single()
        assertEquals(IncidentTriage.USE_CASE, useCase)
        assertEquals(chore.id, context.processInstance)
        assertEquals(repo, context.repo)
        assertEquals(48L, context.issue)
        state as Map<*, *>
        assertEquals(incident.activityId, state["activityId"])
        assertEquals(incident.message, state["message"])
        assertEquals(IncidentTriage.REMEDIES, jev.questionSets.single()[IncidentTriage.QUESTION])
        assertTrue(history.finished(started.fixId))
        assertEquals("retried", fixVar(started.fixId, FixVars.OUTCOME))
        assertTrue(history.finished(chore.id), "the retried chore went on to the end")

        val row = calls.get(jev.ids.single())!!
        assertEquals(0.9, row.threshold)
        assertEquals("retry: retried without a Smith", row.action)
        assertEquals("retried without a Smith: retried", row.outcome)
        val view = fixes.fixes(url(48))
        assertEquals("retried", view.fix!!.state)
        assertFalse(view.fix!!.fixFailed)
        assertTrue("Jev" in view.fix!!.diagnosis!!)
    }

    @Test
    fun `a Jev retry that trips again reads fix failed, is its call's outcome, and the next Fix gets the Smith`() {
        sandbox.reset("work" to done, "work" to done, "fix" to unfixable)
        val (chore, incident) = failedChore(49)
        jev.choice = IncidentTriage.RETRY to 0.97

        fixes.fix(incident.id, RetryRequest(), TestTokens.asLogin("lundmatt1"))
        sandbox.failMode = "work"
        runAllJobs()
        val again = fixes.fixes(url(49))
        assertNotNull(again.incident, "the retried work run failed too")
        assertEquals("retried", again.fix!!.state)
        assertTrue(again.fix!!.fixFailed)
        val outcome = calls.get(jev.ids.single())!!.outcome!!
        assertTrue(outcome.startsWith("retried without a Smith: retried; tripped again (fixFailed)"), outcome)
        fixes.fixes(url(49))
        assertEquals(outcome, calls.get(jev.ids.single())!!.outcome, "recorded once")

        fixes.fix(again.incident!!.id, RetryRequest(), TestTokens.asLogin("lundmatt1"))
        runAllJobs()
        assertEquals(1, jev.asked.size, "Jev is not asked again after its retry tripped")
        assertEquals(listOf("work", "work", "work", "fix"), sandbox.started.map { it.mode })
        assertEquals("the smith found it unfixable", fixes.fixes(url(49)).fix!!.detail)
        runtime.deleteProcessInstance(chore.id, "test done")
    }

    @Test
    fun `investigate, unfixable, a retry below the floor or no answer start the Fix Smith as before`() {
        listOf<Pair<String, Double>?>(IncidentTriage.INVESTIGATE to 0.99, IncidentTriage.UNFIXABLE to 0.99, IncidentTriage.RETRY to 0.6, null)
            .forEachIndexed { i, pick ->
                val issue = 50L + i
                sandbox.reset("work" to done, "fix" to unfixable)
                jev.reset()
                val (chore, incident) = failedChore(issue)
                jev.choice = pick

                val started = fixes.fix(incident.id, RetryRequest(), TestTokens.asLogin("lundmatt1"))
                runAllJobs()

                assertEquals(listOf("work", "work", "fix"), sandbox.started.map { it.mode }, "$pick: the Fix Smith ran")
                assertEquals("failed", fixVar(started.fixId, FixVars.OUTCOME))
                assertEquals(false, fixVar(started.fixId, FixVars.JEV_RETRY))
                val row = calls.get(jev.ids.single())!!
                assertEquals(when (pick) {
                    null -> "no answer: Fix Smith"
                    IncidentTriage.RETRY to 0.6 -> "retry below floor 0.9: Fix Smith"
                    else -> "${pick.first}: Fix Smith"
                }, row.action)
                assertEquals("the Fix Smith's remedy: unfixable, failed (the smith found it unfixable)", row.outcome)
                assertEquals(incident.id, fixes.fixes(url(issue)).incident!!.id)
                runtime.deleteProcessInstance(chore.id, "test done")
            }
    }

    @Test
    fun `Propose a fix does not ask Jev`() {
        sandbox.reset("work" to done, "diagnose" to """{"outcome":"proposed","summary":"Retry it","diagnosis":"network"}""")
        val (chore, incident) = failedChore(54)
        jev.choice = IncidentTriage.RETRY to 0.99
        fixes.propose(incident.id, RetryRequest(), TestTokens.asLogin("lundmatt1"))
        runAllJobs()
        assertTrue(jev.asked.isEmpty())
        assertEquals(listOf("work", "work", "diagnose"), sandbox.started.map { it.mode })
        runtime.deleteProcessInstance(chore.id, "test done")
    }

    @Test
    fun `Propose a fix changes nothing and keeps its summary to 40 characters`() {
        val summary = "Retry it: the work run's sandbox lost its network mid-run"
        sandbox.reset("work" to done, "diagnose" to """{"outcome":"proposed","summary":"$summary","diagnosis":"network"}""")
        val (chore, incident) = failedChore(42)

        val started = fixes.propose(incident.id, RetryRequest(), TestTokens.asLogin("lundmatt1"))
        assertEquals("diagnose", started.mode)
        assertEquals(HttpStatus.CONFLICT, assertFailsWith<ResponseStatusException> {
            fixes.propose(incident.id, RetryRequest(), TestTokens.asLogin("lundmatt1")) }.statusCode, "one proposal at a time per incident")
        assertEquals("running", fixes.fixes(url(42)).propose!!.state)
        runAllJobs()

        val view = fixes.fixes(url(42))
        assertEquals(incident.id, view.incident!!.id, "the incident is still open")
        assertNull(view.fix)
        assertEquals("proposed", view.propose!!.state)
        assertEquals(proposal(summary), view.propose!!.proposal)
        assertEquals(MAX_PROPOSAL, view.propose!!.proposal!!.length)
        assertEquals(listOf("work", "work", "diagnose"), sandbox.started.map { it.mode })
        runtime.deleteProcessInstance(chore.id, "test done")
    }

    @Test
    fun `a diagnose run that fails is a failed proposal, not an incident of its own`() {
        sandbox.reset("work" to done, "diagnose" to "not json")
        val (chore, incident) = failedChore(43)

        val started = fixes.propose(incident.id, RetryRequest(), TestTokens.asLogin("lundmatt1"))
        runAllJobs()
        assertTrue(history.finished(started.fixId))
        assertEquals("failed", fixes.fixes(url(43)).propose!!.state)
        assertNotNull(fixVar(started.fixId, FixVars.DETAIL))
        runtime.deleteProcessInstance(chore.id, "test done")
    }

    @Test
    fun `a running Fix or Propose a fix on an incident refuses a second start of either`() {
        sandbox.reset("work" to done, "fix" to unfixable)
        val (chore, incident) = failedChore(45)
        fixes.fix(incident.id, RetryRequest(), TestTokens.asLogin("lundmatt1"))
        assertEquals(HttpStatus.CONFLICT, assertFailsWith<ResponseStatusException> {
            fixes.fix(incident.id, RetryRequest(), TestTokens.asLogin("lundmatt1")) }.statusCode, "one fix at a time per incident")
        assertEquals(HttpStatus.CONFLICT, assertFailsWith<ResponseStatusException> {
            fixes.propose(incident.id, RetryRequest(), TestTokens.asLogin("lundmatt1")) }.statusCode, "no proposal while a fix runs")
        runAllJobs()
        runtime.deleteProcessInstance(chore.id, "test done")

        sandbox.reset("work" to done, "diagnose" to """{"outcome":"proposed","summary":"Retry it","diagnosis":"network"}""")
        val (other, second) = failedChore(46)
        fixes.propose(second.id, RetryRequest(), TestTokens.asLogin("lundmatt1"))
        assertEquals(HttpStatus.CONFLICT, assertFailsWith<ResponseStatusException> {
            fixes.fix(second.id, RetryRequest(), TestTokens.asLogin("lundmatt1")) }.statusCode, "no fix while a proposal runs")
        runAllJobs()
        runtime.deleteProcessInstance(other.id, "test done")
    }

    @Test
    fun `no open incident is a 404, and a login without access a 403`() {
        sandbox.reset("work" to done)
        val (chore, incident) = failedChore(44)
        assertEquals(HttpStatus.NOT_FOUND, assertFailsWith<ResponseStatusException> {
            fixes.fix("nope", RetryRequest(), TestTokens.asLogin("lundmatt1")) }.statusCode)
        assertEquals(HttpStatus.FORBIDDEN, assertFailsWith<ResponseStatusException> {
            fixes.fix(incident.id, RetryRequest(), TestTokens.asLogin("mallory")) }.statusCode)
        assertEquals(HttpStatus.FORBIDDEN, assertFailsWith<ResponseStatusException> {
            fixes.propose(incident.id, RetryRequest(), TestTokens.asLogin("mallory")) }.statusCode)
        runtime.deleteProcessInstance(chore.id, "test done")
        assertNull(fixes.fixes(url(44)).incident)
        assertEquals(HttpStatus.NOT_FOUND, assertFailsWith<ResponseStatusException> {
            fixes.propose("nope", RetryRequest(), TestTokens.asLogin("lundmatt1")) }.statusCode)
        assertEquals(HttpStatus.NOT_FOUND, assertFailsWith<ResponseStatusException> {
            fixes.propose(incident.id, RetryRequest(), TestTokens.asLogin("lundmatt1")) }.statusCode, "the incident is no longer open")
        assertEquals(HttpStatus.NOT_FOUND, assertFailsWith<ResponseStatusException> {
            fixes.fix(incident.id, RetryRequest(), TestTokens.asLogin("lundmatt1")) }.statusCode, "the incident is no longer open")
    }

    @Test
    fun `a Smith sandbox may not start a Fix or Propose a fix, and an unreachable Docker is 503, not a human`() {
        sandbox.reset("work" to done)
        val (chore, incident) = failedChore(47)
        val smith = "172.30.0.9"
        sandbox.callers[smith] = SmithCaller("run-9", 47, repo)
        val from = MockHttpServletRequest().apply { remoteAddr = smith }
        assertEquals(HttpStatus.FORBIDDEN, assertFailsWith<ResponseStatusException> {
            fixes.fix(incident.id, RetryRequest(), from) }.statusCode)
        assertEquals(HttpStatus.FORBIDDEN, assertFailsWith<ResponseStatusException> {
            fixes.propose(incident.id, RetryRequest(), from) }.statusCode)
        sandbox.runAtFails = true
        val human = MockHttpServletRequest().apply { remoteAddr = "127.0.0.1" }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertFailsWith<ResponseStatusException> {
            fixes.fix(incident.id, RetryRequest(), human) }.statusCode)
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertFailsWith<ResponseStatusException> {
            fixes.propose(incident.id, RetryRequest(), human) }.statusCode)
        assertEquals(0, runtime.createProcessInstanceQuery().processDefinitionKey(IncidentFixController.PROCESS)
            .variableValueEquals(FixVars.INCIDENT_ID, incident.id).count(), "no incident-fix started")
        assertEquals(listOf("work", "work"), sandbox.started.map { it.mode }, "no Smith run started")
        sandbox.runAtFails = false
        runtime.deleteProcessInstance(chore.id, "test done")
    }
}

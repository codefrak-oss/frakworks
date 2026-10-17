package com.codefrak.engine.sdk

import com.codefrak.engine.ProcessFakes
import com.codefrak.engine.chore.ScriptedSandbox
import com.codefrak.engine.intake.TicketRecorder
import com.codefrak.engine.runAllJobs
import com.codefrak.engine.smith.BRIEF_FILE
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.SmithExitNotifier
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.ensureTestAccount
import com.codefrak.engine.smith.dropTestAccount
import com.codefrak.sdk.CallableSmith
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
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `callable-smith` (bpmn/callable-smith.bpmn; engine/README.md, "Custom jobs and the SDK"), called from the test-only
 * `plugin-test` diagram the way a plugin's would, with a scripted sandbox answering mode `job`.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ProcessFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class CallableSmithProcessTest {
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var history: HistoryService
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var exits: SmithExitNotifier
    @Autowired lateinit var sandbox: ScriptedSandbox
    @Autowired lateinit var github: TicketRecorder
    @Autowired lateinit var accounts: com.codefrak.engine.accounts.AiAccounts

    private val json = jacksonObjectMapper()

    @BeforeEach fun setUp() { sandbox.reset(); accounts.ensureTestAccount() }
    @AfterEach fun dropTestAccount() { accounts.dropTestAccount() }

    private fun runAllJobs() = management.runAllJobs(exits)

    private fun variable(instanceId: String, name: String) =
        history.createHistoricVariableInstanceQuery().processInstanceId(instanceId).variableName(name).singleResult()?.value

    @Test
    fun `a plugin diagram calls callable-smith with a prompt and a repo, a job run runs it, and result is the run's result json`() {
        sandbox.script.getOrPut("job") { ArrayDeque() }.addLast("""{"outcome":"done","summary":"ran"}""")
        val comments = github.comments.size
        val root = runtime.startProcessInstanceByKey("plugin-test", mapOf(
            CallableSmith.PROMPT to "Count the files under docs/ and say how many.", CallableSmith.REPO to "acme/widgets",
            Vars.MODEL to "claude-sonnet-5", Vars.EFFORT to "low", Vars.DRY_RUN to true))
        runAllJobs()

        val callable = history.createHistoricProcessInstanceQuery().processDefinitionKey(CallableSmith.PROCESS_KEY).superProcessInstanceId(root.id).singleResult()
        val spec = sandbox.started.single()
        assertEquals("job", spec.mode)
        assertNull(spec.issue, "a job run has no issue")
        assertEquals(callable.id, spec.jobId, "the job id is the callable-smith instance's id when the caller gave none")
        assertEquals("acme/widgets", spec.repo)
        val brief = json.readValue<Brief>(spec.inputs.getValue(BRIEF_FILE))
        assertEquals("Count the files under docs/ and say how many.", brief.prompt)
        assertNull(brief.ticket)

        assertTrue(history.createHistoricProcessInstanceQuery().processInstanceId(root.id).finished().count() == 1L, "the caller ended")
        val result = variable(root.id, CallableSmith.RESULT) as String
        assertEquals(mapOf("outcome" to "done", "summary" to "ran"), json.readValue<Map<String, Any?>>(result))
        assertEquals("done", variable(root.id, "outcome"))
        assertEquals(comments, github.comments.size, "nothing is written to GitHub for a job run")
    }

    @Test
    fun `a caller's own jobId is kept, and a call with no prompt fails before any sandbox starts`() {
        sandbox.script.getOrPut("job") { ArrayDeque() }.addLast("""{"outcome":"failed","summary":"could not"}""")
        val root = runtime.startProcessInstanceByKey("plugin-test", mapOf(
            CallableSmith.PROMPT to "Try.", CallableSmith.REPO to "acme/widgets", Vars.JOB_ID to "nightly-7",
            Vars.MODEL to "claude-sonnet-5", Vars.EFFORT to "low", Vars.DRY_RUN to true))
        runAllJobs()
        assertEquals("nightly-7", sandbox.started.single().jobId)
        assertEquals("failed", variable(root.id, "outcome"))

        val started = sandbox.started.size
        val bare = runtime.startProcessInstanceByKey("plugin-test", mapOf(CallableSmith.REPO to "acme/widgets", Vars.DRY_RUN to true))
        runAllJobs()
        assertEquals(started, sandbox.started.size)
        val incident = runtime.createIncidentQuery().processInstanceId(bare.id).list().single()
        assertTrue("prompt" in (incident.incidentMessage ?: ""), incident.incidentMessage)
        runtime.deleteProcessInstance(bare.id, "test done", true)
    }
}

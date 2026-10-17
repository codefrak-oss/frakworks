package com.codefrak.engine.jobs

import com.codefrak.engine.TestTokens
import com.codefrak.engine.ProcessFakes
import com.codefrak.engine.chore.ScriptedSandbox
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.events.EventRecorder
import com.codefrak.engine.intake.TicketRecorder
import com.codefrak.engine.mcp.EngineMcpController
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.runAllJobs
import com.codefrak.engine.smith.BRIEF_FILE
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.SmithExitNotifier
import com.codefrak.engine.smith.ensureTestAccount
import com.codefrak.engine.smith.dropTestAccount
import com.codefrak.fixtureplugin.FixtureDelegate
import com.codefrak.fixtureplugin.FixtureListener
import com.codefrak.fixtureplugin.RecordingJob
import com.codefrak.sdk.CallableSmith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A job's run end to end (bpmn/job.bpmn; engine/README.md, "Custom jobs and the SDK"): a prompt job from
 * `POST /jobs/{id}/execute` through `callable-smith` with a scripted sandbox answering mode `job`, and its two `job`
 * frames; a bean job from `POST /jobs` (the fixture jar loaded) through `task-run-bean-job`, done or `failed: <message>`
 * with no incident; a flow job from `POST /jobs` (the fixture jar's `fixture-flow` deployed as `job:<id>`) through
 * `call-plugin-flow`, its delegate and listener bound from the jar, and the undeploy guard on its delete.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ProcessFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class JobProcessTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var history: HistoryService
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var exits: SmithExitNotifier
    @Autowired lateinit var sandbox: ScriptedSandbox
    @Autowired lateinit var github: TicketRecorder
    @Autowired lateinit var accounts: com.codefrak.engine.accounts.AiAccounts
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var access: Access
    @Autowired lateinit var store: JobStore
    @Autowired lateinit var bus: EngineEvents
    @Autowired lateinit var pluginJars: PluginJars
    @Autowired lateinit var repository: RepositoryService

    private lateinit var jars: java.nio.file.Path

    private val json = jacksonObjectMapper()
    private val repo = "acme/jobflow"
    private val login = "alice"

    @BeforeEach fun setUp() {
        runtime.createProcessInstanceQuery().processDefinitionKey(JobStarter.PROCESS_KEY).list().forEach { runtime.deleteProcessInstance(it.id, "test reset") }
        repos.remove(repo); repos.add(repo)
        access.add(login, "test")
        sandbox.reset(); accounts.ensureTestAccount()
        jars = PluginJarFixture.directory(); pluginJars.closeAll(); pluginJars.useDirectory(jars); RecordingJob.runs.clear()
        dropPluginDeployments()
    }
    @AfterEach fun dropTestAccount() { accounts.dropTestAccount(); pluginJars.closeAll(); PluginJarFixture.delete(jars); dropPluginDeployments() }

    /** A flow job's deployment left by a test (or another class on the shared database) would make the next registration's key "already deployed". */
    private fun dropPluginDeployments() =
        repository.createDeploymentQuery().deploymentSource(PluginFlows.SOURCE).list().forEach { repository.deleteDeployment(it.id, true) }

    private fun runAllJobs() = management.runAllJobs(exits)

    private fun variable(instanceId: String, name: String) =
        history.createHistoricVariableInstanceQuery().processInstanceId(instanceId).variableName(name).singleResult()?.value

    private fun register(kind: String, config: String): Job =
        store.insert("the $kind job", JobKind.of(kind), repo, json.readTree(config), Signal.Execute, login)

    private fun execute(id: String): String {
        val result = mvc.post("/jobs/$id/execute") { with(TestTokens.bearer(login)) }.andReturn()
        assertEquals(200, result.response.status, result.response.errorMessage ?: "")
        return json.readValue<Map<String, String>>(result.response.contentAsString).getValue("instance")
    }

    @Test
    fun `a prompt job executed runs callable-smith in mode job on its prompt and repo, and the stream says started and ended with the outcome`() = EventRecorder(bus, repo).use { rec ->
        sandbox.script.getOrPut("job") { ArrayDeque() }.addLast("""{"outcome":"done","summary":"ran"}""")
        val job = register("prompt", """{"prompt":"Count the files under docs/ and say how many."}""")
        val comments = github.comments.size
        val root = execute(job.id)
        runAllJobs()

        val callable = history.createHistoricProcessInstanceQuery().processDefinitionKey(CallableSmith.PROCESS_KEY).superProcessInstanceId(root).singleResult()
        val spec = sandbox.started.single()
        assertEquals("job", spec.mode)
        assertNull(spec.issue, "a job run has no issue")
        assertEquals(job.id, spec.jobId, "the job's id is the run's")
        assertEquals(repo, spec.repo)
        val brief = json.readValue<Brief>(spec.inputs.getValue(BRIEF_FILE))
        assertEquals("Count the files under docs/ and say how many.", brief.prompt)
        assertEquals(job.id, variable(callable.id, "jobId"))

        assertEquals(1L, history.createHistoricProcessInstanceQuery().processInstanceId(root).finished().count(), "the job instance ended")
        assertEquals(mapOf("outcome" to "done", "summary" to "ran"), json.readValue<Map<String, Any?>>(variable(root, CallableSmith.RESULT) as String))
        assertEquals("done", variable(root, "outcome"))
        assertEquals(comments, github.comments.size, "nothing is written to GitHub for a job run")
        assertEquals(root, store.get(job.id)!!.lastInstance)

        val frames = rec.of("job", Duration.ofSeconds(2)).filter { it.str("jobId") == job.id }
        assertEquals(listOf("started", "ended"), frames.map { it.state }, frames.toString())
        frames.forEach {
            assertEquals(repo, it.str("repo")); assertEquals(root, it.str("instance")); assertEquals("the prompt job", it.str("name")); assertEquals("prompt", it.str("kind"))
        }
        assertNull(frames[0].str("outcome"))
        assertEquals("done", frames[1].str("outcome"))
    }

    /** A bean job through `POST /jobs`, so the registrar loads the jar as it does for an operator. */
    private fun registerBean(bean: String): String {
        val body = """{"name":"the bean job","kind":"bean","repo":"$repo","config":{"jar":"fixture.jar","bean":"$bean"},"signal":{"kind":"execute"}}"""
        val result = mvc.post("/jobs") { contentType = MediaType.APPLICATION_JSON; content = body; with(TestTokens.bearer(login)) }.andReturn()
        assertEquals(201, result.response.status, result.response.errorMessage ?: "")
        return json.readValue<Map<String, Any?>>(result.response.contentAsString).getValue("id") as String
    }

    @Test
    fun `a bean job executed runs its FrakJob with the job's id, repo and config, and the stream says ended with done`() = EventRecorder(bus, repo).use { rec ->
        PluginJarFixture.write(jars)
        val id = registerBean("recorder")
        val root = execute(id)
        runAllJobs()

        val run = RecordingJob.runs.single()
        assertEquals(id, run.jobId); assertEquals(repo, run.repo)
        assertEquals(mapOf("jar" to "fixture.jar", "bean" to "recorder"), run.config)
        assertEquals("bean", run.kindVariable, "the flow's variables are the context's")
        assertEquals(true, variable(root, "beanRan"), "a variable the job set reached the flow")
        assertEquals(1L, history.createHistoricProcessInstanceQuery().processInstanceId(root).finished().count(), "the job instance ended")
        assertEquals(0L, runtime.createIncidentQuery().processInstanceId(root).count())
        assertNull(variable(root, "outcome"), "a bean job that returns sets no outcome")
        assertTrue(sandbox.started.isEmpty(), "no sandbox for a bean job")

        val frames = rec.of("job", Duration.ofSeconds(2)).filter { it.str("jobId") == id }
        assertEquals(listOf("started", "ended"), frames.map { it.state }, frames.toString())
        assertEquals("bean", frames[1].str("kind")); assertEquals("done", frames[1].str("outcome"))

        mvc.delete("/jobs/$id") { with(TestTokens.bearer(login)) }.andExpect { status { isNoContent() } }
        assertEquals(emptyList(), pluginJars.loaded(), "the last job's delete closes the jar")
    }

    @Test
    fun `a bean job whose FrakJob throws ends with failed and the message, with no incident`() = EventRecorder(bus, repo).use { rec ->
        PluginJarFixture.write(jars)
        val id = registerBean("thrower")
        val root = execute(id)
        runAllJobs()

        assertEquals(1L, history.createHistoricProcessInstanceQuery().processInstanceId(root).finished().count(), "the job instance ended")
        assertEquals(0L, runtime.createIncidentQuery().processInstanceId(root).count(), "no incident")
        assertEquals("failed: boom", variable(root, "outcome"))
        val frames = rec.of("job", Duration.ofSeconds(2)).filter { it.str("jobId") == id }
        assertEquals(listOf("started", "ended"), frames.map { it.state }, frames.toString())
        assertEquals("failed: boom", frames[1].str("outcome"))
        assertTrue(RecordingJob.runs.isEmpty())
        mvc.delete("/jobs/$id") { with(TestTokens.bearer(login)) }.andExpect { status { isNoContent() } }
    }

    @Test
    fun `a bean job whose jar is gone at run time ends with failed and the loader's reason, with no incident`() = EventRecorder(bus, repo).use { rec ->
        PluginJarFixture.write(jars)
        val id = registerBean("recorder")
        pluginJars.closeAll() // As after an engine restart: nothing is loaded, and the run reloads from the directory.
        java.nio.file.Files.delete(jars.resolve("fixture.jar"))
        val root = execute(id)
        runAllJobs()

        assertEquals(0L, runtime.createIncidentQuery().processInstanceId(root).count(), "no incident")
        assertEquals("failed: jar fixture.jar is not in /var/lib/frakworks/jobs", variable(root, "outcome"))
        assertEquals("failed: jar fixture.jar is not in /var/lib/frakworks/jobs", rec.of("job", Duration.ofSeconds(2)).last { it.str("jobId") == id }.str("outcome"))
        assertTrue(RecordingJob.runs.isEmpty())
        store.delete(id)
    }

    /** A flow job through `POST /jobs`, so the registrar loads the fixture jar and deploys its `fixture-flow` as `job:<id>`. */
    private fun registerFlow(): String {
        PluginJarFixture.write(jars, diagrams = listOf(PluginJarFixture.FLOW))
        val body = """{"name":"the flow job","kind":"flow","repo":"$repo","config":{"jar":"fixture.jar","process":"${PluginJarFixture.FLOW}"},"signal":{"kind":"execute"}}"""
        val result = mvc.post("/jobs") { contentType = MediaType.APPLICATION_JSON; content = body; with(TestTokens.bearer(login)) }.andReturn()
        assertEquals(201, result.response.status, result.response.errorMessage ?: "")
        return json.readValue<Map<String, Any?>>(result.response.contentAsString).getValue("id") as String
    }

    @Test
    fun `a flow job executed runs its jar's process with the job's variables, callable-smith's result reaches the jar's delegate, and the stream says ended with done`() = EventRecorder(bus, repo).use { rec ->
        sandbox.script.getOrPut("job") { ArrayDeque() }.addLast("""{"outcome":"done","summary":"hello"}""")
        FixtureDelegate.seen.clear(); FixtureListener.events.clear()
        val id = registerFlow()
        val root = execute(id)
        runAllJobs()

        val plugin = history.createHistoricProcessInstanceQuery().processDefinitionKey(PluginJarFixture.FLOW).superProcessInstanceId(root).singleResult()
        assertEquals(PluginJarFixture.FLOW, variable(root, PrepareFlowJobDelegate.PROCESS_KEY_VAR))
        assertEquals(id, variable(plugin.id, "jobId"), "the job instance's variables went in")
        assertEquals(repo, variable(plugin.id, "repo"))
        assertEquals("flow", variable(plugin.id, "kind"))
        val spec = sandbox.started.single()
        assertEquals("job", spec.mode); assertEquals(id, spec.jobId); assertEquals(repo, spec.repo)
        assertEquals("say hello", json.readValue<Brief>(spec.inputs.getValue(BRIEF_FILE)).prompt, "the plugin flow's literal prompt")

        val resultJson = """{"outcome":"done","summary":"hello"}"""
        assertEquals(listOf(id to resultJson), FixtureDelegate.seen.map { it.first to it.second?.let { r -> json.writeValueAsString(json.readTree(r)) } }, "the jar's delegate ran with callable-smith's result")
        assertEquals(listOf("task-fixture-delegate:start"), FixtureListener.events, "the jar's listener ran")
        assertEquals(true, variable(plugin.id, "flowRan"))
        assertEquals(1L, history.createHistoricProcessInstanceQuery().processInstanceId(plugin.id).finished().count(), "the plugin flow ended")
        assertEquals(1L, history.createHistoricProcessInstanceQuery().processInstanceId(root).finished().count(), "the job instance ended")
        assertEquals(0L, runtime.createIncidentQuery().processInstanceId(root).count())
        assertEquals("done", variable(root, "outcome"))
        assertEquals(mapOf("outcome" to "done", "summary" to "hello"), json.readValue<Map<String, Any?>>(variable(root, CallableSmith.RESULT) as String))

        val frames = rec.of("job", Duration.ofSeconds(2)).filter { it.str("jobId") == id }
        assertEquals(listOf("started", "ended"), frames.map { it.state }, frames.toString())
        assertEquals("flow", frames[1].str("kind")); assertEquals("done", frames[1].str("outcome")); assertEquals("the flow job", frames[1].str("name"))

        mvc.delete("/jobs/$id") { with(TestTokens.bearer(login)) }.andExpect { status { isNoContent() } }
        assertEquals(0L, repository.createDeploymentQuery().deploymentName(PluginFlows.deploymentName(id)).count(), "undeployed")
        assertEquals(emptyList(), pluginJars.loaded(), "the last job's delete closes the jar")
    }

    @Test
    fun `a flow job whose plugin flow still runs cannot be deleted, 409 naming the deployment, and can once it ended`() {
        sandbox.script.getOrPut("job") { ArrayDeque() }.addLast("""{"outcome":"done","summary":"late"}""")
        sandbox.holdMode = "job" // The Smith run keeps running: the plugin flow waits in callable-smith.
        val id = registerFlow()
        val root = execute(id)
        runAllJobs()
        val plugin = runtime.createProcessInstanceQuery().processDefinitionKey(PluginJarFixture.FLOW).superProcessInstanceId(root).singleResult()
        assertNotNull(plugin, "the plugin flow runs")

        // The job instance runs, so the merged 409 answers first; with it gone, the plugin flow's own instance still holds the deployment.
        val whileJobRuns = mvc.delete("/jobs/$id") { with(TestTokens.bearer(login)) }.andReturn()
        assertEquals(409, whileJobRuns.response.status); assertEquals("job $id is running in $root", whileJobRuns.response.errorMessage)
        runtime.deleteProcessInstance(root, "test: the job instance alone", false, true, false, true)
        assertEquals(1L, runtime.createProcessInstanceQuery().processInstanceId(plugin.id).count(), "the plugin flow stays")
        val whileFlowRuns = mvc.delete("/jobs/$id") { with(TestTokens.bearer(login)) }.andReturn()
        assertEquals(409, whileFlowRuns.response.status)
        assertContains(whileFlowRuns.response.errorMessage!!, "1 instance(s) of ${PluginFlows.deploymentName(id)}")
        assertNotNull(store.get(id)); assertEquals(1L, repository.createDeploymentQuery().deploymentName(PluginFlows.deploymentName(id)).count())

        runtime.deleteProcessInstance(plugin.id, "test done")
        mvc.delete("/jobs/$id") { with(TestTokens.bearer(login)) }.andExpect { status { isNoContent() } }
        assertEquals(0L, repository.createDeploymentQuery().deploymentName(PluginFlows.deploymentName(id)).count())
    }
}

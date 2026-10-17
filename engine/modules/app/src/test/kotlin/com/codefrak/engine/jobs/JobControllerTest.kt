package com.codefrak.engine.jobs

import com.codefrak.engine.TestTokens
import com.codefrak.engine.ProcessFakes
import com.codefrak.engine.chore.ScriptedSandbox
import com.codefrak.engine.mcp.EngineMcpController
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.SmithCaller
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `/jobs` (engine/README.md, "Custom jobs and the SDK") over MockMvc. Jobs are not executed, so a started `job` instance
 * stays at its first async step and is what the 409s see. The sandbox is scripted: `callers` says which address is a Smith.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ProcessFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class JobControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var access: Access
    @Autowired lateinit var store: JobStore
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var repository: RepositoryService
    @Autowired lateinit var sandbox: ScriptedSandbox
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var pluginJars: PluginJars

    private val json = jacksonObjectMapper()
    private val repo = "acme/jobctl"
    private val other = "acme/jobctl-other"
    private val login = "alice"
    private val smithAddress = "172.18.0.9"
    private lateinit var jars: java.nio.file.Path

    private fun beanJob(jar: String = "fixture.jar", bean: String = "recorder", name: String = "sweep") =
        """{"name":"$name","kind":"bean","repo":"$repo","config":{"jar":"$jar","bean":"$bean"},"signal":{"kind":"execute"}}"""

    private fun MockHttpServletRequestDsl.asJson(body: String, who: String? = login, from: String? = null) {
        contentType = MediaType.APPLICATION_JSON; content = body
        with(who?.let { TestTokens.bearer(it) } ?: TestTokens.noToken)
        if (from != null) with(RequestPostProcessor { it.remoteAddr = from; it })
    }

    private fun promptJob(name: String = "nightly docs count", signal: String = """{"kind":"execute"}""", target: String = repo) =
        """{"name":"$name","kind":"prompt","repo":"$target","config":{"prompt":"Count the files under docs/."},"signal":$signal}"""

    private fun create(body: String, who: String? = login, from: String? = null): MvcResult =
        mvc.post("/jobs") { asJson(body, who, from) }.andReturn()

    private fun created(body: String): Map<String, Any?> {
        val result = create(body)
        assertEquals(201, result.response.status, result.response.errorMessage ?: result.response.contentAsString)
        return json.readValue(result.response.contentAsString, Map::class.java).mapKeys { it.key as String }
    }

    @BeforeEach
    fun reset() {
        runtime.createProcessInstanceQuery().processDefinitionKey(JobStarter.PROCESS_KEY).list().forEach { runtime.deleteProcessInstance(it.id, "test reset") }
        listOf(repo, other).forEach { repos.remove(it) }
        repos.add(repo); repos.add(other)
        access.add(login, "test")
        sandbox.reset()
        sandbox.callers[smithAddress] = SmithCaller("run-9", 9, repo)
        jars = PluginJarFixture.directory(); pluginJars.closeAll(); pluginJars.useDirectory(jars)
        dropPluginDeployments()
    }
    @AfterEach fun dropJars() { pluginJars.closeAll(); PluginJarFixture.delete(jars); dropPluginDeployments() }

    /** The tests share one database: a flow job's deployment left by one would make the next registration's key "already deployed". */
    private fun dropPluginDeployments() =
        repository.createDeploymentQuery().deploymentSource(PluginFlows.SOURCE).list().forEach { repository.deleteDeployment(it.id, true) }

    private fun flowJob(jar: String = "fixture.jar", process: String = PluginJarFixture.FLOW, name: String = "nightly") =
        """{"name":"$name","kind":"flow","repo":"$repo","config":{"jar":"$jar","process":"$process"},"signal":{"kind":"execute"}}"""

    private fun pluginDeployments() = repository.createDeploymentQuery().deploymentSource(PluginFlows.SOURCE).list()

    @Test
    fun `POST registers a job and GET lists it, by repo too, and reads it by id`() {
        val job = created(promptJob(signal = """{"kind":"every","every":60}"""))
        val id = job["id"] as String
        assertEquals("prompt", job["kind"]); assertEquals(repo, job["repo"]); assertEquals(login, job["createdBy"])
        assertEquals("user", job["owner"], "a registered job is the user's")
        assertEquals(mapOf("prompt" to "Count the files under docs/."), job["config"])
        assertEquals(mapOf("kind" to "every", "every" to 60), job["signal"])
        assertNotNull(job["nextRunAt"], "an every job is due from its registration on"); assertNull(job["lastRunAt"]); assertNull(job["lastInstance"])
        created(promptJob(name = "elsewhere", target = other).also { access.add(login, "test") })

        mvc.get("/jobs/$id").andExpect { status { isOk() }; jsonPath("$.name") { value("nightly docs count") }; jsonPath("$.owner") { value("user") } }
        mvc.get("/jobs/nope").andExpect { status { isNotFound() } }
        mvc.get("/jobs/nope/runs").andExpect { status { isNotFound() } }
        mvc.get("/jobs/$id/runs").andExpect { status { isOk() }; jsonPath("$.length()") { value(0) } }
        mvc.get("/jobs/$id/runs?limit=0").andExpect { status { isBadRequest() } }
        val all = json.readValue(mvc.get("/jobs").andReturn().response.contentAsString, List::class.java).map { (it as Map<*, *>)["id"] }
        assertTrue(id in all, all.toString())
        val mine = json.readValue(mvc.get("/jobs?repo=$repo").andReturn().response.contentAsString, List::class.java).map { (it as Map<*, *>)["repo"] }
        assertEquals(setOf(repo), mine.toSet())
        val theirs = json.readValue(mvc.get("/jobs?repo=$other").andReturn().response.contentAsString, List::class.java).map { (it as Map<*, *>)["name"] }
        assertEquals(listOf("elsewhere"), theirs)

        val stored = store.get(id)!!
        assertEquals(Signal.Every(60), stored.parsedSignal())
        assertNull(stored.lastInstance)
    }

    @Test
    fun `an execute job has no next run, and bean and flow jobs are stored with their config`() {
        val execute = created(promptJob())
        assertNull(execute["nextRunAt"])
        PluginJarFixture.write(jars, diagrams = listOf(PluginJarFixture.FLOW))
        val bean = created(beanJob())
        assertEquals(mapOf("jar" to "fixture.jar", "bean" to "recorder"), bean["config"])
        assertEquals(listOf("fixture.jar"), pluginJars.loaded(), "the jar is loaded at registration")
        assertEquals(setOf(bean["id"]), pluginJars.entryOf(bean["id"] as String)!!.jobIds)
        val flow = created(flowJob())
        assertEquals("flow", flow["kind"])
        assertEquals(mapOf("jar" to "fixture.jar", "process" to PluginJarFixture.FLOW), flow["config"])
        assertEquals(setOf(bean["id"], flow["id"]), pluginJars.entryOf(flow["id"] as String)!!.jobIds, "the flow job shares the bean job's loaded jar")
    }

    @Test
    fun `an at job is due at its instant, and a past or malformed instant is 400 naming the reason`() {
        val at = java.time.Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val job = created(promptJob(name = "later", signal = """{"kind":"at","at":"$at"}"""))
        assertEquals("prompt", job["kind"]); assertEquals(repo, job["repo"])
        assertEquals(mapOf("kind" to "at", "at" to at.toString()), job["signal"])
        assertEquals(at, java.time.OffsetDateTime.parse(job["nextRunAt"] as String).toInstant(), "due at its instant")
        assertEquals(Signal.At(at), store.get(job["id"] as String)!!.parsedSignal())

        fun refused(signal: String): String {
            val result = create(promptJob(name = "too late", signal = signal))
            assertEquals(400, result.response.status, signal)
            return result.response.errorMessage ?: ""
        }
        val past = java.time.Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        assertEquals("at $past is in the past", refused("""{"kind":"at","at":"$past"}"""))
        assertContains(refused("""{"kind":"at","at":"tomorrow at nine"}"""), "not an ISO-8601 instant")
        assertContains(refused("""{"kind":"at"}"""), "ISO-8601 instant string")
        assertTrue(store.list(repo).none { it.name == "too late" }, "nothing stored")
    }

    @Test
    fun `a flow job deploys its jar's diagrams as job colon id from source plugin, and its delete undeploys them and releases the jar`() {
        PluginJarFixture.write(jars, diagrams = listOf(PluginJarFixture.FLOW))
        val id = created(flowJob())["id"] as String
        val deployment = pluginDeployments().single()
        assertEquals(PluginFlows.deploymentName(id), deployment.name)
        assertEquals(PluginFlows.SOURCE, deployment.source)
        val definition = repository.createProcessDefinitionQuery().deploymentId(deployment.id).singleResult()
        assertEquals(PluginJarFixture.FLOW, definition.key)
        assertEquals(listOf("fixture.jar"), pluginJars.loaded())
        assertNotNull(store.get(id))

        mvc.delete("/jobs/$id") { with(TestTokens.bearer(login)) }.andExpect { status { isNoContent() } }
        assertEquals(emptyList(), pluginDeployments(), "undeployed")
        assertEquals(0L, repository.createProcessDefinitionQuery().processDefinitionKey(PluginJarFixture.FLOW).count())
        assertEquals(emptyList(), pluginJars.loaded(), "the last job's delete closes the jar")
        assertNull(store.get(id))
        // The key is free again: a new flow job on the same jar is accepted.
        val again = created(flowJob())["id"] as String
        assertEquals(PluginFlows.deploymentName(again), pluginDeployments().single().name)
    }

    @Test
    fun `a flow job is 400 listing every problem for an unbound delegate, a crossing flow, an engine bean, a missing process or a deployed key, and nothing is kept`() {
        fun refused(body: String, vararg expected: String): String {
            val result = create(body)
            assertEquals(400, result.response.status, result.response.errorMessage ?: body)
            val reason = result.response.errorMessage ?: ""
            expected.forEach { assertContains(reason, it, message = "in: $reason") }
            assertEquals(emptyList(), store.list(repo), "no row for a refused job: $reason")
            assertEquals(emptyList(), pluginJars.loaded(), "nothing loaded after: $reason")
            assertEquals(emptyList(), pluginDeployments(), "nothing deployed after: $reason")
            return reason
        }
        PluginJarFixture.write(jars, "unbound.jar", diagrams = listOf("unbound-flow"))
        refused(flowJob(jar = "unbound.jar", process = "unbound-flow"), "bpmn/unbound-flow.bpmn: task-nobody: delegateExpression \"\${nobody}\" names nobody, which is no bean of the jar")
        PluginJarFixture.write(jars, "crossing.jar", diagrams = listOf("crossing-flow"))
        refused(flowJob(jar = "crossing.jar", process = "crossing-flow"), "bpmn/crossing-flow.bpmn: flow-crossing-a-to-end x flow-crossing-b-to-end")
        PluginJarFixture.write(jars, "clash.jar", diagrams = listOf("clash-flow"))
        refused(flowJob(jar = "clash.jar", process = "clash-flow"), "names noteJobEnded, an engine bean")
        // Every problem of every diagram is listed, one per line.
        PluginJarFixture.write(jars, "both.jar", diagrams = listOf("unbound-flow", "crossing-flow"))
        val both = refused(flowJob(jar = "both.jar", process = "unbound-flow"), "unbound-flow.bpmn: task-nobody", "crossing-flow.bpmn: flow-crossing-a-to-end")
        assertTrue(both.lines().size >= 2, both)
        PluginJarFixture.write(jars, diagrams = listOf(PluginJarFixture.FLOW))
        refused(flowJob(process = "nightly-flow"), "process nightly-flow is not an executable process of fixture.jar's diagrams (fixture-flow)")
        PluginJarFixture.write(jars, "empty.jar")
        refused(flowJob(jar = "empty.jar"), "jar empty.jar has no bpmn/*.bpmn diagram")
        refused(flowJob(jar = "missing.jar"), "jar missing.jar is not in /var/lib/frakworks/jobs")
        assertEquals(PluginJars.NAME_REASON, refused(flowJob(jar = "../fixture.jar")))
        // A key another deployment defines is refused, naming the holder.
        val first = created(flowJob())["id"] as String
        val second = create(flowJob(jar = "other.jar", name = "nightly too").also { PluginJarFixture.write(jars, "other.jar", diagrams = listOf(PluginJarFixture.FLOW)) })
        assertEquals(400, second.response.status)
        assertContains(second.response.errorMessage!!, "process fixture-flow is already deployed by ${PluginFlows.deploymentName(first)}")
        assertEquals(listOf(first), store.list(repo).map { it.id })
        assertEquals(listOf("fixture.jar"), pluginJars.loaded(), "the refused job's jar is released")
    }

    @Test
    fun `two bean jobs share their jar, deleting one keeps it loaded and deleting the last closes it`() {
        PluginJarFixture.write(jars)
        val first = created(beanJob())["id"] as String
        val second = created(beanJob(bean = "thrower", name = "sweep too"))["id"] as String
        assertEquals(listOf("fixture.jar"), pluginJars.loaded())
        assertEquals(setOf(first, second), pluginJars.entryOf(first)!!.jobIds)
        mvc.delete("/jobs/$first") { with(TestTokens.bearer(login)) }.andExpect { status { isNoContent() } }
        assertEquals(listOf("fixture.jar"), pluginJars.loaded(), "the second job still holds it")
        mvc.delete("/jobs/$second") { with(TestTokens.bearer(login)) }.andExpect { status { isNoContent() } }
        assertEquals(emptyList(), pluginJars.loaded(), "the last job's delete closes it")
    }

    @Test
    fun `a bean job is 400 with the reason for a missing jar, no manifest attribute, a missing or non-FrakJob bean, or a path as the jar, and nothing is kept`() {
        fun refused(body: String): String {
            val result = create(body)
            assertEquals(400, result.response.status, result.response.errorMessage ?: body)
            val listed = json.readValue(mvc.get("/jobs?repo=$repo").andReturn().response.contentAsString, List::class.java)
            assertEquals(emptyList<Any>(), listed, "no row for a refused job")
            assertEquals(emptyList(), pluginJars.loaded(), "nothing loaded after: ${result.response.errorMessage}")
            return result.response.errorMessage ?: ""
        }
        assertEquals("jar missing.jar is not in /var/lib/frakworks/jobs", refused(beanJob(jar = "missing.jar")))
        PluginJarFixture.write(jars, "bare.jar", pkg = null)
        assertEquals("jar bare.jar has no Frak-Plugin-Package manifest attribute", refused(beanJob(jar = "bare.jar")))
        PluginJarFixture.write(jars)
        assertEquals("jar fixture.jar has no bean nope", refused(beanJob(bean = "nope")))
        assertEquals("bean plain of fixture.jar is a com.codefrak.fixtureplugin.NotAJob, not a FrakJob", refused(beanJob(bean = "plain")))
        assertEquals(PluginJars.NAME_REASON, refused(beanJob(jar = "../fixture.jar")))
        assertEquals(PluginJars.NAME_REASON, refused(beanJob(jar = "sub/fixture.jar")))
        assertEquals(PluginJars.NAME_REASON, refused(beanJob(jar = "..")))
        assertEquals(emptyList(), store.list(repo), "nothing stored")
    }

    @Test
    fun `POST is 400 with the reason for an unknown kind, a missing or foreign repo, config the kind does not take, and a bad signal`() {
        fun refused(body: String): String {
            val result = create(body)
            assertEquals(400, result.response.status, body)
            return result.response.errorMessage ?: ""
        }
        assertContains(refused("""{"name":"x","kind":"cron","repo":"$repo","config":{},"signal":{"kind":"execute"}}"""), "unknown job kind 'cron'")
        assertContains(refused("""{"name":"x","kind":"prompt","config":{"prompt":"p"},"signal":{"kind":"execute"}}"""), "no default repo")
        val foreign = refused("""{"name":"x","kind":"prompt","repo":"acme/not-a-target","config":{"prompt":"p"},"signal":{"kind":"execute"}}""")
        assertContains(foreign, "acme/not-a-target is not a target repo"); assertContains(foreign, repo)
        assertContains(refused("""{"name":"x","kind":"prompt","repo":"$repo","config":{"prompt":"p","jar":"a.jar"},"signal":{"kind":"execute"}}"""), "not jar")
        assertContains(refused("""{"name":"x","kind":"bean","repo":"$repo","config":{"jar":"a.jar"},"signal":{"kind":"execute"}}"""), "needs bean")
        assertContains(refused("""{"name":"x","kind":"prompt","repo":"$repo","config":{"prompt":"  "},"signal":{"kind":"execute"}}"""), "config.prompt")
        assertContains(refused("""{"name":"x","kind":"prompt","repo":"$repo","config":{"prompt":"p"},"signal":{"kind":"cron"}}"""), "unknown signal kind 'cron'")
        assertContains(refused("""{"name":"x","kind":"prompt","repo":"$repo","config":{"prompt":"p"},"signal":{"kind":"every","every":0}}"""), "at least 1")
        assertContains(refused("""{"name":"","kind":"prompt","repo":"$repo","config":{"prompt":"p"},"signal":{"kind":"execute"}}"""), "name")
        assertContains(refused("""{"name":"x","kind":"prompt","repo":"$repo","config":{"prompt":"p"},"signal":{"kind":"execute"},"cron":"x"}"""), "not cron")
        assertEquals(emptyList(), store.list(repo), "nothing stored")
    }

    @Test
    fun `a write from any token's login is taken, and one with no token is 401`() {
        val noLogin = create(promptJob(), who = null)
        assertEquals(401, noLogin.response.status)
        assertContains(noLogin.response.contentAsString, "no such token")
        assertEquals(emptyList(), store.list(repo))
        mvc.post("/jobs/${created(promptJob())["id"]}/execute") { with(TestTokens.noToken) }.andExpect { status { isUnauthorized() } }

        assertEquals(201, create(promptJob(name = "bobs"), who = "bob").response.status)
        val id = store.list(repo).single { it.name == "bobs" }.id
        mvc.delete("/jobs/$id") { with(TestTokens.bearer("bob")) }.andExpect { status { isNoContent() } }
    }

    @Test
    fun `a Smith sandbox may not register, delete or execute a job, even with a granted login, and Docker not answering is 503`() {
        val smith = create(promptJob(), from = smithAddress)
        assertEquals(403, smith.response.status)
        assertContains(smith.response.errorMessage!!, "a Smith sandbox (run run-9) may not register a job")
        assertEquals(emptyList(), store.list(repo))

        val id = created(promptJob())["id"] as String
        mvc.delete("/jobs/$id") { asJson("", from = smithAddress) }.andExpect { status { isForbidden() } }
        mvc.post("/jobs/$id/execute") { asJson("", from = smithAddress) }.andExpect { status { isForbidden() } }
        assertNotNull(store.get(id)); assertNull(store.get(id)!!.lastInstance)

        sandbox.runAtFails = true
        assertEquals(503, create(promptJob(), from = "10.0.0.5").response.status)
        mvc.post("/jobs/$id/execute") { asJson("", from = "10.0.0.5") }.andExpect { status { isServiceUnavailable() } }
        sandbox.runAtFails = false
        // Reads are open to a Smith.
        mvc.get("/jobs/$id") { with(RequestPostProcessor { it.remoteAddr = smithAddress; it }) }.andExpect { status { isOk() } }
    }

    @Test
    fun `execute starts one job instance and answers it, and a second execute or a delete while it runs is 409`() {
        val id = created(promptJob())["id"] as String
        val started = mvc.post("/jobs/$id/execute") { with(TestTokens.bearer(login)) }.andReturn()
        assertEquals(200, started.response.status, started.response.errorMessage ?: "")
        val instance = json.readValue(started.response.contentAsString, Map::class.java)["instance"] as String
        val running = runtime.createProcessInstanceQuery().processInstanceId(instance).singleResult()
        assertNotNull(running, "the job instance runs (its first step is async and no executor runs)")
        assertEquals(1L, runtime.createProcessInstanceQuery().processInstanceId(instance).processDefinitionKey(JobStarter.PROCESS_KEY).count(), "a job instance")
        assertEquals(id, runtime.getVariable(instance, "jobId"))
        assertEquals(repo, runtime.getVariable(instance, "repo"))
        assertEquals("prompt", runtime.getVariable(instance, "kind"))
        assertEquals("Count the files under docs/.", runtime.getVariable(instance, "prompt"))
        assertEquals("""{"prompt":"Count the files under docs/."}""", runtime.getVariable(instance, "config"))

        val row = store.get(id)!!
        assertEquals(instance, row.lastInstance)
        assertNotNull(row.lastRunAt)
        assertNull(row.nextRunAt, "an execute job has no clock")

        val again = mvc.post("/jobs/$id/execute") { with(TestTokens.bearer(login)) }.andReturn()
        assertEquals(409, again.response.status)
        assertEquals("job $id is running in $instance", again.response.errorMessage)
        val delete = mvc.delete("/jobs/$id") { with(TestTokens.bearer(login)) }.andReturn()
        assertEquals(409, delete.response.status)
        assertEquals("job $id is running in $instance", delete.response.errorMessage)
        assertNotNull(store.get(id))

        runtime.deleteProcessInstance(instance, "test done")
        mvc.delete("/jobs/$id") { with(TestTokens.bearer(login)) }.andExpect { status { isNoContent() } }
        mvc.delete("/jobs/$id") { with(TestTokens.bearer(login)) }.andExpect { status { isNotFound() } }
        mvc.post("/jobs/$id/execute") { with(TestTokens.bearer(login)) }.andExpect { status { isNotFound() } }
        assertNull(store.get(id))
    }

    @Test
    fun `kind refactor is 400 naming the refactor endpoint, and a system job cannot be deleted but can be executed`() {
        val refused = create("""{"name":"r","kind":"refactor","repo":"$repo","config":{},"signal":{"kind":"execute"}}""")
        assertEquals(400, refused.response.status); assertEquals(JobRegistrar.REFACTOR_IS_SYSTEM, refused.response.errorMessage)
        assertEquals(emptyList(), store.list(repo))

        val enabled = mvc.put("/repos/$repo/refactor") { asJson("""{"enabled":true}""") }.andReturn()
        assertEquals(200, enabled.response.status, enabled.response.errorMessage ?: "")
        val id = json.readValue(enabled.response.contentAsString, Map::class.java)["jobId"] as String
        val row = json.readValue(mvc.get("/jobs/$id").andReturn().response.contentAsString, Map::class.java)
        assertEquals("system", row["owner"]); assertEquals("refactor", row["kind"]); assertEquals("refactor $repo", row["name"])
        assertEquals(mapOf("kind" to "every", "every" to 604800), row["signal"])

        val delete = mvc.delete("/jobs/$id") { with(TestTokens.bearer(login)) }.andReturn()
        assertEquals(409, delete.response.status)
        assertEquals("job $id is system-owned (refactor on $repo): disable refactoring with PUT /repos/$repo/refactor", delete.response.errorMessage)
        assertNotNull(store.get(id), "the row stays")

        mvc.post("/jobs/$id/execute") { asJson("", from = smithAddress) }.andExpect { status { isForbidden() } }
        val started = mvc.post("/jobs/$id/execute") { with(TestTokens.bearer(login)) }.andReturn()
        assertEquals(200, started.response.status, started.response.errorMessage ?: "")
        val instance = json.readValue(started.response.contentAsString, Map::class.java)["instance"] as String
        assertEquals(1L, runtime.createProcessInstanceQuery().processInstanceId(instance).processDefinitionKey(JobStarter.PROCESS_KEY).count())
        runtime.deleteProcessInstance(instance, "test done")
        mvc.put("/repos/$repo/refactor") { asJson("""{"enabled":false}""") }.andExpect { status { isOk() } }
        assertNull(store.get(id), "disabling deletes the system job")
    }

    @Test
    fun `a removed target repo takes its jobs with it`() {
        val id = created(promptJob())["id"] as String
        repos.remove(repo)
        assertNull(store.get(id))
        assertEquals(0L, jdbc.sql("SELECT COUNT(*) FROM engine.job WHERE repo = :r").param("r", repo).query(Long::class.java).single())
    }
}

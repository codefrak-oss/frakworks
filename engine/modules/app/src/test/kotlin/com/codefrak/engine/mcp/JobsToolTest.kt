package com.codefrak.engine.mcp

import com.codefrak.engine.TestTokens
import com.codefrak.engine.ProcessFakes
import com.codefrak.engine.chore.ScriptedSandbox
import com.codefrak.engine.jobs.JobKind
import com.codefrak.engine.jobs.JobOwner
import com.codefrak.engine.jobs.JobRegistrar
import com.codefrak.engine.jobs.JobRuns
import com.codefrak.engine.jobs.JobStarter
import com.codefrak.engine.jobs.Signal
import com.codefrak.engine.jobs.JobStore
import com.codefrak.engine.jobs.PluginFlows
import com.codefrak.engine.jobs.PluginJarFixture
import com.codefrak.engine.jobs.PluginJars
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
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The job tools on frakworks-engine-diagnostics ([JobsTool]) over the server's HTTP endpoint: the `/jobs` API's answers
 * and refusals as tool results. The sandbox is scripted, as in `JobControllerTest`: `callers` says which address is a Smith.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ProcessFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class JobsToolTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var access: Access
    @Autowired lateinit var store: JobStore
    @Autowired lateinit var runs: JobRuns
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var repository: RepositoryService
    @Autowired lateinit var sandbox: ScriptedSandbox
    @Autowired lateinit var pluginJars: PluginJars

    private val json = jacksonObjectMapper()
    private val path = "/mcp/${DiagnosticsMcpServer.NAME}"
    private val repo = "acme/jobtool"
    private val login = "alice"
    private val smith = "172.18.0.19"
    private val local = "127.0.0.1"
    private lateinit var jars: java.nio.file.Path

    @BeforeEach
    fun reset() {
        runtime.createProcessInstanceQuery().processDefinitionKey(JobStarter.PROCESS_KEY).list().forEach { runtime.deleteProcessInstance(it.id, "test reset") }
        repos.remove(repo); repos.add(repo)
        access.add(login, "test")
        sandbox.reset()
        sandbox.callers[smith] = SmithCaller("run-19", 19, repo)
        jars = PluginJarFixture.directory(); pluginJars.closeAll(); pluginJars.useDirectory(jars)
        dropPluginDeployments()
    }
    @AfterEach fun dropJars() { pluginJars.closeAll(); PluginJarFixture.delete(jars); dropPluginDeployments(); repos.remove(repo) }

    private fun dropPluginDeployments() =
        repository.createDeploymentQuery().deploymentSource(PluginFlows.SOURCE).list().forEach { repository.deleteDeployment(it.id, true) }

    /** One tools/call: whether it is an error, and its JSON. */
    private fun call(tool: String, args: Map<String, Any?>, from: String = local, who: String? = login): Pair<Boolean, Any?> {
        val res = mvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            header("Accept", "application/json, text/event-stream")
            with(who?.let { TestTokens.bearer(it) } ?: TestTokens.noToken)
            content = json.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to "tools/call",
                "params" to mapOf("name" to tool, "arguments" to args)))
            with(RequestPostProcessor { it.remoteAddr = from; it })
        }.andExpect { status { isOk() } }.andReturn()
        val reply = json.readValue<Map<String, Any?>>(res.response.contentAsString)
        // A JSON-RPC error is the bearer filter's refusal (-32001), before the tool runs.
        (reply["error"] as? Map<*, *>)?.let { return true to mapOf("error" to it["message"]) }
        val result = reply["result"] as Map<*, *>
        val text = ((result["content"] as List<*>).single() as Map<*, *>)["text"] as String
        return (result["isError"] == true) to json.readValue<Any?>(text)
    }

    private fun ok(tool: String, args: Map<String, Any?>): Map<*, *> {
        val (error, body) = call(tool, args)
        assertEquals(false, error, body.toString())
        return body as Map<*, *>
    }

    private fun error(tool: String, args: Map<String, Any?>, from: String = local, who: String? = login): String {
        val (error, body) = call(tool, args, from, who)
        assertTrue(error, body.toString())
        return (body as Map<*, *>)["error"] as String
    }

    private fun prompt(name: String = "docs count") = mapOf("name" to name, "kind" to "prompt", "repo" to repo,
        "config" to mapOf("prompt" to "Count the files under docs/."), "signal" to mapOf("kind" to "execute"))
    private fun bean(jar: String = "fixture.jar", bean: String = "recorder") = mapOf("name" to "sweep", "kind" to "bean", "repo" to repo,
        "config" to mapOf("jar" to jar, "bean" to bean), "signal" to mapOf("kind" to "execute"))
    private fun flow() = mapOf("name" to "nightly", "kind" to "flow", "repo" to repo,
        "config" to mapOf("jar" to "fixture.jar", "process" to PluginJarFixture.FLOW), "signal" to mapOf("kind" to "execute"))

    @Test
    fun `each tool has a closed schema and refuses an argument it does not take or of the wrong type`() {
        val res = mvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            header("Accept", "application/json, text/event-stream")
            content = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""
        }.andReturn()
        val tools = ((json.readValue<Map<String, Any?>>(res.response.contentAsString)["result"] as Map<*, *>)["tools"] as List<*>).map { it as Map<*, *> }
        for (name in JobsTool.NAMES) {
            val tool = tools.single { it["name"] == name }
            assertTrue((tool["description"] as String).isNotBlank())
            assertEquals(false, (tool["inputSchema"] as Map<*, *>)["additionalProperties"], name)
        }
        assertEquals("list_jobs takes only \"repo\", not \"x\"", error("list_jobs", mapOf("x" to 1)))
        assertTrue("not 7" in error("read_job", mapOf("id" to 7)))
        assertTrue("not \"x\"" in error("run_job", mapOf("id" to "j", "x" to 1)))
        assertTrue("\"config\" is an object" in error("register_job", prompt() + ("config" to "text")))
        assertEquals(emptyList(), store.list(repo))
    }

    @Test
    fun `a prompt job is registered, listed, read, run and deleted through the tools by a granted login`() {
        val job = ok("register_job", prompt())
        val id = job["id"] as String
        assertEquals("prompt", job["kind"]); assertEquals(repo, job["repo"]); assertEquals(login, job["createdBy"]); assertEquals("user", job["owner"])
        assertEquals(listOf(id to "user"), (call("list_jobs", mapOf("repo" to repo)).second as List<*>).map { (it as Map<*, *>)["id"] to it["owner"] })
        assertTrue(id in (call("list_jobs", emptyMap()).second as List<*>).map { (it as Map<*, *>)["id"] })
        assertEquals(id, ok("read_job", mapOf("id" to id))["id"])
        // Reads answer a Smith too.
        val (smithError, smithRead) = call("read_job", mapOf("id" to id), from = smith, who = null)
        assertEquals(false, smithError); assertEquals(id, (smithRead as Map<*, *>)["id"])

        val instance = ok("run_job", mapOf("id" to id))["instance"] as String
        assertEquals(instance, store.get(id)!!.lastInstance)
        assertEquals("job $id is running in $instance", error("run_job", mapOf("id" to id)))
        assertEquals("job $id is running in $instance", error("delete_job", mapOf("id" to id)))
        assertNotNull(store.get(id))
        runtime.deleteProcessInstance(instance, "test done")

        assertEquals(mapOf("deleted" to id), ok("delete_job", mapOf("id" to id)))
        assertNull(store.get(id))
        assertEquals("no job $id", error("read_job", mapOf("id" to id)))
        assertEquals("no job $id", error("run_job", mapOf("id" to id)))
        assertEquals("no job $id", error("delete_job", mapOf("id" to id)))
    }

    @Test
    fun `read_job_runs answers a job's runs newest first, with a refactor run's scan, and refuses a bad limit`() {
        val id = store.insert("refactor $repo", JobKind.REFACTOR, repo, json.readTree("{}"), Signal.Every(3600), login, owner = JobOwner.SYSTEM).id
        val t = java.time.Instant.parse("2026-10-07T10:00:00Z")
        runs.started("runs-a-$id", id, repo, "refactor", t)
        runs.scanFiled("runs-a-$id", 900)
        runs.ended("runs-a-$id", "done", "filed refactor-scan #900", t.plusSeconds(1))
        runs.scanResult(repo, 900, listOf(901L), mapOf("open" to 2), foundNothing = false)
        runs.started("runs-b-$id", id, repo, "refactor", t.plusSeconds(3600))
        runs.ended("runs-b-$id", "done", "filed nothing: refactor-scan #900 is still open", t.plusSeconds(3601))

        val (error, body) = call("read_job_runs", mapOf("id" to id), from = smith, who = null)
        assertEquals(false, error, body.toString())
        val list = (body as List<*>).map { it as Map<*, *> }
        assertEquals(listOf("runs-b-$id", "runs-a-$id"), list.map { it["instance"] })
        assertEquals("nothing", list[0]["status"]); assertEquals("filed nothing: refactor-scan #900 is still open", list[0]["summary"])
        assertEquals("done", list[1]["status"]); assertEquals(900, list[1]["scanIssue"]); assertEquals(listOf(901), list[1]["filed"])
        assertEquals(mapOf("open" to 2), list[1]["skipped"]); assertEquals(false, list[1]["foundNothing"])
        assertEquals(1, (call("read_job_runs", mapOf("id" to id, "limit" to 1)).second as List<*>).size)
        assertTrue("limit is 1 to 200" in error("read_job_runs", mapOf("id" to id, "limit" to 0)))
        assertTrue("\"limit\" is a whole number" in error("read_job_runs", mapOf("id" to id, "limit" to "x")))
        assertEquals("no job nope", error("read_job_runs", mapOf("id" to "nope")))
        store.delete(id)
    }

    @Test
    fun `a system refactor job is listed with its owner, refused to register_job, and delete_job answers the 409 text`() {
        assertEquals(JobRegistrar.REFACTOR_IS_SYSTEM, error("register_job", mapOf("name" to "r", "kind" to "refactor", "repo" to repo,
            "config" to emptyMap<String, Any>(), "signal" to mapOf("kind" to "execute"))))
        val id = store.insert("refactor $repo", JobKind.REFACTOR, repo, json.readTree("{}"), Signal.Every(604800), login, owner = JobOwner.SYSTEM).id
        assertEquals("system", ok("read_job", mapOf("id" to id))["owner"])
        assertEquals("job $id is system-owned (refactor on $repo): disable refactoring with PUT /repos/$repo/refactor", error("delete_job", mapOf("id" to id)))
        assertNotNull(store.get(id))
        store.delete(id)
    }

    @Test
    fun `a bean job and a flow job are registered from a jar and deleted, and a bad jar is the endpoint's 400 text`() {
        PluginJarFixture.write(jars, diagrams = listOf(PluginJarFixture.FLOW))
        val beanId = ok("register_job", bean())["id"] as String
        val flowId = ok("register_job", flow())["id"] as String
        assertEquals(PluginFlows.deploymentName(flowId), repository.createDeploymentQuery().deploymentSource(PluginFlows.SOURCE).singleResult().name)
        ok("delete_job", mapOf("id" to flowId))
        assertEquals(0L, repository.createDeploymentQuery().deploymentSource(PluginFlows.SOURCE).count(), "undeployed")
        ok("delete_job", mapOf("id" to beanId))
        assertEquals(emptyList(), pluginJars.loaded(), "released")

        assertEquals("jar missing.jar is not in /var/lib/frakworks/jobs", error("register_job", bean(jar = "missing.jar")))
        assertEquals(PluginJars.NAME_REASON, error("register_job", bean(jar = "../fixture.jar")))
        assertEquals("jar fixture.jar has no bean nope", error("register_job", bean(bean = "nope")))
        assertTrue("is not a target repo" in error("register_job", prompt() + ("repo" to "acme/elsewhere")))
        assertEquals(emptyList(), store.list(repo))
    }

    @Test
    fun `every write is refused to a Smith, an unplaced caller, and no login, and nothing changes`() {
        val id = ok("register_job", prompt("kept"))["id"] as String
        fun unchanged() {
            assertEquals(listOf(id), store.list(repo).map { it.id })
            assertNull(store.get(id)!!.lastInstance, "not started")
        }
        val writes = listOf("register_job" to prompt() to "register a job", "run_job" to mapOf("id" to id) to "execute a job",
            "delete_job" to mapOf("id" to id) to "delete a job")
        for ((call, what) in writes) {
            val (tool, args) = call
            assertEquals("a Smith sandbox (run run-19) may not $what", error(tool, args, from = smith))
            unchanged()
            sandbox.runAtFails = true
            try { assertTrue(error(tool, args, from = "10.0.0.5").startsWith("cannot tell whether the caller is a Smith sandbox")) }
            finally { sandbox.runAtFails = false }
            unchanged()
        }
    }
}

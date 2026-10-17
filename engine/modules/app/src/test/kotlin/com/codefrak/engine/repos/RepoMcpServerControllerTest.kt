package com.codefrak.engine.repos

import com.codefrak.engine.TestTokens
import com.codefrak.engine.ProcessFakes
import com.codefrak.engine.accounts.SecretsKey
import com.codefrak.engine.chore.ScriptedSandbox
import com.codefrak.engine.mcp.EngineMcpController
import com.codefrak.engine.smith.SmithCaller
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.web.server.ResponseStatusException
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** First mate's per-repo MCP servers under `/repos/{owner}/{name}/mcp-servers` (#1801; engine/README.md, "First mate's MCP servers"). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ProcessFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class RepoMcpServerControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var sandbox: ScriptedSandbox
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var store: RepoMcpServers

    private val json = jacksonObjectMapper()
    private val repo = "acme/mcpcfg"
    private val smithAddress = "172.18.0.8"

    @BeforeEach fun setUp() {
        repos.remove(repo); repos.add(repo)
        sandbox.reset(); sandbox.callers[smithAddress] = SmithCaller("run-8", 8, repo)
    }
    @AfterEach fun tearDown() { repos.remove(repo) }

    private fun put(server: String, body: String, from: String? = null, path: String = repo): MvcResult = mvc.put("/repos/$path/mcp-servers/$server") {
        contentType = MediaType.APPLICATION_JSON; content = body; with(TestTokens.bearer("alice"))
        if (from != null) with(RequestPostProcessor { it.remoteAddr = from; it })
    }.andReturn()

    private fun list(): List<Map<String, Any?>> = json.readValue(mvc.get("/repos/$repo/mcp-servers").andExpect { status { isOk() } }.andReturn().response.contentAsString)

    @Test
    fun `add, list, edit and remove a server, and a repo that is not a target is 404`() {
        assertEquals(emptyList(), list())
        val added = put("docs", """{"kind":"http","audience":"first-mate","url":"https://mcp.example.com/mcp","headers":{"X-Team":"core"}}""")
        assertEquals(201, added.response.status, added.response.errorMessage)
        val row = list().single()
        assertEquals("docs", row["name"]); assertEquals("http", row["kind"]); assertEquals("https://mcp.example.com/mcp", row["url"])
        assertEquals("first-mate", row["audience"])
        assertEquals(mapOf("X-Team" to mapOf("secret" to false, "value" to "core")), row["headers"]); assertEquals("alice", row["updatedBy"])

        val edited = put("docs", """{"kind":"stdio","audience":"both","command":"npx","args":["-y","docs-mcp"],"env":{"MODE":"ro"}}""")
        assertEquals(200, edited.response.status, edited.response.errorMessage)
        val now = list().single()
        assertEquals("stdio", now["kind"]); assertEquals(listOf("-y", "docs-mcp"), now["args"]); assertEquals(null, now["url"])
        assertEquals("both", now["audience"], "an edit changes the audience")

        mvc.delete("/repos/$repo/mcp-servers/docs").andExpect { status { isNoContent() } }
        mvc.delete("/repos/$repo/mcp-servers/docs").andExpect { status { isNotFound() } }
        assertEquals(emptyList(), list())

        mvc.get("/repos/acme/nowhere/mcp-servers").andExpect { status { isNotFound() } }
        assertEquals(404, put("docs", """{"kind":"http","audience":"first-mate","url":"https://x.example.com"}""", path = "acme/nowhere").response.status)
        mvc.delete("/repos/acme/nowhere/mcp-servers/docs").andExpect { status { isNotFound() } }
    }

    @Test
    fun `an unknown kind or a missing required field is 400 naming the field`() {
        val cases = listOf(
            """{"url":"https://x.example.com"}""" to "kind is required: http, sse or stdio",
            """{"kind":"ws","url":"https://x.example.com"}""" to "kind is http, sse or stdio, not 'ws'",
            """{"kind":"http","url":"https://x.example.com"}""" to "audience is required: smith, first-mate or both (who gets the server)",
            """{"kind":"http","audience":"laptop","url":"https://x.example.com"}""" to "audience is smith, first-mate or both, not 'laptop'",
            """{"kind":"http","audience":"first-mate"}""" to "url is required for an http server: an http or https URL",
            """{"kind":"sse","audience":"first-mate","url":"ftp://x"}""" to "url is required for an sse server: an http or https URL",
            """{"kind":"stdio","audience":"first-mate"}""" to "command is required for a stdio server: the program to run",
            """{"kind":"stdio","audience":"first-mate","command":"x","args":[1]}""" to "args is a list of strings",
            """{"kind":"stdio","audience":"first-mate","command":"x","url":"https://x.example.com"}""" to "url is for an http or sse server, not a stdio one",
            """{"kind":"http","audience":"first-mate","url":"https://x.example.com","colour":"blue"}""" to "unknown field(s): colour; only kind, audience, role, url, headers, command, args, env",
        )
        for ((body, reason) in cases) {
            val result = put("one", body)
            assertEquals(400, result.response.status, body)
            assertEquals(reason, result.response.errorMessage, body)
        }
        for (reserved in listOf("frakworks", "frakworks-engine-diagnostics"))
            assertEquals(400, put(reserved, """{"kind":"http","audience":"smith","url":"https://x.example.com"}""").response.status, reserved)
        assertEquals(400, put("bad name", """{"kind":"http","audience":"first-mate","url":"https://x.example.com"}""").response.status)
        assertEquals(emptyList(), list())
    }

    @Test
    fun `a secret is sealed, a read shows only that it is set and since when, and the config opens it`() {
        put("docs", """{"kind":"http","audience":"first-mate","url":"https://mcp.example.com/mcp","headers":{"Authorization":{"value":"Bearer s3cr3t-value","secret":true}}}""")
        val read = mvc.get("/repos/$repo/mcp-servers").andReturn().response.contentAsString
        assertFalse("s3cr3t-value" in read, read)
        val header = (list().single()["headers"] as Map<*, *>)["Authorization"] as Map<*, *>
        assertEquals(true, header["secret"]); assertEquals(true, header["set"]); assertTrue(header["since"] is String)
        val stored = jdbc.sql("SELECT headers FROM engine.repo_mcp_server WHERE repo = :r").param("r", repo).query(String::class.java).single()
        assertFalse("s3cr3t-value" in stored, "sealed in the row")

        // {"secret": true} alone keeps the sealed value.
        assertEquals(200, put("docs", """{"kind":"http","audience":"first-mate","url":"https://mcp.example.com/v2","headers":{"Authorization":{"secret":true}}}""").response.status)
        val config: Map<String, Any?> = json.readValue(mvc.get("/repos/$repo/mcp-servers/config").andExpect { status { isOk() } }.andReturn().response.contentAsString)
        assertEquals(mapOf("docs" to mapOf("type" to "http", "url" to "https://mcp.example.com/v2", "headers" to mapOf("Authorization" to "Bearer s3cr3t-value"))), config["mcpServers"])
        assertFalse("s3cr3t-value" in mvc.get("/repos/$repo/mcp-servers/docs").andReturn().response.contentAsString)
    }

    @Test
    fun `the config is the First mate's, a first-mate or both row is in it, a smith row never, and config() filters by audience`() {
        put("laptop", """{"kind":"http","audience":"first-mate","url":"http://127.0.0.1:8737/mcp"}""")
        put("shared", """{"kind":"http","audience":"both","url":"https://shared.example.com/mcp"}""")
        put("cluster", """{"kind":"http","audience":"smith","url":"http://ops.ops.svc.cluster.local:8737/mcp","headers":{"Authorization":{"value":"Bearer cluster-s3cr3t","secret":true}}}""")
        assertEquals(listOf("cluster", "laptop", "shared"), list().map { it["name"] })
        val audiences: Map<Any?, Any?> = list().associate { it["name"] to it["audience"] }
        assertEquals(mapOf<Any?, Any?>("cluster" to "smith", "laptop" to "first-mate", "shared" to "both"), audiences)
        val raw = mvc.get("/repos/$repo/mcp-servers/config").andExpect { status { isOk() } }.andReturn().response.contentAsString
        val config: Map<String, Any?> = json.readValue(raw)
        assertEquals(setOf("laptop", "shared"), (config["mcpServers"] as Map<*, *>).keys)
        assertFalse("cluster-s3cr3t" in raw, "a Smith's header never reaches the desktop's config")

        assertEquals(listOf("cluster", "laptop", "shared"), store.config(repo).map { it.name }, "no audience: every row")
        val smiths = store.config(repo, audience = McpAudience.SMITH)
        assertEquals(mapOf("cluster" to McpAudience.SMITH, "shared" to McpAudience.BOTH), smiths.associate { it.name to it.audience }, "a Smith gets smith and both rows")
        assertEquals("Bearer cluster-s3cr3t", smiths.single { it.name == "cluster" }.headers["Authorization"], "opened for the run's Secret")
        assertEquals(listOf("laptop", "shared"), store.config(repo, audience = McpAudience.FIRST_MATE).map { it.name })
    }

    @Test
    fun `without ENGINE_SECRETS_KEY a write that sets a secret is 503`() {
        val unkeyed = StoredRepoMcpServers(jdbc, SecretsKey(""), repos)
        val e = assertThrows<ResponseStatusException> {
            unkeyed.put(repo, "docs", mapOf("kind" to "http", "audience" to "first-mate", "url" to "https://x.example.com", "headers" to mapOf("A" to mapOf("value" to "v", "secret" to true))), null)
        }
        assertEquals(503, e.statusCode.value())
        assertEquals(emptyList(), list())
        unkeyed.put(repo, "plain", mapOf("kind" to "http", "audience" to "first-mate", "url" to "https://x.example.com", "headers" to mapOf("A" to "v")), null)
        assertEquals(1, list().size, "a plain value needs no key")
    }

    @Test
    fun `a Smith sandbox gets 403 on every write, the test and the config, and may read the list`() {
        val smith = put("docs", """{"kind":"http","audience":"smith","url":"https://x.example.com"}""", from = smithAddress)
        assertEquals(403, smith.response.status)
        assertContains(smith.response.errorMessage!!, "a Smith sandbox (run run-8) may not set a repo's MCP servers")
        put("docs", """{"kind":"http","audience":"smith","url":"https://x.example.com"}""")
        val asSmith = RequestPostProcessor { it.remoteAddr = smithAddress; it }
        mvc.delete("/repos/$repo/mcp-servers/docs") { with(asSmith) }.andExpect { status { isForbidden() } }
        mvc.post("/repos/$repo/mcp-servers/docs/test") { with(asSmith) }.andExpect { status { isForbidden() } }
        mvc.get("/repos/$repo/mcp-servers/config") { with(asSmith) }.andExpect { status { isForbidden() } }
        mvc.get("/repos/$repo/mcp-servers") { with(asSmith) }.andExpect { status { isOk() } }
        assertEquals(1, list().size)
    }

    @Test
    fun `the test of a stdio server answers that the desktop tests it`() {
        put("local", """{"kind":"stdio","audience":"first-mate","command":"echo"}""")
        val body: Map<String, Any?> = json.readValue(mvc.post("/repos/$repo/mcp-servers/local/test").andExpect { status { isOk() } }.andReturn().response.contentAsString)
        assertEquals(mapOf("reached" to false, "tools" to emptyList<String>(), "error" to McpProbe.STDIO, "toolSpecs" to emptyList<Any>()), body)
        mvc.post("/repos/$repo/mcp-servers/none/test").andExpect { status { isNotFound() } }
    }

    private fun testOf(server: String): Map<String, Any?> = json.readValue(mvc.post("/repos/$repo/mcp-servers/$server/test").andExpect { status { isOk() } }.andReturn().response.contentAsString)

    @Test
    fun `role ops (#100008) is probed on a put, stored and listed when the server conforms, and the test reports the contract`() {
        StubMcpServer().use { stub ->
            val added = put("ops", """{"kind":"http","audience":"smith","role":"ops","url":"${stub.url}"}""")
            assertEquals(201, added.response.status, added.response.errorMessage)
            assertEquals("ops", list().single()["role"])
            assertEquals(null, (json.readValue<Map<String, Any?>>(put("plain", """{"kind":"http","audience":"smith","url":"${stub.url}"}""").response.contentAsString))["role"], "no role: null")

            val test = testOf("ops")
            assertEquals(true, test["reached"]); assertEquals(listOf("is_deployed"), test["tools"])
            assertEquals(mapOf("version" to "ops/1", "conforms" to true, "missing" to emptyList<String>()), test["contract"])
            val spec = (test["toolSpecs"] as List<*>).single() as Map<*, *>
            assertEquals("is_deployed", spec["name"]); assertEquals(StubMcpServer.OPS_TOOL["inputSchema"], spec["inputSchema"])
            assertFalse("contract" in testOf("plain"), "a plain row reports no contract")

            // A second ops row for the same audience is refused; another audience is fine; dropping the role frees it.
            val second = put("ops2", """{"kind":"http","audience":"smith","role":"ops","url":"${stub.url}"}""")
            assertEquals(400, second.response.status); assertContains(second.response.errorMessage!!, "already has an ops server for audience smith: ops")
            assertEquals(201, put("ops-laptop", """{"kind":"http","audience":"first-mate","role":"ops","url":"${stub.url}"}""").response.status)
            assertEquals(200, put("ops", """{"kind":"http","audience":"smith","url":"${stub.url}"}""").response.status)
            assertEquals(null, list().single { it["name"] == "ops" }["role"], "an edit without the role drops it")
            assertEquals(201, put("ops2", """{"kind":"http","audience":"smith","role":"ops","url":"${stub.url}"}""").response.status)
            assertEquals("ops2", store.opsServer(repo)!!.name, "the cluster-reachable ops row is the engine's")

            // A server that stops conforming is refused on edit, and the test says what is missing.
            stub.tools = listOf(StubMcpServer.LOOSE_TOOL)
            val loose = put("ops2", """{"kind":"http","audience":"smith","role":"ops","url":"${stub.url}"}""")
            assertEquals(400, loose.response.status)
            assertEquals("ops server does not conform to ops/1: ${OpsContract.MISSING_SHA}", loose.response.errorMessage)
            assertEquals(mapOf("version" to "ops/1", "conforms" to false, "missing" to listOf(OpsContract.MISSING_SHA)), testOf("ops2")["contract"])
        }
    }

    @Test
    fun `role ops is refused for an unknown value, a stdio row, a server without the tool and one that cannot be reached, and nothing is stored`() {
        val unknown = put("ops", """{"kind":"http","audience":"smith","role":"deploy","url":"https://x.example.com"}""")
        assertEquals(400, unknown.response.status); assertEquals(RepoMcpServers.ROLE_400, unknown.response.errorMessage)
        val stdio = put("ops", """{"kind":"stdio","audience":"first-mate","role":"ops","command":"ops-mcp"}""")
        assertEquals(400, stdio.response.status); assertEquals(RepoMcpServers.OPS_STDIO_400, stdio.response.errorMessage)
        StubMcpServer(tools = listOf(mapOf("name" to "search_docs"))).use { stub ->
            val missing = put("ops", """{"kind":"http","audience":"smith","role":"ops","url":"${stub.url}"}""")
            assertEquals(400, missing.response.status)
            assertEquals("ops server does not conform to ops/1: ${OpsContract.MISSING_TOOL}", missing.response.errorMessage)
        }
        val port = java.net.ServerSocket(0).use { it.localPort }
        val down = put("ops", """{"kind":"http","audience":"smith","role":"ops","url":"http://127.0.0.1:$port/mcp"}""")
        assertEquals(400, down.response.status); assertContains(down.response.errorMessage!!, "ops server does not conform to ops/1: ")
        assertEquals(emptyList(), list(), "nothing stored")
    }
}

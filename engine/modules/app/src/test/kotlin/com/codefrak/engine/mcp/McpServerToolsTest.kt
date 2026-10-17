package com.codefrak.engine.mcp

import com.codefrak.engine.ProcessFakes
import com.codefrak.engine.repos.OpsContract
import com.codefrak.engine.repos.RepoMcpServers
import com.codefrak.engine.repos.StubMcpServer
import com.codefrak.engine.repos.TargetRepos
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The frakworks server's repo MCP server tools against the real store (#1801, #2449): the audience on add, edit and read. */
@SpringBootTest
@Import(ProcessFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class McpServerToolsTest {
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var store: RepoMcpServers
    @Autowired lateinit var engine: FrakworksEngine

    private val repo = "acme/mcptools"
    private val tools by lazy { McpServerTools(store, null, engine) }

    @BeforeEach fun setUp() { repos.remove(repo); repos.add(repo) }
    @AfterEach fun tearDown() { repos.remove(repo) }

    private fun call(tool: String, vararg args: Pair<String, Any?>) = tools.call(tool, mapOf("repo" to repo, *args), "alice")

    @Test
    fun `the add, edit and read tools carry the audience, which the schema requires and bounds`() {
        for (name in listOf(McpServerTools.ADD, McpServerTools.EDIT)) {
            val schema = McpServerTools.TOOLS.single { it.name == name }.inputSchema
            assertTrue("audience" in (schema["required"] as List<*>), name)
            val audience = (schema["properties"] as Map<*, *>)["audience"] as Map<*, *>
            assertEquals(listOf("smith", "first-mate", "both"), audience["enum"], name)
        }

        val added = call(McpServerTools.ADD, "name" to "ops-server", "kind" to "http", "audience" to "smith",
            "url" to "http://ops-server.ops.svc.cluster.local:8737/mcp", "headers" to mapOf("Authorization" to mapOf("value" to "Bearer s3cr3t", "secret" to true)))
        assertFalse(added.isError, added.text)
        assertTrue("its next Smith run gets it" in added.text, added.text)
        assertTrue("- ops-server (http, smith): http://ops-server.ops.svc.cluster.local:8737/mcp; headers: Authorization (secret, set since" in added.text, added.text)
        assertFalse("s3cr3t" in added.text)

        val missing = call(McpServerTools.ADD, "name" to "laptop", "kind" to "http", "url" to "http://127.0.0.1:8737/mcp")
        assertTrue(missing.isError); assertTrue("audience is required: smith, first-mate or both" in missing.text, missing.text)
        val unknown = call(McpServerTools.ADD, "name" to "laptop", "kind" to "http", "audience" to "laptop", "url" to "http://127.0.0.1:8737/mcp")
        assertTrue(unknown.isError); assertTrue("audience is smith, first-mate or both, not 'laptop'" in unknown.text, unknown.text)

        val edited = call(McpServerTools.EDIT, "name" to "ops-server", "kind" to "http", "audience" to "both",
            "url" to "http://ops-server.ops.svc.cluster.local:8737/mcp", "headers" to mapOf("Authorization" to mapOf("secret" to true)))
        assertFalse(edited.isError, edited.text)
        assertTrue("its next First mate and its next Smith run get it" in edited.text, edited.text)
        assertTrue("(http, both)" in edited.text, edited.text)

        val read = call(McpServerTools.READ)
        assertTrue(read.text.startsWith("The MCP servers $repo's First mate (first-mate), its Smith runs (smith) or both get beside the engine's three:"), read.text)
        assertTrue("- ops-server (http, both)" in read.text, read.text)
        assertFalse("s3cr3t" in read.text)

        assertFalse(call(McpServerTools.REMOVE, "name" to "ops-server").isError)
        assertTrue("neither its First mate nor its Smith runs get one" in call(McpServerTools.READ).text)
    }

    @Test
    fun `role ops (#100008) - add and edit probe the server and refuse one that does not conform, read shows the role, and test reports the contract`() {
        val schema = McpServerTools.TOOLS.single { it.name == McpServerTools.ADD }.inputSchema
        assertEquals(listOf("ops"), ((schema["properties"] as Map<*, *>)["role"] as Map<*, *>)["enum"])
        assertFalse("role" in (schema["required"] as List<*>))
        StubMcpServer().use { stub ->
            val added = call(McpServerTools.ADD, "name" to "ops-server", "kind" to "http", "audience" to "smith", "role" to "ops", "url" to stub.url)
            assertFalse(added.isError, added.text)
            assertTrue("- ops-server (http, smith, role ops): ${stub.url}" in added.text, added.text)
            assertTrue("role ops" in call(McpServerTools.READ).text)
            val test = call(McpServerTools.TEST, "name" to "ops-server")
            assertFalse(test.isError, test.text)
            assertEquals("ops-server answered; it lists 1 tool(s): is_deployed; it conforms to ops/1", test.text)

            stub.tools = listOf(StubMcpServer.LOOSE_TOOL)
            val edited = call(McpServerTools.EDIT, "name" to "ops-server", "kind" to "http", "audience" to "smith", "role" to "ops", "url" to stub.url)
            assertTrue(edited.isError); assertTrue("ops server does not conform to ops/1: ${OpsContract.MISSING_SHA}" in edited.text, edited.text)
            assertEquals("ops-server answered; it lists 1 tool(s): is_deployed; it does not conform to ops/1: ${OpsContract.MISSING_SHA}", call(McpServerTools.TEST, "name" to "ops-server").text)
            val stdio = call(McpServerTools.ADD, "name" to "local-ops", "kind" to "stdio", "audience" to "first-mate", "role" to "ops", "command" to "ops")
            assertTrue(stdio.isError); assertTrue(RepoMcpServers.OPS_STDIO_400 in stdio.text, stdio.text)
            val unknown = call(McpServerTools.ADD, "name" to "x", "kind" to "http", "audience" to "smith", "role" to "deploy", "url" to stub.url)
            assertTrue(unknown.isError); assertTrue(RepoMcpServers.ROLE_400 in unknown.text, unknown.text)
        }
    }
}

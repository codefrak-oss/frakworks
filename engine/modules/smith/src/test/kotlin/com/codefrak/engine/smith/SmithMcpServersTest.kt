package com.codefrak.engine.smith

import com.codefrak.engine.repos.McpAudience
import com.codefrak.engine.repos.McpServerConfig
import com.codefrak.engine.repos.McpServerKind
import com.codefrak.engine.repos.RepoMcpServers
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The repo MCP servers a Smith run gets (#2449; engine/README.md, "A Smith's repo MCP servers"): the filter, the run header, the env value. */
class SmithMcpServersTest {
    private fun http(name: String, url: String, audience: McpAudience, headers: Map<String, String> = emptyMap(), kind: McpServerKind = McpServerKind.HTTP) =
        McpServerConfig(name, kind, url, headers, null, emptyList(), emptyMap(), audience)
    private val ops = http("ops-server", "http://ops-server.ops.svc.cluster.local:8737/mcp", McpAudience.SMITH, mapOf("Authorization" to "Bearer s3cr3t"))
    private val spec = SmithSpec("run-9", "acme/ops", 37, "m", "e", dryRun = false)

    @Test
    fun `smith and both rows are kept, a first-mate row is dropped, and every http or sse entry carries X-Frakworks-Run`() {
        val (servers, dropped) = SmithMcpServers.build("run-9", listOf(
            ops,
            http("events", "https://events.example.com/sse", McpAudience.BOTH, kind = McpServerKind.SSE),
            http("laptop", "http://10.0.0.5:8737/mcp", McpAudience.FIRST_MATE),
        ))
        assertEquals(listOf("ops-server", "events"), servers.keys.toList())
        assertEquals(mapOf("type" to "http", "url" to "http://ops-server.ops.svc.cluster.local:8737/mcp",
            "headers" to mapOf("Authorization" to "Bearer s3cr3t", "X-Frakworks-Run" to "run-9")), servers["ops-server"])
        assertEquals(mapOf("type" to "sse", "url" to "https://events.example.com/sse", "headers" to mapOf("X-Frakworks-Run" to "run-9")), servers["events"])
        assertEquals(listOf(SmithMcpDrop("laptop", "a First mate's, not a Smith's")), dropped)
    }

    @Test
    fun `a row header named X-Frakworks-Run is replaced by the run's id, whatever its case`() {
        val (servers, _) = SmithMcpServers.build("run-9", listOf(ops.copy(headers = mapOf("x-frakworks-run" to "spoofed", "A" to "b"))))
        assertEquals(mapOf("A" to "b", "X-Frakworks-Run" to "run-9"), servers["ops-server"]!!["headers"])
    }

    @Test
    fun `an engine server's name and a local URL are dropped with a reason, a stdio entry passes through`() {
        val stdio = McpServerConfig("tool", McpServerKind.STDIO, null, emptyMap(), "tool", listOf("-v"), mapOf("A" to "b"), McpAudience.SMITH)
        val (servers, dropped) = SmithMcpServers.build("run-9", listOf(
            http("frakworks", "https://evil.example.com/mcp", McpAudience.SMITH),
            http("frakworks-engine-diagnostics", "https://evil.example.com/mcp", McpAudience.SMITH),
            http("mac", "http://127.0.0.1:8737/mcp", McpAudience.SMITH),
            http("home", "http://printer.local/mcp", McpAudience.SMITH),
            stdio,
        ))
        assertEquals(mapOf("tool" to mapOf("type" to "stdio", "command" to "tool", "args" to listOf("-v"), "env" to mapOf("A" to "b"))), servers)
        assertEquals(listOf("frakworks", "frakworks-engine-diagnostics", "mac", "home"), dropped.map { it.name })
        assertTrue(dropped.take(2).all { it.reason == "the engine's own server's name" })
        assertEquals("http://127.0.0.1:8737/mcp is a local address a Smith pod cannot reach", dropped[2].reason)
    }

    @Test
    fun `a cluster service name is not a local address, so the cluster instance is kept`() {
        val (servers, dropped) = SmithMcpServers.build("run-9", listOf(ops, ops.copy(name = "dns", url = "http://svc.ns.svc.cluster.local/mcp")))
        assertEquals(listOf("ops-server", "dns"), servers.keys.toList())
        assertEquals(emptyList(), dropped)
    }

    @Test
    fun `the env value is one JSON object, name to entry, and nothing for a repo with no Smith server`() {
        val store = mock(RepoMcpServers::class.java)
        `when`(store.config("acme/ops", null, McpAudience.SMITH)).thenReturn(listOf(ops))
        val value = SmithMcpServers(store).forRun(spec)!!
        val parsed: Map<String, Map<String, Any?>> = jacksonObjectMapper().readValue(value)
        assertEquals(setOf("ops-server"), parsed.keys)
        assertEquals(mapOf("Authorization" to "Bearer s3cr3t", "X-Frakworks-Run" to "run-9"), parsed["ops-server"]!!["headers"])

        `when`(store.config("acme/widgets", null, McpAudience.SMITH)).thenReturn(listOf(http("laptop", "http://10.0.0.5/mcp", McpAudience.FIRST_MATE)))
        assertNull(SmithMcpServers(store).forRun(spec.copy(repo = "acme/widgets")), "a First mate-only row gives a Smith nothing")
        assertNull(SmithMcpServers(null).forRun(spec), "no store, no servers")
    }

    @Test
    fun `a store that cannot open a sealed value costs the run its repo servers, not its start`() {
        val store = mock(RepoMcpServers::class.java)
        `when`(store.config("acme/ops", null, McpAudience.SMITH)).thenThrow(ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ENGINE_SECRETS_KEY is not set"))
        assertNull(SmithMcpServers(store).forRun(spec))
    }

    @Test
    fun `sandboxEnv carries the value as SMITH_MCP_SERVERS, and leaves the key out without one`() {
        val plain = SmithSpec("run-1", "acme/widgets", 7, "m", "e", dryRun = false)
        val value = SmithMcpServers.json(SmithMcpServers.build("run-1", listOf(ops)).first)
        val env = sandboxEnv(plain, "gh", null, "http://engine:8080", mcpServers = value)
        assertEquals(value, env.single { it.startsWith("SMITH_MCP_SERVERS=") }.substringAfter('='))
        assertFalse(sandboxEnv(plain, "gh", null, "http://engine:8080").any { it.startsWith("SMITH_MCP_SERVERS") })
        assertFalse(sandboxEnv(plain, "gh", null, "http://engine:8080", mcpServers = "").any { it.startsWith("SMITH_MCP_SERVERS") })
    }
}

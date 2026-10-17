package com.codefrak.engine.firstmate

import com.codefrak.engine.repos.McpAudience
import com.codefrak.engine.repos.McpServerConfig
import com.codefrak.engine.repos.McpServerKind
import com.codefrak.engine.repos.RepoMcpServers
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The session pod's MCP config (engine/README.md, "First-mate sessions"): the engine's servers, the repo's, the filter. */
class FirstMateMcpConfigTest {
    private fun http(name: String, url: String, kind: McpServerKind = McpServerKind.HTTP) =
        McpServerConfig(name, kind, url, mapOf("X-Key" to "k"), null, emptyList(), emptyMap())
    private fun stdio(name: String, command: String) = McpServerConfig(name, McpServerKind.STDIO, null, emptyMap(), command, listOf("-v"), mapOf("A" to "b"))

    @Test
    fun `the engine's two servers carry the token, and http and sse rows are kept as they are`() {
        val c = FirstMateMcpConfig.build("http://engine.frakworks:8090/", "frk_abc",
            listOf(http("linear", "https://mcp.linear.app/mcp"), http("events", "https://events.example.com/sse", McpServerKind.SSE))) { false }
        assertEquals(listOf("frakworks", "frakworks-engine-diagnostics", "linear", "events"), c.servers.keys.toList())
        for (n in FirstMateMcpConfig.ENGINE_SERVERS) {
            assertEquals("http://engine.frakworks:8090/mcp/$n", c.servers[n]!!["url"])
            assertEquals(mapOf("Authorization" to "Bearer frk_abc"), c.servers[n]!!["headers"])
        }
        assertEquals(mapOf("type" to "sse", "url" to "https://events.example.com/sse", "headers" to mapOf("X-Key" to "k")), c.servers["events"])
        assertEquals(emptyList(), c.dropped); assertNull(c.firstLine())
    }

    @Test
    fun `rebasedOn moves only the engine's servers to the pod's in-cluster engine, and a blank URL changes nothing`() {
        val c = FirstMateMcpConfig.build("https://engine.example.com", "frk_abc", listOf(http("linear", "https://mcp.linear.app/mcp"))) { false }
        val r = c.rebasedOn("http://engine.frakworks.svc:8080/")
        for (n in FirstMateMcpConfig.ENGINE_SERVERS) {
            assertEquals("http://engine.frakworks.svc:8080/mcp/$n", r.servers[n]!!["url"])
            assertEquals(mapOf("Authorization" to "Bearer frk_abc"), r.servers[n]!!["headers"])
        }
        assertEquals(c.servers["linear"], r.servers["linear"])
        assertEquals(c, c.rebasedOn(" "))
    }

    @Test
    fun `a stdio server is kept when its command is in the image and dropped and named when it is not`() {
        val c = FirstMateMcpConfig.build("http://e", "t", listOf(stdio("git", "git"), stdio("mine", "/Users/me/bin/mine"))) { it == "git" }
        assertEquals("stdio", c.servers["git"]!!["type"])
        assertFalse("mine" in c.servers)
        assertEquals(listOf(DroppedMcpServer("mine", "/Users/me/bin/mine is not in the image")), c.dropped)
    }

    @Test
    fun `loopback and local URLs are dropped and named for the chat's first line`() {
        val local = listOf("http://127.0.0.1:3000/mcp", "http://127.9.8.7/mcp", "http://[::1]:8080/mcp", "http://localhost:9000",
            "http://LOCALHOST/mcp", "http://printer.local/mcp", "not a url")
        val c = FirstMateMcpConfig.build("http://e", "t", local.mapIndexed { i, u -> http("s$i", u) } + http("ok", "http://128.0.0.1/mcp")) { true }
        assertEquals(local.indices.map { "s$it" }, c.dropped.map { it.name })
        assertTrue("ok" in c.servers)
        assertTrue(c.firstLine()!!.startsWith("Not on this chat: s0 (http://127.0.0.1:3000/mcp is a local address), s1 "))
    }

    @Test
    fun `forSession carries the First mate-audience rows only, first-mate and both, never a smith row`() {
        val store = mock(RepoMcpServers::class.java)
        // The store filters by audience (RepoMcpServers.config); forSession asks for the First mate's.
        `when`(store.config("acme/widgets", null, McpAudience.FIRST_MATE)).thenReturn(listOf(
            http("laptop", "https://laptop.example.com/mcp").copy(audience = McpAudience.FIRST_MATE),
            http("shared", "https://shared.example.com/mcp").copy(audience = McpAudience.BOTH)))
        `when`(store.config("acme/widgets")).thenReturn(listOf(
            http("laptop", "https://laptop.example.com/mcp").copy(audience = McpAudience.FIRST_MATE),
            http("shared", "https://shared.example.com/mcp").copy(audience = McpAudience.BOTH),
            http("cluster", "http://ops.ops.svc.cluster.local:8737/mcp").copy(audience = McpAudience.SMITH)))
        val c = FirstMateMcpConfig(store).forSession("http://e", "t", "acme/widgets")
        assertEquals(FirstMateMcpConfig.ENGINE_SERVERS + listOf("laptop", "shared"), c.servers.keys.toList())
        assertFalse("cluster" in c.servers)
        assertEquals(emptyList(), c.dropped)
    }

    @Test
    fun `local and onPath`() {
        assertTrue(FirstMateMcpConfig.local("https://a.local:1/x")); assertFalse(FirstMateMcpConfig.local("https://localhost.example.com"))
        assertFalse(FirstMateMcpConfig.local("https://10.0.0.1/mcp"))
        assertFalse(FirstMateMcpConfig.local("http://ops-server.ops.svc.cluster.local:8737/mcp"), "a cluster service name is not mDNS-local (#2449)")
        assertTrue(FirstMateMcpConfig.onPath("sh", "/bin:/usr/bin")); assertFalse(FirstMateMcpConfig.onPath("no-such-command-xyz", "/bin:/usr/bin"))
    }
}

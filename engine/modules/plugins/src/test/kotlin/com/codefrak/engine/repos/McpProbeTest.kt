package com.codefrak.engine.repos

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** [McpProbe] against a stub streamable-HTTP MCP server, an unreachable one and a stdio one (#1801). */
class McpProbeTest {
    private val json = jacksonObjectMapper()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val probe = McpProbe().apply { timeout = Duration.ofSeconds(3) }
    private val seen = mutableListOf<String>()

    @AfterEach fun stop() = server.stop(0)

    private fun stub() {
        server.createContext("/mcp") { ex ->
            val msg: Map<String, Any?> = json.readValue(ex.requestBody.readAllBytes())
            synchronized(seen) { seen += "${msg["method"]} ${ex.requestHeaders.getFirst("Authorization")} ${ex.requestHeaders.getFirst("Mcp-Session-Id")}" }
            val id = msg["id"]
            if (id == null) { ex.sendResponseHeaders(202, -1); ex.close(); return@createContext }
            val result = when (msg["method"]) {
                "initialize" -> mapOf("protocolVersion" to "2025-03-26", "capabilities" to mapOf("tools" to emptyMap<String, Any>()), "serverInfo" to mapOf("name" to "stub", "version" to "1"))
                "tools/list" -> mapOf("tools" to listOf(mapOf("name" to "search_docs", "description" to "Finds docs",
                    "inputSchema" to mapOf("type" to "object", "properties" to mapOf("q" to mapOf("type" to "string")), "required" to listOf("q"))), mapOf("name" to "read_doc")))
                "tools/call" -> mapOf("content" to listOf(mapOf("type" to "text", "text" to "{\"state\":\"deployed\"}")),
                    "structuredContent" to mapOf("state" to "deployed", "args" to (msg["params"] as Map<*, *>)["arguments"]))
                else -> emptyMap()
            }
            val body = json.writeValueAsBytes(mapOf("jsonrpc" to "2.0", "id" to id, "result" to result))
            ex.responseHeaders.add("Content-Type", "application/json")
            if (msg["method"] == "initialize") ex.responseHeaders.add("Mcp-Session-Id", "sess-1")
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.write(body); ex.close()
        }
        server.start()
    }

    private fun http(url: String) = McpServerConfig("docs", McpServerKind.HTTP, url, mapOf("Authorization" to "Bearer t"), null, emptyList(), emptyMap())

    @Test
    fun `a reached server answers its tool names, the headers and session carried on`() {
        stub()
        val result = probe.test(http("http://127.0.0.1:${server.address.port}/mcp"))
        assertEquals(McpProbeResult(true, listOf("search_docs", "read_doc"), null, listOf(
            McpToolSpec("search_docs", "Finds docs", mapOf("type" to "object", "properties" to mapOf("q" to mapOf("type" to "string")), "required" to listOf("q"))),
            McpToolSpec("read_doc"))), result)
        assertEquals(null, result.contract, "a plain row reports no contract")
        assertEquals(listOf("initialize Bearer t null", "notifications/initialized Bearer t sess-1", "tools/list Bearer t sess-1"), seen)
    }

    @Test
    fun `a role ops row reports its conformance, and call does one tools call after the handshake`() {
        stub()
        val ops = http("http://127.0.0.1:${server.address.port}/mcp").copy(role = McpRole.OPS)
        val result = probe.test(ops)
        assertTrue(result.reached)
        assertEquals(OpsContract.Conformance("ops/1", false, listOf(OpsContract.MISSING_TOOL)), result.contract)

        val answer = probe.call(ops, "is_deployed", mapOf("sha" to "abc1234"))
        assertEquals(mapOf("state" to "deployed", "args" to mapOf("sha" to "abc1234")), answer["structuredContent"])
        assertEquals("tools/call Bearer t sess-1", seen.last())
    }

    @Test
    fun `an unreachable server answers not reached with its error`() {
        val port = ServerSocket(0).use { it.localPort }
        val result = probe.test(http("http://127.0.0.1:$port/mcp"))
        assertFalse(result.reached); assertEquals(emptyList(), result.tools)
        assertNotNull(result.error); assertTrue(result.error!!.isNotBlank())
    }

    @Test
    fun `a stdio server is the desktop's to test`() {
        val result = probe.test(McpServerConfig("local", McpServerKind.STDIO, null, emptyMap(), "echo", emptyList(), emptyMap()))
        assertEquals(McpProbeResult(false, emptyList(), McpProbe.STDIO), result)
        val ops = probe.test(McpServerConfig("local", McpServerKind.STDIO, null, emptyMap(), "echo", emptyList(), emptyMap(), role = McpRole.OPS))
        assertEquals(OpsContract.Conformance("ops/1", false, listOf(OpsContract.MISSING_TOOL)), ops.contract, "a stdio ops row conforms to nothing the engine can see")
    }
}

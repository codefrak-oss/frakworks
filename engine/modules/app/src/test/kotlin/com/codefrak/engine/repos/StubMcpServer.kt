package com.codefrak.engine.repos

import com.sun.net.httpserver.HttpServer
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.net.InetSocketAddress

/**
 * A streamable-HTTP MCP server on loopback for the tests (#100008): answers `initialize`, lists [tools] on
 * `tools/list`, and answers `tools/call` with [answer] of the call's arguments. [OPS_TOOL] is an `is_deployed` that
 * conforms to `ops/1`.
 */
class StubMcpServer(var tools: List<Map<String, Any?>> = listOf(OPS_TOOL),
                    var answer: (Map<*, *>) -> Map<String, Any?> = { mapOf("structuredContent" to mapOf("state" to "not_yet")) }) : AutoCloseable {
    companion object {
        val OPS_TOOL: Map<String, Any?> = mapOf("name" to "is_deployed", "description" to "Whether sha is in production: {state: deployed|not_yet}",
            "inputSchema" to mapOf("type" to "object", "properties" to mapOf("sha" to mapOf("type" to "string")), "required" to listOf("sha")))
        /** An `is_deployed` whose `sha` is optional: it does not conform. */
        val LOOSE_TOOL: Map<String, Any?> = mapOf("name" to "is_deployed", "description" to "Whether sha is in production: {state: deployed|not_yet}",
            "inputSchema" to mapOf("type" to "object", "properties" to mapOf("sha" to mapOf("type" to "string"))))
    }
    private val json = jacksonObjectMapper()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val calls = mutableListOf<Map<*, *>>()
    val url: String get() = "http://127.0.0.1:${server.address.port}/mcp"

    init {
        server.createContext("/mcp") { ex ->
            val msg: Map<String, Any?> = json.readValue(ex.requestBody.readAllBytes())
            val id = msg["id"]
            if (id == null) { ex.sendResponseHeaders(202, -1); ex.close(); return@createContext }
            val result = when (msg["method"]) {
                "initialize" -> mapOf("protocolVersion" to "2025-03-26", "capabilities" to mapOf("tools" to emptyMap<String, Any>()), "serverInfo" to mapOf("name" to "stub", "version" to "1"))
                "tools/list" -> mapOf("tools" to tools)
                "tools/call" -> { val args = (msg["params"] as Map<*, *>)["arguments"] as Map<*, *>; synchronized(calls) { calls += args }; answer(args) }
                else -> emptyMap()
            }
            val body = json.writeValueAsBytes(mapOf("jsonrpc" to "2.0", "id" to id, "result" to result))
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.write(body); ex.close()
        }
        server.start()
    }

    override fun close() = server.stop(0)
}

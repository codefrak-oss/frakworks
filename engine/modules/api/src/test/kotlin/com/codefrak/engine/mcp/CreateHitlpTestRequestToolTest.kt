package com.codefrak.engine.mcp

import com.codefrak.engine.engineJson
import com.codefrak.engine.hitlp.HitlpEndpoint
import com.codefrak.engine.hitlp.HitlpRequester
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.readValue
import java.net.InetSocketAddress
import java.time.Duration

/** create_hitlp_test_request against a stub HITLP server: a round trip, its defaults, bad input, and no server configured. */
class CreateHitlpTestRequestToolTest {
    private lateinit var server: HttpServer
    private val calls = mutableListOf<Map<String, Any?>>()
    private var sessions = 0

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mcp") { x -> handle(x) }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private val url get() = "http://127.0.0.1:${server.address.port}/mcp"
    private fun tool(configured: Boolean = true) = CreateHitlpTestRequestTool(HitlpRequester({ if (configured) HitlpEndpoint(url, "secret-token") else null }, Duration.ofSeconds(5)))

    private fun handle(x: HttpExchange) {
        val body: Map<String, Any?> = engineJson.readValue(x.requestBody.readBytes())
        val reply: Map<String, Any?> = when (body["method"]) {
            "initialize" -> {
                sessions++
                x.responseHeaders.add("Mcp-Session-Id", "s-$sessions")
                mapOf("protocolVersion" to "2026-07-28", "capabilities" to mapOf("tools" to emptyMap<String, Any?>(), "tasks" to emptyMap<String, Any?>()), "serverInfo" to mapOf("name" to "stub"))
            }
            "notifications/initialized" -> return respond(x, 202, "")
            "tools/call" -> {
                val params = body["params"] as Map<*, *>
                calls += (params["arguments"] as Map<String, Any?>)
                mapOf("task" to mapOf("taskId" to "t-100", "status" to "working", "ttl" to 3600000, "pollInterval" to 5000,
                    "_meta" to mapOf("io.hitlp/decisionUrl" to "https://hitlp.example/decide/t-100")))
            }
            else -> return respond(x, 200, engineJson.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to body["id"],
                "error" to mapOf("code" to -32601, "message" to "no such method"))))
        }
        respond(x, 200, engineJson.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to body["id"], "result" to reply)))
    }

    private fun respond(x: HttpExchange, status: Int, body: String) {
        x.responseHeaders.add("Content-Type", "application/json")
        val bytes = body.toByteArray()
        x.sendResponseHeaders(status, if (bytes.isEmpty()) -1L else bytes.size.toLong())
        if (bytes.isNotEmpty()) x.responseBody.use { it.write(bytes) } else x.responseBody.close()
    }

    @Suppress("UNCHECKED_CAST")
    private fun ok(r: McpToolResult): Map<String, Any?> { assertFalse(r.isError, r.text); return engineJson.readValue(r.text) }
    private fun error(r: McpToolResult): String { assertTrue(r.isError, r.text); return engineJson.readValue<Map<String, Any?>>(r.text)["error"] as String }

    @Test
    fun `creates a human ask with test defaults and answers the task id and decision url`() {
        val result = ok(tool().call(emptyMap()))
        assertEquals("t-100", result["id"]); assertEquals("working", result["status"]); assertEquals("https://hitlp.example/decide/t-100", result["url"])
        val sent = calls.single()
        assertEquals("fail", sent["defaultOnTimeout"]); assertEquals("normal", sent["priority"])
        assertTrue((sent["question"] as String).isNotBlank())
        assertEquals("Test HITLP request", (sent["context"] as Map<*, *>)["summary"])
        assertEquals(mapOf("type" to "string"), sent["responseSchema"])
        assertTrue((sent["idempotencyKey"] as String).startsWith("first-mate-test:"))
    }

    @Test
    fun `title, body and priority are passed through`() {
        ok(tool().call(mapOf("title" to "My test", "body" to "Does this reach you?", "priority" to "urgent")))
        val sent = calls.single()
        assertEquals("Does this reach you?", sent["question"]); assertEquals("My test", (sent["context"] as Map<*, *>)["summary"]); assertEquals("urgent", sent["priority"])
    }

    @Test
    fun `a bad priority is refused before any call`() {
        val e = error(tool().call(mapOf("priority" to "whenever")))
        assertTrue("priority" in e, e)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `an unknown argument is refused`() {
        val e = error(tool().call(mapOf("subject" to "x")))
        assertTrue("subject" in e, e)
    }

    @Test
    fun `no server configured names PUT settings hitlp`() {
        val e = error(tool(configured = false).call(emptyMap()))
        assertTrue("PUT /settings/hitlp" in e, e)
    }

    @Test
    fun `a server error is an error naming the cause`() {
        server.stop(0)
        val e = error(tool().call(emptyMap()))
        assertTrue(e.isNotBlank(), e)
    }
}

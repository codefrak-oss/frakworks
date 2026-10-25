package com.codefrak.engine.mcp

import com.codefrak.engine.engineJson
import com.codefrak.engine.friction.FrictionLog
import com.codefrak.engine.hitlp.HitlpEndpoint
import com.codefrak.engine.hitlp.HitlpRequester
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.tier.TierTable
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

/** hitlp_ask, hitlp_approve and hitlp_task against a stub HITLP server: created at once, read pending and decided, First mate's only. */
class HitlpToolsTest {
    private lateinit var server: HttpServer
    private val calls = mutableListOf<Map<String, Any?>>()
    private var decided = false

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mcp") { x -> handle(x) }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private val url get() = "http://127.0.0.1:${server.address.port}/mcp"
    private fun requester(configured: Boolean = true) = HitlpRequester({ if (configured) HitlpEndpoint(url, "secret-token") else null }, Duration.ofSeconds(5))
    private fun tools(configured: Boolean = true) = HitlpTools(requester(configured))

    @Suppress("UNCHECKED_CAST")
    private fun handle(x: HttpExchange) {
        val body: Map<String, Any?> = engineJson.readValue(x.requestBody.readBytes())
        val params = body["params"] as? Map<String, Any?>
        val reply: Map<String, Any?> = when (body["method"]) {
            "initialize" -> {
                x.responseHeaders.add("Mcp-Session-Id", "s-1")
                mapOf("protocolVersion" to "2026-07-28", "capabilities" to mapOf("tools" to emptyMap<String, Any?>(), "tasks" to emptyMap<String, Any?>()), "serverInfo" to mapOf("name" to "stub"))
            }
            "notifications/initialized" -> return respond(x, 202, "")
            "tools/call" -> {
                calls += params!!
                mapOf("task" to mapOf("taskId" to "t-100", "status" to "working", "ttl" to 3600000, "pollInterval" to 5000))
            }
            "tasks/get" -> if (!decided) mapOf("taskId" to params!!["taskId"], "status" to "input_required",
                "_meta" to mapOf("io.hitlp/decisionUrl" to "https://hitlp.example/decide/t-100"))
                else mapOf("taskId" to params!!["taskId"], "status" to "completed")
            "tasks/result" -> mapOf("structuredContent" to mapOf("outcome" to "answered", "value" to "blue"))
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

    private fun ok(r: McpToolResult): Map<String, Any?> { assertFalse(r.isError, r.text); return engineJson.readValue(r.text) }
    private fun error(r: McpToolResult): String { assertTrue(r.isError, r.text); return engineJson.readValue<Map<String, Any?>>(r.text)["error"] as String }
    private fun sent() = calls.single()["arguments"] as Map<*, *>

    @Test
    fun `hitlp_ask files a task-augmented human ask with choices and answers its id at once`() {
        val result = ok(tools().call(HitlpTools.ASK, mapOf("question" to "Which colour?", "summary" to "Pick a colour", "choices" to listOf("red", "blue"))))
        assertEquals("t-100", result["taskId"]); assertEquals("working", result["status"]); assertEquals(false, result["terminal"])
        val call = calls.single()
        assertEquals("human.ask", call["name"]); assertTrue(call["task"] is Map<*, *>)
        val args = sent()
        assertEquals("Which colour?", args["question"]); assertEquals("normal", args["priority"])
        assertEquals(mapOf("type" to "string", "enum" to listOf("red", "blue")), args["responseSchema"])
        assertTrue((args["idempotencyKey"] as String).startsWith("first-mate:"))
    }

    @Test
    fun `hitlp_approve files a human approve with its payload digest`() {
        val result = ok(tools().call(HitlpTools.APPROVE, mapOf("action" to "delete branch x of acme/widgets", "summary" to "Delete branch x", "payload" to mapOf("branch" to "x"))))
        assertEquals("t-100", result["taskId"])
        assertEquals("human.approve", calls.single()["name"])
        val args = sent()
        assertEquals("delete branch x of acme/widgets", args["action"]); assertEquals(mapOf("branch" to "x"), args["payload"])
        assertTrue(args["payloadDigest"] is String); assertEquals(true, args["requiresHuman"])
    }

    @Test
    fun `hitlp_task reads a pending task then its decision`() {
        val pending = ok(tools().call(HitlpTools.TASK, mapOf("taskId" to "t-100")))
        assertEquals("input_required", pending["status"]); assertEquals(false, pending["terminal"])
        assertEquals("https://hitlp.example/decide/t-100", pending["url"]); assertFalse("result" in pending)
        decided = true
        val done = ok(tools().call(HitlpTools.TASK, mapOf("taskId" to "t-100")))
        assertEquals("completed", done["status"]); assertEquals(true, done["terminal"])
        assertEquals(mapOf("outcome" to "answered", "value" to "blue"), done["result"])
    }

    @Test
    fun `bad input is refused before any call`() {
        assertTrue("question" in error(tools().call(HitlpTools.ASK, mapOf("summary" to "s"))))
        assertTrue("priority" in error(tools().call(HitlpTools.ASK, mapOf("question" to "q", "summary" to "s", "priority" to "whenever"))))
        assertTrue("choices" in error(tools().call(HitlpTools.ASK, mapOf("question" to "q", "summary" to "s", "choices" to "red"))))
        assertTrue("payload" in error(tools().call(HitlpTools.APPROVE, mapOf("action" to "a", "summary" to "s", "payload" to "x"))))
        assertTrue("taskId" in error(tools().call(HitlpTools.TASK, emptyMap())))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `no server configured names PUT settings hitlp`() {
        assertTrue("PUT /settings/hitlp" in error(tools(configured = false).call(HitlpTools.TASK, mapOf("taskId" to "t-1"))))
    }

    /** Every member answers null: the HITLP tools reach only the requester. */
    private inline fun <reified T> unused(): T = java.lang.reflect.Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ ->
        if (m.name == "toString") "unused" else null } as T

    private fun tierTable(): TierTable {
        val f = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        return (f.get(null) as sun.misc.Unsafe).allocateInstance(TierTable::class.java) as TierTable
    }

    @Test
    fun `First mate lists and calls them, a Smith neither lists nor may call them`() {
        val mcp = FrakworksMcp(unused<FrakworksEngine>(), tierTable(), unused<FrictionLog>(), hitlp = requester())
        val mate = McpCaller.Nobody("127.0.0.1", "someone")
        val smith = McpCaller.Smith(SmithCaller("run-1", 7, "acme/widgets"))
        assertTrue(mcp.tools(mate).map { it.name }.containsAll(HitlpTools.NAMES))
        assertTrue(mcp.tools(smith).none { it.name in HitlpTools.NAMES })
        for (name in HitlpTools.NAMES) assertTrue("may not call" in error(mcp.call(name, mapOf("taskId" to "t-100"), smith)))
        assertTrue(calls.isEmpty())
        assertEquals("input_required", ok(mcp.call(HitlpTools.TASK, mapOf("taskId" to "t-100"), mate))["status"])
        assertFalse(FrakworksMcp(unused<FrakworksEngine>(), tierTable(), unused<FrictionLog>()).tools(mate).any { it.name in HitlpTools.NAMES })
    }
}

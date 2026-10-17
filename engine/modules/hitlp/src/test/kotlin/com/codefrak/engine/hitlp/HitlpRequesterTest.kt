package com.codefrak.engine.hitlp

import com.codefrak.engine.engineJson
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.readValue
import java.net.InetSocketAddress
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [HitlpRequester] against a `com.sun.net.httpserver` stub of a HITLP server's MCP Streamable HTTP endpoint: the bearer
 * header on every request, the `initialize` / `notifications/initialized` handshake once with the session id echoed on
 * every later request, `tools/call` carrying `task.ttl`, `tasks/get` and `tasks/cancel`, an SSE reply read to its first
 * `message` event, a 404 re-initializing the session, a JSON-RPC error surfaced, no server refused naming
 * `PUT /settings/hitlp`, and a URL change (the endpoint is read live, #2500) dropping the session.
 */
class HitlpRequesterTest {
    private lateinit var server: HttpServer
    /** Every request the stub took: its headers and JSON body, in order. */
    private val requests = mutableListOf<Pair<Map<String, String>, Map<String, Any?>>>()
    private var sessions = 0
    private var sse = false
    /** The protocol version the stub answers to `initialize`. */
    private var negotiatedVersion = "2026-07-28"
    /** Session ids the stub answers 404 to (it "forgot" them). */
    private val forgotten = mutableSetOf<String>()

    private val working = mapOf("taskId" to "t-1", "status" to "working", "ttl" to 60000, "pollInterval" to 2500, "statusMessage" to "waiting on a human",
        "_meta" to mapOf("io.hitlp/decisionUrl" to "https://hitlp.example/decide/t-1"))
    private val record = mapOf("requestId" to "t-1", "idempotencyKey" to "k", "primitive" to "ask", "outcome" to "answered", "answer" to "EUR",
        "decidedBy" to mapOf("type" to "human", "id" to "lundmatt1"), "decidedAt" to "2026-10-11T10:00:00Z", "channel" to "desktop")

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mcp") { x -> handle(x) }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private val url get() = "http://127.0.0.1:${server.address.port}/mcp"
    private fun requester(url: String? = this.url) = HitlpRequester({ url?.let { HitlpEndpoint(it, "secret-token") } }, Duration.ofSeconds(5))

    private fun handle(x: HttpExchange) {
        val headers = x.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
        val body: Map<String, Any?> = engineJson.readValue(x.requestBody.readBytes())
        requests += headers to body
        val session = headers[HitlpRequester.SESSION_HEADER]
        val method = body["method"]
        if (method != "initialize" && session in forgotten) return respond(x, 404, "", "text/plain")
        val params = body["params"] as? Map<*, *>
        val result: Any? = when (method) {
            "initialize" -> {
                sessions++
                x.responseHeaders.add("Mcp-Session-Id", "s-$sessions")
                mapOf("protocolVersion" to negotiatedVersion, "capabilities" to mapOf("tools" to emptyMap<String, Any?>(), "tasks" to emptyMap<String, Any?>()), "serverInfo" to mapOf("name" to "stub"))
            }
            "notifications/initialized" -> return respond(x, 202, "", "text/plain")
            "tools/call" -> if ((params!!["name"] as String).startsWith("human.")) mapOf("task" to working)
                else return rpc(x, mapOf("error" to mapOf("code" to -32602, "message" to "unknown tool ${params["name"]}")), body["id"])
            "tasks/get" -> if (params!!["taskId"] == "t-done") working + ("taskId" to "t-done") + ("status" to "completed") + ("result" to mapOf(
                "content" to listOf(mapOf("type" to "text", "text" to engineJson.writeValueAsString(record))), "structuredContent" to record))
                else if (params["taskId"] == "t-spec") working + ("taskId" to "t-spec") + ("status" to "completed")
                else working
            "tasks/result" -> if (params!!["taskId"] == "t-spec") mapOf("content" to listOf(mapOf("type" to "text", "text" to engineJson.writeValueAsString(record))),
                "structuredContent" to record) else return rpc(x, mapOf("error" to mapOf("code" to -32602, "message" to "not terminal")), body["id"])
            "tasks/cancel" -> working + ("status" to "cancelled")
            else -> return rpc(x, mapOf("error" to mapOf("code" to -32601, "message" to "no such method")), body["id"])
        }
        rpc(x, mapOf("result" to result), body["id"])
    }

    private fun rpc(x: HttpExchange, reply: Map<String, Any?>, id: Any?) {
        val json = engineJson.writeValueAsString(reply + ("jsonrpc" to "2.0") + ("id" to id))
        if (sse) respond(x, 200, ": keep-alive\n\nevent: ping\ndata: {}\n\nevent: message\ndata: $json\n\n", "text/event-stream")
        else respond(x, 200, json, "application/json")
    }

    private fun respond(x: HttpExchange, status: Int, body: String, type: String) {
        val bytes = body.toByteArray()
        x.responseHeaders.add("Content-Type", type)
        x.sendResponseHeaders(status, if (bytes.isEmpty()) -1L else bytes.size.toLong())
        if (bytes.isNotEmpty()) x.responseBody.use { it.write(bytes) }
        else x.responseBody.close()
    }

    private fun methods() = requests.map { it.second["method"] }

    @Test
    fun `call initializes once, sends the bearer header and the session id on every request, and carries task ttl`() {
        val r = requester()
        val h = r.call("human.ask", mapOf("idempotencyKey" to "k", "question" to "Which?"), 60000)
        assertEquals("t-1", h.taskId); assertEquals("working", h.status); assertEquals(2500L, h.pollInterval); assertEquals(60000L, h.ttl)
        assertEquals("https://hitlp.example/decide/t-1", h.meta!!["io.hitlp/decisionUrl"]); assertEquals("waiting on a human", h.statusMessage)
        assertEquals(listOf("initialize", "notifications/initialized", "tools/call"), methods())
        for ((headers, _) in requests) {
            assertEquals("Bearer secret-token", headers["authorization"])
            assertEquals("application/json, text/event-stream", headers["accept"])
            assertEquals(HitlpRequester.PROTOCOL_VERSION, headers["mcp-protocol-version"])
        }
        assertNull(requests[0].first[HitlpRequester.SESSION_HEADER], "initialize opens the session")
        assertEquals("s-1", requests[1].first[HitlpRequester.SESSION_HEADER]); assertEquals("s-1", requests[2].first[HitlpRequester.SESSION_HEADER])
        val call = requests[2].second["params"] as Map<*, *>
        assertEquals("human.ask", call["name"]); assertEquals(mapOf("ttl" to 60000), call["task"]); assertEquals("Which?", (call["arguments"] as Map<*, *>)["question"])
        val init = requests[0].second["params"] as Map<*, *>
        assertEquals(HitlpRequester.PROTOCOL_VERSION, init["protocolVersion"]); assertEquals(HitlpRequester.CLIENT_NAME, (init["clientInfo"] as Map<*, *>)["name"])

        r.call("human.approve", mapOf("idempotencyKey" to "k2", "action" to "Deploy"), 1000)
        assertEquals(listOf("initialize", "notifications/initialized", "tools/call", "tools/call"), methods(), "the handshake happens once per server")
    }

    @Test
    fun `get and cancel send the task id, and a terminal get carries the decision record`() {
        val r = requester()
        val open = r.get("t-1")
        assertEquals("working", open.status); assertNull(open.record); assertTrue(!open.terminal)
        val done = r.get("t-done")
        assertEquals("completed", done.status); assertTrue(done.terminal); assertEquals(record, done.record)
        val cancelled = r.cancel("t-1")
        assertEquals("cancelled", cancelled.status)
        assertEquals(listOf("initialize", "notifications/initialized", "tasks/get", "tasks/get", "tasks/cancel"), methods())
        assertEquals(mapOf("taskId" to "t-1"), requests[2].second["params"]); assertEquals(mapOf("taskId" to "t-done"), requests[3].second["params"])
        assertEquals(mapOf("taskId" to "t-1"), requests[4].second["params"])
    }

    @Test
    fun `an SSE reply is read to its first message event`() {
        sse = true
        val h = requester().call("human.ask", mapOf("idempotencyKey" to "k"), 60000)
        assertEquals("t-1", h.taskId)
        assertEquals("completed", requester().get("t-done").status)
        assertEquals("{\"a\":\n1}", requester().firstMessage("event: other\ndata: no\n\ndata: {\"a\":\ndata: 1}\n\n"), "data lines of one event are joined with a newline; a named other event is skipped")
    }

    @Test
    fun `a 404 re-initializes the session once and retries the request on the new one`() {
        val r = requester()
        r.get("t-1")
        forgotten += "s-1"
        val h = r.get("t-1")
        assertEquals("t-1", h.taskId)
        assertEquals(listOf("initialize", "notifications/initialized", "tasks/get", "tasks/get", "initialize", "notifications/initialized", "tasks/get"), methods())
        assertEquals("s-2", requests.last().first[HitlpRequester.SESSION_HEADER])
    }

    @Test
    fun `a JSON-RPC error is an RpcError with its code and message`() {
        val e = assertFailsWith<HitlpRequester.RpcError> { requester().call("other.tool", emptyMap(), 1000) }
        assertEquals(-32602, e.code); assertTrue("unknown tool other.tool" in e.message!!, e.message)
    }

    @Test
    fun `no server refuses every method naming PUT settings hitlp, and sends nothing`() {
        val r = requester(url = null)
        assertTrue(!r.configured)
        for (attempt in listOf<() -> Any>({ r.call("human.ask", emptyMap(), 1000) }, { r.get("t") }, { r.cancel("t") })) {
            val e = assertFailsWith<IllegalStateException> { attempt() }
            assertTrue("PUT /settings/hitlp" in e.message!!, e.message)
        }
        assertEquals(emptyList<Any?>(), methods())
    }

    @Test
    fun `the endpoint is read at each call, and a URL change drops the session and initializes again`() {
        var current: HitlpEndpoint? = HitlpEndpoint(url, "secret-token")
        val r = HitlpRequester({ current }, Duration.ofSeconds(5))
        r.get("t-1"); r.get("t-1")
        assertEquals(listOf("initialize", "notifications/initialized", "tasks/get", "tasks/get"), methods())
        current = null
        assertTrue(!r.configured)
        assertFailsWith<IllegalStateException> { r.get("t-1") }
        current = HitlpEndpoint("$url?other", "other-token")
        assertTrue(r.configured)
        r.get("t-1")
        assertEquals(listOf("initialize", "notifications/initialized", "tasks/get", "tasks/get", "initialize", "notifications/initialized", "tasks/get"), methods())
        assertEquals("Bearer other-token", requests.last().first["authorization"])
    }

    @Test
    fun `a terminal task answered without its result is completed from tasks result`() {
        val done = requester().get("t-spec")
        assertEquals("completed", done.status)
        assertEquals("answered", done.record!!["outcome"])
        val methods = requests.map { it.second["method"] }
        assertEquals(listOf("initialize", "notifications/initialized", "tasks/get", "tasks/result"), methods)
        assertEquals(mapOf("taskId" to "t-spec"), requests[3].second["params"])
    }

    @Test
    fun `a task still open is not asked for its result`() {
        requester().get("t-1")
        assertTrue(requests.none { it.second["method"] == "tasks/result" })
    }

    @Test
    fun `later requests carry the protocol version the server negotiated, not the one asked for`() {
        negotiatedVersion = "2025-11-25"
        requester().get("t-1")
        val init = requests.first { it.second["method"] == "initialize" }
        assertEquals(HitlpRequester.PROTOCOL_VERSION, (init.second["params"] as Map<*, *>)["protocolVersion"])
        val later = requests.filter { it.second["method"] != "initialize" }
        assertTrue(later.isNotEmpty())
        assertTrue(later.all { it.first["mcp-protocol-version"] == "2025-11-25" }, later.map { it.first["mcp-protocol-version"] }.toString())
    }
}

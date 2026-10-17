package com.codefrak.engine.hitlp

import com.codefrak.engine.engineJson
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.springframework.test.context.DynamicPropertyRegistry
import tools.jackson.module.kotlin.readValue
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * An in-process HITLP server for the engine as a requester (#2458): one per test class, started on construction, its
 * URL set as `test.hitlp.url` (token [TOKEN]) by [register] from the class's `@DynamicPropertySource`, which the
 * [HitlpStubSeeder] writes into the engine's settings row before the lease stream starts. It records
 * every JSON-RPC call ([calls]) and answers `initialize`, `tools/call` with a handle (`taskId` `t-<n>`, `pollInterval`
 * 10 ms, `status input_required`, `_meta io.hitlp/decisionUrl`), `tasks/get` as `input_required` until [decide] gives
 * the task its record, `tasks/result` of a decided task, and `tasks/cancel`. [handle] overrides the handle `tools/call`
 * answers next.
 *
 * A GET is the session's standalone SSE stream (#2463): held open and counted in [streams]; with [notify] set, [decide]
 * sends `notifications/tasks/status` on the newest open stream (off by default, so the engine's own stream does not
 * race a test that drives [HitlpPoller] by hand). [drop] ends every open stream; with [noStream] set a GET is answered
 * 405.
 */
class StubHitlpServer {
    companion object {
        const val TOKEN = "test-token"
        const val DECISION_BASE = "https://hitlp.example/decide/"
    }

    /** One call the stub took: its method, params and `Authorization` header. */
    data class Call(val method: String, val params: Map<String, Any?>, val authorization: String?)

    val calls = CopyOnWriteArrayList<Call>()
    private val decided = ConcurrentHashMap<String, Pair<String, Map<String, Any?>>>()
    private val ids = AtomicInteger()
    /** The handle members the next `tools/call` answers, over the defaults; null for the defaults. */
    @Volatile var handle: Map<String, Any?>? = null
    /** Whether [decide] sends `notifications/tasks/status` on the newest open stream. */
    @Volatile var notify = false
    /** Whether a GET is answered 405 (the server offers no stream). */
    @Volatile var noStream = false
    /** How many stream GETs were answered (405s included). */
    val streams = AtomicInteger()
    private val held = CopyOnWriteArrayList<HttpExchange>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        // A held stream takes its thread: one per exchange.
        executor = Executors.newCachedThreadPool()
        createContext("/mcp") { x -> if (x.requestMethod == "GET") stream(x) else answer(x) }
        start()
    }
    val url: String get() = "http://127.0.0.1:${server.address.port}/mcp"

    fun register(registry: DynamicPropertyRegistry) {
        registry.add(SharedHitlpStub.URL_PROPERTY) { url }
        registry.add(SharedHitlpStub.TOKEN_PROPERTY) { TOKEN }
    }

    /** Ends [taskId] with [record]: `tasks/get` answers [status] and the record from now on. */
    fun decide(taskId: String, record: Map<String, Any?>, status: String = HitlpPrimitives.COMPLETED) {
        decided[taskId] = status to record
        if (notify) send(mapOf("jsonrpc" to "2.0", "method" to "notifications/tasks/status", "params" to mapOf("taskId" to taskId, "status" to status)))
    }

    /** How many streams are open now. */
    fun openStreams() = held.size

    /** Ends every open stream, as a server restart or a proxy timeout would. */
    fun drop() { held.toList().forEach { runCatching { it.close() } }; held.clear() }

    // To every open stream, not only the newest: another cached test context's engine stream may hold one here too.
    private fun send(message: Map<String, Any?>) {
        val bytes = "event: message\ndata: ${engineJson.writeValueAsString(message)}\n\n".toByteArray()
        for (x in held.toList()) runCatching { x.responseBody.write(bytes); x.responseBody.flush() }.onFailure { held.remove(x) }
    }

    private fun stream(x: HttpExchange) {
        streams.incrementAndGet()
        if (noStream) return respond(x, 405, "")
        x.responseHeaders.add("Content-Type", "text/event-stream")
        x.sendResponseHeaders(200, 0)
        x.responseBody.write(": open\n\n".toByteArray()); x.responseBody.flush()
        held += x
    }

    fun calls(method: String) = calls.filter { it.method == method }

    fun reset() {
        calls.clear(); decided.clear(); handle = null; notify = false; noStream = false
    }

    fun stop() { drop(); server.stop(0) }

    private fun open(taskId: String) = mapOf("taskId" to taskId, "status" to "input_required", "pollInterval" to 10, "ttl" to 60000,
        "statusMessage" to "waiting on a human at $DECISION_BASE$taskId", "_meta" to mapOf("io.hitlp/decisionUrl" to "$DECISION_BASE$taskId"))

    @Suppress("UNCHECKED_CAST")
    private fun answer(x: HttpExchange) {
        val body: Map<String, Any?> = engineJson.readValue(x.requestBody.readBytes())
        val method = body["method"].toString()
        val params = body["params"] as? Map<String, Any?> ?: emptyMap()
        calls += Call(method, params, x.requestHeaders.getFirst("Authorization"))
        val result: Any? = when (method) {
            "initialize" -> {
                x.responseHeaders.add("Mcp-Session-Id", "stub-session")
                mapOf("protocolVersion" to HitlpRequester.PROTOCOL_VERSION, "capabilities" to mapOf("tasks" to emptyMap<String, Any?>()), "serverInfo" to mapOf("name" to "stub"))
            }
            "notifications/initialized" -> return respond(x, 202, "")
            "tools/call" -> mapOf("task" to open("t-${ids.incrementAndGet()}") + (handle ?: emptyMap()).also { handle = null })
            "tasks/get" -> {
                val id = params["taskId"].toString()
                decided[id]?.let { (status, record) -> open(id) - "_meta" + ("status" to status) + ("result" to mapOf("structuredContent" to record)) } ?: open(id)
            }
            "tasks/result" -> decided[params["taskId"].toString()]?.let { mapOf("structuredContent" to it.second) }
                ?: return respond(x, 200, engineJson.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to body["id"],
                    "error" to mapOf("code" to -32602, "message" to "task ${params["taskId"]} is not terminal"))))
            "tasks/cancel" -> open(params["taskId"].toString()) - "_meta" + ("status" to HitlpPrimitives.CANCELLED)
            else -> return respond(x, 200, engineJson.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to body["id"],
                "error" to mapOf("code" to -32601, "message" to "no such method $method"))))
        }
        respond(x, 200, engineJson.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to body["id"], "result" to result)))
    }

    private fun respond(x: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        x.responseHeaders.add("Content-Type", "application/json")
        x.sendResponseHeaders(status, if (bytes.isEmpty()) -1L else bytes.size.toLong())
        if (bytes.isNotEmpty()) x.responseBody.use { it.write(bytes) } else x.responseBody.close()
    }
}

/**
 * Decides flow [processInstance]'s one open HITLP wait on [stub] as [by] would on the HITLP server (#2473: the engine's
 * own answer path is gone), then one [poller] pass past it; the caller runs the flow's jobs. An ask is answered with
 * [answer]; an approve (a merge or legacy acceptance) is `approved` when [answer] starts with `accept`, else `rejected`
 * with [answer] as its reason. No `channel`, so the issue reads `@<by> answered:`.
 */
fun decideWait(stub: StubHitlpServer, requests: HitlpRequests, poller: HitlpPoller, processInstance: String, answer: String,
               by: String = "lundmatt1") {
    val open = requests.openFor(processInstance)
    val request = checkNotNull(open.singleOrNull()) { "flow $processInstance has ${open.size} open HITLP requests" }
    val decidedBy = mapOf("type" to "human", "id" to by)
    val record = if (request.kind == HitlpAsserted.Kind.MERGE.name || request.kind == HitlpAsserted.Kind.ACCEPTANCE.name) {
        val approved = answer.trim().lowercase().startsWith("accept")
        approveRecord(request.taskId, if (approved) "approved" else "rejected", answer.takeUnless { approved }, decidedBy) - "channel"
    } else askRecord(request.taskId, answer, decidedBy, channel = null)
    stub.decide(request.taskId, record)
    poller.poll(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(1))
}

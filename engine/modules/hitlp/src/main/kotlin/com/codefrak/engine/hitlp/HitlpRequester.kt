package com.codefrak.engine.hitlp

import com.codefrak.engine.engineJson
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The engine as a HITLP requester (#2447, part 1 of the plan in #2443; `spec/hitlp.md` §7): an MCP Streamable HTTP
 * client to the HITLP server whose MCP URL and bearer token [HitlpServerSettings] holds (#2500: set through
 * `PUT /settings/hitlp`, read from [source] at each call, so a change needs no restart). Four methods, each one JSON-RPC request over POST: [call] is a
 * `tools/call` of a `human.*` tool augmented with `task: {ttl}` (the MCP Tasks extension), answering the Task handle
 * at once; [get] is `tasks/get`, the handle again with its `result` (the §6 decision record) once terminal (§7.4):
 * the engine's own server answers the result inline in `tasks/get`, while a server that follows the MCP Tasks
 * extension (the reference server, `codefrak-oss/hitlp` `server/`) answers it only from `tasks/result`, so a terminal
 * handle with no result is completed from `tasks/result`; [result] is `tasks/result`; [cancel] is `tasks/cancel`. [openStream] opens the session's standalone SSE stream, where the
 * server sends `notifications/tasks/status` ([HitlpTaskStream], #2463).
 *
 * The session is the server's: an `initialize` once per server URL (a session made against another URL is dropped when
 * the setting changes), then `notifications/initialized`, the
 * `mcp-session-id` the server answered kept and sent on every later request with the protocol version the server
 * answered to `initialize` (the negotiated one, which may be older than [PROTOCOL_VERSION]: an MCP server refuses a
 * later request naming a version it does not support), and a 404 (the server forgot the session)
 * answered by initializing again once and retrying the request. Every request sends `Accept: application/json,
 * text/event-stream`; a reply on an SSE stream is read to its first `message` event, whose `data` is the JSON-RPC
 * reply. Connect and request timeouts are `engine.hitlp.timeout` (PT10S). Nothing here files a request on its own: part
 * 2 wires the flow waits through [call], with [HitlpAsserted]'s `asserted` block, and keeps the handle in
 * [HitlpRequests] (R3).
 *
 * No server (the row has no MCP URL): [call], [get] and [cancel] throw an [IllegalStateException] naming
 * `PUT /settings/hitlp`, so a flow wait that reaches them with no server configured fails on the spot rather than hangs.
 */
@Component
class HitlpRequester(
    private val source: HitlpEndpointSource,
    @Value("\${engine.hitlp.timeout}") private val timeout: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(timeout).build() }
    private val ids = AtomicLong(1)
    private val lock = Any()
    @Volatile private var sessionId: String? = null
    /** The URL [sessionId] was initialized against: a session is the server's, so another URL drops it. */
    @Volatile private var sessionUrl: String? = null
    /** The protocol version the server answered to `initialize`, sent on every later request. */
    @Volatile private var negotiated: String = PROTOCOL_VERSION

    /** A JSON-RPC error the server answered: its [code] and message. */
    class RpcError(val code: Int, message: String) : RuntimeException("hitlp server: $code $message")

    companion object {
        const val PROTOCOL_VERSION = "2026-07-28"
        const val SESSION_HEADER = "mcp-session-id"
        const val CLIENT_NAME = "frakworks-engine"
        const val UNSET = "the HITLP server is not configured: set its MCP URL and bearer token with PUT /settings/hitlp {mcpUrl, requesterToken}"
    }

    /** Whether a server is configured now (the settings row has an MCP URL); [call], [get] and [cancel] throw when it is not. */
    val configured: Boolean get() = source.endpoint() != null

    /** The configured server's MCP URL, or null; for log lines. */
    val url: String? get() = source.endpoint()?.url

    /** The server as set now, or the [UNSET] failure; a session made against another URL is dropped first. */
    private fun endpoint(): HitlpEndpoint {
        val endpoint = source.endpoint() ?: throw IllegalStateException(UNSET)
        synchronized(lock) { if (sessionId != null && sessionUrl != endpoint.url) { sessionId = null; sessionUrl = null } }
        return endpoint
    }

    /**
     * Files [args] as a `tools/call` of [tool] (`human.ask` or `human.approve`) with `task: {ttl: [ttlMs]}`; the Task
     * handle the server answered at once (§7.2). [args] is the request as the engine sends it: a caller validates it with
     * [HitlpSchemas.requesterProblem] first.
     */
    fun call(tool: String, args: Map<String, Any?>, ttlMs: Long): HitlpHandle {
        val reply = rpc("tools/call", mapOf("name" to tool, "arguments" to args, "task" to mapOf("ttl" to ttlMs)))
        val task = reply["task"] as? Map<*, *> ?: reply
        return HitlpHandle.of(task)
    }

    /**
     * The Task [taskId] as it is now (`tasks/get`), its `result` set once terminal (§7.4): when the server answers a
     * terminal task without one (the MCP Tasks extension keeps it for `tasks/result`), it is read from `tasks/result`.
     */
    fun get(taskId: String): HitlpHandle {
        val handle = HitlpHandle.of(rpc("tasks/get", mapOf("taskId" to taskId)))
        if (!handle.terminal || handle.result != null) return handle
        return handle.copy(result = rpc("tasks/result", mapOf("taskId" to taskId)))
    }

    /** The result of the terminal task [taskId] (`tasks/result`): its `structuredContent` is the §6 decision record. */
    fun result(taskId: String): Map<String, Any?> = rpc("tasks/result", mapOf("taskId" to taskId))

    /**
     * Opens the session's standalone SSE stream (#2463): a GET on the MCP endpoint with the session id and `Accept:
     * text/event-stream`, no request timeout, the session made first and remade once on a 404. Null when the server
     * answers 405 (it offers no stream); an [IllegalStateException] on any other non-2xx status. The caller reads it
     * with [HitlpSseStream.each] and closes it to drop the connection.
     */
    fun openStream(): HitlpSseStream? {
        val server = endpoint()
        var session = sessionId ?: initialize(server)
        var response = streamGet(server, session)
        if (response.statusCode() == 404) {
            response.body().close()
            log.info("hitlp server forgot session {}: initializing again", session)
            synchronized(lock) { if (sessionId == session) sessionId = null }
            session = sessionId ?: initialize(server)
            response = streamGet(server, session)
        }
        if (response.statusCode() == 405) { response.body().close(); return null }
        if (response.statusCode() / 100 != 2) {
            response.body().close()
            throw IllegalStateException("hitlp server: the stream GET answered ${response.statusCode()}")
        }
        return HitlpSseStream(response.body())
    }

    private fun streamGet(server: HitlpEndpoint, session: String): HttpResponse<java.util.stream.Stream<String>> {
        val request = HttpRequest.newBuilder(URI(server.url)).GET()
            .header("Authorization", "Bearer ${server.token}")
            .header("Accept", "text/event-stream")
            .header("MCP-Protocol-Version", negotiated)
        if (session.isNotEmpty()) request.header(SESSION_HEADER, session)
        val pending = http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofLines())
        try {
            return pending.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            // Stopped mid-GET: the client still sends it, so close the stream it opens rather than leave one on the
            // server that nobody reads.
            runCatching { pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS).body().close() }
            throw e
        }
    }

    /** Cancels [taskId] (`tasks/cancel`); the Task as the server answers it. */
    fun cancel(taskId: String): HitlpHandle = HitlpHandle.of(rpc("tasks/cancel", mapOf("taskId" to taskId)))

    /** One request of [method]: the session made first when there is none, remade once on a 404; the reply's `result`. */
    private fun rpc(method: String, params: Map<String, Any?>): Map<String, Any?> {
        val server = endpoint()
        var session = sessionId ?: initialize(server)
        var response = post(server, mapOf("jsonrpc" to "2.0", "id" to ids.getAndIncrement(), "method" to method, "params" to params), session)
        if (response.statusCode() == 404) {
            log.info("hitlp server forgot session {}: initializing again", session)
            synchronized(lock) { if (sessionId == session) sessionId = null }
            session = sessionId ?: initialize(server)
            response = post(server, mapOf("jsonrpc" to "2.0", "id" to ids.getAndIncrement(), "method" to method, "params" to params), session)
        }
        return result(response, method)
    }

    /** The handshake: `initialize`, the session id kept, `notifications/initialized`; the session id (empty when the server runs without one). */
    private fun initialize(server: HitlpEndpoint): String = synchronized(lock) {
        sessionId?.let { return it }
        val init = post(server, mapOf("jsonrpc" to "2.0", "id" to ids.getAndIncrement(), "method" to "initialize", "params" to mapOf(
            "protocolVersion" to PROTOCOL_VERSION,
            "capabilities" to mapOf("tasks" to mapOf("requests" to mapOf("tools" to mapOf("call" to emptyMap<String, Any?>())))),
            "clientInfo" to mapOf("name" to CLIENT_NAME, "version" to "1"))), null)
        negotiated = result(init, "initialize")["protocolVersion"]?.toString() ?: PROTOCOL_VERSION
        val session = init.headers().firstValue(SESSION_HEADER).orElse("")
        val initialized = post(server, mapOf("jsonrpc" to "2.0", "method" to "notifications/initialized"), session)
        if (initialized.statusCode() / 100 != 2) throw IllegalStateException("hitlp server: notifications/initialized answered ${initialized.statusCode()}")
        sessionId = session
        sessionUrl = server.url
        session
    }

    private fun post(server: HitlpEndpoint, body: Map<String, Any?>, session: String?): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI(server.url)).timeout(timeout)
            .header("Authorization", "Bearer ${server.token}")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", negotiated)
        if (!session.isNullOrEmpty()) request.header(SESSION_HEADER, session)
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(engineJson.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString())
    }

    /** The JSON-RPC reply's `result`; an [RpcError] on an `error`, an [IllegalStateException] on any other status. */
    private fun result(response: HttpResponse<String>, method: String): Map<String, Any?> {
        if (response.statusCode() / 100 != 2) throw IllegalStateException("hitlp server: $method answered ${response.statusCode()}: ${response.body().take(300)}")
        val contentType = response.headers().firstValue("Content-Type").orElse("")
        val text = if (contentType.startsWith("text/event-stream")) firstMessage(response.body()) else response.body()
        val reply: Map<String, Any?> = engineJson.readValue(text)
        (reply["error"] as? Map<*, *>)?.let { throw RpcError((it["code"] as? Number)?.toInt() ?: -1, it["message"]?.toString() ?: "") }
        @Suppress("UNCHECKED_CAST")
        return reply["result"] as? Map<String, Any?> ?: throw IllegalStateException("hitlp server: $method answered no result: ${text.take(300)}")
    }

    /** The `data` of the first `message` event of an SSE [stream] (an event without a name is a `message`), its lines joined. */
    internal fun firstMessage(stream: String): String {
        var event = "message"
        val data = StringBuilder()
        for (raw in stream.lines() + "") {
            val line = raw.trimEnd('\r')
            if (line.isEmpty()) {
                if (data.isNotEmpty() && event == "message") return data.toString()
                event = "message"; data.setLength(0); continue
            }
            if (line.startsWith(":")) continue
            val field = line.substringBefore(":"); val value = line.substringAfter(":", "").removePrefix(" ")
            when (field) {
                "event" -> event = value
                "data" -> { if (data.isNotEmpty()) data.append('\n'); data.append(value) }
            }
        }
        throw IllegalStateException("hitlp server: an SSE reply with no message event")
    }
}

/**
 * The session's standalone SSE stream, open ([HitlpRequester.openStream]): [each] hands every `message` event's
 * `data`, read as a JSON-RPC message, to its callback until the server ends the stream; [close] drops it (from any
 * thread: a blocked [each] then returns or throws).
 */
class HitlpSseStream(private val lines: java.util.stream.Stream<String>) : AutoCloseable {
    @Suppress("UNCHECKED_CAST")
    fun each(onMessage: (Map<String, Any?>) -> Unit) {
        var event = "message"
        val data = StringBuilder()
        for (raw in lines.iterator()) {
            val line = raw.trimEnd('\r')
            if (line.isEmpty()) {
                if (data.isNotEmpty() && event == "message") onMessage(engineJson.readValue<Map<String, Any?>>(data.toString()))
                event = "message"; data.setLength(0); continue
            }
            if (line.startsWith(":")) continue
            val field = line.substringBefore(":"); val value = line.substringAfter(":", "").removePrefix(" ")
            when (field) {
                "event" -> event = value
                "data" -> { if (data.isNotEmpty()) data.append('\n'); data.append(value) }
            }
        }
    }

    override fun close() = lines.close()
}

/**
 * A Task handle as the HITLP server answers it (MCP Tasks; `spec/hitlp.md` §7.2, §7.3): [taskId], [status], how
 * often to poll ([pollInterval], ms), the effective deadline ([ttl], as the server gives it; the engine's own server
 * answers the milliseconds left), the `statusMessage`, `_meta` (`io.hitlp/decisionUrl` when `input_required`) and, on
 * a `tasks/get` of a terminal task, [result] (its `structuredContent` is the §6 decision record: [record]).
 */
data class HitlpHandle(
    val taskId: String,
    val status: String,
    val pollInterval: Long,
    val ttl: Long?,
    val statusMessage: String?,
    val meta: Map<String, Any?>?,
    val result: Map<String, Any?>?,
) {
    val terminal: Boolean get() = status in HitlpPrimitives.TERMINAL

    /** The decision record of a terminal task: `result.structuredContent`, or `result` itself when the server answers the record bare. */
    @Suppress("UNCHECKED_CAST")
    val record: Map<String, Any?>? get() = (result?.get("structuredContent") as? Map<String, Any?>) ?: result?.takeIf { "outcome" in it }

    companion object {
        /** The default poll interval when a handle names none: five seconds. */
        const val DEFAULT_POLL_INTERVAL = 5000L

        @Suppress("UNCHECKED_CAST")
        fun of(task: Map<*, *>): HitlpHandle = HitlpHandle(
            taskId = task["taskId"]?.toString() ?: throw IllegalStateException("hitlp server: a task without taskId: $task"),
            status = task["status"]?.toString() ?: throw IllegalStateException("hitlp server: a task without status: $task"),
            pollInterval = (task["pollInterval"] as? Number)?.toLong() ?: DEFAULT_POLL_INTERVAL,
            ttl = (task["ttl"] as? Number)?.toLong(),
            statusMessage = task["statusMessage"]?.toString(),
            meta = task["_meta"] as? Map<String, Any?>,
            result = task["result"] as? Map<String, Any?>,
        )
    }
}

/** The HITLP primitives and Task statuses a requester reads (`spec/hitlp.md` §4, §7.2). */
object HitlpPrimitives {
    const val ASK = "ask"
    const val APPROVE = "approve"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    val TERMINAL = listOf(COMPLETED, FAILED, CANCELLED)
}

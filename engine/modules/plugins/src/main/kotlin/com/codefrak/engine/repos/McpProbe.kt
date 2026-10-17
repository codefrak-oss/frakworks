package com.codefrak.engine.repos

import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A call of `method` with `params` under `id` on an open MCP session, its `result`. */
private typealias Call = (Int, String, Map<String, Any?>) -> Map<*, *>

/** One tool as `tools/list` describes it: its name, description and `inputSchema` (JSON Schema, as the server sent it). */
data class McpToolSpec(val name: String, val description: String? = null, val inputSchema: Map<String, Any?>? = null,
                       /** The MCP `outputSchema`, when the server declares its answer's shape. */
                       val outputSchema: Map<String, Any?>? = null)

/**
 * What a test of a server answers: whether it answered `initialize` and `tools/list`, the tools it listed (names, and
 * [toolSpecs] with each one's description and input schema), or the error. For a row with a role, [contract] says
 * whether the tools conform to the role's contract ([OpsContract.conformance]); null for a plain row.
 */
data class McpProbeResult(val reached: Boolean, val tools: List<String>, val error: String?,
                          val toolSpecs: List<McpToolSpec> = emptyList(),
                          @get:JsonInclude(JsonInclude.Include.NON_NULL) val contract: OpsContract.Conformance? = null)

/**
 * Tests a First mate MCP server from the engine (#1801; engine/README.md, "First mate's MCP servers"): an MCP
 * `initialize`, `notifications/initialized` and `tools/list`. An `http` server is spoken to as streamable HTTP (each
 * message POSTed, the answer JSON or an event stream, `Mcp-Session-Id` carried on); an `sse` server as the older
 * HTTP+SSE transport (a GET opens the stream, its `endpoint` event names where to POST, the answers come down the
 * stream). A `stdio` server runs on the user's machine: the desktop tests it, the engine cannot. [call] does the same
 * handshake and then one `tools/call`, for a server the engine uses itself (an ops server, [OpsContract]).
 */
@Component
class McpProbe {
    /** How long each step may take; a test shortens it. */
    var timeout: Duration = Duration.ofSeconds(10)

    companion object {
        const val STDIO = "a stdio server runs on the user's machine: the engine cannot test it from the cluster, the desktop does"
        private val JSON = jacksonObjectMapper()
        private const val PROTOCOL = "2025-03-26"
    }

    private val client by lazy { HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build() }

    fun test(server: McpServerConfig): McpProbeResult = try {
        when (server.kind) {
            McpServerKind.STDIO -> McpProbeResult(false, emptyList(), STDIO,
                contract = if (server.role == McpRole.OPS) OpsContract.conformance(emptyList()) else null)
            else -> {
                val specs = session(server) { call -> toolSpecs(call(2, "tools/list", emptyMap())) }
                McpProbeResult(true, specs.map { it.name }, null, specs,
                    if (server.role == McpRole.OPS) OpsContract.conformance(specs) else null)
            }
        }
    } catch (e: Exception) {
        McpProbeResult(false, emptyList(), e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
    }

    /**
     * One `tools/call` of [tool] with [args] after the handshake: the call's `result` (`content`, `structuredContent`,
     * `isError`). Throws on a stdio server, an unreachable one or a JSON-RPC error, naming the step.
     */
    fun call(server: McpServerConfig, tool: String, args: Map<String, Any?>): Map<*, *> {
        if (server.kind == McpServerKind.STDIO) throw IllegalStateException(STDIO)
        return session(server) { call -> call(2, "tools/call", mapOf("name" to tool, "arguments" to args)) }
    }

    /** An `initialize`, `notifications/initialized`, then [body] with the session's call. */
    private fun <T> session(server: McpServerConfig, body: (Call) -> T): T = when (server.kind) {
        McpServerKind.HTTP -> http(server.url!!, server.headers, body)
        McpServerKind.SSE -> sse(server.url!!, server.headers, body)
        McpServerKind.STDIO -> throw IllegalStateException(STDIO)
    }

    private fun message(id: Int?, method: String, params: Map<String, Any?> = emptyMap()): String = JSON.writeValueAsString(
        linkedMapOf<String, Any?>("jsonrpc" to "2.0").apply { if (id != null) put("id", id); put("method", method); put("params", params) })

    private val initParams = mapOf("protocolVersion" to PROTOCOL, "capabilities" to emptyMap<String, Any?>(),
        "clientInfo" to mapOf("name" to "frakworks-engine", "version" to "1"))

    private fun post(url: String, headers: Map<String, String>, body: String, session: String?): HttpResponse<String> {
        val req = HttpRequest.newBuilder(URI(url)).timeout(timeout)
            .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
            .apply { headers.forEach { (k, v) -> header(k, v) }; if (session != null) header("Mcp-Session-Id", session) }
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    /** The JSON-RPC answer with this id in a response body: plain JSON, or the `data:` lines of an event stream. */
    private fun answer(response: HttpResponse<String>, id: Int): Map<String, Any?> {
        if (response.statusCode() !in 200..299) throw IllegalStateException("HTTP ${response.statusCode()}: ${response.body().take(300)}")
        val type = response.headers().firstValue("Content-Type").orElse("")
        val bodies = if (type.startsWith("text/event-stream")) events(response.body().lines()).map { it.second } else listOf(response.body())
        return bodies.mapNotNull { runCatching { JSON.readValue<Map<String, Any?>>(it) }.getOrNull() }.firstOrNull { (it["id"] as? Number)?.toInt() == id }
            ?: throw IllegalStateException("no answer to request $id")
    }

    /** An event stream's events, (event, data). */
    private fun events(lines: List<String>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>(); var event = "message"; val data = StringBuilder()
        for (l in lines + "") when {
            l.isEmpty() -> { if (data.isNotEmpty()) out += event to data.toString().trimEnd('\n'); event = "message"; data.clear() }
            l.startsWith("event:") -> event = l.removePrefix("event:").trim()
            l.startsWith("data:") -> data.append(l.removePrefix("data:").removePrefix(" ")).append('\n')
        }
        return out
    }

    private fun result(answer: Map<String, Any?>, what: String): Map<*, *> {
        (answer["error"] as? Map<*, *>)?.let { throw IllegalStateException("$what: ${it["message"] ?: it}") }
        return answer["result"] as? Map<*, *> ?: throw IllegalStateException("$what: no result")
    }

    @Suppress("UNCHECKED_CAST")
    private fun toolSpecs(result: Map<*, *>): List<McpToolSpec> = (result["tools"] as? List<*>).orEmpty().mapNotNull { t ->
        val m = t as? Map<*, *> ?: return@mapNotNull null
        val name = m["name"] as? String ?: return@mapNotNull null
        McpToolSpec(name, m["description"] as? String, m["inputSchema"] as? Map<String, Any?>, m["outputSchema"] as? Map<String, Any?>)
    }

    private fun <T> http(url: String, headers: Map<String, String>, body: (Call) -> T): T {
        val init = post(url, headers, message(1, "initialize", initParams), null)
        val session = init.headers().firstValue("Mcp-Session-Id").orElse(null)
        result(answer(init, 1), "initialize")
        post(url, headers, message(null, "notifications/initialized"), session)
        return body { id, method, params -> result(answer(post(url, headers, message(id, method, params), session), id), method) }
    }

    private fun <T> sse(url: String, headers: Map<String, String>, body: (Call) -> T): T {
        val req = HttpRequest.newBuilder(URI(url)).header("Accept", "text/event-stream")
            .apply { headers.forEach { (k, v) -> header(k, v) } }.GET().build()
        val response = client.sendAsync(req, HttpResponse.BodyHandlers.ofLines()).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        if (response.statusCode() !in 200..299) throw IllegalStateException("HTTP ${response.statusCode()}")
        val queue = LinkedBlockingQueue<Pair<String, String>>()
        val reader = Thread {
            var event = "message"; val data = StringBuilder()
            try {
                response.body().forEach { l -> when {
                    l.isEmpty() -> { if (data.isNotEmpty()) queue.put(event to data.toString().trimEnd('\n')); event = "message"; data.clear() }
                    l.startsWith("event:") -> event = l.removePrefix("event:").trim()
                    l.startsWith("data:") -> data.append(l.removePrefix("data:").removePrefix(" ")).append('\n')
                } }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
        try {
            fun next(want: (Pair<String, String>) -> Boolean): Pair<String, String> {
                val end = System.nanoTime() + timeout.toNanos()
                while (true) {
                    val left = end - System.nanoTime()
                    val e = (if (left > 0) queue.poll(left, TimeUnit.NANOSECONDS) else null) ?: throw IllegalStateException("timed out waiting for the server")
                    if (want(e)) return e
                }
            }
            val endpoint = URI(url).resolve(next { it.first == "endpoint" }.second).toString()
            fun call(id: Int, method: String, params: Map<String, Any?>): Map<*, *> {
                val sent = post(endpoint, headers, message(id, method, params), null)
                if (sent.statusCode() !in 200..299) throw IllegalStateException("HTTP ${sent.statusCode()}: ${sent.body().take(300)}")
                val e = next { it.first == "message" && runCatching { (JSON.readValue<Map<String, Any?>>(it.second)["id"] as? Number)?.toInt() == id }.getOrDefault(false) }
                return result(JSON.readValue(e.second), method)
            }
            call(1, "initialize", initParams)
            post(endpoint, headers, message(null, "notifications/initialized"), null)
            return body(::call)
        } finally {
            reader.interrupt(); runCatching { response.body().close() }
        }
    }
}

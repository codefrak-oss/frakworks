package com.codefrak.engine.mcp

import com.codefrak.engine.SMITH_AT_ADDRESS_ATTRIBUTE
import com.codefrak.engine.BearerTokenFilter
import com.codefrak.engine.engineJson
import com.codefrak.engine.provenLogin
import com.codefrak.engine.provenTokenId
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.smith.SmithSandbox
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import tools.jackson.module.kotlin.readValue

/**
 * Serves every [EngineMcpServer] at `/mcp/<name>` (engine/README.md, "Engine-served MCP servers"):
 * MCP's streamable HTTP transport, stateless. Each POST carries one JSON-RPC message; a request
 * is answered with one `application/json` response, a notification with 202 and no body; a call to one of a server's
 * [EngineMcpServer.streamed] tools (await_children) that accepts `text/event-stream` is answered as an SSE stream with
 * progress ([McpStream]). No standalone SSE stream and no session: GET and DELETE are 405, which the transport allows. Unauthenticated, like
 * `POST /intake`: a `tools/call`'s caller is found by its source address ([McpCaller]), and each
 * server decides what that caller may do. `initialize` and `tools/list` answer anyone, so a probe works
 * (a server may list per role: frakworks lists a Smith only its `submit_ask`). A browser page must not
 * reach it, so a request with an `Origin` header is 403 (DNS-rebinding guard; Claude Code sends none).
 */
@RestController
class EngineMcpController(servers: List<EngineMcpServer>, private val sandbox: SmithSandbox, private val stream: McpStream = McpStream()) {
    private val log = LoggerFactory.getLogger(EngineMcpController::class.java)

    companion object {
        /** Newest first; an `initialize` asking for another gets the newest. */
        val PROTOCOL_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")
        /** A [TaskServer]'s: the Tasks extension's `2026-07-28` first. Only a task server offers it: a client that asks
         *  for a version we do not know refuses one it does not know either (Claude Code did, for frakworks). */
        val TASK_PROTOCOL_VERSIONS = listOf("2026-07-28") + PROTOCOL_VERSIONS
        /** What `initialize` advertises for a [TaskServer]: the Tasks extension, with `tools/call` task-augmentable. */
        val TASKS_CAPABILITY = mapOf("list" to emptyMap<String, Any>(), "cancel" to emptyMap<String, Any>(),
            "requests" to mapOf("tools" to mapOf("call" to emptyMap<String, Any>())))
        private val NAME = Regex("[a-z0-9-]+")
    }

    private val byName: Map<String, EngineMcpServer> = servers.onEach {
        require(NAME.matches(it.name)) { "an engine MCP server's name is [a-z0-9-]+, not '${it.name}'" }
    }.groupBy { it.name }.mapValues { (name, same) ->
        require(same.size == 1) { "two engine MCP servers are named '$name'" }
        same.single()
    }

    @PostMapping("/mcp/{server}")
    fun post(@PathVariable server: String, @RequestBody body: String, request: HttpServletRequest? = null,
             response: HttpServletResponse? = null): ResponseEntity<Any>? {
        val mcp = byName[server] ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no engine MCP server '$server'")
        if (request?.getHeader("Origin") != null) throw ResponseStatusException(HttpStatus.FORBIDDEN, "a request with an Origin header is refused")
        val msg = runCatching { engineJson.readValue<Any?>(body) }.getOrElse { return reply(null, error = -32700 to "parse error: ${it.message}") }
        if (msg !is Map<*, *> || msg["jsonrpc"] != "2.0" || msg["method"] !is String)
            return reply((msg as? Map<*, *>)?.get("id"), error = -32600 to "one JSON-RPC 2.0 request or notification per POST")
        val method = msg["method"] as String
        val params = msg["params"] as? Map<*, *> ?: emptyMap<Any, Any>()
        // No valid token and no Smith: every method is refused, `initialize` and `tools/list` included (BearerTokenFilter).
        (request?.getAttribute(BearerTokenFilter.REFUSAL_ATTRIBUTE) as? String)?.let { return reply(msg["id"], error = BearerTokenFilter.MCP_UNAUTHORIZED to it) }
        val id = msg["id"] ?: return ResponseEntity.accepted().build() // a notification
        return when (method) {
            "initialize" -> reply(id, mapOf(
                "protocolVersion" to (if (mcp is TaskServer) TASK_PROTOCOL_VERSIONS else PROTOCOL_VERSIONS).let { known ->
                    (params["protocolVersion"] as? String)?.takeIf { it in known } ?: known.first() },
                "capabilities" to buildMap {
                    put("tools", emptyMap<String, Any>())
                    if (mcp is TaskServer) put("tasks", TASKS_CAPABILITY)
                },
                "serverInfo" to mapOf("name" to mcp.name, "version" to mcp.version)))
            "ping" -> reply(id, emptyMap<String, Any>())
            "tools/list" -> reply(id, mapOf("tools" to mcp.tools(callerOf(request))))
            "tools/call" -> {
                val tool = params["name"] as? String
                val caller = callerOf(request)
                @Suppress("UNCHECKED_CAST")
                val args = (params["arguments"] as? Map<String, Any?>) ?: emptyMap()
                // A task server answers every call, listed or not, so an unlisted name gets its own refusal.
                if (mcp is TaskServer && tool in mcp.plainTools) return plain(id, "tools/call $tool") { mcp.callTask(tool!!, args, caller) }
                // Only a request with `task` is task-augmented; a client without the Tasks extension (Claude Code)
                // refuses a `{task}` result, so it gets the handle as a tool result.
                if (mcp is TaskServer && params["task"] != null) return task(id, "tools/call $tool") { mcp.callTask(tool ?: "", args, caller) }
                if (mcp is TaskServer) return plain(id, "tools/call $tool", refusalAsError = true) { mcp.callTask(tool ?: "", args, caller) }
                if (tool == null || mcp.tools(caller).none { it.name == tool }) return reply(id, error = -32602 to "unknown tool: ${engineJson.writeValueAsString(tool)}")
                if (tool in mcp.streamed && response != null && request?.getHeader("Accept")?.contains(McpStream.CONTENT_TYPE) == true) {
                    response.status = HttpStatus.OK.value()
                    response.contentType = McpStream.CONTENT_TYPE
                    response.characterEncoding = "UTF-8"
                    response.setHeader("Cache-Control", "no-cache")
                    response.setHeader("X-Accel-Buffering", "no")
                    val out = response.writer
                    val token = (params["_meta"] as? Map<*, *>)?.get("progressToken")
                    stream.run({ out.write(it); out.flush(); response.flushBuffer() }, id, token) { toolResult(mcp, tool, args, caller) }
                    return null
                }
                reply(id, toolResult(mcp, tool, args, caller))
            }
            "tasks/get", "tasks/list", "tasks/cancel" -> {
                if (mcp !is TaskServer) return reply(id, error = -32601 to "method not found: $method")
                val caller = callerOf(request)
                task(id, method) {
                    when (method) {
                        "tasks/list" -> mcp.listTasks(caller, params["cursor"] as? String)
                        else -> {
                            val taskId = (params["taskId"] as? String)?.takeIf { it.isNotBlank() }
                                ?: return@task TaskCall.Refused.invalid("$method needs a taskId")
                            if (method == "tasks/get") mcp.getTask(taskId, caller) else mcp.cancelTask(taskId, caller)
                        }
                    }
                }
            }
            else -> reply(id, error = -32601 to "method not found: $method")
        }
    }

    /** A plain `tools/call`'s `result`: a throw is an `isError` result, logged. */
    private fun toolResult(mcp: EngineMcpServer, tool: String, args: Map<String, Any?>, caller: McpCaller): Map<String, Any?> {
        val result = runCatching { mcp.call(tool, args, caller) }.getOrElse {
            log.warn("mcp {}: {} failed: {}", mcp.name, tool, it.message)
            McpToolResult.error(it.message ?: it.toString())
        }
        return buildMap {
            put("content", listOfNotNull(mapOf("type" to "text", "text" to result.text),
                result.image?.let { mapOf("type" to "image", "data" to it.data, "mimeType" to it.mimeType) }))
            if (result.isError) put("isError", true)
        }
    }

    /** A [TaskServer] method's answer as a JSON-RPC reply; a throw is `-32603`, logged, never a 500. */
    private fun task(id: Any?, what: String, call: () -> TaskCall): ResponseEntity<Any> =
        when (val r = runCatching(call).getOrElse { log.warn("mcp {} failed: {}", what, it.message); TaskCall.Refused(-32603, it.message ?: it.toString()) }) {
            is TaskCall.Ok -> reply(id, if (what.startsWith("tools/call")) mapOf("task" to r.result) else r.result)
            is TaskCall.Refused -> reply(id, error = r.code to r.message)
        }

    /** A [TaskServer] call's answer as a tool result: the object as text and as `structuredContent`; a refusal is
     *  `isError`, or with [refusalAsError] the JSON-RPC error a task-augmented call would get. */
    private fun plain(id: Any?, what: String, refusalAsError: Boolean = false, call: () -> TaskCall): ResponseEntity<Any> =
        when (val r = runCatching(call).getOrElse { log.warn("mcp {} failed: {}", what, it.message); TaskCall.Refused(-32603, it.message ?: it.toString()) }) {
            is TaskCall.Ok -> reply(id, mapOf("content" to listOf(mapOf("type" to "text", "text" to engineJson.writeValueAsString(r.result))),
                "structuredContent" to r.result))
            is TaskCall.Refused -> if (refusalAsError) reply(id, error = r.code to r.message)
                else reply(id, mapOf("content" to listOf(mapOf("type" to "text", "text" to r.message)), "isError" to true))
        }

    /** No SSE stream to open and no session to end. */
    @GetMapping("/mcp/{server}")
    fun get(@PathVariable server: String): ResponseEntity<Any> = notAllowed(server)

    @DeleteMapping("/mcp/{server}")
    fun delete(@PathVariable server: String): ResponseEntity<Any> = notAllowed(server)

    private fun notAllowed(server: String): ResponseEntity<Any> {
        if (server !in byName) throw ResponseStatusException(HttpStatus.NOT_FOUND, "no engine MCP server '$server'")
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header("Allow", "POST").build()
    }

    /**
     * The caller by source address; a provider that cannot be asked is [McpCaller.Unknown], never [McpCaller.Nobody].
     * A [McpCaller.Nobody]'s login is the one its bearer token proved ([BearerTokenFilter], which also leaves the
     * `SmithCaller` it found so the address is looked up once); it is read only for a Nobody, so a Smith that sends a
     * token is still that Smith, with no login.
     */
    private fun callerOf(request: HttpServletRequest?): McpCaller {
        val address = request?.remoteAddr ?: return McpCaller.Nobody(null)
        (request.getAttribute(SMITH_AT_ADDRESS_ATTRIBUTE) as? SmithCaller)?.let { return McpCaller.Smith(it, request) }
        return try {
            sandbox.runAt(address)?.let { McpCaller.Smith(it, request) }
                ?: McpCaller.Nobody(address, request.provenLogin(), request, request.provenTokenId())
        } catch (e: Exception) {
            log.warn("could not tell whether {} is a Smith sandbox: {}", address, e.message)
            McpCaller.Unknown(address, e.message, request)
        }
    }

    private fun reply(id: Any?, result: Any? = null, error: Pair<Int, String>? = null): ResponseEntity<Any> {
        val body = buildMap<String, Any?> {
            put("jsonrpc", "2.0"); put("id", id)
            if (error != null) put("error", mapOf("code" to error.first, "message" to error.second)) else put("result", result)
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body)
    }
}

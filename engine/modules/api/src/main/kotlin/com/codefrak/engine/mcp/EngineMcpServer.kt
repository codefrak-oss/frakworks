package com.codefrak.engine.mcp

import com.codefrak.engine.engineJson
import com.codefrak.engine.smith.SmithCaller
import jakarta.servlet.http.HttpServletRequest

/**
 * One MCP server the engine serves (engine/README.md, "Engine-served MCP servers"), at
 * `POST /mcp/<name>` on the engine's own port. A new server is one more `@Component`
 * implementing this: [EngineMcpController] finds it by [name], and no other server changes.
 */
interface EngineMcpServer {
    /** The `serverInfo.name` `initialize` reports, and the path segment: `[a-z0-9-]+`, unique. */
    val name: String
    val version: String
    /** What `tools/list` lists. */
    fun tools(): List<McpTool>
    /** What `tools/list` lists for [caller], and what it may call: [tools] unless a server lists per role. */
    fun tools(caller: McpCaller): List<McpTool> = tools()
    /**
     * Runs [tool] (one [tools] lists) with [args], the call's `arguments`, for [caller]. A refusal
     * or a failure is an [McpToolResult] with `isError`, never a throw.
     */
    fun call(tool: String, args: Map<String, Any?>, caller: McpCaller): McpToolResult
    /**
     * Tools whose [call] blocks for minutes (await_children): [EngineMcpController] answers one as an SSE stream, its
     * first bytes at once and a progress notification every [McpStream.PROGRESS_INTERVAL], so a client's idle timeout
     * never ends the call, when the request accepts `text/event-stream`.
     */
    val streamed: Set<String> get() = emptySet()
}

/**
 * An [EngineMcpServer] that speaks the MCP Tasks extension (`io.modelcontextprotocol/tasks`, protocol `2026-07-28`;
 * engine/README.md, "The judge server"): [EngineMcpController] advertises the `tasks` capability for it, sends its
 * `tools/call` to [callTask] (answered with a Task handle, not a tool result) and routes `tasks/get`, `tasks/list` and
 * `tasks/cancel` to it; on any other server those three are `-32601`. [EngineMcpServer.call] is never reached for one.
 * Every method takes the caller, so a server checks it on each (HITLP rule R5).
 */
interface TaskServer : EngineMcpServer {
    override fun call(tool: String, args: Map<String, Any?>, caller: McpCaller): McpToolResult =
        McpToolResult.error("$name answers tools/call with a task, not a tool result")
    /** `tools/call`: [TaskCall.Ok] carries the Task object (the result is `{"task": it}`). */
    fun callTask(tool: String, args: Map<String, Any?>, caller: McpCaller): TaskCall
    /** Tools whose `tools/call` [callTask] answers as a plain tool result (`content` + `structuredContent`), not `{"task": ...}`:
     *  for a client without the Tasks extension (the judge's `judge.command`). */
    val plainTools: Set<String> get() = emptySet()
    /** `tasks/get`: the Task object, with its result inline when terminal. */
    fun getTask(taskId: String, caller: McpCaller): TaskCall
    /** `tasks/list`: `{"tasks": [...]}`, the caller's own only. */
    fun listTasks(caller: McpCaller, cursor: String?): TaskCall
    /** `tasks/cancel`: the Task object as it is after the cancel. */
    fun cancelTask(taskId: String, caller: McpCaller): TaskCall
}

/** A [TaskServer] method's answer: the JSON-RPC `result`, or a JSON-RPC error with its [Refused.code]. */
sealed interface TaskCall {
    data class Ok(val result: Map<String, Any?>) : TaskCall
    /** `-32602` for a bad argument or a task the caller may not see; [REFUSED] for a caller the server will not serve. */
    data class Refused(val code: Int, val message: String) : TaskCall {
        companion object {
            /** A caller the server does not serve (no Smith, no trusted login): a server error in the implementation-defined range. */
            const val REFUSED = -32001
            const val INVALID_PARAMS = -32602
            fun invalid(message: String) = Refused(INVALID_PARAMS, message)
            fun caller(message: String) = Refused(REFUSED, message)
        }
    }
}

/** One tool, as `tools/list` gives it: `inputSchema` is a JSON Schema object. */
data class McpTool(val name: String, val description: String, val inputSchema: Map<String, Any?>)

/**
 * Who made a `tools/call`, as the engine found it by the request's source address, never as the
 * request says: a running Smith sandbox (`SmithSandbox.runAt`), [Nobody] known, or [Unknown] when
 * the sandbox provider could not be asked, which a server must refuse as it refuses [Nobody].
 * [Nobody]'s [Nobody.login] is the login a bearer token proved (engine/README.md, "Tokens"; then [Nobody.tokenId] is
 * that token's id), read only when the address is no Smith's; a request with no valid token never reaches a server
 * (`BearerTokenFilter`, `-32001`). [request] is
 * the HTTP request, so an engine call a tool makes in-process checks the same address again.
 */
sealed interface McpCaller {
    val request: HttpServletRequest?
    data class Smith(val caller: SmithCaller, override val request: HttpServletRequest? = null) : McpCaller
    data class Nobody(val address: String?, val login: String? = null, override val request: HttpServletRequest? = null, val tokenId: String? = null) : McpCaller
    data class Unknown(val address: String?, val why: String?, override val request: HttpServletRequest? = null) : McpCaller
}

/** The JSON mapper the engine's MCP servers read and write with. */
val mcpJson = engineJson

/** [v] as JSON text, with [mcpJson]. */
fun json(v: Any?) = mcpJson.writeValueAsString(v)
/** [keys] each as JSON text, comma-joined. */
fun quoted(keys: Collection<String>) = keys.joinToString(", ") { json(it) }

/** A `tools/call` result: one text block, JSON as the desktop's frakworks server writes it. */
/** An image content block: base64 [data] of type [mimeType]. */
data class McpImage(val data: String, val mimeType: String)

/** A tool's result: its text, and an [image] block after it when there is one (read_attachment's). */
data class McpToolResult(val text: String, val isError: Boolean = false, val image: McpImage? = null) {
    companion object {
        private val json = engineJson
        /** [value] as JSON text. */
        fun of(value: Any?, isError: Boolean = false) = McpToolResult(json.writeValueAsString(value), isError)
        /** `{"error": why, ...more}`, an error. */
        fun error(why: String, more: Map<String, Any?> = emptyMap()) = of(mapOf("error" to why) + more, true)
    }
}

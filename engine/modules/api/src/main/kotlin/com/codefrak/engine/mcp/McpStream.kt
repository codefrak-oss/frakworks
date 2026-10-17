package com.codefrak.engine.mcp

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * One streamed `tools/call` (#2571; engine/README.md, "Engine-served MCP servers"): the answer to a tool that blocks for
 * minutes (await_children), as MCP's streamable HTTP allows a POST to be answered, `text/event-stream`. Claude Code gives
 * an MCP call 60 s to its first byte and gives up on 5 min with none, so [run] writes a comment at once, then every
 * [interval] while the call runs a `notifications/progress` for the request's `progressToken` (an SSE comment when the
 * request sent none: the spec lets a server send progress only for a token it was given), and last the JSON-RPC
 * response as one `message` event. [waitFor] is how long it waits on the call each time: a test passes one on a
 * virtual clock.
 */
class McpStream(
    private val interval: Duration = PROGRESS_INTERVAL,
    private val waitFor: (Future<*>, Duration) -> Boolean = ::waitOn,
) {
    companion object {
        /** Half Claude Code's 60 s, so a late tick still lands well inside it. */
        val PROGRESS_INTERVAL: Duration = Duration.ofSeconds(30)
        const val CONTENT_TYPE = "text/event-stream"

        /** Whether the future finished within [timeout]; a failed one has finished too. */
        fun waitOn(future: Future<*>, timeout: Duration): Boolean = try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS); true
        } catch (e: TimeoutException) { false } catch (e: ExecutionException) { true }

        /** One SSE `message` event carrying [message] as JSON. */
        fun event(message: Any?): String = "event: message\ndata: ${json(message)}\n\n"

        private val threads = Executors.newVirtualThreadPerTaskExecutor()
    }

    /**
     * Runs [call] on a virtual thread and writes the stream to [write] (which flushes each piece): the opening comment,
     * the progress ticks, then `{jsonrpc, id, result}` with what [call] answered. A throw from [call] is its result, as
     * [EngineMcpController] makes one: never an unfinished stream.
     */
    fun run(write: (String) -> Unit, id: Any?, progressToken: Any?, call: () -> Map<String, Any?>) {
        write(": streaming\n\n")
        val future = CompletableFuture.supplyAsync({ call() }, threads)
        var ticks = 0
        while (!waitFor(future, interval)) {
            ticks++
            write(if (progressToken == null) ": waiting\n\n" else event(mapOf("jsonrpc" to "2.0", "method" to "notifications/progress",
                "params" to mapOf("progressToken" to progressToken, "progress" to ticks, "message" to "waiting"))))
        }
        val result = try { future.get() } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            mapOf("content" to listOf(mapOf("type" to "text", "text" to json(mapOf("error" to (cause.message ?: cause.toString()))))), "isError" to true)
        }
        write(event(mapOf("jsonrpc" to "2.0", "id" to id, "result" to result)))
    }
}

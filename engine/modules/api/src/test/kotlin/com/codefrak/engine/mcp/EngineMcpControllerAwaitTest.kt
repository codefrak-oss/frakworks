package com.codefrak.engine.mcp

import com.codefrak.engine.smith.SandboxStatus
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.smith.SmithSandbox
import com.codefrak.engine.smith.SmithSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Duration
import java.util.concurrent.CountDownLatch

private class NoSandbox : SmithSandbox {
    override fun start(spec: SmithSpec): String = error("unused")
    override fun status(handle: String): SandboxStatus = error("unused")
    override fun logs(handle: String, maxLines: Int): String = ""
    override fun output(handle: String, path: String): ByteArray? = null
    override fun remove(handle: String) {}
    override fun runAt(address: String): SmithCaller? = null
}

/** A server with one streamed tool, `slow`, that answers after [millis], and one plain tool, `echo`. */
private class SlowServer(val millis: Long) : EngineMcpServer {
    override val name = "slow"; override val version = "1"
    override val streamed = setOf("slow")
    override fun tools() = listOf(McpTool("slow", "slow", mapOf("type" to "object")), McpTool("echo", "echo", mapOf("type" to "object")))
    override fun call(tool: String, args: Map<String, Any?>, caller: McpCaller): McpToolResult {
        if (tool == "slow") Thread.sleep(millis)
        return McpToolResult.of(mapOf("changed" to false, "tool" to tool))
    }
}

/**
 * await_children's transport (#2571): a streamed tool is answered as SSE at once, with a progress tick every interval
 * and the result last ([McpStream]); 15 minutes on a virtual clock never leave the client 60 s without a byte.
 */
class EngineMcpControllerAwaitTest {
    private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(
        EngineMcpController(listOf(SlowServer(300)), NoSandbox(), McpStream(Duration.ofMillis(50)))).build()

    private fun call(tool: String, accept: String, meta: String = "") = mvc.post("/mcp/slow") {
        contentType = MediaType.APPLICATION_JSON; header("Accept", accept)
        content = """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"$tool","arguments":{}$meta}}"""
        with(RequestPostProcessor { it.remoteAddr = "127.0.0.1"; it })
    }.andReturn().response

    @Test
    fun `a streamed tool that accepts SSE gets a stream with progress for its token and the result last`() {
        val r = call("slow", "application/json, text/event-stream", ""","_meta":{"progressToken":"p-1"}""")
        assertEquals(200, r.status)
        assertTrue(r.contentType!!.startsWith("text/event-stream"), r.contentType)
        val body = r.contentAsString
        assertTrue(body.startsWith(": streaming\n\n"), body)
        assertTrue("\"method\":\"notifications/progress\"" in body && "\"progressToken\":\"p-1\"" in body, body)
        val last = body.trimEnd().lines().last()
        assertTrue(last.startsWith("data: ") && "\"id\":7" in last && "\\\"changed\\\":false" in last, last)
    }

    @Test
    fun `without a progress token the ticks are comments, and without SSE in Accept the answer is plain JSON`() {
        val sse = call("slow", "application/json, text/event-stream").contentAsString
        assertTrue(": waiting\n\n" in sse && "notifications/progress" !in sse, sse)
        val plain = call("slow", "application/json")
        assertTrue(plain.contentType!!.startsWith("application/json"))
        assertTrue(plain.contentAsString.startsWith("{\"jsonrpc\":\"2.0\",\"id\":7"), plain.contentAsString)
        assertTrue(call("echo", "application/json, text/event-stream").contentType!!.startsWith("application/json"), "only a streamed tool streams")
    }

    @Test
    fun `a 15-minute wait on a virtual clock gets its first byte at once and never goes 60 s without one`() {
        var now = Duration.ZERO
        val done = CountDownLatch(1)
        val at = mutableListOf<Pair<Duration, String>>()
        val stream = McpStream(McpStream.PROGRESS_INTERVAL) { future, interval ->
            now += interval
            if (now >= Duration.ofMinutes(15)) { done.countDown(); future.get(); true } else false
        }
        stream.run({ at += now to it }, 1, "tok") { done.await(); mapOf("changed" to false) }
        assertTrue(at.first().first < Duration.ofSeconds(60), "first byte at ${at.first().first}")
        val gaps = at.zipWithNext { a, b -> b.first - a.first }
        assertTrue(gaps.all { it <= Duration.ofSeconds(60) }, "gaps $gaps")
        assertTrue(gaps.none { it >= Duration.ofMinutes(5) })
        assertEquals(Duration.ofMinutes(15), at.last().first)
        assertTrue(at.dropLast(1).drop(1).all { "notifications/progress" in it.second })
        assertTrue("\"result\":{\"changed\":false}" in at.last().second, at.last().second)
    }
}

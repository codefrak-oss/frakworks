package com.codefrak.engine.summarize

import com.codefrak.engine.accounts.ClaudeTokens
import com.codefrak.engine.accounts.ClaudeUsage
import com.codefrak.engine.accounts.ClaudeUsageSink
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** HttpSessionSummarizer against a stub Anthropic `/v1/messages`. */
class SessionSummarizerTest {
    private val json = jacksonObjectMapper()

    /** Records each request's headers and body, answers [status] with [reply]. */
    private inner class StubAnthropic(var status: Int = 200, var reply: String = "") : AutoCloseable {
        val bodies = CopyOnWriteArrayList<Map<String, Any?>>()
        val headers = CopyOnWriteArrayList<Map<String, String>>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/messages") { ex ->
                bodies += json.readValue<Map<String, Any?>>(String(ex.requestBody.readAllBytes()))
                headers += ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }
                val out = reply.toByteArray()
                ex.sendResponseHeaders(status, out.size.toLong()); ex.responseBody.write(out); ex.close()
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
        override fun close() = server.stop(0)
    }

    private val stubs = mutableListOf<AutoCloseable>()
    @AfterEach fun stop() = stubs.forEach { it.close() }

    private fun stub(text: String, status: Int = 200) =
        StubAnthropic(status, json.writeValueAsString(mapOf("content" to listOf(mapOf("type" to "text", "text" to text))))).also { stubs += it }

    private fun summarizer(url: String, token: String? = "sk-ant-oat01-sum") = HttpSessionSummarizer({ token }, url, "claude-haiku-4-5")

    private fun words(s: String) = s.trim().split(Regex("\\s+")).size

    @Test
    fun `the request carries the prompt verbatim, the model, the digest and the OAuth headers`() {
        val api = stub("""{"summary":"It is storing sessions.","now":"Testing."}""")
        val summary = summarizer(api.url).summarize("Ticket acme/widgets#7, a work run.", "said: hello", "tool Bash: run tests")
        assertEquals(SessionSummary("It is storing sessions.", "Testing."), summary)

        val body = api.bodies.single()
        assertEquals("claude-haiku-4-5", body["model"])
        val system = (body["system"] as List<*>).map { (it as Map<*, *>)["text"] }
        assertEquals(listOf(HttpSessionSummarizer.CLAUDE_CODE, HttpSessionSummarizer.PROMPT), system)
        val user = ((body["messages"] as List<*>).single() as Map<*, *>)["content"] as String
        assertTrue(user.startsWith("Ticket acme/widgets#7, a work run.") && "said: hello" in user, user)

        assertTrue(user.endsWith("\n\n${HttpSessionSummarizer.RECENT}\ntool Bash: run tests"), user)

        val h = api.headers.single()
        assertEquals("Bearer sk-ant-oat01-sum", h["authorization"])
        assertEquals("oauth-2025-04-20", h["anthropic-beta"])
        assertEquals("2023-06-01", h["anthropic-version"])
    }

    /** engine/README.md, "Consumption ledger": the summarizer's call goes in the ledger with its model and tokens, no cost. */
    @Test
    fun `each summary is recorded in the ledger as the summarizer's with its model and tokens`() {
        val api = StubAnthropic(reply = json.writeValueAsString(mapOf(
            "content" to listOf(mapOf("type" to "text", "text" to """{"summary":"It works.","now":"Testing."}""")),
            "usage" to mapOf("input_tokens" to 900, "output_tokens" to 30, "cache_read_input_tokens" to 0, "cache_creation_input_tokens" to 5),
        ))).also { stubs += it }
        val recorded = mutableListOf<ClaudeUsage>()
        HttpSessionSummarizer({ "sk-ant-oat01-sum" }, api.url, "claude-haiku-4-5", ClaudeUsageSink { recorded += it }).summarize("c", "d", "r")
        val u = recorded.single()
        assertEquals("summarizer", u.caller); assertEquals("claude-haiku-4-5", u.model)
        assertEquals(ClaudeTokens(900, 30, 0, 5), u.tokens); assertEquals(null, u.costUsd)
    }

    @Test
    fun `the prompt asks for a high-level explanation, no file names or edits, and the two word limits`() {
        val p = HttpSessionSummarizer.PROMPT
        assertTrue("high level" in p)
        assertTrue("Never list file names, paths, functions or individual edits" in p)
        assertTrue("at most 20 words" in p && "fewer than 15 words" in p)
    }

    @Test
    fun `the most recent turns section holds the digest's last turns`() {
        val lines = (1..6).map { """{"type":"assistant","message":{"content":[{"type":"text","text":"step $it"},{"type":"tool_use","name":"Bash","input":{"description":"run $it"}}]}}""" }
        val digest = com.codefrak.engine.smith.SessionDigest.digest(lines, 24_000)
        val recent = com.codefrak.engine.smith.SessionDigest.recent(lines, 3)
        assertEquals((4..6).joinToString("\n") { "said: step $it\ntool Bash: run $it" }, recent)
        assertTrue(digest.endsWith(recent), digest)
        val api = stub("""{"summary":"s","now":"n"}""")
        summarizer(api.url).summarize("c", digest, recent)
        val user = ((api.bodies.single()["messages"] as List<*>).single() as Map<*, *>)["content"] as String
        val section = user.substringAfter("\n\n${HttpSessionSummarizer.RECENT}\n")
        assertEquals(recent, section)
        assertTrue("step 4" in section && "step 6" in section && "step 3" !in section, section)
    }

    @Test
    fun `an over-long answer comes back cut to 20 and 14 words, from inside a fenced block`() {
        val done = (1..70).joinToString(" ") { "d$it" }
        val now = (1..25).joinToString(" ") { "n$it" }
        val api = stub("```json\n{\"summary\":\"$done\",\"now\":\"$now\"}\n```")
        val summary = summarizer(api.url).summarize("c", "d", "r")
        assertEquals(20, words(summary.summary)); assertTrue(summary.summary.startsWith("d1 d2 ") && summary.summary.endsWith("d20…"), summary.summary)
        assertEquals(14, words(summary.now)); assertTrue(summary.now.endsWith("n14…"), summary.now)
    }

    @Test
    fun `no credential is SummaryUnavailable and no call is made`() {
        val api = stub("{}")
        assertFailsWith<SummaryUnavailable> { summarizer(api.url, token = null).summarize("c", "d", "r") }
        assertTrue(api.bodies.isEmpty())
    }

    @Test
    fun `a refusal, an unreachable provider or an answer that is not the JSON asked for is SummaryFailed`() {
        val refused = StubAnthropic(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid bearer token"}}""").also { stubs += it }
        val e = assertFailsWith<SummaryFailed> { summarizer(refused.url).summarize("c", "d", "r") }
        assertEquals("anthropic answered 401: invalid bearer token", e.message)
        assertFailsWith<SummaryFailed> { summarizer("http://127.0.0.1:1").summarize("c", "d", "r") }
        assertFailsWith<SummaryFailed> { summarizer(stub("I cannot tell.").url).summarize("c", "d", "r") }
        assertFailsWith<SummaryFailed> { summarizer(stub("""{"summary":"only this"}""").url).summarize("c", "d", "r") }
    }

    @Test
    fun `limitWords keeps up to n words and marks a cut`() {
        assertEquals("a b c", limitWords("  a  b\nc ", 3))
        assertEquals("a b…", limitWords("a b c", 2))
        assertEquals("", limitWords("   ", 5))
    }
}

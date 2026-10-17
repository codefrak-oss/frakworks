package com.codefrak.engine.accounts

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [AnthropicTitles] against a stub Anthropic: an answer, an error, a blank, a hang and no account. Plain JUnit, no Spring. */
class TitleSummarizerTest {
    private lateinit var stub: HttpServer
    private var status = 200
    private var body = "{}"
    private var hang = false
    private val bodies = mutableListOf<String>()
    private lateinit var url: String

    @BeforeEach
    fun start() {
        stub = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        stub.executor = java.util.concurrent.Executors.newCachedThreadPool()
        stub.createContext("/") { ex ->
            bodies += String(ex.requestBody.readAllBytes())
            if (hang) Thread.sleep(3000)
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        stub.start()
        url = "http://127.0.0.1:${stub.address.port}"
    }

    @AfterEach
    fun stop() = stub.stop(0)

    private fun titles(token: String? = "sk-ant-oat01-t") = AnthropicTitles(url, Duration.ofMillis(500)) { token }

    private fun answer(text: String) = """{"content":[{"type":"text","text":${com.codefrak.engine.engineJson.writeValueAsString(text)}}]}"""

    @Test
    fun `the model's answer is the title, sanitized`() {
        body = answer("Title: \"Explorer shows each ticket's running and past builds.\"\nmore")
        assertEquals(TitleAnswer("Explorer shows each ticket's running and past builds", null), titles().summarize("did I ask?"))
        assertTrue("did I ask?" in bodies.single())
    }

    @Test
    fun `an error, a blank answer, a hang and no account are no title`() {
        status = 500
        assertNull(titles().summarize("x").title)
        status = 200; body = answer("  \n ")
        assertEquals(TitleAnswer(null, "an empty answer"), titles().summarize("x"))
        hang = true
        assertNull(titles().summarize("x").title)
        hang = false
        assertEquals(TitleAnswer(null, "no usable anthropic account"), titles(null).summarize("x"))
    }

    @Test
    fun `sanitize keeps one line and drops quotes and punctuation`() {
        assertEquals("Make the widget blue", AnthropicTitles.sanitize("\n“Make the widget blue”.\n"))
        assertNull(AnthropicTitles.sanitize("\"\""))
        assertNull(AnthropicTitles.sanitize(null))
    }
}

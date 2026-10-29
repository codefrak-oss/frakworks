package com.codefrak.engine.accounts

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The verifier against a stub provider: what it sends, and how each answer reads. Plain JUnit, no Spring. */
class HttpCredentialVerifierTest {
    private lateinit var stub: HttpServer
    private var status = 200
    private var body = "{}"
    private var replyHeaders = mapOf<String, String>()
    private val bodies = mutableListOf<String>()
    private val seen = mutableListOf<Triple<String, String, Map<String, String?>>>()
    private lateinit var verifier: HttpCredentialVerifier

    @BeforeEach
    fun start() {
        stub = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        stub.createContext("/") { ex ->
            val headers = listOf("authorization", "anthropic-beta", "anthropic-version", "x-api-key").associateWith { ex.requestHeaders.getFirst(it) }
            seen += Triple(ex.requestMethod, ex.requestURI.path, headers)
            bodies += String(ex.requestBody.readAllBytes())
            replyHeaders.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        stub.start()
        val url = "http://127.0.0.1:${stub.address.port}"
        verifier = HttpCredentialVerifier(url, url)
    }

    @AfterEach
    fun stop() = stub.stop(0)

    @Test
    fun `an anthropic token is tried as claude code sends it, and a 200 accepts it`() {
        assertEquals(Verdict(true, true, "anthropic accepted it"), verifier.verify("anthropic", "sk-ant-oat01-t"))
        val (method, path, headers) = seen.single()
        assertEquals("POST" to "/v1/messages", method to path)
        assertEquals("Bearer sk-ant-oat01-t", headers["authorization"])
        assertEquals("oauth-2025-04-20", headers["anthropic-beta"])
        assertEquals("2023-06-01", headers["anthropic-version"])
    }

    /** engine/README.md, "Consumption ledger": each Anthropic probe is recorded with its model and the reply's usage, no cost. */
    @Test
    fun `each anthropic probe is recorded in the ledger under its own caller with its tokens and model`() {
        val recorded = mutableListOf<ClaudeUsage>()
        val url = "http://127.0.0.1:${stub.address.port}"
        val ledgered = HttpCredentialVerifier(url, url, url, ClaudeUsageSink { recorded += it })
        body = """{"type":"message","usage":{"input_tokens":12,"output_tokens":1,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}}"""
        ledgered.verify("anthropic", "sk-ant-oat01-t")
        ledgered.probeAnthropic("sk-ant-oat01-t")
        ledgered.verify("openai", "sk-o")
        assertEquals(listOf("credential-verify", "usage-probe"), recorded.map { it.caller }, "an OpenAI model list is no Claude call")
        recorded.forEach {
            assertEquals("claude-haiku-4-5", it.model); assertEquals(ClaudeTokens(12, 1, 0, 0), it.tokens); assertEquals(null, it.costUsd)
        }
    }

    @Test
    fun `a 401 refuses it with the provider's reason, and a 429 still accepts it`() {
        status = 401; body = """{"type":"error","error":{"type":"authentication_error","message":"Invalid bearer token"}}"""
        assertEquals(Verdict(false, true, "anthropic refused it (401): Invalid bearer token"), verifier.verify("anthropic", "sk-ant-oat01-t"))
        status = 429; body = "{}"
        assertTrue(verifier.verify("anthropic", "sk-ant-oat01-t").valid)
    }

    @Test
    fun `the meter probe is verify's request, and brings back the reply's status and headers whatever the status`() {
        verifier.verify("anthropic", "sk-ant-oat01-t")
        replyHeaders = mapOf("anthropic-ratelimit-unified-5h-utilization" to "0.42", "anthropic-ratelimit-unified-status" to "allowed")
        val reply = verifier.probeAnthropic("sk-ant-oat01-t")
        assertEquals(200, reply.status)
        assertEquals(0.42, MeterReading.of(reply.headers)!!.fiveHourUtilization)
        assertEquals(seen[0], seen[1], "same method, path and headers as verify's")
        assertEquals(listOf(HttpCredentialVerifier.ANTHROPIC_PROBE, HttpCredentialVerifier.ANTHROPIC_PROBE), bodies, "the same one-token Haiku message")

        status = 429; replyHeaders = mapOf("anthropic-ratelimit-unified-status" to "rejected")
        val limited = verifier.probeAnthropic("sk-ant-oat01-t")
        assertEquals(429, limited.status)
        assertEquals("rejected", MeterReading.of(limited.headers)!!.status)

        stub.stop(0)
        val gone = assertFailsWith<ProbeUnreachable> { verifier.probeAnthropic("sk-ant-oat01-t") }
        assertTrue(gone.message!!.startsWith("could not reach anthropic"), gone.message)
        assertFalse(gone.message!!.contains("sk-ant"), "the token is not in the error")
    }

    @Test
    fun `an openai key is tried on the model list`() {
        assertTrue(verifier.verify("openai", "sk-o").valid)
        assertEquals("GET" to "/v1/models", seen.single().first to seen.single().second)
    }

    @Test
    fun `a jev key is tried on the model list, which spends no credits`() {
        val jev = HttpCredentialVerifier("http://127.0.0.1:1", "http://127.0.0.1:1", "http://127.0.0.1:${stub.address.port}")
        assertEquals(Verdict(true, true, "jev accepted it"), jev.verify("jev", "jev-key"))
        val (method, path, headers) = seen.single()
        assertEquals("GET" to "/v1/models", method to path)
        assertEquals("Bearer jev-key", headers["authorization"])
    }

    @Test
    fun `a 401 or 403 from jev refuses it with jev's reason`() {
        val jev = HttpCredentialVerifier("http://127.0.0.1:1", "http://127.0.0.1:1", "http://127.0.0.1:${stub.address.port}")
        status = 401; body = """{"detail":"Invalid API key"}"""
        assertEquals(Verdict(false, true, "jev refused it (401): Invalid API key"), jev.verify("jev", "jev-key"))
        status = 403; body = """{"detail":{"error_type":"forbidden","message":"Key disabled"}}"""
        assertEquals(Verdict(false, true, "jev refused it (403): Key disabled"), jev.verify("jev", "jev-key"))
        status = 429; body = "{}"
        assertTrue(jev.verify("jev", "jev-key").valid)
    }

    @Test
    fun `a 500 or no answer says nothing about the token`() {
        status = 500; body = "upstream sad"
        val sad = verifier.verify("anthropic", "sk-ant-oat01-t")
        assertFalse(sad.valid); assertFalse(sad.reachable)
        assertTrue("500" in sad.detail && "upstream sad" in sad.detail, sad.detail)

        stub.stop(0)
        val gone = verifier.verify("anthropic", "sk-ant-oat01-t")
        assertFalse(gone.valid); assertFalse(gone.reachable)
        assertTrue(gone.detail.startsWith("could not reach anthropic"), gone.detail)
    }

    @Test
    fun `an api key is tried with a one-token message on x-api-key, with no oauth beta`() {
        assertEquals(Verdict(true, true, "anthropic accepted the API key"), verifier.verifyKey(ApiKeyKind.API, "sk-ant-usr-k"))
        val (method, path, headers) = seen.single()
        assertEquals("POST" to "/v1/messages", method to path)
        assertEquals("sk-ant-usr-k", headers["x-api-key"])
        assertEquals(null, headers["authorization"])
        assertEquals(null, headers["anthropic-beta"])
        assertTrue(bodies.single().contains("\"max_tokens\":1"))
    }

    @Test
    fun `an admin key is tried on organizations me, and a refusal carries anthropic's message`() {
        status = 401; body = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""
        assertEquals(Verdict(false, true, "anthropic refused the admin key (401): invalid x-api-key"),
            verifier.verifyKey(ApiKeyKind.ADMIN, "sk-ant-admin01-k"))
        val (method, path, headers) = seen.single()
        assertEquals("GET" to "/v1/organizations/me", method to path)
        assertEquals("sk-ant-admin01-k", headers["x-api-key"])
    }

    @Test
    fun `a server error says nothing about the key`() {
        status = 500
        assertFalse(verifier.verifyKey(ApiKeyKind.ADMIN, "sk-ant-admin01-k").reachable)
    }

    @Test
    fun `key prefixes - sk-ant-usr- for both, api03 for the api key and admin01 for the admin key`() {
        assertEquals(null, ApiKeyKind.API.problem("sk-ant-usr-x"))
        assertEquals(null, ApiKeyKind.ADMIN.problem("sk-ant-usr-x"))
        assertEquals(null, ApiKeyKind.API.problem("sk-ant-api03-x"))
        assertEquals(null, ApiKeyKind.ADMIN.problem("sk-ant-admin01-x"))
        assertEquals("an Anthropic API key starts with sk-ant-api03- or sk-ant-usr-", ApiKeyKind.API.problem("sk-ant-admin01-x"))
        assertEquals("an Anthropic admin key starts with sk-ant-admin01- or sk-ant-usr-", ApiKeyKind.ADMIN.problem("sk-ant-oat01-x"))
        assertTrue(ApiKeyKind.API.problem("sk-ant-usr-a b")!!.contains("whitespace"))
    }
}

package com.codefrak.engine.jev

import com.codefrak.engine.accounts.AiAccounts
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Jev client against a stub TypeSafe: what it sends, how each answer reads, and its audit row. */
@SpringBootTest
class HttpJevDeciderTest(@Autowired val accounts: AiAccounts, @Autowired val calls: JevCalls, @Autowired val jdbc: JdbcClient) {
    private lateinit var stub: HttpServer
    /** Status and body for each request in turn; the last repeats. */
    private var replies = mutableListOf(200 to ANSWER)
    private var delayMs = 0L
    private val seen = mutableListOf<Triple<String, String?, String>>()
    private lateinit var jev: HttpJevDecider
    private val json = jacksonObjectMapper()
    private val context = JevContext("pi-7", "acme/tickets", 7)
    private val questions = mapOf(
        "accepts" to JevQuestion.noul("Does the user accept it?", "yes", "no"),
        "kind" to JevQuestion.choice("Which kind?", mapOf("chore" to "small", "feature" to null)),
        "size" to JevQuestion.score("How big?", listOf("small", "large")),
    )

    @BeforeEach
    fun start() {
        stub = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        stub.createContext("/") { ex ->
            seen += Triple(ex.requestURI.path, ex.requestHeaders.getFirst("authorization"), String(ex.requestBody.readAllBytes()))
            if (delayMs > 0) Thread.sleep(delayMs)
            val (status, body) = if (replies.size > 1) replies.removeAt(0) else replies.first()
            val bytes = body.toByteArray()
            runCatching { ex.sendResponseHeaders(status, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) } }
        }
        stub.start()
        jev = HttpJevDecider(accounts, calls, "http://127.0.0.1:${stub.address.port}", "jev-1.13.0", 300, 3, 10)
        accounts.list().filter { it.provider == "jev" }.forEach { accounts.remove(it.name) }
        accounts.add("jev-test", "jev"); accounts.setCredential("jev-test", "ts-secret-key")
    }

    @AfterEach
    fun stop() { stub.stop(0); accounts.remove("jev-test") }

    private fun rows() = jdbc.sql("SELECT COUNT(*) FROM engine.jev_call").query(Int::class.java).single()

    @Test
    fun `it posts the pinned model, state and questions with the jev key, and reads noul, choice and score answers`() {
        val before = rows()
        val decision = jev.decide("acceptance", context, mapOf("answer" to "Looks good"), questions, 0.9)
        val (path, auth, body) = seen.single()
        assertEquals("/v1/systemone" to "Bearer ts-secret-key", path to auth)
        val sent = json.readValue<Map<String, Any?>>(body)
        assertEquals("jev-1.13.0", sent["model"])
        assertEquals(mapOf("answer" to "Looks good"), sent["state"])
        val sentQuestions = sent["questions"] as Map<*, *>
        assertEquals(mapOf("type" to "noul", "instructions" to "Does the user accept it?", "criteria" to mapOf("true" to "yes", "false" to "no")),
            sentQuestions["accepts"])
        assertEquals(mapOf("chore" to "small", "feature" to null), (sentQuestions["kind"] as Map<*, *>)["criteria"])
        assertEquals(listOf("small", "large"), (sentQuestions["size"] as Map<*, *>)["criteria"])

        val answers = assertNotNull(decision.answers)
        assertEquals(0.95, answers["accepts"]!!.noul)
        assertEquals(JevAnswer("choice", choice = "feature", probabilities = mapOf("chore" to 0.2, "feature" to 0.8), confidence = 0.8), answers["kind"])
        assertEquals(1.3, answers["size"]!!.score); assertEquals(0.7, answers["size"]!!.confidence)

        assertEquals(before + 1, rows())
        val row = calls.get(decision.callId)!!
        assertEquals(listOf("acceptance", "pi-7", "acme/tickets", 7L, "jev-test", "jev-1.13.0"),
            listOf(row.useCase, row.processInstance, row.repo, row.issue, row.account, row.model))
        assertEquals(body, row.request)
        assertEquals(ANSWER, row.response)
        assertNull(row.error); assertNotNull(row.latencyMs); assertEquals(0.9, row.threshold)
        assertFalse(listOf(row.request, row.response, row.error).any { it?.contains("ts-secret-key") == true }, "the key is never stored")
    }

    @Test
    fun `a 429 and a 529 are tried again, and the third attempt answers`() {
        replies = mutableListOf(429 to """{"detail":"slow down"}""", 529 to """{"detail":"overloaded"}""", 200 to ANSWER)
        val decision = jev.decide("acceptance", context, "state", questions, null)
        assertEquals(3, seen.size)
        assertEquals(0.95, decision.answers!!["accepts"]!!.noul)
        assertNull(calls.get(decision.callId)!!.error)
    }

    @Test
    fun `retries are bounded, and a busy jev after the last attempt is no answer`() {
        replies = mutableListOf(429 to """{"detail":"slow down"}""")
        val decision = jev.decide("acceptance", context, "state", questions, 0.9)
        assertEquals(3, seen.size)
        assertNull(decision.answers)
        val row = calls.get(decision.callId)!!
        assertNull(row.response); assertTrue(row.error!!.startsWith("jev answered 429"), row.error)
    }

    @Test
    fun `a 500 is no answer at once, with its row`() {
        replies = mutableListOf(500 to "boom")
        val before = rows()
        val decision = jev.decide("acceptance", context, "state", questions, 0.9)
        assertEquals(1, seen.size, "a 500 is not retried")
        assertNull(decision.answers)
        assertEquals(before + 1, rows())
        val row = calls.get(decision.callId)!!
        assertEquals("jev answered 500: boom", row.error); assertNull(row.response); assertEquals(seen.single().third, row.request)
    }

    @Test
    fun `a timeout is no answer`() {
        delayMs = 1500
        val decision = jev.decide("acceptance", context, "state", questions, 0.9)
        assertNull(decision.answers)
        val row = calls.get(decision.callId)!!
        assertNull(row.response); assertTrue(row.error!!.startsWith("no answer"), row.error)
    }

    @Test
    fun `garbage json is no answer, with the body kept`() {
        replies = mutableListOf(200 to "not json")
        val decision = jev.decide("acceptance", context, "state", questions, 0.9)
        assertNull(decision.answers)
        val row = calls.get(decision.callId)!!
        assertEquals("not json", row.response); assertTrue(row.error!!.startsWith("unreadable answer"), row.error)
    }

    @Test
    fun `no usable jev account is no answer, with no request made, and still a row`() {
        accounts.setEnabled("jev-test", false)
        val before = rows()
        val decision = jev.decide("acceptance", context, "state", questions, 0.9)
        assertNull(decision.answers)
        assertTrue(seen.isEmpty())
        assertEquals(before + 1, rows())
        val row = calls.get(decision.callId)!!
        assertEquals("no usable jev account", row.error); assertNull(row.account)
        assertTrue("\"jev-1.13.0\"" in row.request)
    }

    companion object {
        val ANSWER = """{"model":"jev-1.13.0","answers":{""" +
            """"accepts":{"type":"noul","noul":0.95},""" +
            """"kind":{"type":"choice","choice":"feature","probabilities":{"chore":0.2,"feature":0.8},"confidence":0.8},""" +
            """"size":{"type":"score","score":1.3,"legend":{"0":"small","1":"large"},"probabilities":{"0":0.2,"1":0.8},"confidence":0.7}""" +
            """},"usage":{"input_tokens":40,"output_tokens":0}}"""
    }
}

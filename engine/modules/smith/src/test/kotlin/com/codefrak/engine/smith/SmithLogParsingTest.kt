package com.codefrak.engine.smith

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class SmithLogParsingTest {
    /** A closing `result` event as Claude's stream-json writes it, trimmed to the fields read. */
    private val resultLine = """{"type":"result","subtype":"success","is_error":false,"duration_ms":81234,""" +
        """"duration_api_ms":80000,"num_turns":17,"result":"done","total_cost_usd":1.2345}"""

    @Test
    fun `the closing result event is read from the end, and a missing or garbled one is null`() {
        val event = closingResult(listOf(
            """{"type":"system","subtype":"init"}""",
            """{"type":"assistant","message":{"content":[{"type":"text","text":"the \"result\" is near"}]}}""",
            resultLine, "smith: done").joinToString("\n"))!!
        assertEquals(1.2345, event["total_cost_usd"]); assertEquals(17, event["num_turns"]); assertEquals(81234, event["duration_ms"])
        assertNull(closingResult("""{"type":"assistant","result":"not a result event"}""" + "\nsmith: done"))
        assertNull(closingResult("""{"type":"result","num_turns":17,"total_cost""" + "\nsmith: done"))
        assertNull(closingResult(""))
    }

    private val outOfCredits = "You're out of usage credits. Switch to another model to continue."

    @Test
    fun `out of usage credits is the Fable limit on Fable only, never a session limit`() {
        val log = """{"type":"result","subtype":"success","is_error":true,"result":"$outOfCredits"}"""
        assertNotNull(fableLimitHit(log, null, null, "claude-fable-5-1"))
        assertNull(fableLimitHit(log, null, null, "claude-opus-5-5"))
        assertNull(fableLimitHit(log, null, null, "claude-sonnet-5"))
        assertNotNull(fableLimitHit("""{"type":"result","is_error":true,"result":"You've reached your Fable limit"}""", null, null, "claude-opus-5-5"))
        assertNull(sessionLimitHit(log, outOfCredits.toByteArray()))
    }

    private val inThreeDays get() = java.time.Instant.now().plus(java.time.Duration.ofDays(3)).epochSecond

    /** The reading observed on 2026-10-05: 429, both windows allowed, the overall status rejected (only 7d_oi out). */
    private fun fableOutMeter(fiveHour: String = "allowed", sevenDay: String = "allowed") = """
        {"observedAt": "${java.time.OffsetDateTime.now()}", "status": 429,
         "anthropic-ratelimit-unified-5h-utilization": "0.20", "anthropic-ratelimit-unified-5h-status": "$fiveHour",
         "anthropic-ratelimit-unified-7d-utilization": "0.40", "anthropic-ratelimit-unified-7d-status": "$sevenDay",
         "anthropic-ratelimit-unified-7d-reset": "$inThreeDays", "anthropic-ratelimit-unified-status": "rejected"}
    """.trimIndent().toByteArray()

    @Test
    fun `a 429 with both windows allowed is the Fable limit whatever the overall status`() {
        val meter = SandboxMeter.parse(fableOutMeter())
        assertEquals("rejected", meter.reading?.status)
        assertNotNull(fableLimitHit("", null, meter, "claude-fable-5-1"))
        assertNull(fableLimitHit("", null, SandboxMeter.parse(fableOutMeter(fiveHour = "rejected")), "claude-fable-5-1"))
        assertNull(fableLimitHit("", null, SandboxMeter.parse(fableOutMeter(sevenDay = "rejected")), "claude-fable-5-1"))
        assertNull(fableLimitHit("", null, meter, "claude-opus-5-5"))
    }

    @Test
    fun `limit resets parse to the next such instant`() {
        val at = java.time.OffsetDateTime.parse("2026-09-25T12:43:00Z")
        assertEquals("2026-09-25T15:10Z", parseLimitReset("your session limit resets 3:10pm (UTC)", at).toString())
        assertEquals("2026-09-26T09:00Z", parseLimitReset("resets 9am", at).toString())
        assertEquals("2026-09-26T00:30Z", parseLimitReset("resets 12:30am (UTC)", at).toString())
        assertEquals("2026-09-25T13:00Z", parseLimitReset("resets 3pm (Europe/Berlin)", at).toString())
        assertNull(parseLimitReset("You've hit your org's monthly spend limit", at))
        assertNotNull(sessionLimitHit("", "session limit reached".toByteArray()))
        assertNull(sessionLimitHit("""{"type":"result","is_error":false,"result":"session limit"}""", null))
    }

    @Test
    fun `overflowHit is the closing result's blocking_limit or a too-long prompt, never a quoting line`() {
        assertTrue(overflowHit("""{"type":"result","terminal_reason":"blocking_limit","result":"stopped"}"""))
        assertTrue(overflowHit("""{"type":"result","is_error":true,"result":"API Error: Prompt is too long"}"""))
        assertFalse(overflowHit("""{"type":"assistant","result":"I saw 'Prompt is too long' in the log"}"""))
        assertFalse(overflowHit("""{"type":"result","terminal_reason":"api_error","result":"reached your Fable limit"}"""))
    }
}

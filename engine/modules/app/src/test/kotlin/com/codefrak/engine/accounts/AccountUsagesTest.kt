package com.codefrak.engine.accounts

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The meter's table and its API, fed directly: what `collect result` records
 * from a sandbox's meter.json (engine/README.md, "The usage meter").
 */
@SpringBootTest
class AccountUsagesTest(@Autowired val usages: AccountUsages, @Autowired val api: AiAccountController) {
    private fun reading(rejected: Boolean = false) = MeterReading.of(mapOf(
        "anthropic-ratelimit-unified-5h-utilization" to listOf(if (rejected) "1.0" else "0.08"),
        "anthropic-ratelimit-unified-5h-status" to listOf(if (rejected) "rejected" else "allowed"),
        "anthropic-ratelimit-unified-5h-reset" to listOf("1790230200"),
        "anthropic-ratelimit-unified-7d-utilization" to listOf("0.17"),
        "anthropic-ratelimit-unified-7d-status" to listOf("allowed"),
        "anthropic-ratelimit-unified-7d-reset" to listOf("1790719200"),
        "anthropic-ratelimit-unified-status" to listOf(if (rejected) "rejected" else "allowed"),
    ))!!

    @Test
    fun `a reading is recorded under its account, latest wins, and counted`() {
        usages.record("usage-a", reading(), 200)
        val u = usages.get("usage-a")!!
        assertEquals(0.08, u.fiveHourUtilization); assertEquals("allowed", u.fiveHourStatus)
        assertEquals(OffsetDateTime.ofInstant(Instant.ofEpochSecond(1790230200), ZoneOffset.UTC).toInstant(), u.fiveHourReset!!.toInstant())
        assertEquals(0.17, u.sevenDayUtilization); assertEquals("allowed", u.status); assertEquals(200, u.httpStatus)
        assertEquals(1, u.requests); assertFalse(u.rejected())

        usages.record("usage-a", reading(rejected = true), 429)
        assertEquals(2, usages.get("usage-a")!!.requests)
        assertEquals(429, usages.get("usage-a")!!.httpStatus)
    }

    @Test
    fun `a rejected reading marks the account rejected until its reset`() {
        usages.record("usage-hot", reading(rejected = true), 429)
        val u = usages.get("usage-hot")!!
        assertEquals("rejected", u.status)
        assertTrue(u.rejected(Instant.ofEpochSecond(1790230100)))
        assertFalse(u.rejected(Instant.ofEpochSecond(1790719201)))   // past the rejected 5h window's reset
    }

    @Test
    fun `a rejected 5h window whose reset passed no longer marks the account rejected while 7d is allowed`() {
        usages.record("usage-cooled", reading(rejected = true), 429)
        val u = usages.get("usage-cooled")!!
        assertEquals("rejected", u.status); assertEquals("allowed", u.sevenDayStatus)
        val now = Instant.ofEpochSecond(1790230201)   // past the 5h reset, before the 7d one
        assertFalse(u.rejected(now))
        assertNull(u.rejectedUntil(now))
    }

    @Test
    fun `the meter is served under accounts`() {
        usages.record("usage-d", reading(), 200)
        val one = api.usage("usage-d")
        assertEquals(200, one.statusCode.value())
        assertEquals(0.08, one.body!!.fiveHourUtilization); assertFalse(one.body!!.rejected)
        assertTrue(api.usage().any { it.account == "usage-d" })
        assertEquals(404, api.usage("usage-nobody").statusCode.value())
    }

    @Test
    fun `a limit rejection is stored, survives later readings, and clears once its reset passes`() {
        usages.record("lim-u", reading(), 200, at("2026-09-25T12:00:00Z"))
        assertTrue(usages.markLimitRejected("lim-u", at("2026-09-25T15:10:00Z"), at("2026-09-25T12:43:00Z")))
        assertFalse(usages.markLimitRejected("lim-u", at("2026-09-25T14:00:00Z"), at("2026-09-25T12:44:00Z")), "an earlier reset does not shorten it")
        usages.record("lim-u", reading(), 200, at("2026-09-25T13:00:00Z"))
        val u = usages.get("lim-u")!!
        assertEquals(Instant.parse("2026-09-25T15:10:00Z"), u.limitRejectedReset!!.toInstant())
        assertEquals(Instant.parse("2026-09-25T12:43:00Z"), u.limitRejectedSince!!.toInstant())
        assertTrue(u.windowRejected(Instant.parse("2026-09-25T15:00:00Z")))
        assertEquals(Instant.parse("2026-09-25T15:10:00Z"), u.rejectedUntil(Instant.parse("2026-09-25T15:00:00Z"))!!.toInstant())
        assertFalse(u.windowRejected(Instant.parse("2026-09-25T15:10:00Z")))

        usages.record("lim-u", reading(), 200, at("2026-09-25T15:11:00Z"))
        assertNull(usages.get("lim-u")!!.limitRejectedReset)
        assertTrue(usages.markLimitRejected("lim-new", at("2026-09-25T15:10:00Z")), "no reading yet: a row of its own")
    }

    @Test
    fun `a rejected window blocks until its own reset`() {
        val u = usages.let { usages.record("win-u", reading(rejected = true), 429); usages.get("win-u")!! }
        assertTrue(u.windowRejected(Instant.ofEpochSecond(1790230100)))
        assertFalse(u.windowRejected(Instant.ofEpochSecond(1790230200)), "the 5-hour reset, not the later 7-day one")
    }

    private fun at(iso: String) = OffsetDateTime.parse(iso)
    private fun weekReading(sevenDayReset: String) = MeterReading(0.1, "allowed", null, 0.6, "allowed", at(sevenDayReset), "allowed")

    @Test
    fun `the Fable limit is recorded once, with the account's 7-day reset at that moment`() {
        usages.record("fable-a", reading(), 200, at("2026-09-25T04:00:00Z"))
        assertEquals("unknown", usages.get("fable-a")!!.fableLimit)
        assertTrue(usages.markFableReached("fable-a", at("2026-09-25T04:39:00Z")))
        val u = usages.get("fable-a")!!
        assertEquals("reached", u.fableLimit)
        assertEquals(at("2026-09-25T04:39:00Z").toInstant(), u.fableLimitSince!!.toInstant())
        assertEquals(Instant.ofEpochSecond(1790719200), u.fableLimitReset!!.toInstant())
        assertTrue(u.fableReached(Instant.parse("2026-09-26T00:00:00Z")))
        assertFalse(u.fableReached(Instant.ofEpochSecond(1790719200)))

        // A second hit, and a later reading in the same window, leave it as it was recorded.
        assertFalse(usages.markFableReached("fable-a", at("2026-09-25T06:00:00Z")))
        assertFalse(usages.record("fable-a", reading(), 200, at("2026-09-25T07:00:00Z")))
        val again = usages.get("fable-a")!!
        assertEquals(u.fableLimitSince!!.toInstant(), again.fableLimitSince!!.toInstant())
        assertEquals(u.fableLimitReset!!.toInstant(), again.fableLimitReset!!.toInstant())
        assertEquals("reached", again.fableLimit)
    }

    @Test
    fun `an account with no reading yet gets a row holding only its Fable limit, a week out`() {
        assertTrue(usages.markFableReached("fable-new", at("2026-09-25T04:39:00Z")))
        val u = usages.get("fable-new")!!
        assertEquals("reached", u.fableLimit); assertEquals(0, u.requests)
        assertEquals(Instant.parse("2026-10-02T04:39:00Z"), u.fableLimitReset!!.toInstant())
    }

    @Test
    fun `a reading past the recorded reset clears the Fable limit, once`() {
        usages.record("fable-c", weekReading("2026-09-29T22:00:00Z"), 200, at("2026-09-25T04:00:00Z"))
        usages.markFableReached("fable-c", at("2026-09-25T04:39:00Z"))
        assertEquals(Instant.parse("2026-09-29T22:00:00Z"), usages.get("fable-c")!!.fableLimitReset!!.toInstant())

        // The window rolled: the reading's 7-day reset moved past the recorded one.
        assertTrue(usages.record("fable-c", weekReading("2026-10-06T22:00:00Z"), 200, at("2026-09-29T22:05:00Z")))
        val u = usages.get("fable-c")!!
        assertEquals("unknown", u.fableLimit); assertNull(u.fableLimitSince); assertNull(u.fableLimitReset)
        assertFalse(usages.record("fable-c", weekReading("2026-10-06T22:00:00Z"), 200, at("2026-09-29T23:00:00Z")),
            "only the reading that cleared it reports (and logs) the clear")

        // Or no new reset, but the time is past the recorded one.
        usages.markFableReached("fable-c", at("2026-09-30T01:00:00Z"))
        assertEquals(Instant.parse("2026-10-06T22:00:00Z"), usages.get("fable-c")!!.fableLimitReset!!.toInstant())
        assertTrue(usages.clearFableIfRolled("fable-c", weekReading("2026-10-06T22:00:00Z"), at("2026-10-06T22:00:01Z")))
        assertFalse(usages.clearFableIfRolled("fable-c", weekReading("2026-10-06T22:00:00Z"), at("2026-10-06T22:00:02Z")))
    }

    @Test
    fun `the Fable limit is served with the meter`() {
        usages.record("fable-d", reading(), 200)
        assertEquals("unknown", api.usage("fable-d").body!!.fableLimit)
        usages.markFableReached("fable-d", at("2026-09-25T04:39:00Z"))
        val one = api.usage("fable-d").body!!
        assertEquals("reached", one.fableLimit)
        assertEquals(Instant.parse("2026-09-25T04:39:00Z"), one.fableLimitSince!!.toInstant())
        assertEquals(Instant.ofEpochSecond(1790719200), one.fableLimitReset!!.toInstant())
        assertEquals("reached", api.usage().single { it.account == "fable-d" }.fableLimit)
    }

    /** [reading] as a `claude-fable-*` reply carries it, with the Fable limit's `7d_oi` pair (seen live 2026-09-26). */
    private fun fableReading() = MeterReading.of(mapOf(
        "anthropic-ratelimit-unified-5h-utilization" to listOf("0.16"),
        "anthropic-ratelimit-unified-7d-utilization" to listOf("0.59"),
        "anthropic-ratelimit-unified-7d-reset" to listOf("1790719200"),
        "anthropic-ratelimit-unified-status" to listOf("allowed_warning"),
        "anthropic-ratelimit-unified-7d_oi-utilization" to listOf("0.79"),
        "anthropic-ratelimit-unified-7d_oi-reset" to listOf("1790719200"),
        "anthropic-ratelimit-unified-7d_oi-status" to listOf("allowed_warning"),
    ))!!

    @Test
    fun `the Fable pair is parsed, stored and served`() {
        val r = fableReading()
        assertEquals(0.79, r.fableUtilization); assertEquals(Instant.ofEpochSecond(1790719200), r.fableReset!!.toInstant())
        assertNull(reading().fableUtilization); assertNull(reading().fableReset)

        usages.record("fable-meter", r, 200, at("2026-09-26T10:00:00Z"))
        val u = usages.get("fable-meter")!!
        assertEquals(0.79, u.fableUtilization); assertEquals(Instant.ofEpochSecond(1790719200), u.fableReset!!.toInstant())
        assertEquals("unknown", u.fableLimit, "the reading does not touch the recorded Fable limit")
        val one = api.usage("fable-meter").body!!
        assertEquals(0.79, one.fableUtilization); assertEquals(Instant.ofEpochSecond(1790719200), one.fableReset!!.toInstant())
        assertEquals(0.79, api.usage().single { it.account == "fable-meter" }.fableUtilization)

        usages.record("fable-meter-none", reading(), 200, at("2026-09-26T10:00:00Z"))   // first row, inserted without the pair
        assertNull(usages.get("fable-meter-none")!!.fableUtilization); assertNull(usages.get("fable-meter-none")!!.fableReset)
    }

    @Test
    fun `a reading without the Fable pair keeps the last one, until its reset passes (#465)`() {
        usages.record("fable-kept", fableReading(), 200, at("2026-09-26T10:00:00Z"))
        usages.record("fable-kept", reading(), 200, at("2026-09-26T11:00:00Z"))   // a later run on another model
        val u = usages.get("fable-kept")!!
        assertEquals(0.79, u.fableUtilization); assertEquals(Instant.ofEpochSecond(1790719200), u.fableReset!!.toInstant())
        assertEquals(0.08, u.fiveHourUtilization, "the rest of the reading is still the latest one's")
        assertEquals(2, u.requests)
        val one = api.usage("fable-kept").body!!
        assertEquals(0.79, one.fableUtilization); assertEquals(Instant.ofEpochSecond(1790719200), one.fableReset!!.toInstant())
        assertEquals(0.79, api.usage().single { it.account == "fable-kept" }.fableUtilization)

        // A newer Fable reading replaces it.
        usages.record("fable-kept", MeterReading.of(mapOf(
            "anthropic-ratelimit-unified-status" to listOf("allowed"),
            "anthropic-ratelimit-unified-7d_oi-utilization" to listOf("0.81"),
            "anthropic-ratelimit-unified-7d_oi-reset" to listOf("1790719200"),
        ))!!, 200, at("2026-09-26T12:00:00Z"))
        assertEquals(0.81, usages.get("fable-kept")!!.fableUtilization)

        // Past its reset (2026-09-29T22:00:00Z) the kept pair says nothing any more: cleared.
        usages.record("fable-kept", reading(), 200, at("2026-09-29T22:05:00Z"))
        val after = api.usage("fable-kept").body!!
        assertNull(after.fableUtilization); assertNull(after.fableReset)
    }

    @Test
    fun `header parsing is case-insensitive and tolerant`() {
        assertNull(MeterReading.of(mapOf("Content-Type" to listOf("x"))))
        val r = MeterReading.of(mapOf("Anthropic-Ratelimit-Unified-5H-Utilization" to listOf("0.5"), "anthropic-ratelimit-unified-7d-reset" to listOf("junk")))!!
        assertEquals(0.5, r.fiveHourUtilization); assertNull(r.sevenDayReset); assertNull(r.status)
        val f = MeterReading.of(mapOf("Anthropic-Ratelimit-Unified-7D_OI-Utilization" to listOf("0.3"), "anthropic-ratelimit-unified-7d_oi-reset" to listOf("junk")))!!
        assertEquals(0.3, f.fableUtilization); assertNull(f.fableReset)
    }
}

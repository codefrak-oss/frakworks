package com.codefrak.engine.doctor

import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [backOff]: the back-off rule, pulled out of [Doctor.backedOff]/[Doctor.fixedSinceLast]. */
class BackOffTest {
    private val now = OffsetDateTime.of(2026, 10, 10, 12, 0, 0, 0, ZoneOffset.UTC)
    private val backoff = Duration.ofHours(6)
    private val throws: (Long, OffsetDateTime) -> Boolean = { _, _ -> throw AssertionError("should not have been called") }

    private fun run(outcome: String, startedAt: OffsetDateTime, sighting: DoctorSighting? = null) =
        DoctorRun("run-$startedAt-$outcome", "owner/repo", 1, "root", listOf("feature"), startedAt, startedAt, null, null, null, null, outcome, null, sighting = sighting)

    private fun sighting(bugState: String?) = DoctorSighting(42, "doctor-failed", "doctor", bugState?.let { SightingBug("owner/repo", 7, it) }, 1, 1, "sig")

    @Test
    fun `no runs returns null`() {
        assertNull(backOff(emptyList(), backoff, now, throws))
    }

    @Test
    fun `back-off window already passed returns null`() {
        val there = listOf(run("failed", now.minus(backoff).minusMinutes(1)))
        assertNull(backOff(there, backoff, now, throws))
    }

    @Test
    fun `3 most recent runs failed backs off until the window ends`() {
        val newest = now.minusHours(1)
        val there = listOf(run("failed", newest), run("failed", newest.minusHours(1)), run("failed", newest.minusHours(2)))
        assertEquals("failed" to newest.plus(backoff), backOff(there, backoff, now, throws))
    }

    @Test
    fun `an unfixed bug sighting backs off until the window ends`() {
        val newest = now.minusHours(1)
        val there = listOf(run("healthy", newest, sighting("open")))
        assertEquals("bug" to newest.plus(backoff), backOff(there, backoff, now, throws))
    }

    @Test
    fun `a bug fixed since the newest run lifts the back-off`() {
        val newest = now.minusHours(1)
        val there = listOf(run("healthy", newest, sighting("fixed")))
        var called = false
        val fixedSince: (Long, OffsetDateTime) -> Boolean = { id, since -> called = true; assertEquals(42L, id); assertEquals(newest, since); true }
        assertNull(backOff(there, backoff, now, fixedSince))
        assertEquals(true, called)
    }

    @Test
    fun `fewer than 3 failures and no bug returns null`() {
        val newest = now.minusHours(1)
        val there = listOf(run("failed", newest), run("failed", newest.minusHours(1)))
        assertNull(backOff(there, backoff, now, throws))
    }
}

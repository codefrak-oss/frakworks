package com.codefrak.engine.doctor

import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/** [bugsSince]: the filter and sort pulled out of [DoctorBugStore.since]. */
class BugsSinceTest {
    private val since = OffsetDateTime.of(2026, 10, 10, 12, 0, 0, 0, ZoneOffset.UTC)

    private fun bug(id: Long, createdAt: OffsetDateTime, lastSeen: OffsetDateTime? = null) =
        DoctorBug(id, "owner/repo", 1, "open", null, createdAt, null, emptyList(), 1, 1, lastSeen)

    @Test
    fun `a bug with no sighting falls back to createdAt and is kept when createdAt is at or after since`() {
        val there = bug(1, since)
        assertEquals(listOf(there), bugsSince(listOf(there), since))
    }

    @Test
    fun `a bug exactly at since is kept`() {
        val there = bug(1, since.minusDays(1), since)
        assertEquals(listOf(there), bugsSince(listOf(there), since))
    }

    @Test
    fun `a bug one unit before since is dropped`() {
        val there = bug(1, since.minusDays(1), since.minusNanos(1))
        assertEquals(emptyList(), bugsSince(listOf(there), since))
    }

    @Test
    fun `ties on the same effective time order by id descending`() {
        val a = bug(1, since, since)
        val b = bug(2, since, since)
        assertEquals(listOf(b, a), bugsSince(listOf(a, b), since))
    }

    @Test
    fun `distinct times order newest-first`() {
        val older = bug(1, since, since.plusHours(1))
        val newer = bug(2, since, since.plusHours(2))
        assertEquals(listOf(newer, older), bugsSince(listOf(older, newer), since))
    }
}

package com.codefrak.engine.trigger

import com.codefrak.engine.lease.EngineLease
import com.codefrak.engine.ci.CiCheckWaits
import com.codefrak.engine.repos.TargetRepo
import com.codefrak.engine.repos.TargetRepos
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The per-repo scan interval: which repos a tick scans, from a clock started at [t]. */
class ScanScheduleTest {
    private val t = Instant.parse("2026-10-05T10:00:00Z")
    private val fast = "acme/no-webhook"
    private val slow = "acme/hooked"
    private val scanner = mock(IssueScanner::class.java).also { s ->
        `when`(s.scan(any())).thenAnswer { ScanReport(emptyList(), emptyList(), (it.getArgument<Set<String>?>(0) ?: setOf(fast, slow)).toList()) }
    }
    private val ciWaits = mock(CiCheckWaits::class.java)
    private val repos = mock(TargetRepos::class.java).also {
        val added = OffsetDateTime.parse("2026-09-26T10:00:00Z")
        `when`(it.list()).thenReturn(listOf(TargetRepo(slow, added, scanIntervalMinutes = 60), TargetRepo(fast, added, scanIntervalMinutes = 5)))
    }
    private val clock = ScanClock().also { it.reset(t) }

    private fun schedule(enabled: Boolean = true) = ScanSchedule(scanner, ciWaits, repos, clock, mock(EngineLease::class.java), enabled)
    private fun at(minutes: Long) = t.plus(Duration.ofMinutes(minutes))

    @Test
    fun `a repo with interval 5 is scanned once 5 minutes have passed, one with 60 is not until 60 have`() {
        val schedule = schedule()
        assertNull(schedule.scanDue(at(4)), "nothing due yet: no scan")
        verify(scanner, never()).scan(any())

        assertEquals(listOf(fast), schedule.scanDue(at(5))?.repos)
        verify(scanner).scan(setOf(fast))
        verify(ciWaits).poll(setOf(fast))

        assertNull(schedule.scanDue(at(9)), "measured from its last scan, at 5")
        assertEquals(listOf(fast), schedule.scanDue(at(10))?.repos)
        assertEquals(listOf(slow, fast).sorted(), schedule.scanDue(at(60))?.repos)
        verify(scanner).scan(setOf(fast, slow))
    }

    @Test
    fun `any scan that covered a repo restarts its interval`() {
        val schedule = schedule()
        // A wake or POST /scan at +30 covered both (IssueScanner records it).
        clock.scanned(listOf(fast, slow), at(30))
        assertEquals(listOf(fast), schedule.scanDue(at(60))?.repos, "the slow repo's hour runs from +30")
        assertEquals(listOf(fast, slow).sorted(), schedule.scanDue(at(90))?.repos)
    }

    @Test
    fun `a failed scan is retried on the interval, not every tick`() {
        `when`(scanner.scan(setOf(fast))).thenThrow(IllegalStateException("GitHub is down"))
        val schedule = schedule()
        assertEquals(listOf("GitHub is down"), schedule.scanDue(at(5))?.failures)
        assertNull(schedule.scanDue(at(6)))
    }

    @Test
    fun `engine scan enabled false scans nothing, at startup or on a tick`() {
        val schedule = schedule(enabled = false)
        schedule.atStartup()
        assertNull(schedule.scanDue(at(120)))
        verifyNoInteractions(scanner, ciWaits, repos)
    }
}

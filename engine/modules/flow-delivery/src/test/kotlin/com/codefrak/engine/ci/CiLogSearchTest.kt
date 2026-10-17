package com.codefrak.engine.ci

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** First mate's bounded reads of a job and its log (#2188): steps, tail, search. Plain JUnit. */
class CiLogSearchTest {
    private val lines = listOf("compile", "warning: deprecated api", "test", "Retrying download", "done", "")

    @Test
    fun `search answers line numbers, context and the total, bounded by max`() {
        val found = CiLogSearch.search(lines, CiLogSearch.SUSPICIOUS, context = 1, max = 1)
        assertEquals(2, found.total)
        assertEquals(CiLogSearch.Match(2, "warning: deprecated api", listOf("compile"), listOf("test")), found.matches.single())
        assertEquals(0, CiLogSearch.search(lines, Regex("nope"), 2, 20).total)
    }

    @Test
    fun `a fixed pattern is quoted, and an empty, overlong or broken one is refused`() {
        assertEquals(1, CiLogSearch.search(lines, CiLogSearch.compile("api", true).getOrThrow(), 0, 5).total)
        assertEquals(0, CiLogSearch.search(listOf("a.c"), CiLogSearch.compile("a\\.d", false).getOrThrow(), 0, 5).total)
        assertEquals(1, CiLogSearch.search(listOf("a.c"), CiLogSearch.compile("a.c", true).getOrThrow(), 0, 5).total)
        assertTrue(CiLogSearch.compile("", false).isFailure)
        assertTrue(CiLogSearch.compile("x".repeat(CiLogSearch.MAX_PATTERN + 1), false).isFailure)
        assertTrue(CiLogSearch.compile("(", false).isFailure)
    }

    @Test
    fun `a search past its time guard stops and says so`() {
        val found = CiLogSearch.search(listOf("a".repeat(40) + "!"), Regex("(a+)+$"), 0, 5, Duration.ofMillis(-1))
        assertTrue(found.timedOut)
    }

    @Test
    fun `tail is capped and drops trailing blank lines, and long lines are cut`() {
        assertEquals(listOf("Retrying download", "done"), CiLogSearch.tail(lines, 2))
        assertEquals(CiLogSearch.MAX_TAIL, CiLogSearch.tail((1..500).map { "$it" }, 999).size)
        assertTrue(CiLogSearch.shown("x".repeat(1000)).endsWith("… (1000 characters)"))
    }

    @Test
    fun `job marks the slowest step and a long-running one possibly stuck`() {
        val now = Instant.parse("2026-10-10T11:00:00Z")
        val job = WorkflowJob(1, 2, "build", "in_progress", null, "2026-10-10T10:00:00Z", null, null, listOf(
            JobStep(1, "setup", "completed", "success", "2026-10-10T10:00:00Z", "2026-10-10T10:00:30Z"),
            JobStep(2, "build", "in_progress", null, "2026-10-10T10:00:30Z", null)))
        val row = CiLogSearch.job(job, now)
        val steps = row["steps"] as List<*>
        assertEquals(30L, (steps[0] as Map<*, *>)["elapsedSeconds"])
        assertEquals(true, (steps[1] as Map<*, *>)["possiblyStuck"])
        assertEquals("build", row["slowestStep"])
        assertEquals(3600L, row["elapsedSeconds"])
    }
}

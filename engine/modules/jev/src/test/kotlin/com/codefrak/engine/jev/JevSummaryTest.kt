package com.codefrak.engine.jev

import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** JevSummaries' pure math: median, actionKind, and the confidence-bucket boundary in useCase/of. */
class JevSummaryTest {
    @Test
    fun `median is null for an empty list, the value for one, the middle for an odd-sized list, the average of the two middles for an even-sized one`() {
        assertNull(JevSummaries.median(emptyList()))
        assertEquals(5L, JevSummaries.median(listOf(5L)))
        assertEquals(3L, JevSummaries.median(listOf(1L, 5L, 3L)))
        assertEquals(2L, JevSummaries.median(listOf(1L, 2L, 3L, 4L)))
    }

    @Test
    fun `actionKind special-cases refine-pick's no-answer, below-floor and default branches`() {
        assertEquals("no answer: the refiner's", JevSummaries.actionKind("refine-pick", "no answer: timed out"))
        assertEquals("filed, a field below the floor", JevSummaries.actionKind("refine-pick", "filed, tier <floor"))
        assertEquals("filed at jev's pick", JevSummaries.actionKind("refine-pick", "filed as jev picked"))
    }

    @Test
    fun `actionKind special-cases proposal-decision's took prefix and its substringBefore fallback`() {
        assertEquals("took jev's pick", JevSummaries.actionKind("proposal-decision", "took jev's pick: accept"))
        assertEquals("retry", JevSummaries.actionKind("proposal-decision", "retry 3: did something"))
    }

    @Test
    fun `actionKind's generic else branch strips ticket refs and bare numbers on another use case`() {
        assertEquals("fixed #n after retries", JevSummaries.actionKind("ci-triage", "fixed #123 after 2 retries"))
    }

    @Test
    fun `a confidence of exactly 1_0 lands in the last bucket, not out of bounds and not in a phantom BUCKETS slot`() {
        val call = JevCall(
            id = "1", calledAt = OffsetDateTime.now(ZoneOffset.UTC), useCase = "ci-triage", processInstance = null,
            repo = null, issue = null, account = null, model = "jev-1.13.0", request = "{}",
            response = """{"answers":{"cause":{"type":"choice","choice":"flaky","confidence":1.0}}}""",
            error = null, latencyMs = 10, threshold = null, action = null, outcome = null, outcomeAt = null,
        )
        val since = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1)
        val until = OffsetDateTime.now(ZoneOffset.UTC)
        val summary = JevSummaries.of(listOf(call), since, until, JevAgreement(emptyList()))
        val confidence = summary.useCases.single { it.useCase == "ci-triage" }.confidence
        assertEquals(JevSummaries.BUCKETS, confidence.size)
        assertEquals(1, confidence[JevSummaries.BUCKETS - 1])
        confidence.dropLast(1).forEach { assertEquals(0, it) }
    }
}

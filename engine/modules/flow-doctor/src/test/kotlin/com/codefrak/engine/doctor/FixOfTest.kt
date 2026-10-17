package com.codefrak.engine.doctor

import com.codefrak.engine.github.MergedPull
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [DoctorBugs.fixOf]: which merged PR is taken as the fix for a signature. */
class FixOfTest {
    private val at = OffsetDateTime.parse("2026-10-06T21:23:00Z")
    private val signature = Signature("engine", listOf("chore:task-open-pr@none"), listOf("task-open-pr failedJob git clone failed"))
    private fun pr(n: Long, title: String, body: String = "", mergedAt: OffsetDateTime = at) = MergedPull(n, title, body, "u$n", mergedAt)

    @Test
    fun `a PR naming the leaf's activity as a word is the fix, the newest first`() {
        val fix = DoctorBugs.fixOf(signature, listOf(pr(1, "task-open-pr retries a transient GitHub failure"),
            pr(2, "x", "fixes `task-open-pr`", at.plusMinutes(1))))
        assertEquals(2L, fix?.number)
    }

    @Test
    fun `a PR naming another activity, or the activity inside a longer one, is not`() {
        assertNull(DoctorBugs.fixOf(signature, listOf(pr(1, "task-smith retries"), pr(2, "task-open-pr-draft added"))))
    }

    @Test
    fun `a PR naming the signature's hash is the fix`() {
        assertEquals(3L, DoctorBugs.fixOf(signature, listOf(pr(3, "x", "Signature ${signature.hash.take(16)}")))?.number)
    }
}

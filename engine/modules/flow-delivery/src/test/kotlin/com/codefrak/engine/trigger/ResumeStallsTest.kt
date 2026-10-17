package com.codefrak.engine.trigger

import com.codefrak.engine.github.Blocker
import kotlin.test.Test
import kotlin.test.assertEquals

/** [stallsAfter]: the stalled-set bookkeeping pulled out of [IssueScanner.resume]. */
class ResumeStallsTest {
    private fun blocker(repo: String = "owner/repo", number: Long = 1) = Blocker(repo, number, "closed")

    @Test
    fun `first stall recorded, need added`() {
        val b = blocker(number = 1)
        val result = stallsAfter(stalled = emptySet(), running = emptyList(), unmerged = listOf(b))
        assertEquals(setOf("owner/repo#1"), result.stalled)
        assertEquals(listOf(b), result.toComment)
        assertEquals(ReauthorizeNeed.ADDED, result.need)
    }

    @Test
    fun `repeat stall, no comment and no need change`() {
        val b = blocker(number = 1)
        val result = stallsAfter(stalled = setOf("owner/repo#1"), running = emptyList(), unmerged = listOf(b))
        assertEquals(setOf("owner/repo#1"), result.stalled)
        assertEquals(emptyList(), result.toComment)
        assertEquals(ReauthorizeNeed.UNCHANGED, result.need)
    }

    @Test
    fun `a running blocker clears the last stall, need removed`() {
        val b = blocker(number = 1)
        val result = stallsAfter(stalled = setOf("owner/repo#1"), running = listOf(b), unmerged = emptyList())
        assertEquals(emptySet(), result.stalled)
        assertEquals(emptyList(), result.toComment)
        assertEquals(ReauthorizeNeed.REMOVED, result.need)
    }

    @Test
    fun `a running blocker with other stalls left, untouched`() {
        val b1 = blocker(number = 1)
        val result = stallsAfter(stalled = setOf("owner/repo#1", "owner/repo#2"), running = listOf(b1), unmerged = emptyList())
        assertEquals(setOf("owner/repo#2"), result.stalled)
        assertEquals(emptyList(), result.toComment)
        assertEquals(ReauthorizeNeed.UNCHANGED, result.need)
    }
}

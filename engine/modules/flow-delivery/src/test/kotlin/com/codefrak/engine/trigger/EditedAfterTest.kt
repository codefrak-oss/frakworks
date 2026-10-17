package com.codefrak.engine.trigger

import com.codefrak.engine.github.IssueEvent
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** [editedAfter]: the edited-after-approval rule, pulled out of [IssueScanner.editedAfter]. */
class EditedAfterTest {
    private val approvalAt: OffsetDateTime = OffsetDateTime.parse("2026-01-01T00:00:00Z")
    private val before: OffsetDateTime = approvalAt.minusHours(1)
    private val after: OffsetDateTime = approvalAt.plusHours(1)

    private fun event(event: String, at: OffsetDateTime) = IssueEvent(1, event, "someone", at)

    @Test
    fun `a rename after approval is edited after`() {
        assertEquals(true, editedAfter(approvalAt, listOf(event("renamed", after)), null))
    }

    @Test
    fun `a rename before approval is not edited after`() {
        assertEquals(false, editedAfter(approvalAt, listOf(event("renamed", before)), null))
    }

    @Test
    fun `a rename at the same instant as approval is not edited after`() {
        assertEquals(false, editedAfter(approvalAt, listOf(event("renamed", approvalAt)), null))
    }

    @Test
    fun `a non-renamed event after approval is not edited after`() {
        assertEquals(false, editedAfter(approvalAt, listOf(event("labeled", after)), null))
    }

    @Test
    fun `a body edit after approval is edited after`() {
        assertEquals(true, editedAfter(approvalAt, emptyList(), after))
    }

    @Test
    fun `a body edit before approval is not edited after`() {
        assertEquals(false, editedAfter(approvalAt, emptyList(), before))
    }

    @Test
    fun `a null body edit is not edited after`() {
        assertEquals(false, editedAfter(approvalAt, emptyList(), null))
    }
}

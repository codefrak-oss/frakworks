package com.codefrak.engine.intake

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** [undoNotes]'s sentence cases and [cancelComment]'s joining, pulled out of [FlowCanceller.undo] (#2332). */
class FlowCancelWordingTest {
    private fun stopped(
        sandboxes: Int = 0, pr: Long? = null, merged: Long? = null, branch: String? = null,
    ) = Stopped("flow-1", "intake", sandboxes, pr, merged, branch)

    @Test
    fun `no sandboxes, no pr, no merge, no branch is no notes`() {
        assertEquals(emptyList(), undoNotes(stopped(), null))
    }

    @Test
    fun `a stopped sandbox notes it`() {
        assertEquals(listOf("Its running Smith is stopped."), undoNotes(stopped(sandboxes = 1), null))
    }

    @Test
    fun `a closed pr notes it closed unmerged`() {
        assertEquals(listOf("PR #42 is closed unmerged."), undoNotes(stopped(pr = 42), true))
    }

    @Test
    fun `a pr that could not be closed notes it by hand`() {
        assertEquals(listOf("PR #42 could not be closed; close it by hand."), undoNotes(stopped(pr = 42), false))
    }

    @Test
    fun `no pr is no note, even with prClosed null`() {
        assertEquals(emptyList(), undoNotes(stopped(pr = null), null))
    }

    @Test
    fun `an already merged pr notes it stays merged`() {
        assertEquals(listOf("PR #42 was already merged and stays merged."), undoNotes(stopped(merged = 42), null))
    }

    @Test
    fun `a kept branch notes it`() {
        assertEquals(listOf("Kept: branch `work` and every commit on it."), undoNotes(stopped(branch = "work"), null))
    }

    @Test
    fun `every case at once, in order`() {
        val flow = stopped(sandboxes = 1, pr = 42, merged = 7, branch = "work")
        assertEquals(
            listOf(
                "Its running Smith is stopped.",
                "PR #42 is closed unmerged.",
                "PR #7 was already merged and stays merged.",
                "Kept: branch `work` and every commit on it.",
            ),
            undoNotes(flow, true),
        )
    }

    @Test
    fun `cancelComment drops an empty notes paragraph`() {
        assertEquals("the head\n\n— engine", cancelComment("the head", emptyList()))
    }

    @Test
    fun `cancelComment joins non-empty notes with spaces`() {
        assertEquals(
            "the head\n\nfirst. second.\n\n— engine",
            cancelComment("the head", listOf("first.", "second.")),
        )
    }

    @Test
    fun `cancelComment always puts engine last`() {
        val comment = cancelComment("the head", listOf("a note."))
        assertEquals("— engine", comment.lines().last())
    }
}

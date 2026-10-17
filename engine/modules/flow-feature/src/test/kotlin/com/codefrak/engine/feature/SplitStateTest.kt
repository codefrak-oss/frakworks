package com.codefrak.engine.feature

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SplitStateTest {
    private fun draft(title: String, vararg deps: Int) = ChildDraft(title, "## Goal\n\n$title", "chore", dependsOn = deps.toList(), points = 3)

    @Test
    fun `a split child's drafts go in its place, ahead of the drafts left, renumbered over the whole split`() {
        // Filed: 0 (#10, merged), 1 (#11, open). Left: C (index 2) after 1, D (index 3) after 0 and 2.
        val state = SplitState(
            drafts = listOf(draft("C", 1), draft("D", 0, 2)),
            filed = listOf(
                FiledChild(0, "A", 10, "u", "chore", SplitState.MERGED, 7),
                FiledChild(1, "B", 11, "u", "chore"),
            ),
        )
        val split = state.splitInto(11, listOf(draft("B1"), draft("B2", 0)))

        assertEquals(SplitState.SPLIT, split.filed.single { it.number == 11L }.state)
        assertEquals(listOf("B1", "B2", "C", "D"), split.drafts.map { it.title })
        // B1 at 2, B2 at 3 after B1; C waited on #11, now on its last part (3); D's 2 moved to 4.
        assertEquals(listOf(emptyList(), listOf(2), listOf(3), listOf(0, 4)), split.drafts.map { it.dependsOn })
        assertEquals("B1", split.next()?.title)
    }

    @Test
    fun `closed without drafts is a merge or a satisfied close, as before`() {
        val state = SplitState(filed = listOf(FiledChild(0, "A", 10, "u", "chore")))
        assertEquals(SplitState.MERGED, state.closed(10, 7, null).filed.single().state)
        assertEquals(SplitState.SATISFIED, state.closed(10, null, emptyList()).filed.single().state)
    }

    @Test
    fun `the drafts round-trip as child closed carries them`() {
        val drafts = listOf(draft("B1"), draft("B2", 0))
        assertEquals(drafts, ChildDraft.parse(ChildDraft.bytes(drafts)))
        assertEquals(null, ChildDraft.parse(null))
    }

    @Test
    fun `three independent drafts are all ready at once, and open lists them once filed`() {
        var state = SplitState(drafts = listOf(draft("A"), draft("B"), draft("C")))
        assertEquals(listOf("A", "B", "C"), state.ready().map { it.title })
        state.ready().forEachIndexed { i, d -> state = state.filed(d, 10L + i, "u", "chore") }
        assertEquals(emptyList(), state.ready())
        assertEquals(listOf(10L, 11L, 12L), state.open().map { it.number })
    }

    @Test
    fun `a chain is ready one link at a time, as each dependency merges`() {
        var state = SplitState(drafts = listOf(draft("A"), draft("B", 0), draft("C", 1)))
        assertEquals(listOf("A"), state.ready().map { it.title })
        state = state.filed(state.ready().single(), 10, "u", "chore")
        assertEquals(emptyList(), state.ready(), "B waits on A, still open")
        state = state.closed(10, 7)
        assertEquals(listOf("B"), state.ready().map { it.title })
        assertEquals(emptyList(), state.open())
    }

    @Test
    fun `closing one of several open children leaves the others open`() {
        var state = SplitState(drafts = listOf(draft("A"), draft("B"), draft("C", 0)))
        state.ready().forEachIndexed { i, d -> state = state.filed(d, 10L + i, "u", "chore") }
        state = state.closed(10, 7)
        assertEquals(listOf(11L), state.open().map { it.number })
        assertEquals(listOf("C"), state.ready().map { it.title }, "C waited on A (index 0) alone")
        state = state.closed(11, null)
        assertEquals(emptyList(), state.open())
    }

    @Test
    fun `a work item child round-trips its item id, and state from before items reads with none`() {
        val state = SplitState(drafts = listOf(draft("A"), draft("B", 0))).filedItem(draft("A"), "item-1", "chore")
        val back = SplitState.parse(state.bytes())
        assertEquals(listOf("item-1"), back.filed.map { it.item })
        assertEquals("B", back.closedItem("item-1", null, null).next()?.title)
        val old = SplitState.parse("""{"drafts":[],"filed":[{"index":0,"title":"A","number":10,"url":"u","kind":"chore"}],"dropped":[]}""".toByteArray())
        assertEquals(listOf<String?>(null), old.filed.map { it.item })
    }
}

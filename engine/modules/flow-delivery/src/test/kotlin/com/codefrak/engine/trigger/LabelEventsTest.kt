package com.codefrak.engine.trigger

import com.codefrak.engine.feature.FeatureMode
import com.codefrak.engine.github.IssueEvent
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [labelsAt], [kindsAt] and [kindChangedAfter]: replaying an issue's label events up to its approval. */
class LabelEventsTest {
    private fun at(n: Long) = OffsetDateTime.parse("2026-10-0${n}T10:00:00Z")
    private fun event(id: Long, event: String, label: String? = null) = IssueEvent(id, event, "someone", at(1), label)

    @Test
    fun `a label added then removed before the approval is not present`() {
        val events = listOf(
            event(1, "labeled", "bug"),
            event(2, "unlabeled", "bug"),
            event(3, "labeled", "authorized"),
        )
        val approval = events[2]
        assertEquals(emptySet(), labelsAt(setOf("bug"), approval, events))
    }

    @Test
    fun `only labels in of are replayed`() {
        val events = listOf(
            event(1, "labeled", "bug"),
            event(2, "labeled", "do-not-merge"),
            event(3, "labeled", "authorized"),
        )
        val approval = events[2]
        assertEquals(setOf("bug"), labelsAt(setOf("bug", "feature"), approval, events))
    }

    @Test
    fun `events after the approval are ignored by labelsAt`() {
        val events = listOf(
            event(1, "labeled", "bug"),
            event(2, "labeled", "authorized"),
            event(3, "unlabeled", "bug"),
        )
        val approval = events[1]
        assertEquals(setOf("bug"), labelsAt(setOf("bug"), approval, events))
    }

    @Test
    fun `kindsAt replays only the kind labels`() {
        val events = listOf(
            event(1, "labeled", "feature"),
            event(2, "labeled", "do-not-merge"),
            event(3, "labeled", "authorized"),
        )
        val approval = events[2]
        assertEquals(setOf("feature"), kindsAt(approval, events))
    }

    @Test
    fun `kindChangedAfter is true for a kind label event after the approval`() {
        val events = listOf(
            event(1, "labeled", "feature"),
            event(2, "labeled", "authorized"),
            event(3, "labeled", "bug"),
        )
        val approval = events[1]
        assertTrue(kindChangedAfter(approval, events))
    }

    @Test
    fun `kindChangedAfter is true for a feature-mode label event after the approval`() {
        val events = listOf(
            event(1, "labeled", "feature"),
            event(2, "labeled", "authorized"),
            event(3, "labeled", FeatureMode.label(FeatureMode.AUTO)),
        )
        val approval = events[1]
        assertTrue(kindChangedAfter(approval, events))
    }

    @Test
    fun `kindChangedAfter is false for an unrelated label after the approval`() {
        val events = listOf(
            event(1, "labeled", "feature"),
            event(2, "labeled", "authorized"),
            event(3, "labeled", "do-not-merge"),
        )
        val approval = events[1]
        assertFalse(kindChangedAfter(approval, events))
    }

    @Test
    fun `kindChangedAfter is false for a kind label event before the approval`() {
        val events = listOf(
            event(1, "labeled", "feature"),
            event(2, "labeled", "bug"),
            event(3, "labeled", "authorized"),
        )
        val approval = events[2]
        assertFalse(kindChangedAfter(approval, events))
    }
}

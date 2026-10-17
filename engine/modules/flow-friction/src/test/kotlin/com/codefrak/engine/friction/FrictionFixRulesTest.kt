package com.codefrak.engine.friction

import com.codefrak.engine.github.ClosingPull
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure rules of a friction group's fixed-by link: the phrase, every transition, recurrence (engine/README.md, "Friction notes", "Fixed by"). */
class FrictionFixRulesTest {
    private val merged = OffsetDateTime.parse("2026-10-06T12:00:00Z")
    private fun pr(state: String, at: OffsetDateTime? = null) = ClosingPull(1, "https://github.com/acme/a/pull/1", state, at)

    @Test
    fun `the phrase names one or several groups, any case`() {
        assertEquals(listOf(6L), FrictionFixRules.groupsNamed("Context: (friction group g6, 11 notes, high)"))
        assertEquals(listOf(6L, 9L, 12L), FrictionFixRules.groupsNamed("friction groups g6, g9 and g12"))
        assertEquals(listOf(6L, 9L), FrictionFixRules.groupsNamed("FRICTION GROUP G6 ... and friction group g9; again friction group g6"))
        assertEquals(emptyList(), FrictionFixRules.groupsNamed("group g6 alone is not the phrase"))
        assertEquals(emptyList(), FrictionFixRules.groupsNamed(null))
    }

    @Test
    fun `open is fix filed, a merged PR fixes, not planned clears, completed without a merge stays filed`() {
        assertEquals(FixTransition(FrictionFix.FILED), FrictionFixRules.transition(null, FixTicket("OPEN", null, emptyList())), "open → fix filed")
        assertEquals(FixTransition(FrictionFix.FIXED, merged),
            FrictionFixRules.transition(FrictionFix.FILED, FixTicket("CLOSED", "completed", listOf(pr("CLOSED"), pr("MERGED", merged)))), "fix filed → fixed")
        for (current in listOf(FrictionFix.FILED, FrictionFix.FIXED)) {
            val t = FrictionFixRules.transition(current, FixTicket("CLOSED", "not_planned", listOf(pr("MERGED", merged))))
            assertNull(t.state, "$current → open, link cleared")
        }
        val unmerged = FrictionFixRules.transition(FrictionFix.FILED, FixTicket("CLOSED", "completed", listOf(pr("CLOSED"))))
        assertEquals(FrictionFix.FILED, unmerged.state, "closed as completed with no merged PR stays fix filed")
        assertTrue(unmerged.why!!.contains("no merged PR"), "and is logged")
    }

    @Test
    fun `only a note after the merge recurs`() {
        assertTrue(FrictionFixRules.recurs(merged.plusMinutes(1), merged))
        assertFalse(FrictionFixRules.recurs(merged.minusDays(1), merged))
        assertFalse(FrictionFixRules.recurs(merged.plusDays(1), null))
    }
}

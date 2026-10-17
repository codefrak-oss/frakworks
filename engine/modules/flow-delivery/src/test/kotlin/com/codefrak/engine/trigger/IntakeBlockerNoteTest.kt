package com.codefrak.engine.trigger

import com.codefrak.engine.github.Blocker
import com.codefrak.engine.smith.IntakeRef
import kotlin.test.Test
import kotlin.test.assertEquals

/** [intakeNote]: the four texts for a blocker's intake ref, and the empty answer for a null one. */
class IntakeBlockerNoteTest {
    private fun blocker(number: Long = 7) = Blocker("owner/repo", number, "closed")
    private fun ref(state: String, ticket: Long? = null, ended: String? = null) =
        IntakeRef(ref = "#7", intake = "intake-1", repo = "owner/repo", issue = 7, state = state, ticket = ticket, ended = ended)

    @Test
    fun `ended intake says it ended without filing`() {
        assertEquals(
            ": it is intake `intake-1`'s issue, and that intake ended without filing a ticket (cancelled)",
            intakeNote(blocker(), ref(IntakeRef.ENDED, ended = "cancelled")),
        )
    }

    @Test
    fun `filed intake with the blocker's own number was refined into this ticket`() {
        assertEquals(
            ": it is intake `intake-1`'s issue, refined into this ticket and then closed",
            intakeNote(blocker(7), ref(IntakeRef.FILED, ticket = 7)),
        )
    }

    @Test
    fun `filed intake with another ticket number names it to block on`() {
        assertEquals(
            ": it is intake `intake-1`'s issue, and that intake filed #42; to wait for it, block this on #42",
            intakeNote(blocker(7), ref(IntakeRef.FILED, ticket = 42)),
        )
    }

    @Test
    fun `refining intake has not filed a ticket yet`() {
        assertEquals(
            ": it is intake `intake-1`'s issue, and that intake has not filed a ticket",
            intakeNote(blocker(), ref(IntakeRef.REFINING)),
        )
    }

    @Test
    fun `a null ref answers the empty string`() {
        assertEquals("", intakeNote(blocker(), null))
    }
}

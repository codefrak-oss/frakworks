package com.codefrak.engine.workitems

import com.codefrak.engine.github.Blocker
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The scan's reads of a ticket on [Tickets]: open, its labels as `labeled` events, its blockers, and a flag set. */
class TicketReadsTest {
    private val repo = "acme/widgets"
    private val tickets = FakeTickets()

    private fun ticket(n: Long, kind: String = "chore", mode: String? = null, flags: WorkItemFlags = WorkItemFlags()) =
        tickets.create(NewWorkItem(repo, "# Tidy\n\ntidy it", kind, WorkItemSourceRef(WorkItemSourceRef.RAISED), mode = mode, ticket = n, flags = flags))

    @Test
    fun `labelEvents gives the labels as engine's labeled events at its creation, ids 1 to n`() {
        val item = ticket(100_001, "feature", "auto", WorkItemFlags(authorized = true))
        val events = tickets.labelEvents(repo, 100_001)
        assertEquals(listOf("feature", "feature:auto", "authorized"), events.map { it.label })
        assertEquals(listOf(1L, 2L, 3L), events.map { it.id })
        assertTrue(events.all { it.event == "labeled" && it.actor == "engine" && it.at == item.createdAt })
    }

    @Test
    fun `isOpen is false once the ticket ends, and an unknown ticket is refused`() {
        val item = ticket(100_001)
        assertTrue(tickets.isOpen(repo, 100_001))
        tickets.setState(item.id, TicketState.CANCELLED, "dropped")
        assertFalse(tickets.isOpen(repo, 100_001))
        assertFailsWith<WorkItemRefused> { tickets.isOpen(repo, 100_002) }
    }

    @Test
    fun `blockers give each blocker's state - open, or closed completed or not planned`() {
        val parent = ticket(100_001)
        val (a, b, c) = listOf(100_002L, 100_003L, 100_004L).map { ticket(it) }
        listOf(a, b, c).forEach { tickets.block(parent.id, it.id) }
        tickets.setState(b.id, TicketState.DONE, "merged")
        tickets.setState(c.id, TicketState.CANCELLED, "dropped")
        assertEquals(listOf(Blocker(repo, 100_002, "open"), Blocker(repo, 100_003, "closed", "completed"), Blocker(repo, 100_004, "closed", "not_planned")),
            tickets.blockers(repo, 100_001))
        assertEquals(emptyList(), tickets.blockers(repo, 100_009), "none for an unknown ticket")
    }

    @Test
    fun `flag sets one flag and keeps the others`() {
        ticket(100_001, flags = WorkItemFlags(refactor = true))
        tickets.flag(repo, 100_001, "authorized")
        assertEquals(WorkItemFlags(refactor = true, authorized = true), tickets.forTicket(repo, 100_001)!!.flags)
    }
}

package com.codefrak.engine.workitems

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The [FakeTickets] fixture behaves as the store does where the flow tests lean on it. */
class FakeTicketsTest {
    private val told = mutableListOf<Triple<Long?, String, String>>()
    private val tickets = FakeTickets(mutableListOf(TicketStateListener { item, from, to -> told += Triple(item.ticket, from, to) }))

    private fun new(repo: String = "acme/widgets") =
        tickets.create(NewWorkItem(repo, "# Fix it\n\nThe body", "chore", WorkItemSourceRef(WorkItemSourceRef.RAISED)))

    @Test
    fun `create numbers from 100000 and setState tells each listener from and to`() {
        val item = new()
        assertEquals(100_000L, item.ticket)
        assertEquals(100_001L, new().ticket)
        tickets.setState(item.id, TicketState.CANCELLED, "dropped")
        assertEquals(listOf<Triple<Long?, String, String>>(Triple(100_000L, WorkItem.READY, WorkItem.CANCELLED)), told)
        assertEquals(WorkItem.CANCELLED, tickets.state(item.id))
        assertTrue(tickets.history(item.id).any { it.body == "cancelled: dropped" })
    }

    @Test
    fun `an unknown id is refused, and its reads are null`() {
        assertThrows<WorkItemRefused> { tickets.setState("nope", TicketState.DONE, "x") }
        assertNull(tickets.kind("nope")); assertNull(tickets.mode("nope")); assertNull(tickets.state("nope"))
        assertThrows<WorkItemRefused> { tickets.end("acme/widgets", 5, TicketState.DONE, "x") }
    }

    @Test
    fun `end looks the ticket up by repo and number and ends an open one once`() {
        val item = new()
        new("acme/other")
        assertEquals(listOf(item.id), tickets.open("acme/widgets").map { it.id })
        assertEquals(item.id, tickets.forTicket("ACME/widgets", item.ticket!!)?.id)
        assertTrue(tickets.end("acme/widgets", item.ticket!!, TicketState.DONE, "merged"))
        assertFalse(tickets.end("acme/widgets", item.ticket!!, TicketState.CANCELLED, "again"))
        assertEquals(WorkItem.DONE, tickets.state(item.id))
        assertEquals(1, told.size)
        assertEquals(emptyList<WorkItem>(), tickets.open("acme/widgets"))
    }

    @Test
    fun `a listener that throws leaves the ticket as it was`() {
        val item = new()
        tickets.listeners += TicketStateListener { _, _, _ -> error("GitHub down") }
        assertThrows<IllegalStateException> { tickets.setState(item.id, TicketState.DONE, "merged") }
        assertTrue(tickets.forTicket("acme/widgets", item.ticket!!)!!.open)
    }

    @Test
    fun `adopt answers an open ticket for a number never filed`() {
        val adopting = FakeTickets(adopt = true)
        val item = adopting.forTicket("acme/widgets", 2532)!!
        assertEquals(2532L, item.ticket); assertTrue(item.open)
        assertEquals(item.id, adopting.forTicket("acme/widgets", 2532)?.id)
    }

    @Test
    fun `adopt mints a fresh open duplicate for a number it closed itself, unless reuseClosed is on`() {
        val adopting = FakeTickets(adopt = true)
        val item = adopting.forTicket("acme/widgets", 100)!!
        assertTrue(adopting.end("acme/widgets", 100, TicketState.DONE, "nothing to do"))
        val again = adopting.forTicket("acme/widgets", 100)!!
        assertTrue(again.open, "adopt's default reuses a closed number as a fresh chore")
        assertNotEquals(item.id, again.id)

        val reusing = FakeTickets(adopt = true, reuseClosed = true)
        val reused = reusing.forTicket("acme/widgets", 100)!!
        assertTrue(reusing.end("acme/widgets", 100, TicketState.DONE, "nothing to do"))
        val reusedAgain = reusing.forTicket("acme/widgets", 100)!!
        assertEquals(reused.id, reusedAgain.id, "forTicket found a different item: open=${reusedAgain.open}, state=${reusedAgain.state}")
        assertFalse(reusedAgain.open)
    }

    @Test
    fun `ofReason maps not_planned to cancelled and anything else to done`() {
        assertEquals(TicketState.CANCELLED, TicketState.ofReason("not_planned"))
        assertEquals(TicketState.DONE, TicketState.ofReason("completed"))
    }
}

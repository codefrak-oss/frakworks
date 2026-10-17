package com.codefrak.engine.workitems

import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.events.EventRecorder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The engine's tickets ([WorkItemTickets]): every ticket is a work item, numbered from 100000 up, written and read through
 * the store's [Tickets] and `TicketReads` with no GitHub client; a lower number (a pre-cutover GitHub issue) is refused as
 * an unknown ticket.
 */
@SpringBootTest
class WorkItemTicketsTest(@Autowired val store: WorkItemStore, @Autowired val jdbc: JdbcClient, @Autowired val bus: EngineEvents) {
    private val repo = "acme/widgets"
    private val tickets: Tickets get() = store

    @BeforeEach
    fun clear() { jdbc.sql("DELETE FROM engine.work_item").update() }

    private fun file(title: String, body: String, kind: String, mode: String? = null, flags: WorkItemFlags = WorkItemFlags()): Long =
        tickets.create(NewWorkItem(repo, "# $title\n\n$body", kind, WorkItemSourceRef(WorkItemSourceRef.RAISED), mode = mode,
            author = WorkItemTickets.ENGINE, flags = flags)).ticket!!

    @Test
    fun `a filed ticket is a work item numbered from 100000, read back as the flows read it`() {
        val n = file("Tidy the readme", "Tidy it.\n\n<!-- engine: split of #100001 · by lundmatt1 -->", "feature", "auto", WorkItemFlags(refactor = true))
        assertTrue(n >= WorkItemTickets.FIRST)
        val item = store.forTicket(repo, n)!!
        assertEquals("feature", item.kind); assertEquals("auto", item.mode)
        assertEquals(listOf("feature", "feature:auto", "refactor"), item.labelSet)

        val snapshot = tickets.snapshot(repo, n)
        assertEquals("Tidy the readme", snapshot.title)
        assertEquals("Tidy it.\n\n<!-- engine: split of #100001 · by lundmatt1 -->", snapshot.body)
        assertEquals(listOf(n), tickets.open(repo).filter { it.flags.refactor }.map { it.ticket })
        assertEquals(listOf("feature", "feature:auto", "refactor"), tickets.labelEvents(repo, n).map { it.label })
        assertTrue(tickets.isOpen(repo, n))
        assertEquals(listOf(n), tickets.openListings(repo).map { it.number })
    }

    @Test
    fun `comments, flags, edits and ends are written to the item`() {
        val n = file("Fix it", "the body", "bug")
        tickets.commentOn(repo, n, "Started.")
        tickets.flag(repo, n, "authorized")
        val id = tickets.item(repo, n).id
        tickets.retitle(id, "Fix it now", WorkItemTickets.ENGINE)
        tickets.editBody(id, "a new body", WorkItemTickets.ENGINE)
        val item = store.forTicket(repo, n)!!
        assertEquals("Fix it now", item.title); assertEquals("a new body", item.body)
        assertEquals(listOf("bug", "authorized"), item.labelSet)
        assertTrue(item.entries.any { it.kind == WorkItemEntry.CONVERSATION && it.body == "Started." && it.author == "engine" })
        assertEquals(listOf("bug", "authorized"), tickets.labelEvents(repo, n).map { it.label })

        tickets.end(repo, n, TicketState.DONE, "closed")
        assertEquals(WorkItem.DONE, store.forTicket(repo, n)!!.state)
        assertFalse(tickets.isOpen(repo, n))
        assertNull(tickets.openListing(repo, n))

        val m = file("Drop it", "body", "chore")
        tickets.end(repo, m, TicketState.ofReason("not_planned"), "closed")
        assertEquals(WorkItem.CANCELLED, store.forTicket(repo, m)!!.state)
    }

    @Test
    fun `an end publishes the ticket's closed frame through IssueCloseFrames, once`() = EventRecorder(bus, repo).use { rec ->
        val n = file("Fix it", "the body", "bug")
        rec.drain()
        tickets.end(repo, n, TicketState.DONE, "closed")
        tickets.end(repo, n, TicketState.DONE, "closed")
        val closed = rec.of("ticket").filter { it.str("action") == "closed" }
        assertEquals(listOf(n), closed.map { it.long("number") })
    }

    @Test
    fun `blocked by is a store dependency, read back with each blocker's state`() {
        val parent = file("Parent", "p", "feature", "auto")
        val child = file("Child", "c", "chore")
        tickets.block(tickets.item(repo, parent).id, tickets.item(repo, child).id)
        assertEquals(listOf("open"), tickets.blockers(repo, parent).map { it.state })

        tickets.end(repo, child, TicketState.DONE, "closed")
        val closed = tickets.blockers(repo, parent).single()
        assertEquals(child, closed.number); assertEquals("closed", closed.state); assertEquals("completed", closed.stateReason)

        tickets.unblock(tickets.item(repo, parent).id, tickets.item(repo, child).id)
        assertEquals(emptyList(), tickets.blockers(repo, parent))
    }

    @Test
    fun `an ask filed by the intake gets a ticket number too`() {
        val item = store.create(NewWorkItem(repo, "an ask", WorkItem.ASK, WorkItemSourceRef(WorkItemSourceRef.ASK, "intake-1")))
        assertTrue(item.ticket!! >= WorkItemTickets.FIRST)
        val refined = store.refine(item.id, "chore", 3, null, "# Tidy\n\nTidy it.")
        assertEquals(item.ticket, refined.ticket)
        assertEquals("Tidy", tickets.snapshot(repo, item.ticket!!).title)
    }

    @Test
    fun `a ticket below 100000 is refused as unknown, with no GitHub client to fall back to`() {
        val n = 2627L
        listOf<() -> Any?>(
            { tickets.commentOn(repo, n, "hi") }, { tickets.flag(repo, n, "x") }, { tickets.snapshot(repo, n) },
            { tickets.labelEvents(repo, n) }, { tickets.isOpen(repo, n) }, { tickets.item(repo, n) },
            { tickets.end(repo, n, TicketState.DONE, "closed") },
        ).forEach { call -> assertFailsWith<WorkItemRefused> { call() } }
        assertNull(tickets.openListing(repo, n))
        assertEquals(emptyList(), tickets.blockers(repo, n))
        assertEquals(emptyList(), tickets.openListings(repo))
    }
}

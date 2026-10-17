package com.codefrak.engine.intake

import com.codefrak.engine.github.CreatedIssue
import com.codefrak.engine.workitems.FakeTickets
import com.codefrak.engine.workitems.NewWorkItem
import com.codefrak.engine.workitems.TicketState
import com.codefrak.engine.workitems.WorkItemEntry
import com.codefrak.engine.workitems.WorkItemRefused
import com.codefrak.engine.workitems.WorkItemSourceRef
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The decisions flow-intake made on the GitHub issue client, made on [FakeTickets] (#100037): the same answer for
 * file-once detection, a ticket's open or closed state, a cancel's end, blocked-by deferral, comments and body edits.
 */
class IntakeTicketsDecisionsTest {
    private val repo = "acme/tickets"
    private val tickets = FakeTickets()

    private fun file(body: String, kind: String = "chore", blockedBy: List<String> = emptyList()) =
        tickets.create(NewWorkItem(repo, "# A ticket\n\n$body", kind, WorkItemSourceRef(WorkItemSourceRef.RAISED), blockedBy = blockedBy))

    @Test
    fun `file once finds the open ticket whose trailer names the intake, as the open-issues scan did`() {
        file("## Goal\n\nOther.\n\n<!-- engine: intake i-2 · tier 3 -->")
        val ours = file("## Goal\n\nThis.\n\n<!-- engine: intake i-1 · tier 3 -->")
        assertEquals(CreatedIssue(ours.ticket!!, "https://github.com/$repo/issues/${ours.ticket}"), FileOnce.filedFor(tickets, "i-1", repo))
    }

    @Test
    fun `file once ignores a closed ticket and an unmarked one, so the intake files anew`() {
        file("## Goal\n\nNo trailer.")
        val closed = file("## Goal\n\nDone.\n\n<!-- engine: intake i-1 · tier 3 -->")
        tickets.setState(closed.id, TicketState.DONE, "merged")
        assertNull(FileOnce.filedFor(tickets, "i-1", repo))
        assertNull(FileOnce.filedFor(tickets, "i-1", "acme/other"), "another repo's tickets are not looked at")
    }

    @Test
    fun `an open blocker reads as still open, so the start is deferred`() {
        val blocker = file("blocker")
        val ticket = file("blocked", blockedBy = listOf(blocker.id))
        val blockers = tickets.blockers(repo, ticket.ticket!!)
        assertEquals(listOf(blocker.ticket), blockers.map { it.number })
        assertTrue(blockers.any { it.stillOpen() })
    }

    @Test
    fun `a closed blocker reads as closed with its reason, so the start goes on`() {
        val done = file("done")
        val dropped = file("dropped")
        val ticket = file("blocked", blockedBy = listOf(done.id, dropped.id))
        tickets.setState(done.id, TicketState.DONE, "merged")
        tickets.setState(dropped.id, TicketState.CANCELLED, "not needed")
        val blockers = tickets.blockers(repo, ticket.ticket!!)
        assertFalse(blockers.any { it.stillOpen() })
        assertEquals(listOf("completed", "not_planned"), blockers.map { it.stateReason })
        assertEquals(emptyList(), tickets.blockers(repo, 42), "an unknown ticket has no blockers")
    }

    @Test
    fun `a cancel ends an open ticket as cancelled, and leaves an already closed one as it is`() {
        val open = file("open")
        assertTrue(tickets.ticket(repo, open.ticket!!).open, "the canceller's isOpen: open")
        assertTrue(tickets.end(repo, open.ticket!!, TicketState.CANCELLED, "cancelled: not needed"))
        assertEquals("cancelled", tickets.state(open.id))

        val merged = file("merged")
        tickets.setState(merged.id, TicketState.DONE, "merged")
        assertFalse(tickets.ticket(repo, merged.ticket!!).open, "the canceller's isOpen: closed, so no close")
        assertFalse(tickets.end(repo, merged.ticket!!, TicketState.CANCELLED, "cancelled: stray flow"))
        assertEquals("done", tickets.state(merged.id), "its state is kept")
    }

    @Test
    fun `an unknown ticket is refused, as the canceller's open read failed and its comments did`() {
        assertThrows<WorkItemRefused> { tickets.ticket(repo, 42) }
        assertThrows<WorkItemRefused> { tickets.commentOn(repo, 42, "hello") }
    }

    @Test
    fun `a comment is the engine's conversation entry on the ticket`() {
        val item = file("body")
        tickets.commentOn(repo, item.ticket!!, "Authorized by its submitter, @lundmatt1.\n\n— engine")
        val said = tickets.conversation(item.id).single()
        assertEquals(WorkItemEntry.CONVERSATION to ENGINE_AUTHOR, said.kind to said.author)
        assertEquals("Authorized by its submitter, @lundmatt1.\n\n— engine", said.body)
    }

    @Test
    fun `an amendment edits the ticket's body under its title, its kind kept`() {
        val item = file("## Goal\n\nBlue.", kind = "feature")
        val amended = TicketAmender.withAmendment(item.body, com.codefrak.engine.smith.Amendment("lundmatt1", "2026-10-15T00:00:00Z", "and round"))
        tickets.editBody(tickets.ticket(repo, item.ticket!!).id, amended, ENGINE_AUTHOR)
        val after = tickets.details(item.id)!!
        assertEquals("A ticket" to "feature", after.title to after.kind)
        assertTrue(after.body.startsWith("## Goal\n\nBlue.") && "and round" in after.body, after.body)
    }
}

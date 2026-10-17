package com.codefrak.engine.sdk

import com.codefrak.engine.workitems.FakeTickets
import com.codefrak.engine.workitems.WorkItem
import com.codefrak.engine.workitems.WorkItemFlags
import com.codefrak.engine.workitems.WorkItemRefused
import com.codefrak.sdk.Ticket
import com.codefrak.sdk.Tickets
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The SDK's [Tickets], called as a plugin would, reach the engine's tickets of the repo each call names. */
class EngineTicketsTest {
    private val engineTickets = FakeTickets()
    private val tickets: Tickets = EngineTickets(engineTickets)

    @Test
    fun `create, read, update and close go to the named repo`() {
        val number = tickets.create("acme/widgets", "A title", "A body", listOf("chore"))
        val item = engineTickets.forTicket("acme/widgets", number)!!
        assertEquals(Triple("acme/widgets", "A title", "chore"), Triple(item.repo, item.title, item.kind))

        assertEquals(Ticket("acme/widgets", number, "A title", "A body", "engine"), tickets.read("acme/widgets", number))

        tickets.update("acme/widgets", number, title = "New title", labels = listOf("bug", "authorized"))
        val edited = engineTickets.details(item.id)!!
        assertEquals(Triple("New title", "A body", "bug"), Triple(edited.title, edited.body, edited.kind))
        assertEquals(WorkItemFlags(authorized = true), edited.flags)

        tickets.update("acme/widgets", number, body = "A new body")
        assertEquals("New title" to "A new body", engineTickets.details(item.id)!!.let { it.title to it.body })

        tickets.close("acme/widgets", number, Tickets.NOT_PLANNED)
        assertEquals(WorkItem.CANCELLED, engineTickets.state(item.id), "not_planned ends the ticket cancelled")
    }

    @Test
    fun `a feature's mode comes from its feature label, and a create with no kind is refused`() {
        val number = tickets.create("acme/widgets", "A feature", "", listOf("feature", "feature:confirm"))
        assertEquals("confirm", engineTickets.forTicket("acme/widgets", number)!!.mode)
        assertFailsWith<WorkItemRefused> { tickets.create("acme/widgets", "Untriaged", "", emptyList()) }
    }
}

package com.codefrak.engine.chore

import com.codefrak.engine.engineJson
import com.codefrak.engine.proposal.ProposeResult
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.Ticket
import com.codefrak.engine.smith.WorkResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** [prText]: what a PR's title and summary say, for a work flow and for a proposal flow. */
class PrTextTest {
    private val work = engineJson.writeValueAsBytes(WorkResult(outcome = "done", title = "T", summary = " S \n"))
    private val proposal = engineJson.writeValueAsBytes(
        ProposeResult(outcome = "drafted", path = "docs/proposals/x.md", pitch = "docs/proposals/x.html", summary = " sum \n"))

    @Test
    fun `a work flow is the work result's title and trimmed summary`() {
        assertEquals(PrText("T", "S"), prText("chore", work, null, null))
    }

    @Test
    fun `a proposal flow with a ticket titles 'Proposal, the ticket's title' and uses the proposal's trimmed summary`() {
        val ticket = Brief.ticketBytes(Ticket(1, "Ticket Title", "body", "author"))
        assertEquals(PrText("Proposal: Ticket Title", "sum"), prText("proposal", null, proposal, ticket))
    }

    @Test
    fun `a proposal flow without a ticket falls back to the proposal's path for the title`() {
        assertEquals(PrText("Proposal: docs/proposals/x.md", "sum"), prText("proposal", null, proposal, null))
    }

    @Test
    fun `missing bytes errors with the flow's message`() {
        assertFailsWith<IllegalStateException> { prText("chore", null, null, null) }.let {
            assertEquals("no work result to act on", it.message)
        }
        assertFailsWith<IllegalStateException> { prText("proposal", null, null, null) }.let {
            assertEquals("no proposal to act on", it.message)
        }
    }
}

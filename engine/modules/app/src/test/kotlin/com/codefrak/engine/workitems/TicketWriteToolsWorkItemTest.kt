package com.codefrak.engine.workitems

import com.codefrak.engine.mcp.ApprovalTool
import com.codefrak.engine.mcp.FakeEngine
import com.codefrak.engine.mcp.McpCaller
import com.codefrak.engine.mcp.SearchTool
import com.codefrak.engine.mcp.TicketWriteTools
import com.codefrak.engine.trigger.IssueApprovals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** close_ticket, comment_ticket, search_tickets and approve on engine work items: the store, never a GitHub issue. */
@SpringBootTest
class TicketWriteToolsWorkItemTest(
    @Autowired val store: WorkItemStore, @Autowired val jdbc: JdbcClient,
    @Autowired val details: WorkItemDetails,
) {
    private val engine = FakeEngine().also { it.closes = { repo, n, reason -> store.end(repo, n, TicketState.ofReason(reason), "closed as \$reason") } }
    private val tools by lazy { TicketWriteTools(engine, store) }
    private val mate = McpCaller.Nobody("127.0.0.1", "someone")

    @BeforeEach
    fun clear() { jdbc.sql("DELETE FROM engine.work_item").update() }

    private fun item(kind: String = "chore", description: String = "# Tidy the board\n\nIt is untidy.") =
        store.create(NewWorkItem("acme/widgets", description, kind, WorkItemSourceRef(WorkItemSourceRef.RAISED)))

    private fun closeAs(outcome: String, state: String, text: String) {
        val n = item().ticket!!
        engine.trustedLogins += "someone"
        val r = tools.call("close_ticket", mapOf("ticket" to n, "repo" to "acme/widgets", "reason" to "no longer wanted", "outcome" to outcome), mate)
        assertFalse(r.isError, r.text)
        assertTrue("\"ok\":true" in r.text && "\"state_reason\":\"$outcome\"" in r.text, r.text)
        assertEquals(listOf("close acme/widgets#$n $outcome"), engine.writes)
        val item = store.forTicket("acme/widgets", n)!!
        assertEquals(state, item.state)
        val entry = store.entries(item.id).last { it.kind == WorkItemEntry.CONVERSATION }
        assertEquals("someone", entry.author)
        assertEquals("Closed as $text by @someone through First mate: no longer wanted", entry.body)
        assertEquals(outcome, details.closed("acme/widgets", 10).single { it.number == n }.stateReason)
    }

    @Test
    fun `close_ticket as completed records the login and reason, then closes the item done`() =
        closeAs("completed", WorkItem.DONE, "completed")

    @Test
    fun `close_ticket as not_planned records the login and reason, then closes the item cancelled`() =
        closeAs("not_planned", WorkItem.CANCELLED, "not planned")

    @Test
    fun `close_ticket refuses a closed item, a running flow and an untrusted login, writing nothing`() {
        val made = item()
        val n = made.ticket!!
        val close = mapOf("ticket" to n, "repo" to "acme/widgets", "reason" to "x", "outcome" to "completed")
        assertTrue("@someone is not a trusted login" in tools.call("close_ticket", close, mate).text)
        engine.trustedLogins += "someone"
        engine.running["acme/widgets" to n] = listOf("pi-1")
        assertTrue("a flow is running on acme/widgets#$n" in tools.call("close_ticket", close, mate).text)
        engine.running.clear()
        store.setState(made.id, WorkItem.CANCELLED, "test")
        assertTrue("acme/widgets#$n is already closed; nothing was changed" in tools.call("close_ticket", close, mate).text)
        assertTrue(engine.writes.isEmpty())
    }

    @Test
    fun `comment_ticket appends a conversation entry by the login`() {
        val made = item()
        engine.trustedLogins += "someone"
        val r = tools.call("comment_ticket", mapOf("repo" to "acme/widgets", "ticket" to made.ticket, "text" to "Fixed in the PR."), mate)
        assertFalse(r.isError, r.text)
        val entry = store.entries(made.id).last()
        assertEquals(WorkItemEntry.CONVERSATION to "someone", entry.kind to entry.author)
        assertEquals("Fixed in the PR.", entry.body)
        assertTrue(entry.id in r.text && "\"posted_for\":\"someone\"" in r.text, r.text)
        assertTrue(engine.writes.isEmpty(), "no GitHub write")
    }

    @Test
    fun `comment_ticket on a closed item needs closed true`() {
        val made = item()
        engine.trustedLogins += "someone"
        store.setState(made.id, WorkItem.DONE, "test")
        val reply = mapOf("repo" to "acme/widgets", "ticket" to made.ticket, "text" to "late note")
        assertTrue("acme/widgets#${made.ticket} is closed; pass closed: true" in tools.call("comment_ticket", reply, mate).text)
        assertFalse(tools.call("comment_ticket", reply + ("closed" to true), mate).isError)
        assertEquals("late note", store.entries(made.id).last().body)
    }

    @Test
    fun `comment_ticket refuses an untrusted login, writing nothing`() {
        val made = item()
        val before = store.entries(made.id).size
        val r = tools.call("comment_ticket", mapOf("repo" to "acme/widgets", "ticket" to made.ticket, "text" to "hi"), mate)
        assertTrue(r.isError && "@someone is not a trusted login; nothing was posted" in r.text, r.text)
        assertEquals(before, store.entries(made.id).size)
    }

    @Test
    fun `a number the store does not have is refused by both, writing nothing`() {
        engine.trustedLogins += "someone"
        assertTrue("acme/widgets#100999 is not a ticket: the engine holds no work item with that number; nothing was changed" in tools.call("close_ticket", mapOf("ticket" to 100999, "repo" to "acme/widgets", "reason" to "x", "outcome" to "not_planned"), mate).text)
        assertTrue("acme/widgets#99 is not a ticket: the engine holds no work item with that number; nothing was posted" in tools.call("comment_ticket", mapOf("ticket" to 99, "repo" to "acme/widgets", "text" to "x"), mate).text)
        assertTrue(engine.writes.isEmpty())
    }

    @Test
    fun `search_tickets finds work items by every word, open by default`() {
        val hit = item(description = "# Authorized label color\n\nThe label is the wrong color.")
        item(description = "# Something else")
        val closed = item(description = "# Old label color")
        store.setState(closed.id, WorkItem.DONE, "test")
        val search = SearchTool(engine, store)
        val open = search.call(mapOf("repo" to "acme/widgets", "text" to "LABEL color")).text
        assertTrue("\"count\":1" in open && "\"number\":${hit.ticket}" in open && "\"state\":\"open\"" in open, open)
        assertTrue("\"count\":2" in search.call(mapOf("repo" to "acme/widgets", "text" to "label color", "state" to "all")).text)
        assertTrue("\"count\":0" in search.call(mapOf("repo" to "acme/widgets", "text" to "nothing like it")).text)
    }

    @Test
    fun `approve authorizes a filed research work item through the scan's path, by its kind`() {
        val n = item(kind = "research").ticket!!
        engine.ticketApprovable["acme/widgets" to n] = IssueApprovals.TicketApproved.Done("research", "started", "instance-1", 5L, emptyList())
        val r = ApprovalTool(engine, store).approve(mate, mapOf("ticket" to n, "repo" to "acme/widgets"))
        assertFalse(r.isError, r.text)
        assertEquals(listOf(Triple("acme/widgets", n, "someone")), engine.ticketApprovals)
        assertTrue(engine.approvals.isEmpty(), "not an intake")
    }
}

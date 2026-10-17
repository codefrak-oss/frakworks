package com.codefrak.engine.workitems

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [Tickets] as [WorkItemStore] implements it, and [TicketStateListener] told of each state change. */
@SpringBootTest
@Import(TicketsTest.Listening::class)
class TicketsTest(@Autowired val tickets: Tickets, @Autowired val heard: RecordingStateListener, @Autowired val jdbc: JdbcClient) {
    class RecordingStateListener : TicketStateListener {
        val changes = mutableListOf<String>()
        override fun onStateChanged(item: WorkItem, from: String, to: String) { changes += "${item.id} $from>$to" }
    }

    @TestConfiguration
    class Listening {
        @Bean fun recordingStateListener() = RecordingStateListener()
    }

    private val repo = "acme/widgets"

    @BeforeEach
    fun clear() { jdbc.sql("DELETE FROM engine.work_item").update(); heard.changes.clear() }

    private fun item() = tickets.create(NewWorkItem(repo, "# Fix it\n\nthe body", "bug", WorkItemSourceRef(WorkItemSourceRef.RAISED)))

    @Test
    fun `the store is the engine's Tickets, with typed fields and a history entry for each change`() {
        assertTrue(tickets is WorkItemStore)
        val id = item().id
        tickets.comment(id, "lundmatt1", "a note")
        tickets.setKind(id, "feature"); tickets.setMode(id, "confirm"); tickets.setTier(id, 6)
        val item = tickets.details(id)!!
        assertEquals("feature", item.kind); assertEquals("confirm", item.mode); assertEquals(6, item.tier)
        assertEquals(listOf("feature", "feature:confirm"), item.labelSet)
        assertEquals(listOf("a note"), tickets.conversation(id).map { it.body })
        assertEquals(listOf("lundmatt1"), tickets.conversation(id).map { it.author })
        assertEquals(listOf("created from raised", "kind: feature", "mode: confirm", "tier: 6"), tickets.history(id).map { it.body })
        assertFailsWith<WorkItemRefused> { tickets.setKind(id, "ask") }
        assertFailsWith<WorkItemRefused> { tickets.setTier(id, 11) }
        assertFailsWith<WorkItemRefused> { tickets.comment("nope", "a", "b") }
        assertNull(tickets.details("nope"))
    }

    @Test
    fun `editBody keeps the title and names the old body's revision in a history entry`() {
        val id = item().id
        tickets.editBody(id, "a new body", "smith run r")
        val item = tickets.details(id)!!
        assertEquals("Fix it", item.title); assertEquals("a new body", item.body)
        val entry = tickets.history(id).last()
        assertEquals("smith run r", entry.author)
        assertEquals("body edited (was ${Tickets.bodyRev("the body")})", entry.body)
        assertTrue(Tickets.bodyRev("the body").matches(Regex("sha256:[0-9a-f]{64}")))
    }

    @Test
    fun `block and unblock move a ready item to blocked and keep the dependency`() {
        val a = item().id
        val b = item().id
        tickets.block(a, b)
        assertEquals(listOf(b), tickets.details(a)!!.blockedBy)
        assertEquals(WorkItem.BLOCKED, tickets.details(a)!!.state)
        tickets.unblock(a, b)
        assertEquals(emptyList(), tickets.details(a)!!.blockedBy)
    }

    @Test
    fun `done and cancelled are told to each state listener, a repeat is not`() {
        val a = item().id
        val b = item().id
        tickets.setState(a, TicketState.DONE, "merged")
        tickets.setState(a, TicketState.DONE, "again")
        tickets.setState(b, TicketState.CANCELLED, "not planned")
        assertEquals(WorkItem.DONE, tickets.details(a)!!.state)
        assertEquals(WorkItem.CANCELLED, tickets.details(b)!!.state)
        assertEquals(listOf("$a ready>done", "$b ready>cancelled"), heard.changes)
    }

    @Test
    fun `flags round-trip and read back as labels after kind and mode`() {
        val item = tickets.create(NewWorkItem(repo, "# Tidy", "feature", WorkItemSourceRef(WorkItemSourceRef.RAISED), mode = "auto",
            flags = WorkItemFlags.of(listOf("needs-review", "refactor", "unknown"))))
        assertEquals(WorkItemFlags(refactor = true, needsReview = true), item.flags)
        assertEquals(listOf("feature", "feature:auto", "refactor", "needs-review"), item.labelSet)
    }

    @Test
    fun `forTicket, open, kind, mode and state read the store`() {
        val a = item(); val b = item()
        tickets.setKind(b.id, "feature"); tickets.setMode(b.id, "auto")
        assertEquals(a.id, tickets.forTicket("ACME/widgets", a.ticket!!)?.id)
        assertNull(tickets.forTicket("acme/other", a.ticket!!))
        assertEquals("bug", tickets.kind(a.id)); assertNull(tickets.mode(a.id)); assertEquals(WorkItem.READY, tickets.state(a.id))
        assertEquals("feature", tickets.kind(b.id)); assertEquals("auto", tickets.mode(b.id))
        assertNull(tickets.kind("nope")); assertNull(tickets.mode("nope")); assertNull(tickets.state("nope"))
        assertTrue(tickets.end(repo, a.ticket!!, TicketState.DONE, "merged"))
        assertEquals(false, tickets.end(repo, a.ticket!!, TicketState.CANCELLED, "again"), "an ended ticket stays as it is")
        assertEquals(WorkItem.DONE, tickets.state(a.id))
        assertEquals(listOf(b.id), tickets.open(repo).map { it.id })
        assertFailsWith<WorkItemRefused> { tickets.end(repo, 5, TicketState.DONE, "x") }
    }
}
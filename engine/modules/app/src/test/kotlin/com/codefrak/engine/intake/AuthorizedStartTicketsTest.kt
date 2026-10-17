package com.codefrak.engine.intake

import com.codefrak.engine.chore.FlowStarter
import com.codefrak.engine.github.IssueSnapshot
import com.codefrak.engine.repos.HandledIssues
import com.codefrak.engine.workitems.FakeTickets
import com.codefrak.engine.workitems.NewWorkItem
import com.codefrak.engine.workitems.WorkItemRefused
import com.codefrak.engine.workitems.WorkItemSourceRef
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [AuthorizedStart] on the engine's [com.codefrak.engine.workitems.Tickets] (#100036): the start's comment is a
 * `conversation` entry on the ticket's work item and its `authorized` label the item's flag, with the same decisions
 * as when both were GitHub writes. Against H2 for [HandledIssues]' claims.
 */
@SpringBootTest
class AuthorizedStartTicketsTest(@Autowired val handled: HandledIssues) {
    /** A repo of its own per test: each test's [FakeTickets] numbers from 100000 again, and the claims stay in H2. */
    private val repo = "acme/authorized-start-${java.util.UUID.randomUUID()}"
    private val tickets = FakeTickets()
    private val started = mutableListOf<Long>()
    private var startsFlow = true
    private val starter = object : FlowStarter {
        override fun start(flow: String, repo: String, ticket: IssueSnapshot, authorizedBy: String, model: String?, effort: String?,
                           tier: Int?, extra: Map<String, Any>): String? = if (startsFlow) "pi-${ticket.number}".also { started += ticket.number } else null
        override fun counts(instance: String?) = true
        override fun mergedClose(instance: String?) = false
        override fun steer(instance: String, by: String, message: String) = false
    }
    private val start = AuthorizedStart(handled, starter, tickets)

    private fun filed(kind: String = "feature", mode: String? = "auto") =
        tickets.create(NewWorkItem(repo, "# Blue\n\nbody", kind, WorkItemSourceRef(WorkItemSourceRef.RAISED), mode = mode))

    private fun snapshot(number: Long) = IssueSnapshot(number, "Blue", "body", "alice")

    @Test
    fun `an engine start on a filed ticket starts the flow and says so on the item, with no authorized flag unless asked`() {
        val item = filed()
        val instance = start.start(repo, "feature", snapshot(item.ticket!!), "alice", "authorized by @alice", null, "auto", false)
        assertEquals("pi-${item.ticket}", instance)
        val said = tickets.conversation(item.id).single()
        assertEquals("engine", said.author)
        assertEquals("Feature started in auto mode: authorized by @alice (`$instance`).\n\n— engine", said.body)
        assertFalse(tickets.details(item.id)!!.flags.authorized, "no label asked for")
    }

    @Test
    fun `a refactoring start sets the authorized flag after it, keeping kind and mode`() {
        val item = filed(mode = "confirm")
        start.start(repo, "feature", snapshot(item.ticket!!), "alice", "refactoring job", null, "confirm", false, label = "authorized")
        val after = tickets.details(item.id)!!
        assertTrue(after.flags.authorized)
        assertEquals(listOf("feature", "confirm"), listOf(after.kind, after.mode))
    }

    @Test
    fun `a second start on the same ticket is refused as already started and says nothing more`() {
        val item = filed(kind = "chore", mode = null)
        start.start(repo, "chore", snapshot(item.ticket!!), "alice", "d", null, null, false)
        assertFailsWith<IllegalStateException> { start.start(repo, "chore", snapshot(item.ticket!!), "alice", "d", null, null, false) }
        assertEquals(1, tickets.conversation(item.id).size)
        assertEquals(listOf(item.ticket!!), started)
    }

    @Test
    fun `a start on an unknown ticket is refused and its claim released`() {
        assertFailsWith<WorkItemRefused> { start.start(repo, "chore", snapshot(424_242), "alice", "d", null, null, false) }
        assertFalse(handled.list(repo).any { it.issue == 424_242L }, "the claim is given back")
    }

    @Test
    fun `a flow that does not start writes nothing and releases its claim`() {
        val item = filed(kind = "bug", mode = null)
        startsFlow = false
        assertFailsWith<IllegalStateException> { start.start(repo, "bug", snapshot(item.ticket!!), "alice", "d", null, null, false) }
        assertEquals(emptyList(), tickets.conversation(item.id))
        assertFalse(handled.list(repo).any { it.issue == item.ticket })
    }
}

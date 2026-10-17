package com.codefrak.engine.ci

import com.codefrak.engine.chore.FlowStarter
import com.codefrak.engine.github.IssueSnapshot
import com.codefrak.engine.intake.AuthorizedStart
import com.codefrak.engine.repos.HandledIssues
import com.codefrak.engine.workitems.FakeTickets
import com.codefrak.engine.workitems.TicketState
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import com.codefrak.engine.smith.Vars
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** #100054, #100068: `main`'s build's retried tests become one flaky-test ticket per test. */
class FlakyTestsTest {
    private val repo = "codefrak-oss/frakworks"
    private val url = "https://github.com/codefrak-oss/frakworks/actions/runs/1/job/42"
    private val passing = """
        2026-10-15T12:00:00.0000000Z #11 88.4 > Task :engine-app:test
        2026-10-15T12:00:01.0000000Z #11 90.1 FLAKY-TEST: com.codefrak.engine.ci.CiChecksTest > the setting names a check() passed on retry
        2026-10-15T12:00:02.0000000Z #11 99.0 BUILD SUCCESSFUL in 5m
    """.trimIndent()

    /** The starts made, (flow, ticket number, authorizedBy, tier). */
    private val starts = mutableListOf<List<Any?>>()
    /** Each start's `extra` flow variables, in order. */
    private val extras = mutableListOf<Map<String, Any>>()
    private val starter = object : FlowStarter {
        override fun start(flow: String, repo: String, ticket: IssueSnapshot, authorizedBy: String, model: String?, effort: String?,
                           tier: Int?, extra: Map<String, Any>): String? =
            "pi-${ticket.number}".also { starts += listOf(flow, ticket.number, authorizedBy, tier); extras += extra }
        override fun counts(instance: String?) = true
        override fun mergedClose(instance: String?) = false
        override fun steer(instance: String, by: String, message: String) = false
    }
    /** Claims in memory: no database here. */
    private val handled = object : HandledIssues(JdbcClient.create(SimpleDriverDataSource())) {
        val claimed = mutableSetOf<Pair<String, Long>>()
        override fun claim(repo: String, issue: Long, labelEvent: Long, flow: String, outcome: String, detail: String?, askedBy: String?,
                           points: Int?) = claimed.add(repo to issue)
        override fun started(repo: String, issue: Long, labelEvent: Long, processInstance: String) {}
        override fun release(repo: String, issue: Long, labelEvent: Long) { claimed.remove(repo to issue) }
    }

    private val seen = Instant.parse("2026-10-16T23:30:00Z")

    private fun record(tickets: FakeTickets, log: String, run: Long = 7) =
        FlakyTests.file(tickets, AuthorizedStart(handled, starter, tickets), repo, CiLog.flaky(CiLog.clean(log)), "on `main`'s build (images run $run)", url, "abcdef0123456789", seen)

    @Test
    fun `main's build with a retried test files one ticket naming the test, the build, its commit and its job`() {
        val tickets = FakeTickets()
        val ids = record(tickets, passing)
        assertEquals(1, ids.size)
        val item = tickets.details(ids[0])!!
        assertEquals("Flaky test: com.codefrak.engine.ci.CiChecksTest.the setting names a check", item.title)
        assertEquals("bug", item.kind)
        assertTrue(item.body.contains("on `main`'s build (images run 7)") && item.body.contains(url) && item.body.contains("abcdef012345"), item.body)
        assertTrue(item.flags.authorized, "started on its own, authorized")
        assertEquals(listOf(listOf<Any?>("bug", item.ticket, FlakyTests.BY, FlakyTests.TIER)), starts)
        assertTrue(tickets.conversation(item.id).any { it.body.startsWith("Bug started: authorized by the engine's flaky-test filer, job $url") })
    }

    @Test
    fun `the same test on a later build comments on the open ticket and files no second one`() {
        val tickets = FakeTickets()
        val first = record(tickets, passing).single()
        val again = record(tickets, passing, run = 8).single()
        assertEquals(first, again)
        assertEquals(1, tickets.open(repo).size)
        assertEquals(1, starts.size, "a repeat sighting starts nothing")
        assertTrue(tickets.conversation(first).any { it.body.contains("Seen again") && it.body.contains("images run 8") })
    }

    @Test
    fun `a sighting after the ticket closed files a new one`() {
        val tickets = FakeTickets()
        val first = record(tickets, passing).single()
        tickets.setState(first, TicketState.DONE, "fixed")
        val second = record(tickets, passing).single()
        assertNotEquals(first, second)
        assertEquals(2, starts.size, "the new ticket starts as a first sighting does")
        assertTrue(tickets.details(second)!!.flags.authorized)
    }

    @Test
    fun `every start of a flaky-test ticket carries the flaky-test flow variable`() {
        val tickets = FakeTickets()
        val first = record(tickets, passing).single()
        tickets.setState(first, TicketState.DONE, "fixed")
        record(tickets, passing)
        assertEquals(listOf<Map<String, Any>>(mapOf(Vars.FLAKY_TEST to true), mapOf(Vars.FLAKY_TEST to true)), extras)
    }

    @Test
    fun `a first sighting's body names the one class run and has no fixed-before line`() {
        val tickets = FakeTickets()
        val body = tickets.details(record(tickets, passing).single())!!.body
        assertTrue(body.contains("`cd engine && gradle :engine-app:test --tests com.codefrak.engine.ci.CiChecksTest --no-daemon --offline`"), body)
        assertTrue(body.contains("Never loop it") && body.contains("background it"), body)
        assertFalse(body.contains("Fixed before"), body)
    }

    @Test
    fun `a sighting after the ticket closed names the closed ticket, its suspected cause and the date`() {
        val tickets = FakeTickets()
        val first = record(tickets, passing).single()
        tickets.comment(first, "engine", "Done. PR #9 merged after 1 round(s).\n\nOptimistic flaky-test fix, not verified. Suspected cause: a shared static map.\n\n— engine bug `pi`")
        tickets.setState(first, TicketState.DONE, "merged")
        val body = tickets.details(record(tickets, passing).single())!!.body
        val number = tickets.details(first)!!.ticket
        assertTrue(body.contains("Fixed before in #$number (a shared static map), seen again on 2026-10-16."), body)
        assertTrue(body.indexOf("Later sightings are commented here.") < body.indexOf("Fixed before"), "after the Goal's first paragraph")
    }

    @Test
    fun `a sighting after a ticket closed with no suspected cause says unknown cause`() {
        val tickets = FakeTickets()
        val first = record(tickets, passing).single()
        tickets.setState(first, TicketState.DONE, "fixed")
        val body = tickets.details(record(tickets, passing).single())!!.body
        assertTrue(body.contains("Fixed before in #${tickets.details(first)!!.ticket} (unknown cause), seen again on 2026-10-16."), body)
    }

    @Test
    fun `a build with no retried test files nothing`() {
        val tickets = FakeTickets()
        assertTrue(record(tickets, "> Task :engine-app:test\nBUILD SUCCESSFUL in 5m").isEmpty())
        assertTrue(tickets.open(repo).isEmpty())
    }
}

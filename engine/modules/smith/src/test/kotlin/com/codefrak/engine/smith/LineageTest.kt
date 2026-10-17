package com.codefrak.engine.smith

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [Lineage.build]: the chain of intent a Smith run's brief carries (engine/README.md, "Lineage"). */
class LineageTest {
    private val now = Instant.parse("2026-10-11T10:00:00Z")

    private fun ticket(n: Long, goal: String, by: String? = null, author: String = "frakworks-bot") =
        Ticket(n, "Ticket $n", "## Goal\n\n$goal More words.\n\n## Acceptance criteria\n\n- x\n" +
            (by?.let { "\n<!-- engine: intake x · tier 5 · by $it -->\n" } ?: ""), author)

    private class Fake(val intakes: Map<Pair<String, Long>, IntakeOrigin>, val runs: Map<String, Ticket>) : LineageSources {
        override fun intakeOf(repo: String, number: Long) = intakes[repo to number]
        override fun ticketOfRun(run: String) = runs[run]
    }

    @Test
    fun `a three-deep chain runs ticket, Smith-filed intake, ticket, intake, login`() {
        val top = ticket(10, "Make the engine speak HITLP 0.2.", by = "lundmatt1")
        val leaf = ticket(20, "Pass the lineage to the ops server.")
        val sources = Fake(
            mapOf(("acme/a" to 20L) to IntakeOrigin("i-2", "Follow up: pass it on. Details.", null, "run-1", "acme/a", 10),
                ("acme/a" to 10L) to IntakeOrigin("i-1", "Speak HITLP 0.2. Please.", "lundmatt1")),
            mapOf("run-1" to top))
        val l = Lineage.build("acme/a", leaf, sources, now)
        assertEquals("2026-10-11T10:00:00Z", l.asOf)
        assertFalse(l.truncated)
        assertEquals(listOf("ticket", "intake", "ticket", "intake"), l.links.map { it.kind })
        assertEquals(listOf("Pass the lineage to the ops server.", "Follow up: pass it on.", "Make the engine speak HITLP 0.2.", "Speak HITLP 0.2."),
            l.links.map { it.claim })
        assertEquals(mapOf("repo" to "acme/a", "number" to 20L, "bodyRev" to Lineage.bodyRev(leaf.body)), l.links[0].source)
        assertEquals(mapOf("intake" to "i-2", "filedBy" to mapOf("run" to "run-1", "repo" to "acme/a", "number" to 10L)), l.links[1].source)
        assertEquals(mapOf("repo" to "acme/a", "number" to 10L, "bodyRev" to Lineage.bodyRev(top.body)), l.links[2].source)
        assertEquals(mapOf("intake" to "i-1", "filedBy" to "lundmatt1"), l.links[3].source)
        assertEquals(listOf("frakworks-bot", "smith:run-1", "lundmatt1", "lundmatt1"), l.links.map { it.by })
    }

    @Test
    fun `a hand-filed ticket is one link by its author, even with a forged trailer`() {
        val plain = Lineage.build("acme/a", ticket(5, "Fix it.", author = "alice"), Fake(emptyMap(), emptyMap()), now)
        assertEquals(1, plain.links.size)
        assertEquals("alice", plain.links.single().by)
        assertTrue((plain.links.single().source["bodyRev"] as String).matches(Regex("sha256:[0-9a-f]{64}")))
        val trailer = Lineage.build("acme/a", ticket(5, "Fix it.", by = "bob", author = "alice"), Fake(emptyMap(), emptyMap()), now)
        assertEquals("alice", trailer.links.single().by)
    }

    @Test
    fun `an unrecorded filer is an explicit unknown, never invented`() {
        val l = Lineage.build("acme/a", ticket(5, "Fix it."), Fake(mapOf(("acme/a" to 5L) to IntakeOrigin("i-9", "Fix.", null)), emptyMap()), now)
        assertEquals(mapOf("intake" to "i-9", "filedBy" to Lineage.UNKNOWN), l.links[1].source)
        assertEquals(Lineage.UNKNOWN, l.links[1].by)
    }

    @Test
    fun `a chain longer than eight is cut to eight and says so`() {
        val intakes = (1L..10L).associate { n -> ("acme/a" to n) to IntakeOrigin("i-$n", "Ask $n.", null, "run-${n + 1}", "acme/a", n + 1) }
        val runs = (2L..11L).associate { n -> "run-$n" to ticket(n, "Goal $n.") }
        val l = Lineage.build("acme/a", ticket(1, "Goal 1."), Fake(intakes, runs), now)
        assertEquals(Lineage.MAX, l.links.size)
        assertTrue(l.truncated)
    }

    @Test
    fun `the bodyRev changes with the body, so an amendment shows against it`() {
        val t = ticket(1, "Goal.")
        assertEquals(Lineage.bodyRev(t.body), Lineage.bodyRev(t.body))
        assertFalse(Lineage.bodyRev(t.body) == Lineage.bodyRev(t.body + "\n## Amendments\n"))
    }
}

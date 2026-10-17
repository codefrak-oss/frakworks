package com.codefrak.engine.doctor

import com.codefrak.engine.github.IssueListing
import com.codefrak.engine.jev.JevAnswer
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The `doctor-sighting` question (engine/README.md, "Doctor", "Jev, for a new signature"): its options, its rule on the pick, its outcome texts. */
class SightingCheckTest {
    private val now = OffsetDateTime.now(ZoneOffset.UTC)

    private fun listing(n: Long, labels: List<String> = listOf("bug"), body: String? = "## Goal\n\nThe sandbox dies.\nIt is OOM.") =
        IssueListing(n, "Bug $n", "someone", labels, "https://github.com/acme/defects/issues/$n", now, now.minusMinutes(n), body)

    @Test
    fun `options are the open bug issues, then the closed doctor bugs not among them, cut to 254`() {
        val open = listOf(listing(1), listing(2, labels = listOf("chore")), listing(3))
        val closed = listOf(SightingCheck.ClosedBug(9, "Sandbox OOM", listOf("The sandbox died\nmore", "The sandbox died", "")),
            SightingCheck.ClosedBug(3, "Already open again", emptyList()), SightingCheck.ClosedBug(8, null, emptyList()))
        val options = SightingCheck.candidates(open, closed)
        assertEquals(listOf("#1", "#3", "#9", "#8"), options.map { it.key }, "only bug-labelled open issues; a closed bug listed once")
        assertEquals("Bug 1: The sandbox dies. It is OOM.", options[0].description)
        assertEquals("Closed doctor bug: Sandbox OOM; seen as: The sandbox died", options[2].description)
        assertEquals("Closed doctor bug", options[3].description)

        val many = SightingCheck.candidates((1L..300L).map { listing(it) }, listOf(SightingCheck.ClosedBug(999, "late", emptyList())))
        assertEquals(SightingCheck.MAX, many.size)
        assertEquals("#1", many.first().key)
        val question = SightingCheck.question(many)
        @Suppress("UNCHECKED_CAST")
        val criteria = question.criteria as Map<String, String?>
        assertEquals(255, criteria.size, "254 bugs plus new")
        assertTrue(SightingCheck.NEW in criteria)
        assertEquals("choice", question.type)
    }

    @Test
    fun `a bug at the floor lumps, while new, below the floor, another option or no answer files`() {
        val options = SightingCheck.candidates(listOf(listing(412)), emptyList())
        fun pick(c: String, p: Double?) = JevAnswer("choice", choice = c, confidence = p)
        assertEquals("#412 (0.95): lumped" to options.single(), SightingCheck.read(pick("#412", 0.95), options, 0.9))
        assertEquals("#412 (0.90): lumped" to options.single(), SightingCheck.read(pick("#412", 0.9), options, 0.9), "at the floor")
        assertEquals("#412 below floor 0.9 (0.71): new intake" to null, SightingCheck.read(pick("#412", 0.71), options, 0.9))
        assertEquals("new (0.93): new intake" to null, SightingCheck.read(pick("new", 0.93), options, 0.9))
        assertEquals("#7 (0.99): new intake" to null, SightingCheck.read(pick("#7", 0.99), options, 0.9), "not an option")
        assertEquals("no answer: new intake" to null, SightingCheck.read(null, options, 0.9))
    }

    @Test
    fun `outcomes name the bug in the home repo by number, elsewhere in full, and the truth they show`() {
        assertEquals("#4", SightingCheck.name("acme/d", "ACME/d", 4))
        assertEquals("acme/x#4", SightingCheck.name("acme/d", "acme/x", 4))
        assertEquals("no bug", SightingCheck.name("acme/d", null, null))
        assertEquals("lumped #412: moved to #430 by @u", SightingCheck.moved("#412", "#430", "u"))
        assertEquals("new: filed #430, closed as duplicate of #412 by @u", SightingCheck.filedDuplicate("#430", "#412", "u"))
        assertEquals("#412", SightingCheck.truth(SightingCheck.kept("#412")))
        assertEquals("#430", SightingCheck.truth("lumped #412: moved to #430 by @u"))
        assertEquals(SightingCheck.NEW, SightingCheck.truth(SightingCheck.filedKept("#430")))
        assertNull(SightingCheck.truth("new: filed #430, moved to no bug by @u"))
    }
}

package com.codefrak.engine.proposal

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DecisionTest {
    @Test
    fun `each verb parses, with its why, whatever the case and leading punctuation`() {
        assertEquals(Decision("accept", null), DecisionVerb.parse("accept"))
        assertEquals(Decision("accept", "the cost is fine"), DecisionVerb.parse("Accept: the cost is fine"))
        assertEquals(Decision("build", null), DecisionVerb.parse("accept and build"))
        assertEquals(Decision("build", "go"), DecisionVerb.parse("> Accept  and\nbuild: go"))
        assertEquals(Decision("build-auto", null), DecisionVerb.parse("accept and build auto"))
        assertEquals(Decision("revise", "add a cost section\nand an alternative"), DecisionVerb.parse("revise: add a cost section\nand an alternative"))
        assertEquals(Decision("shelve", "not now"), DecisionVerb.parse("- shelve: not now"))
        assertEquals(Decision("reject", "too big"), DecisionVerb.parse("REJECT: too big"))
    }

    @Test
    fun `anything else, or a verb that needs a why without one, is unclear`() {
        listOf("yes", "sounds good, accept", "revise", "shelve:", "reject:   ", "accepted", "accept and build later", "").forEach {
            assertEquals(Decision("unclear", null), DecisionVerb.parse(it), it)
        }
    }

    @Test
    fun `the why is the answer minus leading punctuation and a leading verb`() {
        assertEquals("the cost is fine", DecisionVerb.why("Accept: the cost is fine"))
        assertEquals("go", DecisionVerb.why("> Accept  and\nbuild: go"))
        assertEquals("but not this quarter", DecisionVerb.why("shelve, but not this quarter"))
        assertEquals("Park this for now", DecisionVerb.why("Park this for now"))
        assertEquals("accepted", DecisionVerb.why("accepted"), "a verb is a whole word")
        assertNull(DecisionVerb.why("- reject:  "))
        assertNull(DecisionVerb.why("accept and build auto"))
    }

    @Test
    fun `jev's pick is taken at its floor, the build verbs at the higher one, else the rule reads`() {
        fun read(answer: String, pick: String?, confidence: Double?) = DecisionVerb.read(answer, pick, confidence, 0.8, 0.95)
        assertEquals(Decision("revise", "Please redo section 2"), read("Please redo section 2", "revise", 0.8).decision)
        assertEquals("took revise", read("Please redo section 2", "revise", 0.8).action)
        assertEquals(Decision("unclear", null), read("Please redo section 2", "revise", 0.79).decision)
        assertEquals(Decision("unclear", null), read("go ahead and build it", "build", 0.9).decision, "below the build floor")
        assertEquals(Decision("build-auto", "go ahead"), read("go ahead", "build-auto", 0.95).decision)
        assertEquals(Decision("accept", "fine"), read("accept: fine", null, null).decision, "no answer: the rule")
        assertEquals("no answer, rule: accept", read("accept: fine", null, null).action)
    }

    @Test
    fun `a pick that needs a why without one is unclear, and a verb as written is never overridden`() {
        val bare = DecisionVerb.read("reject", "reject", 0.99, 0.8, 0.95)
        assertEquals(Decision("unclear", null), bare.decision)
        assertEquals("re-asked: reject with no text", bare.action)
        val written = DecisionVerb.read("accept", "build", 0.99, 0.8, 0.95)
        assertEquals(Decision("accept", null), written.decision)
        assertEquals("kept the verb as written: accept", written.action)
        assertEquals("verb as written: accept (jev picked build)", written.outcome)
        assertEquals("verb as written: accept", DecisionVerb.read("accept", "accept", 0.99, 0.8, 0.95).outcome)
        assertNull(DecisionVerb.read("sounds good", "accept", 0.99, 0.8, 0.95).outcome)
    }

    @Test
    fun `a final decision's status, and none for revise or unclear`() {
        assertEquals(listOf("accepted", "accepted", "accepted", "shelved", "rejected"),
            listOf("accept", "build", "build-auto", "shelve", "reject").map(DecisionVerb::status))
        assertFailsWith<IllegalStateException> { DecisionVerb.status("revise") }
        assertFailsWith<IllegalStateException> { DecisionVerb.status("unclear") }
    }

    @Test
    fun `the log line has the date, status, ticket, a one-line why and the build`() {
        val date = LocalDate.of(2026, 9, 26)
        assertEquals("- 2026-09-26 accepted (#12)", LogDecisionDelegate.logLine(date, "accepted", 12, null, null))
        assertEquals("- 2026-09-26 shelved (#12) — not this quarter", LogDecisionDelegate.logLine(date, "shelved", 12, "not\n  this quarter", null))
        assertEquals("- 2026-09-26 accepted (#12) — go; build filed as #100", LogDecisionDelegate.logLine(date, "accepted", 12, "go", 100))
    }

    @Test
    fun `the edit sets the status and appends to the log, once`() {
        val doc = "# Widgets\n\nStatus: draft\n\nDecision log:\n- 2026-09-01 shelved (#3) — later\n\n## Why\n\nBecause.\n"
        val line = "- 2026-09-26 accepted (#12)"
        val edited = LogDecisionDelegate.edit(doc, "accepted", line)
        assertEquals("# Widgets\n\nStatus: accepted\n\nDecision log:\n- 2026-09-01 shelved (#3) — later\n$line\n\n## Why\n\nBecause.\n", edited)
        assertEquals(edited, LogDecisionDelegate.edit(edited, "accepted", line), "a retried step changes nothing")
    }

    @Test
    fun `a doc with no status or log gets both under its title`() {
        val line = "- 2026-09-26 rejected (#12) — too big"
        assertEquals("# Widgets\nStatus: rejected\nDecision log:\n$line\n\nBody.\n",
            LogDecisionDelegate.edit("# Widgets\n\nBody.\n", "rejected", line))
    }
}

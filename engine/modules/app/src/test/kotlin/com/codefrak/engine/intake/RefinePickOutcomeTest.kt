package com.codefrak.engine.intake

import com.codefrak.engine.jev.JevAnswer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a flow's start records back against [RefinePicker]'s pick; no Jev call is made. */
class RefinePickOutcomeTest {
    private fun choice(c: String, p: Double) = JevAnswer("choice", choice = c, probabilities = mapOf(c to p), confidence = p)
    private fun score(level: Int, p: Double) = JevAnswer("score", score = level.toDouble(), probabilities = mapOf("$level" to p, "0" to 1 - p), confidence = p)

    @Test
    fun `the trailer's jev id is the call a flow's start records against`() {
        val body = testFiler().body("## Goal", "intake 1", 5, "p3", jev = "0f8fad5b-d9cb-469f-a165-70867728950e")
        assertEquals("0f8fad5b-d9cb-469f-a165-70867728950e", RefinePickOutcome.callOf(body))
        assertEquals(5, com.codefrak.engine.chore.ProcessFlowStarter.parseTierFromBody(body), "the tier still resolves from the trailer")
        assertNull(RefinePickOutcome.callOf(testFiler().body("## Goal", "intake 1", 5, "p3")))
        val byAlice = testFiler().body("## Goal", "intake 1", 5, "p3", by = "alice", jev = "0f8fad5b-d9cb-469f-a165-70867728950e")
        assertEquals("0f8fad5b-d9cb-469f-a165-70867728950e", RefinePickOutcome.callOf(byAlice), "`by` before `jev` does not hide the call")
        assertEquals("alice", TicketFiler.askedByOf(byAlice))
    }

    @Test
    fun `a start at jev's pick records that it agreed, and the prefix stays as it was`() {
        val answers = mapOf("kind" to choice("feature", 0.9), "featureMode" to choice("confirm", 0.9), "tier" to score(5, 0.9))
        val action = "filed: kind feature (jev), mode confirm (jev), tier 6 (jev)"
        assertEquals("started as feature confirm tier 6; jev: agreed", RefinePickOutcome.outcome(Placement("feature", "confirm", 6), answers, action))
    }

    @Test
    fun `a start that differs from jev names the fields, and one that differs from what was filed is a relabel`() {
        val answers = mapOf("kind" to choice("feature", 0.9), "featureMode" to choice("confirm", 0.9), "tier" to score(5, 0.6))
        val filed = "filed: kind feature (jev), mode confirm (jev), tier 2 (jev 6<floor)"
        assertEquals("started as feature confirm tier 2; jev: differs (picked tier 6)",
            RefinePickOutcome.outcome(Placement("feature", "confirm", 2), answers, filed), "a tier below the floor that started otherwise differs, with no relabel")
        assertEquals("started as chore tier 4; jev: differs (picked kind feature, tier 6), relabelled from feature confirm tier 2",
            RefinePickOutcome.outcome(Placement("chore", null, 4), answers, filed))
        assertEquals("started as feature auto tier 6; jev: differs (picked mode confirm), relabelled from feature confirm tier 2",
            RefinePickOutcome.outcome(Placement("feature", "auto", 6), answers, filed))
    }

    @Test
    fun `with no answer or no readable action only what can be told is recorded`() {
        assertEquals("started as chore tier 7", RefinePickOutcome.outcome(Placement("chore", null, 7), null, null))
        assertEquals("started as feature confirm tier 3; relabelled from chore tier 2",
            RefinePickOutcome.outcome(Placement("feature", "confirm", 3), null, "no answer: filed at the refiner's chore tier 2"))
        assertEquals("started as chore tier 2", RefinePickOutcome.outcome(Placement("chore", null, 2), null, "no answer: filed at the refiner's chore tier 2"))
        assertEquals("started as chore tier 2", RefinePickOutcome.outcome(Placement("chore", null, 2), null, "something else"))
    }

    @Test
    fun `the filed action and the started prefix read back into kind, mode and tier`() {
        assertEquals(Placement("feature", "confirm", 2), RefinePickOutcome.filedAs("filed: kind feature (jev), mode confirm (jev), tier 2 (jev 5<floor)"))
        assertEquals(Placement("chore", null, 2), RefinePickOutcome.filedAs("filed: kind chore (jev feature<floor), mode -, tier 2 (jev 9<floor)"))
        assertEquals(Placement("feature", "auto", 5), RefinePickOutcome.filedAs("no answer: filed at the refiner's feature auto tier 5"))
        assertNull(RefinePickOutcome.filedAs(null))
        assertEquals(Placement("feature", "auto", 3), RefinePickOutcome.startedAs("started as feature auto tier 3; jev: agreed"))
        assertEquals(Placement("chore", null, null), RefinePickOutcome.startedAs("started as chore tier -"))
        assertNull(RefinePickOutcome.startedAs("not a start"))
    }
}

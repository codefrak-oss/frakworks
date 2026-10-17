package com.codefrak.engine.intake

import com.codefrak.engine.jev.JevAnswer
import com.codefrak.engine.tier.SeededTiers
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rule on Jev's pick of a refined ticket's kind, feature mode and tier (RefinePicker); no Jev call is made. */
class RefinePickTest {
    private val refiner = RefinePick("chore", null, 2)
    private fun choice(c: String, p: Double) = JevAnswer("choice", choice = c, probabilities = mapOf(c to p), confidence = p)
    private fun score(level: Int, p: Double) = JevAnswer("score", score = level.toDouble(), probabilities = mapOf("$level" to p, "0" to 1 - p), confidence = p)

    @Test
    fun `with no answer the refiner's choice stands, with no note`() {
        assertEquals(refiner, RefinePicker.read(refiner, null, 0.8, "id"))
        assertEquals(refiner, RefinePicker.read(refiner, emptyMap(), 0.8, "id"))
    }

    @Test
    fun `each pick at the floor is filed, and the note says what jev picked and what the refiner chose`() {
        val picked = RefinePicker.read(refiner, mapOf("kind" to choice("feature", 0.9), "featureMode" to choice("auto", 0.8), "tier" to score(6, 0.85)), 0.8, "id")
        assertEquals(Triple("feature", "auto", 7), Triple(picked.kind, picked.featureMode, picked.tier))
        assertEquals("id", picked.call)
        val note = assertNotNull(picked.note)
        assertTrue("kind `feature` (0.90)" in note && "tier 7 (0.85)" in note && "the refiner chose `chore`, tier 2" in note, note)
        assertTrue(note.lines().none { it.trimStart().startsWith("Tier:") }, "the note must not read as a Tier: line")
    }

    @Test
    fun `below the floor each field keeps the refiner's choice, and a feature jev made with no sure mode is confirm`() {
        val low = RefinePicker.read(refiner, mapOf("kind" to choice("bug", 0.79), "tier" to score(8, 0.6)), 0.8, "id")
        assertEquals(Triple("chore", null, 2), Triple(low.kind, low.featureMode, low.tier))
        assertNotNull(low.note, "a pick below the floor is still shown")
        val feature = RefinePicker.read(refiner, mapOf("kind" to choice("feature", 0.95), "featureMode" to choice("auto", 0.5)), 0.8, "id")
        assertEquals("feature" to "confirm", feature.kind to feature.featureMode)
        val kept = RefinePicker.read(RefinePick("feature", "auto", 5), mapOf("kind" to choice("chore", 0.99)), 0.8, "id")
        assertEquals("chore" to null, kept.kind to kept.featureMode, "a chore carries no mode")
    }

    @Test
    fun `a score's tier is its most probable level, else its score rounded, one-based`() {
        assertEquals(5, RefinePicker.tierOf(JevAnswer("score", score = 4.4, probabilities = mapOf("4" to 0.6, "5" to 0.4), confidence = 0.6)))
        assertEquals(3, RefinePicker.tierOf(JevAnswer("score", score = 1.6)))
        assertEquals(10, RefinePicker.tierOf(JevAnswer("score", score = 12.0)))
        assertNull(RefinePicker.tierOf(JevAnswer("score")))
    }

    @Test
    fun `a field the user named is not asked, and the tier levels are the ten tiers of the table`() {
        val picker = RefinePicker(FakeDecider, unusedCalls, SeededTiers.table())
        assertEquals(setOf("kind", "featureMode", "tier"), picker.questions(refiner, emptySet()).keys)
        assertEquals(setOf("featureMode", "tier"), picker.questions(RefinePick("feature", "auto", 5), setOf("kind")).keys)
        assertEquals(setOf("tier"), picker.questions(refiner, setOf("kind")).keys, "a chore the user named has no mode to ask")
        assertEquals(emptySet(), picker.questions(refiner, setOf("kind", "tier")).keys)
        assertEquals(setOf("tier"), picker.questions(RefinePick("research", null, 3), emptySet()).keys, "research is not jev's to change")
        @Suppress("UNCHECKED_CAST")
        val levels = picker.tierQuestion().criteria as List<String>
        assertEquals(10, levels.size)
        assertEquals("Tier 5: claude-opus-5-5 at medium effort", levels[4])
    }

    @Test
    fun `the ask's Tier line and the refiner's userNamed bind`() {
        val result = RefineResult("file", DraftTicket("t", "b", 2, "p3", 2), kind = "chore", userNamed = listOf("kind"))
        assertEquals(setOf("kind", "tier"), RefinePicker.bound(result, "Tier: 9 (fable medium) — prescribed by the user; do not re-judge\nfix it"))
        assertEquals(setOf("kind"), RefinePicker.bound(result, "fix the tier table"))
        assertEquals(emptyList(), result.copy(userNamed = listOf("points")).problems(), "a Points: line the user wrote binds")
        assertEquals(listOf("userNamed 'model' is not one of [kind, featureMode, tier, points]"),
            RefineResult("file", DraftTicket("t", "b", 2, "p3", 2), kind = "chore", userNamed = listOf("model")).problems())
    }

    @Test
    fun `the call's action says per field what was filed and from whose choice`() {
        val answers = mapOf("kind" to choice("feature", 0.9), "featureMode" to choice("confirm", 0.9), "tier" to score(4, 0.6))
        val action = RefinePicker.action(refiner, RefinePicker.read(refiner, answers, 0.8, "id"), answers)
        assertEquals("filed: kind feature (jev), mode confirm (jev), tier 2 (jev 5<floor)", action)
        val longest = mapOf("kind" to choice("feature", 0.1), "featureMode" to choice("confirm", 0.1), "tier" to score(9, 0.1))
        val proposal = RefinePick("proposal", null, 10)
        assertTrue(RefinePicker.action(proposal, RefinePicker.read(proposal, longest, 0.8, "id"), longest).length <= 100,
            "engine.jev_call.action is VARCHAR(100)")
        assertEquals("no answer: filed at the refiner's chore tier 2", RefinePicker.action(refiner, refiner, null))
    }

    private object FakeDecider : com.codefrak.engine.jev.JevDecider {
        override fun decide(useCase: String, context: com.codefrak.engine.jev.JevContext, state: Any,
                            questions: Map<String, com.codefrak.engine.jev.JevQuestion>, threshold: Double?) = error("not called")
    }
    private val unusedCalls = com.codefrak.engine.jev.JevCalls(org.mockito.Mockito.mock(org.springframework.jdbc.core.simple.JdbcClient::class.java))
}

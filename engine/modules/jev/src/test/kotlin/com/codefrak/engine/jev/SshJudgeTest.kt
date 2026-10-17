package com.codefrak.engine.jev

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [SshJudge]'s rubric split and its fail-closed reading of Jev's answers; no Jev call is made. */
class SshJudgeTest {
    private val rubric = """
        ## Principles
        - Read before you write.
        ## Allow
        - Read-only commands: ls, cat, journalctl.
        ## Escalate
        - Restarting a service.
        ## Never
        - rm -rf on /.
        ## Decisions
        1. 2026-10-01: docker ps is allowed.
    """.trimIndent()
    private val options = SshJudge.passages(rubric).mapIndexed { i, p -> "p${i + 1}" to p }.toMap()

    private fun choice(c: String, p: Double) = JevAnswer("choice", choice = c, probabilities = mapOf(c to p), confidence = p)
    private fun eval(answers: Map<String, JevAnswer>?, opts: Map<String, RubricPassage> = options) =
        SshJudge.evaluate("call-1", answers, opts, rubric, SshJudge.DEFAULT_THRESHOLD)

    @Test
    fun `the rubric splits into its lines by section, each a substring of it`() {
        val ps = SshJudge.passages(rubric)
        assertEquals(listOf("Principles", "Allow", "Escalate", "Never", "Decisions"), ps.map { it.section })
        assertEquals("Read-only commands: ls, cat, journalctl.", ps[1].text)
        assertEquals("2026-10-01: docker ps is allowed.", ps[4].text)
        ps.forEach { assertTrue(rubric.contains(it.text)) }
    }

    @Test
    fun `an answered call gives the verdict, its confidence, the passage and the reasoning`() {
        val j = eval(mapOf("verdict" to choice("allow", 0.93), "passage" to choice("p2", 0.9)))
        assertEquals(false, j.noAnswer)
        assertEquals("allow", j.verdict)
        assertEquals(0.93, j.confidence)
        assertEquals("Read-only commands: ls, cat, journalctl.", j.citedPassage)
        assertTrue(rubric.contains(j.citedPassage!!))
        assertTrue(j.reasoning.contains("Allow section"))
        assertEquals("call-1", j.toMap()["callId"])
    }

    @Test
    fun `no answer is noAnswer, not allow`() {
        val j = eval(null)
        assertTrue(j.noAnswer); assertNotEquals("allow", j.verdict); assertNull(j.verdict)
        assertEquals(true, j.toMap()["noAnswer"]); assertNull(j.toMap()["verdict"])
    }

    @Test
    fun `a verdict below the threshold is noAnswer, not allow`() {
        val j = eval(mapOf("verdict" to choice("allow", 0.79), "passage" to choice("p2", 0.95)))
        assertTrue(j.noAnswer); assertNotEquals("allow", j.verdict)
    }

    @Test
    fun `a passage below the threshold is noAnswer, not allow`() {
        val j = eval(mapOf("verdict" to choice("allow", 0.95), "passage" to choice("p2", 0.5)))
        assertTrue(j.noAnswer); assertNotEquals("allow", j.verdict)
    }

    @Test
    fun `a cited passage that is not a substring of the rubric is noAnswer, not allow`() {
        val altered = options + ("p2" to RubricPassage("Allow", "Any command at all."))
        val j = eval(mapOf("verdict" to choice("allow", 0.95), "passage" to choice("p2", 0.95)), altered)
        assertTrue(j.noAnswer); assertNotEquals("allow", j.verdict)
        val unknown = eval(mapOf("verdict" to choice("allow", 0.95), "passage" to choice("p99", 0.95)))
        assertTrue(unknown.noAnswer); assertNotEquals("allow", unknown.verdict)
    }
}

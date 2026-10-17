package com.codefrak.engine.friction

import com.codefrak.engine.intake.DuplicateCheck
import com.codefrak.engine.jev.JevAnswer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The `friction-group` question (FrictionGroupCheck): its options, its state, its stages, its actions and the truth its outcomes show. No Jev call is made. */
class FrictionGroupCheckTest {
    private val t0 = OffsetDateTime.parse("2026-10-04T18:49:35Z")
    private fun groups(n: Int) = (1..n).map { GroupOption(it.toLong(), "group $it", t0.plusMinutes(it.toLong())) }
    private fun choice(c: String, p: Double) = JevAnswer("choice", choice = c, probabilities = mapOf(c to p) + (if (c == "new") emptyMap() else mapOf("new" to 1.0 - p)), confidence = p)
    private fun note(evidence: String? = "e".repeat(3000)) =
        FrictionNote("n", "acme/a", 7, "run", "root", t0, FrictionNote.AS_YOU_GO, "tooling", "s", evidence, "high")

    @Test
    fun `the options are every group as g-id with its title cut to 240, plus new, and the state is the note cut`() {
        val candidates = FrictionGroupCheck.candidates(listOf(GroupOption(12, "t".repeat(300), t0), GroupOption(3, "short", t0)))
        assertEquals(listOf("g12", "g3"), candidates.map { it.key })
        assertEquals(240, candidates[0].title.length)
        val question = FrictionGroupCheck.question(candidates)
        assertEquals("choice", question.type)
        @Suppress("UNCHECKED_CAST")
        val criteria = question.criteria as Map<String, String?>
        assertEquals(listOf("g12", "g3", "new"), criteria.keys.toList())
        assertEquals("short", criteria["g3"])
        val state = FrictionGroupCheck.state(note())
        assertEquals(listOf("kind", "source", "repo", "summary", "evidence"), state.keys.toList())
        assertEquals(listOf("tooling", "as-you-go", "acme/a", "s"), listOf(state["kind"], state["source"], state["repo"], state["summary"]))
        assertEquals(FrictionGroupCheck.EVIDENCE_CHARS, (state["evidence"] as String).length)
        assertNull(FrictionGroupCheck.state(note(null))["evidence"])
        assertThrows<IllegalArgumentException> { FrictionGroupCheck.run(emptyList(), { _, _ -> error("asked") }, { _, _ -> }) }
    }

    @Test
    fun `up to 254 groups are one call, and the verdict reads a join that clearly beats new, new, a pick that does not and no answer`() {
        val candidates = FrictionGroupCheck.candidates(groups(254))
        val asked = mutableListOf<Pair<Int, Boolean>>()
        fun run(answer: JevAnswer?) = FrictionGroupCheck.run(candidates, { batch, final -> asked += batch.size to final; DuplicateCheck.Asked("c1", answer) }, { _, _ -> error("no stage-1 action") })

        val joined = run(choice("g12", 0.91))
        assertEquals(listOf(254 to true), asked)
        assertEquals(12L, joined.joins(2.0)!!.id)
        assertEquals("g12 (0.91 vs new 0.09): joined", joined.action(2.0, null))
        assertEquals("g12 (0.67 vs new 0.33): joined", run(choice("g12", 0.67)).action(2.0, null), "at twice new")

        val below = run(choice("g12", 0.64))
        assertNull(below.joins(2.0))
        assertEquals("g12 (0.64 vs new 0.36): new group g31", below.action(2.0, 31))

        val new = run(choice("new", 0.88))
        assertNull(new.joins(2.0)); assertNull(new.pick)
        assertEquals("new (0.88): new group g31", new.action(2.0, 31))
        assertEquals("g999 (0.99): new group g31", run(choice("g999", 0.99)).action(2.0, 31), "an option that is no group founds")

        val none = run(null)
        assertEquals(true, none.noAnswer)
        assertEquals("no answer: left ungrouped", none.action(2.0, null))
        assertEquals("no answer: new group g31", none.action(2.0, 31), "fallback or no-jev founded a group")
        assertEquals("c1", none.call)
    }

    @Test
    fun `more than 254 groups go in two stages, newest member first, and only the second stage's pick counts`() {
        val candidates = FrictionGroupCheck.candidates(groups(600))
        val batches = mutableListOf<List<String>>()
        val actions = mutableListOf<String>()
        val verdict = FrictionGroupCheck.run(candidates, { batch, final ->
            batches += batch.map { it.key }
            val n = batches.size
            when {
                final -> DuplicateCheck.Asked("c$n", choice("g300", 0.92))
                n == 1 -> DuplicateCheck.Asked("c$n", choice("g5", 0.6))
                n == 2 -> DuplicateCheck.Asked("c$n", choice("g300", 0.7))
                else -> DuplicateCheck.Asked("c$n", choice("new", 0.9))
            }
        }, { call, action -> actions += "$call $action" })
        assertEquals(listOf(254, 254, 92, 2), batches.map { it.size })
        assertEquals(listOf("g5", "g300"), batches[3])
        assertEquals(300L, verdict.joins(2.0)!!.id)
        assertEquals("c4", verdict.call)
        assertEquals(listOf("c1 stage 1 (1/3) g5 (0.60): to stage 2", "c2 stage 1 (2/3) g300 (0.70): to stage 2", "c3 stage 1 (3/3) new (0.90): out"), actions)
        assertEquals("stage 2 g300 (0.92 vs new 0.08): joined", verdict.action(2.0, null))

        actions.clear()
        var calls = 0
        val noAnswer = FrictionGroupCheck.run(candidates, { _, _ -> calls++; DuplicateCheck.Asked("c$calls", null) }, { call, action -> actions += "$call $action" })
        assertEquals(1, calls, "a stage-1 no answer ends the check")
        assertEquals(true, noAnswer.noAnswer)
        assertEquals(listOf("c1 stage 1 (1/3) no answer: left ungrouped"), actions)
        assertNull(noAnswer.action(2.0, null), "recorded by the stage")

        calls = 0
        val allOut = FrictionGroupCheck.run(candidates, { _, _ -> calls++; DuplicateCheck.Asked("c$calls", choice("new", 0.99)) }, { _, _ -> })
        assertEquals(3, calls, "no stage 2 without a pick")
        assertEquals(false, allOut.noAnswer)
        assertNull(allOut.pick); assertNull(allOut.action(2.0, 601))
        assertEquals("c3", allOut.call)
    }

    @Test
    fun `the outcomes name the group, and the truth they show is the group kept or merged into, or new`() {
        assertEquals("g12", FrictionGroupCheck.truth(FrictionGroupCheck.kept("g12")))
        assertEquals("new", FrictionGroupCheck.truth(FrictionGroupCheck.pulledOut("g12", "g40", "u")))
        assertEquals("new", FrictionGroupCheck.truth(FrictionGroupCheck.newKept("g31")))
        assertEquals("g12", FrictionGroupCheck.truth(FrictionGroupCheck.merged("g31", "g12", "u")))
        assertEquals("joined g12: pulled out to g40 by @u", FrictionGroupCheck.pulledOut("g12", "g40", "u"))
        assertEquals("new g31: merged into g12 by @u", FrictionGroupCheck.merged("g31", "g12", "u"))
        assertNull(FrictionGroupCheck.truth("no answer: left ungrouped"))
    }

    @Test
    fun `placed reads the group a call's action put the note in, for the sweep`() {
        assertEquals("g12", FrictionGroupCheck.placed("g12 (0.91): joined"))
        assertEquals("g12", FrictionGroupCheck.placed("stage 2 g12 (0.91): joined"))
        assertEquals("g31", FrictionGroupCheck.placed("new (0.88): new group g31"))
        assertEquals("g31", FrictionGroupCheck.placed("g12 below floor 0.8 (0.64): new group g31"), "a call from before the cluster rule")
        assertEquals("g31", FrictionGroupCheck.placed("g12 (0.64 vs new 0.36): new group g31"))
        assertEquals("g12", FrictionGroupCheck.placed("g12 (0.91 vs new 0.09): joined"))
        assertEquals("g9", FrictionGroupCheck.placed("g9 cluster{g9,g27,g2,g32} (0.73 vs new 0.11): joined"))
        assertEquals("g9", FrictionGroupCheck.placed("stage 2 g9 cluster{g2,g9,g27,g32,+3} (0.73 vs new 0.11): joined; two things (0.90)"))
        assertEquals("g31", FrictionGroupCheck.placed("no answer: new group g31"))
        assertNull(FrictionGroupCheck.placed("no answer: left ungrouped"))
        assertNull(FrictionGroupCheck.placed("stage 1 (1/2) g12 (0.60): to stage 2"))
        assertNull(FrictionGroupCheck.placed(null))
    }

    @Test
    fun `clusters link near-duplicate titles, sum their probabilities, and the oldest group is the cluster's best`() {
        val c = listOf(FrictionGroupCheck.Candidate(9, "bots/check-acceptance-criteria is not executable in the sandbox"),
            FrictionGroupCheck.Candidate(27, "bots/check-acceptance-criteria is not executable in the clone"),
            FrictionGroupCheck.Candidate(3, "no Gradle in the sandbox"))
        val answer = JevAnswer("choice", choice = "g27", probabilities = mapOf("g9" to 0.2, "g27" to 0.3, "g3" to 0.1, "new" to 0.2), confidence = 0.3)
        val clusters = FrictionGroupCheck.clusters(c, answer)
        assertEquals(setOf(listOf(9L, 27L), listOf(3L)), clusters.map { cl -> cl.members.map { it.id } }.toSet())
        assertEquals(0.5, clusters.single { it.members.size == 2 }.probability, 1e-9)
        val verdict = FrictionGroupCheck.Verdict("c1", answer, c[1], stage2 = false, recorded = false, finalists = c)
        assertEquals(9L, verdict.joins(2.0)!!.id, "the oldest, not the pick")
        assertEquals("g9 cluster{g9,g27} (0.50 vs new 0.20): joined", verdict.action(2.0, null))
        assertNull(verdict.joins(3.0))
        val many = (1L..9L).map { FrictionGroupCheck.Candidate(1000 + it, "bots/check-acceptance-criteria is not executable") }
        val wide = FrictionGroupCheck.Verdict("c2", JevAnswer("choice", choice = "g1009", probabilities = many.associate { it.key to 0.1 }, confidence = 0.1),
            many.last(), stage2 = true, recorded = false, finalists = many).action(2.0, null)!! + FrictionGroupCheck.twoThingsAction(0.9)
        assertTrue(wide.length <= 100, wide)
        assertEquals("g1001", FrictionGroupCheck.placed(wide))
    }
}

package com.codefrak.engine.smith

import com.codefrak.engine.feature.ChildSplitRules
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkResultTest {
    private val rules = ChildSplitRules()
    private fun parse(text: String) = WorkResult.parse(text.toByteArray())

    @Test
    fun `nothing-to-do with a reason and no bundle can be acted on`() {
        assertEquals(emptyList(), parse("""{"outcome":"nothing-to-do","reason":"Already done in a1b2c3."}""").problems(emptyList(), hasBundle = false))
    }

    @Test
    fun `nothing-to-do needs a reason, and may not leave commits or open findings`() {
        assertTrue(parse("""{"outcome":"nothing-to-do","reason":" "}""").problems(emptyList(), false).any { "needs a reason" in it })
        assertTrue(parse("""{"outcome":"nothing-to-do","reason":"x"}""").problems(emptyList(), true).any { "left commits" in it })
        assertTrue(parse("""{"outcome":"nothing-to-do","reason":"x"}""").problems(listOf(Finding(id = "review-1-1", ask = "fix")), false)
            .any { "findings open" in it })
    }

    private val ci = Finding(id = "ci-2-1", ask = "the check failed", review = "ci")
    private val review = Finding(id = "review-2-1", ask = "rename it", review = "correctness")
    private fun done(addressed: String = "", declined: String = "") = parse(
        """{"outcome":"done","title":"t","summary":"s","addressed":[$addressed],"declined":[$declined]}""")

    @Test
    fun `done without commits is taken only when every open finding is a ci one, declined with a why`() {
        val notMine = done(declined = """{"finding":"ci-2-1","why":"a flaky desktop test this branch does not touch"}""")
        assertTrue(notMine.ciNotMine(listOf(ci)))
        assertEquals(emptyList(), notMine.problems(listOf(ci), hasBundle = false))

        val noWhy = done(declined = """{"finding":"ci-2-1","why":" "}""")
        assertTrue(noWhy.problems(listOf(ci), false).any { "left no commits" in it }, "a decline needs its why")
        val fixed = done(addressed = """{"finding":"ci-2-1","how":"fixed"}""")
        assertTrue(fixed.problems(listOf(ci), false).any { "left no commits" in it }, "addressing it needs commits")
        val mixed = done(declined = """{"finding":"ci-2-1","why":"flaky"},{"finding":"review-2-1","why":"disagree"}""")
        assertTrue(mixed.problems(listOf(ci, review), false).any { "left no commits" in it }, "a review finding open: commits as usual")
        assertTrue(done().problems(emptyList(), false).any { "left no commits" in it }, "no findings: a first round always commits")
    }

    private fun draft(title: String, deps: String = "") =
        """{"title":"$title","body":"## Goal\n\nx","kind":"chore","dependsOn":[$deps],"points":3}"""
    private val split = parse("""{"outcome":"split","summary":"too big","children":[${draft("a")},${draft("b", "0")}]}""")

    @Test
    fun `split with drafts on a child of a split feature can be acted on, and passes the schema`() {
        assertEquals(emptyList(), split.problems(emptyList(), hasBundle = false, parent = 12, rules = rules))
        assertEquals(emptyList(), ResultSchemas.problems("work",
            """{"outcome":"split","summary":"too big","children":[${draft("a")},${draft("b", "0")}]}""".toByteArray()))
    }

    @Test
    fun `split on a ticket with no parent is refused, so the Smith asks instead`() {
        assertTrue(split.problems(emptyList(), hasBundle = false, parent = null, rules = rules).any { "no parent" in it })
    }

    @Test
    fun `split needs good drafts, no commits and no open findings`() {
        assertTrue(parse("""{"outcome":"split","summary":"s","children":[${draft("a")}]}""").problems(emptyList(), false, 12, rules).any { "2-16 children" in it })
        assertTrue(parse("""{"outcome":"split","summary":"s","children":[${draft("a", "1")},${draft("b")}]}""").problems(emptyList(), false, 12, rules)
            .any { "earlier children only" in it })
        assertTrue(split.problems(emptyList(), true, 12, rules).any { "left commits" in it })
        assertTrue(split.problems(listOf(review), false, 12, rules).any { "findings open" in it })
        assertTrue(parse("""{"outcome":"nothing-to-do","reason":"x","children":[${draft("a")}]}""").problems(emptyList(), false, 12, rules)
            .any { "only for outcome 'split'" in it })
    }

    @Test
    fun `a brief's parent is the split feature its part line names`() {
        assertEquals(1557L, Brief.parent("## Goal\n\nx\n\nPart 2 of 3 of #1557.\n\n<!-- engine: split of #1557 -->"))
        assertEquals(null, Brief.parent("## Goal\n\nNo parent here."))
    }
}

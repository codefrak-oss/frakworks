package com.codefrak.engine.feature

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A plan question's options (engine/README.md, "Feature"): checked as a choice block's are, and rendered as one. */
class PlanQuestionTest {
    private val plan = """"plan":{"summary":"x","acceptance":["a"]}"""

    private fun design(questions: String) = DesignResult.parse("""{"outcome":"planned",$plan,"questions":$questions}""".toByteArray())

    private fun question(text: String, options: String? = null) =
        """{"question":"$text","assumption":"b","confidence":0.4,"impact":"high"${options?.let { ""","options":$it""" } ?: ""}}"""

    private val abc = """[{"id":"a","label":"Keep the pane"},{"id":"b","label":"Drop the pane"},{"id":"c","label":"Move it to the sidebar"}]"""

    @Test
    fun `a question with 2 to 5 well-formed options, or none, is accepted`() {
        assertEquals(emptyList(), design("[${question("Which?", abc)}]").problems())
        assertEquals(emptyList(), design("[${question("Which?")}]").problems())
        assertEquals(emptyList(), design("""[${question("Which?", """[{"id":"a","label":"A"},{"id":"b","label":"${"x".repeat(60)}"}]""")}]""").problems(),
            "a long label is shortened on the button, not refused")
    }

    @Test
    fun `options break the choice block's rules - each refused naming its question`() {
        fun problems(options: String) = design("[${question("Fine?")},${question("Which?", options)}]").problems()
        listOf(
            """[{"id":"a","label":"A"}]""" to "questions[1].options has 1 entries",
            "[]" to "questions[1].options has 0 entries",
            (1..6).joinToString(",", "[", "]") { """{"id":"o$it","label":"O$it"}""" } to "questions[1].options has 6 entries",
            """[{"id":"","label":"A"},{"id":"b","label":"B"}]""" to "questions[1].options[0] has no id",
            """[{"id":"a","label":" "},{"id":"b","label":"B"}]""" to "questions[1].options[0] has no label",
            """[{"id":"a","label":"A"},{"id":"a","label":"B"}]""" to "questions[1].options has the id 'a' more than once",
        ).forEach { (options, message) -> assertTrue(problems(options).single().startsWith(message), "$options: ${problems(options)}") }
    }

    @Test
    fun `one important question with options is a choice block, its options the buttons`() {
        val asked = design("[${question("Which pane layout?", abc)}]").userQuestion()!!
        assertTrue("**Which pane layout?** (confidence 0.4, impact high). Without an answer it would assume: b" in asked, asked)
        assertTrue(asked.endsWith(
            "\n\n```ask\nkind: choice\nquestion: Which pane layout?\noptions:\n" +
                "  - id: a\n    label: Keep the pane\n" +
                "  - id: b\n    label: Drop the pane\n" +
                "  - id: c\n    label: Move it to the sidebar\n```",
        ), asked)
        assertEquals(1, Regex("(?m)^```ask$").findAll(asked).count(), "one ask block per comment")
    }

    @Test
    fun `several important questions, or one without options, stay a text block`() {
        val two = design("[${question("Which?", abc)},${question("Why?")}]").userQuestion()!!
        assertTrue(two.endsWith("```ask\nkind: text\nquestion: Answer the 2 plan questions above\n```"), two)
        assertTrue("options:" !in two, two)
        val one = design("[${question("Which?")}]").userQuestion()!!
        assertTrue(one.endsWith("```ask\nkind: text\nquestion: Which?\n```"), one)
    }

    @Test
    fun `the plan's summary, its inline svg included, comes after the intro and before the first question`() {
        val svg = "<svg viewBox=\\\"0 0 10 10\\\"><rect width=\\\"10\\\" height=\\\"10\\\"/></svg>"
        val asked = DesignResult.parse("""{"outcome":"planned","plan":{"summary":"  The pane.\n\n$svg  ","acceptance":["a"]},"questions":[${question("Which?")}]}""".toByteArray()).userQuestion()!!
        val drawn = "The plan:\n\nThe pane.\n\n<svg viewBox=\"0 0 10 10\"><rect width=\"10\" height=\"10\"/></svg>"
        assertTrue(drawn in asked, asked)
        assertTrue(asked.indexOf("confirm mode") < asked.indexOf(drawn) && asked.indexOf(drawn) < asked.indexOf("1. **Which?**"), asked)
        assertEquals(1, Regex("```ask").findAll(asked).count(), asked)
    }

    @Test
    fun `a propose run's single question with options gets buttons too`() {
        val asked = PlanQuestion.userQuestion(listOf(PlanQuestion("Which?", "a", 0.4, "low", listOf(PlanOption("a", "A"), PlanOption("b", "B")))), "proposal") { "Intro $it." }!!
        assertTrue(asked.endsWith("```ask\nkind: choice\nquestion: Which?\noptions:\n  - id: a\n    label: A\n  - id: b\n    label: B\n```"), asked)
    }
}

package com.codefrak.engine.feature

import com.codefrak.engine.proposal.PitchRules
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The confirm-mode design deck: slides from the plan, everything escaped but the mockups, a page the pitch frame takes. */
class DesignDeckTest {
    private val mock = "<svg xmlns='http://www.w3.org/2000/svg' width='40' height='20' onload='x()'><rect width='40' height='20'/>" +
        "<script>alert(1)</script><a href='https://evil.example'><text>t</text></a></svg>"
    private val result = DesignResult(
        outcome = "planned",
        plan = Plan(
            summary = "Render the `deck`.\n\n$mock\n\nThen <script>alert(2)</script> ship it.",
            files = listOf("engine/A.kt"),
            stages = listOf(PlanStage("Renderer", "escape & draw")),
            tests = listOf("DesignDeckTest"),
            acceptance = listOf("It shows"),
        ),
        questions = listOf(
            PlanQuestion("Who writes the \"HTML\"?", "The engine", 0.4, "high", listOf(PlanOption("a", "Engine"), PlanOption("b", "Smith"))),
            PlanQuestion("Sure thing?", "Yes", 0.95, "low"),
        ),
    )
    private val html = DesignDeck.render(result, "o/r", 7, "A <b>title</b>")

    @Test
    fun `has a slide per section and per important question`() {
        listOf("Design proposal", "Files", "Stages", "Tests", "Acceptance", "Question 1 of 1").forEach { assertTrue(it in html, it) }
        assertFalse("Sure thing?" in html, "a question confirm mode does not stop for gets no slide")
        assertTrue("The engine" in html && "impact high" in html && "confidence 40%" in html && "<code>a</code> Engine" in html)
        assertTrue("<code>deck</code>" in html)
    }

    @Test
    fun `escapes text and keeps the svg without its script`() {
        assertTrue("A &lt;b&gt;title&lt;/b&gt;" in html)
        assertTrue("&lt;script&gt;alert(2)" in html)
        assertTrue("Who writes the &quot;HTML&quot;?" in html)
        assertTrue("<rect width='40' height='20'/>" in html, "the mockup is carried over")
        assertFalse("<script" in html, "no script reaches the page")
        assertFalse("onload" in html)
        assertFalse("evil.example" in html)
    }

    @Test
    fun `is dark and passes the pitch rules`() {
        assertTrue("color-scheme:dark" in html)
        assertEquals(emptyList(), PitchRules.problems("deck.html", html))
    }
}

package com.codefrak.engine.intake

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** `OpenAskItemDelegate.title`, isolated from the Spring/Operaton wiring `IntakeProcessTest` exercises. */
class OpenAskItemDelegateTest {
    @Test
    fun `a leading Tier line is skipped in favour of the ask that follows`() {
        assertEquals("Intake: Make the widget blue",
            OpenAskItemDelegate.title("Tier: 3 (sonnet medium) — prescribed by the user; do not re-judge\n\nMake the widget blue."))
    }

    @Test
    fun `a Tier line directly followed by the ask, with no blank line, is also skipped`() {
        assertEquals("Intake: Make the widget blue",
            OpenAskItemDelegate.title("Tier: 3 (sonnet medium) — prescribed by the user; do not re-judge\nMake the widget blue."))
    }

    @Test
    fun `leading blank lines before the Tier line are still ignored`() {
        assertEquals("Intake: Make the widget blue",
            OpenAskItemDelegate.title("\n\nTier: 3 (sonnet medium) — prescribed by the user; do not re-judge\n\nMake the widget blue."))
    }

    @Test
    fun `a Tier line that is not the first non-empty line is kept as ordinary text`() {
        assertEquals("Intake: Make the widget blue. Tier: 3, please",
            OpenAskItemDelegate.title("Make the widget blue. Tier: 3, please"))
    }

    @Test
    fun `an ask that is nothing but a Tier line falls back to it, never to a bare Intake`() {
        assertEquals("Intake: Tier: 3 (sonnet medium) — prescribed by the user; do not re-judge",
            OpenAskItemDelegate.title("Tier: 3 (sonnet medium) — prescribed by the user; do not re-judge"))
    }

    @Test
    fun `unchanged behaviour for an ask with no Tier line`() {
        assertEquals("Intake: make the widget blue", OpenAskItemDelegate.title("\nmake the widget blue.\nand shiny"))
    }

    @Test
    fun `a generated summary is the title, with the prefix`() {
        assertEquals("Intake: Explorer shows each ticket's running and past builds",
            OpenAskItemDelegate.generatedTitle("Explorer shows each ticket's running and past builds.\n"))
        kotlin.test.assertNull(OpenAskItemDelegate.generatedTitle("  \n "))
    }

    @Test
    fun `a leading Tier line never reaches the summarizer`() {
        assertEquals("Make the widget blue.",
            OpenAskItemDelegate.withoutTier("Tier: 3 (sonnet medium) — prescribed by the user; do not re-judge\n\nMake the widget blue."))
        assertEquals("Make the widget blue. Tier: 3", OpenAskItemDelegate.withoutTier("Make the widget blue. Tier: 3"))
    }

    @Test
    fun `both forms are cut to 80 at a word boundary, never mid-word`() {
        val long = "did I ask for a ticket that shows me running and history of builds? I think so but maybe not really"
        for (t in listOf(OpenAskItemDelegate.title(long), OpenAskItemDelegate.generatedTitle(long)!!)) {
            kotlin.test.assertTrue(t.length <= 80, t)
            kotlin.test.assertTrue(t.endsWith("…"), t)
            val words = t.removePrefix("Intake: ").removeSuffix("…").split(" ")
            kotlin.test.assertTrue(words.all { it in long.split(" ") }, t)
        }
        assertEquals("Intake: short", OpenAskItemDelegate.generatedTitle("short"))
    }

    @Test
    fun `the Context names the Smith run an intake came from, and only then`() {
        val from = OpenAskItemDelegate.body("ask", "i-1", com.codefrak.engine.smith.SmithCaller("run-9", 12, "acme/widgets"))
        kotlin.test.assertTrue("- Filed by Smith run `run-9` working #12 in acme/widgets, not by a person" in from, from)
        kotlin.test.assertTrue("Filed by Smith run" !in OpenAskItemDelegate.body("ask", "i-1"))
    }

    @Test
    fun `without context the body is the quote and the Context, with no conversation section`() {
        val plain = OpenAskItemDelegate.body("make it blue", "i-1")
        assertEquals(plain, OpenAskItemDelegate.body("make it blue", "i-1", context = "  \n "))
        kotlin.test.assertTrue("Conversation context" !in plain, plain)
    }

    @Test
    fun `with context the conversation section follows the quote, labelled as the session's`() {
        val body = OpenAskItemDelegate.body("make this blue", "i-1", context = "We discussed the widget in `w.kt`.")
        val quote = body.indexOf("> make this blue")
        val section = body.indexOf("### Conversation context")
        kotlin.test.assertTrue(quote in 0 until section, body)
        kotlin.test.assertTrue(section < body.indexOf("## Context"), body)
        kotlin.test.assertTrue("not the user's words" in body, body)
        kotlin.test.assertTrue("We discussed the widget in `w.kt`." in body, body)
    }
}

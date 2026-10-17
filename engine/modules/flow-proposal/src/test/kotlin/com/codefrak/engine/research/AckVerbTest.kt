package com.codefrak.engine.research

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The rule and its combination with Jev's pick: nothing is ever "unclear", free text is a follow-up. */
class AckVerbTest {
    @Test
    fun `the three verbs parse with and without text, case and leading punctuation aside`() {
        assertEquals(Ack("accept", null), AckVerb.parse("accept"))
        assertEquals(Ack("accept", "good enough"), AckVerb.parse("Accept: good enough"))
        assertEquals(Ack("save", null), AckVerb.parse("> save"))
        assertEquals(Ack("save", "docs/notes/"), AckVerb.parse("save: docs/notes/"))
        assertEquals(Ack("drop", "no longer needed"), AckVerb.parse("drop - no longer needed"))
    }

    @Test
    fun `anything else is a follow-up carrying the whole text`() {
        assertEquals(Ack("follow-up", "And what about the H2 case?"), AckVerb.parse("And what about the H2 case?"))
        assertEquals(Ack("follow-up", "accepted, but why?"), AckVerb.parse("accepted, but why?"), "a verb with letters after it is no verb")
        assertEquals(Ack("follow-up", "savings?"), AckVerb.parse("savings?"))
        assertNull(AckVerb.parse("...").text)
    }

    @Test
    fun `a pick at the floor is taken, below it or missing the rule stands, and a written verb is never overridden`() {
        assertEquals(Ack("accept", null), AckVerb.read("Looks right to me", "accept", 0.9, 0.8).ack)
        assertEquals("took accept", AckVerb.read("Looks right to me", "accept", 0.9, 0.8).action)
        assertEquals(Ack("follow-up", "Looks right to me"), AckVerb.read("Looks right to me", "accept", 0.5, 0.8).ack, "below the floor: a follow-up, never a re-ask")
        assertEquals(Ack("follow-up", "Looks right to me"), AckVerb.read("Looks right to me", null, null, 0.8).ack, "no answer: a follow-up")
        assertEquals(Ack("drop", "not now"), AckVerb.read("drop: not now", "accept", 0.99, 0.8).ack)
        assertEquals("kept the verb as written: drop", AckVerb.read("drop: not now", "accept", 0.99, 0.8).action)
        assertEquals("verb as written: drop (jev picked accept)", AckVerb.read("drop: not now", "accept", 0.99, 0.8).outcome)
        assertEquals(Ack("follow-up", "accept it if you can tell me why"), AckVerb.read("accept it if you can tell me why", "follow-up", 0.9, 0.8).ack)
    }

    @Test
    fun `the save directory is under bots research or docs, with no climbing`() {
        assertEquals("bots/research/", SaveAnswerDelegate.directory(null))
        assertEquals("bots/research/", SaveAnswerDelegate.directory("  "))
        assertEquals("bots/research/notes/", SaveAnswerDelegate.directory("bots/research/notes"))
        assertEquals("docs/notes/", SaveAnswerDelegate.directory("`docs/notes/`"))
        assertNull(SaveAnswerDelegate.directory("engine/x"))
        assertNull(SaveAnswerDelegate.directory("docs/../engine"))
        assertNull(SaveAnswerDelegate.directory("/docs"))
        assertEquals("engine-desktop-a-research-flow", SaveAnswerDelegate.slug("Engine + desktop: a `research` flow"))
    }
}

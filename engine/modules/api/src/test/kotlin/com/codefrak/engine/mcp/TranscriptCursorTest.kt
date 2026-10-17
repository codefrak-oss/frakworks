package com.codefrak.engine.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/** read_transcript's cursor for a live parent (#2571): the previous cursor returns only the events after it. */
class TranscriptCursorTest {
    private fun event(time: String, text: String) = TranscriptEvent(time, listOf(text))
    private val first = Transcript(21, session = "s-1", events = listOf(event("10:00", "a"), event("10:01", "b"), event("10:01", "c")))

    @Test
    fun `the previous cursor returns only what is new, ties at its time included`() {
        val cursor = cursorOf(first)
        assertEquals("s-1|10:01|2", cursor)
        val later = first.copy(events = first.events + event("10:01", "d") + event("10:02", "e"))
        val (events, note) = afterCursor(later, cursor)
        assertNull(note)
        assertEquals(listOf("d", "e"), events.map { it.lines.single() })
        assertEquals(emptyList<TranscriptEvent>(), afterCursor(first, cursor).first)
    }

    @Test
    fun `no cursor, another session's or a foreign one returns every event`() {
        assertEquals(3, afterCursor(first, null).first.size)
        val (events, note) = afterCursor(first.copy(session = "s-2"), cursorOf(first))
        assertEquals(3, events.size)
        assertTrue(note!!.startsWith("a new session"))
        assertTrue(afterCursor(first, "junk").second!!.startsWith("the cursor was not one"))
    }

    @Test
    fun `read_transcript with cursors ends with the next cursor and says when nothing is new`() {
        val engine = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(FrakworksEngine::class.java)) { _, m, _ ->
            if (m.name == "transcript") first else error("unused: ${m.name}")
        } as FrakworksEngine
        val tool = TranscriptTool(engine)
        val text = tool.call(mapOf("ticket" to 21), cursors = true).text
        assertTrue(text.endsWith("Cursor: s-1|10:01|2"), text)
        assertEquals("Nothing new on #21 since the cursor.\nCursor: s-1|10:01|2",
            tool.call(mapOf("ticket" to 21, "cursor" to "s-1|10:01|2"), cursors = true).text)
        assertTrue("takes only" in tool.call(mapOf("ticket" to 21, "cursor" to "x")).text, "First mate's read_transcript takes no cursor")
    }
}

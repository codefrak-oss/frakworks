package com.codefrak.engine.board

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** `firstLine`'s blank-skipping, trimming and 200-character cap. */
class NeedsTest {
    @Test
    fun `firstLine skips leading blank lines`() {
        assertEquals("first", firstLine("\n\n  first\nsecond"))
    }

    @Test
    fun `firstLine trims the chosen line`() {
        assertEquals("padded", firstLine("  padded  \nrest"))
    }

    @Test
    fun `firstLine caps the result at 200 characters`() {
        val long = "x".repeat(250)
        assertEquals(200, firstLine(long).length)
        assertEquals(long.take(200), firstLine(long))
    }
}

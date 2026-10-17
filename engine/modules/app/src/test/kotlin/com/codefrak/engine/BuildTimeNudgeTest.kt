package com.codefrak.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Build-time measurement nudge: a bytecode change so the test task is not served from the cache. Dropped before merge. */
class BuildTimeNudgeTest {
    @Test
    fun nudge() = assertEquals(2, 1 + 1)
}

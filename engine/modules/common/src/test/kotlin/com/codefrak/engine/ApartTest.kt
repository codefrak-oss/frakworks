package com.codefrak.engine

import org.junit.jupiter.api.Test
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** [apart]: runs on another thread, with a timeout, unwrapping the callee's own exception. */
class ApartTest {
    @Test
    fun `the value comes back`() {
        assertEquals(42, apart(5) { 42 })
    }

    @Test
    fun `an exception thrown inside comes out as itself`() {
        val e = assertFailsWith<IllegalStateException> { apart(5) { throw IllegalStateException("boom") } }
        assertEquals("boom", e.message)
    }

    @Test
    fun `a timeout after the limit is a TimeoutException`() {
        assertFailsWith<TimeoutException> { apart(0) { Thread.sleep(500) } }
    }
}

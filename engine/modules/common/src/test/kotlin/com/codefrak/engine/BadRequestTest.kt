package com.codefrak.engine

import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** [refuseUnknownFields]: the request-body checks every write endpoint shares. */
class BadRequestTest {
    @Test
    fun `refuseUnknownFields is silent when every key is allowed`() {
        refuseUnknownFields(listOf("a", "b"), setOf("a", "b", "c"), "a, b and c")
    }

    @Test
    fun `refuseUnknownFields 400s naming one unknown field`() {
        val e = assertFailsWith<ResponseStatusException> { refuseUnknownFields(listOf("a", "x"), setOf("a", "b"), "a and b") }
        assertEquals("unknown field(s): x; only a and b", e.reason)
    }

    @Test
    fun `refuseUnknownFields 400s naming every unknown field, in the order given`() {
        val e = assertFailsWith<ResponseStatusException> { refuseUnknownFields(listOf("x", "a", "y"), setOf("a"), "a") }
        assertEquals("unknown field(s): x, y; only a", e.reason)
    }
}

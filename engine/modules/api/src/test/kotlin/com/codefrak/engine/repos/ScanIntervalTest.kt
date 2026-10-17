package com.codefrak.engine.repos

import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** `scanMinutes`'s accepted types, its range check and its refusal reason, without a Spring context. */
class ScanIntervalTest {
    @Test
    fun `an Int, a Long or a BigInteger within range is accepted`() {
        assertEquals(1, scanMinutes(1))
        assertEquals(1, scanMinutes(1L))
        assertEquals(1440, scanMinutes(BigInteger.valueOf(1440)))
    }

    @Test
    fun `out of range, the wrong type, or null is refused with 400 and INTERVAL_REASON`() {
        for (bad in listOf(0, 1441, 2.0, 2.5, "5", true, null)) {
            val e = assertFailsWith<ResponseStatusException>("$bad should be refused") { scanMinutes(bad) }
            assertEquals(HttpStatus.BAD_REQUEST, e.statusCode)
            assertEquals(INTERVAL_REASON, e.reason)
        }
    }
}

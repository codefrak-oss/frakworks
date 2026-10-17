package com.codefrak.engine

import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [parseSince]: the `since` filter's shared ISO instant / offset date-time / plain-date-at-UTC-midnight fallback chain. */
class SinceTest {
    @Test
    fun `an offset date-time parses as itself`() {
        assertEquals(OffsetDateTime.parse("2026-10-04T18:49:35+02:00"), parseSince("2026-10-04T18:49:35+02:00"))
    }

    @Test
    fun `a bare instant parses at UTC`() {
        assertEquals(OffsetDateTime.parse("2026-10-04T18:49:35Z"), parseSince("2026-10-04T18:49:35Z"))
    }

    @Test
    fun `a plain date is its UTC midnight`() {
        assertEquals(OffsetDateTime.parse("2026-10-04T00:00:00Z"), parseSince("2026-10-04"))
    }

    @Test
    fun `an unparseable string is null`() {
        assertNull(parseSince("yesterday"))
    }
}

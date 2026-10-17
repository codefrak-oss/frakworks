package com.codefrak.engine

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** A `since` filter string as an ISO instant, an offset date-time, or a date (its UTC midnight); null if none parses. */
fun parseSince(text: String): OffsetDateTime? {
    val t = text.trim()
    return runCatching { OffsetDateTime.parse(t) }.recoverCatching { Instant.parse(t).atOffset(ZoneOffset.UTC) }
        .recoverCatching { LocalDate.parse(t).atStartOfDay().atOffset(ZoneOffset.UTC) }.getOrNull()
}

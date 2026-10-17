package com.codefrak.engine

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Date

/** [at] in UTC, ISO-8601. */
fun utc(at: OffsetDateTime): String = at.withOffsetSameInstant(ZoneOffset.UTC).toString()

/** This `Date` (what Operaton's history and runtime APIs answer) as a UTC `OffsetDateTime`. */
fun Date.at(): OffsetDateTime = toInstant().atOffset(ZoneOffset.UTC)

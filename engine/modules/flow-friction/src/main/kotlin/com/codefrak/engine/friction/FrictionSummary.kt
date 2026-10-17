package com.codefrak.engine.friction

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * `GET /friction/summary`: the window from [from] to [to] (UTC ISO-8601), its [total], the notes per UTC day
 * ([perDay], every day of the window, zeros included), the counts by kind, source, severity (`unset` for a note
 * with none) and repo (`none` for a First mate note that named none), the distinct [groups] the window's notes are
 * in and the [ungrouped] notes still waiting for the grouper. The totals count raw notes; the group count stands beside.
 */
data class FrictionSummary(
    val from: String, val to: String, val total: Int, val perDay: List<FrictionDay>,
    val byKind: Map<String, Int>, val bySource: Map<String, Int>, val bySeverity: Map<String, Int>, val byRepo: Map<String, Int>,
    val groups: Int, val ungrouped: Int,
) {
    /** The JSON the endpoint answers, its keys snake_case like the rest of `/friction`. */
    fun json(): Map<String, Any?> = linkedMapOf(
        "from" to from, "to" to to, "total" to total,
        "per_day" to perDay.map { linkedMapOf("day" to it.day, "count" to it.count) },
        "by_kind" to byKind, "by_source" to bySource, "by_severity" to bySeverity, "by_repo" to byRepo,
        "groups" to groups, "ungrouped" to ungrouped,
    )
}

/** One UTC day of [FrictionSummary.perDay], `YYYY-MM-DD`. */
data class FrictionDay(val day: String, val count: Int)

/** Builds [FrictionSummary] from the window's notes, in Kotlin rather than SQL so H2 and Postgres answer alike (as `JevSummaries`). */
object FrictionSummaries {
    const val UNSET = "unset"
    const val NO_REPO = "none"

    fun of(notes: List<FrictionNote>, from: OffsetDateTime, to: OffsetDateTime): FrictionSummary {
        val days = notes.groupingBy { day(it.notedAt) }.eachCount()
        val perDay = generateSequence(day(from)) { it.plusDays(1) }.takeWhile { !it.isAfter(day(to)) }
            .map { FrictionDay(it.toString(), days[it] ?: 0) }.toList()
        return FrictionSummary(
            utc(from), utc(to), notes.size, perDay,
            counts(FrictionInput.KINDS, notes.map { it.kind }),
            counts(FrictionNote.SOURCES, notes.map { it.source }),
            counts(FrictionInput.SEVERITIES + UNSET, notes.map { it.severity ?: UNSET }),
            notes.groupingBy { it.repo ?: NO_REPO }.eachCount().entries.sortedByDescending { it.value }.associate { it.key to it.value },
            notes.mapNotNull { it.groupId }.distinct().size, notes.count { it.groupId == null },
        )
    }

    /** Every known value counted (zero included) in their order, then any other value seen. */
    private fun counts(known: List<String>, values: List<String>): Map<String, Int> {
        val out = linkedMapOf<String, Int>()
        known.forEach { out[it] = 0 }
        values.forEach { out.merge(it, 1, Int::plus) }
        return out
    }

    private fun day(at: OffsetDateTime) = at.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate()

    /** [at] in UTC, ISO-8601 (`2026-10-05T12:00:00Z`). Keeps seconds where the shared [com.codefrak.engine.utc] (`OffsetDateTime.toString()`) drops them on the minute, so `recurredAt`/`newest`/`oldest`/`noted_at` stay byte-for-byte unchanged. */
    fun utc(at: OffsetDateTime): String = at.withOffsetSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}

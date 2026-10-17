package com.codefrak.engine.camunda

import com.fasterxml.jackson.annotation.JsonInclude

/*
 * GET /ticket/{owner}/{repo}/{n}/timeline's contract, the one the desktop Timeline widget
 * was built against (src/test/resources/fixture-ticket-timeline.json): `start`/`end` are
 * epoch seconds, every `*Ms` is milliseconds.
 */

/**
 * `state` OPEN while any of the issue's flows runs; `round` the highest step round; `tier` null when unset.
 * `blocked` only for an intake whose refine result is `insufficient-permissions` (no key otherwise).
 */
data class TimelineTicket(
    val number: Long, val title: String?, val state: String, val round: Int, val kind: String, val tier: Int?,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val blocked: TimelineBlocked? = null,
)

/** Why an intake stopped: `kind` is `insufficient-permissions`; `summary` at most 20 words; `reason` the refiner's full reason. */
data class TimelineBlocked(val kind: String, val summary: String, val reason: String)

/**
 * One Smith run. `end` is null while it runs, and then `durationMs` is to now, `cost` null, and `turns` and
 * `tokens` the live tally's so far (SmithSessions.live), null before its first `assistant` event. Once
 * `collect result` has run, `cost` and `turns` are the closing `result`'s and `tokens` is null.
 * `catchUp` is true for a security or review run in a round that began with a merge from the default branch
 * (`bring pr up to date`), not a work Smith's push.
 */
data class TimelineStep(
    val role: String, val round: Int, val start: Long, val end: Long?, val durationMs: Long,
    val model: String?, val effort: String?, val cost: Double?, val turns: Long?, val outcome: String?, val running: Boolean,
    val catchUp: Boolean = false, val tokens: TimelineTokens? = null,
    /** The AI account the run is on (`engine.smith_run_stats.account`); null when unknown. */
    val account: String? = null,
)

/** A running step's tokens so far, summed over its messages: input, output, cache read and cache creation (`cacheWrite`). */
data class TimelineTokens(val input: Long, val output: Long, val cacheRead: Long, val cacheWrite: Long)

/**
 * `cause` is one of `person-authorization`, `person-question`, `stuck`, `ci`, `engine`; `payload` as in the fixture per cause.
 * A `ci` wait is time `reusable-ci-check`'s `receive-ci-check` spent waiting on the engine check: its payload has `check` and
 * `conclusion` (null while it waits, or when the flow recorded none) besides `steps`.
 */
data class TimelineWait(val start: Long, val end: Long, val durationMs: Long, val cause: String, val payload: Map<String, Any?>)

/** `elapsedMs = workingMs + waitingMs`, `waitingMs = waitingPersonMs + stuckMs + ciMs + engineMs`, `costUsd` the steps' costs. */
data class TimelineTotals(
    val elapsedMs: Long, val workingMs: Long, val waitingMs: Long, val costUsd: Double,
    val waitingPersonMs: Long, val stuckMs: Long, val ciMs: Long, val engineMs: Long,
)

/**
 * One Smith run as read_ticket_runs reads it (#1162), not part of the timeline's contract: the `smith-run`
 * process instance id (its run id), its mode, the step's round, the step itself, and for a review or
 * security run the findings it returned (stamped, empty when it approved or passed); null for other modes.
 */
data class TicketRun(val runId: String, val mode: String, val round: Int, val step: TimelineStep, val findings: List<com.codefrak.engine.smith.Finding>?)

data class Timeline(val ticket: TimelineTicket, val steps: List<TimelineStep>, val waits: List<TimelineWait>, val totals: TimelineTotals)

/** A stretch of time in epoch milliseconds; an open one ends at the request's `now`. */
data class Span(val start: Long, val end: Long) {
    val length get() = end - start
}

/** A step with the time it covers, in milliseconds. */
data class PlacedStep(val step: TimelineStep, val span: Span)

/**
 * Something history shows happening outside the steps. `ACTIVITY` marks only fill a wait's
 * `payload.steps`; the others make the wait they fall in a person's, stuck or CI one, the
 * higher `rank` winning where two overlap. `detail` is the payload a mark brings.
 */
data class Mark(val kind: Kind, val span: Span, val name: String, val detail: Map<String, Any?> = emptyMap()) {
    enum class Kind(val cause: String, val rank: Int) {
        STUCK("stuck", 4), QUESTION("person-question", 3), AUTHORIZATION("person-authorization", 2), CI("ci", 1), ACTIVITY("engine", 0)
    }
}

object TicketTimeline {
    const val PERSON_QUESTION = "person-question"; const val PERSON_AUTHORIZATION = "person-authorization"
    const val STUCK = "stuck"; const val CI = "ci"; const val ENGINE = "engine"

    /**
     * The timeline over `span`: every stretch of it no step covers (before the first, between
     * two, after the last) is cut into waits by the marks in it, and totals are summed from
     * the steps and waits, so their identities hold by construction.
     */
    fun build(ticket: TimelineTicket, steps: List<PlacedStep>, span: Span, marks: List<Mark>): Timeline {
        val placed = steps.sortedBy { it.span.start }
        val waits = gaps(span, placed.map { it.span }).flatMap { cut(it, marks) }
        val waitingMs = waits.sumOf { it.durationMs }
        val workingMs = span.length - gaps(span, placed.map { it.span }).sumOf { it.length }
        val byCause = waits.groupBy { it.cause }.mapValues { (_, w) -> w.sumOf { it.durationMs } }
        val cost = placed.sumOf { it.step.cost ?: 0.0 }
        return Timeline(ticket, placed.map { it.step }, waits, TimelineTotals(
            elapsedMs = workingMs + waitingMs, workingMs = workingMs, waitingMs = waitingMs,
            costUsd = Math.round(cost * 1_000_000) / 1_000_000.0,
            waitingPersonMs = (byCause[PERSON_QUESTION] ?: 0) + (byCause[PERSON_AUTHORIZATION] ?: 0),
            stuckMs = byCause[STUCK] ?: 0, ciMs = byCause[CI] ?: 0, engineMs = byCause[ENGINE] ?: 0,
        ))
    }

    /** The stretches of `span` no working span covers, in time order. */
    fun gaps(span: Span, working: List<Span>): List<Span> {
        val gaps = mutableListOf<Span>()
        var cursor = span.start
        for (w in working.sortedBy { it.start }) {
            if (w.start > cursor) gaps += Span(cursor, minOf(w.start, span.end))
            cursor = maxOf(cursor, w.end)
            if (cursor >= span.end) break
        }
        if (cursor < span.end) gaps += Span(cursor, span.end)
        return gaps.filter { it.length > 0 }
    }

    /**
     * One gap as waits: each stretch goes to the highest-ranked mark over it (the earlier one
     * on a tie), and what no person, stuck or CI mark covers is the engine's. Neighbouring
     * stretches of the same mark, or both the engine's, are one wait.
     */
    fun cut(gap: Span, marks: List<Mark>): List<TimelineWait> {
        val causes = marks.filter { it.kind != Mark.Kind.ACTIVITY && it.span.start < gap.end && it.span.end > gap.start }
        val points = (listOf(gap.start, gap.end) + causes.flatMap { listOf(it.span.start, it.span.end) })
            .filter { it in gap.start..gap.end }.distinct().sorted()
        val pieces = mutableListOf<Pair<Span, Mark?>>()
        for ((p, q) in points.zipWithNext()) {
            val owner = causes.filter { it.span.start <= p && it.span.end >= q }
                .sortedWith(compareByDescending<Mark> { it.kind.rank }.thenBy { it.span.start }).firstOrNull()
            val last = pieces.lastOrNull()
            if (last != null && last.second === owner) pieces[pieces.lastIndex] = Span(last.first.start, q) to owner
            else pieces += Span(p, q) to owner
        }
        return pieces.map { (s, owner) -> wait(s, owner, marks) }
    }

    private fun wait(s: Span, owner: Mark?, marks: List<Mark>): TimelineWait {
        val steps = marks.filter { it.kind == Mark.Kind.ACTIVITY && it.span.start < s.end && it.span.end > s.start }
            .sortedBy { it.span.start }
            .map { linkedMapOf("name" to it.name, "start" to seconds(maxOf(it.span.start, s.start)), "end" to seconds(minOf(it.span.end, s.end))) }
        val payload: Map<String, Any?> = when (owner?.kind) {
            Mark.Kind.QUESTION -> linkedMapOf("question" to owner.detail["question"], "answer" to owner.detail["answer"])
            Mark.Kind.STUCK -> linkedMapOf("incident" to owner.detail["incident"], "retrier" to owner.detail["retrier"], "steps" to steps)
            Mark.Kind.CI -> linkedMapOf("steps" to steps, "conclusion" to owner.detail["conclusion"], "check" to owner.detail["check"])
            else -> linkedMapOf("steps" to steps)
        }
        return TimelineWait(seconds(s.start), seconds(s.end), s.length, owner?.kind?.cause ?: ENGINE, payload)
    }

    fun seconds(epochMs: Long) = Math.floorDiv(epochMs, 1000L)

    const val SUMMARY_WORDS = 20

    /** The `insufficient-permissions` outcome's name before the rename (#708): a refine result stored with it reads as the new token. */
    const val LEGACY_OUTCOME = "capability"

    /**
     * The banner for an intake whose refiner said `insufficient-permissions`, null for any other outcome.
     * `summary` is the refiner's `blocker` when it gave one; otherwise (a result from before
     * `blocker` existed) the first sentence of `reason`. Either is cut to SUMMARY_WORDS words.
     */
    fun blocked(outcome: String?, blocker: String?, reason: String?): TimelineBlocked? {
        if (outcome != "insufficient-permissions" && outcome != LEGACY_OUTCOME) return null
        val full = reason?.trim().orEmpty()
        val summary = blocker?.trim()?.takeIf { it.isNotEmpty() } ?: firstSentence(full)
        return TimelineBlocked("insufficient-permissions", words(summary, SUMMARY_WORDS), full)
    }

    /** Up to the first `.`, `!` or `?` followed by whitespace, on the first non-blank line. */
    fun firstSentence(text: String): String {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
        val end = Regex("""[.!?](?=\s|$)""").find(line)?.range?.last ?: return line
        return line.substring(0, end + 1)
    }

    /** `text` cut to `max` words, with an ellipsis when anything was cut. */
    fun words(text: String, max: Int): String {
        val all = text.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        return if (all.size <= max) all.joinToString(" ") else all.take(max).joinToString(" ") + "…"
    }

    /**
     * A run's span, extended to its `reusable-smith-run` wrapper's own start and end: the
     * wrapper's `start-smith-run` runs before `call-smith-run` starts the inner run, and its
     * `task-read-result`/`end-smith-run` run after it ends, so placing a step at the inner
     * run's own timestamps left those wrapper activities as separate short engine waits either
     * side of it. A run that finished cleanly (no incident) is placed at the wrapper's edges
     * when known; one with an incident keeps the tighter, unextended reading, since `stuckAt`
     * (not the wrapper) says where it stopped working.
     */
    fun runSpan(runStart: Long, runEnd: Long?, stuckAt: Long?, openIncident: Boolean, wrapperStart: Long?, wrapperEnd: Long?): Pair<Long, Long?> {
        val start = wrapperStart?.let { minOf(it, runStart) } ?: runStart
        val end = when {
            runEnd != null && stuckAt == null -> wrapperEnd ?: runEnd
            runEnd != null -> listOfNotNull(runEnd, stuckAt).min()
            openIncident -> stuckAt
            else -> null
        }
        return start to end
    }
}

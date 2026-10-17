package com.codefrak.engine.ci

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * What First mate's CI log tools answer from a job and its cleaned log: its steps'
 * times with the slowest and a possibly stuck one marked, bounded searches with context, and a bounded tail.
 */
object CiLogSearch {
    const val MAX_TAIL = 200
    const val MAX_MATCHES = 50
    const val DEFAULT_MATCHES = 20
    const val MAX_CONTEXT = 5
    const val DEFAULT_CONTEXT = 2
    const val MAX_PATTERN = 200
    /** A line in an answer is cut to this many characters (a minified bundle on one line must not fill the budget). */
    const val MAX_LINE = 400
    /** How long one search may run its regex over the log before it gives up (a pattern that backtracks badly). */
    val SEARCH_TIME: Duration = Duration.ofSeconds(2)
    /** A running step is marked possibly stuck past this long and past [STUCK_FACTOR] times its job's median finished step. */
    const val STUCK_FLOOR_SECONDS = 600L
    const val STUCK_FACTOR = 3

    /** The search when no pattern is given: lines that are often worth a look in a passed job. */
    val SUSPICIOUS = Regex("""(?i)\b(warn(ing)?|deprecat\w*|retry(ing)?|retries|flaky|cache (miss|not found)|timed? ?out|exception|error)\b""")

    /** A line as an answer shows it: cut at [MAX_LINE] characters, saying so. */
    fun shown(line: String) = if (line.length <= MAX_LINE) line else line.take(MAX_LINE) + " … (${line.length} characters)"

    private fun instant(s: String?): Instant? = s?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

    /** A step's or job's elapsed seconds: to its end, or to [now] while it runs; null when it has not started. */
    fun elapsed(startedAt: String?, completedAt: String?, now: Instant): Long? {
        val start = instant(startedAt) ?: return null
        val end = instant(completedAt) ?: now
        return Duration.between(start, end).seconds.coerceAtLeast(0)
    }

    /** The job as a row: its times, and its steps each with its elapsed seconds, the slowest and a possibly stuck one marked. */
    fun job(job: WorkflowJob, now: Instant): MutableMap<String, Any?> {
        val times = job.steps.map { elapsed(it.startedAt, it.completedAt, now) }
        val slowest = times.withIndex().filter { it.value != null }.maxByOrNull { it.value!! }?.index
        val finished = job.steps.indices.filter { job.steps[it].status == "completed" }.map { times[it] }
        val median = CiRuns.median(finished) ?: 0.0
        val steps = job.steps.mapIndexed { i, s ->
            val row = linkedMapOf<String, Any?>("number" to s.number, "name" to s.name, "status" to s.status, "conclusion" to s.conclusion,
                "startedAt" to s.startedAt, "completedAt" to s.completedAt, "elapsedSeconds" to times[i])
            if (i == slowest) row["slowest"] = true
            val t = times[i]
            if (s.status == "in_progress" && t != null && t > STUCK_FLOOR_SECONDS && t > STUCK_FACTOR * median) row["possiblyStuck"] = true
            row
        }
        return linkedMapOf("id" to job.id, "runId" to job.runId, "name" to job.name, "status" to job.status, "conclusion" to job.conclusion,
            "startedAt" to job.startedAt, "completedAt" to job.completedAt, "elapsedSeconds" to elapsed(job.startedAt, job.completedAt, now),
            "url" to job.url, "slowestStep" to slowest?.let { job.steps[it].name }, "steps" to steps)
    }

    /** The last [n] lines (at most [MAX_TAIL]), trailing blank lines dropped, each [shown]. */
    fun tail(lines: List<String>, n: Int): List<String> =
        lines.dropLastWhile { it.isBlank() }.takeLast(n.coerceIn(1, MAX_TAIL)).map(::shown)

    /** One match: its 1-based line number, the line, and up to the context lines before and after it. */
    data class Match(val line: Int, val text: String, val before: List<String>, val after: List<String>)

    /** The first [max] matches with [context] lines each side, the total count, and whether the time guard stopped it. */
    data class Found(val matches: List<Match>, val total: Int, val timedOut: Boolean)

    /** A regex from [pattern] (quoted when [fixed]), or the reason it is refused. */
    fun compile(pattern: String, fixed: Boolean): Result<Regex> = when {
        pattern.isEmpty() -> Result.failure(IllegalArgumentException("an empty pattern"))
        pattern.length > MAX_PATTERN -> Result.failure(IllegalArgumentException("a pattern longer than $MAX_PATTERN characters"))
        fixed -> Result.success(Regex(Regex.escape(pattern)))
        else -> runCatching { Regex(pattern) }.recoverCatching { throw IllegalArgumentException("not a regex: ${it.message?.lineSequence()?.first()}") }
    }

    private class TimeUp : RuntimeException(null, null, false, false)

    /** A line whose characters stop the match once [deadline] passes, so a backtracking regex cannot hold the engine. */
    private class Guarded(private val s: CharSequence, private val deadline: Long) : CharSequence {
        override val length get() = s.length
        override fun get(index: Int): Char { if (System.nanoTime() > deadline) throw TimeUp(); return s[index] }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = Guarded(s.subSequence(startIndex, endIndex), deadline)
        override fun toString() = s.toString()
    }

    fun search(lines: List<String>, regex: Regex, context: Int, max: Int, time: Duration = SEARCH_TIME): Found {
        val deadline = System.nanoTime() + time.toNanos()
        val c = context.coerceIn(0, MAX_CONTEXT)
        val matches = mutableListOf<Match>()
        var total = 0
        try {
            for ((i, line) in lines.withIndex()) {
                if (!regex.containsMatchIn(Guarded(line, deadline))) continue
                total++
                if (matches.size < max) matches += Match(i + 1, shown(line),
                    lines.subList(maxOf(0, i - c), i).map(::shown), lines.subList(i + 1, minOf(lines.size, i + 1 + c)).map(::shown))
            }
        } catch (e: TimeUp) {
            return Found(matches, total, true)
        }
        return Found(matches, total, false)
    }
}

package com.codefrak.engine.ci

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * A completed job's cleaned log ([CiLog.clean]) per (repo, job id), so First mate's read_ci_job and search_ci_log on
 * the same job fetch it from GitHub once. A completed job's log does not change,
 * so an entry lives [TTL] unless the cache's [MAX_CHARS] bound evicts it first, least recently used first. A running
 * job's log is fetched on every call and never kept. A log above [MAX_LOG_BYTES] is not fetched whole at all
 * ([CheckRuns.boundedLog]): its [Log] has no lines, only its size.
 */
class CiLogCache(private val clock: Clock = Clock.systemUTC(), private val maxChars: Long = MAX_CHARS) {
    companion object {
        /** The largest log the engine fetches to search or tail: 8 MB. */
        const val MAX_LOG_BYTES = 8L * 1024 * 1024
        /** Characters of cleaned lines the cache holds over all its jobs: 32 M (about 64 MB of heap). */
        const val MAX_CHARS = 32L * 1024 * 1024
        val TTL: Duration = Duration.ofHours(6)

        private val stamp = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z)""")

        /** The last Actions timestamp a line of [raw] starts with, or null: when the job last wrote output. */
        fun lastOutputAt(raw: String): String? {
            var end = raw.length
            while (end > 0) {
                val start = raw.lastIndexOf('\n', end - 1) + 1
                stamp.find(raw.substring(start, end))?.let { return it.groupValues[1] }
                end = start - 1
            }
            return null
        }
    }

    /** A job's log: its cleaned [lines] (null when it was above [MAX_LOG_BYTES]), its size in bytes, its last output time. */
    data class Log(val lines: List<String>?, val size: Long, val lastOutputAt: String?) {
        val chars: Long get() = lines?.sumOf { it.length.toLong() + 1 } ?: 0
    }

    private data class Entry(val log: Log, val at: Instant)
    private val entries = LinkedHashMap<Pair<String, Long>, Entry>(16, 0.75f, true)
    private var held = 0L
    /** GitHub log fetches made through this cache, for tests and the README's "fetched once" claim. */
    @Volatile var fetches = 0
        private set

    /** The log of [jobId] in [repo]: the cached one for a [completed] job still fresh, else fetched (and kept when [completed]). */
    fun get(runs: CheckRuns, repo: String, jobId: Long, completed: Boolean): Log {
        val key = repo.lowercase() to jobId
        if (completed) synchronized(this) {
            entries[key]?.let { e -> if (Duration.between(e.at, clock.instant()) < TTL) return e.log; drop(key) }
        }
        fetches++
        val fetched = runs.boundedLog(repo, jobId, MAX_LOG_BYTES)
        val log = Log(fetched.text?.let { CiLog.clean(it) }, fetched.size, fetched.text?.let { lastOutputAt(it) })
        if (completed && log.chars <= maxChars) synchronized(this) {
            drop(key)
            entries[key] = Entry(log, clock.instant())
            held += log.chars
            val oldest = entries.entries.iterator()
            while (held > maxChars && oldest.hasNext()) { held -= oldest.next().value.log.chars; oldest.remove() }
        }
        return log
    }

    private fun drop(key: Pair<String, Long>) { entries.remove(key)?.let { held -= it.log.chars } }

    /** How many jobs' logs are held now. */
    @Synchronized fun size() = entries.size
}

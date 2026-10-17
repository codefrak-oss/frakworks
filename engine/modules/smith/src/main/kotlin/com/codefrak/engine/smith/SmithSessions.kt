package com.codefrak.engine.smith

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** One row of `engine.smith_session`: a run whose stdout the engine stores. */
data class SmithSession(
    val runId: String, val repo: String, val issue: Long?, val mode: String, val model: String,
    val startedAt: OffsetDateTime, val endedAt: OffsetDateTime?,
)

/**
 * A live Smith's session in the engine's own database (engine/README.md,
 * "Summarize: a live run summarized"): `engine.smith_session` and
 * `engine.smith_session_line`. [SmithTelemetry]'s follow of the sandbox's
 * stdout hands every line to [offer] while the run goes on, so the table has
 * the session as it happens, not only after `collect result`.
 *
 * Like the VictoriaLogs shipper, nothing here may touch a run: [offer] only
 * queues, a virtual thread of the run's own writes in batches, outside any
 * transaction of the engine's, and a full queue or a failed insert drops
 * lines with one WARN per streak.
 */
@Component
class SmithSessions(private val template: JdbcTemplate) {
    private val log = LoggerFactory.getLogger(SmithSessions::class.java)
    private val jdbc = JdbcClient.create(template)
    private val writers = ConcurrentHashMap<String, Writer>()
    /** Each live run's [LiveTally], from the lines [offer] receives; dropped by [forget] at `collect result`. */
    private val tallies = ConcurrentHashMap<String, LiveTally>()

    /** A run is live: record it and start its writer. A run resumed after a restart keeps its row and numbers on. */
    fun open(labels: RunLabels) {
        tallies.putIfAbsent(labels.runId, LiveTally())
        val writer = Writer(labels)
        writer.start(writers.put(labels.runId, writer))
    }

    /** One stdout line of a live run, in the order read, uncut for its tally. Never blocks or throws; a run not [open] drops it. */
    fun offer(runId: String, line: String) {
        val writer = writers[runId] ?: return
        runCatching { tallies[runId]?.feed(line) }
        writer.offer(line)
    }

    /** The run is over: its writer stores what it holds, then sets `ended_at`. Returns at once; idempotent. Its tally stays until [forget]. */
    fun close(runId: String) { writers[runId]?.finish() }

    /** This instance stops following the run (the lease moved): its writer stores what it holds and leaves `ended_at` unset, and its tally goes. */
    fun detach(runId: String) { writers[runId]?.finish(end = false); tallies.remove(runId) }

    /**
     * A running run's turns and tokens so far ([LiveTally]); null for a run with no session. A tally
     * not in memory (the engine restarted) is rebuilt once from the stored lines, where a line stored
     * cut no longer counts; on a replica that does not follow the run, from the stored lines on each call.
     */
    fun live(runId: String): LiveFigures? {
        val tally = tallies[runId] ?: when {
            session(runId) == null -> return null
            // Followed on another replica (the lease holder): read from what it stored, every time, never cached here.
            !writers.containsKey(runId) -> return LiveTally().also { t -> lines(runId).forEach(t::feed) }.figures()
            else -> tallies.computeIfAbsent(runId) { LiveTally() }
        }
        if (tally.seeded.compareAndSet(false, true)) lines(runId).forEach(tally::feed)
        return tally.figures()
    }

    /** `collect result` set the run's own cost and turns: its tally is no longer read. */
    fun forget(runId: String) { tallies.remove(runId) }

    fun session(runId: String): SmithSession? =
        jdbc.sql("SELECT run_id, repo, issue, mode, model, started_at, ended_at FROM engine.smith_session WHERE run_id = :r")
            .param("r", runId).query { rs, _ ->
                SmithSession(rs.getString(1), rs.getString(2), rs.getObject(3, Long::class.javaObjectType), rs.getString(4), rs.getString(5),
                    rs.getObject(6, OffsetDateTime::class.java), rs.getObject(7, OffsetDateTime::class.java))
            }.optional().orElse(null)

    /**
     * The newest run on `repo`#`issue` whose session is open (`ended_at` unset): read from the database, so every
     * replica answers the same, not only the one that follows the run (#1716). With `mode`, only a run of that mode
     * (`review` for the review prediction, #1746).
     */
    fun running(repo: String, issue: Long, mode: String? = null): SmithSession? =
        jdbc.sql("""SELECT run_id, repo, issue, mode, model, started_at, ended_at FROM engine.smith_session
            WHERE lower(repo) = lower(:repo) AND issue = :i AND ended_at IS NULL${if (mode == null) "" else " AND mode = :m"}
            ORDER BY started_at DESC LIMIT 1""")
            .param("repo", repo).param("i", issue).let { if (mode == null) it else it.param("m", mode) }.query { rs, _ ->
                SmithSession(rs.getString(1), rs.getString(2), rs.getObject(3, Long::class.javaObjectType), rs.getString(4), rs.getString(5),
                    rs.getObject(6, OffsetDateTime::class.java), rs.getObject(7, OffsetDateTime::class.java))
            }.optional().orElse(null)

    /** Every stored line of the run, oldest first. */
    fun lines(runId: String): List<String> =
        jdbc.sql("SELECT line FROM engine.smith_session_line WHERE run_id = :r ORDER BY seq")
            .param("r", runId).query(String::class.java).list().filterNotNull()

    /** [SessionDigest.digest] of what is stored for `runId`. */
    fun digest(runId: String, maxChars: Int = SessionDigest.DIGEST_CHARS): String = SessionDigest.digest(lines(runId), maxChars)

    /** The last [turns] steps of [SessionDigest.digest]'s, whole, for what a run is doing now (the summarize endpoint's "most recent turns"). */
    fun recent(runId: String, turns: Int = SessionDigest.RECENT_TURNS): String = SessionDigest.recent(lines(runId), turns)

    /**
     * One run's writer, on a virtual thread of its own: `open` inserts the session row (or reopens
     * it), then batches of queued lines go in until [finish], then `ended_at`.
     */
    private inner class Writer(private val labels: RunLabels) {
        private val queue = LinkedBlockingQueue<Pair<Instant, String>>(MAX_QUEUED)
        @Volatile private var finishing = false
        private var dropped = 0
        private var failing = false
        private var seq = 0L
        @Volatile var thread: Thread? = null

        /** A writer of the same run still storing (a run resumed right after it ended) finishes first: one writer per run numbers the lines. */
        fun start(prior: Writer?) {
            prior?.finish()
            thread = Thread.ofVirtual().name("smith-session-${labels.runId.take(8)}").start {
                runCatching { prior?.thread?.join(PRIOR_MILLIS) }
                loop()
            }
        }

        @Synchronized fun offer(line: String) {
            if (!queue.offer(Instant.now() to cut(line)) && dropped++ == 0)
                log.warn("run {}: session queue full ({} lines); dropping lines until it drains", labels.runId, MAX_QUEUED)
        }

        @Volatile private var end = true

        fun finish(end: Boolean = true) { this.end = end; finishing = true }

        private fun loop() {
            try {
                if (!attempt("open the session") { seq = openRow() }) return
                while (true) {
                    val first = try { queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { return }
                    if (first == null) { if (finishing) break else continue }
                    val batch = mutableListOf(first)
                    queue.drainTo(batch, MAX_BATCH - 1)
                    val from = seq
                    seq += batch.size
                    if (attempt("store ${batch.size} lines") { insert(from, batch) }) { failing = false; dropped = 0 }
                }
                if (end) attempt("end the session") {
                    jdbc.sql("UPDATE engine.smith_session SET ended_at = :t WHERE run_id = :r")
                        .param("t", OffsetDateTime.now(ZoneOffset.UTC)).param("r", labels.runId).update()
                }
            } finally {
                writers.remove(labels.runId, this)
            }
        }

        /** The row, inserted or reopened; the next line's number. */
        private fun openRow(): Long {
            val inserted = jdbc.sql("""INSERT INTO engine.smith_session (run_id, repo, issue, mode, model, started_at)
                SELECT :r, :repo, :issue, :mode, :model, :t WHERE NOT EXISTS (SELECT 1 FROM engine.smith_session WHERE run_id = :r)""")
                .param("r", labels.runId).param("repo", labels.repo).param("issue", labels.issue)
                .param("mode", labels.phase).param("model", labels.model).param("t", OffsetDateTime.now(ZoneOffset.UTC)).update()
            if (inserted == 1) { tallies[labels.runId]?.seeded?.set(true); return 0 }
            jdbc.sql("UPDATE engine.smith_session SET ended_at = NULL WHERE run_id = :r").param("r", labels.runId).update()
            return jdbc.sql("SELECT COALESCE(MAX(seq) + 1, 0) FROM engine.smith_session_line WHERE run_id = :r")
                .param("r", labels.runId).query(Long::class.java).single()
        }

        private fun insert(from: Long, batch: List<Pair<Instant, String>>) {
            template.batchUpdate("INSERT INTO engine.smith_session_line (run_id, seq, read_at, line) VALUES (?, ?, ?, ?)",
                batch.mapIndexed { i, (at, line) -> arrayOf<Any>(labels.runId, from + i, OffsetDateTime.ofInstant(at, ZoneOffset.UTC), line) })
        }

        private fun attempt(what: String, block: () -> Unit): Boolean = try {
            block(); true
        } catch (e: Exception) {
            if (!failing) log.warn("run {}: cannot {} in engine.smith_session ({}); the run goes on", labels.runId, what, e.message)
            else log.debug("run {}: cannot {} again ({})", labels.runId, what, e.message)
            failing = true
            false
        }
    }

    companion object {
        const val MAX_QUEUED = 10_000
        const val MAX_BATCH = 500
        const val POLL_MILLIS = 250L
        const val PRIOR_MILLIS = 10_000L
        /** A line longer than this (a big tool result) is stored cut, with a marker. */
        const val MAX_LINE = 64 * 1024

        fun cut(line: String): String =
            if (line.length <= MAX_LINE) line else line.take(MAX_LINE) + " …[cut: ${line.length - MAX_LINE} more chars]"
    }
}

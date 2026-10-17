package com.codefrak.engine.smith

import java.time.OffsetDateTime

/** One row of `engine.smith_session`: a run whose stdout the engine stores. */
data class SmithSession(
    val runId: String, val repo: String, val issue: Long?, val mode: String, val model: String,
    val startedAt: OffsetDateTime, val endedAt: OffsetDateTime?,
)

/**
 * A live Smith's session in the engine's own database (engine/README.md, "Summarize: a live run summarized"):
 * the run's row and its stdout lines, stored as the run goes on, not only after `collect result`. The store is
 * `StoredSmithSessions` (engine-smith); every module above programs against this.
 */
interface SmithSessions {
    /** A run is live: record it and start its writer. A run resumed after a restart keeps its row and numbers on. */
    fun open(labels: RunLabels)

    /** One stdout line of a live run, in the order read, uncut for its tally. Never blocks or throws; a run not [open] drops it. */
    fun offer(runId: String, line: String)

    /** The run is over: its writer stores what it holds, then sets `ended_at`. Returns at once; idempotent. Its tally stays until [forget]. */
    fun close(runId: String)

    /** This instance stops following the run (the lease moved): its writer stores what it holds and leaves `ended_at` unset, and its tally goes. */
    fun detach(runId: String)

    /** A running run's turns and tokens so far ([LiveFigures]); null for a run with no session. */
    fun live(runId: String): LiveFigures?

    /** `collect result` set the run's own cost and turns: its tally is no longer read. */
    fun forget(runId: String)

    fun session(runId: String): SmithSession?

    /**
     * The newest run on `repo`#`issue` whose session is open (`ended_at` unset): read from the database, so every
     * replica answers the same, not only the one that follows the run (#1716). With `mode`, only a run of that mode
     * (`review` for the review prediction, #1746).
     */
    fun running(repo: String, issue: Long, mode: String? = null): SmithSession?

    /** Every stored line of the run, oldest first. */
    fun lines(runId: String): List<String>

    /** A digest of what is stored for `runId`, at most [maxChars] long. */
    fun digest(runId: String, maxChars: Int = DIGEST_CHARS): String

    /** The last [turns] steps of the digest, whole, for what a run is doing now (the summarize endpoint's "most recent turns"). */
    fun recent(runId: String, turns: Int = RECENT_TURNS): String

    companion object {
        /** What a summary reads of a session at most. */
        const val DIGEST_CHARS = 24_000
        /** The "most recent turns" a summary shows whole. */
        const val RECENT_TURNS = 3
    }
}

package com.codefrak.engine.smith

import java.time.OffsetDateTime

/**
 * One row of `engine.smith_run_stats` (engine/README.md, "Smith runs"): a run from `start smith` on. `ended`, `exitCode`
 * and what `collect result` records (`turns`, `durationMs`, `result`, `outcome`, `question`, `peakBytes`, `oomKilled`,
 * `evicted`) are null until the run is collected; a reader of collected runs filters on `ended`.
 */
data class SmithRunStat(
    val runId: String, val rootFlowKind: String?, val repo: String, val issue: Long?,
    val tier: Int?, val model: String?, val effort: String?, val account: String?,
    val started: OffsetDateTime?, val ended: OffsetDateTime?, val exitCode: Long?,
    val peakBytes: Long?, val oomKilled: Boolean?,
    /** The run's role, the flow's `mode` (work, review, …); null on rows from before V17 with no session to take it from. */
    val mode: String? = null,
    /** How many paths and globs the run's ticket's Allowed scope grants ([AllowedScope.grantCount]); null with no ticket, and on rows from before V34. */
    val scopePaths: Int? = null,
    /** Whether the cluster evicted or preempted the run's pod ([SmithSandbox.evicted]); null where unknown and on rows from before V56. */
    val evicted: Boolean? = null,
    /** The root flow the run belongs to and its `reusable-smith-run` wrapper (V67); null on rows from before, until backfilled. */
    val root: String? = null, val wrapper: String? = null,
    /**
     * The run's outcome: its result.json's `outcome`, else its `result` (`success`, `failed`, `no-result`); `rejected` once
     * `read result` turned a work result down. The question its result.json asked. The closing `result` event's turns and duration.
     */
    val outcome: String? = null, val question: String? = null, val turns: Long? = null, val durationMs: Long? = null,
    val result: String? = null,
    /** A review or security run's number among its root's reviews, once `read result` read it; null before and for other modes. */
    val reviewNo: Int? = null,
    /** The run's cost, from its `engine.claude_usage` row (where cost lives); null until collected or when the result had none. */
    val cost: Double? = null,
)

/** One successful run's duration, as `IssueBoard`'s stage estimates read them. */
data class RunDuration(val mode: String, val rootFlowKind: String?, val tier: Int?, val millis: Long, val scopePaths: Int? = null)

/** What `collect result` adds to a run's row ([SmithRunStats.finish]). */
data class SmithRunEnd(
    val runId: String, val ended: OffsetDateTime, val exitCode: Long, val peakBytes: Long?, val oomKilled: Boolean?, val evicted: Boolean?,
    val turns: Long?, val durationMs: Long?, val result: String?, val outcome: String?, val question: String?,
    val scopePaths: Int?, val account: String?, val rootFlowKind: String?,
)

/**
 * `engine.smith_run_stats`: a row per run from `start smith` on, finished by `collect result` and stamped by `read result`
 * (engine/README.md, "Smith runs"). The store is `StoredSmithRunStats` (engine-smith); every module above programs against this.
 */
interface SmithRunStats {
    /** `start smith`'s insert: the run's root, wrapper, mode, model, effort, tier, account and start; a retried start rewrites the same row. */
    fun start(s: SmithRunStat)
    /** A whole row at once (tests, and a row written outside the delegates); the same upsert as [start]. */
    fun record(s: SmithRunStat)
    /** The backfill's write ([com.codefrak.engine.camunda.TimelineBackfill]): the row as history shows it, or the projection columns of one already there. */
    fun backfill(s: SmithRunStat)
    /** `collect result`'s finish; a row `start smith` never wrote (a run from before V67 still running at the deploy) is inserted. */
    fun finish(e: SmithRunEnd, fallback: () -> SmithRunStat)
    /** `read result`'s stamp on a review or security run: its number among its root's reviews. */
    fun reviewed(runId: String, reviewNo: Int)
    /** `read result`'s stamp on a work result it turned down: the run ended `rejected`, whatever its result said. */
    fun rejected(runId: String)
    /** `read result`'s stamp on a review run's findings, one `engine.review_finding` row each, replacing any earlier stamp of the run. */
    fun findings(runId: String, reviewNo: Int, findings: List<Finding>)
    /** A review run's stamped findings, in order; empty until `read result` read it. */
    fun findings(runId: String): List<Finding>
    /** The tier of the newest run under the root flow [root] that has one (`start smith` wrote it); null when no run has started yet. */
    fun latestTier(root: String): Int?
    /** The id of the newest run under the root flow [root]; null when none has started. */
    fun latestRunId(root: String): String?
    /** The next review number under [root]: 1 + the highest stamped so far. */
    fun nextReviewNo(root: String): Int
    /** The newest run of the `reusable-smith-run` instance [wrapper] (a retry in place runs the call again); null when it has none yet. */
    fun latestOfWrapper(wrapper: String): SmithRunStat?
    /** The run's row, collected or not. */
    fun get(runId: String): SmithRunStat?
    /** Every collected run on `repo`#`issue` (read_ticket_runs), oldest first; a run still running has no row here. */
    fun forIssue(repo: String, issue: Long): List<SmithRunStat>
    /** Every run under the root flow [root], running ones too, oldest first: the ticket timeline's steps. */
    fun forRoot(root: String): List<SmithRunStat>
    /** The tickets with a run and no `engine.timeline_backfill` row, for the backfill's sweep. */
    fun ticketsWithoutBackfill(): List<Pair<String, Long>>
    /** Every successful collected run (exit 0) with a role and a start: the sample the stage estimates take medians of. */
    fun durations(): List<RunDuration>
}

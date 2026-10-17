package com.codefrak.engine.smith

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
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
 * (engine/README.md, "Smith runs"). Each write is in its own transaction, so a failed one never marks the engine's
 * surrounding one rollback-only on Postgres.
 */
@Repository
class SmithRunStats(private val jdbc: JdbcClient) {
    /** `start smith`'s insert: the run's root, wrapper, mode, model, effort, tier, account and start; a retried start rewrites the same row. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun start(s: SmithRunStat) = upsert(s)

    /** A whole row at once (tests, and a row written outside the delegates); the same upsert as [start]. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(s: SmithRunStat) = upsert(s)

    /** The backfill's write ([com.codefrak.engine.camunda.TimelineBackfill]): the row as history shows it, or the projection columns of one already there. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun backfill(s: SmithRunStat) = upsert(s)

    private fun upsert(s: SmithRunStat) {
        val updated = jdbc.sql("""
            UPDATE engine.smith_run_stats SET root = COALESCE(:root, root), wrapper = COALESCE(:wrapper, wrapper), root_flow_kind = COALESCE(:kind, root_flow_kind),
                repo = :repo, issue = COALESCE(:issue, issue), tier = COALESCE(:tier, tier), model = COALESCE(:model, model), effort = COALESCE(:effort, effort),
                account = COALESCE(:account, account), started = COALESCE(started, :started), mode = COALESCE(:mode, mode),
                ended = COALESCE(ended, :ended), exit_code = COALESCE(exit_code, :exit), peak_bytes = COALESCE(peak_bytes, :peak),
                turns = COALESCE(turns, :turns), duration_ms = COALESCE(duration_ms, :duration), result = COALESCE(result, :result),
                outcome = COALESCE(:outcome, outcome), question = COALESCE(question, :question), review_no = COALESCE(:review, review_no),
                scope_paths = COALESCE(scope_paths, :scope)
            WHERE run_id = :run""".trimIndent())
            .params(params(s)).update()
        if (updated == 0) jdbc.sql("""
            INSERT INTO engine.smith_run_stats (run_id, root_flow_kind, repo, issue, tier, model, effort, account, started, ended, exit_code, peak_bytes,
                oom_killed, mode, scope_paths, evicted, root, wrapper, outcome, question, turns, duration_ms, result, review_no)
            VALUES (:run, :kind, :repo, :issue, :tier, :model, :effort, :account, :started, :ended, :exit, :peak, :oom, :mode, :scope, :evicted,
                :root, :wrapper, :outcome, :question, :turns, :duration, :result, :review)""".trimIndent())
            .params(params(s)).update()
    }

    private fun params(s: SmithRunStat): Map<String, Any?> = mapOf(
        "run" to s.runId, "kind" to s.rootFlowKind, "repo" to s.repo, "issue" to s.issue, "tier" to s.tier, "model" to s.model,
        "effort" to s.effort, "account" to s.account, "started" to s.started, "ended" to s.ended, "exit" to s.exitCode, "peak" to s.peakBytes,
        "oom" to s.oomKilled, "mode" to s.mode, "scope" to s.scopePaths, "evicted" to s.evicted, "root" to s.root, "wrapper" to s.wrapper,
        "outcome" to s.outcome, "question" to s.question, "turns" to s.turns, "duration" to s.durationMs, "result" to s.result, "review" to s.reviewNo,
    )

    /** `collect result`'s finish; a row `start smith` never wrote (a run from before V67 still running at the deploy) is inserted. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun finish(e: SmithRunEnd, fallback: () -> SmithRunStat) {
        val updated = jdbc.sql("""
            UPDATE engine.smith_run_stats SET ended = :ended, exit_code = :exit, peak_bytes = :peak, oom_killed = :oom, evicted = :evicted,
                turns = :turns, duration_ms = :duration, result = :result, outcome = :outcome, question = :question,
                scope_paths = :scope, account = COALESCE(:account, account), root_flow_kind = COALESCE(:kind, root_flow_kind)
            WHERE run_id = :run""".trimIndent())
            .param("ended", e.ended).param("exit", e.exitCode).param("peak", e.peakBytes).param("oom", e.oomKilled).param("evicted", e.evicted)
            .param("turns", e.turns).param("duration", e.durationMs).param("result", e.result).param("outcome", e.outcome)
            .param("question", e.question).param("scope", e.scopePaths).param("account", e.account).param("kind", e.rootFlowKind)
            .param("run", e.runId).update()
        if (updated == 0) upsert(fallback().copy(ended = e.ended, exitCode = e.exitCode, peakBytes = e.peakBytes, oomKilled = e.oomKilled,
            evicted = e.evicted, turns = e.turns, durationMs = e.durationMs, result = e.result, outcome = e.outcome, question = e.question,
            scopePaths = e.scopePaths))
    }

    /** `read result`'s stamp on a review or security run: its number among its root's reviews. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun reviewed(runId: String, reviewNo: Int) {
        jdbc.sql("UPDATE engine.smith_run_stats SET review_no = :n WHERE run_id = :run").param("n", reviewNo).param("run", runId).update()
    }

    /** `read result`'s stamp on a work result it turned down: the run ended `rejected`, whatever its result said. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun rejected(runId: String) {
        jdbc.sql("UPDATE engine.smith_run_stats SET outcome = :o WHERE run_id = :run").param("o", ReadResultDelegate.REJECTED).param("run", runId).update()
    }

    /** `read result`'s stamp on a review run's findings, one `engine.review_finding` row each, replacing any earlier stamp of the run. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun findings(runId: String, reviewNo: Int, findings: List<Finding>) {
        jdbc.sql("DELETE FROM engine.review_finding WHERE run_id = :run").param("run", runId).update()
        findings.forEachIndexed { i, f ->
            jdbc.sql("INSERT INTO engine.review_finding (run_id, position, review_no, finding) VALUES (:run, :pos, :n, :f)")
                .param("run", runId).param("pos", i).param("n", reviewNo).param("f", com.codefrak.engine.engineJson.writeValueAsString(f)).update()
        }
    }

    /** A review run's stamped findings, in order; empty until `read result` read it. */
    fun findings(runId: String): List<Finding> =
        jdbc.sql("SELECT finding FROM engine.review_finding WHERE run_id = :run ORDER BY position").param("run", runId)
            .query { rs, _ -> com.codefrak.engine.engineJson.readValue(rs.getString(1), Finding::class.java) }.list()

    /** The tier of the newest run under the root flow [root] that has one (`start smith` wrote it); null when no run has started yet. */
    fun latestTier(root: String): Int? =
        jdbc.sql("SELECT tier FROM engine.smith_run_stats WHERE root = :root AND tier IS NOT NULL ORDER BY started DESC LIMIT 1").param("root", root)
            .query(Int::class.javaObjectType).optional().orElse(null)

    /** The id of the newest run under the root flow [root]; null when none has started. */
    fun latestRunId(root: String): String? =
        jdbc.sql("SELECT run_id FROM engine.smith_run_stats WHERE root = :root ORDER BY started DESC LIMIT 1").param("root", root)
            .query(String::class.java).optional().orElse(null)

    /** The next review number under [root]: 1 + the highest stamped so far. */
    fun nextReviewNo(root: String): Int =
        (jdbc.sql("SELECT MAX(review_no) FROM engine.smith_run_stats WHERE root = :root").param("root", root)
            .query(Int::class.javaObjectType).optional().orElse(null) ?: 0) + 1

    /** The newest run of the `reusable-smith-run` instance [wrapper] (a retry in place runs the call again); null when it has none yet. */
    fun latestOfWrapper(wrapper: String): SmithRunStat? =
        jdbc.sql("SELECT $columns FROM engine.smith_run_stats s WHERE wrapper = :w ORDER BY started DESC").param("w", wrapper)
            .query { rs, _ -> row(rs) }.list().firstOrNull()

    private val columns = "run_id, root_flow_kind, repo, issue, tier, model, effort, account, started, ended, exit_code, peak_bytes, oom_killed, mode, " +
        "scope_paths, evicted, root, wrapper, outcome, question, turns, duration_ms, result, review_no, " +
        "(SELECT MAX(u.cost_usd) FROM engine.claude_usage u WHERE u.run_id = s.run_id AND u.caller = 'smith-run') AS cost"

    private fun row(rs: java.sql.ResultSet) = SmithRunStat(rs.getString(1), rs.getString(2), rs.getString(3), rs.getObject(4, Long::class.javaObjectType),
        rs.getObject(5, Int::class.javaObjectType), rs.getString(6), rs.getString(7), rs.getString(8),
        rs.getObject(9, OffsetDateTime::class.java), rs.getObject(10, OffsetDateTime::class.java), rs.getObject(11, Long::class.javaObjectType),
        rs.getObject(12, Long::class.javaObjectType), rs.getObject(13, Boolean::class.javaObjectType), rs.getString(14),
        rs.getObject(15, Int::class.javaObjectType), rs.getObject(16, Boolean::class.javaObjectType),
        rs.getString(17), rs.getString(18), rs.getString(19), rs.getString(20), rs.getObject(21, Long::class.javaObjectType),
        rs.getObject(22, Long::class.javaObjectType), rs.getString(23), rs.getObject(24, Int::class.javaObjectType),
        rs.getObject(25, Double::class.javaObjectType))

    /** The run's row, collected or not. */
    fun get(runId: String): SmithRunStat? =
        jdbc.sql("SELECT $columns FROM engine.smith_run_stats s WHERE run_id = :run")
            .param("run", runId).query { rs, _ -> row(rs) }.optional().orElse(null)

    /** Every collected run on `repo`#`issue` (read_ticket_runs), oldest first; a run still running has no row here. */
    fun forIssue(repo: String, issue: Long): List<SmithRunStat> =
        jdbc.sql("SELECT $columns FROM engine.smith_run_stats s WHERE repo = :repo AND issue = :issue AND ended IS NOT NULL ORDER BY started")
            .param("repo", repo).param("issue", issue).query { rs, _ -> row(rs) }.list()

    /** Every run under the root flow [root], running ones too, oldest first: the ticket timeline's steps. */
    fun forRoot(root: String): List<SmithRunStat> =
        jdbc.sql("SELECT $columns FROM engine.smith_run_stats s WHERE root = :root ORDER BY started")
            .param("root", root).query { rs, _ -> row(rs) }.list()

    /** The tickets with a run and no `engine.timeline_backfill` row, for the backfill's sweep. */
    fun ticketsWithoutBackfill(): List<Pair<String, Long>> =
        jdbc.sql("""SELECT DISTINCT s.repo, s.issue FROM engine.smith_run_stats s WHERE s.issue IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM engine.timeline_backfill b WHERE b.repo = s.repo AND b.issue = s.issue)""")
            .query { rs, _ -> rs.getString(1) to rs.getLong(2) }.list()

    /** Every successful collected run (exit 0) with a role and a start: the sample the stage estimates take medians of. */
    fun durations(): List<RunDuration> =
        jdbc.sql("SELECT mode, root_flow_kind, tier, started, ended, scope_paths FROM engine.smith_run_stats WHERE exit_code = 0 AND ended IS NOT NULL AND mode IS NOT NULL AND started IS NOT NULL")
            .query { rs, _ ->
                val started = rs.getObject(4, OffsetDateTime::class.java); val ended = rs.getObject(5, OffsetDateTime::class.java)
                RunDuration(rs.getString(1), rs.getString(2), rs.getObject(3, Int::class.javaObjectType),
                    java.time.Duration.between(started, ended).toMillis(), rs.getObject(6, Int::class.javaObjectType))
            }.list()
}

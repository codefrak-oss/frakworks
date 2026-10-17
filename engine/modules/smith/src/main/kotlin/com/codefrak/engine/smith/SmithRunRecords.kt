package com.codefrak.engine.smith

import com.codefrak.engine.accounts.AccountUsages
import com.codefrak.engine.accounts.ClaudeTokens
import com.codefrak.engine.accounts.ClaudeUsage
import com.codefrak.engine.accounts.ClaudeUsageLedger
import com.codefrak.engine.engineJson
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * What [CollectSmithDelegate] records on the side of the collect: the usage
 * meter, the live-tally comparison, the closing result's cost/turns/duration,
 * the `engine.smith_run_stats` row, the run's `engine.claude_usage` row, and
 * the account-limit recorders for the Fable and session/spend limits.
 */
@Component
class SmithRunRecords(
    private val sandbox: SmithSandbox,
    private val usages: AccountUsages,
    private val stats: SmithRunStats,
    private val telemetry: SmithTelemetry,
    private val ledger: ClaudeUsageLedger,
) {
    private val log = LoggerFactory.getLogger(SmithRunRecords::class.java)

    /** What [recordStats] wrote of the run's length and memory; null where it could not be read. */
    data class Recorded(val seconds: Long?, val peakBytes: Long?)

    /**
     * Once per run, the latest reply's reading, filed under the account the
     * run billed to. A missing or unreadable meter never fails the collect:
     * the run's result matters more than a gauge.
     */
    fun recordMeter(runId: String, account: String?, bytes: ByteArray?): SandboxMeter? {
        if (bytes == null) { log.debug("run {}: no usage meter (no Anthropic call)", runId); return null }
        if (account == null) { log.debug("run {}: usage meter but no account; reading dropped", runId); return null }
        val meter = runCatching { SandboxMeter.parse(bytes) }
            .onFailure { log.warn("run {}: unreadable usage meter: {}", runId, it.message) }.getOrNull() ?: return null
        val reading = meter.reading ?: run { log.debug("run {}: usage meter has no rate-limit headers", runId); return meter }
        usages.record(account, reading, meter.httpStatus, meter.observedAt ?: OffsetDateTime.now())
        if (reading.status == "rejected" || meter.httpStatus == 429)
            log.warn("anthropic refused a request on account {}: status={} 5h={} 7d={}", account, meter.httpStatus, reading.fiveHourStatus, reading.sevenDayStatus)
        return meter
    }

    /**
     * What the Smith's closing stream-json `result` event says the run cost (`closingResult`): `total_cost_usd`,
     * `num_turns` and `duration_ms`. Each is null when the event, or its field, is missing or garbled. Held by the
     * collect while it runs and written to the run's rows ([recordStats], [recordUsage]); never a process variable.
     */
    data class ResultStats(val cost: Double?, val turns: Long?, val durationMs: Long?) {
        companion object { val NONE = ResultStats(null, null, null) }
    }

    /**
     * Cost, turns and duration from the closing `result` event (engine/README.md,
     * "Smith runs"); [ResultStats.NONE] when unread. Like the meter, nothing here fails the collect.
     */
    fun recordResultStats(execution: DelegateExecution, text: String): ResultStats {
        val event = runCatching { closingResult(text) }
            .onFailure { log.warn("run {}: unreadable result event: {}", execution.processInstanceId, it.message) }.getOrNull()
            ?: return ResultStats.NONE
        return ResultStats((event["total_cost_usd"] as? Number)?.toDouble(), (event["num_turns"] as? Number)?.toLong(),
            (event["duration_ms"] as? Number)?.toLong())
    }

    /** The run's `engine.smith_run_stats` row as `start smith` wrote it (its model, effort, tier); null when that insert failed, or the row is unreadable. */
    fun startedRow(runId: String): SmithRunStat? = runCatching { stats.get(runId) }
        .onFailure { log.warn("run {}: stats row not read: {}", runId, it.message) }.getOrNull()

    /**
     * The timeline's live turns and tokens (SmithSessions.live) end here: the run's own `cost`/`turns`
     * replace them. Logged next to `num_turns` so the live turn count can be checked against Claude's.
     * Answers the tally's tokens, for the run's ledger row; with no live tally, the tokens of the
     * stream-json lines in `text` ([LiveTally.of]); null when neither has any.
     */
    fun compareLiveTally(execution: DelegateExecution, text: String = "", result: ResultStats = ResultStats.NONE): LiveTokens? {
        val runId = execution.processInstanceId
        val live = runCatching {
            telemetry.collected(runId)?.also { live ->
                log.info("run {}: live tally counted {} turns, tokens {}; the result says num_turns={} total_cost_usd={}", runId,
                    live.countedTurns, live.tokens, result.turns, result.cost)
            }
        }.onFailure { log.warn("run {}: live tally not compared: {}", runId, it.message) }.getOrNull()
        return live?.tokens ?: runCatching { LiveTally.of(text.lineSequence().asIterable()).figures().tokens }.getOrNull()
    }

    /**
     * The sandbox's peak memory and OOM flag, into `peakMemoryBytes`, and the run's `engine.smith_run_stats` row finished
     * (engine/README.md, "Smith runs"): ended, exit, turns, duration, result, outcome (the result.json's `outcome`, else
     * the `result`) and the question the result.json asked. [result] is the closing event's figures and [run] the row
     * `start smith` wrote (the fallback row for one it did not write takes the flow's `model`, `effort` and `tier`).
     * Like the meter, nothing here fails the collect.
     */
    fun recordStats(execution: DelegateExecution, handle: String, exit: Long, account: String?, evicted: Boolean?, tokens: LiveTokens? = null,
                    result: ResultStats = ResultStats.NONE, run: SmithRunStat? = null): Recorded {
        val runId = execution.processInstanceId
        val peak = runCatching { sandbox.output(handle, MEMORY_PEAK_PATH)?.toString(Charsets.US_ASCII)?.trim()?.toLong() }
            .onFailure { log.warn("run {}: unreadable memory.peak: {}", runId, it.message) }.getOrNull()
        execution.setVariable(Vars.PEAK_MEMORY, peak)
        val oom = runCatching { sandbox.oomKilled(handle) }
            .onFailure { log.warn("run {}: OOM flag unavailable: {}", runId, it.message) }.getOrNull()
        val ended = OffsetDateTime.now()
        val started = runCatching {
            val history = execution.processEngineServices.historyService
            val self = history.createHistoricProcessInstanceQuery().processInstanceId(runId).singleResult()
            val root = self?.rootProcessInstanceId?.takeIf { it != runId }
                ?.let { history.createHistoricProcessInstanceQuery().processInstanceId(it).singleResult() } ?: self
            self?.startTime?.toInstant()?.atOffset(ended.offset) to root?.processDefinitionKey
        }.onFailure { log.warn("run {}: no process history for stats: {}", runId, it.message) }.getOrNull()
        val output = execution.getVariable(Vars.OUTPUT) as? ByteArray
        val resultJson = output?.let { runCatching { engineJson.readTree(it) }.getOrNull() }
        val outcome = execution.getVariable(Vars.RESULT) as? String
        runCatching {
            stats.finish(SmithRunEnd(
                runId = runId, ended = ended, exitCode = exit, peakBytes = peak, oomKilled = oom, evicted = evicted,
                turns = result.turns, durationMs = result.durationMs,
                result = outcome, outcome = resultJson?.get("outcome")?.takeIf { it.isString }?.asString() ?: outcome,
                question = resultJson?.get("question")?.takeIf { it.isString }?.asString()?.take(4000),
                scopePaths = scopePaths(execution), account = account, rootFlowKind = started?.second,
            )) {
                SmithRunStat(
                    runId = runId, rootFlowKind = started?.second, repo = execution.repo(), issue = execution.issueOrNull(),
                    tier = (execution.getVariable(Vars.TIER) as? Number)?.toInt(),
                    model = execution.getVariable(Vars.MODEL) as? String, effort = execution.getVariable(Vars.EFFORT) as? String,
                    account = account, started = started?.first, ended = ended, exitCode = exit, peakBytes = peak, oomKilled = oom,
                    mode = execution.mode(), scopePaths = scopePaths(execution), evicted = evicted, root = rootOf(execution), wrapper = wrapperOf(execution),
                )
            }
        }.onFailure { log.warn("run {}: stats row not finished: {}", runId, it.message) }
        recordUsage(execution, started?.second, account, started?.first, ended, tokens, result, run?.model)
        val seconds = started?.first?.let { java.time.Duration.between(it, ended).seconds }
        log.info("run {}: peak {} MiB, {} s, exit {}{}", runId, peak?.let { it / (1024 * 1024) } ?: "?", seconds ?: "?", exit,
            if (evicted == true) ", evicted by the cluster" else "")
        return Recorded(seconds, peak)
    }

    /**
     * The run's row in the consumption ledger (engine/README.md, "Consumption ledger"): cost and
     * duration from the closing `result` (`durationMs`, else the run's start to now), tokens from
     * the tally, the model from the run's row. A ticket round is caller `smith-run` with its mode's timeline role
     * ([ClaudeUsageLedger.roleOf]); a doctor's, refactor scan's or job's run is filed under that caller ([ClaudeUsageLedger.callerOf]).
     */
    fun recordUsage(execution: DelegateExecution, rootFlowKind: String?, account: String?, started: OffsetDateTime?, ended: OffsetDateTime, tokens: LiveTokens?,
                    result: ResultStats = ResultStats.NONE, model: String? = null) {
        runCatching {
            ledger.record(ClaudeUsage(
                caller = ClaudeUsageLedger.callerOf(rootFlowKind), role = ClaudeUsageLedger.roleOf(execution.mode()),
                runId = execution.processInstanceId, repo = execution.getVariable(Vars.REPO) as? String, issue = execution.issueOrNull(),
                account = account, model = model, started = started, ended = ended,
                durationMs = result.durationMs ?: started?.let { java.time.Duration.between(it, ended).toMillis() },
                costUsd = result.cost,
                tokens = tokens?.let { ClaudeTokens(it.input, it.output, it.cacheRead, it.cacheWrite) },
            ))
        }.onFailure { log.warn("run {}: usage row not written: {}", execution.processInstanceId, it.message) }
    }

    /** The size of the run's ticket's Allowed scope ([AllowedScope.grantCount]); null when the run has no approved ticket, or it is unreadable. */
    fun scopePaths(execution: DelegateExecution): Int? = runCatching {
        (execution.getVariable(Vars.TICKET) as? ByteArray)?.let { AllowedScope.grantCount(Brief.ticket(it).body) }
    }.getOrNull()

    /**
     * After the meter, so the account's row has this run's 7-day reset: marks
     * the account at its Fable limit when the run was refused for it
     * ([fableLimitHit]). Never recomputed while it stands.
     */
    fun recordFableLimit(runId: String, account: String?, exit: Long, logText: String, output: ByteArray?, meter: SandboxMeter?, model: String?) {
        if (account == null) return
        val hit = fableLimitHit(logText, if (exit != 0L) output else null, meter, model) ?: return
        if (usages.markFableReached(account, OffsetDateTime.now()))
            log.warn("run {}: account {} reached its Fable limit ({}), until {}", runId, account, hit,
                usages.get(account)?.fableLimitReset)
    }

    /**
     * After the meter, like [recordFableLimit]: when the run was refused for
     * the account's session or spend limit ([sessionLimitHit]), records the
     * account rejected until the reset the text names, else until the meter's
     * (its 5-hour reset when ahead, else its 7-day one). With neither, nothing is
     * recorded and a warning says so: no window is invented. The limit it found, or null when the run hit none.
     */
    fun recordSessionLimit(runId: String, account: String?, exit: Long, logText: String, output: ByteArray?): SmithReruns.SessionLimit? {
        if (account == null) return null
        val text = sessionLimitHit(logText, if (exit != 0L) output else null) ?: return null
        val now = OffsetDateTime.now()
        val usage = usages.get(account)
        val reset = parseLimitReset(text, now)
            ?: listOfNotNull(usage?.fiveHourReset, usage?.sevenDayReset).firstOrNull { it.isAfter(now) }
        if (reset == null) {
            log.warn("run {}: account {} hit its session or spend limit, but neither the result nor the meter gives a reset; not recorded",
                runId, account)
            return SmithReruns.SessionLimit(account, null)
        }
        if (usages.markLimitRejected(account, reset, now))
            log.warn("run {}: account {} hit its session or spend limit, rejected until {}", runId, account, reset)
        return SmithReruns.SessionLimit(account, usages.get(account)?.rejectedUntil() ?: reset)
    }
}

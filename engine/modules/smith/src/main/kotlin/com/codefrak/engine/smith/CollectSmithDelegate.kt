package com.codefrak.engine.smith

import com.codefrak.engine.engineJson
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.operaton.bpm.engine.variable.Variables
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.readValue

/**
 * "collect result": the full log as bytes, its tail as text, a verdict, the
 * Smith's structured output if it wrote one, and its usage-meter reading,
 * recorded under the run's account (engine/README.md, "The usage meter").
 * The exit code is the message's (`Vars.EXIT_CODE`, set by `SmithExitNotifier.deliver`).
 * The container is removed by `removeSmith` in the NEXT transaction, so a
 * failure to persist here can be retried against a container that still exists.
 * What it records on the side lives in [SmithRunRecords].
 */
@Component("collectSmith")
class CollectSmithDelegate(
    private val sandbox: SmithSandbox,
    private val telemetry: SmithTelemetry,
    private val retroNotes: RetroNotes,
    private val events: EngineEvents,
    private val reruns: SmithReruns,
    private val records: SmithRunRecords,
) : JavaDelegate {
    private val log = LoggerFactory.getLogger(CollectSmithDelegate::class.java)

    companion object {
        /**
         * The bundle a committing run keeps as the last real one: the current bundle, or, when the run before it
         * committed nothing (BUNDLE null), the one already kept. A no-commit run run again (a rules rejection, or an
         * incident retry restarting the call activity) must not overwrite the earlier commit's bundle with null (#1469).
         */
        fun carriedBundle(bundle: ByteArray?, previous: ByteArray?): ByteArray? = bundle ?: previous

        /**
         * The bundle a run leaves in [Vars.BUNDLE]: the one it wrote, or, for a propose run that wrote none, the draft
         * it was given. Between proposal rounds the draft lives only in this variable (nothing is pushed before review
         * passes), so a propose run that died before bundling (evicted, killed, timed out) must not wipe it: the rerun
         * or the next redraft would check out a fresh branch and rewrite the proposal from scratch (#2248, friction g6).
         */
        fun collectedBundle(mode: Any?, written: ByteArray?, current: ByteArray?): ByteArray? =
            written ?: current.takeIf { mode == "propose" }
    }
    override fun execute(execution: DelegateExecution) {
        // The backstop for smith_run_active: its sandbox's stream ending usually ended it already.
        runCatching { telemetry.ended(execution.processInstanceId) }
            .onFailure { log.warn("run {}: gauge not ended: {}", execution.processInstanceId, it.message) }
        val handle = execution.getVariable(Vars.HANDLE) as String
        val exit = (execution.getVariable(Vars.EXIT_CODE) as Number).toLong()
        val text = runCatching { sandbox.logs(handle, maxLines = 5000) }.getOrElse { "(log unavailable: ${it.message})" }
        execution.setVariable(Vars.LOG, Variables.byteArrayValue(text.toByteArray(Charsets.UTF_8)))
        execution.setVariable(Vars.LOG_TAIL, text.takeLast(Vars.TAIL_CHARS))
        execution.setVariable(Vars.OVERFLOWED, overflowHit(text))
        val stats = records.recordResultStats(execution, text)
        val tokens = records.compareLiveTally(execution, text, stats)
        // The run's row, from `start smith` (engine/README.md, "Smith runs"): the model, effort and tier it was started with.
        val run = records.startedRow(execution.processInstanceId)
        val model = run?.model ?: execution.getVariable(Vars.MODEL) as? String
        // Set even when absent: a variable left over from an earlier round must not pass for this run's.
        // Only a work or propose run replaces the bundle; the review after it must not clear it.
        val commits = execution.getVariable(Vars.MODE) in COMMITTING_MODES
        val files = listOf(Vars.OUTPUT to OUTPUT_PATH, Vars.METER to METER_PATH) +
            if (commits) listOf(Vars.BUNDLE to BUNDLE_PATH) else emptyList()
        if (commits) execution.setVariable(Vars.MERGE, null) // its bundle replaces the merge's
        // A round that commits nothing because the check's failure is not its own keeps the last bundle (ReadResultDelegate).
        if (commits) execution.setVariable(Vars.PREVIOUS_BUNDLE, carriedBundle(
            execution.getVariable(Vars.BUNDLE) as? ByteArray, execution.getVariable(Vars.PREVIOUS_BUNDLE) as? ByteArray,
        )?.let { Variables.byteArrayValue(it) })
        val collected = files.associate { (name, path) ->
            val read = runCatching { sandbox.output(handle, path) }.getOrNull()
            val bytes = if (name == Vars.BUNDLE)
                collectedBundle(execution.getVariable(Vars.MODE), read, execution.getVariable(Vars.BUNDLE) as? ByteArray)
            else read
            execution.setVariable(name, bytes?.let { Variables.byteArrayValue(it) })
            name to bytes
        }
        if (execution.getVariable(Vars.MODE) == "reproduce") collectRepro(execution, handle, collected[Vars.OUTPUT])
        // Exit 0 without result.json is a failed run too, so a retry of its incident runs the Smith again.
        val result = when {
            exit != 0L -> "failed"
            collected[Vars.OUTPUT] == null -> NO_RESULT
            else -> "success"
        }
        execution.setVariable(Vars.RESULT, result)
        val account = execution.getVariable(Vars.BILLED_ACCOUNT) as? String
        val meter = records.recordMeter(execution.processInstanceId, account, collected[Vars.METER])
        runCatching { records.recordFableLimit(execution.processInstanceId, account, exit, text, collected[Vars.OUTPUT], meter, model) }
            .onFailure { log.warn("run {}: Fable limit check failed: {}", execution.processInstanceId, it.message) }
        val limit = runCatching { records.recordSessionLimit(execution.processInstanceId, account, exit, text, collected[Vars.OUTPUT]) }
            .onFailure { log.warn("run {}: session limit check failed: {}", execution.processInstanceId, it.message) }
            .getOrNull()
        val evicted = runCatching { sandbox.evicted(handle) }
            .onFailure { log.warn("run {}: eviction unknown: {}", execution.processInstanceId, it.message) }.getOrNull()
        runCatching { reruns.decide(execution, account, result, limit, exit, evicted == true, model) }
            .onFailure {
                log.warn("run {}: rerun not decided, the run ends as failed: {}", execution.processInstanceId, it.message)
                execution.setVariable(Vars.RERUN, false)
            }
        val recorded = records.recordStats(execution, handle, exit, account, evicted, tokens, stats, run)
        recordRetro(execution, handle)
        log.info("run {}: sandbox {} exited {} -> {}", execution.processInstanceId, handle.take(12), exit, result)
        // `session` `ended`, once per run (the exit notifier makes this collect run once), once this job commits.
        val runId = execution.processInstanceId
        val mode = execution.mode()
        val labels = telemetry.labels(runId, execution.issueOrNull(), execution.repo(),
            model ?: "", mode, account, execution.getVariable(Vars.JOB_ID) as? String)
        events.publishAfterCommit(EngineEvent.Session(labels.repo, labels.issue, runId, rootOf(execution), mode, model,
            run?.tier, account, "ended", labels.filename, labels.host,
            exitCode = exit, seconds = recorded.seconds, peakMiB = recorded.peakBytes?.let { it / (1024 * 1024) }, jobId = labels.jobId))
    }

    /**
     * A reproduce run's [REPRO_DIR] (engine/README.md, "Bug"): kept when the run's outcome goes on to a fix and the
     * directory holds a file; past [REPRO_MAX_BYTES] dropped, saying why. An earlier run's are cleared, so they never
     * pass for this run's. Absent, empty or unreadable is not an error and leaves no variable: the run goes on without the files.
     */
    private fun collectRepro(execution: DelegateExecution, handle: String, output: ByteArray?) {
        listOf(Vars.REPRO_FILES, Vars.REPRO_DROPPED).forEach { if (execution.getVariable(it) != null) execution.setVariable(it, null) }
        val outcome = output?.let { runCatching { ReproduceResult.parse(it).outcome }.getOrNull() }
        if (outcome !in ReproduceResult.FIXABLE) return
        val tar = try {
            sandbox.outputDir(handle, REPRO_DIR, REPRO_MAX_BYTES)
        } catch (e: OutputTooLarge) {
            execution.setVariable(Vars.REPRO_DROPPED, "$REPRO_DIR was larger than ${REPRO_MAX_BYTES / (1024 * 1024)} MiB, " +
                "so its files were not carried to /work/in/$REPRO_INPUT; rebuild what the repro needs")
            log.warn("run {}: {} dropped: {}", execution.processInstanceId, REPRO_DIR, e.message)
            return
        } catch (e: Exception) {
            log.warn("run {}: {} not read: {}", execution.processInstanceId, REPRO_DIR, e.message)
            return
        }
        if (tar != null && runCatching { tarFiles(tar).isNotEmpty() }.getOrDefault(false))
            execution.setVariable(Vars.REPRO_FILES, Variables.byteArrayValue(tar))
    }

    /**
     * The retro turn's notes (engine/README.md, "The retro"): each valid item of [RETRO_PATH] becomes an
     * `engine.friction_note` row with source `retro`, stamped from the run as note_friction stamps a call. A backstop
     * that never changes the run: it sets no variable, an unparsable file stores nothing, an invalid item is skipped,
     * and nothing here fails the collect.
     */
    private fun recordRetro(execution: DelegateExecution, handle: String) {
        val runId = execution.processInstanceId
        if (execution.mode() !in RETRO_MODES) return
        runCatching {
            val bytes = sandbox.output(handle, RETRO_PATH) ?: return
            val items = runCatching { engineJson.readValue(bytes, List::class.java) }.getOrNull()
                ?: run { log.warn("run {}: retro.json is not a JSON array; dropped", runId); return }
            val repo = execution.getVariable(Vars.REPO) as? String
            val issue = execution.issueOrNull()
            var kept = 0
            items.take(MAX_RETRO_NOTES).forEachIndexed { i, item ->
                when (val why = retroNotes.record(repo, issue, runId, item, execution.getVariable(Vars.JOB_ID) as? String)) {
                    null -> kept++
                    else -> log.warn("run {}: retro note {} skipped: {}", runId, i, why)
                }
            }
            if (items.size > MAX_RETRO_NOTES) log.warn("run {}: retro gave {} notes; the first {} kept", runId, items.size, MAX_RETRO_NOTES)
            log.info("run {}: retro: {} friction note(s) recorded", runId, kept)
        }.onFailure { log.warn("run {}: retro notes not recorded: {}", runId, it.message) }
    }
}

/** Most retro notes one run records: the retro is a backstop, not a second channel. */
private const val MAX_RETRO_NOTES = 20

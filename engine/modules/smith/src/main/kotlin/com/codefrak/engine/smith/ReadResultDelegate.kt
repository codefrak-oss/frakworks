package com.codefrak.engine.smith

import com.codefrak.engine.feature.ParentResult
import com.codefrak.engine.feature.SplitState
import com.codefrak.engine.github.GitHubPulls
import com.codefrak.engine.github.GitPush
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.operaton.bpm.engine.variable.Variables
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * `task-read-result` in bpmn/reusable-smith-run.bpmn: checks the Smith's
 * `result.json` by its mode's rules and sets `outcome` for the caller's
 * gateway. A failed run, a missing file, or a result that breaks a rule stops
 * here as an incident, before anything is written to GitHub. The smith-owned
 * modes ([SMITH_MODES]) are read here, every other mode by the [ResultHandler]
 * its flow package registers; `work` is read by [WorkResultReader], whose
 * accepted result goes to every [WorkResultListener] (the ci triage's Jev outcome).
 * Bound by `${readResult}`. The modes and variables the flows read (`INCIDENT_MODES`, `PARENT_ENDED`, `FIX_ERROR`,
 * `RESULT_REJECTED`) are engine-smith-api's (ResultModes.kt).
 */
@Component("readResult")
class ReadResultDelegate(
    handlers: List<ResultHandler> = emptyList(), private val workListeners: List<WorkResultListener> = emptyList(),
    private val tickets: com.codefrak.engine.workitems.Tickets? = null, private val splitRules: SplitDraftRules? = null,
    private val git: GitPush? = null, private val pulls: GitHubPulls? = null, private val stats: SmithRunStats? = null,
) : JavaDelegate {
    private val log = LoggerFactory.getLogger(ReadResultDelegate::class.java)
    private val handlers = resultHandlersByMode(handlers)
    private val workResultReader = WorkResultReader(workListeners, splitRules, git, pulls)

    override fun execute(execution: DelegateExecution) {
        val result = execution.getVariable(Vars.RESULT)
        val mode = execution.mode()
        // A work run that overflowed its context window in a flow with a design step (a feature) goes
        // back to design rather than retrying the same brief; a chore or bug has no design to return to.
        if (result != "success" && result != NO_RESULT && mode == "work" && execution.getVariable(Vars.OVERFLOWED) == true && execution.getVariable(Vars.PLAN) != null) {
            execution.setVariable("outcome", "overflowed")
            log.warn("run {}: work overflowed its context window; back to design", execution.processInstanceId)
            return
        }
        // A fix or diagnose run is the user's attempt at an incident, and a doctor run the engine's at a stuck ticket: failing
        // it must not park it on an incident of its own (the ticket view would show it running forever), so any failure is
        // its outcome, with the reason.
        if (mode in INCIDENT_MODES) {
            val problem = incidentRunProblem(execution, result, mode)
            execution.setVariable(FIX_ERROR, problem)
            if (problem != null) {
                execution.setVariable("outcome", "failed")
                log.warn("run {}: {} failed: {}", execution.processInstanceId, mode, problem)
                return
            }
        }
        // A live parent's session ends when it ends: a failed or reaped run, or one that wrote nothing, is outcome `ended`.
        if (mode in PARENT_MODES) {
            val ended = when {
                result != "success" -> "the parent run ended $result; see its log"
                execution.getVariable(Vars.OUTPUT) !is ByteArray -> "the parent run wrote no $OUTPUT_PATH; see its log"
                else -> null
            }
            execution.setVariable(PARENT_ENDED, ended)
            if (ended != null) {
                execution.setVariable("outcome", ParentResult.ENDED)
                log.warn("run {}: parent ended: {}", execution.processInstanceId, ended)
                return
            }
        }
        // A session or spend limit that was not rerun (engine/README.md, "Incidents") says why on a second line, so the
        // first stays the generic message the doctor's signature reads.
        check(result == "success") {
            "smith run ${execution.getVariable(Vars.HANDLE)} ended $result; see its log" +
                ((execution.getVariable(Vars.LIMIT_NOTE) as? String)?.takeIf { result == "failed" }?.let { "\nIt ended $it." } ?: "")
        }
        val bytes = execution.getVariable(Vars.OUTPUT) as? ByteArray ?: error("the smith wrote no $OUTPUT_PATH; see its log")
        // The types first (smith/schemas/<mode>.schema.json), so a wrong one names its field rather than failing the
        // parse; through enforce, so a retry of the incident reruns the Smith as for any other broken rule.
        if (ResultSchemas.of(mode) != null) enforce(ResultSchemas.problems(mode, bytes).map { "smith/schemas/$mode.schema.json: $it" })
        val outcome = when (mode) {
            "work" -> when (val read = workResultReader.read(execution, bytes)) {
                is WorkRead.Refused -> {
                    if (execution.getVariable(Vars.RULES_REJECTED) != true) return reject(execution, read.findings, read.problems)
                    enforce(read.problems)
                    return
                }
                is WorkRead.Accepted -> read.outcome
            }
            "reproduce" -> {
                val reproduction = ReproduceResult.parse(bytes)
                enforce(reproduction.problems())
                // A dropped /work/out/repro rides in the reproduction, so the fix and verify briefs say so.
                val dropped = execution.getVariable(Vars.REPRO_DROPPED) as? String
                val kept = if (dropped == null) bytes else ReproduceResult.withFilesDropped(bytes, dropped)
                execution.setVariable(Vars.REPRODUCTION, Variables.byteArrayValue(kept))
                reproduction.outcome
            }
            "verify" -> {
                val verification = VerifyResult.parse(bytes)
                enforce(verification.problems())
                execution.setVariable(Vars.VERIFICATION, Variables.byteArrayValue(bytes))
                verification.outcome
            }
            "review", "security" -> {
                val review = ReviewResult.parse(bytes)
                enforce(review.problems(mode))
                // The review's number among its root's reviews comes from the run rows (engine/README.md, "Ticket timeline"):
                // one number stamps the findings (`<mode>-<n>-<i>`) and is written to the run's row with them. The rows
                // are the history the ticket views read, so a failure to write them fails the read.
                val runStats = stats ?: error("no SmithRunStats to number the review")
                val run = runStats.latestOfWrapper(execution.processInstanceId)
                    ?: error("run ${execution.processInstanceId} has no engine.smith_run_stats row to number its review")
                val reviewNo = runStats.nextReviewNo(rootOf(execution))
                val open = review.stamped(mode, reviewNo)
                runStats.reviewed(run.runId, reviewNo)
                runStats.findings(run.runId, reviewNo, open)
                execution.setVariable(Vars.FINDINGS, if (open.isEmpty()) null else Variables.byteArrayValue(Brief.findingsBytes(open)))
                if (open.isNotEmpty()) {
                    val raised = Brief.findings(execution.getVariable(Vars.RAISED) as? ByteArray) + open
                    execution.setVariable(Vars.RAISED, Variables.byteArrayValue(Brief.findingsBytes(raised)))
                }
                review.outcome
            }
            // A live parent's result, held to a replan's rules against the split it watches; kept for the flow in `parent`.
            "parent" -> {
                val parent = ParentResult.parse(bytes)
                val split = SplitState.of(execution.getVariable(Vars.SPLIT) as? ByteArray) ?: error("no split state for the parent")
                enforce(parent.problems(split.filed, split.drafts))
                execution.setVariable(Vars.PARENT, Variables.byteArrayValue(bytes))
                parent.outcome
            }
            // A callable-smith run (engine/README.md, "Custom jobs and the SDK"): done or failed, and the JSON stays in `output` for the caller.
            "job" -> JobResult.parse(bytes).also { enforce(it.problems()) }.outcome
            // A propose result the rules refuse for the first time in a row reruns the draft, as a work result does
            // (engine/README.md, "Incidents", "A rejected work result"); the rules finding needs no answer of the draft.
            // An accept result is rejected the same way, and the acceptance check runs again.
            "propose", "accept" -> {
                val handler = handlers[mode] ?: error("no result rules for mode '$mode'")
                val firstRefusal = execution.getVariable(Vars.RULES_REJECTED) != true
                val findings = Brief.findings(execution.getVariable(Vars.FINDINGS) as? ByteArray)
                val outcome = try {
                    handler.handle(execution, mode, bytes) { problems -> if (firstRefusal && problems.isNotEmpty()) throw Refused(problems) else enforce(problems) }
                } catch (refused: Refused) {
                    return reject(execution, findings, refused.problems, if (mode == "accept") ACCEPT else PROPOSE)
                }
                execution.setVariable(Vars.RULES_REJECTED, null)
                if (findings.any { it.review == RULES }) findings.filter { it.review != RULES }.let {
                    execution.setVariable(Vars.FINDINGS, if (it.isEmpty()) null else Variables.byteArrayValue(Brief.findingsBytes(it)))
                }
                outcome
            }
            else -> (handlers[mode] ?: error("no result rules for mode '$mode'")).handle(execution, mode, bytes, ::enforce)
        }
        execution.setVariable("outcome", outcome)
        log.info("run {}: {} outcome {}", execution.processInstanceId, mode, outcome)
    }

    /**
     * A work result [WorkResult.problems] turned down for the first time in a row: outcome `rejected`, which the flow's
     * `gateway-work-outcome` sends back to the work, with a `rules-<round>-1` finding next to the findings still open
     * (engine/README.md, "Incidents"). The next rejection, before an accepted result, parks on the incident as before.
     */
    private fun reject(execution: DelegateExecution, open: List<Finding>, problems: List<String>, what: Rejection = WORK) {
        val round = execution.round()
        val finding = Finding(what.ask(problems), "high", id = "rules-$round-1", review = RULES)
        val findings = open.filter { it.id != finding.id } + finding
        val raised = Brief.findings(execution.getVariable(Vars.RAISED) as? ByteArray).filter { it.id != finding.id } + finding
        execution.setVariable(Vars.FINDINGS, Variables.byteArrayValue(Brief.findingsBytes(findings)))
        execution.setVariable(Vars.RAISED, Variables.byteArrayValue(Brief.findingsBytes(raised)))
        execution.setVariable(Vars.RULES_REJECTED, true)
        execution.setVariable("outcome", RESULT_REJECTED)
        // The run ended `rejected` on its stats row, whatever its result said (engine/README.md, "Ticket timeline").
        runCatching { stats?.let { s -> s.latestOfWrapper(execution.processInstanceId)?.let { s.rejected(it.runId) } } }
            .onFailure { log.warn("run {}: rejection not stamped on its stats row: {}", execution.processInstanceId, it.message) }
        log.warn("run {}: {} result rejected, {} again: {}", execution.processInstanceId, what.result, what.reruns, problems.joinToString("; "))
        tickets?.let { t ->
            runCatching {
                val item = t.forTicket(execution.repo(), execution.issue())
                    ?: throw com.codefrak.engine.workitems.WorkItemRefused("no ticket ${execution.repo()}#${execution.issue()}")
                t.comment(item.id, "engine",
                    "The engine rejected the ${what.result} result (${problems.joinToString("; ") { "`$it`" }}); ${what.reruns} again with a new Smith, " +
                        "told why in a `${finding.id}` finding. A second rejection parks on an incident.\n\n— engine")
            }.onFailure { log.warn("run {}: rejection comment not posted: {}", execution.processInstanceId, it.message) }
        }
    }

    /** Why a fix, diagnose or doctor run's result cannot be used, or null when it can: the same checks as [enforce] makes. */
    private fun incidentRunProblem(execution: DelegateExecution, result: Any?, mode: String): String? {
        if (result != "success") return "the smith run ended $result; see its log"
        val bytes = execution.getVariable(Vars.OUTPUT) as? ByteArray ?: return "the smith wrote no $OUTPUT_PATH; see its log"
        val problems = ResultSchemas.problems(mode, bytes).map { "smith/schemas/$mode.schema.json: $it" }.ifEmpty {
            runCatching { (handlers[mode] ?: error("no result rules for mode '$mode'")).problems(mode, bytes) }
                .getOrElse { listOf("unreadable result: ${it.message}") }
        }
        return if (problems.isEmpty()) null else "the smith's result breaks the rules: ${problems.joinToString("; ")}"
    }

    /** What [reject] turned down: the result's name and what runs again, in the log and the comment, and the finding's ask. */
    class Rejection(val result: String, val reruns: String, val ask: (List<String>) -> String)

    /** A propose or accept rule refused for the first time in a row, out of the handler's `enforce` to [reject]. */
    private class Refused(val problems: List<String>) : RuntimeException(null, null, false, false)

    companion object {
        private val WORK = Rejection("work", "the round runs", ::rulesAsk)
        private val PROPOSE = Rejection("draft", "the draft runs", ::proposeRulesAsk)
        private val ACCEPT = Rejection("accept", "the acceptance check runs", ::acceptRulesAsk)

        /** A work run's outcome when its result was turned down and the round runs again ([reject]). */
        /** The `review` of the finding [reject] raises. */
        const val RULES = "rules"

        /** The `rules` finding's ask: every problem verbatim, and the valid results. */
        fun rulesAsk(problems: List<String>) =
            "The engine rejected the last work result of this round: it breaks the rules (${problems.joinToString("; ")}). " +
                "Answer with one of the valid results; this finding itself needs no answer. `done`: a title, a summary, your commits in " +
                "$BUNDLE_PATH, and every other open finding in addressed or declined. `ask`: a question, when nothing should change while " +
                "findings stand (a ci failure you judge flaky, a review finding you would decline with no commit). `${WorkResult.NOTHING_TO_DO}`: " +
                "a reason, only when no other finding is open."

        /** The `rules` finding's ask for a refused propose result: every problem verbatim, and the valid results. */
        fun proposeRulesAsk(problems: List<String>) =
            "The engine rejected the last draft result: it breaks the rules (${problems.joinToString("; ")}). " +
                "Answer with one of the valid results; this finding itself needs no answer. `drafted`: a path, a pitch, a summary, " +
                "questions, your commits in $BUNDLE_PATH, and an `options` entry for every `data-option-id` in the pitch page " +
                "(run `check-result propose` after writing the page: it checks the page against `options`). `ask`: a question."

        /** The `rules` finding's ask for a refused accept result: every problem verbatim, and the valid results. */
        fun acceptRulesAsk(problems: List<String>) =
            "The engine rejected the last accept result: it breaks the rules (${problems.joinToString("; ")}). " +
                "Answer with one of the valid results, each with a summary; this finding itself needs no answer. `accepted`: every " +
                "criterion `met` true. `unmet`: at least one criterion `met: false`. `unverified`: when some criterion could not be checked here."
    }

    private fun enforce(problems: List<String>) =
        check(problems.isEmpty()) { "the smith's result breaks the rules: ${problems.joinToString("; ")}" }
}

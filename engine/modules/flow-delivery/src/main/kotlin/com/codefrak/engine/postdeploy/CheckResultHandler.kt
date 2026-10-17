package com.codefrak.engine.postdeploy

import com.codefrak.engine.smith.ResultHandler
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.issueOrNull
import com.codefrak.engine.smith.repo
import com.codefrak.engine.workitems.Tickets
import com.codefrak.engine.workitems.commentOn
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.variable.Variables
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.Date

/**
 * A check run's result (`read result`, [ResultHandler]; engine/docs/smith-runs.md, "The check mode"): sets
 * `checkOutcome`, `checkSummary`, `checkWakeAt` (a Date, for a timer) and `checkWakeEvent` (the hint's event, for a
 * bus wait), appends the run to `checkHistory`, writes one conversation entry on the ticket's work item (through
 * [tickets], the engine's ticket writes; none in a test without one) and answers the outcome. A result that breaks
 * the contract (no bug on `failed`, a wake with both or neither key, an unparsable `at`) is refused through `enforce`.
 * `checkWakeAt` and `checkWakeEvent` are cleared first, so a run with no hint leaves none behind for `nextCheck`.
 */
@Component
class CheckResultHandler(private val tickets: Tickets? = null, private val clock: Clock = Clock.systemUTC()) : ResultHandler {
    private val log = LoggerFactory.getLogger(CheckResultHandler::class.java)
    override val modes = setOf(MODE)

    override fun handle(execution: DelegateExecution, mode: String, bytes: ByteArray, enforce: (List<String>) -> Unit): String {
        val result = CheckResult.parse(bytes)
        enforce(result.problems())
        val wakeAt = result.wakeAt()
        val wakeEvent = result.wake?.event
        execution.setVariable(Vars.CHECK_OUTCOME, result.outcome)
        execution.setVariable(Vars.CHECK_SUMMARY, result.summary?.take(Vars.TAIL_CHARS))
        execution.setVariable(Vars.CHECK_WAKE_AT, wakeAt?.let { Date.from(it) })
        execution.setVariable(Vars.CHECK_WAKE_EVENT, wakeEvent)
        val run = CheckRun(execution.processInstanceId, Instant.now(clock).toString(), result.outcome, result.summary, wakeAt?.toString(), wakeEvent)
        val history = CheckRun.list(execution.getVariable(Vars.CHECK_HISTORY) as? ByteArray) + run
        execution.setVariable(Vars.CHECK_HISTORY, Variables.byteArrayValue(CheckRun.bytes(history)))
        comment(execution, result, history.size)
        return result.outcome
    }

    private fun comment(execution: DelegateExecution, result: CheckResult, runs: Int) {
        val issue = execution.issueOrNull() ?: return
        val body = entry(result, runs, execution.processInstanceId)
        try { tickets?.commentOn(execution.repo(), issue, body) } catch (e: Exception) {
            log.warn("check run {} of {}#{}: the conversation entry was not written: {}", execution.processInstanceId, execution.repo(), issue, e.message)
        }
    }

    companion object {
        const val MODE = "check"

        /** The conversation entry per run: the outcome, the summary, and the hint when there is one. */
        fun entry(result: CheckResult, runs: Int, run: String): String = buildString {
            append("Post-deployment check, run $runs: **${result.outcome}**")
            when (result.outcome) {
                CheckResult.FAILED -> append(" — a bug is filed")
                CheckResult.NOT_YET -> result.wake?.let { w -> append(" — look again ${w.at?.let { "at $it" } ?: "on `${w.event}`"}") }
            }
            append(".\n\n").append(result.summary?.trimEnd().orEmpty()).append("\n\n— engine (check run `").append(run).append("`)\n")
        }
    }
}

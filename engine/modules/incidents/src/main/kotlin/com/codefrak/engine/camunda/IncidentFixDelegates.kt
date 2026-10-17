package com.codefrak.engine.camunda

import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.smith.FIX_ERROR
import com.codefrak.engine.smith.Vars
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/** Process variables of `incident-fix` (bpmn/incident-fix.bpmn). */
object FixVars {
    /** The ticket's repo and issue: not `repo`/`issue`, so the fix is not taken for the ticket's own flow (`IssueInstances`). */
    const val REPO = "fixRepo"; const val ISSUE = "fixIssue"
    /** `fix` or `diagnose`: the Smith run's mode. */
    const val MODE = "fixMode"
    /** The root-cause incident the run is about, and the flow it is on. */
    const val INCIDENT_ID = "incidentId"; const val ROOT = "rootInstanceId"
    /** The GitHub login that pressed the button: a fix's retry is done as them. */
    const val BY = "requestedBy"
    /** What `apply the fix` made of the run: `retried`, `failed` or `proposed`. */
    const val OUTCOME = "fixOutcome"
    /** Why a fix failed, or the Smith's diagnosis. */
    const val DETAIL = "fixDetail"; const val DIAGNOSIS = "fixDiagnosis"
    /** A diagnose run's summary, cut to `MAX_PROPOSAL` characters ([proposal]). */
    const val PROPOSAL = "proposal"
    /** Jev's pre-triage of a fix ([IncidentTriage]): true when its `retry` stands in for the Smith; its `engine.jev_call` id. */
    const val JEV_RETRY = "fixJevRetry"; const val JEV_CALL = "fixJevCall"
}

/**
 * `task-apply-fix` in bpmn/incident-fix.bpmn (engine/README.md, "Fix and Propose a fix"). A fix Smith's
 * `retry` is applied through [IncidentRetrier], as the user who pressed Fix, while the incident is still
 * open; `unfixable`, a failed run, or an incident no longer open is `failed`. A diagnose run's summary
 * becomes the `proposal`. A fix Jev read as `retry` (`fixJevRetry`, no Smith run) is retried the same way.
 * Never throws for the Smith's sake: a fix that did not fix is an outcome, not an incident. The fix's Jev
 * call, when it has one, gets what happened as its outcome: the retry, or the Smith's remedy.
 * Bound by `${applyIncidentFix}`.
 */
@Component("applyIncidentFix")
class ApplyIncidentFixDelegate(
    private val retrier: IncidentRetrier, private val runtime: RuntimeService, private val calls: JevCalls,
) : JavaDelegate {
    private val log = LoggerFactory.getLogger(ApplyIncidentFixDelegate::class.java)

    override fun execute(execution: DelegateExecution) {
        val incidentId = execution.getVariable(FixVars.INCIDENT_ID) as String
        val error = execution.getVariable(FIX_ERROR) as? String
        val output = execution.getVariable(Vars.OUTPUT) as? ByteArray
        val by = execution.getVariable(FixVars.BY) as String
        var remedy: String? = null
        val (outcome, detail) = when {
            execution.getVariable(FixVars.JEV_RETRY) == true -> retry(incidentId, by)
            error != null || output == null -> "failed" to (error ?: "the smith left no result")
            execution.getVariable(FixVars.MODE) == "diagnose" -> {
                val result = DiagnoseResult.parse(output)
                execution.setVariable(FixVars.DIAGNOSIS, result.diagnosis)
                execution.setVariable(FixVars.PROPOSAL, proposal(result.summary!!))
                "proposed" to null
            }
            else -> {
                val result = FixResult.parse(output)
                execution.setVariable(FixVars.DIAGNOSIS, result.diagnosis)
                remedy = result.outcome
                apply(result, incidentId, by)
            }
        }
        execution.setVariable(FixVars.OUTCOME, outcome)
        execution.setVariable(FixVars.DETAIL, detail)
        (execution.getVariable(FixVars.JEV_CALL) as? String)?.let { call ->
            val what = when {
                execution.getVariable(FixVars.JEV_RETRY) == true -> "retried without a Smith: $outcome"
                remedy != null -> "the Fix Smith's remedy: $remedy, $outcome"
                else -> "the Fix Smith gave no remedy: $outcome"
            }
            runCatching { calls.recordOutcome(call, what + (detail?.let { " ($it)" } ?: "")) }
                .onFailure { log.warn("could not record the outcome of jev call {}: {}", call, it.message) }
        }
        log.info("incident-fix {} on incident {}: {}{}", execution.processInstanceId, incidentId, outcome, detail?.let { " ($it)" } ?: "")
    }

    private fun apply(result: FixResult, incidentId: String, by: String): Pair<String, String?> {
        if (result.outcome != "retry") return "failed" to "the smith found it unfixable"
        return retry(incidentId, by)
    }

    /** Retries the incident as `by` while it is still open: `retried`, or `failed` and why. */
    private fun retry(incidentId: String, by: String): Pair<String, String?> {
        if (runtime.createIncidentQuery().incidentId(incidentId).singleResult() == null) return "failed" to "the incident is no longer open"
        return try {
            retrier.retry(incidentId, by)
            "retried" to null
        } catch (e: ResponseStatusException) {
            "failed" to "the retry was refused: ${e.reason}"
        }
    }
}

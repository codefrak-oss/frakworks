package com.codefrak.engine.smith

import com.codefrak.engine.accounts.AiAccounts
import com.codefrak.engine.accounts.providerOf
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.Date

/** How many times an evicted run's step is started again before its next eviction parks on an incident. */
const val MAX_EVICTION_RERUNS = 3

/** The process that calls `smith-run` and reads its result (engine/bpmn/reusable-smith-run.bpmn); only it loops back on [Vars.RERUN]. */
const val REUSABLE_SMITH_RUN = "reusable-smith-run"

/** Who started a run, for the rerun policy: the calling process's definition key and instance id, null when the run was started on its own. */
interface RunCaller {
    fun key(execution: DelegateExecution): String?
    fun instance(execution: DelegateExecution): String?
}

/** [RunCaller] through the Operaton entity the engine actually runs on: casts the execution to [ExecutionEntity] to read its super execution. */
@Component
class ExecutionEntityRunCaller : RunCaller {
    private fun superExecution(execution: DelegateExecution) =
        (execution as? ExecutionEntity)?.processInstance?.superExecution

    override fun key(execution: DelegateExecution): String? = superExecution(execution)?.processDefinitionId?.substringBefore(':')

    override fun instance(execution: DelegateExecution): String? = superExecution(execution)?.processInstanceId
}

/** The rerun policy `collectSmith` calls after a run ends (engine/README.md, "Incidents"): whether it is started again, with which note, on which account. */
@Component
class SmithReruns(private val accounts: AiAccounts, private val caller: RunCaller = ExecutionEntityRunCaller()) {
    private val log = LoggerFactory.getLogger(SmithReruns::class.java)

    /** A session or spend limit a run hit: its account, and until when it is rejected (null when no reset was known, so it is not). */
    data class SessionLimit(val account: String, val until: OffsetDateTime?)

    /**
     * Whether the run is started again on another account (engine/README.md, "Incidents"): only a failed run whose
     * account was refused for its session or spend limit and is now rejected, that is neither pinned nor a fix, diagnose
     * or doctor run, that `reusable-smith-run` called, and whose account has not already rerun this step. It reruns now
     * when the picker would choose another account of its provider right now, else at the earliest reset of the
     * provider's accounts ([AiAccounts.blockedUntil], into [Vars.RERUN_AT]) when one is known; with none it parks.
     * Sets [Vars.RERUN], [Vars.RERUN_AT], [Vars.LIMIT_NOTE] and [Vars.LIMIT_RERUNS]; a run that hit no limit clears the
     * last two, and is left to [decideFailRerun]. A failed run the cluster evicted is [decideEvictRerun]'s first.
     */
    fun decide(execution: DelegateExecution, account: String?, result: String, limit: SessionLimit?, exit: Long, evicted: Boolean = false, model: String? = null) {
        execution.setVariable(Vars.RERUN, false)
        execution.setVariable(Vars.RERUN_AT, null)
        execution.setVariable(Vars.LIMIT_NOTE, null)
        execution.setVariable(Vars.FAIL_NOTE, null)
        execution.setVariable(Vars.EVICT_NOTE, null)
        if (evicted && result == "failed" && decideEvictRerun(execution)) return
        val reruns = (execution.getVariable(Vars.LIMIT_RERUNS) as? String)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        if (result != "failed" || account == null || limit == null) {
            execution.setVariable(Vars.LIMIT_RERUNS, null)
            if (limit == null) decideFailRerun(execution, result, exit)
            return
        }
        if (model == null) return
        val provider = providerOf(model)
        val hit = "on account $account's session or spend limit" + (limit.until?.let { ", rejected until ${it.toInstant()}" } ?: "")
        val mode = execution.mode()
        val why = when {
            failureIsOutcome(mode) -> null // its failure is its outcome (ReadResultDelegate)
            execution.getVariable(Vars.ACCOUNT) != null -> "its account is pinned"
            limit.until == null -> "no reset is known, so $account is not rejected"
            callerKey(execution) != REUSABLE_SMITH_RUN -> "it was not called by $REUSABLE_SMITH_RUN"
            account in reruns -> "this step was already rerun after a limit on $account"
            else -> accounts.peek(provider, model)?.takeIf { it != account }?.let { other ->
                execution.setVariable(Vars.RERUN, true)
                execution.setVariable(Vars.LIMIT_RERUNS, (reruns + account).joinToString(","))
                execution.setVariable(Vars.LIMIT_NOTE, "$hit; another $provider account ($other) is usable")
                log.warn("run {}: {}; rerun on another {} account", execution.processInstanceId, hit, provider)
                return
            } ?: accounts.blockedUntil(provider)?.let { reset ->
                // Every account of the provider is rejected: the rerun waits for the first one back, counted like one now.
                val note = "$hit; no other $provider account is usable until ${reset.toInstant()}"
                execution.setVariable(Vars.RERUN, true)
                execution.setVariable(Vars.RERUN_AT, Date.from(reset.toInstant()))
                execution.setVariable(Vars.LIMIT_RERUNS, (reruns + account).joinToString(","))
                execution.setVariable(Vars.LIMIT_NOTE, note)
                log.warn("run {}: {}; rerun at {}", execution.processInstanceId, note, reset.toInstant())
                return
            } ?: "no other $provider account is usable"
        }
        execution.setVariable(Vars.LIMIT_NOTE, why?.let { "$hit; $it" } ?: hit)
        log.warn("run {}: {}; not rerun{}", execution.processInstanceId, hit, why?.let { ": $it" } ?: "")
    }

    /**
     * The one automatic rerun of a failed run (engine/README.md, "Incidents"): a run that ended `failed` or `no-result`
     * on no session or spend limit, that `reusable-smith-run` called, that is not a fix, diagnose or doctor run (its
     * failure is its outcome) nor a work run that overflowed back to design (`read result`), is started again once per
     * wrapper: [Vars.FAIL_RERUN_OF] names the wrapper it was spent in, so the wrapper's next failure parks on the incident.
     * A result `enforce` rejects is `success` here and never qualifies.
     */
    private fun decideFailRerun(execution: DelegateExecution, result: String, exit: Long) {
        if (result != "failed" && result != NO_RESULT) return
        val mode = execution.mode()
        if (failureIsOutcome(mode)) return
        if (callerKey(execution) != REUSABLE_SMITH_RUN) return
        if (result == "failed" && mode == "work" && execution.getVariable(Vars.OVERFLOWED) == true && execution.getVariable(Vars.PLAN) != null) return
        val wrapper = callerInstance(execution) ?: return
        if (execution.getVariable(Vars.FAIL_RERUN_OF) == wrapper) {
            log.warn("run {}: ended {}; its step was already rerun once after a failure, not rerun", execution.processInstanceId, result)
            return
        }
        val note = if (result == "failed") "failed (exit $exit)" else "with no result.json (exit 0)"
        execution.setVariable(Vars.RERUN, true)
        execution.setVariable(Vars.FAIL_RERUN_OF, wrapper)
        execution.setVariable(Vars.FAIL_NOTE, note)
        log.warn("run {}: ended {}; its one automatic rerun", execution.processInstanceId, note)
    }

    /**
     * A run the cluster evicted or preempted (engine/README.md, "Incidents"): not the Smith's failure, so its step is
     * started again, up to [MAX_EVICTION_RERUNS] times per wrapper ([Vars.EVICTION_RERUNS]), without spending the one
     * failure rerun. Past the bound it is not rerun, and [Vars.LIMIT_NOTE] names the evictions for the incident. True
     * when it decided (rerun or parked); false when the run is not one it reruns (a fix, diagnose or doctor run, or one
     * no `reusable-smith-run` called), left to the other rules.
     */
    private fun decideEvictRerun(execution: DelegateExecution): Boolean {
        val mode = execution.mode()
        if (failureIsOutcome(mode)) return false
        if (callerKey(execution) != REUSABLE_SMITH_RUN) return false
        val wrapper = callerInstance(execution) ?: return false
        val previous = (execution.getVariable(Vars.EVICTION_RERUNS) as? String)
            ?.takeIf { it.substringBeforeLast(':') == wrapper }?.substringAfterLast(':')?.toIntOrNull() ?: 0
        val times = previous + 1
        if (previous >= MAX_EVICTION_RERUNS) {
            val note = "evicted by the cluster $times times (node pressure or preemption); its step was rerun after an eviction " +
                "$MAX_EVICTION_RERUNS times, not again"
            execution.setVariable(Vars.LIMIT_NOTE, note)
            log.warn("run {}: {}", execution.processInstanceId, note)
            return true
        }
        val note = "evicted by the cluster (node pressure or preemption), eviction $times of at most ${MAX_EVICTION_RERUNS + 1}"
        execution.setVariable(Vars.RERUN, true)
        execution.setVariable(Vars.EVICTION_RERUNS, "$wrapper:$times")
        execution.setVariable(Vars.EVICT_NOTE, note)
        log.warn("run {}: {}; rerun", execution.processInstanceId, note)
        return true
    }

    /** The process key of the call activity's process that called this run, or null when it was started on its own. */
    private fun callerKey(execution: DelegateExecution): String? = caller.key(execution)

    /** The process instance whose call activity called this run, or null when it was started on its own. */
    private fun callerInstance(execution: DelegateExecution): String? = caller.instance(execution)
}

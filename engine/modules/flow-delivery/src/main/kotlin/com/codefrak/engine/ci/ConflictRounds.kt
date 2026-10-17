package com.codefrak.engine.ci

import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.Finding
import com.codefrak.engine.smith.Vars
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.variable.Variables

/**
 * Conflict rounds a flow may send to the work in a row: a round the work resolved (the next probe finds the branch
 * current or merging cleanly, [resolved]) starts the count over, and [MAX_TOTAL_CONFLICT_ROUNDS] bounds them over the
 * flow's life, so a hub file that main keeps moving under no longer strands a flow, nor loops it forever. Shared with
 * `RebasePrDelegate`'s own conflict (`task-rebase-pr`), which meets the same conflict later, if GitHub's
 * merge succeeds where the engine check's probe (`CiCheckConflictDelegate`) found none yet.
 *
 * A retry of the incident either cap raises grants more rounds (`IncidentRetrier`): it sets `conflictRounds` back to 0
 * and `conflictRoundsGranted` to [MAX_CONFLICT_ROUNDS], on the instance the job runs in and on the root flow. While
 * `conflictRoundsGranted` is above 0, a conflict round spends one of it and is not held to [MAX_TOTAL_CONFLICT_ROUNDS];
 * the consecutive cap still holds. `conflictRoundsTotal` counts granted rounds too, so `<n>` in `rebase-<n>-1` stays unique.
 */
object ConflictRounds {
    /** Consecutive unresolved conflict rounds a flow may send to the work; the next conflict is an incident. */
    const val MAX_CONFLICT_ROUNDS = 2

    /** Conflict rounds over the flow's life, resolved or not (`conflictRoundsTotal`); the next conflict is an incident. */
    const val MAX_TOTAL_CONFLICT_ROUNDS = 6

    /**
     * A conflict round: counts it in `conflictRounds` and leaves the `rebase-<n>-1` finding in `findings`
     * (and `raised`) for the work; past [MAX_CONFLICT_ROUNDS] it fails, which is the incident. Shared with
     * `RebasePrDelegate`'s own conflict probe, and the engine check's `mergeable?` probe
     * (`CiCheckConflictDelegate`), which meets the same conflict right after the push, where GitHub would
     * make no check run for it.
     */
    fun recordConflict(execution: DelegateExecution, default: String, files: List<String>) {
        val rounds = execution.getVariable(Vars.CONFLICT_ROUNDS) as? Number
        val n = (rounds?.toInt() ?: 0) + 1
        // A flow started before the lifetime count has only `conflictRounds`, never reset then: it is the total so far.
        val total = ((execution.getVariable(Vars.CONFLICT_ROUNDS_TOTAL) as? Number ?: rounds)?.toInt() ?: 0) + 1
        check(n <= MAX_CONFLICT_ROUNDS) {
            "merge conflict with $default in ${files.joinToString()} after $MAX_CONFLICT_ROUNDS conflict rounds"
        }
        val granted = (execution.getVariable(Vars.CONFLICT_ROUNDS_GRANTED) as? Number)?.toInt() ?: 0
        if (granted == 0) check(total <= MAX_TOTAL_CONFLICT_ROUNDS) {
            "merge conflict with $default in ${files.joinToString()} after $MAX_TOTAL_CONFLICT_ROUNDS conflict rounds " +
                "in the flow's life (the lifetime cap), each resolved before the next"
        }
        // The lifetime count keeps the id unique across a reset of the consecutive one.
        val finding = Finding(conflictAsk(default, files), "high", id = "rebase-$total-1", review = "rebase")
        val raised = Brief.findings(execution.getVariable(Vars.RAISED) as? ByteArray) + finding
        if (granted > 0) execution.setVariable(Vars.CONFLICT_ROUNDS_GRANTED, granted - 1)
        execution.setVariable(Vars.CONFLICT_ROUNDS, n)
        execution.setVariable(Vars.CONFLICT_ROUNDS_TOTAL, total)
        execution.setVariable(Vars.FINDINGS, Variables.byteArrayValue(Brief.findingsBytes(listOf(finding))))
        execution.setVariable(Vars.RAISED, Variables.byteArrayValue(Brief.findingsBytes(raised)))
    }

    /**
     * The probe after a conflict round found the branch current or merging cleanly: the work resolved it, so the
     * consecutive count starts over. The lifetime count stays.
     */
    fun resolved(execution: DelegateExecution) {
        if (((execution.getVariable(Vars.CONFLICT_ROUNDS) as? Number)?.toInt() ?: 0) > 0) execution.setVariable(Vars.CONFLICT_ROUNDS, 0)
    }

    /**
     * The review of the `push-<n>-1` finding a retried push refusal leaves for the rerun work (`IncidentRetrier.PUSH_REVIEW`,
     * incidents, which this module does not depend on).
     */
    const val PUSH_REVIEW = "push"

    /**
     * `findings` once a push landed: without the `push-<n>-1` finding, which the pushing round answered, so it does not
     * linger into later rounds; null when none remain. Null too when there was none to drop, so the caller leaves `findings` be.
     */
    fun afterPush(findings: List<Finding>): List<Finding>? =
        if (findings.none { it.review == PUSH_REVIEW }) null else findings.filter { it.review != PUSH_REVIEW }

    /** The conflict round's finding: what conflicts, and how the work Smith resolves it. */
    fun conflictAsk(default: String, files: List<String>) =
        "merge conflict with the default branch in ${files.joinToString()}: merge origin/$default into your branch and resolve, " +
            "keeping both sides' intent. First run `git fetch origin $default` (with `--unshallow` if the merge base is missing) " +
            "and `git merge origin/$default`, resolve every conflict, commit the merge, then re-run the tests."
}

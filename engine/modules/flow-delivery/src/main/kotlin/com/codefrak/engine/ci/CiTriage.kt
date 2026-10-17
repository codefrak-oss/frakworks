package com.codefrak.engine.ci

import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.jev.JevQuestion
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.WorkResult
import org.operaton.bpm.engine.delegate.DelegateExecution

/**
 * Jev's triage of a failed engine check (engine/README.md, "Chore", `wait for the engine check`): was it
 * the code, the infrastructure, or a flaky test? [read] is the rule on its pick, and [diagnosed] records the
 * work Smith's answer to the finding as the call's outcome.
 */
object CiTriage {
    const val USE_CASE = "ci-triage"
    const val QUESTION = "cause"
    const val CODE = "code"; const val INFRASTRUCTURE = "infrastructure"; const val FLAKY = "flaky"

    val CAUSES_BY_NAME = mapOf(
        CODE to "The branch broke it: a compile error, or a test that fails because of what the branch changed",
        INFRASTRUCTURE to "Nothing the branch did: the runner, the builder, the network, a registry or download, a timeout, an out-of-memory kill",
        FLAKY to "A test that fails without the branch touching what it tests: timing, ordering, shared state, a race",
    )
    val CAUSES = JevQuestion.choice(
        "The `finding` is the engine's report of a failed build-and-test check on a pull request's branch: the failing tests " +
            "or compile errors, then the end of the job log. Why did the check fail?",
        CAUSES_BY_NAME,
    )

    /** What `read check` does with a failed check: re-run it, or send the finding to the work, and the call's action. */
    data class Triage(val rerun: Boolean, val action: String)

    /**
     * The rule on Jev's `pick` (a cause, or null for no answer) and its `confidence`: infrastructure or flaky
     * at the `floor` or above re-runs the check when the round has not (`rerunAllowed`); anything else goes to
     * the work, as it did before Jev.
     */
    fun read(pick: String?, confidence: Double?, floor: Double, rerunAllowed: Boolean): Triage = when {
        pick == null || confidence == null -> Triage(false, "no answer: to the work")
        pick == CODE -> Triage(false, "code: to the work")
        confidence < floor -> Triage(false, "$pick below floor $floor: to the work")
        !rerunAllowed -> Triage(false, "$pick, re-ran this round already: to the work")
        else -> Triage(true, "$pick: re-ran the check")
    }

    /** The finding's last lines: Jev's pick, which the work Smith weighs but need not believe, and what its answer is for. */
    fun note(pick: String, confidence: Double, floor: Double, reran: Boolean): String = buildString {
        appendLine().appendLine()
        if (reran) append("The engine re-ran this check once already, on a first failure it read as not the code; it failed again. ")
        append("Jev (TypeSafe's decision model) read this failure as `$pick` (confidence ${"%.2f".format(confidence)}, re-run floor $floor). ")
        append("It is a guess from the log, not a diagnosis. In your answer to this finding, say whether the cause was the code, ")
        append("the infrastructure or a flaky test: the engine records it against Jev's pick.")
    }

    /**
     * After a work run: when the finding a triage call went out with (`ciJevFinding`) is addressed or declined,
     * its how or why is the call's outcome, and the pending call is cleared. A result that answers it not
     * (an `ask`) leaves it for the next.
     */
    fun diagnosed(execution: DelegateExecution, work: WorkResult, calls: JevCalls) {
        val call = execution.getVariable(Vars.CI_JEV_CALL) as? String ?: return
        val finding = execution.getVariable(Vars.CI_JEV_FINDING) as? String ?: return
        val answer = work.addressed.firstOrNull { it.finding == finding }?.let { "addressed: ${it.how}" }
            ?: work.declined.firstOrNull { it.finding == finding }?.let { "declined: ${it.why}" } ?: return
        calls.recordOutcome(call, "the work Smith's answer to $finding: $answer")
        execution.setVariable(Vars.CI_JEV_CALL, null)
        execution.setVariable(Vars.CI_JEV_FINDING, null)
    }
}

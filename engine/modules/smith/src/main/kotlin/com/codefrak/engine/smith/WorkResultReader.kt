package com.codefrak.engine.smith

import com.codefrak.engine.github.GitHubPulls
import com.codefrak.engine.github.GitPush
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.variable.Variables
import org.slf4j.LoggerFactory

/** What [WorkResultReader.read] answers: the work outcome, or the rules it was turned down by. */
sealed interface WorkRead {
    data class Accepted(val outcome: String) : WorkRead
    data class Refused(val findings: List<Finding>, val problems: List<String>) : WorkRead
}

/**
 * Reads a `work` result ([ReadResultDelegate]'s `work` mode): the pushed-ahead bundle, the rules check
 * against the open findings, the rules-finding clean-up, the `ciNotMine` streak and its two refusals, the
 * previous-bundle restore, the [Vars.WORK] write and the [WorkResultListener] calls.
 */
class WorkResultReader(
    private val workListeners: List<WorkResultListener>, private val splitRules: SplitDraftRules?,
    private val git: GitPush?, private val pulls: GitHubPulls?,
) {
    companion object {
        /** The start of the refusal past [Vars.CI_NOT_MINE_MAX] declining rounds: `IncidentRetrier`'s `rerunCi` retry matches it. */
        const val CI_NOT_MINE_CAP = "declined the engine check's failure as not this change's"
    }

    private val log = LoggerFactory.getLogger(WorkResultReader::class.java)

    /** What the ticket's `smith/issue-<n>` branch has over the default branch on the remote, or null (none, or no git). */
    private fun pushedAhead(execution: DelegateExecution): ByteArray? {
        val git = git ?: return null; val pulls = pulls ?: return null
        val repo = execution.getVariable(Vars.REPO) as? String ?: return null
        val issue = execution.issueOrNull() ?: return null
        val branch = "smith/issue-$issue"
        return git.aheadBundle(repo, branch, pulls.defaultBranch(repo))
            ?.also { log.info("run {}: done with no commits; {} already holds the ticket's commits, taken for review", execution.processInstanceId, branch) }
    }

    private fun enforce(problems: List<String>) =
        check(problems.isEmpty()) { "the smith's result breaks the rules: ${problems.joinToString("; ")}" }

    fun read(execution: DelegateExecution, bytes: ByteArray): WorkRead {
        val work = WorkResult.parse(bytes)
        val findings = Brief.findings(execution.getVariable(Vars.FINDINGS) as? ByteArray)
        // A `done` that committed nothing on a branch that already holds the ticket's commits (a retry, a
        // later round): the pushed head is bundled as this round's, so it is pushed (a no-op) and reviewed (#1722).
        if (execution.getVariable(Vars.BUNDLE) == null && work.outcome == "done" && !work.ciNotMine(findings.filter { it.review != ReadResultDelegate.RULES }))
            pushedAhead(execution)?.let { execution.setVariable(Vars.BUNDLE, Variables.byteArrayValue(it)) }
        val hasBundle = execution.getVariable(Vars.BUNDLE) != null
        // A rules finding is the engine's word on the last result, not a change to answer: the rules are checked without it.
        // A split is only a child's: its parent is the split feature its part line names.
        val parent = (execution.getVariable(Vars.TICKET) as? ByteArray)?.let { Brief.parent(Brief.ticket(it).body) }
        val problems = work.problems(findings.filter { it.review != ReadResultDelegate.RULES }, hasBundle, parent, splitRules)
        if (problems.isNotEmpty()) return WorkRead.Refused(findings, problems)
        execution.setVariable(Vars.RULES_REJECTED, null)
        if (findings.any { it.review == ReadResultDelegate.RULES }) findings.filter { it.review != ReadResultDelegate.RULES }.let {
            execution.setVariable(Vars.FINDINGS, if (it.isEmpty()) null else Variables.byteArrayValue(Brief.findingsBytes(it)))
        }
        // No commits and every engine-check finding declined as not this change's: the check is re-run on the
        // same commits (`open/update pr`), which keep the last round's bundle for the reviews after it.
        val notMine = !hasBundle && work.ciNotMine(findings.filter { it.review != ReadResultDelegate.RULES })
        val streak = if (notMine) ((execution.getVariable(Vars.CI_NOT_MINE_ROUNDS) as? Number)?.toInt() ?: 0) + 1 else 0
        if (notMine) enforce(listOfNotNull(
            ("$CI_NOT_MINE_CAP ${Vars.CI_NOT_MINE_MAX} rounds in a row; the check keeps " +
                "failing on the same commits, so a person should look at it").takeIf { streak > Vars.CI_NOT_MINE_MAX },
            "declined the engine check's failure, but there is no pull request whose check to re-run"
                .takeIf { execution.getVariable(Vars.PR) == null },
        ))
        // A ticket's first commit may have no kept bundle (an incident's rerun drops it): `open/update pr` then
        // bundles the pushed head again for the reviews (#1537).
        val previous = execution.getVariable(Vars.PREVIOUS_BUNDLE) as? ByteArray
        if (notMine && previous != null) execution.setVariable(Vars.BUNDLE, Variables.byteArrayValue(previous))
        execution.setVariable(Vars.CI_NOT_MINE, notMine)
        execution.setVariable(Vars.CI_NOT_MINE_ROUNDS, streak)
        execution.setVariable(Vars.WORK, Variables.byteArrayValue(bytes))
        workListeners.forEach { listener ->
            runCatching { listener.accepted(execution, work) }
                .onFailure { log.warn("run {}: {} not told of the work result: {}", execution.processInstanceId, listener::class.simpleName, it.message) }
        }
        return WorkRead.Accepted(work.outcome)
    }
}

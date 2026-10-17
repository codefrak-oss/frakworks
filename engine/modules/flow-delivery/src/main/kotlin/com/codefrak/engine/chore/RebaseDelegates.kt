package com.codefrak.engine.chore

import com.codefrak.engine.ci.ConflictRounds
import com.codefrak.engine.github.GitHubPulls
import com.codefrak.engine.github.GitPush
import com.codefrak.engine.github.MergeResult
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.repo
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.operaton.bpm.engine.variable.Variables
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * `task-rebase-pr` in bpmn/chore.bpmn, bug.bpmn and feature.bpmn, between an approved review and `merge pr`: merges the default
 * branch into the PR's branch, never pushing (engine/README.md, "Chore"). A conflict is met earlier too, before
 * the engine check waits, by `CiCheckConflictDelegate` (a PR GitHub cannot merge gets no check run). Sets `rebase` for the gateway:
 * `current` goes on to the merge, and so does a branch that is behind but merges cleanly; after GitHub refused the merge
 * with its 405, `clean` leaves the merge's bundle in `bundle` and its `merge` entry for
 * `open/update pr` to push, then the engine check and the security review of the pushed merge; `conflict` sends a `rebase-<n>-1` finding back to the work, and
 * the third conflict fails the job, until a retry grants more rounds (`IncidentRetrier`). Bound by `${rebasePr}`.
 */
@Component("rebasePr")
class RebasePrDelegate(private val git: GitPush, private val pulls: GitHubPulls) : JavaDelegate {
    private val log = LoggerFactory.getLogger(RebasePrDelegate::class.java)

    override fun execute(execution: DelegateExecution) {
        val repo = execution.repo()
        val branch = execution.getVariable(Vars.BRANCH) as? String ?: error("no branch to bring up to date")
        val default = pulls.defaultBranch(repo)
        val fromConflict = execution.getVariable(Vars.MERGE_ERROR) != null
        execution.setVariable(Vars.MERGE_ERROR, null)
        when (val result = git.mergeDefault(repo, branch, default)) {
            is MergeResult.Current -> {
                // GitHub said it conflicts, git says the branch has the default branch: merge pr and this step would loop.
                check(!fromConflict) { "GitHub refused the merge for conflicts, but $branch already contains $default" }
                ConflictRounds.resolved(execution)
                execution.setVariable(Vars.REBASE, "current")
            }
            // Behind but merging cleanly: GitHub squash-merges a PR that is not up to date, and its check already ran on
            // the merge with the default branch as of the push. A catch-up round (push, check, both reviews) here only
            // chased a default branch that moved again before it ended, while many PRs merged (2026-10-06). Merge as is;
            // the default branch's own build runs the tests on the result. After GitHub's 405 the catch-up still runs.
            is MergeResult.Clean if !fromConflict -> {
                ConflictRounds.resolved(execution)
                execution.setVariable(Vars.REBASE, "current")
            }
            is MergeResult.Clean -> {
                ConflictRounds.resolved(execution)
                execution.setVariable(Vars.BUNDLE, Variables.byteArrayValue(result.bundle))
                execution.setVariable(Vars.MERGE, Variables.byteArrayValue(Brief.mergeBytes(default, result.oldHead, result.merge)))
                execution.setVariable(Vars.REBASE, "clean")
            }
            is MergeResult.Conflict -> {
                ConflictRounds.recordConflict(execution, default, result.files)
                execution.setVariable(Vars.REBASE, "conflict")
            }
        }
        log.info("{} {}: {} against {}: {}", execution.flow(), execution.processInstanceId, branch, default, execution.getVariable(Vars.REBASE))
    }
}

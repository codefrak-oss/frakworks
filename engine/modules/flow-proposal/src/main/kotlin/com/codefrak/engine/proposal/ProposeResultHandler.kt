package com.codefrak.engine.proposal

import com.codefrak.engine.github.GitHubPulls
import com.codefrak.engine.github.GitPush
import com.codefrak.engine.smith.ResultHandler
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.issue
import com.codefrak.engine.smith.repo
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.springframework.stereotype.Component
import org.operaton.bpm.engine.variable.Variables

/**
 * A propose run's result (`read result`, [ResultHandler]): its bundle is read through [git] (nothing is pushed) to
 * check what it changes.
 */
@Component
class ProposeResultHandler(private val git: GitPush, private val pulls: GitHubPulls) : ResultHandler {
    override val modes = setOf("propose")

    override fun handle(execution: DelegateExecution, mode: String, bytes: ByteArray, enforce: (List<String>) -> Unit): String {
        val proposal = ProposeResult.parse(bytes)
        enforce(proposal.problems())
        if (proposal.outcome == "drafted") {
            val bundle = execution.getVariable(Vars.BUNDLE) as? ByteArray
            val changes = bundle?.let {
                val repo = execution.repo()
                git.proposalChanges(repo, branch(execution), pulls.defaultBranch(repo), it, proposal.path!!, proposal.pitch)
            }
            enforce(proposal.changeProblems(changes))
        }
        execution.setVariable(Vars.PROPOSAL, Variables.byteArrayValue(bytes))
        // As confirm mode does a design's: the questions the Smith is not sure enough to assume go to the user before review.
        return if (proposal.outcome == "drafted" && proposal.userQuestion() != null) "ask" else proposal.outcome
    }

    /** The branch a run's bundle goes to: the flow's, or the ticket's `smith/issue-<n>` before the first push. */
    private fun branch(execution: DelegateExecution) =
        execution.getVariable(Vars.BRANCH) as? String ?: "smith/issue-${execution.issue()}"
}

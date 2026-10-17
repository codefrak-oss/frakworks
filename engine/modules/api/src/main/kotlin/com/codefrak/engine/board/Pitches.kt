package com.codefrak.engine.board

import com.codefrak.engine.camunda.variablesOf
import com.codefrak.engine.proposal.ProposeResult
import com.codefrak.engine.repos.HandledIssues
import com.codefrak.engine.smith.Vars
import org.operaton.bpm.engine.HistoryService
import org.springframework.stereotype.Component

/** A proposal ticket's drafted doc and pitch page, the branch they were committed on, and its PR. */
data class ProposalDraft(val path: String, val pitch: String?, val branch: String, val prUrl: String)

/**
 * Finds a ticket's proposal flow and what its last propose Smith drafted, from the flow's process
 * variables (running or ended): the stored [ProposeResult], `branch` and `prUrl`. Read-only, no GitHub call.
 */
@Component
class Pitches(private val handled: HandledIssues, private val history: HistoryService) {
    companion object {
        const val FLOW = "proposal"
        /** A doc or page directly in docs/proposals/ with a plain slug, safe to put in a contents API path as it is. */
        private val SAFE = Regex("""^docs/proposals/[A-Za-z0-9._-]+\.(md|html)$""")

        /** The stored proposal result among a flow's variables, or null when there is none or it does not parse. */
        fun proposal(variables: Map<String, Any?>): ProposeResult? =
            (variables[Vars.PROPOSAL] as? ByteArray)?.let { runCatching { ProposeResult.parse(it) }.getOrNull() }

        /** The pitch page a proposal flow's stored result names, for [FlowView.pitch]; null for any other flow. */
        fun pitchOf(flow: String, variables: Map<String, Any?>): String? =
            if (flow == FLOW) proposal(variables)?.pitch?.takeIf { it.isNotBlank() } else null
    }

    /**
     * The draft of `repo`#`n`'s latest proposal flow; null when the ticket has none, its propose Smith drafted
     * nothing (or only asked), or no PR was opened for it.
     */
    fun draft(repo: String, n: Long): ProposalDraft? {
        val row = handled.list(repo).lastOrNull { it.issue == n && it.flow == FLOW && it.processInstance != null } ?: return null
        val variables = history.variablesOf(row.processInstance!!)
        val result = proposal(variables)?.takeIf { it.outcome == "drafted" } ?: return null
        val path = result.path?.takeIf { SAFE.matches(it) } ?: return null
        val prUrl = (variables[Vars.PR_URL] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val branch = (variables[Vars.BRANCH] as? String)?.takeIf { it.isNotBlank() } ?: "smith/issue-$n"
        return ProposalDraft(path, result.pitch?.takeIf { SAFE.matches(it) }, branch, prUrl)
    }
}

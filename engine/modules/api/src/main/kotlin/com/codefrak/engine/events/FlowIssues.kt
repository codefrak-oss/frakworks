package com.codefrak.engine.events

import com.codefrak.engine.camunda.variablesOf
import com.codefrak.engine.smith.Vars
import org.operaton.bpm.engine.HistoryService
import org.springframework.stereotype.Component

/**
 * The one resolver of a root instance's issue (engine/README.md, "Events"): `repo` and `issue` from its
 * variables, else the issue in its `issueUrl` or `ticketUrl` (an intake's), as `Needs.issueOf` reads them. From
 * the history, so an ended root resolves too. Null for a root with neither (a test process), which publishes nothing.
 */
@Component
class FlowIssues(private val history: HistoryService) : FlowIssueLookup {
    companion object {
        private val ISSUE_URL = Regex("""https://github\.com/([^/]+/[^/]+)/issues/(\d+)""")

        /**
         * The ticket a flow's variables are about: the first [keys] pair both present in [vars], else the repo and
         * number found in `issueUrl` or `ticketUrl` by the unanchored [ISSUE_URL] search, else null.
         */
        fun ticketIn(vars: Map<String, Any?>, keys: List<Pair<String, String>> = listOf(Vars.REPO to Vars.ISSUE)): Pair<String, Long>? {
            for ((r, i) in keys) {
                val repo = vars[r] as? String
                val issue = (vars[i] as? Number)?.toLong()
                if (repo != null && issue != null) return repo to issue
            }
            return sequenceOf(vars["issueUrl"], vars["ticketUrl"]).filterIsInstance<String>().mapNotNull(ISSUE_URL::find).firstOrNull()
                ?.let { it.groupValues[1] to it.groupValues[2].toLong() }
        }
    }

    override fun of(root: String): FlowIssue? {
        val instance = history.createHistoricProcessInstanceQuery().processInstanceId(root).singleResult() ?: return null
        val vars = history.variablesOf(root)
        val (r, n) = ticketIn(vars) ?: return null
        return FlowIssue(r, n, instance.processDefinitionKey, vars[Vars.PR_URL] as? String, vars["outcome"] as? String)
    }
}

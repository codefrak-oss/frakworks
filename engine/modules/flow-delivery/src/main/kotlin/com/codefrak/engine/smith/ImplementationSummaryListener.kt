package com.codefrak.engine.smith

import com.codefrak.engine.repos.ImplementationSummaries
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.springframework.stereotype.Component

/** Keeps an accepted `done` result's `implementationSummary` as its ticket's, latest round wins (engine/README.md, "Implementation summary"). */
@Component
class ImplementationSummaryListener(private val summaries: ImplementationSummaries) : WorkResultListener {
    override fun accepted(execution: DelegateExecution, work: WorkResult) {
        val summary = work.implementationSummary?.trim()?.takeIf { work.outcome == "done" && it.isNotEmpty() } ?: return
        val repo = execution.getVariable(Vars.REPO) as? String ?: return
        val issue = execution.issueOrNull() ?: return
        summaries.save(repo, issue, summary)
    }
}

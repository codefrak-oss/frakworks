package com.codefrak.engine.proposal

import com.codefrak.engine.github.GitPush
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.issue
import com.codefrak.engine.smith.repo
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * `task-log-decision`: one engine commit on the PR's branch that adds the decision to the doc's
 * `Decision log:` (the last item, `- <yyyy-mm-dd> <status> (#<n>) — <why>`, plus `; build filed as #<m>`)
 * and sets its `Status:`, so the doc lands on the default branch with its decision. Bound by `${logDecision}`.
 */
@Component("logDecision")
class LogDecisionDelegate(private val git: GitPush) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val repo = execution.repo(); val issue = execution.issue(); val path = execution.path()
        val branch = execution.getVariable(Vars.BRANCH) as? String ?: "smith/issue-$issue"
        val status = DecisionVerb.status(execution.getVariable(Vars.DECISION) as String)
        val line = logLine(LocalDate.now(), status, issue, execution.getVariable(Vars.DECISION_WHY) as? String,
            (execution.getVariable(Vars.BUILT_TICKET) as? Number)?.toLong())
        git.commitEdit(repo, branch, path, "Decision log: $status (#$issue)") { edit(it, status, line) }
    }

    companion object {
        fun logLine(date: LocalDate, status: String, issue: Long, why: String?, built: Long?): String =
            "- $date $status (#$issue)" + why?.replace(Regex("""\s+"""), " ")?.let { " — $it" }.orEmpty() +
                built?.let { "; build filed as #$it" }.orEmpty()

        private val STATUS = Regex("""^\s*Status:.*$""", RegexOption.IGNORE_CASE)

        /**
         * `doc` with `line` as the last item of its decision log and `Status: <status>`. A doc with no
         * `Status:` gets one under its title, and one with no `Decision log:` gets it under the status.
         * A line already in the log is not added again, so a retried step changes nothing.
         */
        fun edit(doc: String, status: String, line: String): String {
            val lines = doc.lines().toMutableList()
            var at = lines.indexOfFirst { STATUS.matches(it) }
            if (at < 0) { at = if (lines.isEmpty()) 0 else 1; lines.add(at, "") }
            lines[at] = "Status: $status"
            var header = lines.indexOfFirst { ProposeResult.LOG_HEADER.matches(it) }
            if (header < 0) { header = at + 1; lines.add(header, "Decision log:") }
            var end = header + 1
            while (end < lines.size && ProposeResult.LIST_ITEM.containsMatchIn(lines[end])) end++
            if (lines.subList(header + 1, end).none { it.trim() == line }) lines.add(end, line)
            return lines.joinToString("\n")
        }
    }
}

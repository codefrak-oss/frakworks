package com.codefrak.engine.postdeploy

import com.codefrak.engine.intake.TicketFiler
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.issue
import com.codefrak.engine.smith.repo
import com.codefrak.engine.smith.tier
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * `${fileCheckBug}`: after a `failed` check run, files the result's bug in the ticket's repo through [TicketFiler]:
 * the bug's title and body, then a Context naming the original ticket, its PR URL and `mergeSha`, with the engine's
 * trailer at the original's tier, `p2`, asked by the original's asker (`askedBy`); origin `check of #<n>`. Sets
 * `checkBugTicket`. The original ticket is never reopened: the bug is new work (bots/smith.md, "The check Smith").
 */
@Component("fileCheckBug")
class FileCheckBugDelegate(private val filer: TicketFiler) : JavaDelegate {
    private val log = LoggerFactory.getLogger(FileCheckBugDelegate::class.java)

    override fun execute(execution: DelegateExecution) {
        val repo = execution.repo(); val issue = execution.issue()
        val result = CheckResult.parse(execution.getVariable(Vars.OUTPUT) as? ByteArray ?: error("no check result to file a bug from"))
        val bug = result.bug ?: error("the check result's outcome is '${result.outcome}', not '${CheckResult.FAILED}': nothing to file")
        val draft = draft(bug, issue, execution.getVariable(Vars.PR_URL) as? String, execution.getVariable(Vars.MERGE_SHA) as? String)
        val body = filer.body(draft, "check of #$issue", execution.tier(), "p2", by = execution.getVariable(Vars.ASKED_BY) as? String)
        val created = filer.file(repo, bug.title.trim(), body, "bug", null)
        execution.setVariable(Vars.CHECK_BUG_TICKET, created.number)
        log.info("check of {}#{}: filed bug {}", repo, issue, created.url)
    }

    companion object {
        /** The bug's body before the trailer: the Smith's, then the Context the engine adds. */
        fun draft(bug: CheckBug, issue: Long, prUrl: String?, mergeSha: String?): String = buildString {
            append(bug.body.trimEnd()).append("\n\n## Context\n\n")
            append("- Found by the post-deployment check of #").append(issue)
            prUrl?.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(")") }
            mergeSha?.takeIf { it.isNotBlank() }?.let { append(", merged as `").append(it).append("`") }
            append(".\n- Filed by the engine from the check Smith's result; #").append(issue).append(" stays closed.\n")
        }
    }
}

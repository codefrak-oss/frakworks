package com.codefrak.engine.mcp

import com.codefrak.engine.chore.ProcessFlowStarter
import com.codefrak.engine.trigger.IssueApprovals
import com.codefrak.engine.workitems.WorkItemStore

/**
 * approve (#799) and reauthorize (#850), on [FrakworksMcp] for First mate only: authorizing an intake's ask item or a
 * filed ticket, and re-authorizing one stalled on a blocker that closed without a merge, as the caller's login,
 * in-process, not by a label.
 */
class ApprovalTool(private val engine: FrakworksEngine, private val workItems: WorkItemStore? = null) {
    companion object {
        val APPROVE = McpTool("approve",
            "Authorize a ticket as the user, when they say \"approve #N\": every `authorize` row in read_needs. An intake's " +
                "ask item waiting for a trusted login's start goes on to refining; a filed ticket (research, chore, bug, feature " +
                "or proposal, with no `authorized` label yet) starts, waits on its blockers or is refused as an " +
                "`authorized` label from the user would. Authorized by the user's login (refused unless it is " +
                "a trusted login); the engine adds the label and a comment as the record.",
            obj(
                "ticket" to prop("integer", "The ticket's number (an intake's ask item or a filed ticket), e.g. 100012.", "minimum" to 1),
                "repo" to prop("string", "owner/name: the target repo the ticket is in."),
                required = listOf("ticket", "repo"),
            ))

        val REAUTHORIZE = McpTool("reauthorize",
            "Re-authorize a ticket as the user, when they say \"re-authorize #N\": a feature, chore or bug waiting on " +
                "a blocker that closed without a merge (read_needs kind `reauthorize`) goes on as if the user had " +
                "re-labelled it `authorized`: a split feature parked on its child resumes with the child treated as " +
                "satisfied, a deferred start starts, authorized by the user's login (refused unless it is a " +
                "trusted login). The engine comments on the issue as the record. Not for an intake: that is approve.",
            obj(
                "ticket" to prop("integer", "The ticket's issue number, e.g. 724.", "minimum" to 1),
                "repo" to prop("string", "owner/name: the target repo the ticket is in."),
                required = listOf("ticket", "repo"),
            ))
    }

    /**
     * Authorizes an intake's ask item as the caller's login ([FrakworksEngine.approve]): in-process, since a label
     * written with the repo's token would carry the token's login, which the scan reads as the approver.
     */
    fun approve(caller: McpCaller.Nobody, args: Map<String, Any?>): McpToolResult {
        (refuseOthers("approve", args, listOf("ticket", "repo")) ?: badFilter(args))?.let { return it }
        val n = FrakworksMcp.ticketOf(args["ticket"]) ?: return noTicket(args)
        val by = login(caller) ?: return noLogin("approve")
        val asked = args["repo"] as? String ?: return noRepo()
        val repo = engine.targetRepo(asked) ?: return McpToolResult.error("$asked is not a target repo")
        val kind = workItems?.forTicket(repo, n)?.kind
        if (kind != null && kind in ProcessFlowStarter.FLOWS) return approveTicket(repo, n, by)
        // POST /intake's own refusal for `authorize: true`.
        if (!engine.trusted(by)) return McpToolResult.error("@$by is not a trusted login")
        val done = engine.approve(repo, n, by)
            ?: return McpToolResult.error("no intake on $repo#$n waits for an authorization (already authorized, refined or closed, or not an intake's ask); nothing was changed")
        return McpToolResult.of(linkedMapOf<String, Any?>("ok" to true, "ticket" to n, "repo" to repo, "intake_id" to done.instance, "authorized_by" to by)
            .apply { if (done.notes.isNotEmpty()) put("notes", done.notes) })
    }

    /** [approve] for a filed ticket ([FrakworksEngine.approveTicket]): the scan's own start, deferral or refusal, as the caller's login. */
    private fun approveTicket(repo: String, n: Long, by: String): McpToolResult =
        when (val done = engine.approveTicket(repo, n, by)) {
            is IssueApprovals.TicketApproved.Refused -> McpToolResult.error("${done.why}; nothing was changed")
            is IssueApprovals.TicketApproved.Done -> McpToolResult.of(linkedMapOf<String, Any?>("ok" to true, "ticket" to n, "repo" to repo,
                "flow" to done.flow, "outcome" to done.outcome, "detail" to done.detail, "authorized_by" to by)
                .apply { if (done.notes.isNotEmpty()) put("notes", done.notes) })
        }

    /**
     * Re-authorizes a ticket stalled on a blocker closed without a merge as the caller's login ([FrakworksEngine.reauthorize]):
     * in-process, as [approve], since the issue already carries `authorized` and a label from the token is not the user's.
     */
    fun reauthorize(caller: McpCaller.Nobody, args: Map<String, Any?>): McpToolResult {
        (refuseOthers("reauthorize", args, listOf("ticket", "repo")) ?: badFilter(args))?.let { return it }
        val n = FrakworksMcp.ticketOf(args["ticket"]) ?: return noTicket(args)
        val by = login(caller) ?: return noLogin("reauthorize")
        val asked = args["repo"] as? String ?: return noRepo()
        val repo = engine.targetRepo(asked) ?: return McpToolResult.error("$asked is not a target repo")
        return when (val done = engine.reauthorize(repo, n, by)) {
            is IssueApprovals.Reauthorized.Refused -> McpToolResult.error("${done.why}; nothing was changed")
            is IssueApprovals.Reauthorized.Done -> McpToolResult.of(linkedMapOf<String, Any?>("ok" to true, "ticket" to n, "repo" to repo,
                "instance" to done.instance, "authorized_by" to by, "outcome" to done.outcome)
                .apply { if (done.notes.isNotEmpty()) put("notes", done.notes) })
        }
    }
}

package com.codefrak.engine.mcp

import com.codefrak.engine.workitems.WorkItem
import com.codefrak.engine.workitems.WorkItemEntry
import com.codefrak.engine.workitems.WorkItemStore

/**
 * close_ticket (#1170) and comment_ticket (#1627), on [FrakworksMcp] for First mate only: the two tools that write
 * to a ticket's engine work item ([WorkItemStore]) as the caller's login, never to a GitHub issue.
 */
class TicketWriteTools(private val engine: FrakworksEngine, private val workItems: WorkItemStore?) {
    companion object {
        /** close_ticket's outcomes, the issue state reasons [FrakworksEngine.close] takes; a call names one, there is no default. */
        val OUTCOMES = listOf("completed", "not_planned")

        val TOOL: List<McpTool> = listOf(
            McpTool("close_ticket",
                "Close a ticket that has no running flow, as the user, with the outcome they name; there is no default. " +
                    "`outcome` is `completed` for a ticket whose work is done (the work item ends done), `not_planned` when they say " +
                    "\"close #N\" or \"trash #N\" (it ends cancelled). The engine records the login, the outcome and the reason on the work item, then closes it, and drops a " +
                    "deferred start (refused unless they are a trusted login). A ticket whose flow runs " +
                    "is refused: cancel it with act_on_incident (frakworks-engine-diagnostics), which stops the flow and closes the ticket.",
                obj(
                    "ticket" to prop("integer", "The ticket number, e.g. 100027.", "minimum" to 1),
                    "repo" to prop("string", "owner/name: the target repo the ticket is in."),
                    "reason" to prop("string", "Why it is closed, in the user's words; goes on the work item's conversation."),
                    "outcome" to prop("string", "How it is closed, always named: `completed` (its work is done) or `not_planned` (\"close #N\", \"trash #N\").",
                        "enum" to OUTCOMES),
                    required = listOf("ticket", "repo", "reason", "outcome"),
                )),
            McpTool("comment_ticket",
                "Post a comment on a ticket for the user, when they ask you to reply on or tell a ticket something: " +
                    "the engine appends `text` to the work item's conversation as the user's login, and answers the entry's id. " +
                    "Only a trusted login may comment. Refused for a number the engine holds no work item for, and for a closed ticket unless `closed` is true.",
                obj(
                    "repo" to prop("string", "owner/name: the target repo the ticket is in."),
                    "ticket" to prop("integer", "The ticket number, e.g. 100320.", "minimum" to 1),
                    "text" to prop("string", "The comment, markdown, as the user wants it posted."),
                    "closed" to prop("boolean", "Comment even though the ticket is closed; default false."),
                    required = listOf("repo", "ticket", "text"),
                )),
        )
    }

    fun call(tool: String, args: Map<String, Any?>, caller: McpCaller.Nobody): McpToolResult = when (tool) {
        "close_ticket" -> closeTicket(caller, args)
        else -> commentTicket(caller, args)
    }

    /** Work item `repo`#`n` from the store, or the refusal naming that the engine holds none. */
    private fun item(repo: String, n: Long, nothing: String): Pair<WorkItem?, McpToolResult?> {
        val item = workItems?.forTicket(repo, n)
            ?: return null to McpToolResult.error("$repo#$n is not a ticket: the engine holds no work item with that number; $nothing")
        return item to null
    }

    /**
     * Closes a work item with no running flow as the `outcome` the caller names, `completed` (done) or `not_planned`
     * (cancelled), as the caller's login: an entry naming the outcome and the reason, then the close ([FrakworksEngine.close],
     * the store's close). A missing or unknown outcome is refused. Every check comes before the first write; a write that fails is a note.
     */
    private fun closeTicket(caller: McpCaller.Nobody, args: Map<String, Any?>): McpToolResult {
        (refuseOthers("close_ticket", args, listOf("ticket", "repo", "reason", "outcome")) ?: badFilter(args))?.let { return it }
        val n = FrakworksMcp.ticketOf(args["ticket"]) ?: return noTicket(args)
        val reason = (args["reason"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return McpToolResult.error("\"reason\" is required: why the ticket is closed, for its comment")
        val outcome = (args["outcome"] as? String)?.takeIf { it in OUTCOMES }
            ?: return McpToolResult.error("\"outcome\" is required: \"completed\" or \"not_planned\", not ${json(args["outcome"])}; nothing was changed")
        val by = login(caller) ?: return noLogin("close")
        val asked = args["repo"] as? String ?: return noRepo()
        val repo = engine.targetRepo(asked) ?: return McpToolResult.error("$asked is not a target repo")
        val (item, missing) = item(repo, n, "nothing was changed")
        if (item == null) return missing!!
        if (!item.open) return McpToolResult.error("$repo#$n is already closed; nothing was changed")
        if (!engine.trusted(by)) return McpToolResult.error("@$by is not a trusted login")
        val running = engine.runningFlows(repo, n)
        if (running.isNotEmpty()) return McpToolResult.error("a flow is running on $repo#$n (${running.joinToString(", ")}): cancel it with " +
            "act_on_incident (frakworks-engine-diagnostics) instead, which stops it and closes the ticket; nothing was changed")
        val deferred = engine.deferred(repo, n)
        val notes = mutableListOf<String>()
        val comment = "Closed as ${outcome.replace('_', ' ')} by @$by through First mate: $reason" +
            if (deferred) "\n\nIts deferred start (waiting on a blocker) was dropped." else ""
        try { workItems!!.appendEntry(item.id, WorkItemEntry.CONVERSATION, by, comment, true) } catch (e: Exception) { notes += "the comment failed: ${e.message}" }
        val closed = try { engine.close(repo, n, outcome); true } catch (e: Exception) { notes += "the close failed: ${e.message}"; false }
        if (deferred && closed) {
            try { engine.dropDeferred(repo, n) } catch (e: Exception) { notes += "the deferred start was not removed: ${e.message}" }
        }
        return McpToolResult.of(linkedMapOf<String, Any?>("ok" to closed, "ticket" to n, "repo" to repo, "closed_by" to by, "state_reason" to outcome)
            .apply { if (notes.isNotEmpty()) put("notes", notes) })
    }

    /**
     * Appends the user's comment to a work item's conversation, authored by the caller's login. Every check comes
     * before the write: a blank text, no login, a login that is not trusted, a repo that is no target repo, a number with no work item, a closed
     * ticket unless `closed` is true.
     */
    private fun commentTicket(caller: McpCaller.Nobody, args: Map<String, Any?>): McpToolResult {
        (refuseOthers("comment_ticket", args, listOf("repo", "ticket", "text", "closed")) ?: badFilter(args))?.let { return it }
        val n = FrakworksMcp.ticketOf(args["ticket"]) ?: return noTicket(args)
        val text = (args["text"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return McpToolResult.error("\"text\" is required: the comment to post; nothing was posted")
        val closedOk = when (val c = args["closed"]) {
            null -> false
            is Boolean -> c
            else -> return McpToolResult.error("not a boolean: \"closed\" is ${json(c)}")
        }
        val by = login(caller) ?: return noLogin("comment")
        if (!engine.trusted(by)) return McpToolResult.error("@$by is not a trusted login; nothing was posted")
        val asked = args["repo"] as? String ?: return noRepo()
        val repo = engine.targetRepo(asked) ?: return McpToolResult.error(
            "$asked is not a target repo (the target repos: ${engine.repos().joinToString(", ") { it.fullName }}); nothing was posted")
        val (item, missing) = item(repo, n, "nothing was posted")
        if (item == null) return missing!!
        if (!item.open && !closedOk) return McpToolResult.error("$repo#$n is closed; pass closed: true to comment on it anyway. Nothing was posted")
        val entry = try { workItems!!.appendEntry(item.id, WorkItemEntry.CONVERSATION, by, text, true) }
            catch (e: Exception) { return McpToolResult.error("the comment failed: ${e.message}") }
        return McpToolResult.of(linkedMapOf<String, Any?>("ok" to true, "ticket" to n, "repo" to repo, "entry" to entry.id, "posted_for" to by))
    }
}

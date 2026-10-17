package com.codefrak.engine.mcp

import com.codefrak.engine.intake.TicketAmendController

/**
 * cancel_intake and amend_intake (#1173) cancel an intake, or add the user's follow-up to one still refining, as the
 * caller's login ([FrakworksEngine.cancelIntake], [FrakworksEngine.amendIntake]); amend_ticket (#1362) adds the
 * user's late information to a filed ticket whose flow runs and loops it back to the work
 * ([FrakworksEngine.amendTicket]). On [FrakworksMcp] for First mate only, built like [TicketWriteTools].
 */
class IntakeTools(private val engine: FrakworksEngine) {
    companion object {
        /** First mate's intake tools: a Smith's call to one is refused naming its run. */
        val INTAKE_TOOLS = listOf("cancel_intake", "amend_intake")
        /** amend_ticket: a Smith's call is refused naming its run. */
        const val AMEND_TICKET = "amend_ticket"

        val TOOL: List<McpTool> = listOf(
            McpTool("cancel_intake",
                "Cancel an intake that is still running, as the user (DELETE /intake/<id>): its Smith is stopped, a pending " +
                    "question withdrawn, and the ask item closed as not planned with a comment naming the user and the reason. " +
                    "Only when the user asks to drop the ask.",
                obj(
                    "intake_id" to prop("string", "The intake's id (submit_ask's intakeId, or a /needs row's actionId)."),
                    "reason" to prop("string", "Why it is cancelled, in the user's words; said on the ask item."),
                    required = listOf("intake_id", "reason"),
                )),
            McpTool("amend_intake",
                "Add the user's follow-up to an intake that has not filed its ticket yet, so the refiner reads it: the user's " +
                    "own words verbatim. A refine run in flight is restarted with it; a question that waits keeps waiting. " +
                    "Use this, never a second submit_ask, when the user adds to an ask still refining. Refused once the " +
                    "intake filed its ticket: comment on the ticket instead.",
                obj(
                    "intake_id" to prop("string", "The intake's id (submit_ask's intakeId, or a /needs row's actionId)."),
                    "text" to prop("string", "The user's follow-up, verbatim, unchanged."),
                    required = listOf("intake_id", "text"),
                )),
            McpTool(AMEND_TICKET,
                "Add the user's late information to a ticket already filed and being worked: the user's own words " +
                    "verbatim. The engine appends it to the issue's `## Amendments` section, stops the Smith run in flight " +
                    "and loops the flow back to the work (or the reproduce or design it was at) on the same PR; a question " +
                    "that waits keeps waiting, a split feature's parent takes it at its next replan. Use this, never a second " +
                    "submit_ask and never a cancel, when the user adds to a ticket already filed. Refused once the PR merged " +
                    "or the issue closed (file a follow-up), for an intake (amend_intake) and while the flow is between steps.",
                obj(
                    "ticket" to prop("integer", "The ticket's issue number, e.g. 1362.", "minimum" to 1),
                    "repo" to prop("string", "owner/name: the target repo the ticket is in."),
                    "text" to prop("string", "The user's addition, verbatim, unchanged."),
                    required = listOf("ticket", "repo", "text"),
                )),
        )

        private val INSTANCE_ID = Regex("^[0-9a-f][0-9a-f-]{7,63}$", RegexOption.IGNORE_CASE)
    }

    fun call(tool: String, args: Map<String, Any?>, caller: McpCaller.Nobody): McpToolResult = when (tool) {
        "cancel_intake" -> cancelIntake(caller, args)
        "amend_intake" -> amendIntake(caller, args)
        else -> amendTicket(caller, args)
    }

    /** DELETE /intake/{id} as the caller's login: the endpoint's 400, 403 and 404 texts come back as tool errors. */
    private fun cancelIntake(caller: McpCaller.Nobody, args: Map<String, Any?>): McpToolResult {
        refuseOthers("cancel_intake", args, listOf("intake_id", "reason"))?.let { return it }
        val id = args["intake_id"]
        if (id !is String || !INSTANCE_ID.matches(id)) return McpToolResult.error("not an intake id: ${json(id)}")
        val reason = (args["reason"] as? String)?.trim().orEmpty()
        if (reason.isEmpty()) return McpToolResult.error("a cancel needs a reason")
        val by = login(caller) ?: return noLogin("cancel")
        val failed = engine.cancelIntake(id, by, reason, caller.request)
        return McpToolResult.of(linkedMapOf("ok" to true, "intakeId" to id, "failedWrites" to failed))
    }

    /** The user's follow-up to an intake not filed yet ([FrakworksEngine.amendIntake]), as the caller's login. */
    private fun amendIntake(caller: McpCaller.Nobody, args: Map<String, Any?>): McpToolResult {
        refuseOthers("amend_intake", args, listOf("intake_id", "text"))?.let { return it }
        val id = args["intake_id"]
        if (id !is String || !INSTANCE_ID.matches(id)) return McpToolResult.error("not an intake id: ${json(id)}")
        val text = (args["text"] as? String)?.trim().orEmpty()
        if (text.isEmpty()) return McpToolResult.error("a follow-up needs some text")
        if (text.length > FrakworksMcp.MAX_ANSWER_CHARS) return McpToolResult.error("a follow-up is at most ${FrakworksMcp.MAX_ANSWER_CHARS} characters")
        val by = login(caller) ?: return noLogin("amend")
        val done = engine.amendIntake(id, text, by)
        return McpToolResult.of(linkedMapOf("ok" to true, "intakeId" to done.intakeId, "refining" to done.refining))
    }

    /** The user's late addition to a filed ticket ([FrakworksEngine.amendTicket]), as the caller's login. */
    private fun amendTicket(caller: McpCaller.Nobody, args: Map<String, Any?>): McpToolResult {
        (refuseOthers(AMEND_TICKET, args, listOf("ticket", "repo", "text")) ?: badFilter(args))?.let { return it }
        val n = FrakworksMcp.ticketOf(args["ticket"]) ?: return noTicket(args)
        val text = (args["text"] as? String)?.trim().orEmpty()
        if (text.isEmpty()) return McpToolResult.error("an amendment needs some text")
        if (text.length > FrakworksMcp.MAX_ANSWER_CHARS) return McpToolResult.error("an amendment is at most ${FrakworksMcp.MAX_ANSWER_CHARS} characters")
        val by = login(caller) ?: return noLogin("amend")
        val done = engine.amendTicket(args["repo"] as? String, n, text, by)
        return McpToolResult.of(TicketAmendController.result(done))
    }
}

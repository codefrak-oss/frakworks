package com.codefrak.engine.mcp

import com.codefrak.engine.board.Need

/**
 * read_needs (#476): the engine's open needs ([FrakworksEngine.needs]), on [FrakworksMcp] for First mate only, built
 * like [IntakeTools]. A question, acceptance or decision is decided on the HITLP server, not through the engine.
 */
class NeedsTools(private val engine: FrakworksEngine) {
    companion object {
        val READ_NEEDS = McpTool("read_needs",
            "Read-only: everything waiting on a person, from the engine's GET /needs, oldest first. Each row has " +
                "repo, number, title, kind (ask, approve, incident, insufficient-permissions, authorize, reauthorize, sighting), " +
                "wanted, since, actionId (for an ask or approve: the HITLP task id, decided on the HITLP server) and " +
                "question: an ask's full text, untruncated, null on other rows, and for: the login the row waits on (an " +
                "ask's or approve's assignee), null when it is everyone's. Read a question from here, not from " +
                "read_transcript, which cuts messages short; wanted is only its first line. " +
                "Pass `ticket` and/or `repo` to keep only that ticket's or repo's rows.",
            obj(
                "ticket" to prop("integer", "Only rows for this ticket (issue) number.", "minimum" to 1),
                "repo" to prop("string", "Only rows for this repo, owner/name."),
            ))

        val TOOLS: List<McpTool> = listOf(READ_NEEDS)
    }

    fun call(args: Map<String, Any?>): McpToolResult = readNeeds(args)

    private fun matches(row: Need, args: Map<String, Any?>) =
        (!given(args, "ticket") || row.number == FrakworksMcp.ticketOf(args["ticket"])) && (!given(args, "repo") || row.repo == args["repo"])

    private fun readNeeds(args: Map<String, Any?>): McpToolResult {
        (refuseOthers("read_needs", args, listOf("ticket", "repo")) ?: badFilter(args))?.let { return it }
        val rows = engine.needs().filter { matches(it, args) }.map(::needRow)
        return McpToolResult.of(linkedMapOf("count" to rows.size, "rows" to rows))
    }

    private fun needRow(n: Need) = linkedMapOf(
        "repo" to n.repo, "number" to n.number, "title" to n.title, "kind" to n.kind, "wanted" to n.wanted,
        "since" to iso(n.since), "actionId" to n.actionId, "question" to n.question,
        "for" to n.forLogin,
    )
}

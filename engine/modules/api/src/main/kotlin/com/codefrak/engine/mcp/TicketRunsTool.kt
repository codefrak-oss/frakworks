package com.codefrak.engine.mcp

import java.time.Instant

/**
 * read_ticket_runs (#1162), on [FrakworksMcp] for First mate only: a ticket's Smith runs, each review's findings
 * with it; read_transcript's (#352, [TranscriptTool]) `run` reads one of them. Moved out of [FrakworksMcp] (#1815).
 */
class TicketRunsTool(private val engine: FrakworksEngine) {
    companion object {
        const val READ_TICKET_RUNS = "read_ticket_runs"

        val READ_TICKET_RUNS_TOOL = McpTool(READ_TICKET_RUNS,
            "Read-only: a ticket's Smith runs, oldest first, one row per run its flows ran (refine, work, review, " +
                "security, propose, ...): runId, mode, round, account, model, effort, tier, started, ended, exitCode, " +
                "outcome, cost (USD) and turns. A review or security row also has `findings`: each finding it raised " +
                "(id, review, file, ask), empty when it approved or passed. Use it for a ticket's run history or a " +
                "proposal's review rounds; pass a row's runId to read_transcript's `run` to read that session.",
            obj(
                "ticket" to prop("integer", "The ticket (issue) number, e.g. 976.", "minimum" to 1),
                "repo" to prop("string", "owner/name: the target repo the ticket is in."),
                required = listOf("ticket", "repo"),
            ))
    }

    fun call(tool: String, args: Map<String, Any?>): McpToolResult = when (tool) {
        READ_TICKET_RUNS -> readTicketRuns(args)
        else -> McpToolResult.error("unknown tool: ${json(tool)}")
    }

    private fun readTicketRuns(args: Map<String, Any?>): McpToolResult {
        (refuseOthers(READ_TICKET_RUNS, args, listOf("ticket", "repo")) ?: badFilter(args))?.let { return it }
        val n = FrakworksMcp.ticketOf(args["ticket"]) ?: return noTicket(args)
        val asked = args["repo"] as? String ?: return noRepo()
        val repo = engine.targetRepo(asked) ?: return McpToolResult.error("$asked is not a target repo")
        val runs = engine.ticketRuns(repo, n) ?: return McpToolResult("No process instance is tied to $repo#$n: the engine has run nothing on it.")
        return McpToolResult.of(linkedMapOf("repo" to repo, "ticket" to n, "runs" to runs.map(::runRow)))
    }

    internal fun runRow(r: TicketRunRow): Map<String, Any?> {
        val step = r.run.step; val stat = r.stat
        return linkedMapOf<String, Any?>(
            "runId" to r.run.runId, "mode" to r.run.mode, "round" to r.run.round,
            "account" to stat?.account, "model" to (step.model ?: stat?.model), "effort" to (step.effort ?: stat?.effort), "tier" to stat?.tier,
            "started" to (stat?.started?.toInstant() ?: Instant.ofEpochSecond(step.start)).toString(),
            "ended" to if (step.running) null else (stat?.ended?.toInstant() ?: step.end?.let(Instant::ofEpochSecond))?.toString(),
            "exitCode" to if (step.running) null else stat?.exitCode,
            "outcome" to step.outcome, "cost" to step.cost, "turns" to step.turns,
        ).apply {
            r.run.findings?.let { f -> put("findings", f.map { linkedMapOf("id" to it.id, "review" to it.review, "file" to it.file, "ask" to it.ask).filterValues { v -> v != null } }) }
        }
    }
}

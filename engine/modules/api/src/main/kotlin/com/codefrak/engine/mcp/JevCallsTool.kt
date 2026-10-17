package com.codefrak.engine.mcp

import com.codefrak.engine.jev.JevAnswers
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.jev.JevFilter
import com.codefrak.engine.parseSince

/**
 * read_jev_calls (#2255), on [DiagnosticsMcpServer]: the `engine.jev_call` rows of any use case, newest first, with
 * `total`, the count the filters keep (what `GET /jev/calls` answers): by `repo`, `ticket`, `process_instance` (the flow
 * that made the call), `use_case`, `since` (inclusive) and `until` (exclusive). Never a request or response body, nor
 * the `account`. Read-only, for the callers [ReviewPredictionsTool] has; a Smith's `repo` defaults to its own, a login's
 * to every repo. A `ticket` needs a repo.
 */
class JevCallsTool(private val calls: JevCalls) {
    companion object {
        const val NAME = "read_jev_calls"
        private val ARGS = listOf("repo", "ticket", "process_instance", "use_case", "since", "until", "limit")

        val TOOL = McpTool(NAME,
            "Read-only: Jev calls of any use case (what GET /jev/calls answers), the engine.jev_call rows newest first: {total, " +
                "calls}. `total` is how many calls the filters keep; `calls` at most `limit` of them, each {id, calledAt, useCase, " +
                "processInstance, repo, issue, model, latencyMs, threshold, action, outcome, pick, confidence, noAnswer}, never the " +
                "request, the response or the account. Filter by `repo` (default a Smith's own repo; every repo for a login), " +
                "`ticket` (needs a repo), `process_instance` (the flow that made the call), `use_case`, `since` (inclusive) and " +
                "`until` (exclusive), ISO-8601 instants or dates: a run's calls are those between its start and its end.",
            mapOf(
                "type" to "object",
                "properties" to linkedMapOf(
                    "repo" to linkedMapOf("type" to "string", "description" to "owner/name; default a Smith's own repo, every repo for a login."),
                    "ticket" to linkedMapOf("type" to "integer", "minimum" to 1, "description" to "Only calls on this ticket (issue) number."),
                    "process_instance" to linkedMapOf("type" to "string", "description" to "Only calls this flow (process instance id) made."),
                    "use_case" to linkedMapOf("type" to "string", "description" to "Only calls of this use case, e.g. review-prediction."),
                    "since" to linkedMapOf("type" to "string", "description" to "Only calls from this instant or date on, ISO-8601."),
                    "until" to linkedMapOf("type" to "string", "description" to "Only calls before this instant or date, ISO-8601."),
                    "limit" to linkedMapOf("type" to "integer", "minimum" to 1, "maximum" to 500, "description" to "At most this many calls, default 100."),
                ),
                "additionalProperties" to false,
            ))
    }

    /** [ownRepo] is a Smith's run repo, null for a login. */
    fun call(args: Map<String, Any?>, ownRepo: String?): McpToolResult {
        val extra = args.keys.filter { it !in ARGS }
        if (extra.isNotEmpty())
            return McpToolResult.error("$NAME takes only ${ARGS.joinToString(", ") { json(it) }}, not ${extra.joinToString(", ") { json(it) }}")
        val ticketArg = args["ticket"]
        val ticket = ticketArg?.let { t ->
            (t as? Number)?.takeIf { it.toDouble() == it.toLong().toDouble() && it.toLong() >= 1 }?.toLong()
                ?: return McpToolResult.error("\"ticket\" must be an integer of at least 1, not ${json(t)}")
        }
        val given = args["repo"]
        val repo = if (given != null) {
            (given as? String)?.takeIf { REPO_FULL.matches(it) } ?: return McpToolResult.error("\"repo\" must be owner/name, not ${json(given)}")
        } else ownRepo
        if (ticket != null && repo == null) return McpToolResult.error("no repo to read ticket #$ticket in: pass \"repo\"")
        for (key in listOf("process_instance", "use_case")) args[key]?.let { v ->
            if ((v as? String).isNullOrBlank()) return McpToolResult.error("\"$key\" must be text, not ${json(v)}")
        }
        val processInstance = args["process_instance"] as String?
        val useCase = args["use_case"] as String?
        val since = args["since"]?.let { s -> (s as? String)?.let(::parseSince) ?: return McpToolResult.error("\"since\" is an ISO-8601 instant or date, not ${json(s)}") }
        val until = args["until"]?.let { s -> (s as? String)?.let(::parseSince) ?: return McpToolResult.error("\"until\" is an ISO-8601 instant or date, not ${json(s)}") }
        val limitArg = args["limit"]
        val limit = if (limitArg == null) 100 else (limitArg as? Number)
            ?.takeIf { it.toDouble() == it.toLong().toDouble() && it.toLong() in 1..500 }?.toInt()
            ?: return McpToolResult.error("\"limit\" must be an integer from 1 to 500, not ${json(limitArg)}")
        val (rows, total) = calls.list(JevFilter(useCase = useCase, repo = repo, issue = ticket, since = since, until = until,
            processInstance = processInstance), limit, 0)
        return McpToolResult.of(linkedMapOf("total" to total, "calls" to rows.map { row ->
            val (pick, confidence) = JevAnswers.summary(JevAnswers.picks(JevAnswers.parse(row.response)))
            linkedMapOf("id" to row.id, "calledAt" to row.calledAt.toString(), "useCase" to row.useCase,
                "processInstance" to row.processInstance, "repo" to row.repo, "issue" to row.issue, "model" to row.model,
                "latencyMs" to row.latencyMs, "threshold" to row.threshold, "action" to row.action, "outcome" to row.outcome,
                "pick" to pick, "confidence" to confidence, "noAnswer" to (row.response == null || row.error != null))
        }))
    }
}

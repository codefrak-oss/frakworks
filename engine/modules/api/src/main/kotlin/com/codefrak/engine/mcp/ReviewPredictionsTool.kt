package com.codefrak.engine.mcp

import com.codefrak.engine.jev.JevAnswers
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.jev.JevFilter
import com.codefrak.engine.parseSince
import com.codefrak.engine.summarize.PredictReviewController

/**
 * read_review_predictions (#1770), on [DiagnosticsMcpServer]: a ticket's Jev review-prediction calls (#1746), the
 * `engine.jev_call` rows with use case `review-prediction`, newest first, each with the verdict
 * [PredictReviewController.predictionOf] computes. Keyed by ticket: the predictor records no process instance.
 * `since` (inclusive) and `until` (exclusive) keep one review run's calls, between its start and its end (#2255).
 * Read-only; a Smith's `repo` defaults to its own, a login passes one.
 */
class ReviewPredictionsTool(private val calls: JevCalls) {
    companion object {
        const val NAME = "read_review_predictions"
        private val ARGS = listOf("ticket", "repo", "since", "until", "limit")

        val TOOL = McpTool(NAME,
            "Read-only: a ticket's Jev review-prediction calls (POST /ticket/{owner}/{repo}/{n}/predict-review), the " +
                "engine.jev_call rows with use_case `review-prediction`: {repo, issue, calls, predictions}. `calls` is how " +
                "many times Jev was asked; `predictions` the calls newest first (at most `limit`), each {callId, calledAt, " +
                "verdict (approve|deny), confidence, value (-100..100), noAnswer, latencyMs, model}; verdict, confidence and " +
                "value are null with noAnswer true when Jev gave no answer. Keyed by ticket, not run: tell runs apart by `since` and `until` (a run's start and end; ISO-8601 instants or dates, " +
                "`since` inclusive, `until` exclusive), which bound `calls` too.",
            mapOf(
                "type" to "object",
                "properties" to linkedMapOf(
                    "ticket" to linkedMapOf("type" to "integer", "minimum" to 1, "description" to "The ticket (issue) number."),
                    "repo" to linkedMapOf("type" to "string", "description" to "owner/name; default a Smith's own repo, required for a login."),
                    "since" to linkedMapOf("type" to "string", "description" to "Only calls from this instant or date on, ISO-8601."),
                    "until" to linkedMapOf("type" to "string", "description" to "Only calls before this instant or date, ISO-8601."),
                    "limit" to linkedMapOf("type" to "integer", "minimum" to 1, "maximum" to 500, "description" to "At most this many predictions, default 100."),
                ),
                "required" to listOf("ticket"),
                "additionalProperties" to false,
            ))
    }

    /** [ownRepo] is a Smith's run repo, null for a login. */
    fun call(args: Map<String, Any?>, ownRepo: String?): McpToolResult {
        val extra = args.keys.filter { it !in ARGS }
        if (extra.isNotEmpty())
            return McpToolResult.error("$NAME takes only ${ARGS.joinToString(", ") { json(it) }}, not ${extra.joinToString(", ") { json(it) }}")
        val ticket = (args["ticket"] as? Number)?.takeIf { it.toDouble() == it.toLong().toDouble() && it.toLong() >= 1 }?.toLong()
            ?: return McpToolResult.error("\"ticket\" must be an integer of at least 1, not ${json(args["ticket"])}")
        val given = args["repo"]
        val repo = if (given != null) {
            (given as? String)?.takeIf { REPO_FULL.matches(it) } ?: return McpToolResult.error("\"repo\" must be owner/name, not ${json(given)}")
        } else ownRepo ?: return McpToolResult.error("no repo to read ticket #$ticket in: pass \"repo\"")
        val limitArg = args["limit"]
        val limit = if (limitArg == null) 100 else (limitArg as? Number)
            ?.takeIf { it.toDouble() == it.toLong().toDouble() && it.toLong() in 1..500 }?.toInt()
            ?: return McpToolResult.error("\"limit\" must be an integer from 1 to 500, not ${json(limitArg)}")
        val since = args["since"]?.let { s -> (s as? String)?.let(::parseSince) ?: return McpToolResult.error("\"since\" is an ISO-8601 instant or date, not ${json(s)}") }
        val until = args["until"]?.let { s -> (s as? String)?.let(::parseSince) ?: return McpToolResult.error("\"until\" is an ISO-8601 instant or date, not ${json(s)}") }
        val (rows, total) = calls.list(JevFilter(useCase = PredictReviewController.USE_CASE, repo = repo, issue = ticket, since = since, until = until), limit, 0)
        val predictions = rows.map { row ->
            val answer = JevAnswers.parse(row.response)?.get(PredictReviewController.QUESTION)?.takeIf { it.noul != null }
            val p = PredictReviewController.predictionOf("", row.id, answer?.noul, answer?.confidence, 0)
            linkedMapOf("callId" to row.id, "calledAt" to row.calledAt.toString(), "verdict" to p.verdict, "confidence" to p.confidence,
                "value" to p.value, "noAnswer" to (answer == null), "latencyMs" to row.latencyMs, "model" to row.model)
        }
        return McpToolResult.of(linkedMapOf("repo" to repo, "issue" to ticket, "calls" to total, "predictions" to predictions))
    }
}

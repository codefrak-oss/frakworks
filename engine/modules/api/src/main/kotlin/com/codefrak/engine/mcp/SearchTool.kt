package com.codefrak.engine.mcp

import com.codefrak.engine.workitems.UntrustedFence
import com.codefrak.engine.workitems.WorkItem
import com.codefrak.engine.workitems.WorkItemStore
import java.math.BigInteger

/**
 * search_tickets (#1625), on [FrakworksMcp] for First mate only: a target repo's work items whose description holds
 * every word of the text, ignoring case, from the engine's [WorkItemStore]; never GitHub's issue search.
 */
class SearchTool(private val engine: FrakworksEngine, private val workItems: WorkItemStore?) {
    companion object {
        const val NAME = "search_tickets"
        const val MAX_LIMIT = 50
        const val DEFAULT_LIMIT = 20
        val STATES = listOf("open", "closed", "all")

        val TOOL = McpTool(NAME,
            "Read-only: find a target repo's tickets (engine work items) by their words, every word in the title or " +
                "description, most recently updated first: each hit's number, title, state (open or closed), labels and updatedAt. Use it when the user " +
                "names a ticket by what it is about rather than its number.",
            mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "repo" to prop("string", "owner/name: the target repo to search."),
                    "text" to prop("string", "The words to find, in the title or body."),
                    "state" to prop("string", "open (default), closed or all.", "enum" to STATES),
                    "limit" to prop("integer", "At most this many hits, default $DEFAULT_LIMIT.", "minimum" to 1, "maximum" to MAX_LIMIT),
                ),
                "required" to listOf("repo", "text"),
                "additionalProperties" to false,
            ))

        private fun limitOf(v: Any?): Int? = when (v) {
            is Int, is Long, is Short, is Byte -> (v as Number).toLong()
            is BigInteger -> if (v.bitLength() < 63) v.toLong() else null
            is Double, is Float -> (v as Number).toDouble().takeIf { it == Math.floor(it) && !it.isInfinite() && Math.abs(it) < 9e15 }?.toLong()
            else -> null
        }?.takeIf { it in 1..MAX_LIMIT }?.toInt()

        private fun row(i: WorkItem) = linkedMapOf(
            "number" to i.ticket, "title" to if (i.trusted) i.title else UntrustedFence.render(i.source.ref?.let { "${i.source.kind} $it" } ?: i.source.kind, i.title), "state" to if (i.open) "open" else "closed", "labels" to i.labelSet,
            "updatedAt" to iso(i.updatedAt),
        )
    }

    fun call(args: Map<String, Any?>): McpToolResult {
        val allowed = listOf("repo", "text", "state", "limit")
        val extra = args.keys.filter { it !in allowed }
        if (extra.isNotEmpty()) return McpToolResult.error("$NAME takes only ${quoted(allowed)}, not ${quoted(extra)}")
        val asked = args["repo"] as? String
            ?: return McpToolResult.error("\"repo\" is required (owner/name): the target repo to search; there is no default repo")
        val repo = engine.targetRepo(asked)
            ?: return McpToolResult.error("$asked is not a target repo: the target repos are ${quoted(engine.repos().map { it.fullName })}")
        val text = (args["text"] as? String)?.takeIf { it.isNotBlank() }
            ?: return McpToolResult.error("\"text\" is required: the words to find")
        val state = args["state"]?.let { s -> (s as? String)?.takeIf { it in STATES } ?: return McpToolResult.error("\"state\" is open, closed or all, not ${json(s)}") } ?: "open"
        val limit = if (args["limit"] == null) DEFAULT_LIMIT else limitOf(args["limit"])
            ?: return McpToolResult.error("\"limit\" is a whole number from 1 to $MAX_LIMIT, not ${json(args["limit"])}")
        val words = text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val hits = workItems?.list(repo).orEmpty()
            .filter { it.ticket != null && (state == "all" || (state == "open") == it.open) }
            .filter { item -> item.description.lowercase().let { d -> words.all { it in d } } }
            .sortedByDescending { it.updatedAt }.take(limit)
        return McpToolResult.of(linkedMapOf("repo" to repo, "count" to hits.size, "tickets" to hits.map(::row)))
    }
}

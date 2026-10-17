package com.codefrak.engine.mcp

import com.codefrak.engine.friction.FrictionFixLinks
import com.codefrak.engine.friction.FrictionGroup

/**
 * link_friction_fix, on [FrakworksMcp] for First mate only: sets or clears a friction group's fixed-by link
 * (engine/README.md, "Friction notes", "Fixed by"), as `PATCH /friction/groups/{id}` does, as the caller's login.
 */
class FrictionFixTool(private val links: FrictionFixLinks) {
    companion object {
        const val NAME = "link_friction_fix"
        private val ARGS = listOf("group", "repo", "issue")

        val TOOL = McpTool(NAME,
            "Set or clear the ticket that fixes a friction group (GET /friction/groups; the desktop's Analytics > Friction notes): " +
                "`group` is the group's id (6 for g6), `repo` (owner/name) and `issue` the fixing ticket; `issue: null` clears the link, " +
                "and the engine's sync does not link that ticket to the group again. A linked group is fix-filed, fixed once its ticket " +
                "closes as completed with a merged PR, and recurred when a note arrives after that merge. Answers the group's id, " +
                "state and fix.",
            mapOf(
                "type" to "object",
                "properties" to linkedMapOf(
                    "group" to linkedMapOf("type" to "integer", "minimum" to 1, "description" to "The group's id: 6 for g6."),
                    "repo" to linkedMapOf("type" to "string", "description" to "The fixing ticket's repo, owner/name; not needed to clear."),
                    "issue" to linkedMapOf("type" to listOf("integer", "null"), "minimum" to 1, "description" to "The fixing ticket's number; null clears the link."),
                ),
                "required" to listOf("group", "issue"),
                "additionalProperties" to false,
            ))
    }

    fun call(args: Map<String, Any?>, by: String): McpToolResult {
        val extra = args.keys.filter { it !in ARGS }
        if (extra.isNotEmpty()) return McpToolResult.error("$NAME takes only ${ARGS.joinToString(", ") { json(it) }}, not ${extra.joinToString(", ") { json(it) }}")
        val group = FrakworksMcp.ticketOf(args["group"]) ?: return McpToolResult.error("group is a group id (a positive integer), not ${json(args["group"])}")
        if ("issue" !in args) return McpToolResult.error("issue is the fixing ticket's number, or null to clear the link")
        val updated = if (args["issue"] == null) links.clear(group, by) else {
            val issue = FrakworksMcp.ticketOf(args["issue"]) ?: return McpToolResult.error("not a ticket number: ${json(args["issue"])} (a positive integer)")
            val repo = (args["repo"] as? String)?.takeIf { FrakworksMcp.REPO_FULL.matches(it) }
                ?: return McpToolResult.error("not an owner/name repo: ${json(args["repo"])}")
            links.link(group, repo, issue, by)
        } ?: return McpToolResult.error("no friction group g$group")
        return McpToolResult.of(row(updated))
    }

    private fun row(g: FrictionGroup) = linkedMapOf("group" to g.id, "title" to g.title, "state" to g.state, "fix" to g.fix?.json())
}

package com.codefrak.engine.mcp

import com.codefrak.engine.friction.FixIntakeRefused
import com.codefrak.engine.friction.FrictionFixLinks

/**
 * link_friction_fix_intake, on [FrakworksMcp] for a Smith and First mate: links a friction group, or a note's group,
 * to an intake id as its candidate fix (engine/README.md, "Friction notes", "Fixed by"), as
 * `POST /friction/groups/{id}/fix-intake` and `POST /friction/notes/{id}/fix-intake` do.
 */
class FrictionFixIntakeTool(private val links: FrictionFixLinks) {
    companion object {
        const val NAME = "link_friction_fix_intake"
        private val ARGS = listOf("intake", "group", "note")

        val TOOL = McpTool(NAME,
            "After filing the fix for some friction as an ask (submit_ask), link it: `intake` is the id submit_ask answered, " +
                "and either `note` (the id note_friction answered; its group is linked) or `group` (6 for g6). The group shows " +
                "fix-pending until the intake files its ticket, then follows that ticket (fix-filed, fixed); an intake that ends " +
                "without a ticket clears the link. A group already fixed by a ticket is refused. Answers the group's id, state and fix.",
            mapOf(
                "type" to "object",
                "properties" to linkedMapOf(
                    "intake" to linkedMapOf("type" to "string", "description" to "The intake id submit_ask answered."),
                    "note" to linkedMapOf("type" to "string", "description" to "A friction note's id; its group is linked. Not with group."),
                    "group" to linkedMapOf("type" to "integer", "minimum" to 1, "description" to "The group's id: 6 for g6. Not with note."),
                ),
                "required" to listOf("intake"),
                "additionalProperties" to false,
            ))
    }

    /** The call as [by] (First mate's login, or a Smith's run). */
    fun call(args: Map<String, Any?>, by: String): McpToolResult {
        val extra = args.keys.filter { it !in ARGS }
        if (extra.isNotEmpty()) return McpToolResult.error("$NAME takes only ${ARGS.joinToString(", ") { json(it) }}, not ${extra.joinToString(", ") { json(it) }}")
        val intake = (args["intake"] as? String)?.trim()?.ifEmpty { null }
            ?: return McpToolResult.error("intake is the id submit_ask answered, not ${json(args["intake"])}")
        if ((args["group"] != null) == (args["note"] != null)) return McpToolResult.error("pass exactly one of \"group\" and \"note\"")
        val group = args["group"]?.let { g -> FrakworksMcp.ticketOf(g) ?: return McpToolResult.error("group is a group id (a positive integer), not ${json(g)}") }
        val note = args["note"]?.let { n -> (n as? String)?.trim()?.ifEmpty { null } ?: return McpToolResult.error("note is a friction note's id, not ${json(n)}") }
        val g = try { links.linkIntake(group, note, intake, by) } catch (e: FixIntakeRefused) {
            return McpToolResult.error(e.message ?: "refused")
        }
        return McpToolResult.of(linkedMapOf("group" to g.id, "title" to g.title, "state" to g.state, "fix" to (g.fix?.json() ?: g.pending?.json())))
    }
}

package com.codefrak.engine.mcp

import com.codefrak.engine.friction.FrictionConfidence
import com.codefrak.engine.friction.FrictionFilter
import com.codefrak.engine.friction.FrictionFix
import com.codefrak.engine.friction.FrictionFixReads
import com.codefrak.engine.friction.FrictionInput
import com.codefrak.engine.friction.FrictionLog
import com.codefrak.engine.friction.FrictionNote
import com.codefrak.engine.friction.forNotes
import com.codefrak.engine.parseSince
import java.time.OffsetDateTime

/**
 * read_friction_notes (#876), on [DiagnosticsMcpServer]: `engine.friction_note` (a Smith's notes, and First mate's since #968), newest first, for people and
 * the aggregating Smith to come. Read-only, answered for the same callers as [DoctorHistoryTool].
 */
class FrictionNotesTool(private val notes: FrictionLog, private val confidence: FrictionConfidence = FrictionConfidence.NONE,
                        private val fixes: FrictionFixReads = FrictionFixReads.NONE) {
    companion object {
        const val NAME = "read_friction_notes"
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 500
        private val ARGS = listOf("repo", "ticket", "kind", "source", "since", "limit")

        val TOOL = McpTool(NAME,
            "Read-only: the friction notes Smiths left (note_friction during a run, and the retro after it) and First " +
                "mate's (its note_friction), newest first: id, repo, issue, runId, processInstanceId, notedAt, source, kind, " +
                "summary, evidence, severity, login (First mate's caller; null on a Smith's note), signature (the note's exact key), " +
                "groupId, groupedBy (new, exact, jev, fallback, no-jev or user; null while the grouper has not placed it), jevCallId " +
                "(the friction-group Jev call, when one was made), jevConfidence (that call's confidence in its group answer; null without one) " +
                "groupedLogin (the login that pulled the note out, on a user row), twoThings (Jev's mark that the note describes two " +
                "different things, its noul value; null when unmarked), twoThingsCallId (the call that marked it), groupState (the group's " +
                "open, fix-pending, fix-filed, fixed or recurred; null while ungrouped) and groupFix (the ticket that fixes the group, {repo, issue, " +
                "state, recurredAt}, or while pending the intake that will file it, {intake, repo, issue: null, state: fix-pending}; null without one). Groups over HTTP: GET /friction/groups. " +
                "Filter by `repo` (owner/name), `ticket`, `kind`, `source` (as-you-go, retro or first-mate) and `since` (an ISO-8601 " +
                "instant or date); `limit` is at most $MAX_LIMIT, default $DEFAULT_LIMIT.",
            mapOf(
                "type" to "object",
                "properties" to linkedMapOf(
                    "repo" to linkedMapOf("type" to "string", "description" to "Only notes from this repo, owner/name."),
                    "ticket" to linkedMapOf("type" to "integer", "minimum" to 1, "description" to "Only notes from runs on this ticket (issue) number."),
                    "kind" to linkedMapOf("type" to "string", "enum" to FrictionInput.KINDS, "description" to "Only notes of this kind."),
                    "source" to linkedMapOf("type" to "string", "enum" to FrictionNote.SOURCES, "description" to "Only notes from a Smith's note_friction (as-you-go), its retro, or First mate's note_friction (first-mate)."),
                    "since" to linkedMapOf("type" to "string", "description" to "Only notes from this instant or date on, ISO-8601 (e.g. 2026-10-01)."),
                    "limit" to linkedMapOf("type" to "integer", "minimum" to 1, "maximum" to MAX_LIMIT, "description" to "At most this many notes, default $DEFAULT_LIMIT."),
                ),
                "additionalProperties" to false,
            ))

        /** `since` as an offset date-time, an instant or a date (its UTC midnight), as `GET /jev/calls` takes it; null if none parses. */
        fun at(text: String): OffsetDateTime? = parseSince(text)
    }

    fun call(args: Map<String, Any?>): McpToolResult {
        val extra = args.keys.filter { it !in ARGS }
        if (extra.isNotEmpty())
            return McpToolResult.error("$NAME takes only ${ARGS.joinToString(", ") { json(it) }}, not ${extra.joinToString(", ") { json(it) }}")
        val repo = args["repo"]
        if (repo != null && (repo !is String || !FrakworksMcp.REPO_FULL.matches(repo))) return McpToolResult.error("not an owner/name repo: ${json(repo)}")
        val ticket = args["ticket"]?.let { FrakworksMcp.ticketOf(it) ?: return McpToolResult.error("not a ticket number: ${json(args["ticket"])} (a positive integer)") }
        val kind = args["kind"]
        if (kind != null && kind !in FrictionInput.KINDS) return McpToolResult.error("kind is one of ${FrictionInput.KINDS.joinToString(", ") { json(it) }}, not ${json(kind)}")
        val source = args["source"]
        if (source != null && source !in FrictionNote.SOURCES) return McpToolResult.error("source is one of ${FrictionNote.SOURCES.joinToString(", ") { json(it) }}, not ${json(source)}")
        val since = args["since"]?.let { s -> (s as? String)?.let(::at) ?: return McpToolResult.error("since is an ISO-8601 instant or date, not ${json(s)}") }
        val limit = args["limit"]?.let { l -> FrakworksMcp.ticketOf(l)?.takeIf { it <= MAX_LIMIT }?.toInt()
            ?: return McpToolResult.error("limit is an integer from 1 to $MAX_LIMIT, not ${json(l)}") } ?: DEFAULT_LIMIT
        val listed = notes.list(FrictionFilter(repo as String?, ticket, kind as String?, source as String?, since, limit))
        val conf = confidence.forNotes(listed)
        val fix = fixes.fixesOf(listed.mapNotNull { it.groupId })
        val pending = fixes.pendingOf(listed.mapNotNull { it.groupId })
        val rows = listed.map {
            linkedMapOf("id" to it.id, "repo" to it.repo, "issue" to it.issue, "runId" to it.runId,
                "processInstanceId" to it.processInstanceId, "notedAt" to iso(it.notedAt), "source" to it.source,
                "kind" to it.kind, "summary" to it.summary, "evidence" to it.evidence, "severity" to it.severity, "login" to it.login,
                "signature" to it.signature, "groupId" to it.groupId, "groupedBy" to it.groupedBy, "jevCallId" to it.jevCallId,
                "groupedLogin" to it.groupedLogin, "jevConfidence" to it.jevCallId?.let { c -> conf[c] },
                "twoThings" to it.twoThings, "twoThingsCallId" to it.twoThingsCallId,
                "groupState" to it.groupId?.let { g -> FrictionFix.stateOf(fix[g], pending[g]) },
                "groupFix" to it.groupId?.let { g -> fix[g]?.json() ?: pending[g]?.json() })
        }
        return McpToolResult.of(linkedMapOf("count" to rows.size, "notes" to rows))
    }
}

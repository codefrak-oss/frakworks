package com.codefrak.engine.mcp

import com.codefrak.engine.doctor.DoctorHistory
import com.codefrak.engine.doctor.DoctorRun
import com.codefrak.engine.doctor.DoctorTicket
import org.springframework.stereotype.Component

/** What read_doctor_history reads: GET /doctor/history, in-process. An interface so the tool is tested without Spring. */
interface DoctorReads {
    /** GET /doctor/history without `issueUrl`. */
    fun tickets(): List<DoctorTicket>
    /** GET /doctor/history's runs for the ticket in [repo], newest first. */
    fun runs(repo: String, ticket: Long): List<DoctorRun>
}

@Component
class EngineDoctorReads(private val doctor: DoctorHistory) : DoctorReads {
    override fun tickets() = doctor.tickets()
    override fun runs(repo: String, ticket: Long) = doctor.of(repo, ticket)
}

/**
 * read_doctor_history (#655), on [DiagnosticsMcpServer] since #658 (it was on [FrakworksMcp]):
 * the same name, schema, result and error texts.
 */
class DoctorHistoryTool(private val doctor: DoctorReads) {
    companion object {
        const val NAME = "read_doctor_history"
        private val ARGS = listOf("ticket", "repo")

        val TOOL = McpTool(NAME,
            "Read-only: the doctor's history (GET /doctor/history). With no arguments, the tickets the doctor has run " +
                "on, newest last run first: repo, issue, runs, lastStartedAt, lastOutcome. With `ticket`, that ticket's " +
                "runs, newest first: id, position, stuckSince, startedAt, endedAt, diagnosis, remedy, target, outcome, " +
                "detail, sighting (null, or {id, kind, where, bug, tickets, runs, signature}: what the run was recorded as, " +
                "how many tickets and runs its bug, or its signature while there is no bug, was seen on, and its signature hash). `repo` (owner/name) always goes with `ticket`: the target repo the ticket is in.",
            mapOf(
                "type" to "object",
                "properties" to linkedMapOf(
                    "ticket" to linkedMapOf("type" to "integer", "minimum" to 1, "description" to "The ticket (issue) number whose runs to read."),
                    "repo" to linkedMapOf("type" to "string", "description" to "owner/name, with `ticket` and required then."),
                ),
                "additionalProperties" to false,
            ))
    }

    fun call(args: Map<String, Any?>): McpToolResult {
        val extra = args.keys.filter { it !in ARGS }
        if (extra.isNotEmpty())
            return McpToolResult.error("$NAME takes only ${ARGS.joinToString(", ") { json(it) }}, not ${extra.joinToString(", ") { json(it) }}")
        if (args["ticket"] != null && FrakworksMcp.ticketOf(args["ticket"]) == null)
            return McpToolResult.error("not a ticket number: ${json(args["ticket"])} (a positive integer)")
        if (args["repo"] != null && (args["repo"] as? String)?.let { FrakworksMcp.REPO_FULL.matches(it) } != true)
            return McpToolResult.error("not an owner/name repo: ${json(args["repo"])}")
        val ticket = FrakworksMcp.ticketOf(args["ticket"])
        if (ticket == null) {
            if (args["repo"] != null) return McpToolResult.error("\"repo\" goes only with \"ticket\"")
            val rows = doctor.tickets().map {
                linkedMapOf("repo" to it.repo, "issue" to it.issue, "runs" to it.runs,
                    "lastStartedAt" to iso(it.lastStartedAt), "lastOutcome" to it.lastOutcome)
            }
            return McpToolResult.of(linkedMapOf("count" to rows.size, "tickets" to rows))
        }
        val repo = args["repo"] as? String ?: return McpToolResult.error("\"ticket\" needs \"repo\" (owner/name): the target repo the ticket is in")
        val runs = doctor.runs(repo, ticket)
        val rows = runs.map {
            linkedMapOf("id" to it.id, "position" to it.position, "stuckSince" to iso(it.stuckSince),
                "startedAt" to iso(it.startedAt), "endedAt" to iso(it.endedAt), "diagnosis" to it.diagnosis,
                "remedy" to it.remedy, "target" to it.target, "outcome" to it.outcome, "detail" to it.detail,
                "sighting" to it.sighting?.let { s ->
                    linkedMapOf("id" to s.id, "kind" to s.kind, "where" to s.where,
                        "bug" to s.bug?.let { b -> linkedMapOf("repo" to b.repo, "issue" to b.issue, "state" to b.state) },
                        "tickets" to s.tickets, "runs" to s.runs, "signature" to s.signature)
                })
        }
        return McpToolResult.of(linkedMapOf("repo" to repo, "issue" to ticket, "count" to rows.size, "runs" to rows))
    }
}

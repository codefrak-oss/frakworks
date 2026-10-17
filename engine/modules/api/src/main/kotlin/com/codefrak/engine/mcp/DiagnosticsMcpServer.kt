package com.codefrak.engine.mcp

import com.codefrak.engine.board.Need
import com.codefrak.engine.board.Needs
import com.codefrak.engine.board.LiveParentRestarter
import com.codefrak.engine.repos.Access
import com.codefrak.engine.camunda.Actor
import com.codefrak.engine.camunda.IncidentRetrier
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.camunda.JobAttempts
import com.codefrak.engine.friction.FrictionConfidence
import com.codefrak.engine.friction.FixSyncRunning
import com.codefrak.engine.friction.FrictionFixReads
import com.codefrak.engine.friction.FrictionFixSync
import com.codefrak.engine.friction.FrictionLog
import com.codefrak.engine.intake.FlowCanceller
import com.codefrak.engine.jobs.JobRegistrar
import com.codefrak.engine.jobs.JobWriter
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.settings.PublicUrl
import com.codefrak.engine.tier.TierAccounts
import org.operaton.bpm.engine.RuntimeService
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.math.BigInteger

/**
 * frakworks-engine-diagnostics (engine/README.md, "The diagnostics server"): the engine's own MCP
 * server for engine-side actors, Smiths now. One tool, `act_on_incident` (#536, moved here from the
 * desktop's frakworks server by #539): retry a ticket's incident, or cancel its running flow (by ticket
 * with or without an incident, #558), through
 * [IncidentRetrier] and [FlowCanceller], the code `POST /camunda/incidents/{id}/retry` and
 * `DELETE /intake/{id}` run. It acts for a running Smith sandbox, as that Smith ([Actor.Smith]): only
 * on flows in the Smith's own repo, never on the flow it runs under; and for the first mate (#663), a
 * [McpCaller.Nobody] whose bearer token proved a login, as that login ([Actor.Login]), with the
 * `Access` check the HTTP endpoints apply. A login has no own repo, so a `ticket` needs a `repo`. A doctor run's
 * incident is its ticket's: its `GET /needs` row, and the access check, name the ticket's repo (`ActorAccess.ticketOf`);
 * a cancel by its `incident_id` ends the doctor run alone (`FlowCanceller`).
 * And `read_doctor_history` ([DoctorHistoryTool], #655, moved here from [FrakworksMcp] by #658):
 * read-only, for a Smith or any caller the engine placed. `read_friction_notes` ([FrictionNotesTool], #876) reads
 * `engine.friction_note` for the same callers, `read_job_attempts` ([JobAttemptsTool], #1174) a job's failed attempts, and `read_tier_accounts`
 * ([TierAccountsTool], #1260) each tier's account right now, a peek that leaves the rotation alone, and
 * `read_review_predictions` ([ReviewPredictionsTool], #1770) a ticket's Jev review-prediction calls, within `since`/`until` (#2255),
 * `read_jev_calls` ([JevCallsTool], #2255) the Jev calls of any use case and their count, by repo, ticket, flow, use case and window, and
 * `read_public_url` ([PublicUrlTool], #2253) the engine's public URL, null while unset.
 * `sync_friction_fixes` (#1207) runs one pass of the friction fix sync
 * ([FrictionFixSync]) and answers its report, for a login and never a Smith, as `POST /friction/fixes/sync` does.
 * The `/jobs` API as tools ([JobsTool], #1227): `list_jobs`, `read_job` and `read_job_runs` (#1653) read for the same callers as the readers;
 * `register_job`, `run_job` and `delete_job` refuse a Smith and need a login with a `jobs` grant, as the endpoints do.
 * `restart_live_parent` ([RestartLiveParentTool], #2572) restarts a fallen-back split's live parent for a trusted login, as
 * `POST /ticket/{owner}/{repo}/{n}/live-parent` does.
 */
@Component
class DiagnosticsMcpServer(
    private val needs: Needs, private val retrier: IncidentRetrier, private val canceller: FlowCanceller,
    doctor: DoctorReads, friction: FrictionLog, confidence: FrictionConfidence, fixes: FrictionFixReads,
    runtime: RuntimeService, attempts: JobAttempts,
    private val fixSync: FrictionFixSync, registrar: JobRegistrar, tierAccounts: TierAccounts,
    jevCalls: JevCalls, publicUrl: PublicUrl, restarter: LiveParentRestarter, access: Access,
) : EngineMcpServer {
    private val restartLiveParent = RestartLiveParentTool(restarter, access)
    private val publicUrlTool = PublicUrlTool(publicUrl)
    private val reviewPredictions = ReviewPredictionsTool(jevCalls)
    private val jevCallsTool = JevCallsTool(jevCalls)
    private val tierAccountsTool = TierAccountsTool(tierAccounts)
    private val jobs = JobsTool(registrar)
    private val doctorHistory = DoctorHistoryTool(doctor)
    private val frictionNotes = FrictionNotesTool(friction, confidence, fixes)
    private val jobAttempts = JobAttemptsTool(runtime, attempts)

    companion object {
        const val NAME = "frakworks-engine-diagnostics"
        const val ACT_ON_INCIDENT = "act_on_incident"
        const val SYNC_FRICTION_FIXES = "sync_friction_fixes"
        private val SYNC_TOOL = McpTool(
            SYNC_FRICTION_FIXES,
            "Run one pass of the friction fix sync now (what POST /friction/fixes/sync does) and answer what it did: " +
                "`linked` ({groupId, repo, issue, state}), `changed` ({groupId, repo, issue, from, to (null: link cleared), " +
                "mergedAt}), `recurred` (always empty: a recurrence is marked when a note joins) and `skipped` ({groupId, " +
                "repo, issue, reason}). An error when a pass is already running. Not for a Smith.",
            mapOf("type" to "object", "properties" to emptyMap<String, Any>(), "additionalProperties" to false),
        )
        private val ARGS = listOf("action", "ticket", "repo", "incident_id", "reason", "rerun_ci")
        private val ACTIONS = listOf("retry", "cancel")
    }

    override val name = NAME
    override val version = "1"

    override fun tools() = listOf(McpTool(
        ACT_ON_INCIDENT,
        "Retry a ticket's open incident, or cancel a ticket's running flow: a Smith in its own repo, a login where it " +
            "has access. Address it by `ticket` (in `repo`, default a Smith's own repo; a login must pass it) or by " +
            "`incident_id` (a GET /needs incident row's actionId), never both; none or several matches is an error that acts on nothing. `retry` needs an open incident and retries it (what POST " +
            "/camunda/incidents/<id>/retry does). `cancel` cancels a root flow (what DELETE /intake/<id> does): by `ticket`, " +
            "the flow running on that ticket, with or without an incident (parked on a question, or on nothing); by " +
            "`incident_id`, the flow the incident is on. It stops its Smiths and closes the ticket as not planned, and " +
            "`reason` (posted on the issue) is required. Never a Smith's own ticket's flow. A doctor run's incident is " +
            "its ticket's (repo, number and access); cancelling it ends the doctor run alone and leaves the ticket as it is. " +
            "`rerun_ci: true` with `retry`, for an incident where the work declined the engine check's failure " +
            "${Vars.CI_NOT_MINE_MAX} rounds in a row on an unchanged head (a flaky test): the check's failed job is re-run on " +
            "the same head and the flow waits for it, granting at most ${Vars.CI_NOT_MINE_MAX} more automatic re-runs; any " +
            "other incident is refused and nothing is touched.",
        mapOf(
            "type" to "object",
            "properties" to linkedMapOf(
                "action" to mapOf("type" to "string", "enum" to ACTIONS, "description" to "retry the incident, or cancel the ticket's flow."),
                "ticket" to mapOf("type" to "integer", "minimum" to 1, "description" to "The ticket (issue) number, e.g. 525: the incident's for a retry, the running flow's for a cancel."),
                "repo" to mapOf("type" to "string", "description" to "owner/name, only with `ticket`; default a Smith's own repo, required for a login."),
                "incident_id" to mapOf("type" to "string", "description" to "The incident's id (an incident row's actionId)."),
                "reason" to mapOf("type" to "string", "description" to "Why the flow is cancelled; required for cancel."),
                "rerun_ci" to mapOf("type" to "boolean", "description" to "With retry: re-run the failed engine check on the unchanged head of a ticket whose work declined it as not its change's."),
            ),
            "required" to listOf("action"),
            "additionalProperties" to false,
        ),
    ), DoctorHistoryTool.TOOL, FrictionNotesTool.TOOL, JobAttemptsTool.TOOL, TierAccountsTool.TOOL, ReviewPredictionsTool.TOOL, JevCallsTool.TOOL, PublicUrlTool.TOOL, SYNC_TOOL, RestartLiveParentTool.TOOL) + JobsTool.TOOLS

    override fun call(tool: String, args: Map<String, Any?>, caller: McpCaller): McpToolResult {
        if (tool == SYNC_FRICTION_FIXES) return syncFixes(args, caller)
        if (tool == RestartLiveParentTool.NAME) return restartLiveParent.call(args, caller)
        if (tool in JobsTool.NAMES) return when (caller) {
            is McpCaller.Unknown -> McpToolResult.error("cannot tell whether the caller is a Smith sandbox: ${caller.why}")
            // A read needs no writer; a write is checked by JobRegistrar, as the endpoint's is.
            else -> jobs.call(tool, args, if (tool in JobsTool.READS) null else when (caller) {
                is McpCaller.Smith -> JobWriter.Smith(caller.caller.runId)
                else -> JobWriter.Caller((caller as McpCaller.Nobody).login)
            })
        }
        if (tool == ReviewPredictionsTool.NAME) return when (caller) {
            is McpCaller.Unknown -> McpToolResult.error("cannot tell whether the caller is a Smith sandbox: ${caller.why}")
            is McpCaller.Smith -> reviewPredictions.call(args, caller.caller.repo)
            is McpCaller.Nobody -> reviewPredictions.call(args, null)
        }
        if (tool == JevCallsTool.NAME) return when (caller) {
            is McpCaller.Unknown -> McpToolResult.error("cannot tell whether the caller is a Smith sandbox: ${caller.why}")
            is McpCaller.Smith -> jevCallsTool.call(args, caller.caller.repo)
            is McpCaller.Nobody -> jevCallsTool.call(args, null)
        }
        // read_doctor_history, read_friction_notes, read_job_attempts, read_tier_accounts and read_public_url only read: they answer a Smith and a caller the engine placed as no Smith alike.
        val reader = when (tool) {
            DoctorHistoryTool.NAME -> doctorHistory::call
            FrictionNotesTool.NAME -> frictionNotes::call
            JobAttemptsTool.NAME -> jobAttempts::call
            TierAccountsTool.NAME -> tierAccountsTool::call
            PublicUrlTool.NAME -> publicUrlTool::call
            else -> null
        }
        if (reader != null) return when (caller) {
            is McpCaller.Unknown -> McpToolResult.error("cannot tell whether the caller is a Smith sandbox: ${caller.why}")
            else -> reader(args)
        }
        // A Smith acts as itself; a Nobody with a login (the first mate's header) as that login, as the HTTP endpoints do.
        val actor: Actor = when (caller) {
            is McpCaller.Smith -> Actor.Smith(caller.caller)
            is McpCaller.Unknown -> return McpToolResult.error("cannot tell whether the caller is a Smith sandbox: ${caller.why}")
            is McpCaller.Nobody -> caller.login?.takeIf { it.isNotBlank() }?.let { Actor.Login(it) }
                ?: return McpToolResult.error("$NAME acts only for a Smith sandbox; ${caller.address ?: "this caller"} is none")
        }
        badArgs(args)?.let { return McpToolResult.error(it) }
        val action = args["action"] as String
        val ticket = (args["ticket"] as? Number)?.toLong()
        val incidentId = args["incident_id"] as? String
        val ownRepo = (actor as? Actor.Smith)?.caller?.repo
        val repo = (args["repo"] as? String) ?: ownRepo?.takeIf { ticket != null }
        val reason = args["reason"] as? String
        if (ticket != null && repo == null) return McpToolResult.error("no repo to look for ticket #$ticket in: pass \"repo\"")

        val where = if (ticket != null) "ticket #$ticket in $repo" else "incident $incidentId"
        // By ticket, that repo's rows; by id, every repo's, so an incident elsewhere is refused as such, not missed.
        val matches = needs.list(if (ticket != null) repo else null)
            .filter { it.kind == "incident" && if (ticket != null) it.number == ticket else it.actionId == incidentId }
        // A cancel by ticket needs no incident: it takes the ticket's running root flow, parked on anything or on nothing.
        if (action == "cancel" && ticket != null) return cancelTicket(repo!!, ticket, matches, actor, reason!!)
        if (matches.isEmpty()) return McpToolResult.error("no open incident found for $where")
        if (matches.size > 1) return McpToolResult.error(
            "${matches.size} open incidents found for $where; act on one by its incident_id",
            mapOf("matches" to matches.map { mapOf("repo" to it.repo, "number" to it.number, "actionId" to it.actionId) }))
        val m: Need = matches.single()
        val at = linkedMapOf<String, Any?>("incident_id" to m.actionId, "ticket" to m.number, "repo" to m.repo)
        return try {
            if (action == "retry") {
                val r = retrier.retry(m.actionId, actor, args["rerun_ci"] == true)
                McpToolResult.of(linkedMapOf<String, Any?>("ok" to true, "action" to "retry") + at +
                    mapOf("engine" to linkedMapOf("action" to r.action, "activityId" to r.activityId, "rootInstanceId" to r.rootInstanceId)))
            } else {
                // DELETE /intake/{id} takes the root flow, not the incident: the flow the incident's root cause is on.
                cancel(retrier.rootOf(retrier.cause(m.actionId).processInstanceId).id, at, actor, reason!!)
            }
        } catch (e: ResponseStatusException) {
            McpToolResult.error(e.reason ?: e.message, at + mapOf("status" to e.statusCode.value()))
        }
    }

    /**
     * Cancels the root flow running on [repo]#[ticket] (its `repo` and `issue` variables), with or without an
     * incident; the result names the ticket's open incident when [incidents] holds exactly one.
     */
    private fun cancelTicket(repo: String, ticket: Long, incidents: List<Need>, actor: Actor, reason: String): McpToolResult {
        val flows = canceller.running(repo, ticket)
        if (flows.isEmpty()) return McpToolResult.error(
            "no flow is running for $repo#$ticket; there is nothing to cancel, close it as not planned with close_ticket (frakworks) instead")
        if (flows.size > 1) return McpToolResult.error(
            "${flows.size} flows are running for $repo#$ticket (${flows.joinToString { it.id }}); none was cancelled",
            mapOf("matches" to flows.map { mapOf("instanceId" to it.id, "kind" to it.processDefinitionId.substringBefore(':')) }))
        val at = linkedMapOf<String, Any?>()
        incidents.singleOrNull()?.let { at["incident_id"] = it.actionId }
        at["ticket"] = ticket; at["repo"] = repo
        return try { cancel(flows.single().id, at, actor, reason) } catch (e: ResponseStatusException) {
            McpToolResult.error(e.reason ?: e.message, at + mapOf("status" to e.statusCode.value()))
        }
    }

    /** Cancels root flow [flow] as `DELETE /intake/{id}` does; the result carries [at], the flow and the failed GitHub writes. */
    private fun cancel(flow: String, at: Map<String, Any?>, actor: Actor, reason: String): McpToolResult {
        val failed = canceller.cancel(flow, actor, reason)
        return McpToolResult.of(linkedMapOf<String, Any?>("ok" to true, "action" to "cancel") + at +
            mapOf("instanceId" to flow, "failedWrites" to failed))
    }

    /** sync_friction_fixes: refused to a Smith and to a caller placed as neither; a login runs one pass. */
    private fun syncFixes(args: Map<String, Any?>, caller: McpCaller): McpToolResult {
        when (caller) {
            is McpCaller.Smith -> return McpToolResult.error("a Smith sandbox (run ${caller.caller.runId}) may not run the friction fix sync")
            is McpCaller.Unknown -> return McpToolResult.error("cannot tell whether the caller is a Smith sandbox: ${caller.why}")
            is McpCaller.Nobody -> if (caller.login.isNullOrBlank())
                return McpToolResult.error("$SYNC_FRICTION_FIXES runs only for a login; ${caller.address ?: "this caller"} sent none")
        }
        if (args.isNotEmpty()) return McpToolResult.error("$SYNC_FRICTION_FIXES takes no arguments, not ${args.keys.joinToString { "\"$it\"" }}")
        return try { McpToolResult.of(fixSync.syncNow().json()) } catch (e: FixSyncRunning) {
            McpToolResult.error(e.message!!)
        } catch (e: Exception) {
            McpToolResult.error("the friction fix sync failed: ${e.message}")
        }
    }

    /** What the desktop's act_on_incident refused before any request, or null. */
    private fun badArgs(args: Map<String, Any?>): String? {
        val extra = args.keys - ARGS.toSet()
        if (extra.isNotEmpty()) return "$ACT_ON_INCIDENT takes only ${ARGS.joinToString { "\"$it\"" }}, not ${extra.joinToString { "\"$it\"" }}"
        val action = args["action"]
        if (action !in ACTIONS) return "$ACT_ON_INCIDENT's action is \"retry\" or \"cancel\", not ${action?.let { "\"$it\"" }}"
        val ticket = args["ticket"]; val incident = args["incident_id"]; val repo = args["repo"]; val reason = args["reason"]
        if ((ticket == null) == (incident == null)) return "$ACT_ON_INCIDENT takes exactly one of \"incident_id\" or \"ticket\""
        if (incident != null) {
            if (repo != null) return "\"repo\" goes only with \"ticket\""
            if (incident !is String || incident.isBlank()) return "not an incident id: $incident"
        }
        if (ticket != null && !(ticket is Int || ticket is Long || ticket is BigInteger) || (ticket as? Number)?.toLong()?.let { it < 1 } == true)
            return "not a ticket number: $ticket (a positive integer)"
        if (repo != null && (repo !is String || !REPO_FULL.matches(repo))) return "not an owner/name repo: $repo"
        if (reason != null && reason !is String) return "a reason is text, not $reason"
        if (action == "cancel" && (reason as String?).isNullOrBlank()) return "a cancel needs a reason: it closes the ticket as not planned"
        if (action == "retry" && reason != null) return "\"reason\" goes only with a cancel"
        val rerunCi = args["rerun_ci"]
        if (rerunCi != null && rerunCi !is Boolean) return "rerun_ci is true or false, not $rerunCi"
        if (rerunCi == true && action != "retry") return "\"rerun_ci\" goes only with a retry"
        return null
    }
}

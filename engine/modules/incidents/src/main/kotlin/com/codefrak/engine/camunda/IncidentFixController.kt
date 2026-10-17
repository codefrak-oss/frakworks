package com.codefrak.engine.camunda

import com.codefrak.engine.refuseBy
import com.codefrak.engine.tokenLogin
import com.codefrak.engine.at
import com.codefrak.engine.github.parseIssueUrl
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.jev.JevContext
import com.codefrak.engine.jev.JevDecider
import com.codefrak.engine.repos.Access
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.SmithRefusal.refuseSmith
import com.codefrak.engine.smith.SmithSandbox
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.issueOf
import com.codefrak.engine.smith.repoOf
import jakarta.servlet.http.HttpServletRequest
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.history.HistoricProcessInstance
import org.operaton.bpm.engine.runtime.Incident
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime

/** `fixId`: the `incident-fix` instance started; `incidentId` the root cause it is about; `mode` `fix` or `diagnose`. */
data class FixStarted(val fixId: String, val incidentId: String, val mode: String)

/**
 * The latest Fix on a ticket. `state` is `running`, `retried` or `failed`; `fixFailed` is what the ticket view
 * shows as "fix failed": the fix failed, or it retried and the flow has an open incident again since it ended.
 */
data class FixView(val state: String, val fixFailed: Boolean, val endedAt: OffsetDateTime?, val diagnosis: String?, val detail: String?)

/** The latest Propose a fix on the ticket's open incident. `state` is `running`, `proposed` or `failed`. */
data class ProposeView(val state: String, val proposal: String?, val diagnosis: String?)

/** `incident`: the ticket's open incident, null when none; `fix` and `propose` null when never run. */
data class IncidentFixes(val incident: IncidentView?, val fix: FixView?, val propose: ProposeView?)

/**
 * Fix and Propose a fix (engine/README.md, "Fix and Propose a fix"): a sandboxed Smith run on an open
 * incident, as bpmn/incident-fix.bpmn. `/fix` diagnoses and applies a remedy through the retry path
 * (`ApplyIncidentFixDelegate`); `/propose` only diagnoses, and its summary is kept to 40 characters.
 * Before a Fix starts its Smith, Jev pre-triages the incident ([IncidentTriage]): a `retry` at
 * `engine.jev.incident-triage-floor` or above is retried at once, with no Smith run.
 */
@RestController
class IncidentFixController(
    private val runtime: RuntimeService, private val history: HistoryService, private val management: ManagementService,
    private val repository: RepositoryService, private val retrier: IncidentRetrier, private val access: Access,
    private val instances: IssueInstances, private val incidents: OpenIncidents, private val sandbox: SmithSandbox,
    private val jev: JevDecider, private val calls: JevCalls, private val attempts: JobAttempts,
    @Value("\${engine.jev.incident-triage-floor:0.9}") private val floor: Double = 0.9,
) {
    private val log = LoggerFactory.getLogger(IncidentFixController::class.java)

    companion object {
        const val PROCESS = "incident-fix"
        /** The most of a long variable (a log tail, a result) the brief carries. */
        const val MAX_TEXT = 8192
        private const val TRIPPED = "tripped again"

        /** Strings, numbers and booleans as they are; a log tail or result (bytes) as text, cut to [MAX_TEXT]; the rest left out. */
        fun plainVars(vars: Map<String, Any?>): Map<String, Any?> = vars.entries.mapNotNull { (k, v) ->
            when {
                v is String -> k to v.take(MAX_TEXT)
                v is Number || v is Boolean -> k to v
                v is ByteArray && k in setOf(Vars.OUTPUT, Vars.LOG_TAIL, Vars.WORK) -> k to String(v).take(MAX_TEXT)
                else -> null
            }
        }.toMap(sortedMapOf())
    }

    @PostMapping("/camunda/incidents/{id}/fix")
    fun fix(@PathVariable id: String, @RequestBody(required = false) body: RetryRequest? = null, request: HttpServletRequest? = null): FixStarted {
        refuseSmith(sandbox, request, log, "fix an incident")
        refuseBy(body?.by)
        return start(id, "fix", request.tokenLogin())
    }

    @PostMapping("/camunda/incidents/{id}/propose")
    fun propose(@PathVariable id: String, @RequestBody(required = false) body: RetryRequest? = null, request: HttpServletRequest? = null): FixStarted {
        refuseSmith(sandbox, request, log, "propose a fix for an incident")
        refuseBy(body?.by)
        return start(id, "diagnose", request.tokenLogin())
    }

    /**
     * 404 when `id` names no open incident, or its flow has no repo and issue; 403 when the bearer token's login is not
     * trusted; 409 when a Fix or Propose a fix on the same incident is still going.
     */
    private fun start(id: String, mode: String, by: String): FixStarted {
        val cause = retrier.cause(id)
        val root = retrier.rootOf(cause.processInstanceId)
        val kind = root.processDefinitionId.substringBefore(':')
        val repo = runtime.repoOf(root.id)
        val issue = runtime.issueOf(root.id)
        if (repo == null || issue == null) throw ResponseStatusException(HttpStatus.NOT_FOUND, "incident $id is on a flow with no ticket")
        if (!access.trusted(by)) throw ResponseStatusException(HttpStatus.FORBIDDEN, "@$by is not a trusted login")
        if (running(cause.id)) throw ResponseStatusException(HttpStatus.CONFLICT, "a fix or diagnose run on incident ${cause.id} is already going")

        val rootVars = runtime.getVariables(root.id)
        val activityName = activityName(cause)
        val triage = if (mode == "fix") pretriage(cause, root.id, kind, repo, issue, activityName) else PreTriage.NONE
        val vars = mutableMapOf<String, Any?>(
            FixVars.REPO to repo, FixVars.ISSUE to issue, FixVars.MODE to mode, FixVars.INCIDENT_ID to cause.id,
            FixVars.ROOT to root.id, FixVars.BY to by,
            Vars.INCIDENT to Brief.incidentBytes(brief(cause, root.id, kind, rootVars, activityName)),
            FixVars.JEV_RETRY to triage.retry, FixVars.JEV_CALL to triage.callId, FixVars.DIAGNOSIS to triage.diagnosis,
        )
        listOf(Vars.MODEL, Vars.EFFORT, Vars.TIER, Vars.TICKET, Vars.DRY_RUN).forEach { k -> rootVars[k]?.let { vars[k] = it } }
        val started = runtime.startProcessInstanceByKey(PROCESS, vars)
        return FixStarted(started.id, cause.id, mode)
    }

    /** Jev's reading of an incident before a Fix: `retry` retries it with no Smith run; `callId` is its `engine.jev_call` row. */
    private data class PreTriage(val retry: Boolean, val callId: String?, val diagnosis: String?) {
        companion object { val NONE = PreTriage(false, null, null) }
    }

    /**
     * Asks Jev ([IncidentTriage]) whether the incident can just be retried. Not asked, and the Fix Smith runs, for an
     * incident that is not a failed job (a retry would be refused) or when the ticket's latest Fix was a Jev retry that
     * did not fix it (so a second press always gets a Smith). No answer, or any failure, is the Smith too.
     */
    private fun pretriage(cause: Incident, rootId: String, kind: String, repo: String, issue: Long, activityName: String?): PreTriage {
        if (cause.incidentType != Incident.FAILED_JOB_HANDLER_TYPE) return PreTriage.NONE
        val last = latestFix(repo, issue)
        if (last != null && variable(last, FixVars.JEV_RETRY) == true && fixView(last, incidents.of(rootId)).fixFailed) {
            log.info("{}#{}: the last Jev retry on this ticket did not fix it; the Fix Smith runs", repo, issue)
            return PreTriage.NONE
        }
        val state = linkedMapOf(
            "activityId" to cause.activityId, "activityName" to activityName, "processDefinition" to cause.processDefinitionId.substringBefore(':'),
            "rootFlow" to kind, "message" to cause.incidentMessage?.take(MAX_TEXT),
        )
        val call = runCatching {
            jev.decide(IncidentTriage.USE_CASE, JevContext(rootId, repo, issue), state, mapOf(IncidentTriage.QUESTION to IncidentTriage.REMEDIES), floor)
        }.onFailure { log.warn("jev incident-triage call failed; the Fix Smith runs: {}", it.message) }.getOrNull() ?: return PreTriage.NONE
        val answer = call.answers?.get(IncidentTriage.QUESTION)
        val pick = answer?.choice?.takeIf { it in IncidentTriage.REMEDIES_BY_NAME }
        val triage = IncidentTriage.read(pick, answer?.confidence, floor)
        runCatching { calls.setAction(call.callId, triage.action) }
            .onFailure { log.warn("could not record the action of jev call {}: {}", call.callId, it.message) }
        log.info("{}#{}: incident {} read by jev as {} ({}): {}", repo, issue, cause.id, pick, answer?.confidence, triage.action)
        return PreTriage(triage.retry, call.callId, if (triage.retry) IncidentTriage.diagnosis(answer?.confidence, floor) else null)
    }

    /** A Fix or Propose a fix on the incident that has not ended; one stuck on an incident of its own counts as failed, not going. */
    private fun running(incidentId: String): Boolean =
        runtime.createProcessInstanceQuery().processDefinitionKey(PROCESS)
            .variableValueEquals(FixVars.INCIDENT_ID, incidentId).list()
            .any { incidents.of(it.id) == null }

    /** The name the incident's activity has in its BPMN, if any. */
    private fun activityName(cause: Incident): String? = cause.activityId?.let {
        repository.getBpmnModelInstance(cause.processDefinitionId).getModelElementById<org.operaton.bpm.model.bpmn.instance.FlowElement>(it)?.name
    }

    /** What the Smith is told of the incident: Brief.incident. */
    private fun brief(cause: Incident, rootId: String, kind: String, rootVars: Map<String, Any?>, activityName: String?): Map<String, Any?> {
        val definition = cause.processDefinitionId
        val failing = if (cause.processInstanceId == rootId) emptyMap() else runtime.getVariables(cause.processInstanceId)
        val stacktrace = if (cause.incidentType == Incident.FAILED_JOB_HANDLER_TYPE)
            runCatching { management.getJobExceptionStacktrace(cause.configuration) }.getOrNull()?.take(MAX_TEXT) else null
        return linkedMapOf(
            "id" to cause.id, "activityId" to cause.activityId, "activityName" to activityName, "type" to cause.incidentType,
            "message" to cause.incidentMessage, "processInstanceId" to cause.processInstanceId,
            "processDefinition" to definition.substringBefore(':'), "rootInstanceId" to rootId, "rootFlow" to kind,
            "stacktrace" to stacktrace,
            "attempts" to if (cause.incidentType == Incident.FAILED_JOB_HANDLER_TYPE) attempts.rows(cause.configuration) else emptyList(),
            "variables" to linkedMapOf("root" to plainVars(rootVars), "failing" to plainVars(failing)),
        )
    }

    /**
     * The ticket's open incident and its latest Fix and Propose a fix. The proposal shown is the one
     * on the incident open now; a Fix is the ticket's latest, whichever incident it was on.
     */
    @GetMapping("/camunda/incident-fixes")
    fun fixes(@RequestParam issueUrl: String): IncidentFixes {
        val (repo, issue) = parseIssueUrl(issueUrl.trim())
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "issueUrl is https://github.com/<owner>/<repo>/issues/<n>")
        val incident = instances.roots(repo, issue).asSequence().filter { it.endTime == null }.mapNotNull { incidents.of(it.id) }.firstOrNull()
        val runs = runs(repo, issue)
        val fix = runs.firstOrNull { variable(it, FixVars.MODE) == "fix" }?.let { fixView(it, incident) }
        val propose = runs.firstOrNull { variable(it, FixVars.MODE) == "diagnose" && variable(it, FixVars.INCIDENT_ID) == incident?.id }
            ?.let { ProposeView(state(it), variable(it, FixVars.PROPOSAL) as? String, variable(it, FixVars.DIAGNOSIS) as? String) }
        return IncidentFixes(incident, fix, propose)
    }

    /** The ticket's Fix and Propose a fix runs, latest first. */
    private fun runs(repo: String, issue: Long): List<HistoricProcessInstance> =
        history.createHistoricProcessInstanceQuery().processDefinitionKey(PROCESS)
            .variableValueEquals(FixVars.REPO, repo).variableValueEquals(FixVars.ISSUE, issue)
            .orderByProcessInstanceStartTime().desc().list()

    private fun latestFix(repo: String, issue: Long): HistoricProcessInstance? = runs(repo, issue).firstOrNull { variable(it, FixVars.MODE) == "fix" }

    /** A Fix's view; a retry that tripped again is also the outcome of the Fix's Jev call, once ([tripped]). */
    private fun fixView(run: HistoricProcessInstance, incident: IncidentView?): FixView {
        val state = state(run)
        val endedAt = run.endTime?.at()
        // A retry that let the flow go on, only for it to stop on an incident again, did not fix it.
        val trippedAgain = state == "retried" && incident?.createdAt.let { it != null && endedAt != null && !it.isBefore(endedAt) }
        if (trippedAgain) (variable(run, FixVars.JEV_CALL) as? String)?.let { tripped(it, run, incident!!) }
        return FixView(state, state == "failed" || trippedAgain, endedAt, variable(run, FixVars.DIAGNOSIS) as? String, variable(run, FixVars.DETAIL) as? String)
    }

    /** Adds "tripped again" to the outcome of a retried Fix's Jev call, unless it says so already. */
    private fun tripped(callId: String, run: HistoricProcessInstance, incident: IncidentView) {
        runCatching {
            val outcome = calls.get(callId)?.outcome ?: "retried"
            if (TRIPPED !in outcome) calls.recordOutcome(callId, "$outcome; $TRIPPED (fixFailed): incident ${incident.id} at ${incident.activityId}")
        }.onFailure { log.warn("could not record that the retry of incident-fix {} tripped again: {}", run.id, it.message) }
    }

    /** `running`, or what `apply the fix` recorded; a run stuck on an incident of its own, or ended without an outcome, is `failed`. */
    private fun state(run: HistoricProcessInstance): String = when {
        run.endTime == null -> if (incidents.of(run.id) == null) "running" else "failed"
        else -> variable(run, FixVars.OUTCOME) as? String ?: "failed"
    }

    private fun variable(run: HistoricProcessInstance, name: String): Any? =
        history.createHistoricVariableInstanceQuery().processInstanceId(run.id).variableName(name).list().firstOrNull()?.value
}

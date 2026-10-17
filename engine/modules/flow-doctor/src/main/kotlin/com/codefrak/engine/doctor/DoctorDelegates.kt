package com.codefrak.engine.doctor

import com.codefrak.engine.apart
import com.codefrak.engine.engineJson
import com.codefrak.engine.camunda.IncidentRetrier
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.intake.DOCTOR_ACTOR
import com.codefrak.engine.smith.FIX_ERROR
import com.codefrak.engine.smith.SandboxExit
import com.codefrak.engine.smith.SmithExitNotifier
import com.codefrak.engine.smith.SmithSandbox
import com.codefrak.engine.smith.Vars
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import tools.jackson.module.kotlin.readValue
import java.time.OffsetDateTime

/**
 * `task-apply-remedy` in bpmn/doctor.bpmn (engine/README.md, "Doctor"): applies the doctor Smith's remedy, only while
 * what it names is still part of the ticket's position, as `ApplyIncidentFixDelegate` does for a Fix. `retry` goes
 * through [IncidentRetrier.retryAsSystem] as the engine's `doctor`; `collect-exit` delivers the exits of the
 * ticket's Smith runs whose sandbox exited unnoticed ([SmithExitNotifier.deliver], as the backstop does, but only
 * those: the doctor's own run is waiting on this transaction); `run-job` executes the due job. `healthy` and `unfixable` change nothing (`none`);
 * a failed run, a broken result, or a remedy that could not be applied is `failed`, with why. The run's
 * `engine.doctor_run` row gets the outcome, and the smith-run whose log is its transcript ([DoctorSmithRuns]).
 * Every run but a `healthy` one is a sighting ([sighting]): an `engine.doctor_sighting` row with its [Signature], lumped
 * or filed as [DoctorBugs] says (only where the repos' `doctorBugs` allow), and a `setting` `doctor` frame with
 * `state: "sighting"`.
 * Never throws for the Smith's sake. Bound by `${applyDoctorRemedy}`.
 */
@Component("applyDoctorRemedy")
class ApplyDoctorRemedyDelegate(
    private val positions: TicketPositions, private val retrier: IncidentRetrier, private val exits: SmithExitNotifier,
    private val management: ManagementService, private val runtime: RuntimeService, private val history: DoctorHistory,
    private val sandbox: SmithSandbox, private val smithRuns: DoctorSmithRuns, private val sightings: DoctorSightings,
    private val classes: RunClasses, private val events: EngineEvents, private val bugs: DoctorBugs,
) : JavaDelegate {
    private val log = LoggerFactory.getLogger(ApplyDoctorRemedyDelegate::class.java)

    companion object {
        /** The actor a doctor's retry is done as: named in the issue comment. */
        const val ACTOR = DOCTOR_ACTOR
        /** The longest a remedy run apart from this transaction ([apart]) may take. */
        const val REMEDY_TIMEOUT_SECONDS = 120L
    }

    override fun execute(execution: DelegateExecution) {
        val root = execution.getVariable(DoctorVars.ROOT) as String
        val error = execution.getVariable(FIX_ERROR) as? String
        val output = execution.getVariable(Vars.OUTPUT) as? ByteArray
        var diagnosis: String? = null
        var remedy: String? = null
        var target: String? = null
        var result: DoctorResult? = null
        val (outcome, detail) = when {
            error != null || output == null -> "failed" to (error ?: "the smith left no result")
            else -> {
                val parsed = DoctorResult.parse(output).also { result = it }
                diagnosis = parsed.diagnosis
                remedy = parsed.outcome
                target = parsed.target?.take(200)
                runCatching { apply(parsed, root) }.getOrElse { "failed" to "the remedy failed: ${it.message}" }
            }
        }
        execution.setVariable(DoctorVars.OUTCOME, outcome)
        execution.setVariable(DoctorVars.REMEDY, remedy)
        execution.setVariable(DoctorVars.DETAIL, detail)
        execution.setVariable(DoctorVars.DIAGNOSIS, diagnosis)
        val smithRun = runCatching { smithRuns.of(execution.processInstanceId) }.getOrNull()
        if (!history.finish(execution.processInstanceId, diagnosis, remedy, target, outcome, detail, smithRun))
            log.warn("doctor run {} has no engine.doctor_run row to finish", execution.processInstanceId)
        else runCatching { sighting(execution, result, outcome, detail, smithRun) }
            .onFailure { log.warn("doctor run {}: no sighting recorded: {}", execution.processInstanceId, it.message) }
        log.info("doctor run {} on {}: {} {}{}", execution.processInstanceId, root, remedy ?: "no remedy", outcome, detail?.let { " ($it)" } ?: "")
    }

    /**
     * The run's sighting (engine/README.md, "Doctor", "Sightings"): none for `healthy`;
     * `doctor-failed` for a run that failed (signed with the doctor's own run class), `remedy-failed` for a remedy
     * that could not be applied, `unfixable`, `retried` for an applied retry and `backstop` for an applied
     * `collect-exit` or `run-job` (always `where: engine`); `not-held` instead, naming it, when the run before this
     * one at the same root and leaves was an applied retry.
     */
    private fun sighting(execution: DelegateExecution, result: DoctorResult?, outcome: String, detail: String?, smithRun: String?) {
        val runId = execution.processInstanceId
        val root = execution.getVariable(DoctorVars.ROOT) as String
        val position = TicketPosition.parseKey(execution.getVariable(DoctorVars.POSITION) as? String ?: "")
        var kind = when {
            result == null -> "doctor-failed"
            result.outcome == "healthy" -> return
            outcome == "failed" -> "remedy-failed"
            result.outcome == "unfixable" -> "unfixable"
            result.outcome == "retry" -> "retried"
            else -> "backstop"
        }
        val where = when (kind) { "doctor-failed", "backstop" -> "engine"; else -> result?.defect?.where ?: "none" }
        val runClass: String?
        val signature: Signature
        if (result == null) {
            runClass = classes.ofRun(smithRun)
            signature = Signature.doctorFailed(position, runClass)
        } else {
            val incidents = stuckIncidents(execution).map {
                IncidentShape(it["activityId"] as? String, it["type"] as? String, it["message"] as? String,
                    if (Signature.generic(it["message"] as? String)) classes.ofIncident(it["processInstanceId"] as? String) else null)
            }
            runClass = incidents.firstNotNullOfOrNull { it.runClass }
            signature = Signature.of(where, position, incidents)
        }
        val earlier = if (result == null) null
        else history.at(root, Signature.leaves(position), except = runId).firstOrNull()?.takeIf { it.remedy == "retry" && it.outcome == "applied" }
        if (earlier != null) kind = "not-held"
        // The episode's start, when the position holds an incident: when it failed, not when the doctor first saw it.
        val incidentSince = stuckIncidents(execution).mapNotNull { (it["timestamp"] as? String)?.let(OffsetDateTime::parse) }.minOrNull()
        val new = NewSighting(runId, earlier?.id, execution.getVariable(DoctorVars.REPO) as String, (execution.getVariable(DoctorVars.ISSUE) as Number).toLong(),
            root, kind, runClass, where, if (result == null) detail else result.defect?.summary, signature, incidentSince)
        // Two runs recording one new signature at once: the second's insert fails on the key, and once more finds it.
        val recorded = try { sightings.record(new) } catch (e: DuplicateKeyException) { sightings.record(new) }
        // Lumped, filed and commented as the repos' `doctorBugs` allow (DoctorBugs); the sighting stays recorded either way.
        val seen = runCatching { bugs.act(new, recorded, result?.diagnosis) }
            .onFailure { log.warn("doctor run {}: sighting {} not lumped or filed: {}", runId, recorded.id, it.message) }.getOrDefault(recorded)
        // `setting` `doctor`: a sighting recorded (engine/README.md, "Events"). Fleet-wide, as the doctor is.
        events.publishAfterCommit(EngineEvent.Setting("doctor", linkedMapOf("state" to "sighting", "run" to runId, "repo" to new.repo,
            "number" to new.issue, "root" to root, "sighting" to seen.id, "kind" to kind, "where" to where, "signature" to signature.hash,
            "bug" to seen.bug, "tickets" to seen.tickets, "runs" to seen.runs)))
        log.info("doctor run {}: sighting {} ({}, {}), signature {}", runId, seen.id, kind, where, signature.hash.take(12))
    }

    /** The open incidents the doctor's brief named (`stuck.incidents`): the run's own copy, as the ticket's may be retried by now. */
    @Suppress("UNCHECKED_CAST")
    private fun stuckIncidents(execution: DelegateExecution): List<Map<String, Any?>> {
        val bytes = execution.getVariable(Vars.STUCK) as? ByteArray ?: return emptyList()
        return (engineJson.readValue<Map<String, Any?>>(bytes)["incidents"] as? List<Map<String, Any?>>).orEmpty()
    }

    private fun apply(result: DoctorResult, root: String): Pair<String, String?> {
        if (result.outcome in DoctorResult.NO_REMEDY) return "none" to null
        if (runtime.createProcessInstanceQuery().processInstanceId(root).count() == 0L) return "failed" to "the ticket's flow is no longer running"
        val position = positions.of(root)
        return when (result.outcome) {
            "retry" -> {
                val incident = result.target!!
                if (position.incidents.none { it.id == incident }) return "failed" to "incident $incident is not open on the ticket's flow"
                try {
                    retrier.retryAsSystem(incident, ACTOR)
                    "applied" to null
                } catch (e: ResponseStatusException) {
                    "failed" to "the retry was refused: ${e.reason}"
                }
            }
            "collect-exit" -> {
                val exited = position.leaves.filter { it.processKey == TicketPositions.SMITH_RUN && it.activityId == TicketPositions.SMITH_WAIT && it.wait == Wait.NONE && it.handle != null }
                    .filter { result.target.isNullOrBlank() || it.processInstanceId == result.target }
                if (exited.isEmpty()) return "failed" to "no Smith run of the ticket waits on a sandbox that exited"
                apart(REMEDY_TIMEOUT_SECONDS) {
                    exited.forEach { leaf ->
                        val code = runCatching { sandbox.status(leaf.handle!!).exitCode }.getOrNull() ?: -1L
                        exits.deliver(SandboxExit(leaf.handle!!, leaf.processInstanceId, code))
                    }
                }
                val left = exited.filter { runtime.createExecutionQuery().processInstanceId(it.processInstanceId).activityId(TicketPositions.SMITH_WAIT).count() > 0 }
                if (left.isEmpty()) "applied" to null else "failed" to "the exit of ${left.joinToString { it.processInstanceId }} was not collected"
            }
            "run-job" -> {
                val job = result.target!!
                if (position.leaves.none { it.jobId == job && it.wait == Wait.NONE }) return "failed" to "job $job is not a due job the ticket's flow waits on"
                try {
                    apart(REMEDY_TIMEOUT_SECONDS) { management.executeJob(job) }
                    "applied" to null
                } catch (e: Exception) {
                    "failed" to "the job failed: ${e.message}"
                }
            }
            else -> "failed" to "no such remedy: ${result.outcome}"
        }
    }
}

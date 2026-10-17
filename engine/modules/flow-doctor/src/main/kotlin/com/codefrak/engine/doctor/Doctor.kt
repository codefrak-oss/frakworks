package com.codefrak.engine.doctor

import com.codefrak.engine.camunda.IncidentFixController
import com.codefrak.engine.camunda.FixVars
import com.codefrak.engine.camunda.IncidentRetrier
import com.codefrak.engine.camunda.JobAttempts
import com.codefrak.engine.camunda.RootCauseIncidents
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.repos.DeferredIssues
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.issueOf
import com.codefrak.engine.smith.repoOf
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.TaskService
import org.operaton.bpm.engine.runtime.Incident
import org.operaton.bpm.engine.runtime.ProcessInstance
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * What one tick saw: the running tickets, the stuck ones, and the doctor runs it started (their instance ids); the
 * doctor runs it [ended] because their ticket closed or moved, and the incidents it [resumed] once their account window reopened.
 */
data class DoctorTickReport(val tickets: Int, val stuck: List<String>, val started: List<String>,
                            val ended: List<String> = emptyList(), val resumed: List<String> = emptyList()) {
    companion object { val IDLE = DoctorTickReport(0, emptyList(), emptyList()) }
}

/**
 * The doctor (engine/README.md, "Doctor"). Each [tick] reads every running ticket flow's [TicketPosition] and
 * compares it with the one the last tick stored in `engine.doctor_position`. A flow with an open incident is stuck
 * on the first tick that sees it; one whose position is unchanged since the previous tick, and that waits on
 * nothing legitimate (a running Smith, the user, a clock of its own), is stuck too; for a stuck
 * flow with no doctor or Fix run going on it, and none yet for this stuck episode (the same position, stuck since
 * the same time), it starts one `doctor` process (bpmn/doctor.bpmn): a Smith that diagnoses it and picks a remedy
 * the engine applies. A previous row older than two intervals (the engine was down) is only a baseline, unless its
 * position is the one seen now and a doctor run on it ended `none` or `applied`: then its episode goes on, so the
 * gap alone starts no second run; one that only ever ended `failed` is a baseline, and so gets a new run.
 * A place (the root and its leaves, without incident ids) is backed off for `engine.doctor.backoff` after the last
 * run there when its last 3 runs ended `failed`, or its last sighting is lumped into a bug not yet fixed: no run
 * starts there, and a `setting` `doctor` frame with `state: "backoff"` says so once per episode. That bug closed as
 * fixed after the last run there lifts both, and gives an episode the doctor already ran on a fresh look.
 * A plugin doctor ([PluginDiagnoses], engine/README.md, "Custom jobs and the SDK") is asked first about a stuck
 * ticket's open incident: its diagnosis is recorded as a finished run with target `plugin:<bean>` (a `RETRY`
 * retries the incident, `NONE` only records), and no Smith doctor starts for that episode; with no answer the
 * Smith doctor runs as before. A ticket whose every open incident is a `start smith` with no usable Smith token for its
 * repo (`no Smith token for <repo>: PUT …`, or `SmithTokens.forRun`'s refusal) waits on the user, as an account wait
 * does: no doctor run starts, since its own Smith in the same repo would fail the same way and add an incident.
 * A gap at the same position a doctor run still runs on is no new episode either: a slow tick starts no second run.
 * Before all that, each tick ends ([endGone]) the doctor runs whose ticket's flow ended or left the position they were
 * started for, and retries ([resume]) a doctor run's `start smith` that failed for want of an account once the
 * reset its incident names has passed; a ticket flow's own `start smith` incident of that kind is retried the same way.
 */
@Component
class Doctor(
    private val runtime: RuntimeService, private val management: ManagementService, private val positions: TicketPositions,
    private val stored: DoctorPositions, private val runs: DoctorHistory, private val events: EngineEvents,
    private val tasks: TaskService, private val deferred: DeferredIssues, private val bugs: DoctorBugStore,
    private val ender: DoctorRunEnder, private val retrier: IncidentRetrier,
    private val attempts: JobAttempts,
    private val plugins: PluginDiagnoses,
    @Value("\${engine.doctor.interval:PT1M}") private val interval: Duration = Duration.ofMinutes(1),
    @Value("\${engine.doctor.backoff:PT6H}") private val backoff: Duration = Duration.ofHours(6),
) {
    private val log = LoggerFactory.getLogger(Doctor::class.java)
    /** The episodes (`root|position|since`) a `backoff` frame was published for, so it is published once each. */
    private val framed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    companion object {
        const val PROCESS = DoctorVars.PROCESS
        /** The root flows that are tickets. */
        val TICKET_FLOWS = listOf("chore", "bug", "feature", "proposal", "research", "refactor-scan", "intake")
        /** `start smith`'s failures for want of an account (`StartSmithDelegate`), with the reset they wait for. */
        /** `start smith`'s failures for want of a usable Smith token (`RepoTokens.forSmith`, `SmithTokens.forRun`): no retry clears them. */
        private val NO_SMITH_TOKEN = Regex("""^no Smith token for \S+: PUT |; no Smith runs on \S+ until PUT """)

        /** Whether [message] is a `start smith` failure for want of a usable Smith token for the repo. */
        fun noSmithToken(message: String?): Boolean = message != null && NO_SMITH_TOKEN.containsMatchIn(message)

        private val NO_ACCOUNT = Regex("""^no account (?:available|can run Fable) until (\S+)""")

        /** When the window a `no account available until <t>` (or `no account can run Fable until <t>`) [message] waits for reopens; null for any other. */
        fun reopens(message: String?): Instant? =
            message?.let { NO_ACCOUNT.find(it) }?.let { runCatching { Instant.parse(it.groupValues[1]) }.getOrNull() }
    }

    /** One tick at [now]: none running, nothing is done, and no row is written. */
    fun tick(now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)): DoctorTickReport {
        val ended = endGone()
        val resumed = resume(now)
        val tickets = TICKET_FLOWS.flatMap { runtime.createProcessInstanceQuery().processDefinitionKey(it).rootProcessInstances().list() }
            .mapNotNull { root ->
                val repo = runtime.repoOf(root.id)
                val issue = runtime.issueOf(root.id)
                if (repo == null || issue == null) null else Triple(root, repo, issue)
            }
        stored.keepOnly(tickets.map { it.first.id })
        tickets.map { it.first.id }.toSet().let { running -> framed.removeIf { it.substringBefore('|') !in running } }
        if (tickets.isEmpty()) return DoctorTickReport(0, emptyList(), emptyList(), ended, resumed)
        val stuck = mutableListOf<String>()
        val started = mutableListOf<String>()
        for ((root, repo, issue) in tickets) {
            try {
                val position = positions.of(root.id)
                val key = position.keyText
                val last = stored.get(root.id)
                // Back after a gap at the same position a doctor run already judged (`none` or `applied`), or one still runs on
                // (a slow tick is a gap too): the episode goes on.
                val prev = last?.takeUnless {
                    it.observedAt.isBefore(now.minus(interval.multipliedBy(2))) &&
                        (it.position != key || !(runs.settled(root.id, key) || runs.going(root.id, key)))
                }
                // New, back after a gap, or moved: a baseline, stuck from now at the earliest; an open incident is stuck at once.
                val baseline = prev == null || prev.position != key
                val since = if (baseline) now else prev!!.since
                stored.save(root.id, repo, issue, key, since, now)
                if (baseline && position.incidents.isEmpty()) continue
                if (position.waiting) continue
                // Parked on `no account … until <t>` with `t` to come: waiting, not stuck; [resume] retries it at `t`.
                if (accountWait(position, now)) continue
                // Parked on a repo no Smith may start on (no Smith token, or one refused): waiting on the user; a doctor
                // run's own Smith would fail the same way and park on an incident of its own.
                if (smithTokenWait(position)) continue
                stuck += root.id
                if (busy(root.id)) {
                    log.info("doctor: {}#{} is stuck at {}, and a doctor or Fix run on it is going", repo, issue, key)
                    continue
                }
                // A bug closed as fixed since the last run at the place gives the episode a fresh look.
                if (runs.ranFor(root.id, key, since) && !fixedSinceLast(root.id, position)) continue
                val why = backedOff(root.id, position, now)
                if (why != null) {
                    if (framed.add("${root.id}|$key|$since")) {
                        log.info("doctor: {}#{} is stuck at {}, backed off until {}: {}", repo, issue, key, why.second, why.first)
                        // `setting` `doctor`: a place backed off (engine/README.md, "Events").
                        events.publishAfterCommit(EngineEvent.Setting("doctor", linkedMapOf("state" to "backoff", "repo" to repo, "number" to issue,
                            "root" to root.id, "position" to position.key, "reason" to why.first, "until" to why.second.toString())))
                    }
                    continue
                }
                if (pluginDiagnosed(root, repo, issue, position, since)) continue
                started += start(root, repo, issue, position, since)
            } catch (e: Exception) {
                log.warn("doctor: could not check {}#{} ({}): {}", repo, issue, root.id, e.message)
            }
        }
        return DoctorTickReport(tickets.size, stuck, started, ended, resumed)
    }

    /**
     * Ends ([DoctorRunEnder], outcome `ended`) each running doctor run whose ticket's flow is no longer running, or is no
     * longer at the position (`doctorPosition`) the run was started for: its diagnosis would be of a place the ticket
     * left. Its row says which; no sighting. Answers the runs ended.
     */
    private fun endGone(): List<String> = runtime.createProcessInstanceQuery().processDefinitionKey(PROCESS).list().mapNotNull { run ->
        try {
            val root = runtime.getVariable(run.id, DoctorVars.ROOT) as? String ?: return@mapNotNull null
            val at = runtime.getVariable(run.id, DoctorVars.POSITION) as? String
            val detail = when {
                runtime.createProcessInstanceQuery().processInstanceId(root).count() == 0L ->
                    "the ticket closed: its flow `$root` is no longer running"
                positions.of(root).keyText != at -> "the ticket moved: its flow `$root` is no longer at the position the run was started for"
                else -> return@mapNotNull null
            }
            ender.end(run.id, DoctorRunEnder.ENDED, detail)
            run.id
        } catch (e: Exception) {
            log.warn("doctor: could not end doctor run {}: {}", run.id, e.message)
            null
        }
    }

    /**
     * Retries, as the engine's doctor ([IncidentRetrier.retryAsSystem]), each open root-cause incident under a doctor run
     * or a ticket flow ([TICKET_FLOWS]: a ticket's own `start smith` too) whose message is `no account available until <t>` or `no account can run Fable until <t>`, once [now] is at or
     * past `t`: the account window it waited for has reopened. The engine publishes nothing when a window reopens, so
     * `t` is read off the incident and checked on each tick. Answers the incidents retried.
     */
    private fun resume(now: OffsetDateTime): List<String> =
        runtime.createIncidentQuery().incidentType(Incident.FAILED_JOB_HANDLER_TYPE).list()
            .filter { RootCauseIncidents.isRootCause(it.rootCauseIncidentId, it.id) }
            .mapNotNull { incident ->
                val until = reopens(incident.incidentMessage) ?: return@mapNotNull null
                if (until.isAfter(now.toInstant())) return@mapNotNull null
                try {
                    val root = retrier.rootOf(incident.processInstanceId)
                    val key = root.processDefinitionId.substringBefore(':')
                    if (key != PROCESS && key !in TICKET_FLOWS) return@mapNotNull null
                    retrier.retryAsSystem(incident.id, ApplyDoctorRemedyDelegate.ACTOR)
                    log.info("doctor: {} {} resumed: its account window reopened at {}", key, root.id, until)
                    incident.id
                } catch (e: Exception) {
                    log.warn("doctor: could not resume incident {}: {}", incident.id, e.message)
                    null
                }
            }

    /** Every open incident of [position] waits for an account window ([reopens]) that has not reopened by [now]. */
    private fun accountWait(position: TicketPosition, now: OffsetDateTime): Boolean =
        position.incidents.isNotEmpty() && position.incidents.all { reopens(it.message)?.isAfter(now.toInstant()) == true }

    /** Every open incident of [position] is a `start smith` that no Smith token lets start ([noSmithToken]). */
    private fun smithTokenWait(position: TicketPosition): Boolean =
        position.incidents.isNotEmpty() && position.incidents.all { noSmithToken(it.message) }

    /** A doctor or Fix run going on the flow; one stuck on an incident of its own counts as ended, not going. */
    private fun busy(rootId: String): Boolean {
        val doctors = runtime.createProcessInstanceQuery().processDefinitionKey(PROCESS).variableValueEquals(DoctorVars.ROOT, rootId).list()
        val fixes = runtime.createProcessInstanceQuery().processDefinitionKey(IncidentFixController.PROCESS).variableValueEquals(FixVars.ROOT, rootId).list()
        return (doctors + fixes).any { runtime.createIncidentQuery().processInstanceId(it.id).count() == 0L }
    }

    /**
     * Why the place [position] is in on its back-off at [now], and until when: `failed` when its last 3 runs ended
     * `failed`, `bug` when its last sighting is lumped into a bug not yet fixed; null when it is not, or
     * `engine.doctor.backoff` has passed since its last run.
     */
    private fun backedOff(rootId: String, position: TicketPosition, now: OffsetDateTime): Pair<String, OffsetDateTime>? {
        val there = runs.at(rootId, Signature.leaves(position.key)).filter { it.outcome != "running" }
        return backOff(there, backoff, now) { id, since -> bugs.fixedSince(id, since) }
    }

    /** Whether the place's last sighting is on a bug closed as fixed after the last run there started. */
    private fun fixedSinceLast(rootId: String, position: TicketPosition): Boolean {
        val there = runs.at(rootId, Signature.leaves(position.key)).filter { it.outcome != "running" }
        val last = there.firstNotNullOfOrNull { it.sighting } ?: return false
        return last.bug?.state == "fixed" && bugs.fixedSince(last.id, there.first().startedAt)
    }

    /**
     * Asks the plugin doctors about the first open incident of [position]; with an answer, applies and records it
     * (engine/README.md, "Custom jobs and the SDK") and answers true, so no Smith doctor starts. A `RETRY` retries the
     * incident as the engine's doctor ([IncidentRetrier.retryAsSystem]), outcome `applied`, or `failed` with the reason
     * when the retry throws; `NONE` is outcome `none`. No incident, or no answer, is false.
     */
    private fun pluginDiagnosed(root: ProcessInstance, repo: String, issue: Long, position: TicketPosition, since: OffsetDateTime): Boolean {
        val incident = position.incidents.firstOrNull() ?: return false
        val diagnosis = plugins.diagnose(incident.id, incident.processInstanceId, incident.processKey,
            incident.activityId, incident.type, incident.message, incident.jobId, repo, issue) ?: return false
        val bean = diagnosis.bean
        val target = "plugin:$bean"
        val (remedy, outcome, detail) = if (!diagnosis.retry) Triple("none", "none", null) else try {
            retrier.retryAsSystem(incident.id, ApplyDoctorRemedyDelegate.ACTOR)
            Triple("retry", "applied", "incident ${incident.id} retried")
        } catch (e: Exception) {
            Triple("retry", "failed", "incident ${incident.id} not retried: ${e.message}")
        }
        val id = runs.record(repo, issue, root.id, position.keyText, since, diagnosis.summary, remedy, target, outcome, detail)
        log.info("doctor: {}#{} stuck at {} since {}; plugin doctor {} answered {} ({}), run {}", repo, issue, position.keyText, since, bean, remedy, outcome, id)
        events.publishAfterCommit(EngineEvent.Setting("doctor", mapOf("state" to "ended", "run" to id, "repo" to repo, "number" to issue, "root" to root.id,
            "outcome" to outcome, "target" to target)))
        return true
    }

    private fun start(root: ProcessInstance, repo: String, issue: Long, position: TicketPosition, since: OffsetDateTime): String {
        val rootVars = runtime.getVariables(root.id)
        val vars = mutableMapOf<String, Any?>(
            DoctorVars.REPO to repo, DoctorVars.ISSUE to issue, DoctorVars.ROOT to root.id, DoctorVars.POSITION to position.keyText,
            Vars.STUCK to Brief.stuckBytes(brief(root, position, since, rootVars)),
        )
        listOf(Vars.MODEL, Vars.EFFORT, Vars.TIER, Vars.TICKET, Vars.DRY_RUN).forEach { k -> rootVars[k]?.let { vars[k] = it } }
        val run = runtime.startProcessInstanceByKey(PROCESS, vars)
        runs.start(run.id, repo, issue, root.id, position.keyText, since)
        log.info("doctor: {}#{} stuck at {} since {}; doctor run {} started", repo, issue, position.keyText, since, run.id)
        // `setting` `doctor`: a fix started (engine/README.md, "Events"). Fleet-wide, as the doctor is.
        events.publishAfterCommit(EngineEvent.Setting("doctor", mapOf("state" to "started", "run" to run.id, "repo" to repo, "number" to issue, "root" to root.id)))
        return run.id
    }

    /**
     * The counts the dashboard header shows, for the `heartbeat` frame [DoctorTick] publishes on every tick
     * (engine/README.md, "Events"): `running`, the root ticket flows; `waiting`, those parked on a user task or
     * on a deferred row of their own; `incidents`, the open incidents.
     */
    fun heartbeat(): EngineEvent.Heartbeat {
        val roots = TICKET_FLOWS.flatMap { runtime.createProcessInstanceQuery().processDefinitionKey(it).rootProcessInstances().list() }.map { it.id }.toSet()
        val onTask = tasks.createTaskQuery().list().map { it.processInstanceId }.toSet()
        val onRow = deferred.list().mapNotNull { it.waitingInstance }.toSet()
        return EngineEvent.Heartbeat(roots.size, roots.count { it in onTask || it in onRow }, runtime.createIncidentQuery().count().toInt())
    }

    /** What the doctor Smith is told: Brief.stuck. */
    private fun brief(root: ProcessInstance, position: TicketPosition, since: OffsetDateTime, rootVars: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf(
            "rootInstanceId" to root.id, "rootFlow" to root.processDefinitionId.substringBefore(':'),
            "position" to position.key, "stuckSince" to since.toString(),
            "leaves" to position.leaves.map {
                linkedMapOf("processDefinition" to it.processKey, "processInstanceId" to it.processInstanceId, "activityId" to it.activityId,
                    "activityType" to it.activityType, "wait" to it.wait.word, "why" to it.detail, "jobId" to it.jobId, "sandboxHandle" to it.handle)
            },
            "incidents" to incidentRows(position.incidents),
            "earlier" to runs.at(root.id, Signature.leaves(position.key)).map {
                linkedMapOf("id" to it.id, "startedAt" to it.startedAt.toString(), "remedy" to it.remedy, "outcome" to it.outcome)
            },
            "variables" to IncidentFixController.plainVars(rootVars),
        )

    /** The brief's `stuck.incidents[]`: each open incident, with a failed job's stacktrace and its failed `attempts` ([JobAttempts]). */
    fun incidentRows(incidents: List<OpenIncident>): List<Map<String, Any?>> =
        incidents.map {
            val stacktrace = if (it.type == Incident.FAILED_JOB_HANDLER_TYPE && it.jobId != null)
                runCatching { management.getJobExceptionStacktrace(it.jobId) }.getOrNull()?.take(IncidentFixController.MAX_TEXT) else null
            linkedMapOf("id" to it.id, "activityId" to it.activityId, "type" to it.type, "message" to it.message,
                "processInstanceId" to it.processInstanceId, "processDefinition" to it.processKey, "timestamp" to it.timestamp?.toString(), "stacktrace" to stacktrace,
                "attempts" to if (it.type == Incident.FAILED_JOB_HANDLER_TYPE) attempts.rows(it.jobId) else emptyList())
        }

}

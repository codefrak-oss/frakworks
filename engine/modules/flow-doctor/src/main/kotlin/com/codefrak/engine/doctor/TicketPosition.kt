package com.codefrak.engine.doctor

import com.codefrak.engine.at
import com.codefrak.engine.camunda.RootCauseIncidents
import com.codefrak.engine.smith.SMITH_EXITED_MESSAGE
import com.codefrak.engine.smith.SmithSandbox
import com.codefrak.engine.smith.Vars
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.impl.persistence.entity.JobEntity
import org.operaton.bpm.engine.runtime.ActivityInstance
import org.operaton.bpm.engine.runtime.Job
import org.operaton.bpm.engine.runtime.ProcessInstance
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.Date

/** What a leaf of a ticket's flow waits on (engine/README.md, "Doctor"). Only [NONE] can make a ticket stuck. */
enum class Wait(val word: String) {
    /** A Smith run whose sandbox is still running. */
    SMITH("smith"),
    /** The user: a user task (a question, a decision, an acceptance before #583) or the intake's `ask item started`. */
    USER("user"),
    /** Something with its own clock: the engine check, a feature's child, a job due later or being run, a Fix going. */
    SCHEDULED("scheduled"),
    /** Nothing that will move it: an open incident, a due job no executor took, a Smith run whose sandbox is gone. */
    NONE("none"),
}

/**
 * One place a ticket's flow waits: `activityId` in `processKey`'s instance `processInstanceId`. `jobId` is the job
 * that would move it on, `handle` the sandbox of a smith-run waiting for its exit, `detail` why it reads as [wait].
 */
data class Leaf(
    val processKey: String, val processInstanceId: String, val activityId: String, val activityType: String?,
    val wait: Wait, val jobId: String? = null, val handle: String? = null, val detail: String? = null,
) {
    val key: String get() = "$processKey:$activityId"
}

/**
 * An open root-cause incident in a ticket's flow tree; [timestamp] is Operaton's `incidentTimestamp` (UTC), when the
 * failure happened, which the doctor's own `stuckSince` (its first look) is not.
 */
data class OpenIncident(
    val id: String, val processInstanceId: String, val processKey: String, val activityId: String?, val type: String,
    val message: String?, val jobId: String?, val timestamp: OffsetDateTime? = null,
)

/**
 * Where a ticket's flow is (engine/README.md, "Doctor"): the leaf activities of its root and every process it called,
 * and the open incidents among them. Two ticks compare [key]; only a position with a leaf that waits on nothing
 * legitimate, or an open incident, can be stuck ([waiting] false).
 */
data class TicketPosition(val rootInstanceId: String, val leaves: List<Leaf>, val incidents: List<OpenIncident>) {
    /**
     * The sorted `<processKey>:<activityId>@<wait>` leaves, plus `incident:<id>` for each open incident. The wait is
     * part of it, so a Smith whose sandbox exited a moment ago has moved, and is stuck only on the tick after.
     */
    val key: List<String> get() = (leaves.map { "${it.key}@${it.wait.word}" } + incidents.map { "incident:${it.id}" }).sorted()
    val keyText: String get() = key.joinToString(",")
    /** True when every leaf waits on something that will move it and nothing is on an incident: never stuck. */
    val waiting: Boolean get() = leaves.isNotEmpty() && incidents.isEmpty() && leaves.all { it.wait != Wait.NONE }

    companion object {
        fun parseKey(text: String): List<String> = if (text.isBlank()) emptyList() else text.split(',')
    }
}

/**
 * Reads a ticket's [TicketPosition] from Operaton: the root and its called processes, walked to their leaf
 * activity instances (a call activity is not a leaf: the process it called is walked instead), each classified
 * as a [Wait]. A smith-run waiting for its sandbox's exit asks the sandbox; one that cannot be asked reads as
 * [Wait.SMITH], so an unreachable provider never makes a ticket stuck.
 */
@Component
class TicketPositions(private val runtime: RuntimeService, private val management: ManagementService, private val sandbox: SmithSandbox) {
    private val log = LoggerFactory.getLogger(TicketPositions::class.java)

    companion object {
        /** Leaves that wait on the user, by `<processKey>:<activityId>`, beyond every user task. */
        val USER_WAITS = setOf("intake:event-intake-authorized")
        /**
         * Leaves with a clock of their own: the engine check (it has a timeout), a split feature's child, and the
         * post-deployment check's waits (bpmn/post-deploy-check.bpmn: for the deploy, with its 12 h safety poll, and
         * between check runs, on a bus frame or at a time; the deadline's timer bounds those).
         */
        val SCHEDULED_WAITS = setOf("reusable-ci-check:receive-ci-check", "feature:event-child-closed",
            "post-deploy-check:receive-deploy-reached", "post-deploy-check:event-safety-poll",
            "post-deploy-check:event-check-at", "post-deploy-check:receive-check-event")
        const val SMITH_RUN = "smith-run"
        const val SMITH_WAIT = "event-smith-exited"
    }

    /** The position of the running flow [rootId]; throws when it is not running. */
    fun of(rootId: String, now: Date = Date()): TicketPosition {
        val root = runtime.createProcessInstanceQuery().processInstanceId(rootId).singleResult()
            ?: error("flow $rootId is not running")
        val instances = tree(root)
        val incidents = instances.flatMap { pi ->
            runtime.createIncidentQuery().processInstanceId(pi.id).list()
                .filter { RootCauseIncidents.isRootCause(it.rootCauseIncidentId, it.id) }
                .map { OpenIncident(it.id, pi.id, key(pi), it.activityId, it.incidentType, it.incidentMessage, it.configuration,
                    it.incidentTimestamp?.at()) }
        }
        return TicketPosition(root.id, instances.flatMap { leaves(it, now) }, incidents)
    }

    /** The root and every process it called, recursively. */
    private fun tree(root: ProcessInstance): List<ProcessInstance> {
        val all = mutableListOf(root)
        var i = 0
        while (i < all.size) {
            all += runtime.createProcessInstanceQuery().superProcessInstanceId(all[i].id).list()
            i++
        }
        return all
    }

    private fun key(pi: ProcessInstance) = pi.processDefinitionId.substringBefore(':')

    private fun leaves(pi: ProcessInstance, now: Date): List<Leaf> {
        val tree = runtime.getActivityInstance(pi.id) ?: return emptyList()
        val out = mutableListOf<Leaf>()
        fun walk(ai: ActivityInstance) {
            ai.childTransitionInstances.forEach { ti ->
                out += job(key(pi), pi.id, ti.activityId, "transition", ti.executionId, now)
            }
            ai.childActivityInstances.forEach { walk(it) }
            val leaf = ai !== tree && ai.childActivityInstances.isEmpty() && ai.childTransitionInstances.isEmpty()
            if (leaf && ai.activityType != "callActivity") out += classify(pi, ai, now)
        }
        walk(tree)
        return out
    }

    private fun classify(pi: ProcessInstance, ai: ActivityInstance, now: Date): Leaf {
        val processKey = key(pi)
        val at = "$processKey:${ai.activityId}"
        fun leaf(wait: Wait, detail: String) = Leaf(processKey, pi.id, ai.activityId, ai.activityType, wait, detail = detail)
        return when {
            ai.activityType == "userTask" -> leaf(Wait.USER, "a user task")
            at in USER_WAITS -> leaf(Wait.USER, "waiting on the user")
            at in SCHEDULED_WAITS -> leaf(Wait.SCHEDULED, "waiting on an event with its own clock")
            processKey == SMITH_RUN && ai.activityId == SMITH_WAIT -> smith(pi, ai)
            else -> {
                val jobs = ai.executionIds.flatMap { management.createJobQuery().executionId(it).list() }
                if (jobs.isEmpty()) leaf(Wait.NONE, "no job and no known wait")
                else classifyJob(jobs.first(), processKey, pi.id, ai.activityId, ai.activityType, now)
            }
        }
    }

    /** A smith-run at `smith exited`: [Wait.SMITH] while its sandbox runs, [Wait.NONE] once it exited and nothing collected it. */
    private fun smith(pi: ProcessInstance, ai: ActivityInstance): Leaf {
        val handle = runtime.getVariable(pi.id, Vars.HANDLE) as? String
        fun leaf(wait: Wait, detail: String) = Leaf(SMITH_RUN, pi.id, ai.activityId, ai.activityType, wait, handle = handle, detail = detail)
        if (handle == null) return leaf(Wait.NONE, "no sandbox handle")
        val status = runCatching { sandbox.status(handle) }.getOrElse {
            log.warn("doctor: could not check sandbox {}: {}", handle.take(12), it.message)
            return leaf(Wait.SMITH, "the sandbox could not be checked: ${it.message}")
        }
        return if (status.running) leaf(Wait.SMITH, "the sandbox is running")
        else leaf(Wait.NONE, "the sandbox exited (${status.exitCode ?: "gone"}) and $SMITH_EXITED_MESSAGE was never delivered")
    }

    private fun job(processKey: String, instanceId: String, activityId: String, type: String, executionId: String, now: Date): Leaf {
        val job = management.createJobQuery().executionId(executionId).list().firstOrNull()
            ?: return Leaf(processKey, instanceId, activityId, type, Wait.NONE, detail = "no job")
        return classifyJob(job, processKey, instanceId, activityId, type, now)
    }

    /** A job due later or locked by an executor is [Wait.SCHEDULED]; a failed one, or one due and left, is [Wait.NONE]. */
    private fun classifyJob(job: Job, processKey: String, instanceId: String, activityId: String, type: String?, now: Date): Leaf {
        val lockedUntil = (job as? JobEntity)?.lockExpirationTime
        val (wait, detail) = when {
            job.retries <= 0 -> Wait.NONE to "the job failed (no retries left)"
            lockedUntil != null && lockedUntil.after(now) -> Wait.SCHEDULED to "the job is being run"
            job.duedate != null && job.duedate.after(now) -> Wait.SCHEDULED to "the job is due at ${job.duedate.toInstant()}"
            else -> Wait.NONE to "the job is due and no executor has taken it"
        }
        return Leaf(processKey, instanceId, activityId, type, wait, jobId = job.id, detail = detail)
    }
}

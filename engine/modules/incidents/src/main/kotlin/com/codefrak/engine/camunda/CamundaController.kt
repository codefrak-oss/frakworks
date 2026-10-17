package com.codefrak.engine.camunda

import com.codefrak.engine.at
import com.codefrak.engine.github.parseIssueUrl
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.history.HistoricProcessInstance
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime

/** One process instance as a list names it. `ended` once `endedAt` is set; `state` is running, ended or incident. */
data class InstanceSummary(
    val id: String, val processDefinitionKey: String, val processDefinitionId: String, val processName: String?,
    val startedAt: OffsetDateTime, val endedAt: OffsetDateTime?, val ended: Boolean, val state: String,
    /** The activity the flow waits at, from the innermost called process; null once ended. */
    val step: String?,
)

/** `attempts`: a `failedJob` incident's job's failed attempts ([JobAttempts]), oldest first; `message` is the last. Empty for another type. */
data class Incident(val id: String, val activityId: String?, val type: String, val message: String?, val createdAt: OffsetDateTime?,
    val attempts: List<JobAttempt> = emptyList())

/** A timer the process waits on: the catch event's activity id and when it fires (`reusable-smith-run`'s `event-wait-reset`). */
data class Timer(val activityId: String?, val dueAt: OffsetDateTime?)

/** `scope` is the activity id whose scope holds the variable; null for the process's own scope. */
data class Variable(val name: String, val type: String?, val value: Any?, val scope: String?, val truncated: Boolean)

/**
 * One process in an instance's call tree. `calledFrom` is the call activity in the
 * parent that started it (null for the root). `completed` and `active` are activity
 * ids, what a diagram highlights. `timers` are the timers it waits on, each with its due time.
 */
data class InstanceNode(
    val id: String, val parentId: String?, val calledFrom: String?,
    val processDefinitionKey: String, val processDefinitionId: String, val processName: String?,
    val startedAt: OffsetDateTime, val endedAt: OffsetDateTime?, val ended: Boolean,
    val completed: List<String>, val active: List<String>, val incidents: List<Incident>, val variables: List<Variable>,
    val timers: List<Timer> = emptyList(),
)

/** The root first, then every called process in start order, each naming its parent. */
data class InstanceTree(val id: String, val nodes: List<InstanceNode>)

/**
 * Unauthenticated, read-only. What a UI needs to draw a flow's process instances the
 * way Cockpit does: `/camunda/instances?issueUrl=` names them, `/camunda/instances/{id}`
 * gives one instance's call tree with what each process has done, waits at, tripped on
 * and holds, and `/camunda/definitions/{id}/xml` is the diagram that version ran on.
 */
@RestController
class CamundaController(
    private val runtime: RuntimeService, private val history: HistoryService,
    private val repository: RepositoryService, private val issues: IssueInstances, private val management: ManagementService,
    private val attempts: JobAttempts, private val currentStep: CurrentStep,
) {
    companion object {
        /** A variable's value past this is cut, and `truncated` says so; the run log is far past it. */
        const val MAX_VALUE_CHARS = 4000
    }

    /** Every root process instance tied to an issue, newest first (`IssueInstances`). 400 when the URL is not a GitHub issue's. */
    @GetMapping("/camunda/instances")
    fun instances(@RequestParam issueUrl: String): List<InstanceSummary> {
        val (repo, number) = parseIssueUrl(issueUrl.trim())
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "issueUrl is not a GitHub issue URL")
        return issues.roots(repo, number).map(::summary)
    }

    @GetMapping("/camunda/instances/{id}")
    fun instance(@PathVariable id: String): InstanceTree {
        val root = history.createHistoricProcessInstanceQuery().processInstanceId(id).singleResult()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no process instance $id")
        val nodes = mutableListOf<InstanceNode>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            nodes += node(p)
            queue += history.createHistoricProcessInstanceQuery().superProcessInstanceId(p.id)
                .orderByProcessInstanceStartTime().asc().list()
        }
        return InstanceTree(id, nodes)
    }

    /** The BPMN XML the definition was deployed with, so an instance draws on the version it runs. */
    @GetMapping("/camunda/definitions/{id}/xml", produces = [MediaType.APPLICATION_XML_VALUE])
    fun xml(@PathVariable id: String): String {
        val definition = repository.createProcessDefinitionQuery().processDefinitionId(id).singleResult()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no process definition $id")
        return repository.getProcessModel(definition.id).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun summary(p: HistoricProcessInstance): InstanceSummary {
        val ended = p.endTime != null
        val incidents = if (ended) 0L else runtime.createIncidentQuery().processInstanceId(p.id).count()
        return InstanceSummary(p.id, p.processDefinitionKey, p.processDefinitionId, p.processDefinitionName,
            p.startTime.at(), p.endTime?.at(), ended, if (ended) "ended" else if (incidents > 0) "incident" else "running",
            step = if (ended) null else currentStep.of(p.id))
    }

    private fun node(p: HistoricProcessInstance): InstanceNode {
        val ended = p.endTime != null
        val activities = history.createHistoricActivityInstanceQuery().processInstanceId(p.id).list()
        val completed = activities.filter { it.endTime != null }.map { it.activityId }.distinct()
        // The runtime's tree, not the history: a step waiting on its asyncBefore job is a
        // transition instance there and not yet an activity in the history.
        val tree = if (ended) null else runtime.getActivityInstance(p.id)
        val active = if (tree == null) emptyList()
            else (currentStep.leaves(tree).map { it.activityId } + currentStep.transitions(tree).map { it.activityId }).distinct()
        val incidents = if (ended) emptyList() else runtime.createIncidentQuery().processInstanceId(p.id).list()
            .map { Incident(it.id, it.activityId, it.incidentType, it.incidentMessage, it.incidentTimestamp?.at(),
                if (it.incidentType == org.operaton.bpm.engine.runtime.Incident.FAILED_JOB_HANDLER_TYPE) attempts.of(it.configuration) else emptyList()) }
        val timers = if (ended) emptyList() else management.createJobQuery().processInstanceId(p.id).timers().list().map { job ->
            val activity = job.jobDefinitionId?.let { management.createJobDefinitionQuery().jobDefinitionId(it).singleResult()?.activityId }
            Timer(activity, job.duedate?.at())
        }
        val activityOf = activities.associate { it.id to it.activityId }
        val variables = history.createHistoricVariableInstanceQuery().processInstanceId(p.id).list().map { v ->
            val raw = runCatching { v.value }.getOrElse { "<unreadable: ${it.message}>" }
            val text = when (raw) { null -> null; is ByteArray -> "<${raw.size} bytes>"; else -> raw }
            val cut = text is String && text.length > MAX_VALUE_CHARS
            Variable(v.name, v.typeName, if (cut) (text as String).take(MAX_VALUE_CHARS) else text,
                scope = v.activityInstanceId?.let { activityOf[it] }, truncated = cut)
        }.sortedBy { it.name }
        val calledFrom = p.superProcessInstanceId?.let { parent ->
            history.createHistoricActivityInstanceQuery().processInstanceId(parent).activityType("callActivity").list()
                .firstOrNull { it.calledProcessInstanceId == p.id }?.activityId
        }
        return InstanceNode(p.id, p.superProcessInstanceId, calledFrom, p.processDefinitionKey, p.processDefinitionId,
            p.processDefinitionName, p.startTime.at(), p.endTime?.at(), ended, completed, active, incidents, variables, timers)
    }

}

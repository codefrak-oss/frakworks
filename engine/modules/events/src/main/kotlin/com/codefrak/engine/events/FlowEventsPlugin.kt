package com.codefrak.engine.events

import org.operaton.bpm.engine.history.HistoricProcessInstance
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin
import org.operaton.bpm.engine.impl.context.Context
import org.operaton.bpm.engine.impl.history.event.HistoricActivityInstanceEventEntity
import org.operaton.bpm.engine.impl.history.event.HistoricIncidentEventEntity
import org.operaton.bpm.engine.impl.history.event.HistoricProcessInstanceEventEntity
import org.operaton.bpm.engine.impl.history.event.HistoricVariableUpdateEventEntity
import org.operaton.bpm.engine.impl.history.event.HistoryEvent
import org.operaton.bpm.engine.impl.history.event.HistoryEventTypes
import org.operaton.bpm.engine.impl.history.handler.HistoryEventHandler
import org.operaton.bpm.engine.ProcessEngine
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/**
 * Registers [FlowEventHandler] as one of Operaton's `customHistoryEventHandlers` (engine/README.md, "Events"): the
 * engine composes it with its own database handler, so history is written as before and the stream sees every
 * flow and incident of every diagram with no diagram change. Not a `HistoryEventHandler` bean itself: the Spring
 * Boot starter would take one of those for *the* handler and drop the database one.
 */
@Component
class FlowEventsPlugin(private val flows: FlowEvents, private val recorder: ObjectProvider<FlowRecorder>) : ProcessEnginePlugin {
    override fun preInit(configuration: ProcessEngineConfigurationImpl) {
        val handlers = configuration.getCustomHistoryEventHandlers()?.toMutableList() ?: mutableListOf()
        handlers += FlowEventHandler(flows, recorder.ifAvailable ?: FlowRecorder.NONE)
        configuration.setCustomHistoryEventHandlers(handlers)
    }
    override fun postInit(configuration: ProcessEngineConfigurationImpl) {}
    override fun postProcessEngineBuild(engine: ProcessEngine) {}
}

/**
 * What the history handler saw of a flow, inside the command: the root and the instance the activity is in,
 * `state` (`started`, `step`, `waiting`, `ended`, `cancelled`), `step` the activity (its name, else its id) and
 * `stage` the root's activity. Repo, number and kind are resolved after commit ([FlowIssues]).
 */
data class FlowChange(val root: String, val instance: String, val state: String, val step: String? = null, val stage: String? = null)

/**
 * What the history handler saw of an incident, inside the command; `state` is `open`, `resolved` or `deleted`. `rootCause`
 * is the incident this one propagated up from (null, or itself, for a root cause). `GET /needs` lists a root cause, and a
 * propagated incident whose root cause is no longer open, so every change is a `needs` frame ([FlowEvents.incident]).
 */
data class IncidentChange(val root: String, val instance: String, val incidentId: String, val state: String, val activityId: String?,
                          val type: String, val message: String?, val rootCause: String? = null)

/**
 * Maps Operaton's history events to [FlowChange]s and [IncidentChange]s and hands them to [FlowEvents], which
 * publishes after the command commits, and records every flow's structure (every instance's and activity's start and
 * end, every incident, and the variables the ticket timeline keys on) through [FlowRecorder], inside the command. A root instance's start and end are `started` and `ended` (`cancelled` when
 * it was deleted); an activity's start in the root or a called process is `waiting` for a wait state (a user task, a
 * receive task, a message catch event) and `step` for anything else but a call activity, whose called process
 * reports its own steps. Events of a called process are reported under their root.
 */
class FlowEventHandler(private val flows: FlowEvents, private val recorder: FlowRecorder = FlowRecorder.NONE) : HistoryEventHandler {
    private val log = LoggerFactory.getLogger(FlowEventHandler::class.java)

    companion object {
        /** Activity types a flow waits at for someone or something else; the board's "waiting" words. */
        val WAIT_TYPES = setOf("userTask", "receiveTask", "intermediateMessageCatch")
        private const val CALL_ACTIVITY = "callActivity"
        /** The variables the projection keys on ([FlowRecorder.variable]); every other one is skipped unread. */
        val RECORDED_VARIABLES = setOf("repo", "issue", "tier", "approvedTicket", "filedTicket", "ciCheck", "ciConclusion", "ciOutcome", "output")
    }

    override fun handleEvent(event: HistoryEvent) {
        try {
            when (event) {
                is HistoricProcessInstanceEventEntity -> processInstance(event)
                is HistoricActivityInstanceEventEntity -> activity(event)
                is HistoricIncidentEventEntity -> incident(event)
                is HistoricVariableUpdateEventEntity -> variable(event)
                else -> {}
            }
        } catch (e: Exception) {
            // The stream is a side channel: it never fails the command that writes history.
            log.warn("events: history event {} of {} not mapped: {}", event.eventType, event.processInstanceId, e.toString())
        }
    }

    override fun handleEvents(events: List<HistoryEvent>) = events.forEach(::handleEvent)

    private fun processInstance(event: HistoricProcessInstanceEventEntity) {
        val root = event.rootProcessInstanceId ?: event.processInstanceId
        val called = event.superProcessInstanceId != null
        when {
            event.isEventOfType(HistoryEventTypes.PROCESS_INSTANCE_START) -> {
                record { recorder.processStarted(event.processInstanceId, root, event.superProcessInstanceId, event.processDefinitionKey, event.startTime) }
                if (!called) flows.changed(FlowChange(root, event.processInstanceId, "started")) // a called process: its root reports
            }
            event.isEventOfType(HistoryEventTypes.PROCESS_INSTANCE_END) -> {
                val cancelled = event.state == HistoricProcessInstance.STATE_EXTERNALLY_TERMINATED || event.deleteReason != null
                record { recorder.processEnded(event.processInstanceId, root, event.endTime, cancelled) }
                if (!called) flows.changed(FlowChange(root, event.processInstanceId, if (cancelled) "cancelled" else "ended"))
            }
        }
    }

    /** The projection's write, logged and swallowed like the rest: it must not fail the command, and the frames still go out. */
    private fun record(write: () -> Unit) {
        try { write() } catch (e: Exception) { log.warn("events: projection not written: {}", e.toString()) }
    }

    private fun activity(event: HistoricActivityInstanceEventEntity) {
        val root = event.rootProcessInstanceId ?: event.processInstanceId
        when {
            event.isEventOfType(HistoryEventTypes.ACTIVITY_INSTANCE_START) ->
                record { recorder.activityStarted(event.id, event.processInstanceId, root, event.activityId, event.activityType, event.sequenceCounter, event.startTime) }
            event.isEventOfType(HistoryEventTypes.ACTIVITY_INSTANCE_END) ->
                record { recorder.activityEnded(event.id, event.processInstanceId, root, event.activityId, event.activityType, event.sequenceCounter, event.endTime) }
        }
        if (!event.isEventOfType(HistoryEventTypes.ACTIVITY_INSTANCE_START)) return
        if (event.activityType == CALL_ACTIVITY) return
        val state = if (event.activityType in WAIT_TYPES) "waiting" else "step"
        val step = event.activityName?.takeIf { it.isNotBlank() } ?: event.activityId
        val stage = if (event.processInstanceId == root) event.activityId else stageOf(event.processInstanceId, root)
        flows.changed(FlowChange(root, event.processInstanceId, state, step, stage))
    }

    /**
     * The root's activity a called process runs under: up the chain of super executions (the call activities) until
     * the one in the root. Read from the command's own execution cache; null when the chain cannot be followed.
     */
    private fun stageOf(instance: String, root: String): String? {
        val manager = Context.getCommandContext()?.executionManager ?: return null
        var current = instance
        repeat(10) {
            val superExecution = manager.findExecutionById(current)?.superExecution ?: return null
            if (superExecution.processInstanceId == root) return superExecution.activityId
            current = superExecution.processInstanceId ?: return null
        }
        return null
    }

    private fun incident(event: HistoricIncidentEventEntity) {
        val state = when {
            event.isEventOfType(HistoryEventTypes.INCIDENT_CREATE) -> "open"
            event.isEventOfType(HistoryEventTypes.INCIDENT_RESOLVE) -> "resolved"
            event.isEventOfType(HistoryEventTypes.INCIDENT_DELETE) -> "deleted"
            else -> return
        }
        val root = event.rootProcessInstanceId ?: event.processInstanceId
        record { recorder.incident(event.id, event.processInstanceId, root, event.activityId, event.incidentMessage, event.rootCauseIncidentId, state,
            event.createTime, event.endTime) }
        flows.incident(IncidentChange(root, event.processInstanceId, event.id, state, event.activityId, event.incidentType, event.incidentMessage,
            event.rootCauseIncidentId))
    }

    /**
     * A variable's creation or update (not the full-history detail, which repeats it), only for the names the projection
     * keys on, so a `log` or `bundle` is never read: its text, number or bytes as the event carries them.
     */
    private fun variable(event: HistoricVariableUpdateEventEntity) {
        val name = event.variableName ?: return
        if (name !in RECORDED_VARIABLES) return
        val create = event.isEventOfType(HistoryEventTypes.VARIABLE_INSTANCE_CREATE)
        if (!create && !event.isEventOfType(HistoryEventTypes.VARIABLE_INSTANCE_UPDATE)) return
        val instance = event.processInstanceId ?: return
        val root = event.rootProcessInstanceId ?: instance
        val value: Any? = event.textValue ?: event.longValue ?: event.doubleValue ?: event.byteValue
        record { recorder.variable(instance, root, name, value, event.scopeActivityInstanceId == null || event.scopeActivityInstanceId == instance) }
    }
}

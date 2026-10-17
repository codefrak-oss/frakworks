package com.codefrak.engine.camunda

import com.codefrak.engine.at
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.RuntimeService
import org.springframework.stereotype.Component

/**
 * Open incidents under a root process instance, its called processes included (engine/README.md,
 * "Incidents"). A failure propagated up a call activity is one incident per level, all naming the
 * same root cause; this reads the cause.
 */
@Component
class OpenIncidents(private val runtime: RuntimeService, private val history: HistoryService) {
    /** The oldest open root-cause incident anywhere under `rootInstanceId`, or null when it has none (or has ended). */
    fun of(rootInstanceId: String): IncidentView? {
        val instances = history.createHistoricProcessInstanceQuery().rootProcessInstanceId(rootInstanceId).unfinished().list().map { it.id }
            .ifEmpty { return null }
        val open = instances.flatMap { runtime.createIncidentQuery().processInstanceId(it).list() }
        val ids = open.map { it.id }.toSet()
        val cause = open.filter { RootCauseIncidents.isRootCause(it.rootCauseIncidentId, it.id, ids) }
            .minByOrNull { it.incidentTimestamp?.time ?: Long.MAX_VALUE } ?: return null
        return IncidentView(cause.id, cause.activityId, cause.incidentType, cause.incidentMessage, cause.processInstanceId,
            cause.incidentTimestamp?.at())
    }
}

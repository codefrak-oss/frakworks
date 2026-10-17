package com.codefrak.engine.doctor

import org.operaton.bpm.engine.HistoryService
import org.springframework.stereotype.Component

/**
 * A doctor run's Smith run: the newest `smith-run` its `health check` (`reusable-smith-run`) called, read from the
 * engine's history, so it is there for a run still going and for one that ended. Its id names the run's log in
 * VictoriaLogs, `issue-<issue>-doctor-<id>.log` (`SmithTelemetry`'s `RunLabels`); null before the Smith started.
 */
@Component
class DoctorSmithRuns(private val history: HistoryService) {
    fun of(doctorRunId: String): String? =
        history.createHistoricProcessInstanceQuery().superProcessInstanceId(doctorRunId).list()
            .flatMap {
                history.createHistoricProcessInstanceQuery().superProcessInstanceId(it.id)
                    .processDefinitionKey(TicketPositions.SMITH_RUN).list()
            }
            .maxByOrNull { it.startTime }?.id

    /** [run] with its [DoctorRun.smithRunId] from the history when its row has none (a run still going, or one before V28). */
    fun filled(run: DoctorRun): DoctorRun = if (run.smithRunId != null) run else run.copy(smithRunId = runCatching { of(run.id) }.getOrNull())
}

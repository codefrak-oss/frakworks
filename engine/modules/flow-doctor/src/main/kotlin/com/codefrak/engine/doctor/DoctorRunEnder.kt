package com.codefrak.engine.doctor

import com.codefrak.engine.smith.RunSandboxStopper
import com.codefrak.engine.smith.SmithSandbox
import org.operaton.bpm.engine.RuntimeService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Ends a doctor run from outside it (engine/README.md, "Doctor"): a cancel (`DELETE /intake/{id}`, act_on_incident:
 * outcome `cancelled`), or the doctor's tick when the run's ticket closed or moved (outcome `ended`). Only the doctor
 * run: the ticket, its issue and its own flow are never touched. No sighting is recorded for it.
 */
@Component
class DoctorRunEnder(
    private val runtime: RuntimeService, private val sandbox: SmithSandbox, private val history: DoctorHistory,
    private val smithRuns: DoctorSmithRuns,
) : DoctorRunEnding {
    private val log = LoggerFactory.getLogger(DoctorRunEnder::class.java)
    private val sandboxStopper = RunSandboxStopper(runtime, sandbox)

    companion object {
        /** The outcome of a run a person (or a Smith) cancelled. */
        const val CANCELLED = DoctorRunEnding.CANCELLED
        /** The outcome of a run the tick ended because its ticket's flow ended or left the run's position. */
        const val ENDED = DoctorRunEnding.ENDED
    }

    /**
     * Stops every Smith sandbox under the doctor run [id], deletes its instance (its called processes and any incident
     * it is parked on go with it), and finishes its `engine.doctor_run` row with [outcome] and [detail]. Answers how
     * many sandboxes it stopped.
     */
    override fun end(id: String, outcome: String, detail: String): Int {
        val smithRun = runCatching { smithRuns.of(id) }.getOrNull()
        val stopped = sandboxStopper.stopUnder(id)
        stopped.forEach { (runId, handle) -> log.info("doctor run {}: sandbox {} of run {} stopped", id, handle.take(12), runId) }
        runtime.deleteProcessInstance(id, "doctor run $outcome: $detail")
        if (!history.finish(id, null, null, null, outcome, detail, smithRun))
            log.warn("doctor run {} has no engine.doctor_run row to finish", id)
        log.info("doctor run {} {}: {}", id, outcome, detail)
        return stopped.size
    }
}

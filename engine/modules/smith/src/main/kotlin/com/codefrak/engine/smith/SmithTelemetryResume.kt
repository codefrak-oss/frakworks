package com.codefrak.engine.smith

import com.codefrak.engine.lease.LeaseLost
import com.codefrak.engine.lease.LeaseAcquired
import com.codefrak.engine.lease.InstanceId
import org.operaton.bpm.engine.RuntimeService
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Runs that were live before this instance took the lease (the engine restarted mid-run, or the lease
 * moved here from another replica): each run waiting on its exit whose sandbox still runs is followed
 * again, from now. Losing the lease stops following them here ([SmithTelemetry.handOff]).
 */
@Component
class SmithTelemetryResume(
    private val runtime: RuntimeService,
    private val sandbox: SmithSandbox,
    private val telemetry: SmithTelemetry,
    instance: InstanceId,
    private val sessions: SmithSessions? = null,
) {
    private val host = instance.name
    private val log = LoggerFactory.getLogger(SmithTelemetryResume::class.java)

    /** This instance took the lease (a lone engine: at startup, the runs live before it restarted). */
    @EventListener(LeaseLost::class)
    fun leaseLost() = telemetry.handOff()

    @EventListener(LeaseAcquired::class)
    fun atStartup() {
        runCatching {
            for (execution in runtime.createExecutionQuery().messageEventSubscriptionName(SMITH_EXITED_MESSAGE).list()) {
                runCatching {
                    val runId = execution.processInstanceId
                    val v = runtime.getVariables(execution.id)
                    val handle = v[Vars.HANDLE] as? String ?: return@runCatching
                    if (!sandbox.status(handle).running) return@runCatching
                    // The model the run was started with is its session's (`engine.smith_session.model`), not a variable.
                    val labels = RunLabels(runId, (v[Vars.ISSUE] as? Number)?.toLong(), v[Vars.REPO] as String,
                        sessions?.session(runId)?.model ?: v[Vars.MODEL] as? String ?: "unknown", v[Vars.MODE] as? String ?: "work", host,
                        v[Vars.BILLED_ACCOUNT] as? String, v[Vars.JOB_ID] as? String)
                    telemetry.resumed(labels, handle)
                }.onFailure { log.warn("run {}: transcript not resumed: {}", execution.processInstanceId, it.message) }
            }
        }.onFailure { log.warn("live runs not resumed for the transcript: {}", it.message) }
    }
}

package com.codefrak.engine.smith

import com.codefrak.engine.lease.LeaseLost
import com.codefrak.engine.lease.LeaseAcquired
import com.codefrak.engine.lease.EngineLease
import org.operaton.bpm.engine.MismatchingMessageCorrelationException
import org.operaton.bpm.engine.OptimisticLockingException
import org.operaton.bpm.engine.RuntimeService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** The message `smith-run` waits at after starting its sandbox (bpmn/smith-run.bpmn, `event-smith-exited`). */
const val SMITH_EXITED_MESSAGE = "message-smith-exited"

/** One backstop scan's outcome: how many waiting runs it looked at, how many it found exited and collected. */
data class ExitScanReport(val checked: Int, val collected: Int)

/**
 * How `smith-run` learns its sandbox exited (engine/README.md, "Smith runs").
 * The process waits at a message catch event; this delivers the message,
 * with the exit code, two ways:
 *
 * - **the event**: `SmithSandbox.watchExits` (Docker's `die` events for
 *   Smith containers) calls [deliver] the moment a sandbox exits;
 * Both run on the lease's holder only (engine/README.md, "Replicas and the lease"): the watch starts when this
 * instance takes the lease and closes when it loses it, and the backstop skips a tick without it.
 *
 * - **the backstop**: [scanExits] lists every run still waiting on the
 *   message, asks the sandbox for each one's status, and delivers for those
 *   already exited. It runs at startup (exits while the engine was down),
 *   every `engine.smith.exit-scan-interval` (an event the stream dropped),
 *   and after the event stream reconnects (the gap it was down for).
 *
 * The two race on the same exit and collect exactly once: a waiting run has
 * one event subscription, and the first correlation consumes it in its own
 * transaction. The other delivery then finds nothing to correlate
 * ([MismatchingMessageCorrelationException]) or loses the row lock
 * ([OptimisticLockingException]); both are ignored. A `die` that arrives
 * before the start job's transaction has committed its subscription (the
 * container died within a moment of starting) is retried a few times over
 * about a minute, and otherwise left to the backstop.
 */
@Component
class SmithExitNotifier(
    private val runtime: RuntimeService,
    private val sandbox: SmithSandbox,
    @Value("\${engine.smith.exit-scan.enabled:true}") private val scanEnabled: Boolean,
    private val lease: EngineLease,
) {
    private val log = LoggerFactory.getLogger(SmithExitNotifier::class.java)
    private var subscription: AutoCloseable? = null

    /** How often a `die` that found no waiting run is retried, and how long between tries. */
    var earlyRetries = 6
    var earlyRetryMillis = 10_000L

    /** This instance took the lease (a lone engine: at startup): watch the exits, and collect those missed meanwhile. */
    @EventListener(LeaseAcquired::class)
    fun atStartup() {
        if (!scanEnabled) return
        runCatching { subscription?.close() }
        subscription = sandbox.watchExits(::onExit) { scanIfEnabled("reconnect") }
        scanIfEnabled("startup")
    }

    @Scheduled(fixedDelayString = "\${engine.smith.exit-scan-interval:PT3M}", initialDelayString = "\${engine.smith.exit-scan-interval:PT3M}")
    fun backstop() { if (lease.held()) scanIfEnabled("backstop") }

    @EventListener(LeaseLost::class)
    fun leaseLost() { runCatching { subscription?.close() }; subscription = null }

    @jakarta.annotation.PreDestroy
    fun stop() { runCatching { subscription?.close() } }

    private fun scanIfEnabled(why: String) {
        if (!scanEnabled) return
        runCatching { scanExits(why) }.onFailure { log.warn("{} exit scan failed: {}", why, it.message) }
    }

    /**
     * The event path: deliver now; if the run is not waiting yet, try again a
     * few times, then leave it to the backstop. Returns the deliveries tried.
     */
    fun onExit(exit: SandboxExit): Int {
        repeat(earlyRetries + 1) { attempt ->
            when (deliver(exit)) {
                Delivery.COLLECTED, Delivery.ALREADY -> return attempt + 1
                Delivery.NOT_WAITING -> {
                    if (attempt == earlyRetries) {
                        log.info("run {}: exit of {} found no waiting run after {} tries; the backstop will collect it",
                            exit.runId, exit.handle.take(12), attempt + 1)
                        return attempt + 1
                    }
                    Thread.sleep(earlyRetryMillis)
                }
            }
        }
        return earlyRetries + 1
    }

    enum class Delivery { COLLECTED, ALREADY, NOT_WAITING }

    /**
     * Correlate the message to the run's process instance: by run id when the
     * exit carries one, else by the `sandboxHandle` variable. Exactly one of
     * event and backstop gets [Delivery.COLLECTED]; the other is a no-op.
     */
    fun deliver(exit: SandboxExit): Delivery {
        val instanceId = exit.runId ?: runtime.createProcessInstanceQuery()
            .variableValueEquals(Vars.HANDLE, exit.handle).list().singleOrNull()?.id
            ?: return Delivery.NOT_WAITING.also { log.debug("exit of {}: no run has that handle", exit.handle.take(12)) }
        return try {
            runtime.createMessageCorrelation(SMITH_EXITED_MESSAGE)
                .processInstanceId(instanceId)
                .setVariable(Vars.EXIT_CODE, exit.exitCode)
                .correlate()
            log.info("run {}: sandbox {} exited {}", instanceId, exit.handle.take(12), exit.exitCode)
            Delivery.COLLECTED
        } catch (e: MismatchingMessageCorrelationException) {
            // No subscription: either already collected (the race, or gone), or the run is
            // still at its start task (the job that starts the sandbox has not committed
            // yet). Only the second is worth a retry.
            val starting = runtime.createExecutionQuery().processInstanceId(instanceId).activityId("task-start-smith").count() > 0
            if (starting) Delivery.NOT_WAITING else {
                log.info("run {}: exit of {} not delivered: nothing waits on the message (collected already, or no such run)",
                    instanceId, exit.handle.take(12))
                Delivery.ALREADY
            }
        } catch (e: OptimisticLockingException) {
            log.info("run {}: exit of {} already being collected", instanceId, exit.handle.take(12))
            Delivery.ALREADY
        }
    }

    /**
     * The backstop: every run waiting on the message whose sandbox has
     * already exited gets its message now. A sandbox the provider no longer
     * knows (removed by hand, or the daemon restarted) reads as exited with
     * code -1, never as a run that waits forever.
     */
    fun scanExits(why: String = "manual"): ExitScanReport {
        val waiting = runtime.createExecutionQuery().messageEventSubscriptionName(SMITH_EXITED_MESSAGE).list()
        var collected = 0
        for (execution in waiting) {
            val handle = runtime.getVariable(execution.id, Vars.HANDLE) as? String ?: continue
            // A gone sandbox comes back as exited (SmithSandbox.status); an error is not an
            // exit, so that run waits for the next scan or its event instead of failing.
            val status = runCatching { sandbox.status(handle) }.getOrElse {
                log.warn("{} exit scan: could not check {}: {}", why, handle.take(12), it.message)
                continue
            }
            if (status.running) continue
            val exit = SandboxExit(handle, execution.processInstanceId, status.exitCode ?: -1L)
            // One run's failed delivery must not skip the runs after it in this scan.
            val delivery = runCatching { deliver(exit) }.getOrElse {
                log.warn("{} exit scan: delivering {} failed: {}", why, handle.take(12), it.message)
                continue
            }
            if (delivery == Delivery.COLLECTED) collected++
        }
        if (collected > 0 || waiting.isNotEmpty()) log.info("{} exit scan: {} waiting, {} collected", why, waiting.size, collected)
        return ExitScanReport(checked = waiting.size, collected = collected)
    }
}

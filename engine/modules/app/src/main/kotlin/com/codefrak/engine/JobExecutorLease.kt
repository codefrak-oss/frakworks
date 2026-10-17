package com.codefrak.engine

import com.codefrak.engine.lease.EngineLease
import com.codefrak.engine.lease.LeaseAcquired
import com.codefrak.engine.lease.LeaseLost
import org.operaton.bpm.engine.ProcessEngine
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * The Operaton job executor runs on the lease's holder only (engine/README.md, "Replicas and the lease"). Every
 * asynchronous continuation, timer and plugin flow job then runs where the plugin jars are loaded (`PluginJars`, on
 * the ReadWriteOnce jar volume), never on a replica that never loaded them. A follower still starts processes and
 * completes tasks from its HTTP calls, synchronously, in Postgres; the holder's executor picks up what they leave.
 *
 * Operaton's starter starts the executor once the application is ready; this listener runs after it and stops it
 * again on an instance that does not hold the lease. Off with `operaton.bpm.job-execution.enabled=false` (the tests).
 */
@Component
class JobExecutorLease(
    private val engine: ProcessEngine,
    private val lease: EngineLease,
    @Value("\${operaton.bpm.job-execution.enabled:true}") private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(JobExecutorLease::class.java)

    private val executor get() = (engine.processEngineConfiguration as ProcessEngineConfigurationImpl).jobExecutor

    @EventListener(ApplicationReadyEvent::class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    fun atStartup() = sync()

    @EventListener(LeaseAcquired::class)
    fun acquired() = sync()

    @EventListener(LeaseLost::class)
    fun lost() = sync()

    @Synchronized private fun sync() {
        if (!enabled) return
        val executor = executor ?: return
        val held = lease.held()
        if (held && !executor.isActive) { executor.start(); log.info("job executor started (lease held)") }
        else if (!held && executor.isActive) { executor.shutdown(); log.info("job executor stopped (lease not held)") }
    }
}

package com.codefrak.engine.friction

import com.codefrak.engine.lease.EngineLease
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** The bug sync's clock: [FrictionBugs.sync] every `engine.doctor.bug-sync`, the doctor's cadence; absent with the grouper's pokes off. */
@Component
@ConditionalOnProperty("engine.friction.group-enabled", havingValue = "true", matchIfMissing = true)
class FrictionBugTick(private val bugs: FrictionBugs, private val lease: EngineLease) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${engine.doctor.bug-sync:PT10M}", initialDelayString = "\${engine.doctor.bug-sync:PT10M}")
    fun syncBugs() {
        if (!lease.held()) return
        runCatching { bugs.sync() }.onFailure { log.warn("friction bug sync failed: {}", it.message) }
    }
}

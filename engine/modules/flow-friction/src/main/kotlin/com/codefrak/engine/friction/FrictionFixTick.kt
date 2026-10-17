package com.codefrak.engine.friction

import com.codefrak.engine.lease.EngineLease
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * The fix sync's clock: [FrictionFixes.sync] every `engine.doctor.bug-sync`, beside the two-things bug's; absent with the grouper's pokes off.
 * The first pass comes [FIRST_PASS] after start, not a full interval: every merge to main redeploys the engine, and with merges
 * under ten minutes apart a PT10M initial delay meant no pass ever ran, so groups whose fix tickets merged stayed `fix-filed` (#2275).
 */
@Component
@ConditionalOnProperty("engine.friction.group-enabled", havingValue = "true", matchIfMissing = true)
class FrictionFixTick(private val fixes: FrictionFixes, private val lease: EngineLease) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${engine.doctor.bug-sync:PT10M}", initialDelayString = FIRST_PASS)
    fun syncFixes() {
        if (!lease.held()) return
        runCatching { fixes.syncNow() }.onFailure {
            if (it is FixSyncRunning) log.info("friction fix sync: an on-demand pass is running; this tick skips")
            else log.warn("friction fix sync failed: {}", it.message)
        }
    }

    companion object {
        /** How soon after start the first pass runs: long enough for the lease to settle, well under the time between merges. */
        const val FIRST_PASS = "PT1M"
    }
}

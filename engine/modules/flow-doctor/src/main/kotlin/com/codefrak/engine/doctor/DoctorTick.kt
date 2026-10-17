package com.codefrak.engine.doctor

import com.codefrak.engine.lease.EngineLease
import com.codefrak.engine.events.EngineEvents
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * The doctor's clock (engine/README.md, "Doctor"): a [Doctor.tick] every `engine.doctor.interval` (one minute
 * by default), and a [DoctorBugSync.sync] of its filed bugs' closes every `engine.doctor.bug-sync` (ten minutes);
 * `engine.doctor.enabled=false` leaves this bean out, so neither runs. A failed tick or sync is a WARN.
 */
@Component
@ConditionalOnProperty("engine.doctor.enabled", havingValue = "true", matchIfMissing = true)
class DoctorTick(private val doctor: Doctor, private val events: EngineEvents, private val sync: DoctorBugSync, private val lease: EngineLease) {
    private val log = LoggerFactory.getLogger(DoctorTick::class.java)

    /** One tick, then its `heartbeat` frame (engine/README.md, "Events"); with the doctor disabled, this bean is absent and neither happens. */
    @Scheduled(fixedDelayString = "\${engine.doctor.interval:PT1M}", initialDelayString = "\${engine.doctor.interval:PT1M}")
    fun tick() {
        if (!lease.held()) return
        runCatching { doctor.tick() }.onFailure { log.warn("doctor tick failed: {}", it.message) }
        runCatching { events.publish(doctor.heartbeat()) }.onFailure { log.warn("heartbeat not published: {}", it.message) }
    }

    /** The doctor bugs' closes, read from GitHub ([DoctorBugSync.sync]). */
    @Scheduled(fixedDelayString = "\${engine.doctor.bug-sync:PT10M}", initialDelayString = "\${engine.doctor.bug-sync:PT10M}")
    fun syncBugs() {
        if (!lease.held()) return
        runCatching { sync.sync() }.onFailure { log.warn("doctor bug sync failed: {}", it.message) }
    }
}

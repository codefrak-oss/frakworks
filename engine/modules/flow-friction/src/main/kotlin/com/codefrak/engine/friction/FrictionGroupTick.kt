package com.codefrak.engine.friction

import com.codefrak.engine.lease.EngineLease
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * The backstop poke (engine/README.md, "Friction notes"): every `engine.friction.group-interval` it wakes the
 * grouper, for a note inserted just before a restart or one whose `next_attempt_at` has come round. It only pokes;
 * the worker thread drains. Absent when `engine.friction.group-enabled` is false, as `DoctorTick` is without the doctor.
 */
@Component
@ConditionalOnProperty("engine.friction.group-enabled", havingValue = "true", matchIfMissing = true)
class FrictionGroupTick(private val grouper: FrictionGrouper, private val lease: EngineLease) {
    @Scheduled(fixedDelayString = "\${engine.friction.group-interval:PT1M}", initialDelayString = "\${engine.friction.group-interval:PT1M}")
    fun tick() { if (lease.held()) grouper.wake() }
}

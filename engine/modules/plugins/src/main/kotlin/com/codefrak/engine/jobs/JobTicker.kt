package com.codefrak.engine.jobs

import com.codefrak.engine.lease.EngineLease
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Starts the jobs whose `every` or `at` signal has come due (engine/README.md, "Custom jobs and the SDK"). One whose
 * last instance still runs is skipped, logged, and its `next_run_at` moved on all the same: a job's runs never overlap,
 * and a skipped or missed run is not caught up later (an engine that was down an hour comes back to one run, not sixty).
 * An `at` job is the exception: its one run would be lost, so it stays due and is tried again on the next tick.
 * Always a bean, so a test drives [tick] by hand; [JobTickClock] is the scheduled caller.
 */
@Component
class JobTicker(private val store: JobStore, private val starter: JobStarter) {
    private val log = LoggerFactory.getLogger(JobTicker::class.java)

    /** One tick at [now]: the instances started. Each job fails on its own, as a WARN. */
    fun tick(now: Instant = Instant.now()): List<String> = store.due(now).mapNotNull { job ->
        runCatching {
            val running = starter.running(job)
            if (running != null) {
                log.info("job {} ({}) skipped: {} still runs", job.name, job.id, running)
                val signal = job.parsedSignal()
                if (signal !is Signal.At) store.pushNext(job.id, signal.next(now))
                null
            } else starter.start(job, now)
        }.onFailure { log.warn("job {} ({}) could not start: {}", job.name, job.id, it.message) }.getOrNull()
    }
}

/**
 * The ticker's clock: once a second. `engine.jobs.tick-enabled=false` (the tests' properties) leaves this bean out, so
 * no test's jobs are ticked on a clock; JobTickerTest calls [JobTicker.tick] by hand.
 */
@Component
@ConditionalOnProperty("engine.jobs.tick-enabled", havingValue = "true", matchIfMissing = true)
class JobTickClock(private val ticker: JobTicker, private val lease: EngineLease) {
    private val log = LoggerFactory.getLogger(JobTickClock::class.java)

    @Scheduled(fixedDelay = 1000)
    fun tick() {
        if (!lease.held()) return
        runCatching { ticker.tick() }.onFailure { log.warn("job tick failed: {}", it.message) }
    }
}

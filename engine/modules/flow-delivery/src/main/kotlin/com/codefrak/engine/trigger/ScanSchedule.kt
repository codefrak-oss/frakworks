package com.codefrak.engine.trigger

import com.codefrak.engine.lease.LeaseAcquired
import com.codefrak.engine.lease.EngineLease
import com.codefrak.engine.ci.CiCheckWaits
import com.codefrak.engine.repos.TargetRepos
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import java.time.Duration
import java.time.Instant

/**
 * When the scan runs, besides a wake (`POST /hooks/github`, [com.codefrak.engine.webhook.WebhookWake]): at startup, for
 * whatever happened while the engine was down, every repo; and on each repo's own scan interval (`scan_interval_minutes`
 * on its `engine.target_repo` row; engine/README.md, "When it scans"), for a repo with no webhook, or a webhook
 * delivery GitHub lost in a repo that then went quiet. A tick each minute ([tick]) scans the repos whose interval has
 * passed since their last scan ([ScanClock]), then runs the engine-check pass ([CiCheckWaits]) for theirs alone.
 * `engine.scan.enabled=false` turns both off (the tests do); it leaves the webhook's wakes on.
 */
@Configuration
@EnableScheduling
class ScanSchedule(
    private val scanner: IssueScanner,
    private val ciWaits: CiCheckWaits,
    private val repos: TargetRepos,
    private val clock: ScanClock,
    private val lease: EngineLease,
    @Value("\${engine.scan.enabled:true}") private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(ScanSchedule::class.java)

    /** Whenever this instance takes the lease (a lone engine: at startup), for whatever happened while no leader scanned. */
    @EventListener(LeaseAcquired::class)
    fun atStartup() {
        if (!enabled) return
        runCatching { scanner.scan() }.onFailure { log.warn("startup scan failed: {}", it.message) }
        runCatching { ciWaits.poll() }.onFailure { log.warn("startup ci check pass failed: {}", it.message) }
    }

    @Scheduled(fixedDelay = TICK_MS, initialDelay = TICK_MS)
    fun tick() {
        if (!lease.held()) return
        scanDue(clock.now())
    }

    /**
     * Scans the repos whose interval has passed at `now` and polls their engine checks; null when it is off or no repo
     * is due, which costs no GitHub call. The repos are recorded as scanned at `now` even when the scan failed, so a
     * GitHub outage is retried on the interval, not every tick.
     */
    fun scanDue(now: Instant): ScanReport? {
        if (!enabled) return null
        val due = repos.list().filter { Duration.between(clock.since(it.fullName), now) >= Duration.ofMinutes(it.scanIntervalMinutes.toLong()) }
            .map { it.fullName }.toSet()
        if (due.isEmpty()) return null
        val report = runCatching { scanner.scan(due) }.onFailure { log.warn("scheduled scan of {} failed: {}", due.joinToString(", "), it.message) }
            .getOrElse { ScanReport(emptyList(), listOf(it.message.orEmpty()), due.toList()) }
        clock.scanned(due, now)
        val checked = runCatching { ciWaits.poll(due) }.onFailure { log.warn("scheduled ci check pass failed: {}", it.message) }.getOrDefault(emptyList())
        val result = report.copy(actions = report.actions + checked, repos = due.sorted())
        log.info("scheduled scan of {}: {} acted on, {} failed", result.repos.joinToString(", "), result.actions.size, result.failures.size)
        return result
    }

    private companion object {
        /** How often the scheduler looks for repos whose interval is up: the shortest interval there is. */
        const val TICK_MS = 60_000L
    }
}

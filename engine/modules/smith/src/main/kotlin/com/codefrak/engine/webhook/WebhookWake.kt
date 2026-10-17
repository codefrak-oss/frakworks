package com.codefrak.engine.webhook

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * A wake: something changed on GitHub, never what, so each one scans every target repo, then wakes
 * the flows waiting for an engine check that has completed ([FlowWakes.pollChecks]; a `check_suite`
 * delivery is a wake like any other). At most one a second ([INTERVAL]): a burst of deliveries
 * collapses into one wake, and nothing is lost by that, since the one scan reads all of GitHub.
 *
 * Each wake scans twice: at once, and again `engine.wake.follow-up` later. GitHub's
 * labelled-issues listing lags the label by a few seconds (seen on the first live run,
 * 2026-09-24), so a scan the moment a label's webhook lands can miss it. A follow-up that acts on
 * something is logged as a lag catch, so the logs say whether the second scan is still needed.
 * Not switched off by `engine.scan.enabled`: a wake only happens when a delivery comes.
 *
 * Only the lease holder wakes (#1802), as only it runs the scheduled scan and Operaton's jobs: a follower that gets a
 * delivery (the Service sends it to any ready replica) hands it to the holder ([WakeLeader.hand]), which takes it on its
 * next [relayed] tick, a second later at most, and wakes as if the delivery were its own.
 */
@Component
class WebhookWake(
    private val flows: FlowWakes,
    private val leader: WakeLeader,
    @Value("\${engine.wake.follow-up:PT15S}") private val followUp: Duration,
) {
    private val log = LoggerFactory.getLogger(WebhookWake::class.java)
    private var last: Instant? = null
    private var count = 0L
    /** Replaced in tests. */
    var sleep: (Duration) -> Unit = { Thread.sleep(it) }
    var clock: Clock = Clock.systemUTC()
    /** Off the request thread, so the delivery is answered at once. */
    var dispatch: (() -> Unit) -> Unit = { task -> Thread.ofVirtual().name("github-wake").start { task() } }

    /** Wakes when `(event, action)` is allowlisted and the last wake is [INTERVAL] or more ago; returns whether it did. */
    fun offer(event: String, action: String?): Boolean {
        if (event to action !in ALLOWLIST) return false
        if (!leader.leading()) {
            runCatching { leader.hand() }.onFailure { log.warn("handing a wake to the lease holder failed: {}", it.message) }
            return false
        }
        return wakeNow()
    }

    /** On the holder, each second: a wake a follower handed over ([WakeLeader.hand]). */
    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    fun relayed() {
        if (!leader.leading()) return
        if (runCatching { leader.take() }.getOrDefault(false)) wakeNow()
    }

    private fun wakeNow(): Boolean {
        val n = synchronized(this) {
            val now = clock.instant()
            if (last?.let { Duration.between(it, now) < INTERVAL } == true) return false
            last = now
            ++count
        }
        dispatch { runCatching { wake(n) }.onFailure { log.warn("wake {} failed: {}", n, it.message) } }
        return true
    }

    fun wake(n: Long) {
        val first = scan()
        pollChecks()
        sleep(followUp)
        val second = scan()
        pollChecks()
        if (second.isNotEmpty()) {
            log.info("wake {}: the follow-up scan {} later acted on {} that the first ({} actions) missed; GitHub's labelled-issues listing lagged: {}",
                n, followUp, second.size, first.size,
                second.joinToString())
        } else {
            log.debug("wake {}: follow-up scan found nothing new", n)
        }
    }

    private fun scan() = runCatching { flows.scan() }
        .onFailure { log.warn("wake scan failed: {}", it.message) }.getOrDefault(emptyList())

    /** Twice per wake as the scan is: GitHub's check-runs listing may lag its `check_suite` delivery too. */
    private fun pollChecks() {
        runCatching { flows.pollChecks() }.onFailure { log.warn("ci check pass failed: {}", it.message) }
    }

    companion object {
        val INTERVAL: Duration = Duration.ofSeconds(1)

        /** The event/action pairs that wake; `ping` carries no action. */
        val ALLOWLIST: Set<Pair<String, String?>> = setOf(
            "issues" to "labeled",
            "issues" to "unlabeled",
            "issues" to "reopened",
            "issues" to "closed",
            "pull_request" to "opened",
            "pull_request" to "synchronize",
            "pull_request" to "closed",
            "pull_request_review" to "submitted",
            "check_suite" to "completed",
            "ping" to null,
        )
    }
}

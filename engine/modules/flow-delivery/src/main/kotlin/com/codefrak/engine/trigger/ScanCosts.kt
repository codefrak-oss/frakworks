package com.codefrak.engine.trigger

import com.codefrak.engine.ci.CiCheckWaits
import com.codefrak.engine.repos.DeferredIssues
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.ScanCalls
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * When each target repo was last scanned, in memory: the scheduler's tick ([ScanSchedule.scanDue]) scans a repo once
 * its interval has passed since then, or since the engine started for a repo not scanned yet. Every scan records the
 * repos it covered, whatever called it (startup, a tick, a wake, POST /scan).
 */
@Component
class ScanClock {
    /** Replaced in tests. */
    var clock: Clock = Clock.systemUTC()
    private var started: Instant = clock.instant()
    private val last = ConcurrentHashMap<String, Instant>()

    fun now(): Instant = clock.instant()

    /** Restarts the clock as if the engine started at `at` and nothing was scanned yet. For tests. */
    fun reset(at: Instant) { started = at; last.clear() }

    fun scanned(repos: Collection<String>, at: Instant) = repos.forEach { repo -> last.merge(repo, at) { a, b -> maxOf(a, b) } }

    /** When `repo` was last scanned, or when the engine started. */
    fun since(repo: String): Instant = last[repo] ?: started
}

/**
 * What one scan of each repo costs in GitHub calls (engine/README.md, "When it scans"), from what the engine already
 * knows, with no GitHub call of its own: the `authorized` listing (none when nobody may authorize anything there,
 * which the scan skips; one more call a 100 issues), one events read per open `authorized` issue the repo's last scan
 * listed (both 0 since the cutover: the scan lists no labels), one blocked-by read per approval waiting on a blocker
 * (`engine.deferred_issue`), and one check-runs read per
 * flow waiting on the repo's engine check. A fresh approval costs a few reads more once (its issue, its body's edit
 * time, its blockers); the estimate leaves those out, as they do not repeat.
 */
@Component
class ScanCosts(private val deferred: DeferredIssues, private val ciWaits: CiCheckWaits) {
    fun of(repos: List<String>): Map<String, ScanCalls> {
        if (repos.isEmpty()) return emptyMap()
        val waiting = deferred.list().groupingBy { it.repo }.eachCount()
        val checks = ciWaits.waitingByRepo()
        // No label listing since the cutover (engine/docs/work-items.md, "Tickets"): `listing` and `authorized` are 0.
        return repos.associateWith { repo -> ScanCalls(0, 0, waiting[repo] ?: 0, checks[repo.lowercase()] ?: 0) }
    }
}

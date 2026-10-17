package com.codefrak.engine.repos

import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The setup check on request, its result on the event stream (engine/README.md, "A new target repo's GitHub setup"):
 * [request] answers at once and [RepoSetupCheck.check] runs on a virtual thread, publishing `setup` frames
 * (`started`, then `done` with the report or `failed` with GitHub's error). The last report per repo is kept in
 * memory with its `checkedAt`; one younger than [FRESH_FOR] is re-published instead of read again, unless forced.
 * A check already running for a repo is never started twice: a second request joins it. A failure is not kept.
 */
@Component
class RepoSetupChecks(
    private val setup: RepoSetupCheck,
    private val events: EngineEvents,
    private val clock: Clock,
    /** Runs a check off the caller's thread; a virtual thread but in a test. */
    private val dispatch: (() -> Unit) -> Unit,
) {
    @Autowired constructor(setup: RepoSetupCheck, events: EngineEvents) :
        this(setup, events, Clock.systemUTC(), { task -> Thread.ofVirtual().name("repo-setup-check").start { task() } })

    private val log = LoggerFactory.getLogger(RepoSetupChecks::class.java)

    companion object {
        /** How long a kept report stands for a fresh read: the 5 minutes the desktop's timer used to wait. */
        val FRESH_FOR: Duration = Duration.ofMinutes(5)
    }

    private data class Kept(val report: SetupReport, val checkedAt: Instant)

    private val kept = ConcurrentHashMap<String, Kept>()
    private val running = mutableSetOf<String>()

    /** Starts (or joins) a check for each row, or re-publishes its fresh report; never reads GitHub on the caller's thread. */
    fun request(rows: List<TargetRepo>, force: Boolean): SetupCheckQueued {
        val queued = mutableListOf<String>()
        val fresh = mutableListOf<Pair<String, Kept>>()
        val start = mutableListOf<TargetRepo>()
        val now = clock.instant()
        synchronized(running) {
            for (row in rows) {
                val repo = row.fullName
                val last = kept[repo]
                when {
                    repo in running -> queued += repo
                    !force && last != null && Duration.between(last.checkedAt, now) < FRESH_FOR -> fresh += repo to last
                    else -> { running += repo; queued += repo; start += row }
                }
            }
        }
        for ((repo, last) in fresh) events.publish(EngineEvent.Setup(repo, "done", last.checkedAt, last.report))
        for (row in start) dispatch { run(row) }
        return SetupCheckQueued(queued, fresh.map { it.first })
    }

    private fun run(row: TargetRepo) {
        val repo = row.fullName
        try {
            events.publish(EngineEvent.Setup(repo, "started"))
            val report = setup.check(row)
            val checkedAt = clock.instant()
            kept[repo] = Kept(report, checkedAt)
            events.publish(EngineEvent.Setup(repo, "done", checkedAt, report))
        } catch (e: Exception) {
            log.info("setup check of {} failed: {}", repo, e.toString())
            kept.remove(repo)
            events.publish(EngineEvent.Setup(repo, "failed", error = "GitHub: ${e.message}"))
        } finally {
            synchronized(running) { running -= repo }
        }
    }
}

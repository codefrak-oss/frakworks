package com.codefrak.engine.camunda

import com.codefrak.engine.lease.EngineLease
import com.codefrak.engine.lease.LeaseAcquired
import com.codefrak.engine.lease.LeaseLost
import com.codefrak.engine.smith.SmithRunStats
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.ConcurrentHashMap

/**
 * The one-time fill of the projection from history (engine/README.md, "Ticket timeline"), once per ticket, recorded in
 * `engine.timeline_backfill`: by the lease holder's sweep after startup (every ticket `engine.handled_issue`,
 * `engine.intake_ticket` and `engine.smith_run_stats` know with no row, one at a time) and by the controller on a
 * ticket's first request with no row. A ticket with no instance in history gets no row, so it is looked at again.
 */
@Component
class TimelineBackfill(
    private val reader: TimelineHistoryReader, private val projection: TimelineProjection, private val issues: IssueInstances,
    private val stats: SmithRunStats, private val lease: EngineLease, transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)
    private val log = LoggerFactory.getLogger(TimelineBackfill::class.java)
    private val locks = ConcurrentHashMap<String, Any>()
    @Volatile private var sweeping = false

    fun recorded(repo: String, issue: Long): Boolean = projection.backfilled(repo, issue)

    /** The ticket's fill, once: a second caller for the same ticket waits for the first and then finds the row. Whether roots were found. */
    fun ensure(repo: String, issue: Long, source: String): Boolean = synchronized(locks.computeIfAbsent("$repo#$issue") { Any() }) {
        if (projection.backfilled(repo, issue)) return true
        backfill(repo, issue, source)
    }

    /** The fill itself, in one transaction: the ticket's roots from history, each replayed, and the row. False with no root in history. */
    fun backfill(repo: String, issue: Long, source: String): Boolean {
        val roots = issues.roots(repo, issue)
        if (roots.isEmpty()) return false
        transactions.execute {
            val n = roots.sumOf { reader.replay(it) }
            projection.recordBackfill(repo, issue, source, roots.size)
            log.info("timeline backfill ({}): {}#{}: {} roots, {} instances", source, repo, issue, roots.size, n)
        }
        return true
    }

    @EventListener(LeaseAcquired::class)
    fun onLease() {
        if (sweeping) return
        sweeping = true
        Thread.ofVirtual().name("timeline-backfill").start {
            try { sweep(stop = { !sweeping || !lease.held() }) } finally { sweeping = false }
        }
    }

    @EventListener(LeaseLost::class)
    fun onLost() { sweeping = false }

    /**
     * Every ticket with no row, one at a time; a ticket that fails is logged and left for the next sweep. The startup
     * thread passes [stop], true once the lease is lost, so the sweep ends between two tickets; a direct call runs through.
     */
    fun sweep(stop: () -> Boolean = { false }): Int {
        val tickets = runCatching { (projection.ticketsWithoutBackfill() + stats.ticketsWithoutBackfill()).distinct() }
            .onFailure { log.warn("timeline backfill: tickets not listed: {}", it.message) }.getOrDefault(emptyList())
        var filled = 0
        for ((repo, issue) in tickets) {
            if (stop()) break
            runCatching { if (ensure(repo, issue, "startup")) filled++ }
                .onFailure { log.warn("timeline backfill: {}#{} not filled: {}", repo, issue, it.message) }
        }
        if (tickets.isNotEmpty()) log.info("timeline backfill: {} of {} tickets filled", filled, tickets.size)
        return filled
    }
}

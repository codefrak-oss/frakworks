package com.codefrak.engine.events

import com.codefrak.engine.board.BoardIssue
import com.codefrak.engine.board.BoardRows
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The issue a root flow is about, and the flow's kind (its process definition key) and variables that go on the frame. */
data class FlowIssue(val repo: String, val number: Long, val kind: String, val prUrl: String?, val outcome: String?)

/** A root instance's issue, or null for a root with none: `FlowIssues` in engine-api, which reads it from the history. */
fun interface FlowIssueLookup {
    fun of(root: String): FlowIssue?
}

/**
 * Turns the history handler's changes into `flow` and `incident` frames, after the command commits and off its
 * thread (engine/README.md, "Events"). `flow` changes are coalesced per root over [EngineEvents.FLOW_WINDOW]: one
 * issue read and one blockers read per burst, one frame with the board row as it stands at the window's end and
 * the burst's last `step` and `stage`. A `started` in the window is reported over the steps that follow it in the
 * same command, and an `ended` or `cancelled` over everything, so a lifecycle change is never swallowed by the step
 * that came with it. When the row cannot be read, the frame goes out with `issue: null`. `incident` frames carry no
 * row and are not coalesced.
 */
@Component
class FlowEvents(private val bus: EngineEvents, @Lazy private val issues: FlowIssueLookup, @Lazy private val board: BoardRows) {
    private val log = LoggerFactory.getLogger(FlowEvents::class.java)

    /** The coalescing window; [EngineEvents.FLOW_WINDOW], shortened by tests. */
    @Volatile var window: Duration = EngineEvents.FLOW_WINDOW

    private class Burst(first: FlowChange) {
        var last = first
        /** The strongest lifecycle state seen in the window: `ended`/`cancelled`, else `started`, else none. */
        var lifecycle: String? = null
        init { add(first) }
        fun add(change: FlowChange) {
            when (change.state) {
                "ended", "cancelled" -> lifecycle = change.state
                "started" -> if (lifecycle == null) lifecycle = "started"
                else -> last = change
            }
        }
        fun frame(): FlowChange = when (lifecycle) {
            null -> last
            "started" -> last.copy(state = "started")
            else -> last.copy(state = lifecycle!!, step = null, stage = null)
        }
    }

    private val bursts = ConcurrentHashMap<String, Burst>()
    /** One fair lock per root, so a slow row read never lets a later burst's frame overtake an earlier one's. */
    private val order = ConcurrentHashMap<String, ReentrantLock>()

    /** From the history handler, inside the command: queued for the window after the command commits. */
    fun changed(change: FlowChange) = bus.afterCommit { add(change) }

    /**
     * From the history handler, inside the command: published after the command commits, on a thread of its own. A root-cause
     * incident is a row of `GET /needs` too (`board/Needs.kt`, `incidents`), so its `open` is a `needs` `incident` `added`
     * and its `resolved` or `deleted` a `removed`, with the incident id the row's `actionId`. A propagated incident is
     * a row too once its root cause is gone, so every incident's change is a `needs` frame, not only a root cause's
     * (#1172: the propagated row's removal was never announced).
     */
    fun incident(change: IncidentChange) = bus.afterCommit {
        Thread.ofVirtual().name("events-incident").start {
            val issue = resolve(change.root) ?: return@start
            bus.publish(EngineEvent.Incident(issue.repo, issue.number, change.incidentId, change.instance, change.root, change.state,
                change.activityId, change.type, change.message))
            bus.publish(EngineEvent.Needs(issue.repo, "incident", if (change.state == "open") "added" else "removed", issue.number, change.incidentId))
        }
    }

    private fun add(change: FlowChange) {
        var opened = false
        bursts.compute(change.root) { _, burst ->
            if (burst != null) burst.also { it.add(change) }
            else Burst(change).also { opened = true }
        }
        if (opened) Thread.ofVirtual().name("events-flow").start { flush(change.root) }
    }

    private fun flush(root: String) {
        Thread.sleep(window)
        val burst = bursts.remove(root) ?: return
        try {
            order.computeIfAbsent(root) { ReentrantLock(true) }.withLock {
                val change = burst.frame()
                val issue = resolve(root) ?: return
                bus.publish(EngineEvent.Flow(issue.repo, issue.number, change.instance, root, issue.kind, change.state, change.step, change.stage,
                    issue.prUrl, issue.outcome, rowOrNull(issue)))
                // A flow that ended or was cancelled takes the `GET /needs` row its answer task was, recorded question or
                // not (`board/Needs.kt`, `list`), and no answer endpoint said so (#1172). Published after the commit.
                // Only the root's own end: a call activity's child ending leaves the root's answer row where it was.
                if (change.instance == root && (change.state == "ended" || change.state == "cancelled"))
                    bus.publish(EngineEvent.Needs(issue.repo, "question", "removed", issue.number, root))
            }
        } finally {
            // Whatever way the burst ended (a frame, or a root with no issue), the lock entry goes unless a later burst needs it.
            if (bursts[root] == null) order.remove(root)
        }
    }

    /** How many roots hold a burst's ordering lock right now; for tests, which expect none once the frames are out. */
    fun locksHeld(): Int = order.size

    private fun resolve(root: String): FlowIssue? = runCatching { issues.of(root) }
        .onFailure { log.warn("events: could not resolve the issue of {}: {}", root, it.toString()) }.getOrNull()

    private fun rowOrNull(issue: FlowIssue) = board.rowOrNull(issue.repo, issue.number)
}

private val rowLog = LoggerFactory.getLogger(BoardRows::class.java)

/**
 * The issue's board row, read by number, or null when GitHub could not be read for it (logged at warn): a frame that
 * carries a row still goes out on time without it.
 */
fun BoardRows.rowOrNull(repo: String, number: Long): BoardIssue? = runCatching { row(repo, number) }
    .onFailure { rowLog.warn("events: no board row for {}#{}: {}", repo, number, it.message) }.getOrNull()

/** How long a frame published on a caller's thread waits for its board row ([rowWithin]); GitHub gives a webhook delivery ten seconds. */
val ROW_WAIT: Duration = Duration.ofSeconds(3)

/**
 * [rowOrNull], read on a virtual thread of its own and waited for at most `wait`: null past it (logged at warn). For a
 * frame published on a thread that must not be held by GitHub (a webhook delivery's, an Operaton command's), and so the
 * read never runs inside the caller's command context.
 */
fun BoardRows.rowWithin(repo: String, number: Long, wait: Duration = ROW_WAIT): BoardIssue? = runCatching {
    CompletableFuture.supplyAsync({ rowOrNull(repo, number) }) { Thread.ofVirtual().name("board-row").start(it) }
        .get(wait.toMillis(), TimeUnit.MILLISECONDS)
}.onFailure { rowLog.warn("events: no board row for {}#{} within {}: {}", repo, number, wait, it.toString()) }.getOrNull()

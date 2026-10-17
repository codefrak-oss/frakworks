package com.codefrak.engine.mcp

import com.codefrak.engine.engineJson
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.settings.ParentAwaitCap
import com.codefrak.engine.workitems.WorkItem
import com.codefrak.engine.workitems.WorkItemListener
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * What a live parent's await_children waits on (#2571; engine/README.md, "Live parent"): every frame this instance's
 * [EngineEvents] delivers ([EngineEvents.onDeliver], its own and the other replicas' alike) that is about an issue goes
 * into a bounded journal, each with a sequence number, and [await] blocks on a condition the next frame signals: no
 * polling. A frame is about issue N when its data carries `number` N, or, for a `push`, when its ref is a Smith's
 * branch `smith/issue-N` (a child's first push). The journal keeps `session`, `flow`, `ticket`, `needs`, `incident`
 * and `push` frames, but only significant ones wake a parent (#2591, [wakes]): a `flow` whose root moved to another
 * stage that is not a gateway (the run before it ended with its outcome: work done, security or review findings, a
 * question, a PR opened, a merge) or that ended or was cancelled; a `ticket` `closed`; a `needs` `added`; an
 * `incident` `open`; a `push`. A `session` frame and a `flow` step inside a run (`start smith`, `smith exited`) or at a
 * gateway (`outcome?`) are bookkeeping: journaled and answered with the next wake, never a wake of their own. After the
 * first waking frame [await] waits [coalesce] more (bounded by its timeout) and answers everything that came meanwhile.
 *
 * A cursor is `<instance>:<seq>`: [instance] is this engine process's, so a cursor another replica (or an earlier
 * process) gave is read as no cursor, and the answer says `cursorReset`.
 *
 * A work-item split's children (engine/docs/work-items.md, "Live parent on work items") are keyed by their item id, not
 * a number: a frame whose data carries a string `item` is journaled under it, and so is every work item state change
 * the store hands this [WorkItemListener] (an `item` change; done or cancelled wakes).
 */
@Component
class ChildWatch(events: EngineEvents, private val capSetting: () -> Duration, private val coalesce: Duration = COALESCE) : WorkItemListener {
    @Autowired constructor(events: EngineEvents, setting: ParentAwaitCap) : this(events, setting::get)

    /**
     * One frame about an issue: its sequence number, the issue, the `event:` type and its `data`; whether it [wakes] a
     * parent, and when it [arrived] (`System.nanoTime`), which the coalescing window counts from. A work item's change
     * carries its [item] id, and [number] is 0.
     */
    data class Change(val seq: Long, val repo: String, val number: Long, val event: String, val data: Map<String, Any?>,
                      val wakes: Boolean = true, val arrived: Long = System.nanoTime(), val item: String? = null)

    /** What [await] answers: the matching changes (oldest first), the cursor to pass next, and whether the cursor was dropped. */
    data class Awaited(val changes: List<Change>, val cursor: String, val cursorReset: Boolean, val truncated: Boolean)

    companion object {
        /** Frames kept for a cursor to catch up on; a parent's look every 15 minutes needs far fewer. */
        const val JOURNAL = 5000
        /** The `event` of a work item state change ([changed]). */
        const val ITEM = "item"
        /** The events journaled; anything else (`created`, `intake`, ...) is not a child's change. Which of them wake: [wakes]. */
        val KEPT = setOf("session", "flow", "ticket", "needs", "incident", "push", "question")
        /** How long [await] gathers more frames after the first waking one (#2591). */
        val COALESCE: Duration = Duration.ofSeconds(30)
        private val SMITH_BRANCH = Regex("refs/heads/smith/issue-(\\d+)")
        /** The `data` fields a change is reported with; the board row and the rest stay out of the parent's context. */
        private val FIELDS = listOf("action", "state", "step", "stage", "outcome", "prUrl", "prNumber", "label", "mode", "kind",
            "runId", "exitCode", "type", "activityId", "message", "ref", "after", "at", "text", "askedOf", "by")
    }

    val instance: String = UUID.randomUUID().toString().take(8)
    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    private val journal = ArrayDeque<Change>()
    private var seq = 0L
    /** Each issue's last non-gateway root stage a `flow` frame named, so a stage change is told from a step within it. */
    private val stages = HashMap<Pair<String, String>, String>()

    init { events.onDeliver(::take) }

    /** The cap [await] waits for, as the engine setting holds it. */
    fun cap(): Duration = capSetting()

    /** Journals [frame] when it is a child's change; called on the delivering thread, so it only parses and signals. */
    fun take(repo: String, frame: String) {
        val type = frame.lineSequence().firstOrNull { it.startsWith("event: ") }?.removePrefix("event: ") ?: return
        if (type !in KEPT) return
        val text = frame.lineSequence().firstOrNull { it.startsWith("data: ") }?.removePrefix("data: ") ?: return
        @Suppress("UNCHECKED_CAST")
        val data = runCatching { engineJson.readValue(text, Map::class.java) as Map<String, Any?> }.getOrNull() ?: return
        (data["item"] as? String)?.takeIf { it.isNotBlank() }?.let { item ->
            return lock.withLock { journal(Change(0, repo, 0, type, fields(data), wakes(repo, item, type, data), item = item)) }
        }
        val number = (data["number"] as? Number)?.toLong()
            ?: (data["ref"] as? String)?.let { SMITH_BRANCH.matchEntire(it) }?.groupValues?.get(1)?.toLong()
            ?: return
        lock.withLock { journal(Change(0, repo, number, type, fields(data), wakes(repo, number, type, data))) }
    }

    /** A work item's state change: journaled under its id, waking when it is done or cancelled. */
    override fun changed(item: WorkItem) {
        journal(Change(0, item.repo, 0, ITEM, mapOf("state" to item.state),
            item.state == WorkItem.DONE || item.state == WorkItem.CANCELLED, item = item.id))
    }

    private fun fields(data: Map<String, Any?>) = FIELDS.mapNotNull { k -> data[k]?.let { k to it } }.toMap()

    private fun journal(change: Change): Unit = lock.withLock {
        journal.addLast(change.copy(seq = ++seq))
        while (journal.size > JOURNAL) journal.removeFirst()
        arrived.signalAll()
    }

    /**
     * Whether a frame is significant enough to end a parent's wait (#2591); under [lock]. A `flow` frame's `stage` is its
     * root's activity id: the same through every step of a run (`start smith`, `smith exited`), a gateway's
     * (`gateway-...`, `outcome?`) right after one, and another activity's only once the run ended and the flow acted on
     * its outcome.
     */
    private fun wakes(repo: String, number: Long, type: String, data: Map<String, Any?>) = wakes(repo, number.toString(), type, data)

    private fun wakes(repo: String, child: String, type: String, data: Map<String, Any?>): Boolean = when (type) {
        "flow" -> when (data["state"]) {
            "ended", "cancelled" -> { stages.remove(repo.lowercase() to child); true }
            else -> {
                val stage = data["stage"] as? String
                if (stage == null || stage.startsWith("gateway-")) false
                else stages.put(repo.lowercase() to child, stage).let { it != null && it != stage }
            }
        }
        "ticket" -> data["action"] == "closed"
        "needs" -> data["state"] == "added"
        "incident" -> data["state"] == "open"
        "push" -> true
        "question" -> data["state"] == "asked"
        else -> false
    }

    /**
     * The changes to [children] of [repo] after [cursor] (none: from now), waking and not. Blocks until a waking one is
     * journaled (or is there already) or [timeout] passes; after the first waking one, waits until [coalesce] past its
     * arrival (never past [timeout]) and answers everything gathered, the non-waking frames too. At the timeout with no
     * waking frame it answers none, and the cursor stays where it was so the bookkeeping frames come with the next wake.
     */
    fun await(repo: String, children: Set<Long>, cursor: String?, timeout: Duration = cap()): Awaited =
        await(repo, children, emptySet(), cursor, timeout)

    /** As [await], for numbered [children] and work item [items] alike (a work-item split's children, by id). */
    fun await(repo: String, children: Set<Long>, items: Set<String>, cursor: String?, timeout: Duration = cap()): Awaited = lock.withLock {
        val parsed = cursor?.split(":")?.takeIf { it.size == 2 && it[0] == instance }?.get(1)?.toLongOrNull()
        val after = parsed ?: seq
        val truncated = parsed != null && journal.isNotEmpty() && journal.first().seq > after + 1
        fun matching() = journal.filter { it.seq > after && (if (it.item != null) it.item in items else it.number in children) && it.repo.equals(repo, ignoreCase = true) }
        val deadline = System.nanoTime() + timeout.toNanos()
        var first = matching().firstOrNull { it.wakes }
        while (first == null && deadline - System.nanoTime() > 0) {
            arrived.awaitNanos(deadline - System.nanoTime())
            first = matching().firstOrNull { it.wakes }
        }
        if (first == null) return@withLock Awaited(emptyList(), "$instance:$after", cursor != null && parsed == null, truncated)
        val until = (first.arrived + coalesce.toNanos()).let { if (it - deadline < 0) it else deadline }
        while (until - System.nanoTime() > 0) arrived.awaitNanos(until - System.nanoTime())
        Awaited(matching(), "$instance:$seq", cursor != null && parsed == null, truncated)
    }
}

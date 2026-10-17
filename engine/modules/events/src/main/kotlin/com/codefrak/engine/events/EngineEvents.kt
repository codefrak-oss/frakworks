package com.codefrak.engine.events

import org.operaton.bpm.engine.impl.cfg.TransactionState
import org.operaton.bpm.engine.impl.context.Context
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit

/**
 * One client's place on the bus: a bounded queue of frames, drained by the client's own thread
 * ([EventsController]). `repo` is a target repo's full name or [EngineEvent.FLEET]. The bus closes it when
 * its queue is full; the controller closes it when the client goes. Once closed it takes nothing more, and
 * [next] returns [CLOSE] once the frames before the close are drained.
 */
class EventSubscription constructor(val repo: String, capacity: Int) {
    /** One slot past `capacity` is reserved for [CLOSE], so a close always lands. */
    private val queue = ArrayBlockingQueue<String>(capacity + 1)
    @Volatile var closed = false
        private set
    /** True when the bus closed it for falling behind, so the controller can log why. */
    @Volatile var overflowed = false
        private set

    /** Queues the frame; false (and closed) when the queue was full. */
    @Synchronized fun offer(frame: String): Boolean {
        if (closed) return false
        if (queue.remainingCapacity() <= 1) { overflowed = true; close(); return false }
        queue.add(frame)
        return true
    }

    @Synchronized fun close() {
        if (closed) return
        closed = true
        queue.offer(CLOSE)
    }

    /** The next frame, [CLOSE] when the subscription is over, or null after `timeout` with nothing to send. */
    fun next(timeout: Duration): String? = queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS)

    companion object {
        /** The sentinel [next] returns once: the subscription is closed and drained. */
        const val CLOSE = ""
    }
}

/**
 * The engine's event bus (engine/README.md, "Events"): `GET /events` subscribes, every source publishes.
 * [publish] serializes the frame once and offers it to each subscriber's bounded queue; it never blocks
 * on a client, and a subscriber whose queue is full is closed (the client reconnects and snapshots). A
 * frame is kept nowhere once offered: no replay, no ids. Publishing from inside an Operaton command goes
 * through [publishAfterCommit], so a rolled-back step is never announced. Each frame also goes to the other engine
 * replicas through the [EventRelay], and theirs come in to this instance's subscribers, so a client on any replica
 * sees every event (engine/README.md, "Replicas and the lease"); null (unit tests) relays nothing.
 */
@Component
class EngineEvents(private val json: ObjectMapper, private val relay: EventRelay? = null) {
    private val log = LoggerFactory.getLogger(EngineEvents::class.java)
    private val subscribers = CopyOnWriteArraySet<EventSubscription>()
    private val listeners = CopyOnWriteArrayList<(EngineEvent) -> Unit>()
    private val frameListeners = CopyOnWriteArrayList<(String, String) -> Unit>()

    init { relay?.listen(::deliver) }

    companion object {
        /** Frames a subscriber may fall behind by before the engine closes it. */
        const val QUEUE_CAPACITY = 1000
        /** How long an idle stream goes before a `: ping` comment is written (the client gives up at three). */
        val PING_INTERVAL: Duration = Duration.ofSeconds(15)
        /** How long `flow` frames of one issue are coalesced before one goes out with the board row ([FlowEvents]). */
        val FLOW_WINDOW: Duration = Duration.ofSeconds(1)
    }

    /** Adds a subscriber for `repo` (a target repo's full name, or [EngineEvent.FLEET] for every event). */
    fun subscribe(repo: String): EventSubscription = EventSubscription(repo, QUEUE_CAPACITY).also { subscribers += it }

    /** Closes and forgets the subscription; idempotent. */
    fun unsubscribe(subscription: EventSubscription) {
        subscription.close()
        subscribers -= subscription
    }

    /** How many subscriptions are open; for tests and logs. */
    val subscriberCount: Int get() = subscribers.size

    /**
     * Calls [listener] with every event [publish]ed on this instance (never one relayed from another replica, so a reaction
     * happens once), on the publishing thread: it must hand anything slow to a thread of its own (the push notifier does).
     */
    fun onPublish(listener: (EngineEvent) -> Unit) { listeners += listener }

    /**
     * Calls [listener] with `(repo, frame)` for every frame this instance [deliver]s: one published here and one relayed
     * from another replica alike, so a reader of every replica's events sees each once (await_children's `ChildWatch`
     * in engine-api). On the delivering thread: it must not block.
     */
    fun onDeliver(listener: (String, String) -> Unit) { frameListeners += listener }

    /** Serializes once and offers to every subscription that takes `event.repo`; a full one is closed and dropped. */
    fun publish(event: EngineEvent) {
        val frame = frame(event.event, json.writeValueAsString(event))
        deliver(event.repo, frame)
        relay?.send(event.repo, frame)
        for (l in listeners) try { l(event) } catch (e: Exception) { log.warn("events: a listener failed on a {} frame: {}", event.event, e.toString()) }
    }

    /** Offers a serialized frame for `repo` to this instance's subscribers: one published here, or relayed from another replica. */
    fun deliver(repo: String, frame: String) {
        for (l in frameListeners) try { l(repo, frame) } catch (e: Exception) { log.warn("events: a frame listener failed: {}", e.toString()) }
        for (sub in subscribers) {
            if (!takes(sub, repo)) continue
            if (!sub.offer(frame)) {
                subscribers -= sub
                if (sub.overflowed) log.warn("events: a subscriber of {} fell {} frames behind; closed", sub.repo, QUEUE_CAPACITY)
            }
        }
    }

    /**
     * [publish] after the current Operaton command commits (`TransactionState.COMMITTED`, as `CloseWake.afterCommit`
     * waits), so a step that rolls back is never announced; at once when no command context is bound.
     */
    fun publishAfterCommit(event: EngineEvent) = afterCommit { publish(event) }

    /** Runs `action` after the current Operaton command commits, or at once when none is bound. */
    fun afterCommit(action: () -> Unit) {
        val context = Context.getCommandContext() ?: return action()
        context.transactionContext.addTransactionListener(TransactionState.COMMITTED) { action() }
    }

    /**
     * [afterCommit], on a virtual thread of its own named [name] that the committing thread does not wait for: the
     * committed command still holds its database connection in the after-commit callback, so an action that needs one
     * (a `REQUIRES_NEW`, a read on another thread) must not run on it, or a burst of commands each holding one and
     * waiting for a second empties the pool (#1196's CloseWake, #1650's intake filing). At once on a thread of its
     * own when no command is bound. A failure is the action's to handle.
     */
    fun afterCommitApart(name: String, action: () -> Unit) = afterCommit { Thread.ofVirtual().name(name).start(action) }

    /** Whether an Operaton command is bound to this thread: its transaction then holds a database connection. */
    fun inCommand(): Boolean = Context.getCommandContext() != null

    private fun takes(sub: EventSubscription, repo: String) = sub.repo == EngineEvent.FLEET || sub.repo.equals(repo, ignoreCase = true)

    /** `event: <type>`, `data: <json>`, a blank line: the one frame grammar. A comment frame is [PING]. */
    fun frame(type: String, data: String) = "event: $type\ndata: $data\n\n"

    /** The `hello` frame's data, for the controller: `{"repo", "at"}`. */
    fun hello(repo: String): String = frame("hello", json.writeValueAsString(mapOf("repo" to repo, "at" to Instant.now())))

    /** The comment frame an idle stream gets every [PING_INTERVAL]. */
    val ping: String get() = ": ping\n\n"
}

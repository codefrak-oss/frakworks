package com.codefrak.engine.firstmate

import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.events.EventSubscription
import org.springframework.stereotype.Component
import java.time.Duration

/** What a pod's inbox poll answers: the phone's messages after its seq, or that the session is freezing. */
data class InboxAnswer(val messages: List<FirstMateMessage>, val freeze: Boolean = false)

/**
 * The long-poll behind `GET /first-mate/pod/{id}/inbox` (engine/README.md, "First-mate sessions", "The pod relay protocol"): waits up to
 * the asked time for a phone message after `after`, on any replica. It wakes on the session's `first-mate` frame (the
 * message post publishes one, and the [EngineEvents] relay spreads it to every replica) and re-reads the database
 * every [BACKSTOP] besides, so a lost frame costs at most that. It holds no database connection while it waits.
 */
@Component
class FirstMateInbox(private val sessions: FirstMateSessions, private val events: EngineEvents) {
    companion object {
        /** The longest wait a poll may ask for. */
        val MAX_WAIT: Duration = Duration.ofSeconds(25)
        /** How often a waiting poll re-reads the database when no frame came. */
        val BACKSTOP: Duration = Duration.ofSeconds(2)
    }

    fun await(session: FirstMateSession, after: Long, wait: Duration): InboxAnswer {
        val deadline = System.nanoTime() + wait.coerceIn(Duration.ZERO, MAX_WAIT).toNanos()
        var sub: EventSubscription? = events.subscribe(session.repo)
        try {
            while (true) {
                val now = sessions.get(session.id) ?: return InboxAnswer(emptyList())
                if (now.freezing) return InboxAnswer(emptyList(), freeze = true)
                val rows = sessions.userAfter(session.id, after)
                if (rows.isNotEmpty()) return InboxAnswer(rows)
                val left = Duration.ofNanos(deadline - System.nanoTime())
                if (left <= Duration.ZERO) return InboxAnswer(emptyList())
                val nap = minOf(left, BACKSTOP)
                val until = System.nanoTime() + nap.toNanos()
                // Frames of other sessions on the repo are skipped; a closed subscription leaves the backstop alone.
                while (true) {
                    val rest = Duration.ofNanos(until - System.nanoTime())
                    if (rest <= Duration.ZERO) break
                    val s = sub ?: run { Thread.sleep(rest.toMillis().coerceAtLeast(1)); null } ?: break
                    val frame = s.next(rest) ?: break
                    if (frame == EventSubscription.CLOSE) { events.unsubscribe(s); sub = null; continue }
                    if (frame.startsWith("event: first-mate") && "\"sessionId\":\"${session.id}\"" in frame) break
                }
            }
        } finally {
            sub?.let { events.unsubscribe(it) }
        }
    }
}

package com.codefrak.engine.events

import com.codefrak.engine.board.BoardRows
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * The `created` frame (engine/README.md, "Events"): every issue the engine files goes on the bus with its board row
 * the moment GitHub has answered the create, through [EngineEvents.publish] and not [EngineEvents.publishAfterCommit]:
 * the issue is on GitHub whether or not the command commits, so the frame is not held behind the rest of the
 * transaction. The row is read by number ([BoardRows.row]), never from `openIssues`, whose list lags a new issue by
 * seconds; the desktop inserts it in place instead of re-reading `GET /issues`. Called by [com.codefrak.engine.intake.TicketFiler]
 * on the command's thread and by `OpenAskItemDelegate` once its ask item is committed; neither waits for the row: inside a command it is read
 * and published on a thread of its own, outside the command (#1650); outside one, the caller waits at most [ROW_WAIT].
 * A frame whose row is late or failed goes out with `issue: null`.
 */
@Component
class CreatedEvents(private val bus: EngineEvents, @Lazy private val rows: BoardRows) {
    /** How long the create waits for the row ([rowWithin]); [ROW_WAIT], shortened by tests. */
    @Volatile var rowWait: Duration = ROW_WAIT

    fun created(repo: String, number: Long, url: String) {
        // Inside a command the row is read and the frame published on a thread of its own, which the command does not
        // wait for: the row read needs a database connection, and a command waiting for it while holding its own
        // emptied the pool when 11 intakes filed at once (#1650).
        if (bus.inCommand()) Thread.ofVirtual().name("created-row").start { publish(repo, number, url) }
        else publish(repo, number, url)
    }

    private fun publish(repo: String, number: Long, url: String) =
        bus.publish(EngineEvent.Created(repo, number, url, rows.rowWithin(repo, number, rowWait)))
}

package com.codefrak.engine.intake

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime

/** One row of `engine.intake_notification`; `deliveredAt`, `deliveredBy` and `result` are null while it waits for the watcher. */
data class IntakeNotification(
    val id: Long, val intake: String, val session: String, val repo: String, val ticket: Long, val ticketUrl: String,
    val queuedAt: OffsetDateTime, val deliveredAt: OffsetDateTime?, val deliveredBy: String?, val result: String?,
) {
    /** What the watcher has `claude -p` relay to the session, verbatim: the fact, then what to do with it. */
    val message: String
        get() = "Intake $intake was refined and filed as ticket #$ticket in $repo: $ticketUrl. " +
            "Tell the user, in one line, that the ticket you created is #$ticket, with #$ticket a markdown link to that url."
}

/**
 * `engine.intake_notification`: the filed tickets waiting to be pushed into the Claude Code session that filed
 * the intake (engine/README.md, "Intake", "Telling the session that filed it"). The engine only queues; a
 * host-side watcher delivers (`GET /intake/notifications`, `POST /intake/notifications/{id}/delivered`).
 */
@Repository
class IntakeNotifications(private val jdbc: JdbcClient) {
    /** Queues one; false when the intake already has a row (a retried step never queues twice). */
    fun queue(intake: String, session: String, repo: String, ticket: Long, ticketUrl: String): Boolean = try {
        jdbc.sql("INSERT INTO engine.intake_notification (intake, session, full_name, ticket, ticket_url) VALUES (:p, :s, :r, :t, :u)")
            .param("p", intake).param("s", session).param("r", repo).param("t", ticket).param("u", ticketUrl).update() == 1
    } catch (e: DuplicateKeyException) { false }

    /** Every notification not yet delivered, oldest first. */
    fun pending(): List<IntakeNotification> =
        jdbc.sql("$SELECT WHERE delivered_at IS NULL ORDER BY queued_at, id").query(ROW).list()

    /** By id, delivered or not; null when there is none. */
    fun get(id: Long): IntakeNotification? = jdbc.sql("$SELECT WHERE id = :id").param("id", id).query(ROW).optional().orElse(null)

    /** Records the watcher's report; false when it was already delivered (a second watcher loses). */
    fun delivered(id: Long, by: String, result: String): Boolean =
        jdbc.sql("UPDATE engine.intake_notification SET delivered_at = CURRENT_TIMESTAMP, delivered_by = :b, result = :r WHERE id = :id AND delivered_at IS NULL")
            .param("b", by).param("r", result).param("id", id).update() == 1

    private companion object {
        const val SELECT = "SELECT id, intake, session, full_name, ticket, ticket_url, queued_at, delivered_at, delivered_by, result FROM engine.intake_notification"
        val ROW = { rs: ResultSet, _: Int ->
            IntakeNotification(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getString(6),
                rs.getObject(7, OffsetDateTime::class.java), rs.getObject(8, OffsetDateTime::class.java), rs.getString(9), rs.getString(10))
        }
    }
}

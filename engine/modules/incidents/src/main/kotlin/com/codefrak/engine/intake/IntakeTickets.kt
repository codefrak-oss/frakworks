package com.codefrak.engine.intake

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/**
 * One `engine.intake_ticket` row: an intake, its repo, issue and issue url, and the ticket it filed (null until it files).
 * [ticketRepo] is the repo the ticket is in: the intake's own [repo] unless the refiner named another target repo (V71).
 */
data class IntakeTicket(val intake: String, val repo: String, val issue: Long, val issueUrl: String, val ticket: Long?,
                        val ticketRepo: String = repo)

/** An intake's refine result as the timeline's projection parsed it (V67): the outcome, and the refiner's blocker and reason when it gave them. */
data class RefineOutcome(val outcome: String?, val blocker: String?, val reason: String?)

/**
 * `engine.intake_ticket` (engine/README.md, "Intake"): the store of record for which issue an intake opened and which
 * ticket it filed. Written by `OpenAskItemDelegate` and the filing steps; read by [IntakeRefs], the issues board,
 * the amender and friction fixes instead of Operaton's history.
 */
@Repository
class IntakeTickets(private val jdbc: JdbcClient) {
    /** The intake's issue, at its creation; a retried step rewrites the same row. */
    fun opened(intake: String, repo: String, issue: Long, issueUrl: String, at: Instant = Instant.now()) {
        val updated = jdbc.sql("UPDATE engine.intake_ticket SET full_name = :r, issue = :i, issue_url = :u WHERE intake = :id")
            .param("r", repo).param("i", issue).param("u", issueUrl).param("id", intake).update()
        if (updated == 0) jdbc.sql("INSERT INTO engine.intake_ticket (intake, full_name, issue, issue_url, created_at) VALUES (:id, :r, :i, :u, :at)")
            .param("id", intake).param("r", repo).param("i", issue).param("u", issueUrl).param("at", Timestamp.from(at)).update()
    }

    /**
     * The intake is about to create its ticket on GitHub (`filing_at`). In its own transaction, as [filed] is: the
     * filing job's rollback must not erase the trace of a GitHub write it made (engine/README.md, "Intake").
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun filing(intake: String, at: Instant = Instant.now()) {
        jdbc.sql("UPDATE engine.intake_ticket SET filing_at = :at WHERE intake = :id AND filing_at IS NULL")
            .param("at", Timestamp.from(at)).param("id", intake).update()
    }

    /** Whether the intake began a create on GitHub ([filing]); a ticket it filed is [get]'s. */
    fun began(intake: String): Boolean =
        jdbc.sql("SELECT COUNT(*) FROM engine.intake_ticket WHERE intake = :id AND filing_at IS NOT NULL")
            .param("id", intake).query(Long::class.java).single() > 0

    /**
     * The ticket the intake filed, in its own transaction ([filing]). [ticketRepo] is the repo it is in when that is not
     * the intake's own (a refiner that named another target repo); null for the intake's own repo.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun filed(intake: String, ticket: Long, at: Instant = Instant.now(), ticketRepo: String? = null) {
        jdbc.sql("UPDATE engine.intake_ticket SET ticket = :t, filed_at = :at, ticket_repo = :tr WHERE intake = :id")
            .param("t", ticket).param("at", Timestamp.from(at)).param("tr", ticketRepo).param("id", intake).update()
    }

    /** A row as the backfill found it in history; an intake already in the table is left as it is. */
    fun backfill(row: IntakeTicket, createdAt: Instant, filedAt: Instant?): Boolean {
        if (get(row.intake) != null) return false
        jdbc.sql("""INSERT INTO engine.intake_ticket (intake, full_name, issue, issue_url, ticket, created_at, filed_at)
            VALUES (:id, :r, :i, :u, :t, :c, :f)""")
            .param("id", row.intake).param("r", row.repo).param("i", row.issue).param("u", row.issueUrl).param("t", row.ticket)
            .param("c", Timestamp.from(createdAt)).param("f", filedAt?.let { Timestamp.from(it) }).update()
        return true
    }

    /**
     * The refine result the timeline's projection parsed from the intake's `output` ([com.codefrak.engine.camunda.TimelineProjection]),
     * in its own transaction like [filing] and [filed]: the history handler calls it inside the command that goes on to
     * `file ticket`, whose `REQUIRES_NEW` stamps on the same row would otherwise wait on the command's row lock. An intake
     * with no row yet (one from before V58 the backfill reads) gets one when its repo and issue are known.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun refined(intake: String, repo: String?, issue: Long?, outcome: String?, blocker: String?, reason: String?) {
        val updated = jdbc.sql("UPDATE engine.intake_ticket SET refine_outcome = :o, blocker = :b, reason = :r WHERE intake = :id")
            .param("o", outcome?.take(100)).param("b", blocker?.take(4000)).param("r", reason).param("id", intake).update()
        if (updated == 0 && repo != null && issue != null)
            jdbc.sql("""INSERT INTO engine.intake_ticket (intake, full_name, issue, issue_url, created_at, refine_outcome, blocker, reason)
                VALUES (:id, :repo, :i, :u, :at, :o, :b, :r)""")
                .param("id", intake).param("repo", repo).param("i", issue).param("u", com.codefrak.engine.github.issueUrl(repo, issue))
                .param("at", Timestamp.from(Instant.now())).param("o", outcome?.take(100)).param("b", blocker?.take(4000)).param("r", reason).update()
    }

    /** The intake's refine result; null when none was recorded (no refine run yet, or the row predates V67 and was not backfilled). */
    fun refineResult(intake: String): RefineOutcome? =
        jdbc.sql("SELECT refine_outcome, blocker, reason FROM engine.intake_ticket WHERE intake = :id AND refine_outcome IS NOT NULL").param("id", intake)
            .query { rs, _ -> RefineOutcome(rs.getString(1), rs.getString(2), rs.getString(3)) }.optional().orElse(null)

    fun get(intake: String): IntakeTicket? =
        jdbc.sql("SELECT $COLS FROM engine.intake_ticket WHERE intake = :id").param("id", intake).query(::row).optional().orElse(null)

    /** The intake that opened issue [issue] in [repo]; the newest when there are several. */
    fun byIssue(repo: String, issue: Long): IntakeTicket? =
        jdbc.sql("SELECT $COLS FROM engine.intake_ticket WHERE LOWER(full_name) = LOWER(:r) AND issue = :i ORDER BY created_at DESC")
            .param("r", repo).param("i", issue).query(::row).list().firstOrNull()

    /** The intake whose issue is at [url]; the newest when there are several. */
    fun byIssueUrl(url: String): IntakeTicket? =
        jdbc.sql("SELECT $COLS FROM engine.intake_ticket WHERE issue_url = :u ORDER BY created_at DESC").param("u", url).query(::row).list().firstOrNull()

    /**
     * Every ticket in [repo] an intake filed, to that intake; several intakes naming one ticket: the newest wins. A ticket
     * filed in another repo than its intake's counts in the repo it is in.
     */
    fun filedIn(repo: String): Map<Long, IntakeTicket> =
        jdbc.sql("SELECT $COLS FROM engine.intake_ticket WHERE LOWER(COALESCE(ticket_repo, full_name)) = LOWER(:r) AND ticket IS NOT NULL ORDER BY created_at")
            .param("r", repo).query(::row).list().associateBy { it.ticket!! }

    fun ids(): Set<String> = jdbc.sql("SELECT intake FROM engine.intake_ticket").query(String::class.java).list().filterNotNull().toSet()

    private fun row(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = IntakeTicket(
        rs.getString("intake"), rs.getString("full_name"), rs.getLong("issue"), rs.getString("issue_url"),
        rs.getLong("ticket").takeUnless { rs.wasNull() }, rs.getString("ticket_repo") ?: rs.getString("full_name"))

    private companion object {
        const val COLS = "intake, full_name, issue, issue_url, ticket, ticket_repo"
    }
}

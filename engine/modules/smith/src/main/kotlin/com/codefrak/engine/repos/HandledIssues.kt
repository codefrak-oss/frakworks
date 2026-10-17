package com.codefrak.engine.repos

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** `askedBy`: who asked for the ticket (`asked_by`, V47); null on a row from before the column until the scan fills it.
 * `points`: the row's story points (V49); null on a row filed unpointed. */
data class Handled(val repo: String, val issue: Long, val labelEvent: Long, val flow: String, val outcome: String,
                   val detail: String?, val processInstance: String?, val askedBy: String? = null,
                   val points: Int? = null)

/** `engine.handled_issue`: the approvals (labeled events) the scan has acted on. */
@Repository
class HandledIssues(private val jdbc: JdbcClient) {
    fun seen(repo: String, issue: Long, labelEvent: Long): Boolean =
        jdbc.sql("SELECT COUNT(*) FROM engine.handled_issue WHERE full_name = :r AND issue = :i AND label_event = :e")
            .param("r", repo).param("i", issue).param("e", labelEvent).query(Long::class.java).single() > 0

    /**
     * Claims the approval, `askedBy` who asked for the ticket (the trailer's `by`, else the issue's author) and `points`
     * its story points (the trailer's `points`, V49); false if another scan already did.
     * A claimed row is looked for first rather than left to the INSERT to fail on: on PostgreSQL a failed statement aborts the
     * caller's transaction (a flow job's, FileChildDelegate), and the next engine query then fails with a persistence error
     * that hides the caller's own (#2581). The catch is left for a concurrent claim between the two.
     */
    fun claim(repo: String, issue: Long, labelEvent: Long, flow: String, outcome: String, detail: String?, askedBy: String? = null,
              points: Int? = null): Boolean = !seen(repo, issue, labelEvent) && try {
        jdbc.sql("""INSERT INTO engine.handled_issue (full_name, issue, label_event, flow, outcome, detail, asked_by, points)
            VALUES (:r, :i, :e, :f, :o, :d, :a, :p)""")
            .param("r", repo).param("i", issue).param("e", labelEvent).param("f", flow).param("o", outcome).param("d", detail?.take(400))
            .param("a", askedBy?.take(100)).param("p", points)
            .update() == 1
    } catch (e: DuplicateKeyException) { false }

    fun started(repo: String, issue: Long, labelEvent: Long, processInstance: String) {
        jdbc.sql("UPDATE engine.handled_issue SET process_instance = :p WHERE full_name = :r AND issue = :i AND label_event = :e")
            .param("p", processInstance).param("r", repo).param("i", issue).param("e", labelEvent).update()
    }

    fun outcome(repo: String, issue: Long, labelEvent: Long, outcome: String, detail: String) {
        jdbc.sql("UPDATE engine.handled_issue SET outcome = :o, detail = :d WHERE full_name = :r AND issue = :i AND label_event = :e")
            .param("o", outcome).param("d", detail.take(400)).param("r", repo).param("i", issue).param("e", labelEvent).update()
    }

    /**
     * The process instances `flow` was started as on the issue, by a label or by an intake's authorization;
     * null for a start claimed but not yet given its instance.
     */
    fun startedBefore(repo: String, issue: Long, flow: String): List<String?> =
        jdbc.sql("SELECT process_instance FROM engine.handled_issue WHERE full_name = :r AND issue = :i AND flow = :f AND outcome = 'started'")
            .param("r", repo).param("i", issue).param("f", flow).query { rs, _ -> rs.getString(1) as String? }.list()

    /** Gives a claim back, when acting on it failed, so the next scan tries again. */
    fun release(repo: String, issue: Long, labelEvent: Long) {
        jdbc.sql("DELETE FROM engine.handled_issue WHERE full_name = :r AND issue = :i AND label_event = :e")
            .param("r", repo).param("i", issue).param("e", labelEvent).update()
    }

    fun list(repo: String): List<Handled> =
        jdbc.sql("""SELECT full_name, issue, label_event, flow, outcome, detail, process_instance, asked_by, points
            FROM engine.handled_issue WHERE full_name = :r ORDER BY handled_at""").param("r", repo)
            .query { rs, _ -> Handled(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getObject(9)?.let { (it as Number).toInt() }) }
            .list()

    /** Who asked for the issue, from its latest row that says; null when the engine has not seen it, or no row says yet. */
    fun askedBy(repo: String, issue: Long): String? =
        jdbc.sql("""SELECT asked_by FROM engine.handled_issue WHERE full_name = :r AND issue = :i AND asked_by IS NOT NULL
            ORDER BY handled_at DESC LIMIT 1""").param("r", repo).param("i", issue)
            .query { rs, _ -> rs.getString(1) }.list().firstOrNull()

    /** The issue's story points, from its latest row that has them; null when the engine has not seen it, or it was filed unpointed. */
    fun points(repo: String, issue: Long): Int? =
        jdbc.sql("""SELECT points FROM engine.handled_issue WHERE full_name = :r AND issue = :i AND points IS NOT NULL
            ORDER BY handled_at DESC LIMIT 1""").param("r", repo).param("i", issue)
            .query { rs, _ -> rs.getInt(1) }.list().firstOrNull()

    /** The issues with a row that does not say who asked (from before `asked_by`), for the scan to fill ([fillAskedBy]). */
    fun withoutAskedBy(repo: String): List<Long> =
        jdbc.sql("SELECT DISTINCT issue FROM engine.handled_issue WHERE full_name = :r AND asked_by IS NULL ORDER BY issue")
            .param("r", repo).query { rs, _ -> rs.getLong(1) }.list()

    /** Fills `asked_by` on the issue's rows that have none; a row that says keeps what it says. */
    fun fillAskedBy(repo: String, issue: Long, by: String) {
        jdbc.sql("UPDATE engine.handled_issue SET asked_by = :a WHERE full_name = :r AND issue = :i AND asked_by IS NULL")
            .param("a", by.take(100)).param("r", repo).param("i", issue).update()
    }
}

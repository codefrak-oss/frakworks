package com.codefrak.engine.repos

import com.codefrak.engine.engineJson
import com.codefrak.engine.github.Blocker
import com.codefrak.engine.github.IssueSnapshot
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.readValue
import java.sql.ResultSet
import java.time.OffsetDateTime

/**
 * An approval waiting on open blockers: everything needed to start it later as if it had just landed.
 * `labelEvent` is 0 for an intake carry-over. `stalled` holds the blockers (`owner/name#n`) already
 * reported closed without a merge, so that is said once. `waitingInstance` is set when a running
 * flow waits on the blockers rather than a start: a split feature parked at `child closed`
 * (engine/README.md, "Split features"), which the scan wakes instead of starting a flow.
 */
data class Deferred(
    val repo: String, val issue: Long, val labelEvent: Long, val authorizedBy: String, val flow: String,
    val featureMode: String?, val snapshot: IssueSnapshot, val blockers: List<Blocker>,
    val tier: Int? = null, val detail: String? = null, val dryRun: Boolean = false,
    val stalled: Set<String> = emptySet(), val firstSeen: OffsetDateTime? = null, val lastChecked: OffsetDateTime? = null,
    val waitingInstance: String? = null,
)

/** `engine.deferred_issue`: approved tickets waiting on GitHub "blocked by" issues (engine/README.md, "Blocked by"). */
@Repository
class DeferredIssues(private val jdbc: JdbcClient) {
    /**
     * Records the approval as waiting; true when the issue was not waiting yet. On an issue already
     * waiting it replaces the approval (and forgets what was said about blockers closed without a
     * merge, which the new approval has seen), keeping `first_seen`, and returns false.
     * A waiting row is looked for first rather than left to the INSERT to fail on: a failed statement aborts a PostgreSQL
     * transaction, a flow job's included (FileChildDelegate, #2581).
     */
    fun defer(d: Deferred): Boolean {
        val waiting = jdbc.sql("SELECT COUNT(*) FROM engine.deferred_issue WHERE full_name = :r AND issue = :i")
            .param("r", d.repo).param("i", d.issue).query(Long::class.java).single() > 0
        val inserted = !waiting && try {
            jdbc.sql("""INSERT INTO engine.deferred_issue (full_name, issue, label_event, authorized_by, flow, feature_mode, snapshot,
                    tier, detail, dry_run, blockers, stalled, waiting_instance)
                VALUES (:r, :i, :e, :a, :f, :m, :s, :t, :d, :dry, :b, '[]', :w)""").approval(d).update() == 1
        } catch (e: DuplicateKeyException) { false }
        if (!inserted) {
            jdbc.sql("""UPDATE engine.deferred_issue SET label_event = :e, authorized_by = :a, flow = :f, feature_mode = :m, snapshot = :s,
                    tier = :t, detail = :d, dry_run = :dry, blockers = :b, last_checked = CURRENT_TIMESTAMP, waiting_instance = :w,
                    stalled = CASE WHEN label_event = :e AND waiting_instance IS NULL THEN stalled ELSE '[]' END
                WHERE full_name = :r AND issue = :i""").approval(d).update()
        }
        return inserted
    }

    private fun JdbcClient.StatementSpec.approval(d: Deferred) =
        param("r", d.repo).param("i", d.issue).param("e", d.labelEvent).param("a", d.authorizedBy).param("f", d.flow)
            .param("m", d.featureMode).param("s", engineJson.writeValueAsString(d.snapshot)).param("t", d.tier)
            .param("d", d.detail?.take(400)).param("dry", d.dryRun).param("b", engineJson.writeValueAsString(d.blockers)).param("w", d.waitingInstance)

    /** What a scan read: the blockers now, and the ones reported closed without a merge. */
    fun checked(repo: String, issue: Long, blockers: List<Blocker>, stalled: Set<String>) {
        jdbc.sql("""UPDATE engine.deferred_issue SET blockers = :b, stalled = :st, last_checked = CURRENT_TIMESTAMP
            WHERE full_name = :r AND issue = :i""")
            .param("b", engineJson.writeValueAsString(blockers)).param("st", engineJson.writeValueAsString(stalled.sorted()))
            .param("r", repo).param("i", issue).update()
    }

    fun remove(repo: String, issue: Long) {
        jdbc.sql("DELETE FROM engine.deferred_issue WHERE full_name = :r AND issue = :i").param("r", repo).param("i", issue).update()
    }

    fun get(repo: String, issue: Long): Deferred? =
        jdbc.sql("$SELECT WHERE full_name = :r AND issue = :i").param("r", repo).param("i", issue).query(::row).optional().orElse(null)

    fun list(): List<Deferred> = jdbc.sql("$SELECT ORDER BY first_seen").query(::row).list()

    fun list(repo: String): List<Deferred> = jdbc.sql("$SELECT WHERE full_name = :r ORDER BY first_seen").param("r", repo).query(::row).list()

    private fun row(rs: ResultSet, n: Int) = Deferred(
        rs.getString("full_name"), rs.getLong("issue"), rs.getLong("label_event"), rs.getString("authorized_by"), rs.getString("flow"),
        rs.getString("feature_mode"), engineJson.readValue<IssueSnapshot>(rs.getString("snapshot")),
        engineJson.readValue<List<Blocker>>(rs.getString("blockers")),
        rs.getObject("tier")?.let { (it as Number).toInt() }, rs.getString("detail"), rs.getBoolean("dry_run"),
        engineJson.readValue<List<String>>(rs.getString("stalled")).toSet(),
        rs.getObject("first_seen", OffsetDateTime::class.java), rs.getObject("last_checked", OffsetDateTime::class.java),
        rs.getString("waiting_instance"),
    )

    private companion object {
        const val SELECT = """SELECT full_name, issue, label_event, authorized_by, flow, feature_mode, snapshot, tier, detail, dry_run,
            blockers, stalled, first_seen, last_checked, waiting_instance FROM engine.deferred_issue"""
    }
}

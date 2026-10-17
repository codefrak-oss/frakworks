package com.codefrak.engine.friction

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime

@Repository
class FrictionGroupFixes(private val jdbc: JdbcClient) : FrictionFixReads {
    private companion object {
        const val HEAD = "id, fix_repo, fix_issue, fix_state, fix_merged_at, fix_recurred_at, fix_intake, fix_intake_repo"
    }

    private fun fixOf(rs: ResultSet): FrictionFix? = rs.getString(4)?.let { state -> FrictionFix(rs.getLong(1), rs.getString(2), rs.getLong(3), state,
        rs.getObject(5, OffsetDateTime::class.java), rs.getObject(6, OffsetDateTime::class.java)) }

    private fun pendingOf(rs: ResultSet): PendingFix? = rs.getString(7)?.let { PendingFix(rs.getLong(1), it, rs.getString(8)) }

    /** Group [groupId]'s fixed-by link, null without one (or no such group). */
    fun fix(groupId: Long): FrictionFix? =
        jdbc.sql("SELECT $HEAD FROM engine.friction_group WHERE id = :id").param("id", groupId).query { rs, _ -> fixOf(rs) }.optional().orElse(null)

    /** The fixed-by links of [groupIds] that have one. */
    override fun fixesOf(groupIds: Collection<Long>): Map<Long, FrictionFix> = if (groupIds.isEmpty()) emptyMap() else
        jdbc.sql("SELECT $HEAD FROM engine.friction_group WHERE fix_state IS NOT NULL AND id IN (:ids)").param("ids", groupIds.distinct())
            .query { rs, _ -> fixOf(rs)!! }.list().associateBy { it.groupId }

    /** Every group's fixed-by link, oldest group first. */
    fun fixes(): List<FrictionFix> =
        jdbc.sql("SELECT $HEAD FROM engine.friction_group WHERE fix_state IS NOT NULL ORDER BY id").query { rs, _ -> fixOf(rs)!! }.list()

    /** Group [groupId]'s pending intake, null without one (or no such group). */
    fun pending(groupId: Long): PendingFix? =
        jdbc.sql("SELECT $HEAD FROM engine.friction_group WHERE id = :id").param("id", groupId).query { rs, _ -> pendingOf(rs) }.optional().orElse(null)

    /** The pending intakes of [groupIds] that have one. */
    override fun pendingOf(groupIds: Collection<Long>): Map<Long, PendingFix> = if (groupIds.isEmpty()) emptyMap() else
        jdbc.sql("SELECT $HEAD FROM engine.friction_group WHERE fix_intake IS NOT NULL AND id IN (:ids)").param("ids", groupIds.distinct())
            .query { rs, _ -> pendingOf(rs)!! }.list().associateBy { it.groupId }

    /** Every pending intake, oldest group first. */
    fun pendings(): List<PendingFix> =
        jdbc.sql("SELECT $HEAD FROM engine.friction_group WHERE fix_intake IS NOT NULL ORDER BY id").query { rs, _ -> pendingOf(rs)!! }.list()

    /** Group [groupId]'s candidate fix is [intake], filing in [repo]: pending, its ticket link and any dismissal cleared. */
    fun setPending(groupId: Long, intake: String, repo: String) {
        jdbc.sql("""UPDATE engine.friction_group SET fix_intake = :in, fix_intake_repo = :r, fix_repo = NULL, fix_issue = NULL, fix_state = NULL,
                fix_merged_at = NULL, fix_recurred_at = NULL, fix_dismissed = NULL WHERE id = :id""")
            .param("in", intake).param("r", repo).param("id", groupId).update()
    }

    /** Group [groupId]'s pending intake goes. */
    fun clearPending(groupId: Long) {
        jdbc.sql("UPDATE engine.friction_group SET fix_intake = NULL, fix_intake_repo = NULL WHERE id = :id").param("id", groupId).update()
    }

    /** The ticket a user cleared from group [groupId], or one closed as not planned, as `owner/name#n`: the sync does not link it again. */
    fun fixDismissed(groupId: Long): String? =
        jdbc.sql("SELECT fix_dismissed FROM engine.friction_group WHERE id = :id").param("id", groupId).query(String::class.java).optional().orElse(null)

    /**
     * Group [groupId] is fixed by [repo]#[issue] in [state], with [mergedAt] when fixed; a new ticket starts afresh (no
     * recurrence), the same ticket keeps its recurrence. Then a note after the merge marks the group recurred ([recur]).
     */
    fun setFix(groupId: Long, repo: String, issue: Long, state: String, mergedAt: OffsetDateTime?) {
        val was = fix(groupId)
        val recurred = was?.recurredAt?.takeIf { was.repo == repo && was.issue == issue && state == FrictionFix.FIXED }
        jdbc.sql("""UPDATE engine.friction_group SET fix_recurred_at = :rec,
                fix_repo = :r, fix_issue = :i, fix_state = :s, fix_merged_at = :m, fix_dismissed = NULL,
                fix_intake = NULL, fix_intake_repo = NULL WHERE id = :id""")
            .param("rec", recurred).param("r", repo).param("i", issue).param("s", state).param("m", mergedAt).param("id", groupId).update()
        recur(groupId)
    }

    /** Group [groupId]'s link goes: it is open again, and [dismissed] (`owner/name#n`, the ticket it had) is not linked again by the sync. */
    fun clearFix(groupId: Long, dismissed: String?) {
        jdbc.sql("""UPDATE engine.friction_group SET fix_repo = NULL, fix_issue = NULL, fix_state = NULL, fix_merged_at = NULL,
                fix_recurred_at = NULL, fix_dismissed = :d, fix_intake = NULL, fix_intake_repo = NULL WHERE id = :id""").param("d", dismissed).param("id", groupId).update()
    }

    /**
     * Recurrence: a `fixed` group with a member noted after its fix merged, and no recurrence yet, is recurred at the
     * first such note. A note dated before the merge (a late grouping) does not count.
     */
    fun recur(groupId: Long) {
        val fix = fix(groupId) ?: return
        val merged = fix.mergedAt ?: return
        if (fix.state != FrictionFix.FIXED || fix.recurredAt != null) return
        val first = jdbc.sql("SELECT MIN(noted_at) FROM engine.friction_note WHERE group_id = :id AND noted_at > :m")
            .param("id", groupId).param("m", merged).query { rs, _ -> rs.getObject(1, OffsetDateTime::class.java) }.optional().orElse(null) ?: return
        jdbc.sql("UPDATE engine.friction_group SET fix_recurred_at = :at WHERE id = :id AND fix_recurred_at IS NULL")
            .param("at", first).param("id", groupId).update()
    }
}

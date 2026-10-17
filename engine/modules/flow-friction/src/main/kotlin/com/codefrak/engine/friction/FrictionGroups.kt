package com.codefrak.engine.friction

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

/**
 * `engine.friction_group` and `engine.friction_signature`, and the group columns of `engine.friction_note`
 * (engine/README.md, "Friction notes"). The writes are the grouper's ([FrictionGrouper]), which wraps each note's in
 * its own transaction, and a user's corrections' ([FrictionCorrections], one transaction each); nothing here opens one.
 * The group's fix columns live in [FrictionGroupFixes], its two-things bug columns in [FrictionGroupBugs].
 */
@Repository
class FrictionGroups(private val jdbc: JdbcClient, private val fixes: FrictionGroupFixes) {
    private companion object {
        /** A group's head columns, as [head] reads them. */
        const val HEAD = "id, title, created_at, bug_repo, bug_issue, bug_state, bug_intake_id, fix_repo, fix_issue, fix_state, fix_merged_at, fix_recurred_at, fix_intake, fix_intake_repo"
    }

    /** The ungrouped notes due at [now] (no `next_attempt_at`, or one that has passed), oldest first. */
    fun due(now: OffsetDateTime): List<PendingNote> = jdbc.sql(
        "SELECT ${FrictionNote.COLUMNS}, group_attempts FROM engine.friction_note " +
            "WHERE group_id IS NULL AND (next_attempt_at IS NULL OR next_attempt_at <= :now) ORDER BY noted_at, id")
        .param("now", now)
        .query { rs, _ -> PendingNote(FrictionNote.read(rs), rs.getInt(FrictionNote.COLUMN_COUNT + 1)) }.list()

    /** The group the key [signature] points at, or null when it was never seen. */
    fun groupOf(signature: String): Long? =
        jdbc.sql("SELECT group_id FROM engine.friction_signature WHERE signature = :s").param("s", signature)
            .query(Long::class.javaObjectType).optional().orElse(null)

    /** Every group, newest member first, as the Jev options go. */
    fun options(): List<GroupOption> = jdbc.sql(
        """SELECT g.id, g.title, COALESCE(MAX(n.noted_at), g.created_at) AS newest FROM engine.friction_group g
           LEFT JOIN engine.friction_note n ON n.group_id = g.id GROUP BY g.id, g.title, g.created_at ORDER BY newest DESC, g.id DESC""")
        .query { rs, _ -> GroupOption(rs.getLong(1), rs.getString(2), rs.getObject(3, OffsetDateTime::class.java)) }.list()

    fun count(): Int = jdbc.sql("SELECT COUNT(*) FROM engine.friction_group").query(Int::class.javaObjectType).single()

    /** Ungrouped notes, waiting on the drain. */
    fun ungrouped(): Int = jdbc.sql("SELECT COUNT(*) FROM engine.friction_note WHERE group_id IS NULL").query(Int::class.javaObjectType).single()

    /**
     * [note] founds a group: the group with the note's summary line as title, the note's key as its first signature,
     * and the note joined as [by] with [call]; answers the group's id. One transaction's worth: the caller opens it.
     */
    fun found(note: FrictionNote, by: String, call: String?, now: OffsetDateTime): Long {
        val id = create(note.summary, now)
        addSignature(note, id, now)
        join(note.id, id, by, call)
        return id
    }

    /** An empty group titled by [summary]'s first line ([FrictionGroup.title]); answers its id. */
    fun create(summary: String, now: OffsetDateTime): Long {
        val keys = GeneratedKeyHolder()
        jdbc.sql("INSERT INTO engine.friction_group (title, created_at) VALUES (:t, :at)")
            .param("t", FrictionGroup.title(summary)).param("at", now).update(keys, "id")
        return (keys.keys!!.entries.first { it.key.equals("id", ignoreCase = true) }.value as Number).toLong()
    }

    /**
     * [noteId] joins group [groupId] as [by], with the `friction-group` call [call] when one was made, and the
     * [login] that moved it when [by] is [GroupedBy.USER].
     */
    fun join(noteId: String, groupId: Long, by: String, call: String?, login: String? = null) {
        val updated = jdbc.sql("UPDATE engine.friction_note SET group_id = :g, grouped_by = :by, jev_call_id = :c, grouped_login = :l, next_attempt_at = NULL WHERE id = :id")
            .param("g", groupId).param("by", by).param("c", call).param("l", login).param("id", noteId).update()
        check(updated == 1) { "friction note $noteId is gone" }
        fixes.recur(groupId)
    }

    /** One note by id, grouped or not; null for no such note. */
    fun note(id: String): FrictionNote? = jdbc.sql("SELECT ${FrictionNote.COLUMNS} FROM engine.friction_note WHERE id = :id").param("id", id)
        .query { rs, _ -> FrictionNote.read(rs) }.optional().orElse(null)

    /** Whether a member of group [groupId] other than [noteId] has the key [signature]: then the key stays with the group on a pull-out. */
    fun shared(groupId: Long, noteId: String, signature: String): Boolean =
        jdbc.sql("SELECT COUNT(*) FROM engine.friction_note WHERE group_id = :g AND id <> :id AND signature = :s")
            .param("g", groupId).param("id", noteId).param("s", signature).query(Int::class.javaObjectType).single() > 0

    /** The key [signature] points at group [to] instead of [from]; nothing when it points elsewhere, or is not there. */
    fun moveSignature(signature: String, from: Long, to: Long) {
        jdbc.sql("UPDATE engine.friction_signature SET group_id = :to WHERE signature = :s AND group_id = :from")
            .param("to", to).param("s", signature).param("from", from).update()
    }

    /** Every note and every signature of group [from] moves to group [into], and [from] is deleted (its bug columns with it; [into] keeps its own bug); answers the notes moved. The notes keep their `grouped_by`, call and login. */
    fun merge(from: Long, into: Long): Int {
        val moved = jdbc.sql("UPDATE engine.friction_note SET group_id = :into WHERE group_id = :from").param("into", into).param("from", from).update()
        jdbc.sql("UPDATE engine.friction_signature SET group_id = :into WHERE group_id = :from").param("into", into).param("from", from).update()
        jdbc.sql("DELETE FROM engine.friction_group WHERE id = :id").param("id", from).update()
        fixes.recur(into)
        return moved
    }

    /** One group with every member, newest first; null for no such group. */
    fun get(id: Long): FrictionGroup? {
        val head = jdbc.sql("SELECT $HEAD FROM engine.friction_group WHERE id = :id").param("id", id)
            .query { rs, _ -> head(rs) }.optional().orElse(null) ?: return null
        val members = jdbc.sql("SELECT ${FrictionNote.COLUMNS} FROM engine.friction_note WHERE group_id = :id ORDER BY noted_at DESC, id DESC")
            .param("id", id).query { rs, _ -> FrictionNote.read(rs) }.list()
        return FrictionGroup(id, head.title, head.createdAt, members, members.size, head.bug, head.fix, head.pending)
    }

    /** A group's head row: its title, creation, bug and fix. */
    private class Head(val title: String, val createdAt: OffsetDateTime, val bug: FrictionBugRef?, val fix: FrictionFix?, val pending: PendingFix?)

    private fun head(rs: java.sql.ResultSet): Head {
        val id = rs.getLong(1)
        val bug = rs.getString(6)?.let { state -> FrictionBugRef(id, rs.getString(4), rs.getObject(5, Long::class.javaObjectType), state, rs.getString(7)) }
        val fix = rs.getString(10)?.let { state -> FrictionFix(id, rs.getString(8), rs.getLong(9), state,
            rs.getObject(11, OffsetDateTime::class.java), rs.getObject(12, OffsetDateTime::class.java)) }
        val pending = rs.getString(13)?.let { PendingFix(id, it, rs.getString(14)) }
        return Head(rs.getString(2), rs.getObject(3, OffsetDateTime::class.java), bug, fix, pending)
    }

    /** Whether group [groupId] exists. */
    fun exists(groupId: Long): Boolean =
        jdbc.sql("SELECT COUNT(*) FROM engine.friction_group WHERE id = :id").param("id", groupId).query(Int::class.javaObjectType).single() > 0

    /** Jev's mark on [noteId]: `two_things` [value], by [call]. */
    fun mark(noteId: String, value: Double, call: String) {
        jdbc.sql("UPDATE engine.friction_note SET two_things = :v, two_things_call_id = :c WHERE id = :id")
            .param("v", value).param("c", call).param("id", noteId).update()
    }

    /** A user's Not two things: the mark goes, the call stays named so its outcome can be read. */
    fun clearMark(noteId: String) {
        jdbc.sql("UPDATE engine.friction_note SET two_things = NULL WHERE id = :id").param("id", noteId).update()
    }

    /**
     * The lone `friction-two-things` calls made at or before [cutoff] with no outcome whose mark still stands on
     * the note, for the 30-day sweep (`two things: kept`). A mark a user cleared has its outcome already.
     */
    fun unjudgedTwoThings(cutoff: OffsetDateTime): List<String> = jdbc.sql(
        """SELECT c.id FROM engine.jev_call c JOIN engine.friction_note n ON n.two_things_call_id = c.id
           WHERE c.use_case = :u AND c.outcome IS NULL AND c.called_at <= :cutoff AND n.two_things IS NOT NULL ORDER BY c.called_at, c.id""")
        .param("u", FrictionGroupCheck.TWO_THINGS_USE_CASE).param("cutoff", cutoff).query { rs, _ -> rs.getString(1) }.list()

    /**
     * The `friction-group` calls made at or before [cutoff] with no outcome yet whose note is grouped as `jev` or
     * `new`, for the 30-day `kept` sweep ([FrictionGrouper.sweep]). A note the grouper is still retrying, or a user
     * pulled out, has none here.
     */
    fun unjudged(cutoff: OffsetDateTime): List<UnjudgedCall> = jdbc.sql(
        """SELECT c.id, c.action, n.group_id, n.grouped_by FROM engine.jev_call c JOIN engine.friction_note n ON n.jev_call_id = c.id
           WHERE c.use_case = :u AND c.outcome IS NULL AND c.called_at <= :cutoff AND n.group_id IS NOT NULL AND n.grouped_by IN (:jev, :new)
           ORDER BY c.called_at, c.id""")
        .param("u", FrictionGroupCheck.USE_CASE).param("cutoff", cutoff).param("jev", GroupedBy.JEV).param("new", GroupedBy.NEW)
        .query { rs, _ -> UnjudgedCall(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4)) }.list()

    /** [note]'s key becomes one of group [groupId]'s signatures, so the next note with it joins by the exact step. */
    fun addSignature(note: FrictionNote, groupId: Long, now: OffsetDateTime) {
        jdbc.sql("INSERT INTO engine.friction_signature (signature, group_id, line, first_seen) VALUES (:s, :g, :l, :at)")
            .param("s", note.signature ?: FrictionKey.of(note.summary, note.evidence)).param("g", groupId)
            .param("l", FrictionKey.line(note.summary, note.evidence)).param("at", now).update()
    }

    /** No answer from Jev: [noteId]'s [attempts] so far and when the drain tries it next. */
    fun defer(noteId: String, attempts: Int, nextAttemptAt: OffsetDateTime) {
        jdbc.sql("UPDATE engine.friction_note SET group_attempts = :a, next_attempt_at = :n WHERE id = :id")
            .param("a", attempts).param("n", nextAttemptAt).param("id", noteId).update()
    }

    /**
     * `GET /friction/groups`: the groups with at least one member passing [filter] (`repo`, `kind`, `source`, `since`)
     * and, with [FrictionFilter.state], in that state ([FrictionFix.passes]),
     * newest passing member first, at most [FrictionFilter.limit] of them, each with its passing members newest first
     * and its total.
     */
    fun list(filter: FrictionFilter): List<FrictionGroup> {
        val clauses = mutableListOf("group_id IS NOT NULL")
        val params = mutableMapOf<String, Any>()
        filter.repo?.let { clauses += "repo = :repo"; params["repo"] = it }
        filter.kind?.let { clauses += "kind = :kind"; params["kind"] = it }
        filter.source?.let { clauses += "source = :source"; params["source"] = it }
        filter.since?.let { clauses += "noted_at >= :since"; params["since"] = it }
        val members = jdbc.sql("SELECT ${FrictionNote.COLUMNS} FROM engine.friction_note WHERE ${clauses.joinToString(" AND ")} ORDER BY noted_at DESC, id DESC")
            .params(params).query { rs, _ -> FrictionNote.read(rs) }.list()
        val all = members.groupBy { it.groupId!! }.entries.toList()
        val passing = if (filter.state == null) all else {
            if (all.isEmpty()) return emptyList()
            val states = jdbc.sql("SELECT $HEAD FROM engine.friction_group WHERE id IN (:ids)").param("ids", all.map { it.key })
                .query { rs, _ -> rs.getLong(1) to head(rs).let { h -> FrictionFix.stateOf(h.fix, h.pending) } }.list().toMap()
            all.filter { states[it.key]?.let { st -> FrictionFix.passes(st, filter.state) } == true }
        }
        val byGroup = passing.take(filter.limit)
        if (byGroup.isEmpty()) return emptyList()
        val ids = byGroup.map { it.key }
        val heads = jdbc.sql("SELECT $HEAD FROM engine.friction_group WHERE id IN (:ids)").param("ids", ids)
            .query { rs, _ -> rs.getLong(1) to head(rs) }.list().toMap()
        val totals = jdbc.sql("SELECT group_id, COUNT(*) FROM engine.friction_note WHERE group_id IN (:ids) GROUP BY group_id").param("ids", ids)
            .query { rs, _ -> rs.getLong(1) to rs.getInt(2) }.list().toMap()
        return byGroup.map { (id, notes) ->
            val head = heads.getValue(id)
            FrictionGroup(id, head.title, head.createdAt, notes, totals[id] ?: notes.size, head.bug, head.fix, head.pending)
        }
    }
}

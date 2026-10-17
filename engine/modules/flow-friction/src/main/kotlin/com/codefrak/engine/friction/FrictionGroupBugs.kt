package com.codefrak.engine.friction

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet

/**
 * The two-things bug lifecycle of a friction group (`engine.friction_group`'s `bug_repo`, `bug_issue`, `bug_state`
 * and `bug_intake_id`; engine/README.md, "Friction notes", "Two things"). The writes are the callers' own
 * transaction ([FrictionBugs]'s), nothing here opens one.
 */
@Repository
class FrictionGroupBugs(private val jdbc: JdbcClient) {
    private companion object {
        const val HEAD = "id, bug_repo, bug_issue, bug_state, bug_intake_id"
    }

    private fun bugOf(rs: ResultSet): FrictionBugRef? = rs.getString(4)?.let { state ->
        FrictionBugRef(rs.getLong(1), rs.getString(2), rs.getObject(3, Long::class.javaObjectType), state, rs.getString(5)) }

    /** The two-things bug of group [groupId], null without one (or no such group). */
    fun bug(groupId: Long): FrictionBugRef? =
        jdbc.sql("SELECT $HEAD FROM engine.friction_group WHERE id = :id").param("id", groupId).query { rs, _ -> bugOf(rs) }.optional().orElse(null)

    /** Claims group [groupId]'s bug as `filing` in [repo], when it has none; false when it has one (any state). The caller's transaction. */
    fun claimBug(groupId: Long, repo: String): Boolean =
        jdbc.sql("UPDATE engine.friction_group SET bug_repo = :r, bug_issue = NULL, bug_state = :s, bug_intake_id = NULL WHERE id = :id AND bug_state IS NULL")
            .param("r", repo).param("s", FrictionBugRef.FILING).param("id", groupId).update() == 1

    /** Undoes [claimBug] for an intake that did not start (or was cancelled before it filed): the group has no bug again. */
    fun unclaimBug(groupId: Long) {
        jdbc.sql("UPDATE engine.friction_group SET bug_repo = NULL, bug_issue = NULL, bug_state = NULL, bug_intake_id = NULL WHERE id = :id AND bug_state = :s AND bug_issue IS NULL")
            .param("id", groupId).param("s", FrictionBugRef.FILING).update()
    }

    /** The intake that files group [groupId]'s bug. */
    fun bugStarted(groupId: Long, intake: String) {
        jdbc.sql("UPDATE engine.friction_group SET bug_intake_id = :i WHERE id = :id").param("i", intake).param("id", groupId).update()
    }

    /** Group [groupId]'s intake filed its bug as [issue]: `open`, from `filing`. */
    fun bugFiled(groupId: Long, issue: Long) {
        jdbc.sql("UPDATE engine.friction_group SET bug_issue = :i, bug_state = :o WHERE id = :id AND bug_state = :f")
            .param("i", issue).param("o", FrictionBugRef.OPEN).param("id", groupId).param("f", FrictionBugRef.FILING).update()
    }

    /** The engine saw group [groupId]'s bug closed: `closed`, so the next mark files afresh. */
    fun bugClosed(groupId: Long) {
        jdbc.sql("UPDATE engine.friction_group SET bug_state = :c WHERE id = :id AND bug_state = :o")
            .param("c", FrictionBugRef.CLOSED).param("id", groupId).param("o", FrictionBugRef.OPEN).update()
    }

    /** A `closed` bug makes way for the next mark's: its columns go, so [claimBug] can take the row again. */
    fun bugClosedAway(groupId: Long) {
        jdbc.sql("UPDATE engine.friction_group SET bug_repo = NULL, bug_issue = NULL, bug_state = NULL, bug_intake_id = NULL WHERE id = :id AND bug_state = :c")
            .param("id", groupId).param("c", FrictionBugRef.CLOSED).update()
    }

    /** Every group's bug in [state], oldest group first. */
    fun bugs(state: String): List<FrictionBugRef> =
        jdbc.sql("SELECT $HEAD FROM engine.friction_group WHERE bug_state = :s ORDER BY id").param("s", state).query { rs, _ -> bugOf(rs)!! }.list()
}

package com.codefrak.engine.mcp

import com.codefrak.engine.smith.ParentNotes
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * `engine.parent_note` (V92, #2596): a live parent's notes for the next wave's parent session, keyed to the split's
 * feature instance and the run that wrote them. [add] writes at once, so a note outlives its run being killed; [notes]
 * is every note of the split, oldest first, as the next parent's brief carries them.
 */
@Repository
class ParentNoteStore(private val jdbc: JdbcClient) : ParentNoteLog {
    override fun add(split: ParentSplit, note: String) {
        jdbc.sql("INSERT INTO engine.parent_note (instance, repo, issue, run_id, note) VALUES (:instance, :repo, :issue, :run, :note)")
            .param("instance", split.instance).param("repo", split.repo).param("issue", split.parent)
            .param("run", split.runId).param("note", note).update()
    }

    override fun notes(instance: String): List<Map<String, Any?>> =
        jdbc.sql("SELECT run_id, noted_at, note FROM engine.parent_note WHERE instance = :instance ORDER BY id")
            .param("instance", instance)
            .query { rs, _ -> mapOf("run" to rs.getString("run_id"), "notedAt" to rs.getTimestamp("noted_at").toInstant().toString(), "note" to rs.getString("note")) }
            .list()
}

/** note_for_next_parent's store ([ParentTools]): [add] writes one note of [ParentSplit] at once; [notes] reads the split's back. */
interface ParentNoteLog : ParentNotes {
    fun add(split: ParentSplit, note: String)
}

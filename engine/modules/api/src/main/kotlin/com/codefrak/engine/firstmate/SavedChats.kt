package com.codefrak.engine.firstmate

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** One row of `engine.saved_chat`; `messages` is the stored JSON array, null when the row was listed without it. */
data class SavedChat(
    val id: String, val title: String, val startedAt: OffsetDateTime?, val savedAt: OffsetDateTime, val repo: String?,
    val tier: Int?, val claudeSessionId: String?, val savedFrom: String?, val turns: Int, val messageCount: Int,
    val messages: String?,
)

/** What a save writes; the engine derives the counts and stamps `saved_at`. */
data class SavedChatWrite(
    val title: String, val startedAt: OffsetDateTime?, val repo: String?, val tier: Int?, val claudeSessionId: String?,
    val savedFrom: String?, val turns: Int, val messageCount: Int, val messages: String,
)

/**
 * `engine.saved_chat` (V25): First mate chats the desktop saved for later (engine/README.md, "Saved First mate
 * chats"), one row per conversation, keyed by the UUID the desktop minted for it.
 */
@Repository
class SavedChats(private val jdbc: JdbcClient) {
    /** Newest `saved_at` first, without the transcripts. */
    fun list(): List<SavedChat> =
        jdbc.sql("SELECT $COLUMNS, NULL FROM engine.saved_chat ORDER BY saved_at DESC, id").query(ROW).list()

    fun get(id: String): SavedChat? =
        jdbc.sql("SELECT $COLUMNS, messages FROM engine.saved_chat WHERE id = :id").param("id", id).query(ROW).optional().orElse(null)

    /** Inserts or updates row [id]; true when it was created. */
    fun save(id: String, chat: SavedChatWrite, now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)): Boolean {
        fun bind(sql: String) = jdbc.sql(sql).param("id", id).param("title", chat.title).param("started", chat.startedAt)
            .param("saved", now).param("repo", chat.repo).param("tier", chat.tier).param("session", chat.claudeSessionId)
            .param("from", chat.savedFrom).param("turns", chat.turns).param("count", chat.messageCount).param("messages", chat.messages)
        val updated = bind("""UPDATE engine.saved_chat SET title = :title, started_at = :started, saved_at = :saved, repo = :repo,
            tier = :tier, claude_session_id = :session, saved_from = :from, turns = :turns, message_count = :count, messages = :messages
            WHERE id = :id""").update()
        if (updated > 0) return false
        bind("""INSERT INTO engine.saved_chat (id, title, started_at, saved_at, repo, tier, claude_session_id, saved_from, turns,
            message_count, messages) VALUES (:id, :title, :started, :saved, :repo, :tier, :session, :from, :turns, :count, :messages)""").update()
        return true
    }

    /** False when there is no such row. */
    fun delete(id: String): Boolean = jdbc.sql("DELETE FROM engine.saved_chat WHERE id = :id").param("id", id).update() == 1

    private companion object {
        const val COLUMNS = "id, title, started_at, saved_at, repo, tier, claude_session_id, saved_from, turns, message_count"
        val ROW = { rs: ResultSet, _: Int ->
            SavedChat(rs.getString(1), rs.getString(2), rs.getObject(3, OffsetDateTime::class.java), rs.getObject(4, OffsetDateTime::class.java),
                rs.getString(5), (rs.getObject(6) as? Number)?.toInt(), rs.getString(7), rs.getString(8), rs.getInt(9), rs.getInt(10), rs.getString(11))
        }
    }
}

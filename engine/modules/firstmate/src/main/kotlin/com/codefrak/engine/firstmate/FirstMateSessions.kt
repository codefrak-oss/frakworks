package com.codefrak.engine.firstmate

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.OffsetDateTime
import java.util.UUID

/** One row of `engine.first_mate_session` (V75, V76, V80). */
data class FirstMateSession(val id: String, val login: String, val tokenId: String, val repo: String, val state: String,
                            val createdAt: OffsetDateTime, val endedAt: OffsetDateTime?,
                            val claudeSessionId: String? = null, val cwd: String? = null, val lastMessageAt: OffsetDateTime? = null,
                            val frozenAt: OffsetDateTime? = null, val workdirDropped: Boolean = false,
                            val deliveredSeq: Long = 0, val podEpoch: Int = 0,
                            /** The session's HITLP lineage as JSON (V80, `Lineage`), captured at its start; null on a session from before it. */
                            val lineage: String? = null,
                            /** The tier its pod runs at (V87), given at its start; null (the phone) means `engine.firstmate.tier`. */
                            val tier: Int? = null) {
    val open get() = state == FirstMateSessions.OPEN
    val frozen get() = state == FirstMateSessions.FROZEN
    val freezing get() = state == FirstMateSessions.FREEZING
}

/** One transcript row of `engine.first_mate_message`, its body parsed. */
data class FirstMateMessage(val seq: Int, val role: String, val body: Any?, val at: OffsetDateTime)

/**
 * The phone's first-mate sessions and their transcripts (V75; engine/README.md, "First-mate sessions"):
 * a session belongs to the login whose first-mate token started it, and ends when
 * that login deletes it or the token is revoked ([endForToken]). An open session idle for 15 minutes is [freeze]d
 * (`FirstMateFreezer`), and [thaw]ed by its next message; a frozen one is kept until deleted or expired ([expired]).
 */
@Repository
class FirstMateSessions(private val jdbc: JdbcClient) {
    companion object {
        const val OPEN = "open"
        const val ENDED = "ended"
        const val FROZEN = "frozen"
        /** Being frozen: the pod's inbox answers `{"freeze": true}` (engine/README.md, "Freeze"). */
        const val FREEZING = "freezing"
        private val JSON = jacksonObjectMapper()
        /** How often [insert] takes the next seq when another replica took the one it read. */
        private const val INSERT_ATTEMPTS = 5
    }

    private val columns = "id, login, token_id, repo, state, created_at, ended_at, claude_session_id, cwd, last_message_at, frozen_at, workdir_dropped, delivered_seq, pod_epoch, lineage, tier"

    /**
     * A new open session; [lineage] (the JSON of `Lineage`, built from its id) is written by [lineageFor] when given,
     * and [tier] (the desktop's, in the tier map) when given: null leaves the pod at `engine.firstmate.tier`.
     */
    fun start(login: String, tokenId: String, repo: String, tier: Int? = null, lineageFor: ((String) -> String)? = null): FirstMateSession {
        val id = UUID.randomUUID().toString()
        jdbc.sql("INSERT INTO engine.first_mate_session (id, login, token_id, repo, state, created_at, cwd, last_message_at, lineage, tier) VALUES (:id, :l, :t, :r, :s, :now, :cwd, :now, :lin, :tier)")
            .param("lin", lineageFor?.invoke(id)).param("id", id).param("l", login).param("t", tokenId).param("r", repo).param("s", OPEN).param("now", OffsetDateTime.now())
            .param("cwd", FIRST_MATE_CWD).param("tier", tier, java.sql.Types.INTEGER).update()
        return get(id)!!
    }

    fun get(id: String): FirstMateSession? =
        jdbc.sql("SELECT $columns FROM engine.first_mate_session WHERE id = :id").param("id", id)
            .query { rs, _ -> FirstMateSession(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getObject(6, OffsetDateTime::class.java), rs.getObject(7, OffsetDateTime::class.java), rs.getString(8), rs.getString(9),
                rs.getObject(10, OffsetDateTime::class.java), rs.getObject(11, OffsetDateTime::class.java), rs.getBoolean(12),
                rs.getLong(13), rs.getInt(14), rs.getString(15), rs.getObject(16, Int::class.javaObjectType)) }.optional().orElse(null)

    /** Ends [id] when it is open or frozen, and drops its snapshot; true when it was. */
    fun end(id: String): Boolean =
        (jdbc.sql("UPDATE engine.first_mate_session SET state = :e, ended_at = :now WHERE id = :id AND state IN (:o, :f, :g)")
            .param("e", ENDED).param("now", OffsetDateTime.now()).param("id", id).param("o", OPEN).param("f", FROZEN).param("g", FREEZING).update() == 1)
            .also { if (it) jdbc.sql("DELETE FROM engine.first_mate_snapshot WHERE session_id = :id").param("id", id).update() }

    /** The open and frozen sessions started with token [tokenId]: whose pods a revoke stops. */
    fun openForToken(tokenId: String): List<FirstMateSession> =
        jdbc.sql("SELECT id FROM engine.first_mate_session WHERE token_id = :t AND state IN (:o, :f)").param("t", tokenId)
            .param("o", OPEN).param("f", FROZEN).query(String::class.java).list().mapNotNull { id -> id?.let { get(it) } }

    /** Ends every open or frozen session started with token [tokenId] and no other; the number ended. */
    fun endForToken(tokenId: String): Int {
        val ids = openForToken(tokenId).map { it.id }
        ids.forEach { jdbc.sql("DELETE FROM engine.first_mate_snapshot WHERE session_id = :id").param("id", it).update() }
        return jdbc.sql("UPDATE engine.first_mate_session SET state = :e, ended_at = :now WHERE token_id = :t AND state IN (:o, :f)")
            .param("e", ENDED).param("now", OffsetDateTime.now()).param("t", tokenId).param("o", OPEN).param("f", FROZEN).update()
    }

    /** Records the id `claude -p` gave [sessionId]'s conversation (its init line), what a resume passes to `--resume`. */
    fun setClaudeSessionId(sessionId: String, claudeId: String) {
        jdbc.sql("UPDATE engine.first_mate_session SET claude_session_id = :c WHERE id = :id AND (claude_session_id IS NULL OR claude_session_id <> :c)")
            .param("c", claudeId).param("id", sessionId).update()
    }

    /** The open sessions whose last transcript row is older than [cutoff], and the freezing ones a freeze left unfinished: the ones to freeze. */
    fun idleSince(cutoff: OffsetDateTime): List<FirstMateSession> =
        jdbc.sql("SELECT id FROM engine.first_mate_session WHERE (state = :o AND last_message_at <= :c) OR state = :g")
            .param("o", OPEN).param("c", cutoff).param("g", FREEZING)
            .query(String::class.java).list().mapNotNull { id -> id?.let { get(it) } }

    /**
     * Marks open [id] freezing when it is still idle since [cutoff] (or leaves it freezing): its pod's inbox answers
     * `{"freeze": true}` (engine/README.md, "Freeze"). True when it is freezing now.
     */
    fun setFreezing(id: String, cutoff: OffsetDateTime): Boolean =
        jdbc.sql("UPDATE engine.first_mate_session SET state = :g WHERE id = :id AND ((state = :o AND last_message_at <= :c) OR state = :g)")
            .param("g", FREEZING).param("id", id).param("o", OPEN).param("c", cutoff).update() == 1

    /** The highest transcript seq of [sessionId], 0 for none. */
    fun lastSeq(sessionId: String): Int =
        jdbc.sql("SELECT COALESCE(MAX(seq), 0) FROM engine.first_mate_message WHERE session_id = :s").param("s", sessionId).query(Int::class.java).single()

    /** Whether [sessionId]'s pod posted `status exited` (a `pod-status` system row) after transcript seq [after]. */
    fun podExitedAfter(sessionId: String, after: Int): Boolean =
        jdbc.sql("SELECT seq, role, body, at FROM engine.first_mate_message WHERE session_id = :s AND role = 'system' AND seq > :a ORDER BY seq")
            .param("s", sessionId).param("a", after).query { rs, _ -> message(rs) }.list()
            .any { (it.body as? Map<*, *>)?.let { b -> b["type"] == "pod-status" && b["state"] == "exited" } == true }

    /**
     * Marks [id] frozen at [now] when it is freezing (its relay's own status and last lines count as no message), or
     * open and still idle since [cutoff] (no message came while its pod was read); true when it was. A phone message
     * that comes while freezing stays after `delivered_seq` and reaches the resumed pod.
     */
    fun freeze(id: String, cutoff: OffsetDateTime, workdirDropped: Boolean, now: OffsetDateTime): Boolean =
        jdbc.sql("UPDATE engine.first_mate_session SET state = :f, frozen_at = :now, workdir_dropped = :d WHERE id = :id AND (state = :g OR (state = :o AND last_message_at <= :c))")
            .param("f", FROZEN).param("now", now).param("d", workdirDropped).param("id", id).param("o", OPEN).param("g", FREEZING).param("c", cutoff).update() == 1

    /** Opens frozen [id] again; true when it was frozen (and this caller is the one resuming it). */
    fun thaw(id: String): Boolean =
        jdbc.sql("UPDATE engine.first_mate_session SET state = :o, last_message_at = :now WHERE id = :id AND state = :f")
            .param("o", OPEN).param("now", OffsetDateTime.now()).param("id", id).param("f", FROZEN).update() == 1

    /** Deletes every session frozen before [before], with its transcript and snapshot; the number deleted. */
    fun deleteFrozenBefore(before: OffsetDateTime): Int =
        jdbc.sql("DELETE FROM engine.first_mate_session WHERE state = :f AND frozen_at < :b").param("f", FROZEN).param("b", before).update()

    /** Appends [body] (anything Jackson writes) to [sessionId]'s transcript as [role]; the row written. */
    fun append(sessionId: String, role: String, body: Any?): FirstMateMessage = insert(sessionId, role, body)!!

    /**
     * Appends the phone's message [body]; with [clientId], a repeat of one already stored inserts nothing and answers
     * that row with `false` (a retried post during a deploy gap). The row and whether it was inserted now.
     */
    fun appendUser(sessionId: String, body: Any?, clientId: String?): Pair<FirstMateMessage, Boolean> {
        if (clientId != null) byClientId(sessionId, clientId)?.let { return it to false }
        insert(sessionId, "user", body, clientId = clientId)?.let { return it to true }
        return byClientId(sessionId, clientId!!)!! to false
    }

    private fun byClientId(sessionId: String, clientId: String): FirstMateMessage? =
        jdbc.sql("SELECT seq, role, body, at FROM engine.first_mate_message WHERE session_id = :s AND client_id = :c").param("s", sessionId).param("c", clientId)
            .query { rs, _ -> message(rs) }.optional().orElse(null)

    /**
     * Stores the pod's stdout [lines] as `assistant` rows numbered `out_seq` [from], [from]+1, … in the session's current
     * `pod_epoch`, skipping every one already stored (a retried or overlapping batch). The rows inserted now, and the
     * highest `out_seq` stored in this epoch (what the relay may drop its buffer up to).
     */
    fun appendOutbox(sessionId: String, from: Long, lines: List<Any?>): Pair<List<FirstMateMessage>, Long> {
        val epoch = get(sessionId)?.podEpoch ?: 0
        val stored = lines.mapIndexedNotNull { i, body ->
            val outSeq = from + i
            if (hasOut(sessionId, epoch, outSeq)) null else insert(sessionId, "assistant", body, epoch, outSeq)
        }
        val accepted = jdbc.sql("SELECT COALESCE(MAX(out_seq), 0) FROM engine.first_mate_message WHERE session_id = :s AND pod_epoch = :e")
            .param("s", sessionId).param("e", epoch).query(Long::class.java).single()
        return stored to accepted
    }

    /** Whether [sessionId]'s pod of [epoch] stored line [outSeq]. */
    fun hasOut(sessionId: String, epoch: Int, outSeq: Long): Boolean =
        jdbc.sql("SELECT COUNT(*) FROM engine.first_mate_message WHERE session_id = :s AND pod_epoch = :e AND out_seq = :o")
            .param("s", sessionId).param("e", epoch).param("o", outSeq).query(Int::class.java).single() > 0

    /**
     * Inserts one row at the next `seq`. Another replica may take that seq first (a primary-key conflict): it retries at
     * the next one. A conflict on `out_seq` or `client_id` means the row is already there: null, nothing inserted.
     */
    private fun insert(sessionId: String, role: String, body: Any?, epoch: Int? = null, outSeq: Long? = null, clientId: String? = null): FirstMateMessage? {
        val json = JSON.writeValueAsString(body)
        repeat(INSERT_ATTEMPTS) {
            val seq = jdbc.sql("SELECT COALESCE(MAX(seq), 0) + 1 FROM engine.first_mate_message WHERE session_id = :s")
                .param("s", sessionId).query(Int::class.java).single()
            val now = OffsetDateTime.now()
            try {
                jdbc.sql("INSERT INTO engine.first_mate_message (session_id, seq, role, body, at, pod_epoch, out_seq, client_id) VALUES (:s, :q, :r, :b, :at, :e, :o, :c)")
                    .param("s", sessionId).param("q", seq).param("r", role).param("b", json).param("at", now)
                    .param("e", epoch, java.sql.Types.INTEGER).param("o", outSeq, java.sql.Types.BIGINT).param("c", clientId, java.sql.Types.VARCHAR).update()
            } catch (e: DuplicateKeyException) {
                if (outSeq != null && hasOut(sessionId, epoch!!, outSeq)) return null
                if (clientId != null && byClientId(sessionId, clientId) != null) return null
                return@repeat
            }
            jdbc.sql("UPDATE engine.first_mate_session SET last_message_at = :at WHERE id = :s").param("at", now).param("s", sessionId).update()
            return FirstMateMessage(seq, role, body, now)
        }
        throw IllegalStateException("first-mate session $sessionId: no free seq after $INSERT_ATTEMPTS attempts")
    }

    /** The phone's messages to [sessionId] with seq over [after], oldest first: what the pod's inbox answers. */
    fun userAfter(sessionId: String, after: Long): List<FirstMateMessage> =
        jdbc.sql("SELECT seq, role, body, at FROM engine.first_mate_message WHERE session_id = :s AND role = 'user' AND seq > :a ORDER BY seq")
            .param("s", sessionId).param("a", after).query { rs, _ -> message(rs) }.list()

    /** Records that the pod wrote message [seq] to claude; never lowers `delivered_seq`. The value after. */
    fun ack(sessionId: String, seq: Long): Long {
        jdbc.sql("UPDATE engine.first_mate_session SET delivered_seq = GREATEST(delivered_seq, :q) WHERE id = :s").param("q", seq).param("s", sessionId).update()
        return get(sessionId)?.deliveredSeq ?: 0
    }

    /** Starts a new pod lifetime for [sessionId]: `pod_epoch` + 1, so the new pod's `out_seq` 1 never collides. The new epoch. */
    fun nextEpoch(sessionId: String): Int {
        jdbc.sql("UPDATE engine.first_mate_session SET pod_epoch = pod_epoch + 1 WHERE id = :s").param("s", sessionId).update()
        return get(sessionId)?.podEpoch ?: 0
    }

    private fun message(rs: java.sql.ResultSet) =
        FirstMateMessage(rs.getInt(1), rs.getString(2), JSON.readValue<Any?>(rs.getString(3)), rs.getObject(4, OffsetDateTime::class.java))

    /** [sessionId]'s transcript, oldest first. */
    fun transcript(sessionId: String): List<FirstMateMessage> =
        jdbc.sql("SELECT seq, role, body, at FROM engine.first_mate_message WHERE session_id = :s ORDER BY seq").param("s", sessionId)
            .query { rs, _ -> message(rs) }.list()
}

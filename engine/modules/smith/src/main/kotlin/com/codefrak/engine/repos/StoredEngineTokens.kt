package com.codefrak.engine.repos

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

/** [EngineTokens] in `engine.engine_token` (V70). */
@Repository
class StoredEngineTokens(private val jdbc: JdbcClient) : EngineTokens {
    private companion object {
        val random = SecureRandom()
    }

    private val columns = "id, login, name, prefix, created_at, created_by, expires_at, last_used_at, revoked_at, purpose, session_id"

    override fun mint(login: String, name: String, expiresInDays: Int?, createdBy: String?, purpose: String?, sessionId: String?): MintedToken {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        val token = EngineTokens.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val id = UUID.randomUUID().toString()
        val now = OffsetDateTime.now()
        val expiresAt = expiresInDays?.let { now.plusDays(it.toLong()) }
        jdbc.sql("""INSERT INTO engine.engine_token (id, login, name, prefix, hash, created_at, created_by, expires_at, purpose, session_id)
            VALUES (:id, :login, :name, :prefix, :hash, :now, :by, :exp, :purpose, :session)""")
            .param("id", id).param("login", login).param("name", name).param("prefix", EngineTokens.prefixOf(token)).param("hash", EngineTokens.hash(token))
            .param("now", now).param("by", createdBy).param("exp", expiresAt).param("purpose", purpose).param("session", sessionId).update()
        return MintedToken(get(id)!!, token)
    }

    override fun resolve(token: String): TokenResolution {
        val row = jdbc.sql("SELECT $columns FROM engine.engine_token WHERE hash = :h").param("h", EngineTokens.hash(token)).query { rs, _ -> row(rs) }.optional().orElse(null)
        return if (row != null && row.active()) TokenResolution.Ok(row) else TokenResolution.NoSuchToken(EngineTokens.prefixOf(token))
    }

    override fun touch(id: String): Boolean {
        val now = OffsetDateTime.now()
        return jdbc.sql("UPDATE engine.engine_token SET last_used_at = :now WHERE id = :id AND (last_used_at IS NULL OR last_used_at < :before)")
            .param("now", now).param("id", id).param("before", now.minusSeconds(EngineTokens.TOUCH_SECONDS)).update() == 1
    }

    override fun get(id: String): EngineToken? =
        jdbc.sql("SELECT $columns FROM engine.engine_token WHERE id = :id").param("id", id).query { rs, _ -> row(rs) }.optional().orElse(null)

    override fun list(): List<EngineToken> =
        jdbc.sql("SELECT $columns FROM engine.engine_token ORDER BY created_at DESC, id").query { rs, _ -> row(rs) }.list()

    override fun revoke(id: String): Boolean? {
        get(id) ?: return null
        return jdbc.sql("UPDATE engine.engine_token SET revoked_at = :now WHERE id = :id AND revoked_at IS NULL")
            .param("now", OffsetDateTime.now()).param("id", id).update() == 1
    }

    override fun revokeForSession(sessionId: String): Int =
        jdbc.sql("UPDATE engine.engine_token SET revoked_at = :now WHERE session_id = :s AND purpose = :p AND revoked_at IS NULL")
            .param("now", OffsetDateTime.now()).param("s", sessionId).param("p", EngineTokens.FIRST_MATE_POD).update()

    override fun anyActive(): Boolean =
        jdbc.sql("SELECT COUNT(*) FROM engine.engine_token WHERE revoked_at IS NULL").query(Int::class.java).single() > 0

    private fun row(rs: java.sql.ResultSet) = EngineToken(
        rs.getString("id"), rs.getString("login"), rs.getString("name"), rs.getString("prefix"),
        rs.getObject("created_at", OffsetDateTime::class.java), rs.getString("created_by"),
        rs.getObject("expires_at", OffsetDateTime::class.java), rs.getObject("last_used_at", OffsetDateTime::class.java),
        rs.getObject("revoked_at", OffsetDateTime::class.java), rs.getString("purpose"), rs.getString("session_id"))
}

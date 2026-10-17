package com.codefrak.engine.firstmate.push

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/** One registered device: its APNs device token, the login and first-mate token that registered it. */
data class PushDevice(
    val deviceToken: String, val login: String, val tokenId: String, val platform: String, val environment: String,
    val createdAt: OffsetDateTime, val updatedAt: OffsetDateTime,
)

/**
 * `engine.push_device` (V79; engine/README.md, "Push notifications"): the phones that get the first mate's pushes,
 * keyed by APNs device token. Registering a token again moves the row to the new login and token, never a second row.
 */
@Component
class PushDevices(private val jdbc: JdbcClient) {
    companion object {
        const val IOS = "ios"
        const val SANDBOX = "sandbox"
        const val PRODUCTION = "production"
        val ENVIRONMENTS = setOf(SANDBOX, PRODUCTION)
    }

    /** Stores [deviceToken] for [login] and [tokenId]; true when the row is new, false when it was updated. */
    fun register(deviceToken: String, login: String, tokenId: String, platform: String, environment: String): Boolean {
        val now = OffsetDateTime.now()
        val updated = jdbc.sql("UPDATE engine.push_device SET login = :l, token_id = :t, platform = :p, environment = :e, updated_at = :now WHERE device_token = :d")
            .param("l", login).param("t", tokenId).param("p", platform).param("e", environment).param("now", now).param("d", deviceToken).update()
        if (updated == 1) return false
        jdbc.sql("INSERT INTO engine.push_device (device_token, login, token_id, platform, environment, created_at, updated_at) VALUES (:d, :l, :t, :p, :e, :now, :now)")
            .param("d", deviceToken).param("l", login).param("t", tokenId).param("p", platform).param("e", environment).param("now", now).update()
        return true
    }

    fun get(deviceToken: String): PushDevice? =
        jdbc.sql("SELECT * FROM engine.push_device WHERE device_token = :d").param("d", deviceToken).query { rs, _ -> row(rs) }.optional().orElse(null)

    /** [login]'s devices (case-insensitive, as logins are), oldest first. */
    fun forLogin(login: String): List<PushDevice> =
        jdbc.sql("SELECT * FROM engine.push_device WHERE LOWER(login) = LOWER(:l) ORDER BY created_at").param("l", login).query { rs, _ -> row(rs) }.list()

    fun all(): List<PushDevice> = jdbc.sql("SELECT * FROM engine.push_device ORDER BY created_at").query { rs, _ -> row(rs) }.list()

    /** Deletes [deviceToken], but only [login]'s (null: whoever's, for APNs saying it is gone); true when a row went. */
    fun delete(deviceToken: String, login: String? = null): Boolean =
        if (login == null) jdbc.sql("DELETE FROM engine.push_device WHERE device_token = :d").param("d", deviceToken).update() == 1
        else jdbc.sql("DELETE FROM engine.push_device WHERE device_token = :d AND LOWER(login) = LOWER(:l)").param("d", deviceToken).param("l", login).update() == 1

    /** Deletes the devices token [tokenId] registered (it was revoked); how many went. */
    fun deleteForToken(tokenId: String): Int =
        jdbc.sql("DELETE FROM engine.push_device WHERE token_id = :t").param("t", tokenId).update()

    private fun row(rs: java.sql.ResultSet) = PushDevice(
        rs.getString("device_token"), rs.getString("login"), rs.getString("token_id"), rs.getString("platform"),
        rs.getString("environment"), rs.getObject("created_at", OffsetDateTime::class.java), rs.getObject("updated_at", OffsetDateTime::class.java))
}

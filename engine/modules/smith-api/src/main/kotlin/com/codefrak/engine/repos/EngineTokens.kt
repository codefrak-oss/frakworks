package com.codefrak.engine.repos

import java.security.MessageDigest
import java.time.OffsetDateTime

/** One row of `engine.engine_token`, never its hash: what `GET /tokens` and `read_tokens` list. */
data class EngineToken(
    val id: String, val login: String, val name: String, val prefix: String, val createdAt: OffsetDateTime,
    val createdBy: String?, val expiresAt: OffsetDateTime?, val lastUsedAt: OffsetDateTime?, val revokedAt: OffsetDateTime?,
    /** What the token is for: [EngineTokens.FIRST_MATE] for a phone's first-mate token, null for any other. */
    val purpose: String? = null,
    /** The first-mate session a [EngineTokens.FIRST_MATE_POD] token is scoped to; null for any other. */
    val sessionId: String? = null,
) {
    /** Revoked, or past its expiry, as of [now]. */
    fun active(now: OffsetDateTime = OffsetDateTime.now()) = revokedAt == null && (expiresAt == null || expiresAt.isAfter(now))
}

/** A freshly minted token: its row and the token itself, shown to the minter once and never stored or logged. */
data class MintedToken(val row: EngineToken, val token: String) {
    override fun toString() = "MintedToken(row=$row, token=${EngineTokens.PREFIX}${row.prefix}…)"
}

/** What a presented token resolves to. */
sealed interface TokenResolution {
    data class Ok(val row: EngineToken) : TokenResolution
    /** Unknown, revoked or expired; [prefix] is the presented token's first 8 characters after `frk_`, for the log. */
    data class NoSuchToken(val prefix: String) : TokenResolution
}

/**
 * `engine.engine_token` (V70; engine/README.md, "Tokens"): the bearer tokens the engine issues, each bound to a login.
 * A token is [PREFIX] and 32 bytes of randomness as base64url without padding (43 characters); the row keeps its hex
 * SHA-256 and its first 8 characters after the prefix, never the token: a database read cannot yield one. The store is
 * `StoredEngineTokens` (engine-smith); every module above programs against this.
 */
interface EngineTokens {
    companion object {
        const val PREFIX = "frk_"
        /** The purpose of a token minted for the phone's first mate (engine/README.md, "First-mate sessions"). */
        const val FIRST_MATE = "first-mate"
        val PURPOSES = setOf(FIRST_MATE)
        /**
         * The purpose of the token a first-mate session pod calls the engine with (engine/README.md,
         * "First-mate sessions"): scoped by `session_id` to `/first-mate/pod/{session}/` (`BearerTokenFilter`). Minted by the engine at
         * pod start, never through `POST /tokens`, so not in [PURPOSES].
         */
        const val FIRST_MATE_POD = "first-mate-pod"
        /** How often [touch] writes `last_used_at` at most. */
        const val TOUCH_SECONDS = 60L

        /** Hex SHA-256 of [token], the row's `hash`. */
        fun hash(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        /** The 8 characters after [PREFIX], padded for a token shorter than that. */
        fun prefixOf(token: String): String = token.removePrefix(PREFIX).take(8).padEnd(8, '?')
    }

    /** Mints a token for [login] (the caller checks it is trusted), named [name], expiring after [expiresInDays] or never. */
    fun mint(login: String, name: String, expiresInDays: Int?, createdBy: String?, purpose: String? = null, sessionId: String? = null): MintedToken

    /** The row [token] hashes to, when it is active; else [TokenResolution.NoSuchToken] with the presented prefix. */
    fun resolve(token: String): TokenResolution

    /** Sets `last_used_at` to now unless it was set within the last [TOUCH_SECONDS]; one conditional UPDATE, replica-safe. */
    fun touch(id: String): Boolean

    fun get(id: String): EngineToken?

    /** Every row, newest first, without hashes. */
    fun list(): List<EngineToken>

    /** Sets `revoked_at`: true when the row was unrevoked, false when already revoked; null when there is no such row. */
    fun revoke(id: String): Boolean?

    /** Revokes every unrevoked [FIRST_MATE_POD] token of session [sessionId] (its pod was deleted); the number revoked. */
    fun revokeForSession(sessionId: String): Int

    /** Whether any unrevoked row exists: once one does, the `/tokens` endpoints need a token (the bootstrap door is shut). */
    fun anyActive(): Boolean
}

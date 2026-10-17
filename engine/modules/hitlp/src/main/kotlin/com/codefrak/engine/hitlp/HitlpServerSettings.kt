package com.codefrak.engine.hitlp

import com.codefrak.engine.accounts.SecretsKey
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

/** Where [HitlpRequester] gets its server from at each call: the URL with the opened token, or null when none is set. */
fun interface HitlpEndpointSource {
    fun endpoint(): HitlpEndpoint?
}

/** The HITLP server's MCP endpoint and the bearer token the engine sends to it; [token] empty when none is set. */
data class HitlpEndpoint(val url: String, val token: String) {
    override fun toString() = "HitlpEndpoint($url)"
}

/** `GET /settings/hitlp` without the secret: the MCP URL and when the requester token was set. */
data class HitlpServerView(val mcpUrl: String?, val requesterTokenSetAt: OffsetDateTime?)

/**
 * The HITLP server settings (#2500, part 1 of #2498; engine/README.md, "The HITLP requester"): the one row of
 * `engine.hitlp_server` (the requester's MCP URL and its bearer token sealed under the engine's key with [REQUESTER_AAD]),
 * V85. The only reader and writer: `HitlpServerController` sets it through `PUT /settings/hitlp`, and [HitlpRequester]
 * reads [endpoint] at each call. The engine is a requester only, never a surface (#100013: the surface URL and the
 * per-login surface credentials went with the relay, V97). Nothing outside the database holds any of it, and nothing
 * falls back to a property or the environment when a value is unset.
 */
@Repository
class HitlpServerSettings(private val jdbc: JdbcClient, private val secrets: SecretsKey) : HitlpEndpointSource {
    companion object {
        const val REQUESTER_AAD = "engine.hitlp.requester"
        /** The `setting` frame a change of any of these publishes (engine/README.md, "Events"). */
        const val EVENT_NAME = "engine.hitlp"
    }

    /** What `GET /settings/hitlp` shows: no secret, only whether and when each was set. */
    fun get(): HitlpServerView {
        val row = jdbc.sql("SELECT mcp_url, requester_token_set_at FROM engine.hitlp_server")
            .query { rs, _ -> rs.getString(1) to rs.getObject(2, OffsetDateTime::class.java) }.optional().orElse(null)
        return HitlpServerView(row?.first, row?.second)
    }

    /** The MCP URL (blank: null) and the token opened; null means the requester is not configured. */
    override fun endpoint(): HitlpEndpoint? =
        jdbc.sql("SELECT mcp_url, requester_token FROM engine.hitlp_server")
            .query { rs, _ -> rs.getString(1) to rs.getBytes(2) }.optional().orElse(null)
            ?.let { (url, sealed) -> url?.takeIf { it.isNotBlank() }?.let { HitlpEndpoint(it, sealed?.let { s -> secrets.open(s, REQUESTER_AAD) } ?: "") } }

    /**
     * Sets the fields given: each is a [Field] carrying its value, a null value clearing the column (the requester token's
     * key id and set time with it). The one row is made when there is none. Publishes nothing: the controller does.
     */
    fun set(mcpUrl: Field<String?> = Field.Unset, requesterToken: Field<String?> = Field.Unset) {
        jdbc.sql("INSERT INTO engine.hitlp_server (singleton) SELECT TRUE WHERE NOT EXISTS (SELECT 1 FROM engine.hitlp_server)").update()
        if (mcpUrl is Field.Set) jdbc.sql("UPDATE engine.hitlp_server SET mcp_url = CAST(:v AS TEXT)").param("v", mcpUrl.value).update()
        if (requesterToken is Field.Set) {
            val token = requesterToken.value
            if (token == null) jdbc.sql("UPDATE engine.hitlp_server SET requester_token = NULL, requester_key_id = NULL, requester_token_set_at = NULL").update()
            else jdbc.sql("UPDATE engine.hitlp_server SET requester_token = :t, requester_key_id = :k, requester_token_set_at = CURRENT_TIMESTAMP")
                .param("t", secrets.seal(token, REQUESTER_AAD)).param("k", secrets.id).update()
        }
    }

    /** A field of [set]: left as it is ([Unset]) or set to a value, null included ([Set]). */
    sealed class Field<out T> {
        object Unset : Field<Nothing>()
        data class Set<T>(val value: T) : Field<T>()
    }
}

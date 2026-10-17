package com.codefrak.engine.smith

import com.codefrak.engine.accounts.SecretsKey
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * [RunTokens] with its keys in `engine.run_token_key`, the private half sealed with `ENGINE_SECRETS_KEY` (no other
 * store): the newest unretired row signs, made on first use; [rotate] retires it and makes a new one, and a retired key
 * stays in [jwks] until every token it signed has expired. A run's revocation is a row of `engine.run_token_revoked`.
 */
@Component
class StoredRunTokens(
    template: JdbcTemplate,
    private val secrets: SecretsKey,
    @Value("\${engine.smith.k8s.active-deadline:PT24H}") private val lifetime: Duration,
    private val now: () -> Instant = Instant::now,
) : RunTokens {
    private companion object {
        val JSON = jacksonObjectMapper()
        val b64: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val unb64: Base64.Decoder = Base64.getUrlDecoder()
    }

    private val log = LoggerFactory.getLogger(StoredRunTokens::class.java)
    private val jdbc = JdbcClient.create(template)

    private data class KeyRow(val kid: String, val jwk: Map<String, Any?>, val sealed: ByteArray, val retiredAt: Instant?)

    private fun rows(): List<KeyRow> =
        jdbc.sql("SELECT kid, public_jwk, private_key, retired_at FROM engine.run_token_key ORDER BY created_at DESC")
            .query { rs, _ -> KeyRow(rs.getString(1), JSON.readValue(rs.getString(2)), rs.getBytes(3), rs.getTimestamp(4)?.toInstant()) }
            .list()

    /** The keys a verifier may still meet: the signing one and each retired one younger than a token's life. */
    private fun published(): List<KeyRow> = rows().filter { it.retiredAt == null || it.retiredAt.plus(lifetime).isAfter(now()) }

    private fun newKey(): String {
        val pair = RunTokens.keyPair()
        val pub = pair.public as ECPublicKey
        val kid = RunTokens.thumbprint(pub)
        jdbc.sql("INSERT INTO engine.run_token_key (kid, public_jwk, private_key, created_at) VALUES (:k, :j, :p, :t)")
            .param("k", kid).param("j", JSON.writeValueAsString(RunTokens.jwk(pub, kid)))
            .param("p", secrets.seal(b64.encodeToString(pair.private.encoded), kid)).param("t", Timestamp.from(now()))
            .update()
        log.info("run token key {} made", kid)
        return kid
    }

    /** The signing key, made on first use. */
    @Synchronized
    private fun signing(): Pair<String, PrivateKey> {
        val row = rows().firstOrNull { it.retiredAt == null } ?: run { newKey(); rows().first { it.retiredAt == null } }
        val der = unb64.decode(secrets.open(row.sealed, row.kid))
        return row.kid to KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    override fun mint(spec: SmithSpec, audience: String): String {
        val (kid, key) = signing()
        val at = now()
        return RunTokens.sign(key, kid, RunTokenClaims(spec.runId, spec.repo, spec.issue, spec.mode, audience, at, at.plus(lifetime)))
    }

    override fun jwks(): Map<String, Any> = mapOf("keys" to published().map { it.jwk })

    override fun verify(token: String, audience: String?): RunTokenCheck {
        val keys = published().associate { it.kid to it.jwk }
        return RunTokens.verify(token, { kid -> keys[kid]?.let(RunTokens::publicKey) }, now(), audience)
    }

    override fun introspect(token: String, audience: String?): Map<String, Any?> {
        val c = (verify(token, audience) as? RunTokenCheck.Valid)?.claims ?: return mapOf("active" to false)
        if (!live(c.runId)) return mapOf("active" to false)
        return linkedMapOf("active" to true, "iss" to RunTokens.ISSUER, "sub" to "run:${c.runId}", "jti" to c.runId, "aud" to c.audience,
            "repo" to c.repo, "issue" to c.issue, "mode" to c.mode, "scope" to RunTokens.SCOPE, "token_type" to RunTokens.TYPE,
            "iat" to c.issuedAt.epochSecond, "exp" to c.expiresAt.epochSecond)
    }

    override fun live(runId: String): Boolean {
        val open = jdbc.sql("SELECT COUNT(*) FROM engine.smith_session WHERE run_id = :r AND ended_at IS NULL")
            .param("r", runId).query(Long::class.java).single() > 0
        val revoked = jdbc.sql("SELECT COUNT(*) FROM engine.run_token_revoked WHERE run_id = :r")
            .param("r", runId).query(Long::class.java).single() > 0
        return open && !revoked
    }

    override fun revoke(runId: String) {
        jdbc.sql("DELETE FROM engine.run_token_revoked WHERE run_id = :r").param("r", runId).update()
        jdbc.sql("INSERT INTO engine.run_token_revoked (run_id, revoked_at) VALUES (:r, :t)")
            .param("r", runId).param("t", Timestamp.from(now())).update()
    }

    @Synchronized
    override fun rotate(): String {
        val at = Timestamp.from(now())
        jdbc.sql("UPDATE engine.run_token_key SET retired_at = :t WHERE retired_at IS NULL").param("t", at).update()
        jdbc.sql("DELETE FROM engine.run_token_key WHERE retired_at < :t").param("t", Timestamp.from(now().minus(lifetime))).update()
        return newKey()
    }
}

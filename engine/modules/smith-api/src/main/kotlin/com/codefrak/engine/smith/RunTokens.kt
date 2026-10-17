package com.codefrak.engine.smith

import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.time.Instant
import java.util.Base64

/** The claims of a run identity token (docs/run-identity-token.md, "Claims"). */
data class RunTokenClaims(
    val runId: String, val repo: String, val issue: Long?, val mode: String, val audience: String,
    val issuedAt: Instant, val expiresAt: Instant,
)

/** What checking a token found: [Valid] with its claims, or [Invalid] with why (never the token). */
sealed interface RunTokenCheck {
    data class Valid(val claims: RunTokenClaims) : RunTokenCheck
    data class Invalid(val reason: String) : RunTokenCheck
}

/**
 * A Smith run's identity token (#2453; engine/README.md, "Run identity tokens"; the contract is
 * docs/run-identity-token.md): an ES256 JWT, header `{alg: ES256, typ: frakworks-run+jwt, kid}`, claims `iss`
 * ([ISSUER]), `sub` (`run:<run id>`), `jti` (the run id), `aud` (the one MCP server URL it was minted for), `repo`,
 * `issue`, `mode`, `scope` ([SCOPE]), `iat` and `exp` (`iat` plus the run's longest life, `engine.smith.k8s.active-deadline`).
 * `SmithMcpServers` stamps one per http/sse server of the run as [HEADER]; nothing else carries it.
 *
 * The companion holds the token format (sign, verify, the JWK shapes), pure and offline. The keys and the revocations
 * are `StoredRunTokens`' (engine-smith); every module above programs against this. A token is live ([introspect])
 * only while it verifies, the run's `engine.smith_session` row is open (`ended_at` unset) and the run is not revoked:
 * that is the revocation at run end; an offline verifier, with [jwks] alone, gets expiry only.
 */
interface RunTokens {
    companion object {
        const val HEADER = "X-Frakworks-Run-Token"
        const val ISSUER = "frakworks-engine"
        const val TYPE = "frakworks-run+jwt"
        /** The one scope a run token has: it attests who the run is, and grants nothing (no HITLP answer, no engine call). */
        const val SCOPE = "frakworks.run.attest"
        private val JSON = jacksonObjectMapper()
        private val b64 = Base64.getUrlEncoder().withoutPadding()
        private val unb64 = Base64.getUrlDecoder()
        private const val ALG = "SHA256withECDSAinP1363Format"

        /** A fresh P-256 key pair. */
        fun keyPair(): java.security.KeyPair =
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

        /** [key] as a public JWK (`kty EC`, `crv P-256`, `x`, `y`, `kid`, `use sig`, `alg ES256`). */
        fun jwk(key: ECPublicKey, kid: String): Map<String, String> = linkedMapOf(
            "kty" to "EC", "crv" to "P-256", "kid" to kid, "use" to "sig", "alg" to "ES256",
            "x" to b64.encodeToString(fixed(key.w.affineX)), "y" to b64.encodeToString(fixed(key.w.affineY)),
        )

        /** The RFC 7638 thumbprint of [key], the kid a key gets. */
        fun thumbprint(key: ECPublicKey): String {
            val j = jwk(key, "")
            val canonical = """{"crv":"P-256","kty":"EC","x":"${j["x"]}","y":"${j["y"]}"}"""
            return b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()))
        }

        /** The public key of a P-256 [jwk]. */
        fun publicKey(jwk: Map<String, Any?>): PublicKey {
            val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
                .getParameterSpec(ECParameterSpec::class.java)
            val point = ECPoint(BigInteger(1, unb64.decode(jwk["x"] as String)), BigInteger(1, unb64.decode(jwk["y"] as String)))
            return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
        }

        private fun fixed(n: BigInteger): ByteArray {
            val raw = n.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            return ByteArray(32 - raw.size) + raw
        }

        /** The signed token for [claims], with [key] named [kid]. */
        fun sign(key: PrivateKey, kid: String, claims: RunTokenClaims): String {
            val header = b64.encodeToString(JSON.writeValueAsBytes(linkedMapOf("alg" to "ES256", "typ" to TYPE, "kid" to kid)))
            val body = b64.encodeToString(JSON.writeValueAsBytes(linkedMapOf(
                "iss" to ISSUER, "sub" to "run:${claims.runId}", "jti" to claims.runId, "aud" to claims.audience,
                "repo" to claims.repo, "issue" to claims.issue, "mode" to claims.mode, "scope" to SCOPE,
                "iat" to claims.issuedAt.epochSecond, "exp" to claims.expiresAt.epochSecond,
            )))
            val signer = Signature.getInstance(ALG).apply { initSign(key); update("$header.$body".toByteArray()) }
            return "$header.$body.${b64.encodeToString(signer.sign())}"
        }

        /** Whether [token] is shaped as a run token (its header says [TYPE]), signed or not: for refusing one where it does not belong. */
        fun looksLikeRunToken(token: String): Boolean = runCatching {
            JSON.readValue<Map<String, Any?>>(unb64.decode(token.substringBefore('.')))["typ"] == TYPE
        }.getOrDefault(false)

        /**
         * Checks [token]'s signature against [keys] (by kid), its `typ`, `iss`, `scope` and `exp` at [at], and, when
         * [audience] is given, that `aud` is exactly it. Never throws.
         */
        fun verify(token: String, keys: (String) -> PublicKey?, at: Instant, audience: String? = null): RunTokenCheck {
            val parts = token.split('.')
            if (parts.size != 3) return RunTokenCheck.Invalid("not a JWS compact token")
            return try {
                val header: Map<String, Any?> = JSON.readValue(unb64.decode(parts[0]))
                if (header["alg"] != "ES256" || header["typ"] != TYPE) return RunTokenCheck.Invalid("not a run token (alg/typ)")
                val key = (header["kid"] as? String)?.let(keys) ?: return RunTokenCheck.Invalid("unknown kid")
                val ok = Signature.getInstance(ALG).apply { initVerify(key); update("${parts[0]}.${parts[1]}".toByteArray()) }
                    .verify(unb64.decode(parts[2]))
                if (!ok) return RunTokenCheck.Invalid("bad signature")
                val c: Map<String, Any?> = JSON.readValue(unb64.decode(parts[1]))
                if (c["iss"] != ISSUER) return RunTokenCheck.Invalid("wrong issuer")
                if (c["scope"] != SCOPE) return RunTokenCheck.Invalid("wrong scope")
                val runId = c["jti"] as? String ?: return RunTokenCheck.Invalid("no jti")
                if (c["sub"] != "run:$runId") return RunTokenCheck.Invalid("sub and jti disagree")
                val exp = Instant.ofEpochSecond((c["exp"] as Number).toLong())
                if (!at.isBefore(exp)) return RunTokenCheck.Invalid("expired")
                val aud = c["aud"] as? String ?: return RunTokenCheck.Invalid("no aud")
                if (audience != null && aud != audience) return RunTokenCheck.Invalid("wrong audience")
                RunTokenCheck.Valid(RunTokenClaims(runId, c["repo"] as String, (c["issue"] as? Number)?.toLong(),
                    c["mode"] as String, aud, Instant.ofEpochSecond((c["iat"] as Number).toLong()), exp))
            } catch (e: Exception) {
                RunTokenCheck.Invalid("malformed")
            }
        }
    }

    /** A token for [spec]'s run, for the server at [audience]. Throws without `ENGINE_SECRETS_KEY`. */
    fun mint(spec: SmithSpec, audience: String): String

    /** `GET /.well-known/jwks.json`: `{keys: [...]}`, the signing key first. */
    fun jwks(): Map<String, Any>

    /** [token]'s signature, claims and expiry checked against the published keys (not its run's liveness). */
    fun verify(token: String, audience: String? = null): RunTokenCheck

    /**
     * RFC 7662's answer for [token]: `{active: false}` for a token that does not verify, whose run's session has ended
     * or never opened, or whose run was revoked; else `active: true` with the claims.
     */
    fun introspect(token: String, audience: String? = null): Map<String, Any?>

    /** Whether [runId]'s session is open and the run is not revoked. */
    fun live(runId: String): Boolean

    /** Every token of [runId] answers inactive from now on, whatever its session. Idempotent. */
    fun revoke(runId: String)

    /** Retires the signing key and makes a new one; keys retired longer than a token's life ago are deleted. The new kid. */
    fun rotate(): String
}

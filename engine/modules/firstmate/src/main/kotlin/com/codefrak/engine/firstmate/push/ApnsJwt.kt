package com.codefrak.engine.firstmate.push

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * The APNs provider token (engine/README.md, "Push notifications"): an ES256 JWT, header `{alg, kid}` and claims `{iss:
 * team id, iat}`, signed with the `.p8` key by the JDK (`SHA256withECDSAinP1363Format`, the raw r||s JWS wants). Apple
 * refuses a token older than an hour and throttles one renewed more than every 20 minutes, so one is kept [LIFETIME].
 */
class ApnsJwt(private val now: () -> Instant = Instant::now) {
    companion object {
        val LIFETIME: Duration = Duration.ofMinutes(50)
        private val url = Base64.getUrlEncoder().withoutPadding()

        /** The PKCS#8 EC private key in [pem] (Apple's `.p8` file). */
        fun privateKey(pem: String): PrivateKey {
            val der = Base64.getMimeDecoder().decode(pem.replace(Regex("-----[A-Z ]+-----"), "").trim())
            return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der))
        }

        fun sign(key: PrivateKey, keyId: String, teamId: String, issuedAt: Instant): String {
            val header = url.encodeToString("""{"alg":"ES256","kid":"$keyId"}""".toByteArray())
            val claims = url.encodeToString("""{"iss":"$teamId","iat":${issuedAt.epochSecond}}""".toByteArray())
            val signer = Signature.getInstance("SHA256withECDSAinP1363Format").apply { initSign(key); update("$header.$claims".toByteArray()) }
            return "$header.$claims.${url.encodeToString(signer.sign())}"
        }
    }

    private data class Cached(val keyId: String, val teamId: String, val p8: String, val at: Instant, val jwt: String)
    @Volatile private var cached: Cached? = null

    /** A token for [c], the cached one while it is younger than [LIFETIME] and for the same key. */
    fun token(c: ApnsCredentials): String {
        val t = now()
        cached?.takeIf { it.keyId == c.keyId && it.teamId == c.teamId && it.p8 == c.p8 && Duration.between(it.at, t) < LIFETIME }?.let { return it.jwt }
        return sign(privateKey(c.p8), c.keyId, c.teamId, t).also { cached = Cached(c.keyId, c.teamId, c.p8, t, it) }
    }
}

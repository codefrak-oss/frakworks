package com.codefrak.engine.smith

import org.junit.jupiter.api.Test
import java.security.interfaces.ECPublicKey
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A run identity token's signing and offline checks (#2453; docs/run-identity-token.md), no database. */
class RunTokenSigningTest {
    private val pair = RunTokens.keyPair()
    private val kid = RunTokens.thumbprint(pair.public as ECPublicKey)
    private val jwk = RunTokens.jwk(pair.public as ECPublicKey, kid)
    private val keys = { k: String -> if (k == kid) RunTokens.publicKey(jwk) else null }
    private val t0 = Instant.parse("2026-10-11T12:00:00Z")
    private val aud = "http://ops-server.ops.svc.cluster.local:8737/mcp"
    private val claims = RunTokenClaims("run-9", "acme/ops", 37, "work", aud, t0, t0.plusSeconds(3600))
    private val token = RunTokens.sign(pair.private, kid, claims)

    private fun reason(check: RunTokenCheck) = (check as RunTokenCheck.Invalid).reason

    @Test
    fun `a minted token verifies with the published JWK and carries the run, repo and issue`() {
        val ok = assertIs<RunTokenCheck.Valid>(RunTokens.verify(token, keys, t0.plusSeconds(10), aud))
        assertEquals(claims, ok.claims)
        assertTrue(RunTokens.looksLikeRunToken(token))
        assertFalse(RunTokens.looksLikeRunToken("frk_0123456789"))
    }

    @Test
    fun `a tampered, other-key, other-audience or expired token fails`() {
        val parts = token.split('.')
        val forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
            String(Base64.getUrlDecoder().decode(parts[1])).replace("run-9", "run-8").toByteArray())
        assertEquals("bad signature", reason(RunTokens.verify("${parts[0]}.$forged.${parts[2]}", keys, t0, aud)))

        val other = RunTokens.keyPair()
        val signedElsewhere = RunTokens.sign(other.private, kid, claims)
        assertEquals("bad signature", reason(RunTokens.verify(signedElsewhere, keys, t0, aud)))
        assertEquals("unknown kid", reason(RunTokens.verify(RunTokens.sign(other.private, "nope", claims), keys, t0, aud)))

        assertEquals("wrong audience", reason(RunTokens.verify(token, keys, t0, "https://other-repo.example.com/mcp")))
        assertEquals("expired", reason(RunTokens.verify(token, keys, t0.plusSeconds(3600), aud)))
        assertEquals("not a JWS compact token", reason(RunTokens.verify("garbage", keys, t0)))
    }

    @Test
    fun `a JWK round-trips to the same public key, and the kid is its thumbprint`() {
        assertEquals(pair.public, RunTokens.publicKey(jwk))
        assertEquals(setOf("kty", "crv", "kid", "use", "alg", "x", "y"), jwk.keys)
        assertEquals(43, jwk["x"]!!.length)
    }
}

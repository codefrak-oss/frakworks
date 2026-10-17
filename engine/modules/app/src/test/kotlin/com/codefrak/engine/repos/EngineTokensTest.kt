package com.codefrak.engine.repos

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `engine.engine_token` (V70) through [EngineTokens], against H2 with the same migrations Postgres runs. Every token is minted here. */
@SpringBootTest
class EngineTokensTest(@Autowired val tokens: EngineTokens, @Autowired val jdbc: JdbcClient) {
    @BeforeEach @AfterEach fun none() { jdbc.sql("DELETE FROM engine.engine_token").update() }

    private val base64url = Regex("[A-Za-z0-9_-]{43}")

    @Test
    fun `a token is frk_ and 43 base64url characters, two differ, and the row keeps its hex SHA-256 and 8-character prefix, never the token`() {
        val a = tokens.mint("alice", "laptop", null, "alice")
        val b = tokens.mint("alice", "desk", 30, "alice")
        assertTrue(a.token.startsWith("frk_"), a.token.take(4))
        assertTrue(base64url.matches(a.token.removePrefix("frk_")), "43 base64url characters after frk_")
        assertNotEquals(a.token, b.token)
        assertEquals(a.token.substring(4, 12), a.row.prefix)
        val stored = jdbc.sql("SELECT hash FROM engine.engine_token WHERE id = :id").param("id", a.row.id).query(String::class.java).single()
        assertEquals(EngineTokens.hash(a.token), stored)
        assertEquals(64, stored.length)
        val everything = jdbc.sql("SELECT id, login, name, prefix, hash, created_by FROM engine.engine_token").query().listOfRows().toString()
        assertFalse(a.token in everything, "the token itself is nowhere in the table")
        assertFalse(a.token in a.toString(), "MintedToken's toString masks the token")
        assertNull(a.row.expiresAt); assertNotNull(b.row.expiresAt)
        assertEquals("alice", a.row.createdBy)
    }

    @Test
    fun `resolve finds an active token, and answers no such token with the prefix for an unknown, revoked or expired one`() {
        val a = tokens.mint("alice", "laptop", null, "alice")
        assertEquals(TokenResolution.Ok(a.row), tokens.resolve(a.token))
        val unknown = tokens.resolve("frk_notatoken-at-all")
        assertEquals(TokenResolution.NoSuchToken("notatoke"), unknown)
        assertEquals(TokenResolution.NoSuchToken("ab??????"), tokens.resolve("frk_ab"))

        assertEquals(true, tokens.revoke(a.row.id))
        assertEquals(false, tokens.revoke(a.row.id), "a second revoke changes nothing")
        assertNull(tokens.revoke("no-such-id"))
        assertIs<TokenResolution.NoSuchToken>(tokens.resolve(a.token))
        assertNotNull(tokens.get(a.row.id)!!.revokedAt)

        val e = tokens.mint("alice", "short", 1, "alice")
        jdbc.sql("UPDATE engine.engine_token SET expires_at = :t WHERE id = :id").param("t", OffsetDateTime.now().minusMinutes(1)).param("id", e.row.id).update()
        assertEquals(TokenResolution.NoSuchToken(e.row.prefix), tokens.resolve(e.token))
    }

    @Test
    fun `touch bumps last_used_at once, then not again within a minute, and anyActive sees unrevoked rows only`() {
        assertFalse(tokens.anyActive())
        val a = tokens.mint("alice", "laptop", null, "alice")
        assertTrue(tokens.anyActive())
        assertNull(tokens.get(a.row.id)!!.lastUsedAt)
        assertTrue(tokens.touch(a.row.id))
        val first = tokens.get(a.row.id)!!.lastUsedAt
        assertNotNull(first)
        assertFalse(tokens.touch(a.row.id), "within a minute it is not written again")
        assertEquals(first, tokens.get(a.row.id)!!.lastUsedAt)
        jdbc.sql("UPDATE engine.engine_token SET last_used_at = :t WHERE id = :id").param("t", OffsetDateTime.now().minusMinutes(2)).param("id", a.row.id).update()
        assertTrue(tokens.touch(a.row.id), "after a minute it is")

        assertEquals(listOf(a.row.id), tokens.list().map { it.id })
        tokens.revoke(a.row.id)
        assertFalse(tokens.anyActive())
        assertEquals(1, tokens.list().size, "a revoked row is still listed")
    }
}

package com.codefrak.engine.hitlp

import com.codefrak.engine.hitlp.HitlpServerSettings.Field
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [HitlpServerSettings] over the engine's tables (V85, #2500): the one row round-trips, the token is sealed on the row
 * and opened by [HitlpServerSettings.endpoint], a cleared field is null. The shared stub's row is put back after, for
 * the context's other tests.
 */
@SpringBootTest
class HitlpServerSettingsTest {
    @Autowired lateinit var settings: HitlpServerSettings
    @Autowired lateinit var seeder: HitlpStubSeeder
    @Autowired lateinit var jdbc: JdbcClient

    @AfterEach
    fun restore() {
        seeder.seed()
    }

    @Test
    fun `the row round-trips, the token is sealed and opened, and a cleared field is null`() {
        settings.set(Field.Set("https://hitlp.example/mcp"), Field.Set("tok-1"))
        val view = settings.get()
        assertEquals("https://hitlp.example/mcp", view.mcpUrl)
        assertNotNull(view.requesterTokenSetAt)
        assertEquals(HitlpEndpoint("https://hitlp.example/mcp", "tok-1"), settings.endpoint())
        val sealed = jdbc.sql("SELECT requester_token, requester_key_id FROM engine.hitlp_server").query { rs, _ -> rs.getBytes(1) to rs.getString(2) }.single()
        assertFalse("tok-1".toByteArray().contentEquals(sealed.first), "sealed, not the plaintext")
        assertFalse(String(sealed.first, Charsets.ISO_8859_1).contains("tok-1"))
        assertNotNull(sealed.second, "the key id names the key")

        settings.set(requesterToken = Field.Set(null))
        assertEquals("https://hitlp.example/mcp", settings.get().mcpUrl, "a field left out stays")
        assertNull(settings.get().requesterTokenSetAt)
        assertEquals(HitlpEndpoint("https://hitlp.example/mcp", ""), settings.endpoint(), "a URL with no token: the endpoint, an empty token")

        settings.set(mcpUrl = Field.Set(null))
        assertNull(settings.endpoint(), "no URL: not configured")
        settings.set(mcpUrl = Field.Set("  "))
        assertNull(settings.endpoint(), "a blank URL: not configured")
        assertEquals(1L, jdbc.sql("SELECT COUNT(*) FROM engine.hitlp_server").query(Long::class.java).single(), "one row, ever")
    }
}

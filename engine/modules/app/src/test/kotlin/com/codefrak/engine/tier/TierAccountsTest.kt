package com.codefrak.engine.tier

import com.codefrak.engine.accounts.AiAccounts
import com.codefrak.engine.github.ReadFakes
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** `GET /tiers/accounts`: each tier's account by peek, which never moves the rotation nor shows a credential. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ReadFakes::class)
class TierAccountsTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var accounts: AiAccounts
    @Autowired lateinit var jdbc: JdbcClient

    private val json = jacksonObjectMapper()
    private val names = listOf("ta-a", "ta-b")

    private fun lastUsed() = jdbc.sql("SELECT name, last_used_at FROM engine.ai_account ORDER BY name")
        .query { rs, _ -> rs.getString(1) to rs.getObject(2)?.toString() }.list()

    private fun body(): String = mvc.get("/tiers/accounts").andExpect { status { isOk() } }.andReturn().response.contentAsString

    @Test
    fun `twice over, one row per tier in order, peeking without moving the rotation or showing a credential`() {
        names.forEach { accounts.add(it, "anthropic"); accounts.setCredential(it, "t-$it") }
        try {
            val first = accounts.peek("anthropic")
            val before = lastUsed()
            val one = body()
            val two = body()
            assertEquals(before, lastUsed(), "no account's last_used_at moved")
            assertEquals(first, accounts.peek("anthropic"), "the rotation is where it was")
            assertEquals(one, two)
            assertFalse(one.contains("\"credential\"") || one.contains("\"token\""), one)
            val rows: List<Map<String, Any?>> = json.readValue(one)
            assertEquals((1..10).toList(), rows.map { it["tier"] })
            rows.forEach {
                assertEquals(setOf("tier", "model", "effort", "account", "blockedUntil"), it.keys)
                assertEquals(accounts.peek("anthropic", it["model"] as String), it["account"])
                if (it["account"] != null) assertNull(it["blockedUntil"])
            }
        } finally { names.forEach { accounts.remove(it) } }
    }
}

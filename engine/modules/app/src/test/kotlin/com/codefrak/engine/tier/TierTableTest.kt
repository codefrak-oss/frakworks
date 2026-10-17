package com.codefrak.engine.tier

import com.codefrak.engine.accounts.ClaudeModels
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** No Spring context: TierTable takes only a JdbcClient, over a fresh H2 migrated by Flyway (SeededTiers). */
class TierTableTest {
    private val table = SeededTiers.table()

    /** bots/tiers.toml's [claude] table and security_tier as main had them when V42 replaced the file (#1099). */
    @Test
    fun `V42 seeds the ten tiers bots tiers toml had, with full ids, and security tier 5`() {
        assertEquals(listOf(
            TierRow(1, "claude-haiku-4-5-20251001", "low"),
            TierRow(2, "claude-sonnet-5", "low"),
            TierRow(3, "claude-sonnet-5", "medium"),
            TierRow(4, "claude-opus-5-5", "low"),
            TierRow(5, "claude-opus-5-5", "medium"),
            TierRow(6, "claude-opus-5-5", "medium"),
            TierRow(7, "claude-opus-5-5", "medium"),
            TierRow(8, "claude-fable-5-1", "low"),
            TierRow(9, "claude-fable-5-1", "medium"),
            TierRow(10, "claude-fable-5-1", "high"),
        ), table.all())
        assertEquals(Resolved("claude-sonnet-5", "medium"), table.resolve(3))
        assertEquals(5, table.securityTier())
        assertEquals(Resolved("claude-opus-5-5", "medium"), table.securityResolved())
        assertEquals(4, table.checkTier(), "V95 seeds the post-deployment check's tier")
        assertEquals(Resolved("claude-opus-5-5", "low"), table.checkResolved())
    }

    @Test
    fun `the check tier is written like the security tier, within 1-10`() {
        table.setCheckTier(2)
        assertEquals(2, table.checkTier())
        assertEquals(Resolved("claude-sonnet-5", "low"), table.checkResolved())
        assertFailsWith<TierRefused> { table.setCheckTier(11) }
        assertEquals(2, table.checkTier())
        assertEquals(5, table.securityTier(), "the security tier is its own row")
    }

    @Test
    fun `the engine refuses to start without the check tier, naming its migration`() {
        val jdbc = SeededTiers.jdbc()
        jdbc.sql("DELETE FROM engine.engine_setting WHERE name = 'check.tier'").update()
        val error = assertFailsWith<IllegalStateException> { TierTable(jdbc) }
        assertTrue("V95__check_tier.sql" in error.message.orEmpty() && "check.tier" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `a write is what the next read resolves, the model taken as a short name or a full id`() {
        assertEquals(TierRow(6, "claude-opus-5-5", "high"), table.set(6, "opus", "high"))
        assertEquals(Resolved("claude-opus-5-5", "high"), table.resolve(6))
        table.set(6, "claude-fable-5-1", "low")
        assertEquals(Resolved("claude-fable-5-1", "low"), table.resolve(6))
        table.setSecurityTier(6)
        assertEquals(6, table.securityTier())
        assertEquals(Resolved("claude-fable-5-1", "low"), table.securityResolved())
    }

    @Test
    fun `a write the map cannot take is refused with the reason and changes nothing`() {
        mapOf(
            Triple(0, "opus", "low") to "tier 0 is not 1-10",
            Triple(11, "opus", "low") to "tier 11 is not 1-10",
            Triple(4, "gpt-4", "low") to "unknown model 'gpt-4'",
            Triple(1, "haiku", "medium") to "haiku does not take effort 'medium'",
            Triple(4, "opus", "extreme") to "opus does not take effort 'extreme'",
            Triple(4, " ", "low") to "model is empty",
            Triple(4, "opus", "") to "effort is empty",
        ).forEach { (write, reason) ->
            val error = assertFailsWith<TierRefused> { table.set(write.first, write.second, write.third) }
            assertTrue(reason in error.message.orEmpty(), "${error.message} says $reason")
        }
        assertFailsWith<TierRefused> { table.set(4, null, "low") }
        assertFailsWith<TierRefused> { table.setSecurityTier(0) }
        assertFailsWith<TierRefused> { table.setSecurityTier(11) }
        assertEquals(Resolved("claude-opus-5-5", "low"), table.resolve(4))
        assertEquals(Resolved("claude-haiku-4-5-20251001", "low"), table.resolve(1))
        assertEquals(5, table.securityTier())
    }

    @Test
    fun `the engine refuses to start without all ten tiers, naming the migration`() {
        val jdbc = SeededTiers.jdbc()
        jdbc.sql("DELETE FROM engine.tier WHERE tier = 7").update()
        val error = assertFailsWith<IllegalStateException> { TierTable(jdbc) }
        assertTrue("V42__tier.sql" in error.message.orEmpty(), error.message)
        assertTrue("missing: 7" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `the engine refuses to start without the security tier, naming the migration`() {
        val jdbc = SeededTiers.jdbc()
        jdbc.sql("DELETE FROM engine.engine_setting WHERE name = 'security.tier'").update()
        val error = assertFailsWith<IllegalStateException> { TierTable(jdbc) }
        assertTrue("V42__tier.sql" in error.message.orEmpty() && "security.tier" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `shortName takes a short name as-is, maps a full id back to it, and refuses an unknown model`() {
        assertEquals("opus", ClaudeModels.shortName("opus"))
        assertEquals("opus", ClaudeModels.shortName("claude-opus-5-5"))
        assertEquals(null, ClaudeModels.shortName("gpt-4"))
    }

    @Test
    fun `intFrom reads an int long short or byte in Int range, else null`() {
        assertEquals(5, intFrom(mapOf("tier" to 5), "tier"))
        assertEquals(5, intFrom(mapOf("tier" to 5L), "tier"))
        assertEquals(5, intFrom(mapOf("tier" to 5.toShort()), "tier"))
        assertEquals(5, intFrom(mapOf("tier" to 5.toByte()), "tier"))
        assertEquals(null, intFrom(mapOf("tier" to Long.MAX_VALUE), "tier"))
        assertEquals(null, intFrom(mapOf("tier" to "5"), "tier"))
        assertEquals(null, intFrom(mapOf("other" to 5), "tier"))
    }

    @Test
    fun `the table refuses a tier outside 1-10 and a second row for a tier`() {
        val jdbc = SeededTiers.jdbc()
        assertFailsWith<org.springframework.dao.DataAccessException> {
            jdbc.sql("INSERT INTO engine.tier (tier, model, effort) VALUES (11, 'opus', 'low')").update()
        }
        assertFailsWith<org.springframework.dao.DataAccessException> {
            jdbc.sql("INSERT INTO engine.tier (tier, model, effort) VALUES (3, 'opus', 'low')").update()
        }
    }
}

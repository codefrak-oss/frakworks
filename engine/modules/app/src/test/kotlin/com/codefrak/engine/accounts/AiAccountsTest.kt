package com.codefrak.engine.accounts

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.web.server.ResponseStatusException
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Against H2 with Flyway's migrations applied, the same scripts Postgres runs. */
@SpringBootTest
class AiAccountsTest(@Autowired val accounts: AiAccounts, @Autowired val jdbc: JdbcClient, @Autowired val secrets: SecretsKey,
                     @Autowired val api: AiAccountController, @Autowired val verifier: FakeVerifier, @Autowired val usages: AccountUsages,
                     @Autowired val events: com.codefrak.engine.events.EngineEvents, @Autowired val credit: ApiCreditRefresh) {
    @TestConfiguration
    class Fakes { @Bean @Primary fun verifier() = FakeVerifier() }

    @Test
    fun `adds lists and removes accounts`() {
        assertTrue(accounts.add("anthropic-1", "anthropic"))
        assertTrue(accounts.add("openai-1", "openai"))
        assertFalse(accounts.add("anthropic-1", "anthropic"))
        assertEquals(emptyList(), api.list().single { it.name == "anthropic-1" }.running, "no run is live on it")
        assertEquals(listOf("anthropic-1" to true, "openai-1" to true), accounts.list().map { it.name to it.enabled })
        assertTrue(accounts.setEnabled("openai-1", false))
        assertEquals(false, accounts.get("openai-1")!!.enabled)
        assertTrue(accounts.remove("anthropic-1"))
        assertTrue(accounts.remove("openai-1"))
        assertFalse(accounts.remove("openai-1"))
        assertEquals(emptyList(), accounts.list())
    }

    @Test
    fun `the table refuses an unknown provider`() {
        assertFailsWith<DataIntegrityViolationException> { accounts.add("x", "gemini") }
    }

    @Test
    fun `a credential is sealed at rest and opens only for its own row`() {
        accounts.add("anthropic-2", "anthropic")
        assertFalse(accounts.get("anthropic-2")!!.hasCredential)
        assertNull(accounts.credential("anthropic-2"))
        assertFalse(accounts.setCredential("nobody", "sk-x"))

        assertTrue(accounts.setCredential("anthropic-2", "sk-ant-oat01-secret"))
        assertTrue(accounts.get("anthropic-2")!!.hasCredential)
        assertEquals("sk-ant-oat01-secret", accounts.credential("anthropic-2"))

        val stored = jdbc.sql("SELECT credential FROM engine.ai_account WHERE name = 'anthropic-2'").query { rs, _ -> rs.getBytes(1) }.single()
        assertFalse(String(stored, Charsets.ISO_8859_1).contains("secret"))
        assertEquals(secrets.id, jdbc.sql("SELECT key_id FROM engine.ai_account WHERE name = 'anthropic-2'").query(String::class.java).single())

        // Moved to another row's name, the ciphertext does not open.
        assertFailsWith<javax.crypto.AEADBadTagException> { secrets.open(stored, "anthropic-3") }
        accounts.remove("anthropic-2")
    }

    @Test
    fun `the picker rotates through a provider's usable accounts`() {
        assertNull(accounts.pick("anthropic"))
        listOf("pick-a", "pick-b", "pick-c").forEach { accounts.add(it, "anthropic"); accounts.setCredential(it, "t-$it") }
        accounts.add("pick-o", "openai"); accounts.setCredential("pick-o", "t-o")
        accounts.setEnabled("pick-c", false)                      // disabled: never picked
        accounts.add("pick-d", "anthropic")                       // no credential: never picked
        assertEquals("pick-a", accounts.pick("anthropic"))        // never used, then by name
        assertEquals("pick-b", accounts.pick("anthropic"))
        assertEquals("pick-a", accounts.pick("anthropic"))        // least recently used again
        assertEquals("pick-o", accounts.pick("openai"))           // its own provider only
        assertEquals("anthropic", providerOf("claude-sonnet-5"))
        assertEquals("openai", providerOf("gpt-6-astra"))
        assertFailsWith<IllegalArgumentException> { providerOf("gemini") }
        listOf("pick-a", "pick-b", "pick-c", "pick-d", "pick-o").forEach { accounts.remove(it) }
    }

    @Test
    fun `peek names the account pick would, without moving the rotation`() {
        assertNull(accounts.peek("anthropic"))
        listOf("peek-a", "peek-b").forEach { accounts.add(it, "anthropic"); accounts.setCredential(it, "t-$it") }
        assertEquals("peek-a", accounts.peek("anthropic"))
        assertEquals("peek-a", accounts.peek("anthropic"))        // not marked picked
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM engine.ai_account WHERE name LIKE 'peek-%' AND last_used_at IS NOT NULL")
            .query(Int::class.java).single())
        assertEquals("peek-a", accounts.pick("anthropic"))
        assertEquals("peek-b", accounts.peek("anthropic"))
        listOf("peek-a", "peek-b").forEach { accounts.remove(it) }
    }

    private fun meter(account: String, fiveHour: Double?, fiveStatus: String = "allowed", fiveReset: java.time.OffsetDateTime? = null,
                      sevenStatus: String = "allowed", sevenReset: java.time.OffsetDateTime? = null, sevenDay: Double? = 0.3) =
        usages.record(account, MeterReading(fiveHour, fiveStatus, fiveReset, sevenDay, sevenStatus, sevenReset, "allowed"), 200)

    private fun cleanUp(names: List<String>) {
        names.forEach { accounts.remove(it) }
        jdbc.sql("DELETE FROM engine.ai_account_usage WHERE account IN (:n)").param("n", names).update()
    }

    @Test
    fun `a rejected account is skipped until its reset, then picked again`() {
        val names = listOf("rej-5h", "rej-7d", "rej-ok")
        names.forEach { accounts.add(it, "anthropic"); accounts.setCredential(it, "t-$it") }
        try {
            val now = java.time.OffsetDateTime.now().withNano(0)
            meter("rej-5h", 1.0, "rejected", now.plusHours(2))
            meter("rej-7d", 0.0, sevenStatus = "rejected", sevenReset = now.plusDays(3))
            meter("rej-ok", 0.9)
            assertEquals("rej-ok", accounts.pick("anthropic"))
            assertEquals("rej-ok", accounts.pick("anthropic"), "the only usable one")
            assertEquals(now.plusHours(2).toInstant(), accounts.blockedUntil("anthropic")!!.toInstant(), "the earliest reset")

            meter("rej-ok", 1.0, "rejected", now.plusHours(1))
            assertNull(accounts.pick("anthropic"))
            assertEquals(now.plusHours(1).toInstant(), accounts.blockedUntil("anthropic")!!.toInstant())

            // Resets passed (no one re-enabled anything): back in.
            meter("rej-5h", 1.0, "rejected", now.minusMinutes(1))
            meter("rej-7d", 0.0, sevenStatus = "rejected", sevenReset = now.minusMinutes(1))
            assertEquals("rej-5h", accounts.pick("anthropic"), "its 5-hour reset passed: 1.0 counts as 0; never picked, name first")
            assertEquals("rej-7d", accounts.pick("anthropic"), "0 too, least recently picked")
            accounts.setEnabled("rej-7d", false); accounts.setEnabled("rej-ok", false)
            assertEquals("rej-5h", accounts.pick("anthropic"), "its reset passed: usable again")
        } finally { cleanUp(names) }
    }

    @Test
    fun `a recorded limit rejection is skipped until its reset`() {
        val names = listOf("lim-a", "lim-b")
        names.forEach { accounts.add(it, "anthropic"); accounts.setCredential(it, "t-$it") }
        try {
            usages.markLimitRejected("lim-a", java.time.OffsetDateTime.now().plusHours(3))
            assertEquals("lim-b", accounts.pick("anthropic"))
            assertEquals("lim-b", accounts.pick("anthropic"))
            jdbc.sql("UPDATE engine.ai_account_usage SET limit_rejected_reset = :r WHERE account = 'lim-a'")
                .param("r", java.time.OffsetDateTime.now().minusMinutes(1)).update()
            assertEquals("lim-a", accounts.pick("anthropic"))
            assertNull(accounts.blockedUntil("anthropic"))
        } finally { cleanUp(names) }
    }

    @Test
    fun `usable accounts are ordered by 5-hour utilization, no reading as 0, then last use, then name`() {
        val names = listOf("ord-a", "ord-b", "ord-c", "ord-d")
        names.forEach { accounts.add(it, "anthropic"); accounts.setCredential(it, "t-$it") }
        try {
            meter("ord-a", 0.5); meter("ord-b", 0.2); meter("ord-c", null)   // ord-d: no reading at all
            assertEquals("ord-c", accounts.pick("anthropic"), "null utilization is 0; never picked; name before ord-d")
            assertEquals("ord-d", accounts.pick("anthropic"), "no reading is 0 too, never picked")
            assertEquals("ord-c", accounts.pick("anthropic"), "least recently picked of the zeros")
            meter("ord-c", 0.9); meter("ord-d", 0.9)
            assertEquals("ord-b", accounts.pick("anthropic"))
            assertEquals("ord-b", accounts.pick("anthropic"), "still the lowest, though just picked")
        } finally { cleanUp(names) }
    }

    @Test
    fun `a 5-hour reading whose reset has passed orders as 0`() {
        val names = listOf("stale-a", "stale-b")
        names.forEach { accounts.add(it, "anthropic"); accounts.setCredential(it, "t-$it") }
        try {
            meter("stale-a", 1.01, "rejected", java.time.OffsetDateTime.now().minusMinutes(5))
            meter("stale-b", 0.5, "allowed", java.time.OffsetDateTime.now().plusHours(1))
            assertEquals("stale-a", accounts.pick("anthropic"))
        } finally { cleanUp(names) }
    }

    @Test
    fun `a 5-hour reading whose reset is ahead orders as stored`() {
        val names = listOf("live-a", "live-b")
        names.forEach { accounts.add(it, "anthropic"); accounts.setCredential(it, "t-$it") }
        try {
            meter("live-a", 0.9, "allowed", java.time.OffsetDateTime.now().plusMinutes(5))
            meter("live-b", 0.5, "allowed", java.time.OffsetDateTime.now().plusHours(1))
            assertEquals("live-b", accounts.pick("anthropic"))
        } finally { cleanUp(names) }
    }

    // ---- the weekly rule: an account whose 7-day budget would expire unused goes first ----

    /** `names` as usable anthropic accounts while `body` runs; the other anthropic accounts are disabled meanwhile. */
    private fun withOnly(names: List<String>, body: (java.time.OffsetDateTime) -> Unit) {
        val others = accounts.list().filter { it.enabled && it.provider == "anthropic" }.map { it.name }
        others.forEach { accounts.setEnabled(it, false) }
        names.forEach { accounts.add(it, "anthropic"); accounts.setCredential(it, "t-$it") }
        try { body(java.time.OffsetDateTime.now().withNano(0)) } finally {
            cleanUp(names)
            others.forEach { accounts.setEnabled(it, true) }
        }
    }

    @Test
    fun `the summarizer's key is an enabled anthropic account's API key, never its OAuth token, and moves no rotation`() = withOnly(listOf("ak-a", "ak-b")) {
        assertNull(accounts.peekApiKey(), "no account has an API key: no fallback to the OAuth token")
        accounts.storeKey("ak-b", ApiKeyKind.API, "sk-ant-api03-b")
        val key = assertNotNull(accounts.peekApiKey())
        assertEquals(AccountKey("ak-b", "sk-ant-api03-b"), key)
        assertFalse("sk-ant" in key.toString(), "the key stays out of its toString")
        assertEquals(2, jdbc.sql("SELECT COUNT(*) FROM engine.ai_account WHERE name IN ('ak-a', 'ak-b') AND last_used_at IS NULL")
            .query(Int::class.java).single(), "never marked used")
        accounts.setEnabled("ak-b", false)
        assertNull(accounts.peekApiKey(), "a disabled account's key is not used")
    }

    @Test
    fun `an account whose weekly budget would expire unused is picked first for Fable`() = withOnly(listOf("wk-b", "wk-z")) { now ->
        meter("wk-z", 0.2, sevenDay = 0.70, sevenReset = now.plusHours(12))   // pace 0.3 × 168 / 12 = 4.2: expiring
        meter("wk-b", 0.2, sevenDay = 0.3, sevenReset = now.plusDays(6))     // pace 0.7 × 168 / 144 = 0.82: not
        assertEquals("wk-z", accounts.pick("anthropic", "claude-fable-5-1"), "its budget expires unused; wk-b's is ahead of an even pace")
        assertEquals("wk-z", accounts.pick("anthropic", "claude-fable-5-1"), "still it, though just picked")
    }

    @Test
    fun `accounts equally far from expiring fall back to the 5-hour order`() = withOnly(listOf("eq-a", "eq-b", "eq-c")) { now ->
        val reset = now.plusHours(12)
        meter("eq-a", 0.6, sevenDay = 0.7, sevenReset = reset)
        meter("eq-b", 0.2, sevenDay = 0.7, sevenReset = reset)
        meter("eq-c", 0.2, sevenDay = 0.7, sevenReset = reset)
        assertEquals("eq-b", accounts.pick("anthropic"), "lowest 5-hour, never picked, then name")
        assertEquals("eq-c", accounts.pick("anthropic"), "equal 5-hour: never picked first")
        assertEquals("eq-b", accounts.pick("anthropic"), "then least recently picked")
        meter("eq-b", 0.1, sevenDay = 0.7, sevenReset = reset)
        assertEquals("eq-b", accounts.pick("anthropic"), "the lowest 5-hour, though just picked")

        // Neither expiring, with different weekly readings: the 5-hour order alone.
        meter("eq-a", 0.4, sevenDay = 0.3, sevenReset = now.plusDays(6))     // pace 0.82
        meter("eq-b", 0.9, sevenDay = 0.7, sevenReset = reset)
        meter("eq-c", 0.3, sevenDay = 0.7, sevenReset = now.plusDays(3))     // pace 0.7
        accounts.setEnabled("eq-b", false)
        assertEquals("eq-c", accounts.pick("anthropic"))
        assertEquals("eq-c", accounts.pick("anthropic"))
        meter("eq-c", 0.5, sevenDay = 0.7, sevenReset = now.plusDays(3))
        assertEquals("eq-a", accounts.pick("anthropic"))
    }

    /** `other`'s reading ranks it as not expiring: it loses to an expiring account at 5-hour 0.9 and beats a non-expiring one at 0.5. */
    private fun assertNotExpiring(other: String, now: java.time.OffsetDateTime) {
        meter("ne-exp", 0.9, sevenDay = 0.7, sevenReset = now.plusHours(12))
        assertEquals("ne-exp", accounts.peek("anthropic", "claude-fable-5-1"), "the expiring one wins over $other for Fable")
        accounts.setEnabled("ne-exp", false)
        meter("ne-slow", 0.5, sevenDay = 0.3, sevenReset = now.plusDays(6))
        assertEquals(other, accounts.peek("anthropic", "claude-fable-5-1"), "$other is ordered by the 5-hour rule")
    }

    @Test
    fun `an account with no usage row ranks as not expiring`() = withOnly(listOf("ne-exp", "ne-slow", "ne-none")) { now ->
        assertNotExpiring("ne-none", now)
    }

    @Test
    fun `a 7-day reading with no utilization ranks as not expiring`() = withOnly(listOf("ne-exp", "ne-slow", "ne-null")) { now ->
        meter("ne-null", 0.2, sevenDay = null, sevenReset = now.plusHours(12))
        assertNotExpiring("ne-null", now)
    }

    @Test
    fun `a 7-day reading whose reset has passed ranks as not expiring`() = withOnly(listOf("ne-exp", "ne-slow", "ne-past")) { now ->
        meter("ne-past", 0.2, sevenDay = 0.1, sevenReset = now.minusMinutes(1))
        assertNotExpiring("ne-past", now)
    }

    // ---- the 5-hour saturation rule: at or above FIVE_HOUR_SATURATED goes after every account below it ----

    @Test
    fun `an expiring account at the 5-hour saturation loses to a non-expiring one below it`() = withOnly(listOf("sat-exp", "sat-fresh")) { now ->
        meter("sat-exp", 0.95, fiveReset = now.plusHours(2), sevenDay = 0.7, sevenReset = now.plusHours(12))
        meter("sat-fresh", 0.5, fiveReset = now.plusHours(2), sevenDay = 0.3, sevenReset = now.plusDays(6))
        assertEquals("sat-fresh", accounts.peek("anthropic"))
        assertEquals("sat-fresh", accounts.pick("anthropic"))
    }

    @Test
    fun `an expiring account just below the 5-hour saturation still wins for Fable`() = withOnly(listOf("sat-exp", "sat-fresh")) { now ->
        meter("sat-exp", 0.94, fiveReset = now.plusHours(2), sevenDay = 0.7, sevenReset = now.plusHours(12))
        meter("sat-fresh", 0.5, fiveReset = now.plusHours(2), sevenDay = 0.3, sevenReset = now.plusDays(6))
        assertEquals("sat-exp", accounts.pick("anthropic", "claude-fable-5-1"))
    }

    @Test
    fun `among saturated accounts the expiring one wins for Fable`() = withOnly(listOf("sat-exp", "sat-full")) { now ->
        meter("sat-exp", 0.99, fiveReset = now.plusHours(2), sevenDay = 0.7, sevenReset = now.plusHours(12))
        meter("sat-full", 0.95, fiveReset = now.plusHours(2), sevenDay = 0.3, sevenReset = now.plusDays(6))
        assertEquals("sat-exp", accounts.pick("anthropic", "claude-fable-5-1"))
    }

    @Test
    fun `a saturated account that is the only usable one is picked`() = withOnly(listOf("sat-only")) { now ->
        meter("sat-only", 0.99, fiveReset = now.plusHours(2))
        assertEquals("sat-only", accounts.pick("anthropic"))
    }

    @Test
    fun `a saturated reading whose 5-hour reset has passed ranks as 0`() = withOnly(listOf("sat-past", "sat-half")) { now ->
        meter("sat-past", 0.99, fiveReset = now.minusMinutes(1))
        meter("sat-half", 0.5, fiveReset = now.plusHours(2))
        assertEquals("sat-past", accounts.pick("anthropic"))
    }

    @Test
    fun `an expiring account is still skipped by the gates`() = withOnly(listOf("gx-rej", "gx-off", "gx-fab", "gx-ok")) { now ->
        val soon = now.plusHours(12)
        meter("gx-rej", 1.0, "rejected", now.plusHours(2), sevenDay = 0.7, sevenReset = soon)
        meter("gx-off", 0.0, sevenDay = 0.7, sevenReset = soon); accounts.setEnabled("gx-off", false)
        usages.record("gx-fab", MeterReading(0.0, "allowed", null, 0.7, "allowed", soon, "allowed"), 200)
        usages.markFableReached("gx-fab")
        meter("gx-ok", 0.9, sevenDay = 0.3, sevenReset = now.plusDays(6))
        assertEquals("gx-ok", accounts.pick("anthropic", "claude-fable-5-1"), "rejected, disabled and Fable-limited are passed over")
        assertEquals("gx-fab", accounts.pick("anthropic", "claude-opus-5-5"), "the Fable limit binds Fable only")
        accounts.setEnabled("gx-ok", false)
        assertNull(accounts.pick("anthropic", "claude-fable-5-1"), "only gated expiring accounts are left")
    }

    @Test
    fun `an account just behind an even weekly pace beats a lower 5-hour one for Fable`() = withOnly(listOf("ev-behind", "ev-fresh")) { now ->
        meter("ev-behind", 0.5, fiveReset = now.plusHours(2), sevenDay = 0.69, sevenReset = now.plusHours(50))   // 0.31 × 168 / 50 ≈ 1.04
        meter("ev-fresh", 0.1, fiveReset = now.plusHours(2), sevenDay = 0.3, sevenReset = now.plusDays(6))     // 0.82
        assertEquals("ev-behind", accounts.pick("anthropic", "claude-fable-5-1"))
    }

    @Test
    fun `a non-Fable pick prefers a Fable-limited account when paces tie, a Fable pick still skips it`() =
        withOnly(listOf("ft-a", "ft-limited")) { now ->
            val reset = now.plusHours(50)
            meter("ft-a", 0.1, fiveReset = now.plusHours(2), sevenDay = 0.69, sevenReset = reset)
            meter("ft-limited", 0.5, fiveReset = now.plusHours(2), sevenDay = 0.69, sevenReset = reset)
            usages.markFableReached("ft-limited")
            assertEquals("ft-limited", accounts.peek("anthropic", "claude-opus-5-5"), "its Fable limit is reached: it takes the Opus work")
            assertEquals("ft-limited", accounts.peek("anthropic", null))
            assertEquals("ft-a", accounts.peek("anthropic", "claude-fable-5-1"))
            meter("ft-a", 0.1, fiveReset = now.plusHours(2), sevenDay = 0.5, sevenReset = reset)   // pace 1.68: more behind
            assertEquals("ft-limited", accounts.peek("anthropic", "claude-opus-5-5"), "ft-a still has Fable headroom: kept for Fable")
            assertEquals("ft-a", accounts.peek("anthropic", "claude-fable-5-1"), "the expiring pace ranks first for Fable")
        }

    @Test
    fun `peek orders by the weekly rule as pick does`() = withOnly(listOf("pk-b", "pk-z")) { now ->
        meter("pk-z", 0.2, sevenDay = 0.70, sevenReset = now.plusHours(12))
        meter("pk-b", 0.2, sevenDay = 0.3, sevenReset = now.plusDays(6))
        assertEquals("pk-b", accounts.peek("anthropic", "claude-opus-5-5"), "Opus goes elsewhere: pk-z is kept for Fable")
        assertEquals("pk-z", accounts.peek("anthropic", "claude-fable-5-1"))
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM engine.ai_account WHERE name LIKE 'pk-%' AND last_used_at IS NOT NULL")
            .query(Int::class.java).single())
        assertEquals("pk-z", accounts.pick("anthropic", "claude-fable-5-1"))
    }

    // ---- an expiring account with Fable headroom is kept for Fable: other models go elsewhere first ----

    @Test
    fun `a non-Fable pick prefers a non-expiring account over an expiring one with Fable headroom`() =
        withOnly(listOf("kf-exp", "kf-other")) { now ->
            meter("kf-exp", 0.1, sevenDay = 0.7, sevenReset = now.plusHours(12))
            meter("kf-other", 0.6, sevenDay = 0.3, sevenReset = now.plusDays(6))
            assertEquals("kf-other", accounts.pick("anthropic", "claude-opus-5-5"))
            assertEquals("kf-other", accounts.pick("anthropic", "claude-sonnet-5"))
            assertEquals("kf-other", accounts.pick("anthropic", null), "no model counts as not Fable")
        }

    @Test
    fun `a Fable pick still prefers the expiring account with Fable headroom`() = withOnly(listOf("kf-exp", "kf-other")) { now ->
        meter("kf-exp", 0.1, sevenDay = 0.7, sevenReset = now.plusHours(12))
        meter("kf-other", 0.6, sevenDay = 0.3, sevenReset = now.plusDays(6))
        assertEquals("kf-exp", accounts.pick("anthropic", "claude-fable-5-1"))
    }

    @Test
    fun `a non-Fable pick takes the expiring Fable-headroomed account when it is the only usable one`() =
        withOnly(listOf("kf-exp", "kf-other")) { now ->
            meter("kf-exp", 0.1, sevenDay = 0.7, sevenReset = now.plusHours(12))
            meter("kf-other", 0.96, fiveReset = now.plusHours(2), sevenDay = 0.3, sevenReset = now.plusDays(6))
            assertEquals("kf-exp", accounts.pick("anthropic", "claude-opus-5-5"), "the other is 5-hour saturated")
            accounts.setEnabled("kf-other", false)
            assertEquals("kf-exp", accounts.pick("anthropic", "claude-opus-5-5"), "the only usable one")
        }

    @Test
    fun `a non-Fable pick still takes an expiring account at its Fable limit`() = withOnly(listOf("kf-exp", "kf-other")) { now ->
        meter("kf-exp", 0.1, sevenDay = 0.7, sevenReset = now.plusHours(12))
        usages.markFableReached("kf-exp")
        meter("kf-other", 0.1, sevenDay = 0.3, sevenReset = now.plusDays(6))
        assertEquals("kf-exp", accounts.pick("anthropic", "claude-opus-5-5"), "nothing to keep it for")
    }

    @Test
    fun `the weekly pace is the budget left over the time left, and expiring from an even pace`() {
        val now = java.time.Instant.parse("2026-09-28T00:00:00Z")
        val at = { hours: Long -> java.time.OffsetDateTime.ofInstant(now.plusSeconds(hours * 3600), java.time.ZoneOffset.UTC) }
        assertEquals(4.2, weeklyPace(0.7, at(12), now), 1e-9)
        assertEquals(1.05, weeklyPace(0.1, at(144), now), 1e-9)
        assertEquals(1.0, expiringPace(0.5, at(84), now), 1e-9, "exactly even: expiring")
        assertEquals(0.0, expiringPace(0.5, at(85), now), "just under: not")
        assertEquals(1.05, expiringPace(0.1, at(144), now), 1e-9, "just behind an even pace: expiring")
        assertEquals(0.0, weeklyPace(1.0, at(12), now)); assertEquals(0.0, weeklyPace(1.2, at(12), now))
        assertEquals(0.0, weeklyPace(null, at(12), now)); assertEquals(0.0, weeklyPace(0.5, null, now))
        assertEquals(0.0, weeklyPace(0.5, at(0), now), "a reset at now")
        assertEquals(0.0, weeklyPace(0.5, at(-1), now))
    }

    @Test
    fun `a disabled account stays skipped after its reset`() {
        val names = listOf("dis-a")
        accounts.add("dis-a", "anthropic"); accounts.setCredential("dis-a", "t")
        try {
            accounts.setEnabled("dis-a", false)
            meter("dis-a", 1.0, "rejected", java.time.OffsetDateTime.now().minusMinutes(5))
            assertNull(accounts.pick("anthropic"))
            meter("dis-a", 1.0, "rejected", java.time.OffsetDateTime.now().plusHours(1))
            assertNull(accounts.blockedUntil("anthropic"), "a disabled account is not waiting on a reset")
        } finally { cleanUp(names) }
    }

    /** A reading whose 7-day window resets `days` from now; the Fable limit, once marked, lasts until then. */
    private fun fableReached(account: String, days: Long) {
        val reset = java.time.OffsetDateTime.now().plusDays(days)
        usages.record(account, MeterReading(0.1, "allowed", null, 0.9, "allowed", reset, "allowed"), 200)   // ahead of an even pace
        usages.markFableReached(account)
    }

    @Test
    fun `the picker skips an account at its Fable limit for Fable only`() {
        listOf("fab-a", "fab-b").forEach { accounts.add(it, "anthropic"); accounts.setCredential(it, "t-$it") }
        try {
            fableReached("fab-a", 3)
            usages.record("fab-b", MeterReading(0.1, "allowed", null, 0.5, "allowed", null, "allowed"), 200)   // same 5-hour use: rotation decides
            assertTrue(isFable("claude-fable-5-1")); assertFalse(isFable("claude-opus-5-5")); assertFalse(isFable(null))
            assertEquals("fab-b", accounts.pick("anthropic", "claude-fable-5-1"))
            assertEquals("fab-a", accounts.pick("anthropic", "claude-opus-5-5"), "not Fable: the Fable-limited one first")
            assertEquals("fab-a", accounts.pick("anthropic", "claude-sonnet-5"), "though just picked: fab-b keeps its Fable budget")
            assertEquals("fab-a", accounts.pick("anthropic", null))
            assertEquals("fab-b", accounts.pick("anthropic", "claude-fable-5-1"))
            assertEquals("fab-b", accounts.pick("anthropic", "claude-fable-5-1"), "the only one Fable can use")
            assertEquals(usages.get("fab-a")!!.fableLimitReset!!.toInstant(), accounts.fableBlockedUntil()!!.toInstant())

            fableReached("fab-b", 5)
            assertNull(accounts.pick("anthropic", "claude-fable-5-1"))
            assertEquals(usages.get("fab-a")!!.fableLimitReset!!.toInstant(), accounts.fableBlockedUntil()!!.toInstant(), "the earliest")
            assertTrue(accounts.pick("anthropic", "claude-opus-5-5") in setOf("fab-a", "fab-b"))
        } finally {
            listOf("fab-a", "fab-b").forEach { accounts.remove(it) }
            jdbc.sql("DELETE FROM engine.ai_account_usage WHERE account IN ('fab-a', 'fab-b')").update()
        }
    }

    @Test
    fun `the picker skips an account whose Fable meter is out for Fable only, until its reset`() {
        val others = accounts.list().filter { it.provider == "anthropic" && it.enabled }.map { it.name }
        others.forEach { accounts.setEnabled(it, false) }
        accounts.add("fab-meter", "anthropic"); accounts.setCredential("fab-meter", "t-meter")
        try {
            // Observed 2026-10-05: fable_utilization 1.0, fable_reset 2026-10-06T22:00Z, fable_limit unknown. The clock is
            // the database's, so the reset is placed relative to now: ahead, then behind.
            val reset = java.time.OffsetDateTime.now().plusHours(22).withNano(0)
            usages.record("fab-meter", MeterReading(0.1, "allowed", null, 0.5, "allowed", null, "allowed", 1.0, reset), 200)
            assertEquals("unknown", usages.get("fab-meter")!!.fableLimit)
            assertNull(accounts.pick("anthropic", "claude-fable-5-1"))
            assertEquals("fab-meter", accounts.pick("anthropic", "claude-opus-5-5"))
            assertEquals("fab-meter", accounts.pick("anthropic", "claude-sonnet-5"))
            assertEquals(reset.toInstant(), accounts.fableBlockedUntil()!!.toInstant(), "the meter-only case waits for fable_reset")

            jdbc.sql("UPDATE engine.ai_account_usage SET fable_reset = :r WHERE account = 'fab-meter'")
                .param("r", java.time.OffsetDateTime.now().minusHours(1)).update()
            assertEquals("fab-meter", accounts.pick("anthropic", "claude-fable-5-1"))
            assertNull(accounts.fableBlockedUntil())
        } finally {
            accounts.remove("fab-meter")
            jdbc.sql("DELETE FROM engine.ai_account_usage WHERE account = 'fab-meter'").update()
            others.forEach { accounts.setEnabled(it, true) }
        }
    }

    @Test
    fun `an account whose Fable reset has passed is picked for Fable again`() {
        accounts.add("fab-old", "anthropic"); accounts.setCredential("fab-old", "t-old")
        try {
            fableReached("fab-old", -1)   // recorded with a reset already behind: nothing has read the meter since
            assertEquals("reached", usages.get("fab-old")!!.fableLimit)
            assertEquals("fab-old", accounts.pick("anthropic", "claude-fable-5-1"))
            assertNull(accounts.fableBlockedUntil())
        } finally {
            accounts.remove("fab-old")
            jdbc.sql("DELETE FROM engine.ai_account_usage WHERE account = 'fab-old'").update()
        }
    }

    @Test
    fun `seal is randomised`() {
        val a = secrets.seal("t", "n"); val b = secrets.seal("t", "n")
        assertFalse(a.contentEquals(b))
        assertEquals("t", secrets.open(a, "n"))
        assertContentEquals(a.copyOfRange(0, 12), a.copyOfRange(0, 12))
    }

    @Test
    fun `a credential with whitespace or the wrong prefix is refused, saying where`() {
        assertNull(credentialProblem("anthropic", "sk-ant-oat01-abc"))
        assertNull(credentialProblem("openai", "sk-proj-abc"))
        assertEquals("token has whitespace at character 18 of 25 (a copy across a line wrap?); paste it as one unbroken line",
            credentialProblem("anthropic", "sk-ant-oat01-abcd   hkxwh"))
        assertTrue(credentialProblem("anthropic", "sk-ant-oat01-abc\n")!!.startsWith("token has whitespace at character 17"))
        assertTrue("not an API key" in credentialProblem("anthropic", "sk-ant-api03-abc")!!)
        assertEquals("token is empty", credentialProblem("openai", "  "))
    }

    @Test
    fun `the api refuses a bad credential and keeps the one it had`() {
        accounts.add("anthropic-v", "anthropic")
        api.setCredential("anthropic-v", SetCredential("sk-ant-oat01-good"))
        val refused = assertFailsWith<ResponseStatusException> { api.setCredential("anthropic-v", SetCredential("sk-ant-oat01-go od")) }
        assertEquals(400, refused.statusCode.value())
        assertTrue("whitespace at character 16" in refused.reason!!, refused.reason)
        assertEquals(listOf("sk-ant-oat01-good"), verifier.tried, "a malformed token never reaches the provider")

        verifier.next = Verdict(false, true, "anthropic refused it (401): invalid bearer token")
        val rejected = assertFailsWith<ResponseStatusException> { api.setCredential("anthropic-v", SetCredential("sk-ant-oat01-revoked")) }
        assertEquals(400, rejected.statusCode.value())
        assertEquals("anthropic refused it (401): invalid bearer token", rejected.reason)
        assertEquals("sk-ant-oat01-good", accounts.credential("anthropic-v"))

        verifier.next = Verdict(false, false, "could not reach anthropic: timed out")
        val unreached = assertFailsWith<ResponseStatusException> { api.setCredential("anthropic-v", SetCredential("sk-ant-oat01-new")) }
        assertEquals(502, unreached.statusCode.value())
        assertTrue("send \"verify\": false" in unreached.reason!!, unreached.reason)
        assertEquals("sk-ant-oat01-good", accounts.credential("anthropic-v"))
        api.setCredential("anthropic-v", SetCredential("sk-ant-oat01-new", verify = false))
        assertEquals("sk-ant-oat01-new", accounts.credential("anthropic-v"), "saved unverified when asked")

        verifier.next = Verdict(true, true, "anthropic accepted it")
        assertEquals(Verdict(true, true, "anthropic accepted it"), api.verify("anthropic-v").body)
        assertEquals("sk-ant-oat01-new", verifier.tried.last(), "verify tries the stored credential")
        assertEquals(404, api.verify("nobody").statusCode.value())
        assertEquals(404, api.setCredential("nobody", SetCredential("sk-ant-oat01-x")).statusCode.value())
        accounts.remove("anthropic-v") // the database is shared: other tests count accounts
    }

    // ---- jev (engine/README.md, "AI accounts"): stored, verified, toggled, never a Smith's ----

    /** Only `names` (jev accounts, enabled with a credential) are usable while `body` runs. */
    private fun withOnlyJev(names: List<String>, body: () -> Unit) {
        val others = accounts.list().filter { it.enabled }.map { it.name }
        others.forEach { accounts.setEnabled(it, false) }
        names.forEach { accounts.add(it, "jev"); accounts.setCredential(it, "jev-key-$it") }
        try { body() } finally {
            cleanUp(names)
            others.forEach { accounts.setEnabled(it, true) }
        }
    }

    @Test
    fun `a jev account is added through the api and the table, and an unknown provider is still refused`() {
        try {
            assertTrue("jev" in PROVIDERS)
            assertEquals(201, api.add(AddAccount("jev-api", "jev")).statusCode.value())
            assertEquals(200, api.add(AddAccount("jev-api", "jev")).statusCode.value(), "already there")
            assertEquals("jev", accounts.get("jev-api")!!.provider)
            assertTrue(accounts.add("jev-table", "jev"))
            assertEquals(listOf("jev-api", "jev-table"), accounts.list().filter { it.provider == "jev" }.map { it.name })
            val refused = assertFailsWith<ResponseStatusException> { api.add(AddAccount("gemini-1", "gemini")) }
            assertEquals(400, refused.statusCode.value())
            assertFailsWith<DataIntegrityViolationException> { accounts.add("gemini-2", "gemini") }
            assertNull(accounts.get("gemini-1")); assertNull(accounts.get("gemini-2"))
        } finally { listOf("jev-api", "jev-table").forEach { accounts.remove(it) } }
    }

    @Test
    fun `a codex account stores encrypted ChatGPT auth json but cannot yet run a Smith`() {
        try {
            assertTrue("codex" in PROVIDERS)
            assertEquals(201, api.add(AddAccount("codex-account", "codex", "owner@example.com")).statusCode.value())
            assertEquals("codex", accounts.get("codex-account")!!.provider)
            assertEquals("owner@example.com", accounts.get("codex-account")!!.email)
            assertFalse(accounts.get("codex-account")!!.hasCredential)
            assertFalse("codex" in SMITH_PROVIDERS, "Codex is not an OpenAI API-key account or a Smith provider")
            assertNull(accounts.pick("codex"))
            assertFailsWith<IllegalArgumentException> { credentialEnv("codex") }

            val malformed = assertFailsWith<ResponseStatusException> {
                api.setCredential("codex-account", SetCredential("oauth-state"))
            }
            assertEquals(400, malformed.statusCode.value())
            assertFalse(accounts.get("codex-account")!!.hasCredential)

            val wrongMode = """{"auth_mode":"api_key","tokens":{"access_token":"a","refresh_token":"r","id_token":"i","account_id":"acct"}}"""
            assertEquals(400, assertFailsWith<ResponseStatusException> {
                api.setCredential("codex-account", SetCredential(wrongMode))
            }.statusCode.value())
            val tooLarge = " ".repeat(CodexAuthFile.MAX_BYTES + 1)
            assertEquals(400, assertFailsWith<ResponseStatusException> {
                api.setCredential("codex-account", SetCredential(tooLarge))
            }.statusCode.value())

            val auth = """{"auth_mode":"chatgpt","tokens":{"access_token":"access","refresh_token":"refresh","id_token":"identity","account_id":"acct"}}"""
            val beforeVerify = verifier.providers.size
            assertEquals(204, api.setCredential("codex-account", SetCredential(auth)).statusCode.value())
            assertTrue(accounts.get("codex-account")!!.hasCredential)
            assertEquals(auth, accounts.credential("codex-account"), "the sealed credential round-trips without transformation")
            assertEquals(beforeVerify, verifier.providers.size, "Codex login state is shape-checked, not sent to API-key verification")

            val verify = assertFailsWith<ResponseStatusException> { api.verify("codex-account") }
            assertEquals(409, verify.statusCode.value())
        } finally { accounts.remove("codex-account") }
    }

    @Test
    fun `a jev key gets the whitespace rule and no prefix rule`() {
        assertNull(credentialProblem("jev", "anykey123"))
        assertNull(credentialProblem("jev", "ts_abc-DEF.123"))
        assertEquals("token has whitespace at character 4 of 7 (a copy across a line wrap?); paste it as one unbroken line",
            credentialProblem("jev", "abc def"))
        assertEquals("token is empty", credentialProblem("jev", ""))
    }

    @Test
    fun `no Smith provider picks a jev account, even the only usable one`() = withOnlyJev(listOf("jev-only")) {
        assertNull(accounts.pick("anthropic"))
        assertNull(accounts.pick("anthropic", "claude-fable-5-1"))
        assertNull(accounts.pick("openai"))
        assertNull(accounts.pick("jev"))
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM engine.ai_account WHERE name = 'jev-only' AND last_used_at IS NULL")
            .query(Int::class.java).single(), "never marked picked")
        assertFailsWith<IllegalArgumentException> { credentialEnv("jev") }
    }

    @Test
    fun `the engine's jev accessor opens a usable jev key, in rotation, while pick and peek still pass over it`() = withOnlyJev(listOf("jev-a", "jev-b")) {
        accounts.add("jev-off", "jev"); accounts.setCredential("jev-off", "jev-key-off"); accounts.setEnabled("jev-off", false)
        accounts.add("jev-bare", "jev")
        try {
            val first = assertNotNull(accounts.jevCredential())
            assertEquals(JevKey("jev-a", "jev-key-jev-a"), first, "never used first, then by name")
            assertFalse("jev-key" in first.toString(), "the key stays out of its toString")
            assertEquals("jev-b", accounts.jevCredential()!!.account, "the least recently used next")
            accounts.setEnabled("jev-a", false); accounts.setEnabled("jev-b", false)
            assertNull(accounts.jevCredential(), "a disabled account, or one with no credential, is never used")
            assertNull(accounts.pick("jev")); assertNull(accounts.peek("jev")); assertNull(accounts.blockedUntil("jev"))
            accounts.setEnabled("jev-a", true)
            assertNull(accounts.pick("jev")); assertNull(accounts.peek("jev")); assertNull(accounts.pick("anthropic"))
        } finally { listOf("jev-off", "jev-bare").forEach { accounts.remove(it) } }
    }

    @Test
    fun `the limit gates ignore a jev account`() = withOnlyJev(listOf("jev-gate")) {
        val later = java.time.OffsetDateTime.now().plusDays(2)
        usages.record("jev-gate", MeterReading(1.0, "rejected", later, 1.0, "rejected", later, "rejected"), 429)
        usages.markFableReached("jev-gate")
        assertNull(accounts.blockedUntil("anthropic"))
        assertNull(accounts.blockedUntil("openai"))
        assertNull(accounts.blockedUntil("jev"))
        assertNull(accounts.fableBlockedUntil())
    }

    @Test
    fun `a jev credential is checked, verified and stored like any other, and has no usage`() {
        accounts.add("jev-cred", "jev")
        try {
            val calls = verifier.tried.size
            val spaced = assertFailsWith<ResponseStatusException> { api.setCredential("jev-cred", SetCredential("jev key")) }
            assertEquals(400, spaced.statusCode.value())
            assertTrue("whitespace at character 4" in spaced.reason!!, spaced.reason)
            assertEquals(calls, verifier.tried.size, "a malformed key never reaches Jev")

            verifier.next = Verdict(false, true, "jev refused it (401): Invalid API key")
            val refused = assertFailsWith<ResponseStatusException> { api.setCredential("jev-cred", SetCredential("jev-revoked")) }
            assertEquals(400, refused.statusCode.value())
            assertEquals("jev refused it (401): Invalid API key", refused.reason)
            assertFalse(accounts.get("jev-cred")!!.hasCredential)
            assertEquals("jev", verifier.providers.last())

            assertEquals(204, api.setCredential("jev-cred", SetCredential("jev-good")).statusCode.value())
            assertTrue(accounts.get("jev-cred")!!.hasCredential)
            assertEquals("jev-good", accounts.credential("jev-cred"))

            verifier.next = Verdict(true, true, "jev accepted it")
            assertEquals(Verdict(true, true, "jev accepted it"), api.verify("jev-cred").body)
            assertEquals("jev-good" to "jev", verifier.tried.last() to verifier.providers.last())

            assertEquals(404, api.usage("jev-cred").statusCode.value(), "Jev has no balance endpoint: no usage data")
            assertTrue(api.usage().none { it.account == "jev-cred" })
        } finally { accounts.remove("jev-cred") }
    }

    /** A reply's `anthropic-ratelimit-unified-*` headers, resets as epoch seconds, as Anthropic sends them. */
    private fun unified(five: Double, fiveStatus: String, fiveReset: java.time.OffsetDateTime,
                        seven: Double, sevenStatus: String, sevenReset: java.time.OffsetDateTime, status: String): Map<String, List<String>> = mapOf(
        "5h-utilization" to "$five", "5h-status" to fiveStatus, "5h-reset" to "${fiveReset.toEpochSecond()}",
        "7d-utilization" to "$seven", "7d-status" to sevenStatus, "7d-reset" to "${sevenReset.toEpochSecond()}", "status" to status,
    ).entries.associate { (k, v) -> MeterReading.PREFIX + k to listOf(v) } + mapOf("content-type" to listOf("application/json"))

    @Test
    fun `a usage refresh records the probe's reply as the latest reading and keeps the last Fable meter`() {
        accounts.add("ref-a", "anthropic"); accounts.setCredential("ref-a", "sk-ant-oat01-ref")
        try {
            val now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).withNano(0)
            val fableReset = now.plusDays(4)
            usages.record("ref-a", MeterReading(0.1, "allowed", now.plusHours(1), 0.2, "allowed", now.plusDays(2), "allowed", 0.6, fableReset), 200)
            val before = usages.get("ref-a")!!
            verifier.probed.clear()

            // A Haiku reply: the 5h and 7d windows, no 7d_oi pair.
            verifier.probe = ProbeReply(200, unified(0.35, "allowed", now.plusHours(3), 0.5, "allowed_warning", now.plusDays(5), "allowed"))
            val refreshed = api.refreshUsage("ref-a")
            assertEquals(200, refreshed.statusCode.value())
            assertEquals(listOf("sk-ant-oat01-ref"), verifier.probed, "one probe, on the stored credential")
            val view = refreshed.body!!
            assertEquals(0.35 to "allowed", view.fiveHourUtilization to view.fiveHourStatus)
            assertEquals(now.plusHours(3).toInstant(), view.fiveHourReset!!.toInstant())
            assertEquals(0.5 to "allowed_warning", view.sevenDayUtilization to view.sevenDayStatus)
            assertEquals(now.plusDays(5).toInstant(), view.sevenDayReset!!.toInstant())
            assertEquals(200, view.httpStatus)
            assertEquals(before.requests + 1, view.requests)
            assertFalse(view.observedAt.isBefore(before.observedAt))
            assertEquals(0.6, view.fableUtilization, "no 7d_oi headers: the last Fable reading is kept")
            assertEquals(fableReset.toInstant(), view.fableReset!!.toInstant())
            val got = api.usage("ref-a").body!!
            assertEquals(listOf(view.fiveHourUtilization, view.sevenDayUtilization, view.requests, view.httpStatus, view.fableUtilization),
                listOf(got.fiveHourUtilization, got.sevenDayUtilization, got.requests, got.httpStatus, got.fableUtilization), "GET sees the same reading")
            assertEquals(view.observedAt.toInstant(), got.observedAt.toInstant())
            assertFalse(view.toString().contains("sk-ant"), "the credential is not in the answer")

            // A 429 still carries the meter: recorded, not an error.
            verifier.probe = ProbeReply(429, unified(1.0, "rejected", now.plusHours(2), 0.5, "allowed", now.plusDays(5), "rejected"))
            val limited = api.refreshUsage("ref-a").body!!
            assertEquals(429 to "rejected", limited.httpStatus to limited.status)
            assertEquals(429, jdbc.sql("SELECT http_status FROM engine.ai_account_usage WHERE account = 'ref-a'").query(Int::class.java).single())
            assertEquals(before.requests + 2, limited.requests)
        } finally { cleanUp(listOf("ref-a")) }
    }

    @Test
    fun `a usage refresh answers 409 without a meter, 404 without an account or credential, 502 without a reading, and records nothing`() {
        accounts.add("ref-b", "anthropic"); accounts.setCredential("ref-b", "sk-ant-oat01-b")
        accounts.add("ref-none", "anthropic")
        accounts.add("ref-jev", "jev"); accounts.setCredential("ref-jev", "jev-key")
        accounts.add("ref-o", "openai"); accounts.setCredential("ref-o", "sk-o")
        val names = listOf("ref-b", "ref-none", "ref-jev", "ref-o")
        try {
            val now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).withNano(0)
            meter("ref-b", 0.1, fiveReset = now.plusHours(1))
            val before = usages.get("ref-b")!!
            verifier.probed.clear()
            val rows = { jdbc.sql("SELECT COUNT(*) FROM engine.ai_account_usage WHERE account IN (:n)").param("n", names).query(Int::class.java).single() }

            for (name in listOf("ref-jev", "ref-o")) {
                val refused = assertFailsWith<ResponseStatusException> { api.refreshUsage(name) }
                assertEquals(409, refused.statusCode.value())
                assertTrue("no usage meter" in refused.reason!!, refused.reason)
            }
            assertEquals(404, api.refreshUsage("nobody").statusCode.value())
            assertEquals(404, api.refreshUsage("ref-none").statusCode.value(), "no credential to probe with")
            assertEquals(emptyList(), verifier.probed, "nothing was sent for any of them")

            verifier.probe = null
            val unreached = assertFailsWith<ResponseStatusException> { api.refreshUsage("ref-b") }
            assertEquals(502, unreached.statusCode.value())
            assertTrue("could not reach anthropic" in unreached.reason!!, unreached.reason)

            verifier.probe = ProbeReply(500, mapOf("content-type" to listOf("application/json")))
            val bare = assertFailsWith<ResponseStatusException> { api.refreshUsage("ref-b") }
            assertEquals(502, bare.statusCode.value())
            assertTrue("anthropic answered 500 with no usage meter" in bare.reason!!, bare.reason)
            listOf(unreached, bare).forEach { assertFalse(it.reason!!.contains("sk-ant"), "the credential is not in the error") }

            assertEquals(1, rows(), "only ref-b's own earlier reading")
            val after = usages.get("ref-b")!!
            assertEquals(before.requests to before.observedAt.toInstant(), after.requests to after.observedAt.toInstant())
            assertEquals(before.fiveHourUtilization, after.fiveHourUtilization)
        } finally { verifier.probe = ProbeReply(200, emptyMap()); cleanUp(names) }
    }

    @Test
    fun `first mate's pick hands out the picker's account and its token, never to a Smith`() {
        val sandbox = PickSandbox()
        val pick = AccountPickController(accounts, com.codefrak.engine.smith.SandboxCallers(sandbox))
        val host = org.springframework.mock.web.MockHttpServletRequest().apply { remoteAddr = "172.30.0.1" }
        try {
            val none = assertFailsWith<ResponseStatusException> { pick.pick(PickAccount("fable"), host) }
            assertEquals(503, none.statusCode.value())
            assertEquals(400, assertFailsWith<ResponseStatusException> { pick.pick(PickAccount("gemini-9"), host) }.statusCode.value())

            accounts.add("fm-a", "anthropic"); accounts.setCredential("fm-a", "sk-ant-oat01-a")
            accounts.add("fm-b", "anthropic"); accounts.setCredential("fm-b", "sk-ant-oat01-b")
            val first = pick.pick(PickAccount("fable"), host)
            assertEquals(PickedAccount("fm-a", "anthropic", "CLAUDE_CODE_OAUTH_TOKEN", "sk-ant-oat01-a"), first)
            assertFalse(first.toString().contains("sk-ant"), "the token is not in its toString")
            assertEquals("fm-b", pick.pick(PickAccount("claude-opus-5-5"), host).account, "marked picked: the rotation moves on")
            assertEquals("fm-a", accounts.peek("anthropic"), "and a Smith's next pick sees First mate's turn")

            val smith = "172.30.0.9"
            sandbox.runs[smith] = com.codefrak.engine.smith.SmithCaller("run-9", 47, "acme/tickets")
            val from = org.springframework.mock.web.MockHttpServletRequest().apply { remoteAddr = smith }
            assertEquals(403, assertFailsWith<ResponseStatusException> { pick.pick(PickAccount("fable"), from) }.statusCode.value())
            sandbox.down = true
            assertEquals(503, assertFailsWith<ResponseStatusException> { pick.pick(PickAccount("fable"), host) }.statusCode.value(),
                "Docker cannot say: never taken for the host")
        } finally { accounts.remove("fm-a"); accounts.remove("fm-b") }
    }

    @Test
    fun `an email note is stored trimmed, set, cleared and listed`() {
        assertTrue(accounts.add("anthropic-mail", "anthropic", "  me@example.com "))
        assertTrue(accounts.add("anthropic-nomail", "anthropic"))
        assertEquals("me@example.com", accounts.get("anthropic-mail")!!.email)
        assertNull(accounts.get("anthropic-nomail")!!.email)
        assertTrue(accounts.setEmail("anthropic-nomail", "other@example.com"))
        assertEquals(mapOf("anthropic-mail" to "me@example.com", "anthropic-nomail" to "other@example.com"),
            accounts.list().filter { it.name.startsWith("anthropic-") && it.name.contains("mail") }.associate { it.name to it.email })
        assertEquals("me@example.com", api.list().single { it.name == "anthropic-mail" }.email)
        assertTrue(accounts.setEmail("anthropic-mail", ""))
        assertNull(accounts.get("anthropic-mail")!!.email)
        assertTrue(accounts.setEmail("anthropic-nomail", null))
        assertNull(accounts.get("anthropic-nomail")!!.email)
        accounts.remove("anthropic-mail"); accounts.remove("anthropic-nomail")
    }

    @Test
    fun `PUT email answers 404 for no account and 400 for an over-long value`() {
        assertEquals(404, api.setEmail("nobody", SetEmail("a@b")).statusCode.value())
        accounts.add("anthropic-long", "anthropic")
        val refused = assertFailsWith<ResponseStatusException> { api.setEmail("anthropic-long", SetEmail("x".repeat(201))) }
        assertEquals(400, refused.statusCode.value())
        assertFailsWith<ResponseStatusException> { api.add(AddAccount("anthropic-long2", "anthropic", "x".repeat(201))) }
        assertNull(accounts.get("anthropic-long2"))
        assertEquals(204, api.setEmail("anthropic-long", SetEmail("x".repeat(200))).statusCode.value())
        accounts.remove("anthropic-long")
    }

    private fun status(e: ResponseStatusException) = e.statusCode.value()

    @Test
    fun `a rename carries the account, its credential, its usage, run stats and Jev calls to the new name`() {
        val sub = events.subscribe(com.codefrak.engine.events.EngineEvent.FLEET)
        try {
            assertTrue(accounts.add("rn-old", "anthropic", "me@example.com"))
            assertTrue(accounts.setCredential("rn-old", "sk-ant-oat01-rename"))
            meter("rn-old", 0.4)
            assertTrue(usages.markFableReached("rn-old"))
            jdbc.sql("""INSERT INTO engine.smith_run_stats (run_id, repo, issue, account, ended, exit_code)
                VALUES ('rn-run', 'acme/widgets', 1, 'rn-old', CURRENT_TIMESTAMP, 0)""").update()
            jdbc.sql("INSERT INTO engine.jev_call (id, use_case, account, model, request) VALUES ('rn-jev', 'acceptance', 'rn-old', 'm', '{}')").update()
            val before = api.list().single { it.name == "rn-old" }
            val usage = api.usage("rn-old").body!!
            while (sub.next(java.time.Duration.ZERO) != null) Unit

            assertEquals(204, api.rename("rn-old", Rename("rn-new")).statusCode.value())

            val after = api.list()
            assertNull(after.firstOrNull { it.name == "rn-old" })
            assertEquals(before.copy(name = "rn-new"), after.single { it.name == "rn-new" })
            assertEquals("sk-ant-oat01-rename", accounts.credential("rn-new"), "resealed under the new name")
            assertEquals(usage.copy(account = "rn-new"), api.usage("rn-new").body!!.copy(observedAt = usage.observedAt))
            assertEquals(404, api.usage("rn-old").statusCode.value())
            assertEquals("rn-new", jdbc.sql("SELECT account FROM engine.smith_run_stats WHERE run_id = 'rn-run'").query(String::class.java).single())
            assertEquals("rn-new", jdbc.sql("SELECT account FROM engine.jev_call WHERE id = 'rn-jev'").query(String::class.java).single())
            val frame = sub.next(java.time.Duration.ZERO)!!
            assertTrue(frame.startsWith("event: setting\n") && frame.contains("\"name\":\"accounts\"") && frame.contains("\"value\":\"rn-new\""), frame)
            assertNull(sub.next(java.time.Duration.ZERO), "one frame")

            assertEquals(204, api.rename("rn-new", Rename("rn-new")).statusCode.value(), "the same name is a no-op")
            assertNull(sub.next(java.time.Duration.ZERO), "and publishes nothing")
        } finally {
            sub.close()
            jdbc.sql("DELETE FROM engine.smith_run_stats WHERE run_id = 'rn-run'").update()
            jdbc.sql("DELETE FROM engine.jev_call WHERE id = 'rn-jev'").update()
            cleanUp(listOf("rn-old", "rn-new"))
        }
    }

    @Test
    fun `a rename over a removed account's stale usage reading answers 204 and keeps the renamed account's reading`() {
        try {
            assertTrue(accounts.add("st-x", "anthropic"))
            meter("st-x", 0.9)
            assertTrue(accounts.remove("st-x"))
            assertTrue(accounts.add("st-a", "anthropic"))
            meter("st-a", 0.2)
            val usage = api.usage("st-a").body!!
            assertEquals(204, api.rename("st-a", Rename("st-x")).statusCode.value())
            assertEquals(usage.copy(account = "st-x"), api.usage("st-x").body!!.copy(observedAt = usage.observedAt))
        } finally { cleanUp(listOf("st-a", "st-x")) }
    }

    @Test
    fun `a rename is refused for a bad name, no account, a taken name and a live run, and changes nothing`() {
        assertTrue(accounts.add("rf-a", "anthropic"))
        assertTrue(accounts.add("rf-b", "anthropic"))
        try {
            val bad = assertFailsWith<ResponseStatusException> { api.rename("rf-a", Rename("Bad Name")) }
            assertEquals(400, status(bad))
            assertEquals(bad.reason, assertFailsWith<ResponseStatusException> { api.add(AddAccount("Bad Name", "anthropic")) }.reason, "the rule POST /accounts uses")
            assertEquals(400, status(assertFailsWith<ResponseStatusException> { api.rename("rf-a", Rename("a".repeat(101))) }))
            assertEquals(404, api.rename("rf-none", Rename("rf-c")).statusCode.value())
            val taken = assertFailsWith<ResponseStatusException> { api.rename("rf-a", Rename("rf-b")) }
            assertEquals(409, status(taken))
            assertEquals(409, status(assertFailsWith<ResponseStatusException> { api.rename("rf-a", Rename("engine")) }))
            val telemetry = org.mockito.Mockito.mock(com.codefrak.engine.smith.SmithTelemetry::class.java)
            org.mockito.Mockito.`when`(telemetry.running()).thenReturn(mapOf("rf-a" to listOf(com.codefrak.engine.smith.ModelCount("claude-opus-5-5", 1))))
            val busy = AiAccountController(accounts, secrets, usages, verifier, telemetry, events, credit)
            val live = assertFailsWith<ResponseStatusException> { busy.rename("rf-a", Rename("rf-c")) }
            assertEquals(409, status(live))
            assertTrue(live.reason!!.contains("in use"), live.reason)
            assertEquals(listOf("rf-a", "rf-b"), accounts.list().map { it.name }.filter { it.startsWith("rf-") })
        } finally { cleanUp(listOf("rf-a", "rf-b", "rf-c")) }
    }
}

/** A sandbox that knows only which address is which run; `down` is Docker not answering. */
class PickSandbox : com.codefrak.engine.smith.SmithSandbox {
    val runs = mutableMapOf<String, com.codefrak.engine.smith.SmithCaller>()
    var down = false
    override fun start(spec: com.codefrak.engine.smith.SmithSpec): String = error("unused")
    override fun status(handle: String): com.codefrak.engine.smith.SandboxStatus = error("unused")
    override fun logs(handle: String, maxLines: Int): String = ""
    override fun output(handle: String, path: String): ByteArray? = null
    override fun remove(handle: String) {}
    override fun runAt(address: String) = if (down) error("docker is down") else runs[address]
}

/** Answers with [next] (accepted, unless set) and records every token it was asked about, and for which provider. */
class FakeVerifier : CredentialVerifier {
    var next = Verdict(true, true, "accepted")
    val tried = mutableListOf<String>()
    val providers = mutableListOf<String>()
    /** The Anthropic probe's reply; null is Anthropic not answering. */
    var probe: ProbeReply? = ProbeReply(200, emptyMap())
    val probed = mutableListOf<String>()
    override fun verify(provider: String, token: String): Verdict { tried += token; providers += provider; return next.also { next = Verdict(true, true, "accepted") } }
    override fun probeAnthropic(token: String): ProbeReply { probed += token; return probe ?: throw ProbeUnreachable("could not reach anthropic: Connection refused") }
    /** The key verdict, answered once then back to accepted, and every key tried with its kind. */
    var nextKey = Verdict(true, true, "accepted")
    val keys = mutableListOf<Pair<ApiKeyKind, String>>()
    override fun verifyKey(kind: ApiKeyKind, key: String): Verdict { keys += kind to key; return nextKey.also { nextKey = Verdict(true, true, "accepted") } }
}

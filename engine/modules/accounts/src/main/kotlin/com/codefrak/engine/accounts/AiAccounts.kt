package com.codefrak.engine.accounts

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/** What the API shows: never the credential, only that there is one. */
data class AiAccount(
    val name: String, val provider: String,
    /** The user's note of whose login it is; never read by the engine (engine/README.md, "AI accounts"). */
    val email: String?, val enabled: Boolean,
    val hasCredential: Boolean, val credentialSetAt: OffsetDateTime?, val addedAt: OffsetDateTime,
    /** Whether an API key and an Admin API key are stored; never the keys (engine/docs/ai-accounts.md, "API keys and credit"). */
    val apiKeySet: Boolean = false, val adminKeySet: Boolean = false,
    /** The day the plan's API credit cycle renews from, and its grant in cents. */
    val billingCycleDate: LocalDate? = null, val grantCents: Long? = null,
    /** Cents spent this cycle as cost_report last read, when, and why the last refresh failed (null when it did not). */
    val usedCents: BigDecimal? = null, val creditRefreshedAt: OffsetDateTime? = null, val creditError: String? = null,
)

/** The longest email note an account takes (`engine.ai_account.email`). */
const val EMAIL_MAX = 200

/** An email note as stored: trimmed, and null when blank. */
fun noteOf(email: String?): String? = email?.trim()?.ifEmpty { null }

/** The engine's own `engine.ai_account` table; Flyway owns its schema (db/migration). */
@Repository
class AiAccounts(private val jdbc: JdbcClient, private val secrets: SecretsKey, private val usages: AccountUsages) {
    private val columns = "name, provider, email, enabled, credential IS NOT NULL, credential_set_at, added_at, " +
        "api_key IS NOT NULL, admin_key IS NOT NULL, billing_cycle_date, credit_grant_cents, credit_used_cents, credit_refreshed_at, credit_error"
    private fun row(rs: java.sql.ResultSet) = AiAccount(
        rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getBoolean(5),
        rs.getObject(6, OffsetDateTime::class.java), rs.getObject(7, OffsetDateTime::class.java),
        rs.getBoolean(8), rs.getBoolean(9), rs.getObject(10, LocalDate::class.java), rs.getObject(11) as Long?,
        rs.getBigDecimal(12), rs.getObject(13, OffsetDateTime::class.java), rs.getString(14),
    )

    fun list(): List<AiAccount> =
        jdbc.sql("SELECT $columns FROM engine.ai_account ORDER BY name").query { rs, _ -> row(rs) }.list()

    fun get(name: String): AiAccount? =
        jdbc.sql("SELECT $columns FROM engine.ai_account WHERE name = :n").param("n", name).query { rs, _ -> row(rs) }.optional().orElse(null)

    /** False when an account by that name was already there; it is left as it was. */
    fun add(name: String, provider: String, email: String? = null): Boolean =
        jdbc.sql("INSERT INTO engine.ai_account (name, provider, email) SELECT :n, :p, :e WHERE NOT EXISTS (SELECT 1 FROM engine.ai_account WHERE name = :n)")
            .param("n", name).param("p", provider).param("e", noteOf(email)).update() == 1

    /** Sets the account's email note, trimmed; blank or null clears it. False when there is no such account. */
    fun setEmail(name: String, email: String?): Boolean =
        jdbc.sql("UPDATE engine.ai_account SET email = :e WHERE name = :n").param("e", noteOf(email)).param("n", name).update() == 1

    /**
     * Renames the account and carries every row keyed by its name over, in one
     * transaction: its usage reading, its Smith run stats and its Jev calls. A
     * credential is sealed with the name as associated data, so it is opened
     * under the old name and sealed again under the new one. False when there
     * is no account `from`; the caller refuses a taken `to` and a live run.
     */
    @Transactional
    fun rename(from: String, to: String): Boolean {
        val sealed = jdbc.sql("SELECT credential, api_key, admin_key FROM engine.ai_account WHERE name = :n").param("n", from)
            .query { rs, _ -> listOf(rs.getBytes(1), rs.getBytes(2), rs.getBytes(3)) }.optional().orElse(null) ?: return false
        val (credential, apiKey, adminKey) = sealed
        jdbc.sql("UPDATE engine.ai_account SET name = :to WHERE name = :from").param("to", to).param("from", from).update()
        // Each sealed value carries the name as associated data: opened under the old one, sealed under the new.
        credential?.let { setCredentialSealed(to, secrets.seal(secrets.open(it, from), to)) }
        apiKey?.let { storeKey(to, ApiKeyKind.API, secrets.open(it, ApiKeyKind.API.aad(from))) }
        adminKey?.let { storeKey(to, ApiKeyKind.ADMIN, secrets.open(it, ApiKeyKind.ADMIN.aad(from))) }
        // A reading left under the new name by a removed account belongs to no account: the renamed one's wins.
        jdbc.sql("DELETE FROM engine.ai_account_usage WHERE account = :to").param("to", to).update()
        for (table in listOf("ai_account_usage", "smith_run_stats", "jev_call"))
            jdbc.sql("UPDATE engine.$table SET account = :to WHERE account = :from").param("to", to).param("from", from).update()
        return true
    }

    private fun setCredentialSealed(name: String, sealed: ByteArray) =
        jdbc.sql("UPDATE engine.ai_account SET credential = :c, key_id = :k WHERE name = :n")
            .param("c", sealed).param("k", secrets.id).param("n", name).update()

    /** False when it was not there. */
    fun remove(name: String): Boolean =
        jdbc.sql("DELETE FROM engine.ai_account WHERE name = :n").param("n", name).update() == 1

    fun setEnabled(name: String, enabled: Boolean): Boolean =
        jdbc.sql("UPDATE engine.ai_account SET enabled = :e WHERE name = :n").param("e", enabled).param("n", name).update() == 1

    /** Seals the token under the engine's key and stores it; false when there is no such account. */
    fun setCredential(name: String, token: String): Boolean =
        jdbc.sql("UPDATE engine.ai_account SET credential = :c, key_id = :k, credential_set_at = CURRENT_TIMESTAMP WHERE name = :n")
            .param("c", secrets.seal(token, name)).param("k", secrets.id).param("n", name).update() == 1

    /**
     * Seals an API or Admin API key under the engine's key, with the account
     * name and the key's kind as associated data (so neither opens in the
     * other's column or row), and stores it; false when there is no such account.
     */
    fun storeKey(name: String, kind: ApiKeyKind, key: String): Boolean =
        jdbc.sql("UPDATE engine.ai_account SET ${kind.column} = :c, ${kind.column}_id = :k WHERE name = :n")
            .param("c", secrets.seal(key, kind.aad(name))).param("k", secrets.id).param("n", name).update() == 1

    /** Deletes the key; clearing the admin key also drops the credit figure it read. False when there is no such account. */
    fun clearKey(name: String, kind: ApiKeyKind): Boolean =
        jdbc.sql("UPDATE engine.ai_account SET ${kind.column} = NULL, ${kind.column}_id = NULL" +
            (if (kind == ApiKeyKind.ADMIN) ", credit_used_cents = NULL, credit_refreshed_at = NULL, credit_error = NULL" else "") +
            " WHERE name = :n").param("n", name).update() == 1

    /** The key in the clear; null when none is stored. Read only by the credit refresh ([ApiCreditRefresh]) for the admin key. */
    fun key(name: String, kind: ApiKeyKind): String? =
        jdbc.sql("SELECT ${kind.column} FROM engine.ai_account WHERE name = :n AND ${kind.column} IS NOT NULL").param("n", name)
            .query { rs, _ -> rs.getBytes(1) }.optional().map { secrets.open(it, kind.aad(name)) }.orElse(null)

    fun adminKey(name: String): String? = key(name, ApiKeyKind.ADMIN)

    /**
     * Sets the billing-cycle date and the grant. A changed date starts a
     * different cycle, so the figure read for the old one is dropped until the
     * next refresh. False when there is no such account.
     */
    fun setBilling(name: String, cycleDate: LocalDate, grantCents: Long): Boolean =
        jdbc.sql("""UPDATE engine.ai_account SET credit_grant_cents = :g,
                credit_used_cents = CASE WHEN billing_cycle_date = :d THEN credit_used_cents END,
                credit_refreshed_at = CASE WHEN billing_cycle_date = :d THEN credit_refreshed_at END,
                billing_cycle_date = :d WHERE name = :n""")
            .param("d", cycleDate).param("g", grantCents).param("n", name).update() == 1

    /** The accounts the credit refresh reads: those with an admin key and a billing-cycle date. */
    fun creditTracked(): List<String> =
        jdbc.sql("SELECT name FROM engine.ai_account WHERE admin_key IS NOT NULL AND billing_cycle_date IS NOT NULL ORDER BY name")
            .query(String::class.java).list().filterNotNull()

    /** A refresh's outcome: [usedCents] and now, or (null) the [error] beside the figure last read. */
    fun recordCredit(name: String, usedCents: BigDecimal?, error: String?) {
        if (usedCents != null) jdbc.sql("UPDATE engine.ai_account SET credit_used_cents = :u, credit_refreshed_at = CURRENT_TIMESTAMP, credit_error = NULL WHERE name = :n")
            .param("u", usedCents).param("n", name).update()
        else jdbc.sql("UPDATE engine.ai_account SET credit_error = :e WHERE name = :n").param("e", error).param("n", name).update()
    }

    /**
     * The picker: among the provider's enabled accounts that have a credential
     * and are not rejected (the 5-hour or 7-day window `rejected` with its reset
     * ahead, or a recorded limit rejection ahead: [AccountUsage.windowRejected]),
     * first those whose 7-day budget is expiring ([expiringPace]: they would need
     * at least an even week's pace to use up what is left before the
     * 7-day reset), most behind first; an account with no reading, no 7-day
     * utilization or reset, or a 7-day reset that has passed counts as not
     * expiring. Then, for a model that is not Fable, those whose Fable limit is
     * reached or whose Fable meter is out, so the others keep their Fable budget
     * for Fable runs. Then, among the rest and among equals, the one
     * with the lowest 5-hour utilization (no reading, or one whose reset has
     * passed, counts as 0), then
     * the least recently picked (never picked first), then by name; marked
     * picked now. Before all of that, an account whose 5-hour utilization is at
     * or above [FIVE_HOUR_SATURATED] (counted as above) ranks after every one
     * below it; when all are saturated, the order above picks among them.
     * For a model that is not Fable (or no model), an expiring account that
     * still has Fable headroom (Fable limit not reached, meter not out) ranks
     * after every other unsaturated account, so its 5-hour window stays free
     * for the Fable runs that can use up its expiring week; it is still picked
     * when it is the only usable one. A Fable model's order is unaffected.
     * Null when there is none, and a real run does not start. For a
     * Fable `model` an account whose Fable limit is reached is not usable
     * either; any other model ignores it. Two statements (H2 has no
     * RETURNING): two runs starting in the same instant may share an account,
     * which only skews the rotation, never a credential. Null for a provider
     * outside [SMITH_PROVIDERS]: a jev account is never a Smith's.
     */
    fun pick(provider: String, model: String? = null): String? {
        val name = best(provider, model) ?: return null
        jdbc.sql("UPDATE engine.ai_account SET last_used_at = CURRENT_TIMESTAMP WHERE name = :n").param("n", name).update()
        return name
    }

    /**
     * The account [pick] would choose, without marking it picked, so the
     * Smiths' rotation is unaffected: what the summarize endpoint's call bills to
     * (engine/README.md, "Summarize: a live run summarized"). Null when there is none.
     */
    fun peek(provider: String, model: String? = null): String? = best(provider, model)

    private class Ranked(val name: String, val pace: Double, val saturated: Boolean, val fableOut: Boolean)

    private fun best(provider: String, model: String?): String? {
        if (provider !in SMITH_PROVIDERS) return null
        val fableModel = isFable(model)
        val fable = if (fableModel) " AND NOT COALESCE($FABLE_REACHED, FALSE) AND NOT COALESCE($FABLE_METER_OUT, FALSE)" else ""
        val now = Instant.now()
        // The SQL order is the fallback; the stable sort puts unsaturated accounts first, then expiring ones,
        // then (not Fable) Fable-limited ones, and keeps it among equals. For a model that is not Fable, an expiring
        // account with Fable headroom goes after the other unsaturated ones: its 5-hour window is kept for Fable.
        return jdbc.sql("""SELECT a.name, u.seven_day_utilization, u.seven_day_reset, $FIVE_HOUR_UTILIZATION,
                COALESCE($FABLE_REACHED, FALSE) OR COALESCE($FABLE_METER_OUT, FALSE) FROM engine.ai_account a
            LEFT JOIN engine.ai_account_usage u ON u.account = a.name
            WHERE a.provider = :p AND a.enabled AND a.credential IS NOT NULL AND NOT COALESCE($WINDOW_REJECTED, FALSE)$fable
            ORDER BY $FIVE_HOUR_UTILIZATION, a.last_used_at NULLS FIRST, a.name""")
            .param("p", provider)
            .query { rs, _ ->
                Ranked(rs.getString(1), expiringPace(rs.getObject(2) as Number?, rs.getObject(3, OffsetDateTime::class.java), now),
                    (rs.getObject(4) as Number).toDouble() >= FIVE_HOUR_SATURATED, rs.getBoolean(5))
            }.list()
            .sortedWith(compareBy<Ranked> { it.saturated }.thenBy { !fableModel && it.pace > 0 && !it.fableOut }
                .thenByDescending { it.pace }.thenByDescending { it.fableOut })
            .firstOrNull()?.name
    }

    /**
     * When the first of the provider's enabled, credentialed accounts the
     * picker skips as rejected gets back in: the earliest of their
     * [AccountUsage.rejectedUntil]. Null when none is rejected. A disabled
     * account is not counted: no reset brings it back. Null for a provider
     * outside [SMITH_PROVIDERS].
     */
    fun blockedUntil(provider: String): OffsetDateTime? {
        if (provider !in SMITH_PROVIDERS) return null
        val now = Instant.now()
        return jdbc.sql("SELECT name FROM engine.ai_account WHERE provider = :p AND enabled AND credential IS NOT NULL")
            .param("p", provider).query(String::class.java).list()
            .mapNotNull { usages.get(it!!)?.rejectedUntil(now) }.minOrNull()
    }

    /**
     * When the first usable anthropic account gets its Fable limit back: the
     * earliest of `fable_limit_reset` among the enabled, credentialed ones at
     * a recorded limit and `fable_reset` among those whose Fable meter reads
     * at or above 1.0. Null when none is at it.
     */
    fun fableBlockedUntil(): OffsetDateTime? =
        jdbc.sql("""SELECT MIN(CASE WHEN $FABLE_REACHED THEN u.fable_limit_reset END), MIN(CASE WHEN $FABLE_METER_OUT THEN u.fable_reset END)
            FROM engine.ai_account a JOIN engine.ai_account_usage u ON u.account = a.name
            WHERE a.provider = 'anthropic' AND a.enabled AND a.credential IS NOT NULL""")
            .query { rs, _ -> listOfNotNull(rs.getObject(1, OffsetDateTime::class.java), rs.getObject(2, OffsetDateTime::class.java)).minOrNull() }
            .list().firstOrNull()

    /**
     * The key of a usable jev account, for the engine's own Jev call
     * (`HttpJevDecider`, engine/README.md, "Jev accounts"): among the enabled
     * jev accounts with a credential, the least recently used (never used
     * first), then by name; marked used now. Null when there is none. The one
     * way a jev key leaves the table: [pick] and [peek] answer null for jev,
     * so no Smith start ever reads one.
     */
    fun jevCredential(): JevKey? {
        val name = jdbc.sql("""SELECT name FROM engine.ai_account WHERE provider = 'jev' AND enabled AND credential IS NOT NULL
            ORDER BY last_used_at NULLS FIRST, name LIMIT 1""").query(String::class.java).optional().orElse(null) ?: return null
        jdbc.sql("UPDATE engine.ai_account SET last_used_at = CURRENT_TIMESTAMP WHERE name = :n").param("n", name).update()
        return credential(name)?.let { JevKey(name, it) }
    }

    /**
     * The token in the clear, for a Smith about to start; null when the account
     * has none. Outside the accounts' own code and a Smith's start, the
     * callers are the summarize endpoint's call ([peek]), the engine's Jev call
     * ([jevCredential]), First mate's pick ([AccountPickController]) and the
     * on-demand meter refresh ([AiAccountController.refreshUsage]).
     */
    fun credential(name: String): String? =
        jdbc.sql("SELECT credential FROM engine.ai_account WHERE name = :n AND credential IS NOT NULL").param("n", name)
            .query { rs, _ -> rs.getBytes(1) }.optional().map { secrets.open(it, name) }.orElse(null)

    private companion object {
        /** `u`'s 5-hour utilization for ordering: 0 with no reading or once its reset has passed. */
        const val FIVE_HOUR_UTILIZATION = "(CASE WHEN u.five_hour_reset <= CURRENT_TIMESTAMP THEN 0 ELSE COALESCE(u.five_hour_utilization, 0) END)"

        /** `u`'s Fable limit is reached and its reset is still ahead: [AccountUsage.fableReached] in SQL. */
        const val FABLE_REACHED = "(u.fable_limit = 'reached' AND (u.fable_limit_reset IS NULL OR u.fable_limit_reset > CURRENT_TIMESTAMP))"

        /** `u`'s Fable meter reads at or above 1.0 with its reset ahead: [AccountUsage.fableMeterOut] in SQL. */
        const val FABLE_METER_OUT = "(u.fable_utilization >= 1.0 AND u.fable_reset > CURRENT_TIMESTAMP)"

        /** `u` is rejected with a reset ahead: [AccountUsage.windowRejected] in SQL. NULL when there is no `u` row. */
        const val WINDOW_REJECTED = "((u.five_hour_status = 'rejected' AND u.five_hour_reset > CURRENT_TIMESTAMP)" +
            " OR (u.seven_day_status = 'rejected' AND u.seven_day_reset > CURRENT_TIMESTAMP)" +
            " OR u.limit_rejected_reset > CURRENT_TIMESTAMP)"
    }
}

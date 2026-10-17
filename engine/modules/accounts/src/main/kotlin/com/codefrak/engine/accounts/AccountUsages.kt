package com.codefrak.engine.accounts

import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * One account's plan meter as Anthropic last reported it (engine/README.md,
 * "The usage meter"). `rejected` is the one bit a picker needs: the last
 * request on this account was refused and its window has not reset yet.
 * `fableLimit` is separate state no header carries: `reached` once a run on
 * the account was refused with "reached your Fable limit", until
 * `fableLimitReset` (the 7-day reset when it was recorded); else `unknown`.
 * `limitRejectedReset` is the same kind of state for the session or spend
 * limit when only a run's result text named it: rejected until then.
 * `fableUtilization` and `fableReset` are the Fable limit's own meter, read
 * off the last `claude-fable-*` reply (null when none has been read, or its
 * reset has passed); at or above 1.0 with the reset ahead, the account is
 * out of Fable the same as a reached limit ([fableOut]).
 */
data class AccountUsage(
    val account: String,
    val fiveHourUtilization: Double?, val fiveHourStatus: String?, val fiveHourReset: OffsetDateTime?,
    val sevenDayUtilization: Double?, val sevenDayStatus: String?, val sevenDayReset: OffsetDateTime?,
    val status: String?, val httpStatus: Int, val requests: Long, val observedAt: OffsetDateTime,
    val fableLimit: String = FABLE_UNKNOWN, val fableLimitSince: OffsetDateTime? = null, val fableLimitReset: OffsetDateTime? = null,
    val limitRejectedSince: OffsetDateTime? = null, val limitRejectedReset: OffsetDateTime? = null,
    val fableUtilization: Double? = null, val fableReset: OffsetDateTime? = null,
) {
    /** True while the account's Fable limit is reached and its reset has not passed. */
    fun fableReached(now: Instant = Instant.now()): Boolean =
        fableLimit == FABLE_REACHED && (fableLimitReset == null || now.isBefore(fableLimitReset.toInstant()))

    /** True while the Fable meter reads at or above 1.0 and its reset is ahead. */
    fun fableMeterOut(now: Instant = Instant.now()): Boolean =
        (fableUtilization ?: 0.0) >= 1.0 && fableReset != null && now.isBefore(fableReset.toInstant())

    /** The picker's Fable gate: [fableReached] or [fableMeterOut]. */
    fun fableOut(now: Instant = Instant.now()): Boolean = fableReached(now) || fableMeterOut(now)

    /** When [fableOut] stops holding: the latest of the gating resets; null for an unknown reset or when not out. */
    fun fableOutUntil(now: Instant = Instant.now()): OffsetDateTime? = listOfNotNull(
        fableLimitReset.takeIf { fableReached(now) }, fableReset.takeIf { fableMeterOut(now) },
    ).maxOrNull()

    /**
     * The picker's gate (engine/README.md, "AI accounts"): the 5-hour status is
     * `rejected` with its reset ahead, or the 7-day one is, or a result text
     * recorded a limit whose reset is ahead. A rejected window with no reset
     * does not block: nothing would ever let the account back in.
     */
    fun windowRejected(now: Instant = Instant.now()): Boolean = rejectedUntil(now) != null

    /** When [windowRejected] stops holding: the latest of the blocking resets still ahead; null when none is. */
    fun rejectedUntil(now: Instant = Instant.now()): OffsetDateTime? = listOfNotNull(
        fiveHourReset.takeIf { fiveHourStatus == "rejected" },
        sevenDayReset.takeIf { sevenDayStatus == "rejected" },
        limitRejectedReset,
    ).filter { now.isBefore(it.toInstant()) }.maxOrNull()

    /** The API's `rejected` field: the picker's own verdict, [windowRejected]. */
    fun rejected(now: Instant = Instant.now()): Boolean = windowRejected(now)

    companion object {
        const val FABLE_UNKNOWN = "unknown"
        const val FABLE_REACHED = "reached"
    }
}

/** The `anthropic-ratelimit-unified-*` headers of one response; the `7d_oi` pair (the Fable limit) only on a `claude-fable-*` one. */
data class MeterReading(
    val fiveHourUtilization: Double?, val fiveHourStatus: String?, val fiveHourReset: OffsetDateTime?,
    val sevenDayUtilization: Double?, val sevenDayStatus: String?, val sevenDayReset: OffsetDateTime?,
    val status: String?,
    val fableUtilization: Double? = null, val fableReset: OffsetDateTime? = null,
) {
    companion object {
        const val PREFIX = "anthropic-ratelimit-unified-"

        /** Parses a response's headers (names compared case-insensitively). Null when no unified header is present. */
        fun of(headers: Map<String, List<String>>): MeterReading? {
            val h = headers.entries.associate { it.key.lowercase() to it.value.firstOrNull() }
            if (h.keys.none { it.startsWith(PREFIX) }) return null
            fun s(k: String) = h["$PREFIX$k"]?.trim()?.ifEmpty { null }
            fun d(k: String) = s(k)?.toDoubleOrNull()
            fun t(k: String) = s(k)?.toLongOrNull()?.let { OffsetDateTime.ofInstant(Instant.ofEpochSecond(it), ZoneOffset.UTC) }
            return MeterReading(d("5h-utilization"), s("5h-status"), t("5h-reset"), d("7d-utilization"), s("7d-status"), t("7d-reset"), s("status"),
                d("7d_oi-utilization"), t("7d_oi-reset"))
        }
    }
}

/** `engine.ai_account_usage`: the latest reading per account, written by the proxy, read by the API and the picker. */
@Repository
class AccountUsages(private val jdbc: JdbcClient) {
    private val log = LoggerFactory.getLogger(AccountUsages::class.java)
    private val columns = "account, five_hour_utilization, five_hour_status, five_hour_reset, seven_day_utilization, seven_day_status, seven_day_reset, status, http_status, requests, observed_at, " +
        "fable_limit, fable_limit_since, fable_limit_reset, limit_rejected_since, limit_rejected_reset, fable_utilization, fable_reset"
    private fun row(rs: java.sql.ResultSet) = AccountUsage(
        rs.getString(1),
        rs.getObject(2, Double::class.javaObjectType), rs.getString(3), rs.getObject(4, OffsetDateTime::class.java),
        rs.getObject(5, Double::class.javaObjectType), rs.getString(6), rs.getObject(7, OffsetDateTime::class.java),
        rs.getString(8), rs.getInt(9), rs.getLong(10), rs.getObject(11, OffsetDateTime::class.java),
        rs.getString(12), rs.getObject(13, OffsetDateTime::class.java), rs.getObject(14, OffsetDateTime::class.java),
        rs.getObject(15, OffsetDateTime::class.java), rs.getObject(16, OffsetDateTime::class.java),
        rs.getObject(17, Double::class.javaObjectType), rs.getObject(18, OffsetDateTime::class.java),
    )

    fun list(): List<AccountUsage> =
        jdbc.sql("SELECT $columns FROM engine.ai_account_usage ORDER BY account").query { rs, _ -> row(rs) }.list()

    fun get(account: String): AccountUsage? =
        jdbc.sql("SELECT $columns FROM engine.ai_account_usage WHERE account = :a").param("a", account).query { rs, _ -> row(rs) }.optional().orElse(null)

    /**
     * Records one response's reading for the account, replacing the previous
     * one. The Fable meter (`fable_utilization`, `fable_reset`) is replaced only
     * by a reading that carries it: most runs are not on a `claude-fable-*`
     * model, and theirs keeps the last one, until its reset passes. The Fable
     * limit columns are not the reading's: they are left alone, except that a
     * reading past the recorded Fable reset clears them. True when this
     * reading was the one that cleared them.
     */
    fun record(account: String, reading: MeterReading, httpStatus: Int, at: OffsetDateTime = OffsetDateTime.now()): Boolean {
        write(account, reading, httpStatus, at)
        clearLimitIfPast(account, at)
        clearFableMeterIfPast(account, at)
        return clearFableIfRolled(account, reading, at)
    }

    private fun write(account: String, reading: MeterReading, httpStatus: Int, at: OffsetDateTime) {
        val updated = jdbc.sql("""
            UPDATE engine.ai_account_usage SET five_hour_utilization = :fu, five_hour_status = :fs, five_hour_reset = :fr,
                seven_day_utilization = :su, seven_day_status = :ss, seven_day_reset = :sr, status = :st, http_status = :hs,
                fable_utilization = COALESCE(CAST(:bu AS DOUBLE PRECISION), fable_utilization),
                fable_reset = COALESCE(CAST(:br AS TIMESTAMP WITH TIME ZONE), fable_reset), requests = requests + 1, observed_at = :at WHERE account = :a""".trimIndent())
            .params(params(account, reading, httpStatus, at)).update()
        if (updated == 1) return
        try {
            jdbc.sql("""
                INSERT INTO engine.ai_account_usage (account, five_hour_utilization, five_hour_status, five_hour_reset,
                    seven_day_utilization, seven_day_status, seven_day_reset, status, http_status, fable_utilization, fable_reset, requests, observed_at)
                VALUES (:a, :fu, :fs, :fr, :su, :ss, :sr, :st, :hs, :bu, :br, 1, :at)""".trimIndent())
                .params(params(account, reading, httpStatus, at)).update()
        } catch (e: DuplicateKeyException) {
            write(account, reading, httpStatus, at)   // two first responses at once: the other one made the row
        }
    }

    /**
     * The account's Fable limit is reached as of `at`, until its current 7-day
     * reset (a week from `at` when no reading has one). Recorded once: a row
     * already `reached` with its reset ahead keeps its `since` and `reset`.
     * False when it already was.
     */
    fun markFableReached(account: String, at: OffsetDateTime = OffsetDateTime.now()): Boolean {
        val mark = """
            UPDATE engine.ai_account_usage SET fable_limit = 'reached', fable_limit_since = :at,
                fable_limit_reset = COALESCE(seven_day_reset, :week)
            WHERE account = :a AND (fable_limit <> 'reached' OR fable_limit_reset <= :at)""".trimIndent()
        val p = mapOf("a" to account, "at" to at, "week" to at.plusDays(7))
        if (jdbc.sql(mark).params(p).update() == 1) return true
        if (get(account) != null) return false
        try {
            // No reading yet (the refused run left no meter): a row that holds only the limit.
            jdbc.sql("""
                INSERT INTO engine.ai_account_usage (account, http_status, requests, observed_at, fable_limit, fable_limit_since, fable_limit_reset)
                VALUES (:a, 0, 0, :at, 'reached', :at, :week)""".trimIndent()).params(p).update()
            return true
        } catch (e: DuplicateKeyException) {
            return jdbc.sql(mark).params(p).update() == 1
        }
    }

    /**
     * The account is rejected as of `at` until `reset`, from a run's result
     * text naming its session or spend limit. A later reset than one already
     * recorded replaces it; an earlier one does not shorten it. False when it
     * changed nothing.
     */
    fun markLimitRejected(account: String, reset: OffsetDateTime, at: OffsetDateTime = OffsetDateTime.now()): Boolean {
        val mark = """
            UPDATE engine.ai_account_usage SET limit_rejected_since = :at, limit_rejected_reset = :r
            WHERE account = :a AND (limit_rejected_reset IS NULL OR limit_rejected_reset < :r)""".trimIndent()
        val p = mapOf("a" to account, "at" to at, "r" to reset)
        if (jdbc.sql(mark).params(p).update() == 1) return true
        if (get(account) != null) return false
        try {
            // No reading yet: a row that holds only the rejection.
            jdbc.sql("""
                INSERT INTO engine.ai_account_usage (account, http_status, requests, observed_at, limit_rejected_since, limit_rejected_reset)
                VALUES (:a, 0, 0, :at, :at, :r)""".trimIndent()).params(p).update()
            return true
        } catch (e: DuplicateKeyException) {
            return jdbc.sql(mark).params(p).update() == 1
        }
    }

    /** A reading at or past the recorded limit rejection's reset clears it. */
    private fun clearLimitIfPast(account: String, at: OffsetDateTime) {
        val cleared = jdbc.sql("UPDATE engine.ai_account_usage SET limit_rejected_since = NULL, limit_rejected_reset = NULL WHERE account = :a AND limit_rejected_reset <= :at")
            .param("a", account).param("at", at).update() == 1
        if (cleared) log.info("account {}: recorded limit rejection cleared, its reset passed", account)
    }

    /** A kept Fable meter whose reset has passed no longer says anything: cleared, until a Fable reply brings a new one. */
    private fun clearFableMeterIfPast(account: String, at: OffsetDateTime) {
        jdbc.sql("UPDATE engine.ai_account_usage SET fable_utilization = NULL, fable_reset = NULL WHERE account = :a AND fable_reset <= :at")
            .param("a", account).param("at", at).update()
    }

    /**
     * Back to `unknown` once the 7-day window the limit was recorded in has
     * rolled: the reading's 7-day reset is past the recorded one, or `at` is.
     * One conditional update, so only the reading that flips the row logs it.
     */
    fun clearFableIfRolled(account: String, reading: MeterReading, at: OffsetDateTime = OffsetDateTime.now()): Boolean {
        val cleared = jdbc.sql("""
            UPDATE engine.ai_account_usage SET fable_limit = 'unknown', fable_limit_since = NULL, fable_limit_reset = NULL
            WHERE account = :a AND fable_limit = 'reached' AND (fable_limit_reset IS NULL OR fable_limit_reset < :sr OR fable_limit_reset <= :at)""".trimIndent())
            .param("a", account).param("sr", reading.sevenDayReset ?: at).param("at", at).update() == 1
        if (cleared) log.info("account {}: Fable limit cleared, its 7-day window rolled", account)
        return cleared
    }

    private fun params(account: String, r: MeterReading, httpStatus: Int, at: OffsetDateTime): Map<String, Any?> = mapOf(
        "a" to account, "fu" to r.fiveHourUtilization, "fs" to r.fiveHourStatus, "fr" to r.fiveHourReset,
        "su" to r.sevenDayUtilization, "ss" to r.sevenDayStatus, "sr" to r.sevenDayReset, "st" to r.status,
        "bu" to r.fableUtilization, "br" to r.fableReset, "hs" to httpStatus, "at" to at,
    )
}

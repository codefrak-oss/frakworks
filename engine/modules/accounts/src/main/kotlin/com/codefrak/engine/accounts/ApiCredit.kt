package com.codefrak.engine.accounts

import com.codefrak.engine.engineJson
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.lease.EngineLease
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.readValue
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The start of the API credit cycle `today` is in (engine/docs/ai-accounts.md,
 * "API keys and credit"): the anchor's day of the month in today's month,
 * clamped to the month's length (a 31st anchor starts on Feb 28 or 29), or
 * the month before's when that is after today. Cycles roll monthly from the
 * anchor; dates are UTC, as cost_report's daily buckets are.
 */
fun cycleStart(anchor: LocalDate, today: LocalDate): LocalDate {
    fun inMonth(month: LocalDate) = month.withDayOfMonth(minOf(anchor.dayOfMonth, month.lengthOfMonth()))
    val here = inMonth(today.withDayOfMonth(1))
    return if (here.isAfter(today)) inMonth(today.withDayOfMonth(1).minusMonths(1)) else here
}

/** The cycle after the one starting at [start]: when the grant renews. */
fun cycleEnd(anchor: LocalDate, start: LocalDate): LocalDate {
    val next = start.withDayOfMonth(1).plusMonths(1)
    return next.withDayOfMonth(minOf(anchor.dayOfMonth, next.lengthOfMonth()))
}

/** cost_report's amounts, cents as decimal strings ("123.4567"), summed exactly. */
fun sumCents(amounts: Iterable<String>): BigDecimal = amounts.fold(BigDecimal.ZERO) { sum, a -> sum + BigDecimal(a.trim()) }

/** The Admin API's cost report could not be read; the message never carries the key. */
class CostReportFailed(message: String) : RuntimeException(message)

/** Spend from the Admin API's cost report; tests use a fake. */
interface CostReport {
    /** Cents spent in the key's organization from [since] (UTC midnight) to now; throws [CostReportFailed]. */
    fun spentCents(adminKey: String, since: LocalDate): BigDecimal
}

/**
 * `GET /v1/organizations/cost_report` with daily buckets from [since], every
 * page, every result's `amount` summed. The key goes nowhere else and is
 * never logged.
 */
@Component
class HttpCostReport(@Value("\${engine.anthropic.upstream:https://api.anthropic.com}") private val anthropic: String) : CostReport {
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    override fun spentCents(adminKey: String, since: LocalDate): BigDecimal {
        val start = URLEncoder.encode(since.atStartOfDay(ZoneOffset.UTC).toInstant().toString(), Charsets.UTF_8)
        val amounts = mutableListOf<String>()
        var page: String? = null
        repeat(MAX_PAGES) {
            val query = "starting_at=$start&bucket_width=1d&limit=31" + (page?.let { "&page=" + URLEncoder.encode(it, Charsets.UTF_8) } ?: "")
            val request = HttpRequest.newBuilder(URI(anthropic.trimEnd('/') + "/v1/organizations/cost_report?$query"))
                .header("x-api-key", adminKey).header("anthropic-version", "2023-06-01").timeout(TIMEOUT).GET().build()
            val response = try { client.send(request, HttpResponse.BodyHandlers.ofString()) }
                catch (e: Exception) { throw CostReportFailed("could not reach anthropic: ${e.message ?: e.javaClass.simpleName}") }
            if (response.statusCode() !in 200..299)
                throw CostReportFailed("cost_report answered ${response.statusCode()}: ${providerError(response.body())}")
            val report = try { engineJson.readValue<Map<String, Any?>>(response.body()) }
                catch (e: Exception) { throw CostReportFailed("cost_report's answer is not JSON") }
            for (bucket in report["data"] as? List<*> ?: emptyList<Any?>())
                for (result in (bucket as? Map<*, *>)?.get("results") as? List<*> ?: emptyList<Any?>())
                    ((result as? Map<*, *>)?.get("amount"))?.let { amounts += it.toString() }
            page = (report["next_page"] as? String)?.takeIf { report["has_more"] == true }
            if (page == null) return try { sumCents(amounts) } catch (e: NumberFormatException) { throw CostReportFailed("cost_report has an amount that is not a number") }
        }
        throw CostReportFailed("cost_report had more than $MAX_PAGES pages")
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(15)
        /** A cycle is at most 31 daily buckets; the cap only stops a report that never ends. */
        const val MAX_PAGES = 20
    }
}

/** Anthropic's own error message at error.message, else the start of the body. */
internal fun providerError(body: String?): String = runCatching {
    ((engineJson.readValue<Map<String, Any?>>(body ?: "")["error"] as? Map<*, *>)?.get("message") as? String)?.ifBlank { null }
}.getOrNull() ?: body.orEmpty().take(200).ifBlank { "no reason given" }

/**
 * Every [REFRESH_MS] (never faster than a minute: cost_report allows that, and
 * lags ~5 min anyway), under the engine lease, reads each account's spend this
 * cycle and stores it ([AiAccounts.recordCredit]). A failure keeps the last
 * figure and stores the error, so it shows. Each refresh publishes the
 * `accounts` setting frame, which the desktop refetches on.
 */
@Component
class ApiCreditRefresh(private val accounts: AiAccounts, private val report: CostReport, private val lease: EngineLease,
                       private val events: EngineEvents) {
    private val log = LoggerFactory.getLogger(ApiCreditRefresh::class.java)

    @Scheduled(fixedDelay = REFRESH_MS, initialDelay = 60_000)
    fun tick() { if (lease.held()) refreshAll() }

    fun refreshAll(today: LocalDate = LocalDate.now(ZoneOffset.UTC)) {
        for (name in accounts.creditTracked()) refresh(name, today)
    }

    /** Refreshes one account; false when it has no admin key or date, or the refresh failed (the error is stored). */
    fun refresh(name: String, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): Boolean {
        val anchor = accounts.get(name)?.billingCycleDate ?: return false
        val key = accounts.adminKey(name) ?: return false
        val ok = try {
            accounts.recordCredit(name, report.spentCents(key, cycleStart(anchor, today)), null); true
        } catch (e: Exception) {
            val why = if (e is CostReportFailed) e.message!! else "refresh failed: ${e.javaClass.simpleName}"
            log.warn("api credit refresh for {}: {}", name, why)
            accounts.recordCredit(name, null, why); false
        }
        events.publish(EngineEvent.Setting("accounts", name))
        return ok
    }

    companion object {
        const val REFRESH_MS = 5 * 60_000L
    }
}

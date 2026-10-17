package com.codefrak.engine.accounts

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** `GET /accounts/consumption`'s answer: the window it summed, and its rows ([ClaudeUsageLedger.consumption]). */
data class ConsumptionView(val since: OffsetDateTime, val until: OffsetDateTime, val rows: List<ConsumptionRow>)

/**
 * What the fleet consumed from the AI accounts over a window (engine/README.md, "Consumption
 * ledger"), for the desktop's Analytics > AI Accounts > Account consumption. `window` is one of
 * [WINDOWS] ending now; else `since` (and optionally `until`, default now) as ISO-8601 instants.
 */
@RestController
class ConsumptionController(private val ledger: ClaudeUsageLedger) {
    @GetMapping("/accounts/consumption")
    fun consumption(
        @RequestParam(required = false) window: String?,
        @RequestParam(required = false) since: String?,
        @RequestParam(required = false) until: String?,
    ): ConsumptionView {
        val end = until?.let(::instant) ?: OffsetDateTime.now()
        val start = when {
            window != null -> WINDOWS[window]?.let { end.minus(it) }
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "window is one of ${WINDOWS.keys}")
            since != null -> instant(since)
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "give window (${WINDOWS.keys.joinToString()}) or since")
        }
        if (!start.isBefore(end)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "since must be before until")
        return ConsumptionView(start, end, ledger.consumption(start, end))
    }

    private fun instant(s: String): OffsetDateTime = try { OffsetDateTime.parse(s) }
        catch (e: DateTimeParseException) { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "not an ISO-8601 instant: $s") }

    companion object {
        val WINDOWS: Map<String, Duration> = linkedMapOf(
            "1h" to Duration.ofHours(1), "4h" to Duration.ofHours(4), "1d" to Duration.ofDays(1),
            "1w" to Duration.ofDays(7), "30d" to Duration.ofDays(30),
        )
    }
}

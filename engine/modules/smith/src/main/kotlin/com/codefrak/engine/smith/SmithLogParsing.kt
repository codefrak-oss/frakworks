package com.codefrak.engine.smith

import com.codefrak.engine.engineJson
import com.codefrak.engine.accounts.isFable
import tools.jackson.module.kotlin.readValue
import java.time.OffsetDateTime

/** The modes told the repo's token's scopes (`SMITH_GH_SCOPES`): the refiner draws a scope by them. */
private val GH_SCOPES_MODES = setOf("refine")

/** A run's `SMITH_GH_SCOPES`: the token's classic scopes, comma-separated, `none` or `unknown`; null for a mode not told them. */
fun ghScopes(mode: String, scopes: () -> Set<String>?): String? =
    if (mode in GH_SCOPES_MODES) scopes()?.let { it.sorted().joinToString(",").ifEmpty { "none" } } ?: "unknown" else null

private val SESSION_LIMIT = Regex("session limit|monthly spend limit", RegexOption.IGNORE_CASE)
private val LIMIT_RESET = Regex("""resets\s+(?:at\s+)?(\d{1,2})(?::(\d{2}))?\s*([ap]m)\s*(?:\(([^)]+)\))?""", RegexOption.IGNORE_CASE)

/**
 * The text that names a session or monthly spend limit, or null. The same
 * evidence as [fableLimitHit]: Claude's closing `result` event when it is an
 * error, or `result.json` of a failed run; an assistant or tool line that only
 * quotes the phrase does not count.
 */
fun sessionLimitHit(log: String, failedOutput: ByteArray?): String? {
    closingResult(log)?.let { event ->
        val result = event["result"] as? String ?: ""
        if ((event["is_error"] == true || event["terminal_reason"] == "api_error") && SESSION_LIMIT.containsMatchIn(result)) return result
    }
    val out = failedOutput?.toString(Charsets.UTF_8)
    return out?.takeIf { SESSION_LIMIT.containsMatchIn(it) }
}

/**
 * The first instant after `after` at the clock time a text like "your session
 * limit resets 3:10pm (UTC)" names, or null when it names none. The zone in
 * parentheses is read as a zone id; with none, or one that does not parse, UTC.
 */
fun parseLimitReset(text: String, after: OffsetDateTime): OffsetDateTime? {
    val m = LIMIT_RESET.find(text) ?: return null
    val hour12 = m.groupValues[1].toInt()
    val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
    if (hour12 !in 1..12 || minute !in 0..59) return null
    val hour = hour12 % 12 + if (m.groupValues[3].lowercase() == "pm") 12 else 0
    val zone = m.groupValues[4].trim().takeIf { it.isNotEmpty() }
        ?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() } ?: java.time.ZoneOffset.UTC
    val local = after.atZoneSameInstant(zone)
    var at = local.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
    if (!at.isAfter(local)) at = at.plusDays(1)
    return at.toOffsetDateTime().withOffsetSameInstant(java.time.ZoneOffset.UTC)
}

private val FABLE_LIMIT = Regex("reached your fable limit", RegexOption.IGNORE_CASE)

/** Claude's wording on 2026-10-05 for the Fable limit; the Fable limit only on a `claude-fable-*` run. */
private val OUT_OF_CREDITS = Regex("out of usage credits", RegexOption.IGNORE_CASE)
private val PROMPT_TOO_LONG = Regex("prompt is too long", RegexOption.IGNORE_CASE)

/**
 * Claude's closing stream-json `result` event: the last log line that parses as a JSON object
 * of `type` `result`, or null when there is none. Read from the end, since it closes the run
 * and `run-smith.sh` may print after it. The one reader of that event: `overflowHit`,
 * `fableLimitHit` and the run's `cost`/`turns`/`durationMs` all take it from here.
 */
fun closingResult(log: String): Map<String, Any?>? =
    log.lines().asReversed().asSequence().filter { it.contains("\"result\"") }
        .mapNotNull { line -> runCatching { engineJson.readValue<Map<String, Any?>>(line.trim()) }.getOrNull() }
        .firstOrNull { it["type"] == "result" }

/**
 * Whether the run overflowed its context window (#113, #102, #120 on 2026-09-25): Claude's
 * closing stream-json `result` event with `terminal_reason` `blocking_limit`, or a result
 * saying "Prompt is too long". An assistant or tool line that only quotes the phrase does not count.
 */
fun overflowHit(log: String): Boolean {
    val event = closingResult(log) ?: return false
    return event["terminal_reason"] == "blocking_limit" || PROMPT_TOO_LONG.containsMatchIn(event["result"] as? String ?: "")
}

/**
 * Why a run counts as refused for the account's Fable limit, or null. No
 * response header carries that limit (engine/README.md, "The usage meter"),
 * so the evidence is the run's own: Claude's closing stream-json `result`
 * event, when it is an error naming the limit (an assistant or tool line that
 * only quotes the phrase does not count); `result.json`, passed only for a
 * failed run, since a finished Smith may well write the phrase in a summary;
 * or a 429 on a `claude-fable-*` run whose 5-hour and 7-day statuses were
 * both `allowed` (or absent), whatever the overall status: it reads
 * `rejected` when only the Fable (`7d_oi`) quota is out. On a Fable run,
 * "out of usage credits" (2026-10-05) names the Fable limit too.
 */
fun fableLimitHit(log: String, failedOutput: ByteArray?, meter: SandboxMeter?, model: String?): String? {
    val fable = isFable(model)
    fun names(text: String) = FABLE_LIMIT.containsMatchIn(text) || (fable && OUT_OF_CREDITS.containsMatchIn(text))
    val refused = closingResult(log)?.let { event ->
        (event["is_error"] == true || event["terminal_reason"] == "api_error") && names(event["result"] as? String ?: "")
    } ?: false
    if (refused) return "result event"
    if (failedOutput != null && names(failedOutput.toString(Charsets.UTF_8))) return "result.json"
    if (meter != null && meter.httpStatus == 429 && fable &&
        meter.reading?.fiveHourStatus != "rejected" && meter.reading?.sevenDayStatus != "rejected") return "429 on $model"
    return null
}

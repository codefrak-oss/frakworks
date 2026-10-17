package com.codefrak.engine.jev

import com.codefrak.engine.engineJson
import com.codefrak.engine.errorResponse
import com.codefrak.engine.parseSince
import com.codefrak.engine.utc
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import tools.jackson.module.kotlin.readValue
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The Jev decisions over HTTP (engine/README.md, "Jev decisions"), read-only over `engine.jev_call`: the calls,
 * one call in full, and the summary the desktop's Jev decisions page draws. No Jev call and no GitHub call is
 * made, and no row's `account` is ever answered.
 */
@RestController
class JevController(private val calls: JevCalls, private val agreement: JevAgreement) {
    /**
     * The calls newest first, `limit` (1 to 500, default 50) from `offset`, with `total`: filtered by `use_case`,
     * `repo`, `issue`, `process_instance` (the flow that made the call), `action` (a prefix), `outcome` (`set`,
     * `unset` or a prefix), `since` (an ISO instant or date, inclusive) and `until` (the same, exclusive). Each row carries the pick and confidence read from the response, never the request or response.
     */
    @GetMapping("/jev/calls")
    fun list(
        @RequestParam("use_case", required = false) useCase: String?,
        @RequestParam(required = false) repo: String?,
        @RequestParam(required = false) issue: Long?,
        @RequestParam(required = false) action: String?,
        @RequestParam(required = false) outcome: String?,
        @RequestParam(required = false) since: String?,
        @RequestParam(required = false) until: String?,
        @RequestParam("process_instance", required = false) processInstance: String?,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
        @RequestParam(required = false, defaultValue = "0") offset: Long,
    ): Map<String, Any?> {
        if (limit !in 1..MAX_LIMIT) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "limit is 1 to $MAX_LIMIT")
        if (offset < 0) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "offset is 0 or more")
        val filter = JevFilter(useCase?.ifBlank { null }, repo?.ifBlank { null }, issue, action?.ifEmpty { null }, outcome?.ifEmpty { null },
            since?.let { at(it) }, until?.let { at(it, "until") }, processInstance?.ifBlank { null })
        val (rows, total) = calls.list(filter, limit, offset)
        return linkedMapOf("total" to total, "limit" to limit, "offset" to offset, "calls" to rows.map { row(it) })
    }

    /** One call in full: the request as sent (state and questions), the response as received or the error, and its picks. 404 when unknown. */
    @GetMapping("/jev/calls/{id}")
    fun one(@PathVariable id: String): ResponseEntity<Any> {
        val call = calls.get(id) ?: return errorResponse(HttpStatus.NOT_FOUND, "no jev call $id")
        val request = parsed(call.request)
        return ResponseEntity.ok(row(call) + linkedMapOf(
            "outcome_at" to call.outcomeAt?.let { utc(it) },
            "state" to (request as? Map<*, *>)?.get("state"),
            "questions" to (request as? Map<*, *>)?.get("questions"),
            "request" to request,
            "response" to call.response?.let { parsed(it) },
            "error" to call.error,
            "picks" to JevAnswers.picks(JevAnswers.parse(call.response)),
        ))
    }

    /** Per use case over the window from `since` (default 7 days ago) to now ([JevSummary]). */
    @GetMapping("/jev/summary")
    fun summary(@RequestParam(required = false) since: String?): JevSummary {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val from = since?.let { at(it) } ?: now.minusDays(7)
        return JevSummaries.of(calls.window(from), from, now, agreement)
    }

    private fun row(call: JevCall): Map<String, Any?> {
        val (pick, confidence) = JevAnswers.summary(JevAnswers.picks(JevAnswers.parse(call.response)))
        return linkedMapOf(
            "id" to call.id, "called_at" to utc(call.calledAt), "use_case" to call.useCase,
            "process_instance" to call.processInstance, "repo" to call.repo, "issue" to call.issue, "model" to call.model,
            "latency_ms" to call.latencyMs, "threshold" to call.threshold, "action" to call.action, "outcome" to call.outcome,
            "pick" to pick, "confidence" to confidence, "no_answer" to (call.response == null || call.error != null),
        )
    }

    /** A stored body as JSON, or as the text it is when it is not JSON. */
    private fun parsed(body: String): Any? = runCatching { engineJson.readValue<Any>(body) }.getOrDefault(body)

    private companion object {
        const val MAX_LIMIT = 500

        /** `since` (or `until`) as an ISO instant, an offset date-time, or a date (its UTC midnight); 400 otherwise. */
        fun at(text: String, name: String = "since"): OffsetDateTime =
            parseSince(text) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$name is an ISO instant or date: $text")
    }
}

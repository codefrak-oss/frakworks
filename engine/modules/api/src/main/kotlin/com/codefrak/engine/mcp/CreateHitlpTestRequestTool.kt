package com.codefrak.engine.mcp

import com.codefrak.engine.hitlp.HitlpFiling
import com.codefrak.engine.hitlp.HitlpPrimitives
import com.codefrak.engine.hitlp.HitlpRequester
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * create_hitlp_test_request (#100137): First mate's way to try the HITLP server itself rather than filing a ticket
 * ("generate a test hitlp request"). A plain `human.ask` with test content, filed straight through [HitlpRequester]:
 * no ticket, no flow wait, so it skips [com.codefrak.engine.hitlp.HitlpAsserted] and the `engine.hitlp_request` store,
 * which are both a flow wait's. No server configured, or the server's own failure, answers an error naming the cause.
 */
class CreateHitlpTestRequestTool(private val requester: HitlpRequester) {
    companion object {
        const val NAME = "create_hitlp_test_request"
        private val ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        private val PRIORITIES = listOf("low", "normal", "high", "urgent")
        val TTL: Duration = Duration.ofHours(1)

        val TOOL = McpTool(NAME,
            "Create a test HITLP request (task) on the configured HITLP server, for trying the integration itself " +
                "rather than filing a ticket about it. Use this, not submit_ask, when asked to generate, create or " +
                "test a HITLP request. Takes optional title, body and priority, filling test defaults for what is " +
                "left out, and answers the created task's id and decision URL (when the server gives one).",
            obj(
                "title" to prop("string", "The request's summary; a test default when left out."),
                "body" to prop("string", "The question put to the person; a test default when left out."),
                "priority" to prop("string", "The envelope's priority; \"normal\" when left out.", "enum" to PRIORITIES),
            ))
    }

    fun call(args: Map<String, Any?>): McpToolResult {
        refuseOthers(NAME, args, listOf("title", "body", "priority"))?.let { return it }
        val title = (args["title"] as? String)?.takeIf { it.isNotBlank() } ?: "Test HITLP request"
        val body = (args["body"] as? String)?.takeIf { it.isNotBlank() } ?: "This is a test request from First mate, to confirm the HITLP server is reachable and working. Any answer will do."
        val priority = (args["priority"] as? String) ?: "normal"
        if (priority !in PRIORITIES) return McpToolResult.error("priority must be one of ${quoted(PRIORITIES)}, not ${json(args["priority"])}")
        if (!requester.configured) return McpToolResult.error(HitlpRequester.UNSET)

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val deadline = now.plus(TTL)
        val request = linkedMapOf<String, Any?>(
            "idempotencyKey" to "first-mate-test:${UUID.randomUUID()}",
            "deadline" to ISO.format(deadline.withOffsetSameInstant(ZoneOffset.UTC)),
            "defaultOnTimeout" to "fail",
            "priority" to priority,
            "context" to mapOf("summary" to title),
            "question" to body,
            "responseSchema" to mapOf("type" to "string"),
        )
        val handle = try {
            requester.call("human.${HitlpPrimitives.ASK}", request, TTL.toMillis())
        } catch (e: Exception) {
            return McpToolResult.error("the HITLP server did not create the test request: ${e.message}")
        }
        val url = HitlpFiling.decisionUrl(handle)
        return McpToolResult.of(linkedMapOf("ok" to true, "id" to handle.taskId, "status" to handle.status) + if (url != null) mapOf("url" to url) else emptyMap())
    }
}

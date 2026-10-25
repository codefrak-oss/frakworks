package com.codefrak.engine.mcp

import com.codefrak.engine.hitlp.HitlpFiling
import com.codefrak.engine.hitlp.HitlpHandle
import com.codefrak.engine.hitlp.HitlpPrimitives
import com.codefrak.engine.hitlp.HitlpRequester
import com.codefrak.engine.hitlp.HitlpSchemas
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * hitlp_ask, hitlp_approve and hitlp_task (#100138): First mate's way to put a question or an approval to the user on
 * the configured HITLP server and read the answer later. Claude Code's MCP client sends no task-augmented calls, which
 * HITLP requires (spec/hitlp.md §8 R1), so the engine's [HitlpRequester] files the task and the tool answers its id at
 * once, never waiting for the decision; hitlp_task reads it (`tasks/get`, with the result once terminal). No ticket and
 * no flow wait: it skips [com.codefrak.engine.hitlp.HitlpAsserted] and the `engine.hitlp_request` store. First mate's
 * only ([FrakworksMcp] gates by [McpCaller]); no server configured, or the server's own failure, is an error naming it.
 */
class HitlpTools(private val requester: HitlpRequester) {
    companion object {
        const val ASK = "hitlp_ask"
        const val APPROVE = "hitlp_approve"
        const val TASK = "hitlp_task"
        val NAMES = listOf(ASK, APPROVE, TASK)
        val PRIORITIES = listOf("low", "normal", "high", "urgent")
        val DEFAULT_TTL: Duration = Duration.ofHours(24)
        val MAX_TTL: Duration = Duration.ofDays(30)
        private val ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME

        private val COMMON = arrayOf(
            "summary" to prop("string", "A one-line summary the surface shows for the request (context.summary)."),
            "priority" to prop("string", "The envelope's priority; \"normal\" when left out.", "enum" to PRIORITIES),
            "ttlMinutes" to prop("integer", "How long the request stays open, in minutes; 1440 (a day) when left out, at most 43200."),
        )

        val ASK_TOOL = McpTool(ASK,
            "Ask the user a question through HITLP (human.ask) and answer the created task's id at once, without " +
                "waiting for the answer: read it later with $TASK. Optional choices make it a pick from a list.",
            obj("question" to prop("string", "The question put to the user."),
                *COMMON,
                "choices" to mapOf("type" to "array", "items" to mapOf("type" to "string"),
                    "description" to "Answers the user picks from; a free-text answer when left out."),
                required = listOf("question", "summary")))

        val APPROVE_TOOL = McpTool(APPROVE,
            "Ask the user to approve or deny an action through HITLP (human.approve) and answer the created task's id " +
                "at once, without waiting for the decision: read it later with $TASK.",
            obj("action" to prop("string", "The action to approve, in one line (\"delete branch x of owner/name\")."),
                *COMMON,
                "payload" to mapOf("type" to "object", "description" to "What exactly is approved, as JSON; its digest is sent with it. {} when left out."),
                required = listOf("action", "summary")))

        val TASK_TOOL = McpTool(TASK,
            "Read a HITLP task's state by its id (from $ASK or $APPROVE): its status and, once completed, failed or " +
                "cancelled, its decision record (the answer, or approved / denied).",
            obj("taskId" to prop("string", "The task id $ASK or $APPROVE answered."), required = listOf("taskId")))

        val TOOLS = listOf(ASK_TOOL, APPROVE_TOOL, TASK_TOOL)
    }

    fun call(tool: String, args: Map<String, Any?>): McpToolResult = when (tool) {
        ASK -> ask(args)
        APPROVE -> approve(args)
        TASK -> task(args)
        else -> McpToolResult.error("unknown tool: ${json(tool)}")
    }

    private fun ask(args: Map<String, Any?>): McpToolResult {
        refuseOthers(ASK, args, listOf("question", "summary", "priority", "ttlMinutes", "choices"))?.let { return it }
        val question = text(args, "question") ?: return McpToolResult.error("\"question\" is required: a non-empty string")
        val choices = args["choices"]?.let { c ->
            (c as? List<*>)?.takeIf { l -> l.isNotEmpty() && l.all { it is String && it.isNotBlank() } }?.map { it as String }
                ?: return McpToolResult.error("\"choices\" must be a non-empty list of non-empty strings, not ${json(c)}")
        }
        return envelope(ASK, args) { request ->
            request["question"] = question
            if (choices == null) request["responseSchema"] = mapOf("type" to "string")
            else {
                request["responseSchema"] = mapOf("type" to "string", "enum" to choices)
                request["options"] = choices.map { mapOf("value" to it, "label" to it) }
            }
        }
    }

    private fun approve(args: Map<String, Any?>): McpToolResult {
        refuseOthers(APPROVE, args, listOf("action", "summary", "priority", "ttlMinutes", "payload"))?.let { return it }
        val action = text(args, "action") ?: return McpToolResult.error("\"action\" is required: a non-empty string")
        @Suppress("UNCHECKED_CAST")
        val payload = when (val p = args["payload"]) {
            null -> emptyMap()
            is Map<*, *> -> p as Map<String, Any?>
            else -> return McpToolResult.error("\"payload\" must be a JSON object, not ${json(p)}")
        }
        return envelope(APPROVE, args) { request ->
            request["action"] = action
            request["payload"] = payload
            request["payloadDigest"] = HitlpSchemas.digest(payload)
            request["scope"] = mapOf("maxUses" to 1, "notAfter" to request["deadline"])
            request["requiresHuman"] = true
        }
    }

    /** Builds the envelope common to both primitives, lets [fill] add [tool]'s own members, checks it and files it. */
    private fun envelope(tool: String, args: Map<String, Any?>, fill: (MutableMap<String, Any?>) -> Unit): McpToolResult {
        val summary = text(args, "summary") ?: return McpToolResult.error("\"summary\" is required: a non-empty string")
        val priority = args["priority"] ?: "normal"
        if (priority !in PRIORITIES) return McpToolResult.error("priority must be one of ${quoted(PRIORITIES)}, not ${json(args["priority"])}")
        val ttl = args["ttlMinutes"]?.let { t ->
            wholeNumberOf(t)?.takeIf { it > 0 && it <= MAX_TTL.toMinutes() }?.let { Duration.ofMinutes(it) }
                ?: return McpToolResult.error("ttlMinutes must be a whole number from 1 to ${MAX_TTL.toMinutes()}, not ${json(t)}")
        } ?: DEFAULT_TTL
        if (!requester.configured) return McpToolResult.error(HitlpRequester.UNSET)

        val primitive = if (tool == APPROVE) HitlpPrimitives.APPROVE else HitlpPrimitives.ASK
        val deadline = OffsetDateTime.now(ZoneOffset.UTC).plus(ttl).withNano(0)
        val request = linkedMapOf<String, Any?>(
            "idempotencyKey" to "first-mate:${UUID.randomUUID()}",
            "deadline" to ISO.format(deadline),
            "defaultOnTimeout" to "fail",
            "priority" to priority,
            "context" to mapOf("summary" to summary),
        )
        fill(request)
        HitlpSchemas.requesterProblem(primitive, request)?.let { return McpToolResult.error("not a valid human.$primitive request: $it") }
        val handle = try {
            requester.call("human.$primitive", request, ttl.toMillis())
        } catch (e: Exception) {
            return McpToolResult.error("the HITLP server did not create the request: ${e.message}")
        }
        return McpToolResult.of(linkedMapOf<String, Any?>("ok" to true) + handleOf(handle))
    }

    private fun task(args: Map<String, Any?>): McpToolResult {
        refuseOthers(TASK, args, listOf("taskId"))?.let { return it }
        val id = text(args, "taskId") ?: return McpToolResult.error("\"taskId\" is required: the id $ASK or $APPROVE answered")
        if (!requester.configured) return McpToolResult.error(HitlpRequester.UNSET)
        val handle = try {
            requester.get(id)
        } catch (e: Exception) {
            return McpToolResult.error("the HITLP server did not answer task ${json(id)}: ${e.message}")
        }
        return McpToolResult.of(handleOf(handle))
    }

    /** A task as the tools answer it: id, status, whether it is decided, its decision URL and, once terminal, its decision record. */
    private fun handleOf(handle: HitlpHandle): Map<String, Any?> = linkedMapOf<String, Any?>(
        "taskId" to handle.taskId, "status" to handle.status, "terminal" to handle.terminal,
    ).apply {
        handle.statusMessage?.let { put("statusMessage", it) }
        HitlpFiling.decisionUrl(handle)?.takeIf { !handle.terminal }?.let { put("url", it) }
        if (handle.terminal) put("result", handle.record ?: handle.result)
    }

    private fun text(args: Map<String, Any?>, key: String) = (args[key] as? String)?.takeIf { it.isNotBlank() }
}

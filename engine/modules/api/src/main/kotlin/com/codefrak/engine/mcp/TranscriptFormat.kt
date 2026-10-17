package com.codefrak.engine.mcp

import java.math.BigDecimal

/**
 * One Smith stream-json event as the transcript's lines (engine/README.md,
 * "Engine-served MCP servers"): a port of the desktop's `src/transcript-format.js`
 * `formatEvent`, so read_transcript prints what the desktop's transcript pane draws. [event]
 * is the parsed JSON object; an event it does not name renders to nothing.
 * No time column (#934); the caller puts a blank line between entries.
 */
object TranscriptFormat {
    const val CODEX_HOOK_TRUST_WARNING =
        "`--dangerously-bypass-hook-trust` is enabled. Enabled hooks may run without review for this invocation."

    private val SPACES = Regex("(?U)\\s+")
    private val EDGES = Regex("(?U)^\\s+|\\s+$")

    fun collapse(text: String) = text.replace(SPACES, " ")
    fun trim(text: String) = text.replace(EDGES, "")

    /** At most [limit] code points (as Python's `len()` counts), then "…". */
    fun truncate(text: String, limit: Int): String =
        if (text.codePointCount(0, text.length) <= limit) text else text.substring(0, text.offsetByCodePoints(0, limit)) + "…"

    /** jq's number printer: an integral number never gets a trailing `.0`; null is the text `null`. */
    fun jqNum(v: Any?): String = when (v) {
        null -> "null"
        is Double -> if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e21) v.toLong().toString()
                     else BigDecimal(v.toString()).stripTrailingZeros().toPlainString()
        is Float -> jqNum(v.toDouble())
        else -> v.toString()
    }

    /** Half away from zero, like jq's `round`. */
    fun jqRound(x: Double): Double = if (x >= 0) Math.floor(x + 0.5) else -Math.floor(-x + 0.5)

    fun tojson(value: Any?): String = mcpJson.writeValueAsString(value)

    /** JavaScript truthiness, which the original's `||` and `if` test. */
    private fun truthy(v: Any?): Boolean = when (v) {
        null, false -> false
        is String -> v.isNotEmpty()
        is Number -> v.toDouble() != 0.0 && !v.toDouble().isNaN()
        else -> true
    }

    private fun or(vararg vs: Any?): Any? = vs.firstOrNull { truthy(it) } ?: vs.last()
    private fun num(v: Any?): Double = (if (truthy(v)) v as? Number else null)?.toDouble() ?: 0.0
    /** JavaScript's `${v}`: an absent value is the text `undefined`. */
    private fun js(v: Any?): String = if (v == null) "undefined" else jqNum(v)
    private fun str(v: Any?): String = if (v is String) v else if (v == null) "" else jqNum(v)

    /** A tool call's one-line description: its `description` input, else its first non-empty string input in key order. */
    private fun toolDescription(input: Any?): String {
        val obj = input as? Map<*, *> ?: emptyMap<String, Any>()
        fun pick(v: Any?) = if (v is String) trim(collapse(v)) else ""
        val desc = pick(obj["description"]).ifEmpty { obj.values.map(::pick).firstOrNull { it.isNotEmpty() } ?: "" }
        return if (desc.isEmpty()) "" else truncate(desc, 100)
    }

    private fun formatAssistant(event: Map<*, *>): List<String> {
        val content = ((event["message"] as? Map<*, *>)?.get("content") as? List<*>).orEmpty()
        return content.mapNotNull { item ->
            val i = item as? Map<*, *> ?: return@mapNotNull null
            when (i["type"]) {
                "text" -> trim(str(or(i["text"], ""))).takeIf { it.isNotEmpty() }?.let { truncate(collapse(it), 200) }
                "tool_use" -> toolDescription(i["input"]).let { if (it.isEmpty()) "→ ${js(i["name"])}" else "→ ${js(i["name"])}  $it" }
                else -> null
            }
        }
    }

    private fun formatResult(event: Map<*, *>): String {
        val suffix = if (truthy(event["is_error"])) " · is_error" else ""
        val durationS = (num(event["duration_ms"]) / 1000).toLong()
        val cost = jqRound(num(event["total_cost_usd"]) * 10000) / 10000
        var line = "── result: ${jqNum(event["subtype"])}$suffix · ${jqNum(event["num_turns"])} turns · ${durationS}s · ~$${jqNum(cost)}"
        val result = event["result"]
        if (truthy(event["is_error"]) && result is String && result.isNotEmpty()) line += "\n   ↳ ${truncate(collapse(result), 200)}"
        return line
    }

    private fun failed(detail: String) = "── result: error\n   ↳ ${truncate(collapse(detail), 200)}"

    fun formatEvent(event: Map<*, *>): List<String> {
        val type = event["type"]
        return when {
            type == "assistant" -> formatAssistant(event)
            type == "result" -> listOf(formatResult(event))
            // Codex `exec --json` events.
            type == "thread.started" -> listOf("── codex thread ${str(or(event["thread_id"], "")).take(8)}")
            type == "item.completed" -> {
                val item = event["item"] as? Map<*, *> ?: emptyMap<String, Any>()
                when (val itemType = item["type"]) {
                    "agent_message" -> trim(str(or(item["text"], ""))).takeIf { it.isNotEmpty() }?.let { listOf(truncate(collapse(it), 200)) } ?: emptyList()
                    "error" -> {
                        val detail = str(or(item["message"], item["text"], "Codex run failed"))
                        if (detail == CODEX_HOOK_TRUST_WARNING) emptyList() else listOf(failed(detail))
                    }
                    "command_execution", "mcp_tool_call", "file_change" ->
                        listOf("→ $itemType  ${truncate(collapse(str(or(item["command"], item["server"], item["path"], ""))), 100)}")
                    else -> emptyList()
                }
            }
            type == "turn.completed" -> {
                val usage = event["usage"] as? Map<*, *> ?: emptyMap<String, Any>()
                val total = listOf("input_tokens", "output_tokens").sumOf { num(usage[it]).toLong() }
                listOf("── result: success · $total tokens")
            }
            type == "turn.failed" || type == "error" -> {
                var detail = or(event["error"], event["message"], "Codex run failed")
                if (detail is Map<*, *> || detail is List<*>) detail = (detail as? Map<*, *>)?.get("message")?.takeIf { truthy(it) } ?: tojson(detail)
                listOf(failed(str(detail)))
            }
            else -> emptyList()
        }
    }
}

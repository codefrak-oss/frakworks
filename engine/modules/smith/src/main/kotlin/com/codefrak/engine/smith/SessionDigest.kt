package com.codefrak.engine.smith

import com.codefrak.engine.engineJson
import tools.jackson.module.kotlin.readValue

/** The reading of a stored session into what a summary is made from: [SmithSessions]' stored lines, pure, no database. */
object SessionDigest {
    /** What [digest] keeps by default: enough for a summary, well under the summarizer's context. */
    const val DIGEST_CHARS = SmithSessions.DIGEST_CHARS

    /** How many of the last steps [recent] keeps: each is one assistant message, its text and tool calls. */
    const val RECENT_TURNS = SmithSessions.RECENT_TURNS

    /** The last `turns` of [digest]'s entries, never cut, oldest first. */
    fun recent(lines: List<String>, turns: Int): String = lines.mapNotNull(::entry).takeLast(turns).joinToString("\n")

    /**
     * What a summary is made from: one short entry per step of the
     * session, from Claude's stream-json events. The assistant's text and
     * each tool call (its name and description, or the start of its
     * input) are kept; tool results, thinking and the init event are
     * dropped; a line that is not JSON (`run-smith.sh`'s own) is kept as
     * it is. Over `maxChars`, the opening quarter and the most recent
     * rest are kept, with a marker between: what it is doing now weighs most.
     */
    fun digest(lines: List<String>, maxChars: Int): String {
        val entries = lines.mapNotNull(::entry)
        if (entries.sumOf { it.length + 1 } <= maxChars) return entries.joinToString("\n")
        val head = mutableListOf<String>()
        var headChars = 0
        for (e in entries) { if (headChars + e.length + 1 > maxChars / 4) break; head += e; headChars += e.length + 1 }
        val tail = ArrayDeque<String>()
        var tailChars = 0
        for (e in entries.asReversed().take(entries.size - head.size)) {
            if (headChars + tailChars + e.length + 1 > maxChars) break
            tail.addFirst(e); tailChars += e.length + 1
        }
        val skipped = entries.size - head.size - tail.size
        return (head + "[… $skipped steps left out …]" + tail).joinToString("\n")
    }

    private fun entry(line: String): String? {
        if (line.isBlank()) return null
        val event = if (line.trimStart().startsWith("{")) runCatching { engineJson.readValue<Map<String, Any?>>(line) }.getOrNull() else null
        if (event == null) return "log: ${line.take(300)}"
        return when (event["type"]) {
            "assistant" -> ((event["message"] as? Map<*, *>)?.get("content") as? List<*>).orEmpty()
                .mapNotNull { content(it as? Map<*, *> ?: return@mapNotNull null) }.joinToString("\n").ifBlank { null }
            "result" -> "result: ${str(event["subtype"])} ${str(event["result"]).take(300)}".trimEnd()
            else -> null
        }
    }

    private fun content(c: Map<*, *>): String? = when (c["type"]) {
        "text" -> str(c["text"]).trim().ifBlank { null }?.let { "said: ${it.take(600)}" }
        "tool_use" -> {
            val input = c["input"] as? Map<*, *> ?: emptyMap<String, Any?>()
            val what = str(input["description"]).ifBlank { null } ?: str(input["command"]).ifBlank { null } ?: engineJson.writeValueAsString(input)
            "tool ${str(c["name"]).ifBlank { "?" }}: ${what.take(200)}"
        }
        else -> null
    }

    private fun str(v: Any?) = v as? String ?: ""
}

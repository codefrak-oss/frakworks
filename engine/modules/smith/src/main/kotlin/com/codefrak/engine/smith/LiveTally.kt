package com.codefrak.engine.smith

import com.codefrak.engine.engineJson
import tools.jackson.module.kotlin.readValue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A running Smith's turns and tokens, from the Claude stream-json lines of its stdout as they
 * arrive (engine/README.md, "Ticket timeline"). No cost: Claude Code emits no running dollar
 * figure, and the user chose cost at the end over an estimate priced by a table of the engine's.
 *
 * Claude Code writes one `assistant` event per content block, each repeating its message's `id`
 * and `usage`, so the tally keeps one usage per `message.id`, a later event of the same id
 * replacing the earlier: feeding a line twice counts it once. A turn is one distinct main-thread
 * message (`parent_tool_use_id` null); a subagent's messages add tokens, not turns. A line that
 * is not such an event (a Codex run's, `run-smith.sh`'s own, a cut one) is ignored.
 */
class LiveTally {
    private class Message(val tokens: LiveTokens, val main: Boolean)

    private val messages = LinkedHashMap<String, Message>()
    private var resultTurns: Long? = null

    /** Whether the run's stored lines were fed (or there were none to feed): a tally rebuilt after a restart reads them once. */
    val seeded = AtomicBoolean(false)

    fun feed(line: String) {
        val start = line.trimStart()
        if (!start.startsWith("{") || !(ASSISTANT in line || RESULT in line)) return
        val event = runCatching { engineJson.readValue<Map<String, Any?>>(start) }.getOrNull() ?: return
        when (event["type"]) {
            "assistant" -> {
                val message = event["message"] as? Map<*, *> ?: return
                val id = message["id"] as? String ?: return
                val usage = message["usage"] as? Map<*, *> ?: return
                val n = { k: String -> (usage[k] as? Number)?.toLong() ?: 0L }
                val tokens = LiveTokens(n("input_tokens"), n("output_tokens"), n("cache_read_input_tokens"), n("cache_creation_input_tokens"))
                synchronized(this) { messages[id] = Message(tokens, event["parent_tool_use_id"] == null) }
            }
            "result" -> (event["num_turns"] as? Number)?.toLong()?.let { synchronized(this) { resultTurns = it } }
        }
    }

    @Synchronized fun figures(): LiveFigures {
        if (messages.isEmpty()) return LiveFigures(resultTurns, null, null)
        val all = messages.values
        val counted = all.count { it.main }.toLong()
        val tokens = LiveTokens(all.sumOf { it.tokens.input }, all.sumOf { it.tokens.output },
            all.sumOf { it.tokens.cacheRead }, all.sumOf { it.tokens.cacheWrite })
        return LiveFigures(resultTurns ?: counted, tokens, counted)
    }

    companion object {
        private const val ASSISTANT = "\"assistant\""
        private const val RESULT = "\"result\""

        /** A tally of `lines`, as [LiveTally.feed] would build it live. */
        fun of(lines: Iterable<String>) = LiveTally().also { t -> lines.forEach(t::feed) }
    }
}

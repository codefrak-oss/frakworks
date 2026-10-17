package com.codefrak.engine.events

import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration

/**
 * A test subscriber of the bus: every frame published while it is open, as its `event:` type and `data`, so a
 * source's test asserts the frame it publishes. [drain] takes what is queued (waiting up to `wait` for a first
 * frame, for one published off the command thread) and keeps it; [of] is what has been seen of one type.
 */
class EventRecorder(private val bus: EngineEvents, repo: String = EngineEvent.FLEET) : AutoCloseable {
    private val json = jacksonObjectMapper()
    private val sub = bus.subscribe(repo)
    private val seen = mutableListOf<Frame>()

    data class Frame(val type: String, val data: JsonNode) {
        val state: String? get() = str("state")
        fun str(field: String): String? = data[field]?.takeUnless { it.isNull }?.asString()
        fun long(field: String): Long? = data[field]?.takeUnless { it.isNull }?.asLong()
    }

    fun drain(wait: Duration = Duration.ZERO): List<Frame> {
        var frame = sub.next(wait)
        while (frame != null && frame != EventSubscription.CLOSE) {
            val lines = frame.trim().lines()
            seen += Frame(lines[0].removePrefix("event: "), json.readTree(lines[1].removePrefix("data: ")))
            frame = sub.next(Duration.ZERO)
        }
        return seen.toList()
    }

    /** Every frame of `type` seen so far, after draining what is queued. */
    fun of(type: String, wait: Duration = Duration.ZERO): List<Frame> = drain(wait).filter { it.type == type }

    /** Waits up to `timeout` for a frame of `type` matching `match`, draining as it goes; the first such, or fails. */
    fun await(type: String, timeout: Duration = Duration.ofSeconds(5), match: (Frame) -> Boolean): Frame {
        val until = System.currentTimeMillis() + timeout.toMillis()
        while (System.currentTimeMillis() < until) {
            of(type, Duration.ofMillis(100)).firstOrNull(match)?.let { return it }
        }
        error("no $type frame matched within $timeout; saw ${seen.map { "${it.type}/${it.state}" }}")
    }

    override fun close() = bus.unsubscribe(sub)
}

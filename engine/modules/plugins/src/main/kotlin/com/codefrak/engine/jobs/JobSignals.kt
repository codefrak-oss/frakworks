package com.codefrak.engine.jobs

import com.codefrak.engine.engineJson
import tools.jackson.databind.JsonNode
import java.time.Instant

/**
 * A job's start signal (engine/README.md, "Custom jobs and the SDK"): the `engine.job.signal` column's JSON, told apart
 * by its `kind`. Adding a kind is a subclass here and a branch in [parse]; the column is TEXT, so no migration.
 */
sealed class Signal {
    /** The kind as the JSON names it. */
    abstract val kind: String

    /** When the next run is due after a run at [now]; null for a signal with no clock. */
    abstract fun next(now: Instant): Instant?

    /** `{"kind": "every", "every": N}`: a run every N seconds, N at least 1, started by the [JobTicker]. */
    data class Every(val seconds: Long) : Signal() {
        init { require(seconds >= 1) { "every is at least 1 second" } }
        override val kind get() = EVERY
        override fun next(now: Instant): Instant = now.plusSeconds(seconds)
    }

    /** `{"kind": "execute"}`: runs only on `POST /jobs/{id}/execute`. */
    data object Execute : Signal() {
        override val kind get() = EXECUTE
        override fun next(now: Instant): Instant? = null
    }

    /**
     * `{"kind": "at", "at": "<ISO-8601 instant>"}`: one run at [at], started by the [JobTicker], and never again. Due
     * while now is before it, null from then on, so a run at or after it leaves nothing due; an execute before it
     * leaves the scheduled run in place.
     */
    data class At(val at: Instant) : Signal() {
        override val kind get() = AT
        override fun next(now: Instant): Instant? = at.takeIf { now.isBefore(it) }
    }

    companion object {
        const val EVERY = "every"
        const val EXECUTE = "execute"
        const val AT = "at"
        val KINDS = listOf(EVERY, EXECUTE, AT)

        /** Parses the signal's JSON text; an [IllegalArgumentException] names what is wrong. */
        fun parse(text: String): Signal = parse(
            try { engineJson.readTree(text) } catch (e: Exception) { throw IllegalArgumentException("signal is not JSON: ${e.message}") },
        )

        /** Parses a signal object; an [IllegalArgumentException] names what is wrong. */
        fun parse(node: JsonNode?): Signal {
            require(node != null && node.isObject) { "signal is an object with a kind" }
            val kind = node["kind"]?.takeIf { it.isString }?.asString()
                ?: throw IllegalArgumentException("signal needs a kind; there is ${KINDS.joinToString()}")
            val keys = node.propertyNames().toSet()
            return when (kind) {
                EVERY -> {
                    val extra = keys - setOf("kind", "every")
                    require(extra.isEmpty()) { "an every signal takes kind and every, not ${extra.sorted().joinToString()}" }
                    val every = node["every"]
                    require(every != null && every.isIntegralNumber && every.asLong() >= 1) { "every is a whole number of seconds, at least 1" }
                    Every(every.asLong())
                }
                EXECUTE -> {
                    val extra = keys - setOf("kind")
                    require(extra.isEmpty()) { "an execute signal takes only kind, not ${extra.sorted().joinToString()}" }
                    Execute
                }
                AT -> {
                    val extra = keys - setOf("kind", "at")
                    require(extra.isEmpty()) { "an at signal takes kind and at, not ${extra.sorted().joinToString()}" }
                    val at = node["at"]?.takeIf { it.isString }?.asString()
                        ?: throw IllegalArgumentException("at is an ISO-8601 instant string, e.g. 2026-10-06T18:00:00Z")
                    At(try { Instant.parse(at) } catch (e: Exception) { throw IllegalArgumentException("at '$at' is not an ISO-8601 instant, e.g. 2026-10-06T18:00:00Z") })
                }
                else -> throw IllegalArgumentException("unknown signal kind '$kind'; there is ${KINDS.joinToString()}")
            }
        }
    }
}

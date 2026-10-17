package com.codefrak.sdk

/**
 * One question for Jev (engine/README.md, "Jev"): its [type], `noul`, `choice` or `score`, the [instructions],
 * and its [criteria], the choice's options or the score's levels. The companion makes each kind.
 */
data class JevQuestion(val type: String, val instructions: Any, val criteria: Any? = null) {
    companion object {
        /** A yes/no question answered with a value in [0, 1]. */
        fun noul(instructions: Any, whenTrue: String? = null, whenFalse: String? = null) = JevQuestion("noul", instructions,
            if (whenTrue == null && whenFalse == null) null else mapOf("true" to whenTrue, "false" to whenFalse))
        /** A pick among [options], each with an optional description. */
        fun choice(instructions: Any, options: Map<String, String?>) = JevQuestion("choice", instructions, options)
        /** A position between [levels], ordered. */
        fun score(instructions: Any, levels: List<String>) = JevQuestion("score", instructions, levels)
    }
}

/** Jev's answer to one question: `noul` for a noul, `choice` and `confidence` for a choice, `score`, `probabilities` and `confidence` for a score. */
data class JevAnswer(
    val type: String, val noul: Double? = null, val choice: String? = null, val score: Double? = null,
    val probabilities: Map<String, Double> = emptyMap(), val confidence: Double? = null,
)

/** The call's `engine.jev_call` id, and the answers by question key; null answers is "no answer", which the caller's own rule covers. */
data class JevDecision(val callId: String, val answers: Map<String, JevAnswer>?)

/**
 * Jev, the engine's decision model, for a plugin (engine/README.md, "Custom jobs and the SDK"). Every call is
 * logged in `engine.jev_call` as the engine's own are, with the [useCase] and the caller's repo. Never throws
 * for Jev's sake: a failed call is a [JevDecision] with null answers.
 */
interface Jev {
    fun decide(useCase: String, questions: Map<String, JevQuestion>, state: Any, threshold: Double? = null): JevDecision
}

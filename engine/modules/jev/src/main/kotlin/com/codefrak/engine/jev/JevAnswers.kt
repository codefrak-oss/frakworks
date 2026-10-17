package com.codefrak.engine.jev

import com.codefrak.engine.engineJson
import tools.jackson.module.kotlin.readValue

/** One question's pick as `GET /jev/calls` shows it: the option (a choice's, `tier <n>`, or `yes`/`no`), its confidence and probabilities. */
data class JevPick(val question: String, val type: String, val pick: String?, val confidence: Double?, val probabilities: Map<String, Double>)

/**
 * Reads a stored `engine.jev_call.response` back into picks, for `GET /jev/calls` and `/jev/summary`
 * (engine/README.md, "Jev decisions"). A choice's pick is its option; a score's is its level, `tier <n>`
 * for refine-pick's tier ([RefinePickQuestions.tierOf]); a noul's is `yes` at 0.5 or above, else `no`, with the
 * noul itself as its confidence (the value the acceptance threshold is held against). Never throws: an
 * unreadable body, or a failed call's, is no picks.
 */
object JevAnswers {
    /** The answers by question key, in the body's order; null when there is no readable answer. */
    fun parse(response: String?): Map<String, JevAnswer>? = response?.let { body ->
        runCatching {
            val answers = engineJson.readValue<Map<String, Any?>>(body)["answers"] as? Map<*, *> ?: return null
            answers.entries.mapNotNull { (k, v) ->
                val a = v as? Map<*, *> ?: return@mapNotNull null
                k.toString() to JevAnswer(
                    type = a["type"] as? String ?: return@mapNotNull null,
                    noul = (a["noul"] as? Number)?.toDouble(),
                    choice = a["choice"] as? String,
                    score = (a["score"] as? Number)?.toDouble(),
                    probabilities = (a["probabilities"] as? Map<*, *>).orEmpty().entries
                        .mapNotNull { (o, p) -> (p as? Number)?.let { o.toString() to it.toDouble() } }.toMap(),
                    confidence = (a["confidence"] as? Number)?.toDouble(),
                )
            }.toMap().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /** Each question's pick, refine-pick's in the order kind, mode, tier. */
    fun picks(answers: Map<String, JevAnswer>?): List<JevPick> {
        if (answers == null) return emptyList()
        val order = listOf(RefinePickQuestions.KIND, RefinePickQuestions.MODE, RefinePickQuestions.TIER)
        return answers.entries.sortedBy { order.indexOf(it.key).let { i -> if (i < 0) order.size else i } }.map { (key, a) ->
            when (a.type) {
                "noul" -> JevPick(key, a.type, a.noul?.let { if (it >= 0.5) "yes" else "no" }, a.noul, a.probabilities)
                "score" -> JevPick(key, a.type, RefinePickQuestions.tierOf(a)?.let { if (key == RefinePickQuestions.TIER) "tier $it" else "level $it" },
                    a.confidence, a.probabilities)
                else -> JevPick(key, a.type, a.choice, a.confidence, a.probabilities)
            }
        }
    }

    /** The call's one pick and confidence: a single question's, or the picks joined with ` · ` at the lowest confidence. */
    fun summary(picks: List<JevPick>): Pair<String?, Double?> = when (picks.size) {
        0 -> null to null
        1 -> picks.single().pick to picks.single().confidence
        else -> picks.joinToString(" · ") { it.pick ?: "?" } to picks.mapNotNull { it.confidence }.minOrNull()
    }
}

/**
 * refine-pick's questions (`RefinePicker` in the intake package asks them), here so [JevAnswers.picks] can order and
 * label them without knowing the intake package.
 */
object RefinePickQuestions {
    const val KIND = "kind"; const val MODE = "featureMode"; const val TIER = "tier"

    /** A score's level, 1-based: its most probable level index, else its score rounded; null when it has neither. */
    fun tierOf(answer: JevAnswer): Int? {
        val index = answer.probabilities.entries.filter { it.key.toIntOrNull() != null }.maxByOrNull { it.value }?.key?.toInt()
            ?: answer.score?.let { Math.round(it).toInt() }
        return index?.plus(1)?.coerceIn(1, 10)
    }
}

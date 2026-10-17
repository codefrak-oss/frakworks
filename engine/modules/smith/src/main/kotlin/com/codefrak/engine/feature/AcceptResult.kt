package com.codefrak.engine.feature

import com.codefrak.engine.engineJson
import com.codefrak.engine.smith.PassAlong
import tools.jackson.module.kotlin.readValue

/** One acceptance criterion as checked after the merge: `met` null when nothing here could check it. */
data class Criterion(@PassAlong val criterion: String, val met: Boolean? = null, @PassAlong val how: String = "")

/**
 * What a feature's accept Smith writes after the merge, or what the engine
 * recorded from the user's answer on an instance started before #583, when
 * confirm mode's acceptance was the user's (`ReadAcceptanceDelegate`): `accepted`,
 * `unmet` (at least one criterion is not), or `unverified` (none unmet, some
 * unchecked; the ticket closes saying so). `rejected` is the user's no.
 */
data class AcceptResult(val outcome: String, @PassAlong val summary: String? = null, val criteria: List<Criterion> = emptyList()) {
    fun problems(): List<String> = buildList {
        if (outcome !in SMITH_OUTCOMES) add("outcome '$outcome' is not one of $SMITH_OUTCOMES")
        if (summary.isNullOrBlank()) add("an accept result needs a summary")
        if (outcome == "unmet" && criteria.none { it.met == false }) add("outcome 'unmet' needs a criterion with met: false")
        if (outcome != "unmet" && criteria.any { it.met == false }) add("a criterion is not met; the outcome is 'unmet'")
        if (outcome == "accepted" && criteria.any { it.met == null }) add("a criterion was not checked; the outcome is 'unverified'")
    }

    /** The criteria as a markdown checklist. */
    fun checklist(): String = criteria.joinToString("\n") {
        val mark = when (it.met) { true -> "x"; false -> " "; null -> "?" }
        "- [$mark] ${it.criterion.trim()}" + it.how.trim().takeIf { h -> h.isNotEmpty() }?.let { h -> ": $h" }.orEmpty()
    }

    companion object {
        val SMITH_OUTCOMES = setOf("accepted", "unmet", "unverified")
        fun parse(bytes: ByteArray): AcceptResult = engineJson.readValue(bytes)
        fun bytes(result: AcceptResult): ByteArray = engineJson.writeValueAsBytes(result)
    }
}

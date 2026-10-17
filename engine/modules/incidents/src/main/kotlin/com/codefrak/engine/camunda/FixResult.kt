package com.codefrak.engine.camunda

import com.codefrak.engine.engineJson
import com.codefrak.engine.smith.PassAlong
import tools.jackson.module.kotlin.readValue

/**
 * What a fix Smith writes (engine/README.md, "Fix and Propose a fix"): the remedy it picked from
 * the menu, `retry` (the engine retries the incident as `POST /camunda/incidents/{id}/retry` does),
 * or `unfixable`. It cannot touch the engine or Operaton itself; the engine applies the remedy.
 */
data class FixResult(val outcome: String, @PassAlong val diagnosis: String? = null, @PassAlong val summary: String? = null) {
    fun problems(): List<String> = buildList {
        if (outcome !in OUTCOMES) add("outcome '$outcome' is not one of $OUTCOMES")
        if (diagnosis.isNullOrBlank()) add("a fix result needs a diagnosis")
    }

    companion object {
        val OUTCOMES = setOf("retry", "unfixable")
        fun parse(bytes: ByteArray): FixResult = engineJson.readValue(bytes)
    }
}

/**
 * What a diagnose Smith writes (Propose a fix): a proposal `summary`, which the engine keeps to
 * [MAX_PROPOSAL] characters ([proposal]), and the longer `diagnosis` behind it. It changes nothing.
 */
data class DiagnoseResult(val outcome: String, val summary: String? = null, @PassAlong val diagnosis: String? = null) {
    fun problems(): List<String> = buildList {
        if (outcome != "proposed") add("outcome '$outcome' is not proposed")
        if (summary.isNullOrBlank()) add("a diagnose result needs a summary")
    }

    companion object {
        fun parse(bytes: ByteArray): DiagnoseResult = engineJson.readValue(bytes)
    }
}

/** The most a proposal summary may be: what the ticket view's text area shows. */
const val MAX_PROPOSAL = 40

/**
 * A diagnose Smith's summary as the engine stores it: on one line, and at most [MAX_PROPOSAL]
 * characters. A longer one is truncated, not refused (the run already cost what it cost): its
 * first 39 characters and an ellipsis.
 */
fun proposal(summary: String): String {
    val line = summary.trim().replace(Regex("\\s+"), " ")
    return if (line.length <= MAX_PROPOSAL) line else line.take(MAX_PROPOSAL - 1).trimEnd() + "…"
}

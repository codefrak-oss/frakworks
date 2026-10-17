package com.codefrak.engine.research

import com.codefrak.engine.engineJson
import com.codefrak.engine.feature.PlanQuestion
import com.codefrak.engine.proposal.PitchRules
import com.codefrak.engine.smith.PassAlong
import tools.jackson.module.kotlin.readValue

/** The answer a research Smith wrote: `answer.md` (the record) and `answer.html` (one self-contained page). */
data class ResearchAnswer(val md: String? = null, val html: String? = null)

/**
 * What a research Smith writes (smith/run-smith.sh, research mode; engine/README.md, "Research"): `answered`,
 * with the [answer] inline (the engine keeps it, nothing is committed), a [summary] and the [questions] it
 * assumed an answer to; or `ask` when it cannot answer without guessing.
 */
data class ResearchResult(
    val outcome: String,
    val answer: ResearchAnswer? = null,
    @PassAlong val summary: String? = null,
    val questions: List<PlanQuestion> = emptyList(),
    @PassAlong val question: String? = null,
) {
    /** The shape: an `answered` has a non-blank md and html (the html passing [PitchRules]) and a summary; an `ask` asks. */
    fun problems(): List<String> = buildList {
        if (outcome !in OUTCOMES) add("outcome '$outcome' is not one of $OUTCOMES")
        if (outcome == "answered") {
            if (answer?.md.isNullOrBlank()) add("outcome 'answered' needs answer.md: the question, the answer, the citations and the date")
            if (answer?.html.isNullOrBlank()) add("outcome 'answered' needs answer.html: one self-contained page by ${PitchRules.GUIDE}")
            else addAll(PitchRules.problems(PAGE, answer!!.html!!))
            if (summary.isNullOrBlank()) add("outcome 'answered' needs a summary")
            addAll(PlanQuestion.problems(questions))
        }
        if (outcome == "ask" && question.isNullOrBlank()) add("outcome 'ask' needs a question")
    }

    companion object {
        val OUTCOMES = setOf("answered", "ask")
        /** The page's name in a rule's message. */
        const val PAGE = "answer.html"

        fun parse(bytes: ByteArray): ResearchResult = engineJson.readValue(bytes)
    }
}

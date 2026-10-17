package com.codefrak.engine.research

import com.codefrak.engine.engineJson
import tools.jackson.module.kotlin.readValue

/** One round of a research thread as the brief carries it: the round, its summary and its `answer.md`. */
data class ResearchRound(val round: Int, val summary: String, val md: String)

/** The thread so far (`Vars.RESEARCH_THREAD`), as [ResearchRound]s; empty before the first answer. */
fun researchThread(bytes: ByteArray?): List<ResearchRound> = bytes?.let { engineJson.readValue<List<ResearchRound>>(it) }.orEmpty()

/** The asker's ack on a research answer: the verb, and the text given with it (a why, a directory, or the follow-up itself). */
data class Ack(val verb: String, val text: String?)

/**
 * The verbs the asker acks a research answer with (engine/README.md, "Research"): `accept[: why]`, `save[: dir]`,
 * `drop[: why]`; anything else is a [FOLLOW_UP], the text being the question for the next round. Unlike a
 * proposal's [com.codefrak.engine.proposal.DecisionVerb], nothing is ever "unclear": free text means "ask the
 * Smith", never "ask again". [read] combines the rule with Jev's pick ([ReadAckDelegate]).
 */
object AckVerb {
    const val ACCEPT = "accept"
    const val SAVE = "save"
    const val DROP = "drop"
    const val FOLLOW_UP = "follow-up"

    private val VERB = Regex("""^(accept|save|drop)\b\s*(?:[:,\-–—]\s*(.*))?$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** The rule's reading: a verb at the start of the answer (case and leading punctuation aside), else a follow-up with the whole text. */
    fun parse(answer: String): Ack {
        val text = answer.trimStart { !it.isLetterOrDigit() }.trim()
        val match = VERB.matchEntire(text) ?: return Ack(FOLLOW_UP, text.takeIf { it.isNotEmpty() })
        return Ack(match.groupValues[1].lowercase(), match.groupValues[2].trim().takeIf { it.isNotEmpty() })
    }

    /** The verbs Jev chooses among, as the user writes them. */
    val WRITTEN = linkedMapOf(ACCEPT to "accept", SAVE to "save", DROP to "drop", FOLLOW_UP to "follow-up")

    /** One reading: the ack, what the engine did with Jev's pick, and what it learned from the rule. */
    data class Reading(val ack: Ack, val action: String, val outcome: String?)

    /**
     * The ack on `answer` given Jev's `pick` (a verb, or null for no answer) and its `confidence`. A verb the
     * user wrote as the rule reads it is never overridden. A pick at `floor` or above is taken: a verb picked
     * from free text carries no why or directory (the text was not written as one); a follow-up carries the
     * whole text. Below the floor, or with no pick, the rule's reading stands, which for anything but a written
     * verb is a follow-up: never a re-ask.
     */
    fun read(answer: String, pick: String?, confidence: Double?, floor: Double): Reading {
        val rule = parse(answer)
        val written = rule.verb.takeIf { it != FOLLOW_UP }
        val outcome = written?.let { "verb as written: $it${if (pick == null || pick == it) "" else " (jev picked $pick)"}" }
        if (pick == null || confidence == null) return Reading(rule, "no answer, rule: ${rule.verb}", outcome)
        return when {
            confidence < floor -> Reading(rule, "below floor $floor, rule: ${rule.verb}", outcome)
            written != null && written != pick -> Reading(rule, "kept the verb as written: $written", outcome)
            pick == FOLLOW_UP -> Reading(Ack(FOLLOW_UP, rule.text ?: answer.trim()), "took follow-up", outcome)
            else -> Reading(Ack(pick, rule.text.takeIf { written != null }), "took $pick", outcome)
        }
    }
}

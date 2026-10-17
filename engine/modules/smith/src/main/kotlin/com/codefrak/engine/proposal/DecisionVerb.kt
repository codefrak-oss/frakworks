package com.codefrak.engine.proposal

/** A decision on a proposal: the verb, and the text given with it (the why, or for `revise` what to change). */
data class Decision(val verb: String, val why: String?)

/**
 * The verbs the user decides a proposal with (engine/README.md, "Proposal").
 * [parse] is the rule, the reading when Jev gives none: case does not matter, and leading punctuation is
 * skipped: `accept[: why]`, `accept and build[: why]`, `accept and build auto[: why]`, `revise: <text>`,
 * `shelve: <why>`, `reject: <why>`. A missing required text, or anything else, is [UNCLEAR], and the flow
 * asks again. [read] combines it with Jev's pick ([ReadDecisionDelegate]).
 */
object DecisionVerb {
    const val ACCEPT = "accept"
    const val BUILD = "build"
    const val BUILD_AUTO = "build-auto"
    const val REVISE = "revise"
    const val SHELVE = "shelve"
    const val REJECT = "reject"
    const val UNCLEAR = "unclear"

    private val VERB = Regex(
        """^(accept\s+and\s+build\s+auto|accept\s+and\s+build|accept|revise|shelve|reject)\s*(?::\s*(.*))?$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    fun parse(answer: String): Decision {
        val match = VERB.matchEntire(answer.trimStart { !it.isLetterOrDigit() }.trim()) ?: return Decision(UNCLEAR, null)
        val why = match.groupValues[2].trim().takeIf { it.isNotEmpty() }
        val verb = when (match.groupValues[1].lowercase().split(Regex("""\s+""")).joinToString(" ")) {
            "accept and build auto" -> BUILD_AUTO
            "accept and build" -> BUILD
            "accept" -> ACCEPT
            else -> match.groupValues[1].lowercase()
        }
        if (verb in setOf(REVISE, SHELVE, REJECT) && why == null) return Decision(UNCLEAR, null)
        return Decision(verb, why)
    }

    /** Each verb as the user writes it, by the verb the flow uses. */
    val WRITTEN = linkedMapOf(ACCEPT to "accept", BUILD to "accept and build", BUILD_AUTO to "accept and build auto",
        REVISE to "revise", SHELVE to "shelve", REJECT to "reject")

    private val NEEDS_WHY = setOf(REVISE, SHELVE, REJECT)

    /** A written verb at the start, longest first, then any `:`, `-` or `,` after it. */
    private val LEADING_VERB = Regex(
        """^(accept\s+and\s+build\s+auto|accept\s+and\s+build|accept|revise|shelve|reject)\b\s*[:,\-–—]?""",
        RegexOption.IGNORE_CASE,
    )

    /** The text given with a verb: `answer` minus leading punctuation and a leading verb, or null when nothing is left. */
    fun why(answer: String): String? {
        val text = answer.trimStart { !it.isLetterOrDigit() }.trim()
        return text.replaceFirst(LEADING_VERB, "").trim().takeIf { it.isNotEmpty() }
    }

    /** One reading of an answer: the decision, what the engine did with Jev's pick, and what it learned from the rule. */
    data class Reading(val decision: Decision, val action: String, val outcome: String?)

    /**
     * The decision on `answer`, given Jev's `pick` (a verb, or null for no answer) and its `confidence`. A pick
     * below its floor (`buildFloor` for the two build verbs, else `floor`), or none, leaves the rule's reading,
     * so the flow behaves as it did before Jev. A pick at its floor or above is taken, with the [why] by string
     * handling, and is [UNCLEAR] when revise, shelve or reject has no text left; but a verb the user wrote as
     * the rule reads it is never overridden. The outcome is the rule's reading when the user wrote a verb.
     */
    fun read(answer: String, pick: String?, confidence: Double?, floor: Double, buildFloor: Double): Reading {
        val rule = parse(answer)
        val written = rule.verb.takeIf { it != UNCLEAR }
        val outcome = written?.let { "verb as written: $it${if (pick == null || pick == it) "" else " (jev picked $pick)"}" }
        if (pick == null || confidence == null) return Reading(rule, "no answer, rule: ${rule.verb}", outcome)
        val at = if (pick == BUILD || pick == BUILD_AUTO) buildFloor else floor
        return when {
            confidence < at -> Reading(rule, "below floor $at, rule: ${rule.verb}", outcome)
            written != null && written != pick -> Reading(rule, "kept the verb as written: $written", outcome)
            pick in NEEDS_WHY && why(answer) == null -> Reading(Decision(UNCLEAR, null), "re-asked: $pick with no text", outcome)
            else -> Reading(Decision(pick, why(answer)), "took $pick", outcome)
        }
    }

    /** The doc's `Status:` for a final decision. */
    fun status(verb: String): String = when (verb) {
        ACCEPT, BUILD, BUILD_AUTO -> "accepted"
        SHELVE -> "shelved"
        REJECT -> "rejected"
        else -> error("'$verb' is not a final decision")
    }
}

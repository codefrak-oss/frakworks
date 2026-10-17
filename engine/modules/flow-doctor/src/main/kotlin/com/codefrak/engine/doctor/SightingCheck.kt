package com.codefrak.engine.doctor

import com.codefrak.engine.github.IssueListing
import com.codefrak.engine.intake.DuplicateCheck
import com.codefrak.engine.jev.JevAnswer
import com.codefrak.engine.jev.JevQuestion

/**
 * Jev's look at a new signature before the doctor files it (use case `doctor-sighting`; engine/README.md, "Doctor"):
 * one `choice` whose options are the open `bug`
 * issues of the repo the sighting would be filed in and the doctor's bugs there closed in the last [CLOSED_DAYS]
 * days, at most [MAX] of them, plus `new`. A pick of a bug at the floor or above lumps the sighting into it; `new`,
 * a pick below the floor or no answer files it as before. The call's `outcome` is written later, by what humans do
 * with the bug ([kept], [moved], [filedKept], [filedDuplicate]), which makes `engine.jev_call` an evaluation set.
 */
object SightingCheck {
    const val USE_CASE = "doctor-sighting"
    const val QUESTION = "sighting"
    const val NEW = "new"

    /** 255 options at most, one of them `new`. */
    const val MAX = 254

    /** How far back the doctor's closed bugs are options. */
    const val CLOSED_DAYS = 30L

    /** Characters of each option's description, and of the sighting's diagnosis. */
    const val DESCRIPTION_CHARS = 240
    const val DIAGNOSIS_CHARS = 4000

    /** One bug as an option: `#n`, in the repo the sighting would be filed in. */
    data class Candidate(val number: Long, val description: String) {
        val key get() = "#$number"
    }

    /** A doctor bug in the repo closed within [CLOSED_DAYS] days: its issue, its title when GitHub gave one, and its sightings' summaries. */
    data class ClosedBug(val issue: Long, val title: String?, val summaries: List<String>)

    /**
     * The open issues labelled `bug` (newest update first, as `DuplicateCheck` describes them), then the closed doctor
     * bugs (newest first) that are not among them, cut to [MAX].
     */
    fun candidates(open: List<IssueListing>, closed: List<ClosedBug>): List<Candidate> {
        val bugs = open.filter { "bug" in it.labels }.map { Candidate(it.number, DuplicateCheck.describe(it)) }
        val numbers = bugs.map { it.number }.toSet()
        val recent = closed.filter { it.issue !in numbers }.map { bug ->
            val seen = bug.summaries.map { it.lineSequence().firstOrNull { l -> l.isNotBlank() }?.trim().orEmpty() }
                .filter { it.isNotEmpty() }.distinct()
            Candidate(bug.issue, (listOfNotNull("Closed doctor bug", bug.title).joinToString(": ") +
                (if (seen.isEmpty()) "" else "; seen as: " + seen.joinToString("; "))).take(DESCRIPTION_CHARS))
        }
        return (bugs + recent).distinctBy { it.number }.take(MAX)
    }

    fun question(candidates: List<Candidate>): JevQuestion = JevQuestion.choice(
        "The state is a ticket the engine's doctor caught stuck, as its Smith described it. Every option but `new` is a " +
            "bug in the repo it would be filed in, by its number: an open issue labelled bug, or a bug the doctor filed and " +
            "that closed recently. Is this stuck ticket the same defect as one of these bugs? Same defect means the same fix " +
            "would have prevented it; the same symptom with a different cause is `new`.",
        candidates.associate { it.key to it.description } + (NEW to "None of these: a different defect"),
    )

    /** The sighting as Jev reads it: `where`, the Smith's summary and diagnosis, the leaves, each incident's shape, the stuck flow's kind. */
    fun state(new: NewSighting, diagnosis: String?, flow: String?) = mapOf(
        "where" to new.where,
        "summary" to new.summary,
        "diagnosis" to diagnosis?.take(DIAGNOSIS_CHARS),
        "leaves" to new.signature.leaves,
        "incidents" to new.signature.shape,
        "flow" to flow,
    )

    /** The call's `action`, and the bug it lumps into: null for `new`, an option that is no candidate, a pick below [floor] or no answer. */
    fun read(answer: JevAnswer?, candidates: List<Candidate>, floor: Double): Pair<String, Candidate?> {
        answer ?: return "no answer: new intake" to null
        val pick = answer.choice?.let { c -> candidates.firstOrNull { it.key == c } }
        val confidence = answer.confidence
        return when {
            pick == null -> "${answer.choice} (${DuplicateCheck.pct(confidence)}): new intake" to null
            confidence == null || confidence < floor -> "${pick.key} below floor $floor (${DuplicateCheck.pct(confidence)}): new intake" to null
            else -> "${pick.key} (${DuplicateCheck.pct(confidence)}): lumped" to pick
        }
    }

    /** A bug as an outcome names it: `#n` in [home], `owner/name#n` elsewhere, `no bug` for none, `filing` while its intake refines. */
    fun name(home: String, repo: String?, issue: Long?): String = when {
        repo == null -> "no bug"
        issue == null -> "filing"
        repo.equals(home, ignoreCase = true) -> "#$issue"
        else -> "$repo#$issue"
    }

    /** A Jev lump into [bug] that was still on it when it closed. */
    fun kept(bug: String) = "lumped $bug: kept"

    /** A Jev lump into [from] a human moved to [to]. */
    fun moved(from: String, to: String, by: String) = "lumped $from: moved to $to by @$by"

    /** A `new` whose bug [filed] closed, not as a duplicate. */
    fun filedKept(filed: String) = "new: filed $filed, kept"

    /** A `new` whose bug [filed] a human closed as a duplicate of [of]: a miss. */
    fun filedDuplicate(filed: String, of: String, by: String) = "new: filed $filed, closed as duplicate of $of by @$by"

    /** A `new` whose sighting a human moved from the bug it filed to [to]: a miss too. */
    fun filedMoved(filed: String, to: String, by: String) = "new: filed $filed, moved to $to by @$by"

    private val KEPT = Regex("""^lumped (#\d+): kept""")
    private val TO = Regex("""(?:moved to|closed as duplicate of) (#\d+) by """)

    /**
     * The option an outcome shows was right, for `GET /jev/summary`: the bug a lump was kept on, or the bug a sighting
     * was moved to or its filed bug closed as a duplicate of; `new` for a filed bug kept. Null when the outcome names
     * no option (moved off any bug, to another repo's bug, or a bug still filing).
     */
    fun truth(outcome: String): String? {
        KEPT.find(outcome)?.let { return it.groupValues[1] }
        TO.find(outcome)?.let { return it.groupValues[1] }
        return if (outcome.startsWith("new: filed ") && outcome.endsWith(", kept")) NEW else null
    }
}

package com.codefrak.engine.friction

import com.codefrak.engine.github.ClosingPull
import com.codefrak.engine.github.IssueOrPull
import java.time.OffsetDateTime

/** Where a fixing ticket stands on GitHub: `state` `OPEN` or `CLOSED`, GitHub's `stateReason` lowercased, its closing PRs. */
data class FixTicket(val state: String, val stateReason: String?, val closedBy: List<ClosingPull>)

/** What [FrictionFixRules.transition] makes of a link: [state] (null: the link is cleared, the group open) and the merge time when fixed. */
data class FixTransition(val state: String?, val mergedAt: OffsetDateTime? = null, val why: String? = null)

/** The pure part of the fixed-by link: the phrase a ticket names groups by, and how a link follows its ticket. */
object FrictionFixRules {
    /** The phrase the sync searches tickets' bodies for. */
    const val PHRASE = "friction group"

    /** `friction group g6`, `Friction groups g6, g9 and g12`: the phrase, then one or several `g<id>`. */
    private val NAMING = Regex("""friction\s+groups?\s+(g\d+(?:\s*(?:,|and|&|/)\s*g\d+)*)""", RegexOption.IGNORE_CASE)
    private val ID = Regex("""g(\d+)""", RegexOption.IGNORE_CASE)

    /** Every group id [body] names as `friction group g<id>`, in order, once each. */
    fun groupsNamed(body: String?): List<Long> = NAMING.findAll(body.orEmpty())
        .flatMap { m -> ID.findAll(m.groupValues[1]).mapNotNull { it.groupValues[1].toLongOrNull() } }.distinct().toList()

    /**
     * Where a link in [current] state goes once its ticket is [ticket]: open, `fix-filed`; closed as completed with a
     * `MERGED` closing PR, `fixed` at that PR's merge time (the latest, with several); closed as not planned (or a
     * duplicate), cleared; closed as completed with no merged PR, `fix-filed` still, with [FixTransition.why] to log.
     */
    fun transition(current: String?, ticket: FixTicket): FixTransition {
        if (ticket.state != "CLOSED") return FixTransition(FrictionFix.FILED)
        if (ticket.stateReason != null && ticket.stateReason != "completed") return FixTransition(null, why = "closed as ${ticket.stateReason}")
        val merged = ticket.closedBy.filter { it.state == "MERGED" }
        if (merged.isEmpty()) return FixTransition(FrictionFix.FILED, why = "closed as completed with no merged PR" +
            (if (current == FrictionFix.FIXED) "; was fixed" else ""))
        return FixTransition(FrictionFix.FIXED, merged.mapNotNull { it.mergedAt }.maxOrNull())
    }

    /**
     * Where a link goes once the issue or PR it names, read by number, is [ref]: open, `fix-filed`. An issue closed
     * as completed is `fixed` at its latest merged closing PR's merge time, else at its `closedAt`; closed as not
     * planned (or a duplicate), cleared. A merged PR is `fixed` at `mergedAt`; one closed unmerged is cleared.
     */
    fun transition(ref: IssueOrPull): FixTransition {
        if (ref.pull) return when (ref.state) {
            "MERGED" -> FixTransition(FrictionFix.FIXED, ref.mergedAt)
            "CLOSED" -> FixTransition(null, why = "PR closed unmerged")
            else -> FixTransition(FrictionFix.FILED)
        }
        if (ref.state != "CLOSED") return FixTransition(FrictionFix.FILED)
        if (ref.stateReason != null && ref.stateReason != "completed") return FixTransition(null, why = "closed as ${ref.stateReason}")
        val merged = ref.closedBy.filter { it.state == "MERGED" }.mapNotNull { it.mergedAt }.maxOrNull()
        return FixTransition(FrictionFix.FIXED, merged ?: ref.closedAt)
    }

    /** Whether a note at [notedAt] recurs a fix merged at [mergedAt]: only a note after the merge does. */
    fun recurs(notedAt: OffsetDateTime, mergedAt: OffsetDateTime?): Boolean = mergedAt != null && notedAt.isAfter(mergedAt)
}

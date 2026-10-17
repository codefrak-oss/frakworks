package com.codefrak.engine.friction

import java.time.OffsetDateTime

/** A group's two-things bug (V43; engine/README.md, "Friction notes", "Two things"): its repo, issue (null while its intake refines), `state` (`filing`, `open`, `closed`) and intake. */
data class FrictionBugRef(val groupId: Long, val repo: String, val issue: Long?, val state: String, val intakeId: String?) {
    /** The `bug` of a `GET /friction/groups` row. */
    fun json(): Map<String, Any?> = linkedMapOf("repo" to repo, "issue" to issue, "state" to state)

    companion object {
        const val FILING = "filing"
        const val OPEN = "open"
        const val CLOSED = "closed"
    }
}

/**
 * A group's fixed-by link (V44; engine/README.md, "Friction notes", "Fixed by"): the ticket that fixes it, its
 * `state` ([FILED] or [FIXED]), the fixing PR's merge time once fixed, and the first note after that merge.
 */
data class FrictionFix(val groupId: Long, val repo: String, val issue: Long, val state: String,
                       val mergedAt: OffsetDateTime? = null, val recurredAt: OffsetDateTime? = null) {
    /** The `fix` of a `GET /friction/groups` row. */
    fun json(): Map<String, Any?> = linkedMapOf("repo" to repo, "issue" to issue, "state" to state,
        "recurredAt" to recurredAt?.let { FrictionSummaries.utc(it) })

    /** The group's state: [RECURRED] when a note came after the merge, else [state]. */
    val groupState: String get() = if (state == FIXED && recurredAt != null) RECURRED else state

    companion object {
        /** No link: the group is open. */
        const val OPEN = "open"
        const val FILED = "fix-filed"
        const val FIXED = "fixed"
        const val RECURRED = "recurred"
        /** No ticket yet: an intake is linked as the candidate fix ([PendingFix]). */
        const val PENDING = "fix-pending"
        /** Every group that is not [FIXED], for `state=unsolved`. */
        const val UNSOLVED = "unsolved"
        val STATES = listOf(OPEN, PENDING, FILED, FIXED, RECURRED)

        /** Whether a group in [state] passes the `state` filter [wanted]: that state, or for [UNSOLVED] any but [FIXED]. */
        fun passes(state: String, wanted: String): Boolean = if (wanted == UNSOLVED) state != FIXED else state == wanted

        /** A group's state from its link, or [PENDING] with only a pending intake: [OPEN] without either. */
        fun stateOf(fix: FrictionFix?, pending: PendingFix? = null): String = fix?.groupState ?: pending?.let { PENDING } ?: OPEN
    }
}

/**
 * A group's pending candidate fix (V53; engine/README.md, "Friction notes", "Fixed by"): [intake], the id `submit_ask` /
 * `POST /intake` answered, filing in [repo], that has not filed its ticket yet. It becomes a [FrictionFix] when it does.
 */
data class PendingFix(val groupId: Long, val intake: String, val repo: String) {
    /** The `fix` of a `GET /friction/groups` row while pending: no issue yet. */
    fun json(): Map<String, Any?> = linkedMapOf("intake" to intake, "repo" to repo, "issue" to null, "state" to FrictionFix.PENDING)
}

/** A user's or First mate's link and clear ([FrictionFixes]); an interface so the MCP tool is tested without Spring. */
interface FrictionFixLinks {
    /** Group [groupId] is fixed by [repo]#[issue]; null for no such group. */
    fun link(groupId: Long, repo: String, issue: Long, by: String): FrictionGroup?
    /** Group [groupId]'s link goes, a pending intake's too; null for no such group. */
    fun clear(groupId: Long, by: String): FrictionGroup?
    /**
     * Group [groupId] (or, with [noteId], that note's group) has intake [intake] as its candidate fix; refused with
     * [FixIntakeRefused] for an unknown group, note or intake, an ungrouped note, or a group with a ticket link.
     */
    fun linkIntake(groupId: Long?, noteId: String?, intake: String, by: String): FrictionGroup
}

/** A refused [FrictionFixLinks.linkIntake]: [notFound] for an unknown group, note or intake (404), else a conflict (409). */
class FixIntakeRefused(message: String, val notFound: Boolean) : RuntimeException(message)

/** The fixed-by links of groups by id ([FrictionGroupFixes.fixesOf]), for read_friction_notes. */
fun interface FrictionFixReads {
    fun fixesOf(groupIds: Collection<Long>): Map<Long, FrictionFix>
    /** The pending intakes of groups by id ([FrictionGroupFixes.pendingOf]). */
    fun pendingOf(groupIds: Collection<Long>): Map<Long, PendingFix> = emptyMap()

    companion object {
        val NONE = FrictionFixReads { emptyMap() }
    }
}

/**
 * What one [FrictionFixes.sync] pass did: the groups it [linked], the links whose state it [changed] (`to` null: the
 * link was cleared), the recurrences it marked ([recurred]: always empty, a recurrence is marked when a note joins,
 * [FrictionGroupFixes.recur]) and everything it [skipped], with the reason it logs.
 */
data class FixSyncReport(
    val linked: List<Linked> = emptyList(), val changed: List<Changed> = emptyList(),
    val recurred: List<Long> = emptyList(), val skipped: List<Skipped> = emptyList(),
) {
    data class Linked(val groupId: Long, val repo: String, val issue: Long, val state: String)
    data class Changed(val groupId: Long, val repo: String, val issue: Long?, val from: String, val to: String?, val mergedAt: OffsetDateTime?)
    data class Skipped(val groupId: Long?, val repo: String, val issue: Long?, val reason: String)

    /** The report as JSON, `mergedAt` an ISO instant. */
    fun json(): Map<String, Any?> = linkedMapOf(
        "linked" to linked.map { linkedMapOf("groupId" to it.groupId, "repo" to it.repo, "issue" to it.issue, "state" to it.state) },
        "changed" to changed.map { linkedMapOf("groupId" to it.groupId, "repo" to it.repo, "issue" to it.issue, "from" to it.from,
            "to" to it.to, "mergedAt" to it.mergedAt?.toInstant()?.toString()) },
        "recurred" to recurred,
        "skipped" to skipped.map { linkedMapOf("groupId" to it.groupId, "repo" to it.repo, "issue" to it.issue, "reason" to it.reason) },
    )
}

/** A pass of the fix sync was asked for while one runs ([FrictionFixes.syncNow]). */
class FixSyncRunning : RuntimeException("a friction fix sync pass is already running; try again when it finishes")

/** One on-demand pass of the fix sync ([FrictionFixes.syncNow]); an interface so the MCP tool is tested without Spring. */
fun interface FrictionFixSync {
    /** Runs one pass and answers its report; throws [FixSyncRunning] when a pass is already running. */
    fun syncNow(): FixSyncReport
}

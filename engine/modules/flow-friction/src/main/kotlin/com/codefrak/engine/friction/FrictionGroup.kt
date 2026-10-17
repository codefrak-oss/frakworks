package com.codefrak.engine.friction

import java.time.OffsetDateTime

/** How a note came to be in its group (`engine.friction_note.grouped_by`). */
object GroupedBy {
    /** Founded the group: a new key with no groups, Jev's `new`, or a pick below the floor. */
    const val NEW = "new"
    /** Its key was already one of the group's signatures. */
    const val EXACT = "exact"
    /** Jev picked the group at the floor or above. */
    const val JEV = "jev"
    /** No answer from Jev for `engine.friction.group-give-up`: founded its own group. */
    const val FALLBACK = "fallback"
    /** No usable jev account: founded its own group at once. */
    const val NO_JEV = "no-jev"
    /** A user pulled it out of its group into a new one ([FrictionCorrections.pullOut]); `grouped_login` says who. */
    const val USER = "user"
}

/** One `friction-group` call the 30-day sweep looks at: the call, its action, and the note's group and `grouped_by` now. */
data class UnjudgedCall(val call: String, val action: String?, val groupId: Long, val groupedBy: String)

/** An ungrouped note due for the drain, with how many group attempts it has had. */
data class PendingNote(val note: FrictionNote, val attempts: Int)

/** One group as a Jev option: its id, title and newest member's time (its creation when it has none). */
data class GroupOption(val id: Long, val title: String, val newest: OffsetDateTime)

/**
 * One group of `GET /friction/groups`: its [members] passing the filters, newest first, and its [total] members.
 * [newest] and [oldest] are the group's creation when it has no member (a group just merged out of, read once more).
 */
data class FrictionGroup(val id: Long, val title: String, val createdAt: OffsetDateTime, val members: List<FrictionNote>, val total: Int,
                         /** The group's two-things bug, null without one. */
                         val bug: FrictionBugRef? = null,
                         /** The group's fixed-by link, null without one. */
                         val fix: FrictionFix? = null,
                         /** The group's pending intake, null without one; never beside [fix]. */
                         val pending: PendingFix? = null) {
    /** `open`, `fix-pending`, `fix-filed`, `fixed` or `recurred` ([FrictionFix.stateOf]). */
    val state: String get() = FrictionFix.stateOf(fix, pending)

    val newest: OffsetDateTime get() = members.firstOrNull()?.notedAt ?: createdAt
    val oldest: OffsetDateTime get() = members.lastOrNull()?.notedAt ?: createdAt

    /** The members' majority kind, [MIXED] on a tie (and with no member). */
    val kind: String get() {
        val counts = members.groupingBy { it.kind }.eachCount()
        val top = counts.values.maxOrNull() ?: return MIXED
        return counts.entries.filter { it.value == top }.let { if (it.size == 1) it.single().key else MIXED }
    }

    /** Each member's repo once, in first-seen order; a First mate note with none adds nothing. */
    val repos: List<String> get() = members.mapNotNull { it.repo }.distinct()

    /** The highest severity among the members, null when none has one. */
    val severity: String? get() = members.mapNotNull { it.severity }.maxByOrNull { FrictionInput.SEVERITIES.indexOf(it) }

    /** The JSON row, its members as [row] renders a note. */
    fun json(row: (FrictionNote) -> Map<String, Any?>): Map<String, Any?> = linkedMapOf(
        "id" to id, "title" to title, "count" to members.size, "total" to total,
        "newest" to FrictionSummaries.utc(newest), "oldest" to FrictionSummaries.utc(oldest),
        "kind" to kind, "repos" to repos, "severity" to severity, "bug" to bug?.json(),
        "fix" to (fix?.json() ?: pending?.json()), "state" to state, "members" to members.map(row),
    )

    companion object {
        const val MIXED = "mixed"
        /** Characters of a group's title: the founder's summary line, cut. */
        const val TITLE_CHARS = 240

        /** The title a note founds a group with: its summary's first non-blank line, cut to [TITLE_CHARS]. */
        fun title(summary: String): String =
            (summary.lineSequence().firstOrNull { it.isNotBlank() } ?: summary).trim().take(TITLE_CHARS)
    }
}

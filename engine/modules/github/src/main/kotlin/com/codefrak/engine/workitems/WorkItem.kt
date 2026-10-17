package com.codefrak.engine.workitems

import java.time.OffsetDateTime

/**
 * An engine work item (#2568, engine/docs/work-items.md): a ticket held as an `engine.work_item` row instead of a GitHub
 * issue. [trusted] is false for text from outside (an imported issue), which later parts render fenced. [ticket] is its
 * ticket number (V94, from 100000 up): what its flows, branches and events name it by ([WorkItemTickets]). [author] is
 * who asked for it, a login or `engine`.
 */
data class WorkItem(
    val id: String,
    val repo: String,
    val description: String,
    val kind: String,
    val tier: Int?,
    val mode: String?,
    val state: String,
    val source: WorkItemSourceRef,
    val trusted: Boolean,
    val ticket: Long?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val blockedBy: List<String> = emptyList(),
    val entries: List<WorkItemEntry> = emptyList(),
    /** The one-way published copy's reference (`owner/name#n`), null until the repo publishes it (V89). */
    val publishedRef: String? = null,
    val author: String? = null,
    /** The flags that were labels before V98: a refactoring ticket, authorized, a bug needing a repro or a review. */
    val flags: WorkItemFlags = WorkItemFlags(),
) {
    /** Its labels as a ticket, derived from its columns: its kind, a feature's `feature:<mode>`, then its flags. */
    val labelSet: List<String> get() =
        listOfNotNull(kind, mode?.takeIf { kind == "feature" }?.let { "feature:$it" }) + flags.labels

    /** The ticket's title: the description's first line, without a leading `# `. */
    val title: String get() = description.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().removePrefix("# ").trim()

    /** The description after its title line. */
    val body: String get() = description.lineSequence().dropWhile { it.isBlank() }.drop(1).joinToString("\n").trim()

    /** Not done or cancelled. */
    val open: Boolean get() = state != DONE && state != CANCELLED

    companion object {
        /** `ask` is an ask before refinement ([WorkItemStore.refine] turns it into one of the others). */
        const val ASK = "ask"
        /** A refactoring job's scan ticket (V100): its scan flow starts on it, and the job finds its open one by this kind. */
        const val REFACTOR_SCAN = "refactor-scan"
        /** A ticket filed for the user to triage by hand (V100): no work flow starts on it and publish never sends it to GitHub. */
        const val TRIAGE = "triage"
        val KINDS = listOf("feature", "bug", "chore", "proposal", "research", ASK, REFACTOR_SCAN, TRIAGE)
        val STATES = listOf(BLOCKED, READY, RUNNING, DONE, CANCELLED)
        const val BLOCKED = "blocked"
        const val READY = "ready"
        const val RUNNING = "running"
        const val DONE = "done"
        const val CANCELLED = "cancelled"
    }
}

/** Where a work item came from: [kind] typed (ask, import, split-child, raised), [ref] the source's own reference. */
data class WorkItemSourceRef(val kind: String, val ref: String? = null) {
    companion object {
        const val ASK = "ask"
        const val IMPORT = "import"
        const val SPLIT_CHILD = "split-child"
        const val RAISED = "raised"
        val KINDS = listOf(ASK, IMPORT, SPLIT_CHILD, RAISED)
    }
}

/** One row of an item's conversation, decisions, history or raised work. */
data class WorkItemEntry(val id: String, val kind: String, val author: String, val body: String, val trusted: Boolean,
                         val createdAt: OffsetDateTime) {
    companion object {
        const val CONVERSATION = "conversation"
        const val DECISION = "decision"
        const val HISTORY = "history"
        const val RAISED = "raised"
        val KINDS = listOf(CONVERSATION, DECISION, HISTORY, RAISED)
    }
}

/** What [WorkItemStore.create] takes; the store picks the id, the state and the times. */
data class NewWorkItem(val repo: String, val description: String, val kind: String, val source: WorkItemSourceRef,
                       val trusted: Boolean = source.kind != WorkItemSourceRef.IMPORT, val tier: Int? = null,
                       val mode: String? = null, val ticket: Long? = null, val blockedBy: List<String> = emptyList(),
                       val author: String? = null, val flags: WorkItemFlags = WorkItemFlags())

/** A work item's typed flags (V98), each a label a GitHub issue had; [labels] in a fixed order. */
data class WorkItemFlags(val refactor: Boolean = false, val authorized: Boolean = false, val needsRepro: Boolean = false,
                         val needsReview: Boolean = false) {
    val labels: List<String> get() = listOfNotNull(REFACTOR.takeIf { refactor }, AUTHORIZED.takeIf { authorized },
        NEEDS_REPRO.takeIf { needsRepro }, NEEDS_REVIEW.takeIf { needsReview })

    /** These flags with [label] set to [on]; any other label is unchanged (not a flag). */
    fun with(label: String, on: Boolean = true): WorkItemFlags = when (label) {
        REFACTOR -> copy(refactor = on)
        AUTHORIZED -> copy(authorized = on)
        NEEDS_REPRO -> copy(needsRepro = on)
        NEEDS_REVIEW -> copy(needsReview = on)
        else -> this
    }

    companion object {
        const val REFACTOR = "refactor"
        const val AUTHORIZED = "authorized"
        const val NEEDS_REPRO = "needs-repro"
        const val NEEDS_REVIEW = "needs-review"
        val LABELS = listOf(REFACTOR, AUTHORIZED, NEEDS_REPRO, NEEDS_REVIEW)

        /** The flags among [labels]; the rest are ignored. */
        fun of(labels: Collection<String>): WorkItemFlags = labels.fold(WorkItemFlags()) { f, l -> f.with(l) }
    }
}

/** A store refusal: a bad value, an unknown item or a dependency cycle. */
class WorkItemRefused(message: String) : RuntimeException(message)

package com.codefrak.engine.workitems

/**
 * The engine's own ticket interface (#100031, engine/docs/work-items.md, "Tickets"): a ticket is a work item, addressed
 * by its id, with typed fields where a GitHub issue had labels. engine-api's `WorkItemStore` implements it, and
 * `TicketReads` gives the listing, snapshot and blocker shapes the flows read. It lives in engine-github
 * so the flows below engine-api end tickets through it ([end]); tests use the `FakeTickets` fixture. Every change appends
 * a `history` entry; a refusal is a [WorkItemRefused].
 */
interface Tickets {
    fun create(new: NewWorkItem): WorkItem

    /** A `conversation` entry on [id] by [author]. */
    fun comment(id: String, author: String, body: String): WorkItemEntry

    /** Ends [id]: done or cancelled. Each [TicketStateListener] is told after commit. */
    fun setState(id: String, state: TicketState, why: String)

    /** [kind] is one of [WorkItem.KINDS] other than `ask`. */
    fun setKind(id: String, kind: String)

    /** A feature's mode (`auto` or `confirm`), or null. */
    fun setMode(id: String, mode: String?)

    /** Replaces [id]'s flags ([WorkItemFlags]): the labels a GitHub issue had besides its kind and mode. */
    fun setFlags(id: String, flags: WorkItemFlags)

    /** 1 to 10, or null for none. */
    fun setTier(id: String, tier: Int?)

    /** Replaces [id]'s body under its title, with a `history` entry by [author] naming the old body's revision ([bodyRev]). */
    fun editBody(id: String, body: String, author: String)

    /** Replaces [id]'s title, keeping its body, with a `history` entry by [author]. */
    fun retitle(id: String, title: String, author: String)

    /** [id] waits on [blocker] (same repo, no cycle). */
    fun block(id: String, blocker: String)

    fun unblock(id: String, blocker: String)

    /** [id] with its blockers and entries, or null for an unknown item. */
    fun details(id: String): WorkItem?

    fun conversation(id: String): List<WorkItemEntry>

    /** The item that is [repo]'s ticket [number], or null for none. */
    fun forTicket(repo: String, number: Long): WorkItem?

    /** [repo]'s open items (not done or cancelled), newest first, without their entries: what the scan lists. */
    fun open(repo: String): List<WorkItem>

    /** [repo]'s newest item titled [title] exactly, open or closed, with its entries; null for none. */
    fun newestTitled(repo: String, title: String): WorkItem?

    /** [id]'s kind ([WorkItem.KINDS]), or null for an unknown item. */
    fun kind(id: String): String?

    /** [id]'s mode (a feature's `auto` or `confirm`), or null for none or an unknown item. */
    fun mode(id: String): String?

    /** [id]'s state ([WorkItem.STATES]), or null for an unknown item. */
    fun state(id: String): String?

    /**
     * Ends [repo]'s ticket [number] in [state] ([setState]); false, changing nothing, when it is already done or
     * cancelled. Refused ([WorkItemRefused]) for an unknown ticket. What a flow's close of its issue is.
     */
    fun end(repo: String, number: Long, state: TicketState, why: String): Boolean {
        val item = forTicket(repo, number) ?: throw WorkItemRefused("no ticket $repo#$number")
        if (!item.open) return false
        setState(item.id, state, why)
        return true
    }

    fun history(id: String): List<WorkItemEntry>

    companion object {
        /** A body's revision: `sha256:<hex>` of its UTF-8 bytes, as the brief's lineage names one. */
        fun bodyRev(body: String): String =
            "sha256:" + java.security.MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** The two states [Tickets.setState] ends a ticket in: GitHub's `completed` and `not_planned` close reasons. */
enum class TicketState(val state: String) {
    DONE(WorkItem.DONE),
    CANCELLED(WorkItem.CANCELLED);

    companion object {
        /** The state a close with GitHub's `state_reason` [reason] ends in: `not_planned` is cancelled, anything else done. */
        fun ofReason(reason: String): TicketState = if (reason == "not_planned") CANCELLED else DONE
    }
}

/** Told of every state change of a ticket ([from] to [to], [WorkItem.STATES]) after it commits. */
fun interface TicketStateListener {
    fun onStateChanged(item: WorkItem, from: String, to: String)
}

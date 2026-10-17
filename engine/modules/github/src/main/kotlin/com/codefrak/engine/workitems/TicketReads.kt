package com.codefrak.engine.workitems

import com.codefrak.engine.github.Blocker
import com.codefrak.engine.github.IssueEvent
import com.codefrak.engine.github.IssueListing
import com.codefrak.engine.github.IssueSnapshot
import com.codefrak.engine.github.NOT_PLANNED
import com.codefrak.engine.github.issueUrl

/** [repo]'s ticket [number]'s work item; refused ([WorkItemRefused]) for an unknown ticket. */
fun Tickets.item(repo: String, number: Long): WorkItem =
    forTicket(repo, number) ?: throw WorkItemRefused("no ticket $repo#$number")

/** The engine's `conversation` entry [body] on [repo]'s ticket [number]; refused ([WorkItemRefused]) for an unknown ticket. */
fun Tickets.commentOn(repo: String, number: Long, body: String) {
    comment(item(repo, number).id, ENGINE, body)
}

/** [repo]'s ticket [number] as a flow's approved ticket. */
fun Tickets.snapshot(repo: String, number: Long): IssueSnapshot {
    val item = item(repo, number)
    return IssueSnapshot(number, item.title, item.body, item.author ?: ENGINE)
}

/** [repo]'s open tickets ([open]) as listings, newest first. */
fun Tickets.listings(repo: String): List<IssueListing> = open(repo).mapNotNull { item ->
    val number = item.ticket ?: return@mapNotNull null
    IssueListing(number, item.title, item.author ?: ENGINE, item.labelSet, issueUrl(item.repo, number), item.createdAt, item.updatedAt, item.body)
}

/** Whether [repo]'s ticket [number] is open (not done or cancelled); refused ([WorkItemRefused]) for an unknown ticket. */
fun Tickets.isOpen(repo: String, number: Long): Boolean = item(repo, number).open

/**
 * [repo]'s ticket [number]'s labels ([WorkItem.labelSet]) as `labeled` events by `engine` at its creation, ids 1..n:
 * what the approval checks read. No `unlabeled` or `renamed` event, and no body edit time.
 * Refused ([WorkItemRefused]) for an unknown ticket.
 */
fun Tickets.labelEvents(repo: String, number: Long): List<IssueEvent> {
    val item = item(repo, number)
    return item.labelSet.mapIndexed { i, l -> IssueEvent(i + 1L, "labeled", ENGINE, item.createdAt, l) }
}

/**
 * What [repo]'s ticket [number] is blocked by ([WorkItem.blockedBy]), in the shape the deferred scan takes: `open` while a
 * blocker is open, else `closed` with `completed` for done and `not_planned` for cancelled. None for an unknown ticket.
 */
fun Tickets.blockers(repo: String, number: Long): List<Blocker> =
    forTicket(repo, number)?.blockedBy.orEmpty().mapNotNull { details(it) }.mapNotNull { b ->
        val ticket = b.ticket ?: return@mapNotNull null
        Blocker(b.repo, ticket, if (b.open) "open" else "closed",
            when (b.state) { WorkItem.DONE -> "completed"; WorkItem.CANCELLED -> NOT_PLANNED; else -> null })
    }

/** Sets the flag [label] ([WorkItemFlags.with]) on [repo]'s ticket [number]; refused ([WorkItemRefused]) for an unknown ticket. */
fun Tickets.flag(repo: String, number: Long, label: String) {
    val item = item(repo, number)
    setFlags(item.id, item.flags.with(label))
}

/** [item] as a board listing, its labels its [WorkItem.labelSet]; null for no ticket number. */
fun listingOf(item: WorkItem): IssueListing? {
    val number = item.ticket ?: return null
    return IssueListing(number, item.title, item.author ?: ENGINE, item.labelSet, issueUrl(item.repo, number),
        item.createdAt, item.updatedAt, item.body)
}

/** [repo]'s open tickets as listings, newest first ([Tickets.open]). */
fun Tickets.openListings(repo: String): List<IssueListing> = open(repo).mapNotNull(::listingOf)

/** [repo]'s ticket [number] as a listing while it is open, else null. */
fun Tickets.openListing(repo: String, number: Long): IssueListing? = forTicket(repo, number)?.takeIf { it.open }?.let(::listingOf)

/** Who the engine's own entries are by. */
private const val ENGINE = "engine"

package com.codefrak.engine.intake

import com.codefrak.engine.github.Blocker
import com.codefrak.engine.workitems.Tickets
import com.codefrak.engine.workitems.WorkItem
import com.codefrak.engine.workitems.WorkItemRefused

/** The engine's `conversation` entry [body] on [repo]'s ticket [number]; refused ([WorkItemRefused]) for an unknown ticket. */
internal fun Tickets.commentOn(repo: String, number: Long, body: String) {
    comment(ticket(repo, number).id, ENGINE_AUTHOR, body)
}

/** [repo]'s ticket [number]'s work item; refused ([WorkItemRefused]) for an unknown ticket. */
internal fun Tickets.ticket(repo: String, number: Long): WorkItem =
    forTicket(repo, number) ?: throw WorkItemRefused("no ticket $repo#$number")

/**
 * What [repo]'s ticket [number] is blocked by ([WorkItem.blockedBy]), in the shape the deferred scan takes: `open` while a
 * blocker is open, else `closed` with GitHub's close reason for its end. None for an unknown ticket.
 */
internal fun Tickets.blockers(repo: String, number: Long): List<Blocker> =
    forTicket(repo, number)?.blockedBy.orEmpty().mapNotNull { details(it) }.mapNotNull { b ->
        val ticket = b.ticket ?: return@mapNotNull null
        Blocker(b.repo, ticket, if (b.open) "open" else "closed",
            when (b.state) { WorkItem.DONE -> "completed"; WorkItem.CANCELLED -> "not_planned"; else -> null })
    }

internal const val ENGINE_AUTHOR = "engine"

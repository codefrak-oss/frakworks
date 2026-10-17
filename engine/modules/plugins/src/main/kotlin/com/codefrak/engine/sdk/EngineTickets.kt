package com.codefrak.engine.sdk

import com.codefrak.engine.workitems.NewWorkItem
import com.codefrak.engine.workitems.TicketState
import com.codefrak.engine.workitems.WorkItemFlags
import com.codefrak.engine.workitems.WorkItemRefused
import com.codefrak.engine.workitems.WorkItemSourceRef
import com.codefrak.engine.workitems.item
import com.codefrak.sdk.Ticket
import com.codefrak.sdk.Tickets
import org.springframework.stereotype.Component

/**
 * [Tickets] over the engine's own tickets (engine/README.md, "Custom jobs and the SDK"): each call names its repo and
 * reaches that repo's work items in [com.codefrak.engine.workitems.Tickets]. Labels are the item's typed fields, as
 * the engine files them: a work kind ([WORK_KINDS]), a feature's `feature:<mode>`, and its flags
 * ([WorkItemFlags]); a create names a work kind or is refused. A close ends the ticket ([TicketState.ofReason]).
 */
@Component
class EngineTickets(
    private val tickets: com.codefrak.engine.workitems.Tickets,
) : Tickets {
    override fun create(repo: String, title: String, body: String, labels: List<String>): Long {
        val kind = labels.firstOrNull { it in WORK_KINDS }
            ?: throw WorkItemRefused("a ticket's labels name its kind, one of ${WORK_KINDS.joinToString()}")
        return tickets.create(NewWorkItem(repo, "# $title\n\n${body.trimEnd()}", kind, WorkItemSourceRef(WorkItemSourceRef.RAISED),
            mode = modeOf(labels), author = ENGINE, flags = WorkItemFlags.of(labels))).ticket!!
    }

    override fun read(repo: String, number: Long): Ticket =
        tickets.item(repo, number).let { Ticket(repo, number, it.title, it.body, it.author ?: ENGINE) }

    override fun update(repo: String, number: Long, title: String?, body: String?, labels: List<String>?) {
        val item = tickets.item(repo, number)
        title?.let { tickets.retitle(item.id, it, ENGINE) }
        body?.let { tickets.editBody(item.id, it, ENGINE) }
        labels?.let { relabel(item, it) }
    }

    override fun close(repo: String, number: Long, reason: String) {
        tickets.end(repo, number, TicketState.ofReason(reason), "closed as $reason")
    }

    /** [item]'s labels set to [labels]: its kind and a feature's mode where they name one, and its flags. */
    private fun relabel(item: com.codefrak.engine.workitems.WorkItem, labels: List<String>) {
        labels.firstOrNull { it in WORK_KINDS }?.takeIf { it != item.kind }?.let { tickets.setKind(item.id, it) }
        val mode = modeOf(labels)
        if (mode != item.mode && (mode != null || item.kind == "feature")) tickets.setMode(item.id, mode)
        tickets.setFlags(item.id, WorkItemFlags.of(labels))
    }

    private fun modeOf(labels: List<String>) = labels.firstOrNull { it.startsWith("feature:") }?.removePrefix("feature:")

    private companion object {
        const val ENGINE = "engine"
        /** The labels that are a work ticket's kind. */
        val WORK_KINDS = setOf("chore", "bug", "feature", "proposal", "research")
    }
}

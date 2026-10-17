package com.codefrak.engine.events

import com.codefrak.engine.workitems.TicketStateListener
import com.codefrak.engine.workitems.WorkItem
import org.springframework.stereotype.Component

/**
 * The `ticket` `closed` frame for a ticket the engine ends itself ([com.codefrak.engine.workitems.Tickets.setState] to
 * done or cancelled), published once the change commits. Before #1762 only a webhook delivery published it, so on a
 * repo without a webhook secret (hitlp) a closed ticket stayed on the desktop's board. A frame that arrives twice is
 * harmless: the desktop's drop is idempotent.
 */
@Component
class IssueCloseFrames(private val bus: EngineEvents) : TicketStateListener {
    override fun onStateChanged(item: WorkItem, from: String, to: String) {
        val number = item.ticket ?: return
        if (to == WorkItem.DONE || to == WorkItem.CANCELLED) bus.publish(EngineEvent.Ticket(item.repo, number, "closed"))
    }
}

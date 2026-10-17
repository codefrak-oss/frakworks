package com.codefrak.engine.workitems

/**
 * The engine's tickets (engine/docs/work-items.md, "Tickets"): every ticket is an engine work item, numbered from [FIRST]
 * up, written and read through [Tickets] ([WorkItemStore] implements it; `TicketReads` gives the listing, snapshot, label
 * event and blocker shapes the flows read) with no GitHub call. A lower number (a GitHub issue from before the cutover)
 * is unsupported: the store knows no such ticket. GitHub is used only to import an issue as an item and for the opt-in
 * publish of an item's copy ([GitHubWorkItemSource]).
 */
object WorkItemTickets {
    /** The first work item ticket number (V94's sequence): a lower number is a pre-cutover GitHub issue, unsupported. */
    const val FIRST = 100_000L

    /** Who the engine's own entries, labels and tickets are by. */
    const val ENGINE = "engine"
}

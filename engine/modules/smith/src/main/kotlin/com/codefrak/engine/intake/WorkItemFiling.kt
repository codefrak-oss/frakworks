package com.codefrak.engine.intake

/**
 * The intake's filing (engine/docs/work-items.md, "Asks as work items"): an ask becomes a work item of kind `ask`, and
 * refinement files it in place as the ticket, with its tier and mode as fields.
 * Implemented in engine-api's `workitems` package ([TicketFiler.fileWorkItem] calls it); nothing here touches GitHub.
 */
interface WorkItemFiling {
    /** The ask item for intake [intake] in [repo], made from [ask] the first time and found by its source ref after. */
    fun askItem(repo: String, intake: String, ask: String): String

    /**
     * Opens intake [intake]'s ask item in [repo] with [description], by [author] (null: the engine), the first time,
     * and finds it by its source ref after: what the intake's comments, question and close go to, by its ticket number.
     */
    fun openAsk(repo: String, intake: String, description: String, author: String?): AskItem

    /** Refines ask item [item] into the filed ticket; answers it. */
    fun file(item: String, kind: String, featureMode: String?, tier: Int?, description: String): FiledWorkItem
}

/** An intake's ask item: its id and its ticket number (engine/docs/work-items.md, "Tickets"). */
data class AskItem(val id: String, val ticket: Long)

/** [ticket] is its ticket number (engine/docs/work-items.md, "Tickets"), what its flow runs on. */
data class FiledWorkItem(val id: String, val kind: String, val tier: Int?, val mode: String?, val ticket: Long)

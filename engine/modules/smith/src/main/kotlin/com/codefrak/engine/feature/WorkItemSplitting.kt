package com.codefrak.engine.feature

/**
 * A split's work-item path (engine/docs/work-items.md, "Splits as work items"), not switched on yet: each child draft
 * becomes a work item of source `split-child` and the parent item waits on it by a store dependency, so finishing the
 * child (the store's gate) readies the parent. Implemented in engine-api's `workitems` package; nothing here touches GitHub.
 */
interface WorkItemSplitting {
    /**
     * The child item for part [part] of parent item [parentItem], made from [description] the first time and found by
     * its source ref (`<parent>#<part>`) after, so a retried filing pass files no child twice. The parent is blocked by it.
     */
    fun childItem(parentItem: String, part: Int, kind: String, mode: String?, tier: Int?, description: String): String

    /** Whether child item [item] is done. */
    fun done(item: String): Boolean

    /** Appends [body] to [item]'s history: what a GitHub comment on the parent or child said on the issue path. */
    fun note(item: String, body: String)

    /** Appends [body] to [item]'s conversation as the engine (trusted): a live parent's steer, where the issue path comments. */
    fun converse(item: String, body: String)
}

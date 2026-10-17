package com.codefrak.engine.workitems

import com.codefrak.engine.feature.WorkItemSplitting
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * A split's work-item path ([WorkItemSplitting], engine/docs/work-items.md, "Splits as work items"): a child is an item
 * with source `split-child` (ref `<parent item>#<part>`), trusted; the parent item is blocked by it, so
 * [WorkItemGate.finish] on the child readies the parent once it was its last open blocker.
 */
@Component
class WorkItemSplits(private val store: WorkItemStore) : WorkItemSplitting {
    @Transactional
    override fun childItem(parentItem: String, part: Int, kind: String, mode: String?, tier: Int?, description: String): String {
        val parent = store.get(parentItem) ?: throw WorkItemRefused("no work item $parentItem")
        val ref = "$parentItem#$part"
        val child = store.forSourceRef(WorkItemSourceRef.SPLIT_CHILD, ref)
            ?: store.create(NewWorkItem(parent.repo, description, kind, WorkItemSourceRef(WorkItemSourceRef.SPLIT_CHILD, ref), tier = tier, mode = mode))
        store.addDependency(parentItem, child.id)
        // A running parent waits too (the store moves only a ready one); the gate readies it when the child finishes.
        if (child.state != WorkItem.DONE && store.get(parentItem)?.state == WorkItem.RUNNING)
            store.setState(parentItem, WorkItem.BLOCKED, "waiting on its child ${child.id}")
        return child.id
    }

    override fun done(item: String): Boolean = store.get(item)?.state == WorkItem.DONE

    override fun note(item: String, body: String) {
        store.appendEntry(item, WorkItemEntry.HISTORY, "engine", body, true)
    }

    override fun converse(item: String, body: String) {
        store.appendEntry(item, WorkItemEntry.CONVERSATION, "engine", body, true)
    }
}

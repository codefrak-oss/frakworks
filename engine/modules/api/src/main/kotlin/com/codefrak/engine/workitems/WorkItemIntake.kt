package com.codefrak.engine.workitems

import com.codefrak.engine.intake.AskItem
import com.codefrak.engine.intake.FiledWorkItem
import com.codefrak.engine.intake.WorkItemFiling
import org.springframework.stereotype.Component

/**
 * The intake's work-item path ([WorkItemFiling], engine/docs/work-items.md, "Asks as work items"): an ask is an item of
 * kind `ask` with source `ask` (ref the intake id), trusted; filing refines it in place ([WorkItemStore.refine]).
 */
@Component
class WorkItemIntake(private val store: WorkItemStore) : WorkItemFiling {
    /**
     * The ask item in [repo]: the one [openAsk] opened, or, when the refiner files in another target repo, a second one
     * made there (the intake's own is closed naming it). Found by its source ref and repo after.
     */
    override fun askItem(repo: String, intake: String, ask: String): String =
        store.forSourceRef(WorkItemSourceRef.ASK, intake, repo)?.id
            ?: store.create(NewWorkItem(repo, ask, WorkItem.ASK, WorkItemSourceRef(WorkItemSourceRef.ASK, intake))).id

    override fun openAsk(repo: String, intake: String, description: String, author: String?): AskItem =
        (store.forSourceRef(WorkItemSourceRef.ASK, intake, repo)
            ?: store.create(NewWorkItem(repo, description, WorkItem.ASK, WorkItemSourceRef(WorkItemSourceRef.ASK, intake), author = author)))
            .let { AskItem(it.id, it.ticket!!) }

    override fun file(item: String, kind: String, featureMode: String?, tier: Int?, description: String): FiledWorkItem =
        store.refine(item, kind, tier, featureMode, description).let { FiledWorkItem(it.id, it.kind, it.tier, it.mode, it.ticket!!) }

    companion object {
        /** The tier a run of [item] starts at on this path: its field, never a body trailer. */
        fun tierOf(item: WorkItem): Int? = item.tier
    }
}

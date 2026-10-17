package com.codefrak.engine.workitems

import com.codefrak.engine.engineJson
import com.codefrak.engine.smith.WorkItemBriefs
import org.springframework.stereotype.Component

/** [WorkItemBriefs] from the store: the ticket's item as read_work_item answers it, untrusted text fenced. */
@Component
class WorkItemBriefSection(private val store: WorkItemStore) : WorkItemBriefs {
    @Suppress("UNCHECKED_CAST")
    override fun section(repo: String, issue: Long): Map<String, Any?>? =
        store.forTicket(repo, issue)?.let { engineJson.convertValue(UntrustedFence.fenced(it), Map::class.java) as Map<String, Any?> }
}

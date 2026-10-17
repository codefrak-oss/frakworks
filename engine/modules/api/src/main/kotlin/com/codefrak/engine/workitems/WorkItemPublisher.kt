package com.codefrak.engine.workitems

import com.codefrak.engine.repos.TargetRepos
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/**
 * One-way publish (engine/docs/work-items.md, "Sources"): on every work item create and state change, a repo that set
 * `publishWorkItems` (V89, off by default) gets its issue copy created or brought up to date by the GitHub source; any
 * other repo gets no GitHub call at all. A failed write is logged and left: the item, not its copy, is the record, and
 * the next change writes the copy again. Nothing is read back. A `triage` item is never published.
 */
@Component
class WorkItemPublisher(private val repos: TargetRepos, private val github: GitHubWorkItemSource,
                        private val jdbc: JdbcClient) : WorkItemListener {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun changed(item: WorkItem) {
        if (!repos.publishWorkItems(item.repo) || item.kind == WorkItem.TRIAGE) return
        try {
            val ref = github.publish(item)
            if (ref != item.publishedRef) jdbc.sql("UPDATE engine.work_item SET published_ref = :p WHERE id = :id")
                .param("p", ref).param("id", item.id).update()
        } catch (e: Exception) {
            log.warn("work item {}: publishing to {} failed: {}", item.id, item.repo, e.message)
        }
    }
}

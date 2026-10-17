package com.codefrak.engine.workitems

import com.codefrak.engine.github.ClosedIssue
import com.codefrak.engine.github.IssueDetails
import com.codefrak.engine.github.NOT_PLANNED
import com.codefrak.engine.github.TicketComment
import com.codefrak.engine.github.TicketDetail
import com.codefrak.engine.github.issueUrl
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The desktop's ticket reads ([IssueDetails.ticket], [IssueDetails.restComments], [IssueDetails.restComment] and the
 * done list) for a work item, numbered from [WorkItemTickets.FIRST] up, in the shapes GitHub's give: its title and
 * body from its description, `OPEN` unless done or cancelled, its conversation entries as comments, and its PRs those
 * whose head is `smith/issue-<n>` ([IssueDetails.pullsOnHead]). A lower number is not one of these: callers ask GitHub.
 */
@Component
class WorkItemDetails(private val store: WorkItemStore, private val details: IssueDetails) {

    /** Null when the repo has no such work item. */
    fun ticket(repo: String, number: Long): TicketDetail? {
        val item = store.forTicket(repo, number) ?: return null
        val url = issueUrl(repo, number)
        val comments = conversation(item).map { e ->
            TicketComment(e.id, "$url#entry-${e.id}", e.author, e.createdAt, e.createdAt, e.body, "")
        }
        return TicketDetail(item.id, number, item.title, item.body, "", if (item.open) "OPEN" else "CLOSED", url,
            item.labelSet, item.createdAt, item.updatedAt.takeUnless { item.open }, comments,
            details.pullsOnHead(repo, "smith/issue-$number"))
    }

    /** Its conversation entries as GitHub's REST comments (numeric `id`, `updated_at`, `body`); null when no such item. */
    fun restComments(repo: String, number: Long): List<Map<String, Any?>>? {
        val item = store.forTicket(repo, number) ?: return null
        return conversation(item).map { rest(repo, number, it) }
    }

    /** The conversation entry of one of the repo's work items whose REST id ([restId]) is [id]; null when none is. */
    fun restComment(repo: String, id: Long): Map<String, Any?>? =
        store.list(repo).firstNotNullOfOrNull { item ->
            val number = item.ticket ?: return@firstNotNullOfOrNull null
            conversation(item).firstOrNull { restId(it.id) == id }?.let { rest(repo, number, it) }
        }

    /** The repo's done and cancelled work items as closed issues, the last updated first; with [text], those whose title or body has it (any case). */
    fun closed(repo: String, limit: Int, text: String? = null): List<ClosedIssue> =
        store.list(repo).filter { !it.open && it.ticket != null }
            .filter { text == null || it.title.contains(text, ignoreCase = true) || it.body.contains(text, ignoreCase = true) }
            .sortedByDescending { it.updatedAt }.take(limit)
            .map { ClosedIssue(it.ticket!!, it.title, issueUrl(repo, it.ticket!!), it.labelSet, it.updatedAt,
                if (it.state == WorkItem.CANCELLED) NOT_PLANNED else "completed", body = it.body) }

    private fun conversation(item: WorkItem): List<WorkItemEntry> =
        (item.entries.ifEmpty { store.entries(item.id) }).filter { it.kind == WorkItemEntry.CONVERSATION }

    private fun rest(repo: String, number: Long, e: WorkItemEntry): Map<String, Any?> = mapOf(
        "id" to restId(e.id), "node_id" to e.id, "html_url" to "${issueUrl(repo, number)}#entry-${e.id}",
        "user" to mapOf("login" to e.author), "created_at" to e.createdAt.toString(), "updated_at" to e.createdAt.toString(),
        "body" to e.body,
    )

    companion object {
        /** An entry's numeric id, as a REST comment has one: its UUID's high bits, non-negative. */
        fun restId(entry: String): Long = UUID.fromString(entry).mostSignificantBits ushr 1
    }
}

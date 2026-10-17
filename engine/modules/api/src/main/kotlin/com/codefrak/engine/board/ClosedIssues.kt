package com.codefrak.engine.board

import com.codefrak.engine.github.ClosingPull
import com.codefrak.engine.github.issueUrl
import com.codefrak.engine.repos.HandledIssues
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.workitems.WorkItemDetails
import org.operaton.bpm.engine.HistoryService
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * One done or cancelled work item of a target repo, with one thing of the engine's joined on: `parent`,
 * the split feature it was a part of (from that feature flow's split state), null for any other issue.
 */
data class ClosedBoardIssue(
    val repo: String, val number: Long, val title: String, val url: String, val labels: List<String>,
    val closedAt: OffsetDateTime?, val stateReason: String?, val closedBy: List<ClosingPull>,
    val parent: ParentView? = null,
    /** The issue body, for the desktop's Done search; empty when it is empty. */
    val body: String = "",
)

/** `failures`: always empty, kept for the shape [Board] has: the listing reads the engine's store alone. */
data class ClosedBoard(val issues: List<ClosedBoardIssue>, val failures: List<String>)

/**
 * The target repos' done and cancelled work items (`GET /issues?state=closed`, [WorkItemDetails.closed]), for the
 * desktop's done list and the dashboard's completed tickets: newest closed first, at most `limit` across repos; with
 * `q`, those whose title or description holds it. No GitHub read: since the work-items cutover an issue is not a ticket.
 */
@Component
class ClosedIssues(
    private val repos: TargetRepos,
    private val handled: HandledIssues,
    private val history: HistoryService,
    private val items: WorkItemDetails,
) {
    /**
     * `repo`: one target repo only, already checked to be one; null for all of them. `q`: the desktop's Done search,
     * case-insensitive text in the title or description; blank or null lists them all.
     */
    fun list(limit: Int, repo: String? = null, q: String? = null): ClosedBoard {
        val text = q?.trim()?.ifEmpty { null }
        val issues = (repo?.let(::listOf) ?: repos.list().map { it.fullName }).flatMap { r ->
            val closed = items.closed(r, limit, text)
            val parents = if (closed.isEmpty()) emptyMap() else parents(r)
            closed.map { ClosedBoardIssue(r, it.number, it.title, it.url, it.labels, it.closedAt, it.stateReason, it.closedBy, parents[it.number], it.body) }
        }
        return ClosedBoard(issues.sortedByDescending { it.closedAt }.take(limit), emptyList())
    }

    /** Every part a feature flow of [repo] filed, to that feature: the latest feature approval per issue, and its split state. */
    private fun parents(repo: String): Map<Long, ParentView> =
        handled.list(repo).groupBy { it.issue }.mapValues { (_, rows) -> rows.last() }.values
            .filter { it.flow == FEATURE }
            .flatMap { row ->
                val split = history.splitOf(row.processInstance) ?: return@flatMap emptyList()
                split.filed.map { it.number to ParentView(row.issue, issueUrl(repo, row.issue)) }
            }.toMap()

    companion object {
        private const val FEATURE = "feature"
    }
}

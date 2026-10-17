package com.codefrak.engine.github

import java.time.OffsetDateTime

/** A PR whose merge closes (or closed) an issue: `state` is `OPEN`, `CLOSED` or `MERGED`, as GitHub says it; `mergedAt` when merged and read. */
data class ClosingPull(val number: Long, val url: String, val state: String, val mergedAt: OffsetDateTime? = null)

/**
 * An issue whose body names a phrase ([IssueDetails.issuesNaming]): its body, `state` (`OPEN` or `CLOSED`), GitHub's
 * `stateReason` lowercased (`completed`, `not_planned`, `duplicate`, `reopened` or null) and its closing PRs with merge times.
 */
data class NamedIssue(val number: Long, val body: String, val state: String, val stateReason: String?,
                      val closedBy: List<ClosingPull> = emptyList(), val labels: List<String> = emptyList())

/** A merged PR as [IssueDetails.mergedPulls] reads it: its title, body, merge time and the issues it closes. */
data class MergedPull(val number: Long, val title: String, val body: String, val url: String, val mergedAt: OffsetDateTime,
                      val closes: List<Long> = emptyList())
/** One issue search hit ([IssueDetails.searchIssues]): `state` is `OPEN` or `CLOSED`, as GitHub says it. */
data class FoundIssue(val number: Long, val title: String, val state: String, val labels: List<String>,
                      val updatedAt: OffsetDateTime, val url: String)

/** One closed issue: what the desktop's done list and the dashboard's completed tickets show. */
data class ClosedIssue(
    val number: Long, val title: String, val url: String, val labels: List<String>, val closedAt: OffsetDateTime?,
    /** `completed`, `not_planned`, `duplicate`, or null when GitHub does not say. */
    val stateReason: String?,
    val closedBy: List<ClosingPull> = emptyList(),
    /** The issue body as GitHub has it; empty when it is empty. */
    val body: String = "",
)

/**
 * An issue or a PR read by number ([IssueDetails.issueOrPull]): [pull] for a PR; `state` `OPEN`, `CLOSED` or (a PR)
 * `MERGED`; an issue's `stateReason` lowercased and its closing PRs; `closedAt` and a PR's `mergedAt`.
 */
data class IssueOrPull(val number: Long, val pull: Boolean, val state: String, val stateReason: String? = null,
                       val closedAt: OffsetDateTime? = null, val mergedAt: OffsetDateTime? = null,
                       val closedBy: List<ClosingPull> = emptyList())

data class TicketComment(
    val id: String, val url: String, val author: String, val createdAt: OffsetDateTime, val updatedAt: OffsetDateTime,
    val body: String, val bodyHTML: String,
)

/**
 * A PR that closes the ticket; `reviewDecision` is GitHub's (`APPROVED`, `CHANGES_REQUESTED`, `REVIEW_REQUIRED`) or null;
 * `mergeCommitSha` is the merge commit's sha once merged (the main build's `headSha`), else null.
 */
data class TicketPull(val number: Long, val title: String, val url: String, val state: String, val isDraft: Boolean,
                      val reviewDecision: String?, val mergeCommitSha: String? = null)

/** One ticket as the desktop's ticket detail shows it; `state` is `OPEN` or `CLOSED`, as GitHub says it. */
data class TicketDetail(
    val nodeId: String, val number: Long, val title: String, val body: String, val bodyHTML: String, val state: String,
    val url: String, val labels: List<String>, val createdAt: OffsetDateTime, val closedAt: OffsetDateTime?,
    val comments: List<TicketComment>, val prs: List<TicketPull>,
)

/**
 * A PR that closes a ticket, as the pitch read needs it: `state` as GitHub says it, its head branch, and its files' `changeType` by path;
 * for read_ticket_pr (#1628) also its head commit's [checks] and GitHub's `mergeable` and `mergeStateStatus`, as the PR view gives them.
 */
data class PullFiles(val number: Long, val url: String, val state: String, val headRefName: String, val files: Map<String, String>,
                     val checks: List<PullCheck> = emptyList(), val mergeable: String? = null, val mergeStateStatus: String? = null)

/**
 * One check run or commit status on a PR's head commit. `status` is `queued`, `in_progress` or `completed`; a commit
 * status maps onto these: `pending` (or `expected`) is `in_progress`, any other state is `completed` with it as [conclusion].
 */
data class PullCheck(val name: String, val status: String, val conclusion: String?, val startedAt: String?, val completedAt: String?, val url: String?)

/** The repo's default branch and the PRs that close one ticket. */
data class TicketPulls(val defaultBranch: String, val pulls: List<PullFiles>)

/**
 * What the engine reads from GitHub for a UI beyond the board, with each repo's token ([RepoTokens]). Tests use a fake.
 */
interface IssueDetails {
    /** `GET /user` as `token`: its login and its classic scopes from [TokenScopes.HEADER], unknown (null) unless an implementation reads them. */
    fun user(token: String): TokenUser
    /**
     * `login`'s permission on the repo, GitHub's collaborator permission (`role_name`: `admin`, `maintain`, `write`,
     * `triage`, `read`, or a custom role's name; else `permission`), asked with the repo's token; null when GitHub has
     * no such user. Throws unless an implementation asks, or when GitHub refuses.
     */
    fun permission(repo: String, login: String): String? = throw UnsupportedOperationException("permission")
    /** The repo's closed issues (not PRs), most recently updated first, at most `limit`. */
    fun closedIssues(repo: String, limit: Int): List<ClosedIssue>
    /** Issue or PR [number], one GraphQL query; null when the repo has neither. Throws unless an implementation reads it. */
    fun issueOrPull(repo: String, number: Long): IssueOrPull? = throw UnsupportedOperationException("issueOrPull")
    /**
     * The repo's issues (not PRs), open or closed, whose body contains [phrase], oldest first: one GitHub search
     * (`in:body`) a page of 100. Throws unless an implementation searches.
     */
    fun issuesNaming(repo: String, phrase: String): List<NamedIssue> = throw UnsupportedOperationException("issuesNaming")
    /**
     * The repo's PRs merged at or after [since], newest update first: one GraphQL page of the 100 last updated merged
     * PRs (a PR merged since was updated since). Throws unless an implementation reads them.
     */
    fun mergedPulls(repo: String, since: OffsetDateTime): List<MergedPull> = throw UnsupportedOperationException("mergedPulls")
    /**
     * The repo's issues (not PRs) matching [text], GitHub's issue search, most recently updated first: `state` is
     * `open`, `closed` or null for both; at most [limit] (1..100). Throws unless an implementation searches.
     */
    fun searchIssues(repo: String, text: String, state: String?, limit: Int): List<FoundIssue> = throw UnsupportedOperationException("searchIssues")
    /**
     * The repo's PRs, open or closed, whose head branch is [head] (`smith/issue-<n>` for a work item), newest first, as
     * [TicketDetail.prs] shows them. Throws unless an implementation reads them.
     */
    fun pullsOnHead(repo: String, head: String): List<TicketPull> = throw UnsupportedOperationException("pullsOnHead")
    /** Null when the repo has no such issue. */
    fun ticket(repo: String, number: Long): TicketDetail?
    /**
     * Every comment on an issue, oldest first, as GitHub's REST API sends them (numeric `id`,
     * `updated_at`, `body` untouched: the tickets server's question fingerprint digests those).
     * Null when the repo has no such issue.
     */
    fun restComments(repo: String, number: Long): List<Map<String, Any?>>?
    /** One issue comment by its REST id, as GitHub sends it; null when the repo has no such comment. */
    fun restComment(repo: String, id: Long): Map<String, Any?>?
    /**
     * A file's text at `ref` (null: the repo's default branch), GitHub's contents API read raw; null when
     * the repo, the ref or the file is not there. Throws unless an implementation reads files.
     */
    fun fileText(repo: String, path: String, ref: String?): String? = throw UnsupportedOperationException("fileText")
    /**
     * The repo's default branch and the PRs that close the issue, with their files; null when the repo has no
     * such issue. Throws unless an implementation reads them.
     */
    fun ticketPulls(repo: String, number: Long): TicketPulls? = throw UnsupportedOperationException("ticketPulls")
    /**
     * The PR's unified diff (`GET /repos/{r}/pulls/{n}`, GitHub's diff media type); null when the repo has no
     * such PR. Throws unless an implementation reads diffs.
     */
    fun pullDiff(repo: String, number: Long): String? = throw UnsupportedOperationException("pullDiff")
    /** Markdown rendered to HTML by GitHub (`POST /markdown`, gfm, in `repo`'s context). Throws unless an implementation renders. */
    fun renderMarkdown(repo: String, text: String): String = throw UnsupportedOperationException("renderMarkdown")
}

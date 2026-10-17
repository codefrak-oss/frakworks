package com.codefrak.engine.github

data class NewIssue(val title: String, val body: String, val labels: List<String> = emptyList())
/** `id` is GitHub's database id, which the dependency endpoints take instead of the number; null when not read. */
data class CreatedIssue(val number: Long, val url: String, val id: Long? = null)

/**
 * GitHub's own issue calls ([RestGitHub]), made only to import an issue as a work item (`GitHubWorkItemSource.import`,
 * [issue]) and for a repo's opt-in publish of its items' copies (`GitHubWorkItemSource.publish`: [create], [edit],
 * [close]). Tickets are store work items (`Tickets`); nothing else calls GitHub's issues.
 */
interface GitHubIssueApi {
    /** The issue as import reads it: its number, title, body and author. */
    fun issue(repo: String, number: Long): IssueSnapshot
    fun create(repo: String, issue: NewIssue): CreatedIssue
    /** Closes it with GitHub's `state_reason`: `completed`, or `not_planned` for a cancel. */
    fun close(repo: String, number: Long, reason: String = COMPLETED)
    /** One PATCH of what is given: a null title, body or labels is left as it is; `labels` replaces the set. */
    fun edit(repo: String, number: Long, title: String? = null, body: String? = null, labels: List<String>? = null)
}

/**
 * [text]'s words for a GitHub search, each quoted, so they are text and never a qualifier (a `repo:` in them would
 * widen the search). Left as typed: GitHub's search is case-insensitive.
 */
fun searchWords(text: String): String =
    text.replace("\"", " ").split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { "\"$it\"" }

/** [repo]'s issue [issue], as GitHub links it. */
fun issueUrl(repo: String, issue: Long): String = "https://github.com/$repo/issues/$issue"

/** The inverse of [issueUrl]: [url]'s repo full name and issue number, or null when it is not a strict `https://github.com/<owner>/<repo>/issues/<n>` URL. */
fun parseIssueUrl(url: String): Pair<String, Long>? =
    Regex("""^https://github\.com/([\w.-]+/[\w.-]+)/issues/(\d+)$""").find(url)?.destructured?.let { (repo, number) -> repo to number.toLong() }

/** GitHub's `state_reason` for an issue closed as done, and for one closed as not planned. */
const val COMPLETED = "completed"
const val NOT_PLANNED = "not_planned"

package com.codefrak.engine.workitems

import com.codefrak.engine.github.GitHubIssueApi
import com.codefrak.engine.github.NewIssue
import org.springframework.stereotype.Component

/**
 * The GitHub [WorkItemSource] (engine/docs/work-items.md, "Sources"). [import] reads one issue and answers an
 * untrusted item holding its title and body verbatim: nothing in the text is parsed, so a hidden HTML comment, a
 * `Tier:` line or an `<!-- engine: … -->` trailer sets no field; kind, tier and mode are the importer's alone.
 * [publish] writes a one-way issue copy with the engine's repo token: it creates the issue, then edits it, and closes
 * it once the item is done or cancelled. It never lists or answers comments. These
 * are the only GitHub issue calls the engine makes ([GitHubIssueApi]); every ticket is a store work item.
 */
@Component
class GitHubWorkItemSource(private val issues: GitHubIssueApi) : WorkItemSource {
    override val name = "github"

    /** [ref] is `owner/name#n` or `#n` (in [repo]); the issue must be in [repo]. */
    override fun import(repo: String, ref: String): NewWorkItem? {
        val number = issueNumber(repo, ref) ?: return null
        val issue = issues.issue(repo, number)
        val text = listOf(issue.title, issue.body).filter { it.isNotBlank() }.joinToString("\n\n")
        return NewWorkItem(repo, text.ifBlank { "(empty issue)" }, "feature",
            WorkItemSourceRef(WorkItemSourceRef.IMPORT, sourceRef(repo, number)), trusted = false)
    }

    override fun publish(item: WorkItem): String {
        val (repo, number) = item.publishedRef?.let { parse(it) } ?: (item.repo to null)
        val title = title(item)
        val body = body(item)
        val n = number ?: issues.create(repo, NewIssue(title, body)).number
        if (number != null) issues.edit(repo, n, title = title, body = body)
        when (item.state) {
            WorkItem.DONE -> issues.close(repo, n)
            WorkItem.CANCELLED -> issues.close(repo, n, "not_planned")
        }
        return "$repo#$n"
    }

    companion object {
        private val REF = Regex("""^(?:([\w.-]+/[\w.-]+))?#(\d+)$""")

        /** The issue number [ref] names in [repo], or null for a ref that is not one of [repo]'s issues. */
        fun issueNumber(repo: String, ref: String): Long? {
            val m = REF.matchEntire(ref.trim()) ?: return null
            val named = m.groupValues[1]
            if (named.isNotEmpty() && !named.equals(repo, ignoreCase = true)) return null
            return m.groupValues[2].toLongOrNull()?.takeIf { it > 0 }
        }

        fun sourceRef(repo: String, number: Long) = "github:$repo#$number"

        private fun parse(ref: String): Pair<String, Long>? =
            ref.substringBeforeLast('#').takeIf { it.contains('/') }?.let { r -> ref.substringAfterLast('#').toLongOrNull()?.let { r to it } }

        private fun title(item: WorkItem): String =
            item.description.lineSequence().map { it.trim().trimStart('#').trim() }.firstOrNull { it.isNotEmpty() }
                .orEmpty().take(120).ifEmpty { "Work item ${item.id}" }

        private fun body(item: WorkItem): String =
            "${item.description}\n\n---\n" +
                "Kind: ${item.kind} · state: ${item.state}${item.tier?.let { " · tier $it" } ?: ""}\n\n" +
                "_A one-way copy of frakworks work item `${item.id}`, written by the engine. Replies and edits here are " +
                "never read: the work item is the source of truth._"
    }
}

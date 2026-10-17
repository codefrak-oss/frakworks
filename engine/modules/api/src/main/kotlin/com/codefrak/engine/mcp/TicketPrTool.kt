package com.codefrak.engine.mcp

import com.codefrak.engine.github.PullFiles
import org.springframework.web.server.ResponseStatusException

/**
 * read_ticket_pr (#742), on [FrakworksMcp] for First mate only: a ticket's PR(s), the PRs that close it on GitHub,
 * or a proposal's stored draft when it has none yet.
 */
class TicketPrTool(private val engine: FrakworksEngine) {
    companion object {
        val TOOL = McpTool("read_ticket_pr",
            "Read-only: a ticket's PR(s), the PRs that close it on GitHub. Use it to read a ticket's PR or proposal " +
                "(e.g. a doc under docs/proposals/). Without `path` or `diff`: each PR's number, url, state, head branch " +
                "and the files it adds or changes, GitHub's `mergeable` and `mergeStateStatus`, and `checks`: every check run " +
                "and commit status on its head commit as {name, status (queued, in_progress or completed), conclusion, startedAt, " +
                "completedAt, url}. With `path`: that changed file's full text at the PR's head branch. " +
                "With `diff`: the PR's unified diff. A file's text or a diff comes in pages of ${FrakworksMcp.PR_CHAR_BUDGET} characters: " +
                "when more remains, the page ends with a line naming the `offset` to pass (with the same `path` or `diff`) " +
                "for the next page; call again with it until a page ends without that line. A proposal ticket with no PR yet " +
                "whose flow has stored a draft answers from the engine instead: `draft` with the flow's instance, the step(s) " +
                "it waits at, the draft's path, pitch, summary, its questions and the open questions on the flow; `path` and " +
                "`offset` page the draft's doc or pitch at its unpushed head, and `diff` is refused (no PR, no diff).",
            obj(
                "ticket" to prop("integer", "The ticket (issue) number, e.g. 731.", "minimum" to 1),
                "repo" to prop("string", "owner/name: the target repo the ticket is in."),
                "path" to prop("string", "A file the PR adds or changes: return its full text at the PR's head branch."),
                "diff" to prop("boolean", "Return the PR's unified diff."),
                "offset" to prop("integer", "With `path` or `diff`: the character position the returned page starts at " +
                    "(default 0); a cut page's last line names the next one.", "minimum" to 0),
                required = listOf("ticket", "repo"),
            ))
    }

    fun call(args: Map<String, Any?>): McpToolResult {
        (refuseOthers("read_ticket_pr", args, listOf("ticket", "repo", "path", "diff", "offset")) ?: badFilter(args))?.let { return it }
        val n = FrakworksMcp.ticketOf(args["ticket"]) ?: return noTicket(args)
        val path = args["path"]
        if (path != null && (path !is String || path.isBlank())) return McpToolResult.error("not a file path: ${json(path)}")
        val diff = args["diff"]
        if (diff != null && diff !is Boolean) return McpToolResult.error("\"diff\" is true or false, not ${json(diff)}")
        if (path != null && diff == true) return McpToolResult.error("read_ticket_pr takes \"path\" or \"diff\", not both")
        val offset = if (args["offset"] == null) 0 else FrakworksMcp.offsetOf(args["offset"])
            ?: return McpToolResult.error("not an offset: ${json(args["offset"])} (an integer, 0 or more)")
        if (args["offset"] != null && path == null && diff != true)
            return McpToolResult.error("read_ticket_pr takes \"offset\" only with \"path\" or \"diff\": it pages a file's text or a diff")
        val asked = args["repo"] as? String ?: return noRepo()
        return try {
            // Only a target repo: no other repo has a token.
            val repo = engine.targetRepo(asked) ?: return McpToolResult.error("$asked is not a target repo")
            val where = "$repo#$n"
            val ticketPulls = engine.ticketPulls(repo, n)
                ?: return McpToolResult.error("no issue $where on GitHub (no such repo, or no such issue in it)")
            val pulls = ticketPulls.pulls
            if (pulls.isEmpty()) return draft(where, repo, n, ticketPulls.defaultBranch, path as String?, diff == true, offset)
            when {
                path is String -> prFile(where, repo, pulls, path, offset)
                diff == true -> prDiff(where, repo, pulls, offset)
                else -> McpToolResult.of(linkedMapOf("repo" to repo, "ticket" to n, "pulls" to pulls.map(::pullRow)))
            }
        } catch (e: Exception) {
            McpToolResult.error("could not read $asked#$n's PR: ${(e as? ResponseStatusException)?.reason ?: e.message}")
        }
    }

    /**
     * A ticket with no PR: its proposal flow's current draft, from the engine (the stored propose result, the open
     * questions on the flow, and with `path` that file at the unpushed bundle's head). An error when it is no proposal,
     * or its draft is not there yet.
     */
    private fun draft(where: String, repo: String, n: Long, default: String, path: String?, diff: Boolean, offset: Int): McpToolResult {
        val flow = engine.proposalFlow(repo, n) ?: return McpToolResult.error("ticket $where has no PR (none closes it on GitHub)")
        if (!flow.running) return McpToolResult.error("ticket $where has no PR, and its proposal flow ${flow.instance} has ended: no draft is there to read")
        val result = flow.result?.takeIf { it.outcome == "drafted" }
            ?: return McpToolResult.error("ticket $where has no PR, and its proposal flow ${flow.instance} has stored no draft yet (the first draft is still running)")
        if (diff) return McpToolResult.error("ticket $where is a proposal with no PR yet, so it has no diff: pass \"path\" (${quoted(listOfNotNull(result.path, result.pitch))}) to read its draft")
        if (path == null) return McpToolResult.of(linkedMapOf(
            "repo" to repo, "ticket" to n, "pulls" to emptyList<Any>(),
            "draft" to linkedMapOf(
                "instance" to flow.instance, "waitsAt" to flow.steps, "path" to result.path, "pitch" to result.pitch, "summary" to result.summary,
                "questions" to result.questions.map { linkedMapOf("question" to it.question, "assumption" to it.assumption, "confidence" to it.confidence, "impact" to it.impact) },
                "openQuestions" to flow.questions.map { linkedMapOf("question" to it.question, "askedAt" to it.askedAt.toString()) },
            )))
        val changes = engine.draftChanges(repo, n, default, path)
            ?: return McpToolResult.error("ticket $where's proposal flow ${flow.instance} keeps no draft bundle to read $path from")
        if (path !in changes.paths) return McpToolResult.error("$path is not among the files $where's draft changes: ${quoted(changes.paths)}")
        val text = changes.after ?: return McpToolResult.error("the draft of $where deletes $path: it has no text at the draft's head")
        val page = FrakworksMcp.pageText(text, offset) ?: return pastEnd(offset, text)
        return McpToolResult("$path in the draft of $where (proposal flow ${flow.instance}, no PR yet):\n\n$page")
    }

    private fun pullRow(p: PullFiles) = linkedMapOf(
        "number" to p.number, "url" to p.url, "state" to p.state, "headRefName" to p.headRefName,
        "files" to p.files.map { (path, change) -> linkedMapOf("path" to path, "changeType" to change) },
        "mergeable" to p.mergeable, "mergeStateStatus" to p.mergeStateStatus,
        "checks" to p.checks.map { linkedMapOf("name" to it.name, "status" to it.status, "conclusion" to it.conclusion,
            "startedAt" to it.startedAt, "completedAt" to it.completedAt, "url" to it.url) },
    )

    /** The file's text at the head branch of the first PR that changes it. */
    private fun prFile(where: String, repo: String, pulls: List<PullFiles>, path: String, offset: Int): McpToolResult {
        val pull = pulls.firstOrNull { path in it.files }
            ?: return McpToolResult.error("$path is not among the files $where's PR changes: ${quoted(pulls.flatMap { it.files.keys }.distinct())}")
        if (pull.files[path] == "DELETED") return McpToolResult.error("PR #${pull.number} deletes $path: it has no text at ${pull.headRefName}")
        val text = engine.fileText(repo, path, pull.headRefName)
            ?: return McpToolResult.error("$path is not on ${pull.headRefName} in $repo (PR #${pull.number}'s branch may be gone)")
        val page = FrakworksMcp.pageText(text, offset) ?: return pastEnd(offset, text)
        return McpToolResult("$path at ${pull.headRefName} (PR #${pull.number}, ${pull.state}, ${pull.url}):\n\n$page")
    }

    /** Each PR's diff, one after another, paged together. */
    private fun prDiff(where: String, repo: String, pulls: List<PullFiles>, offset: Int): McpToolResult {
        val parts = pulls.map { p ->
            val d = engine.pullDiff(repo, p.number) ?: return McpToolResult.error("no PR $repo#${p.number} on GitHub, though it closes $where")
            "PR #${p.number} (${p.state}, ${p.headRefName}, ${p.url}) diff:\n\n$d"
        }
        val text = parts.joinToString("\n\n")
        return McpToolResult(FrakworksMcp.pageText(text, offset) ?: return pastEnd(offset, text))
    }

    private fun pastEnd(offset: Int, text: String) =
        McpToolResult.error("offset $offset is past the end: the text is ${text.length} characters long")
}

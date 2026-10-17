package com.codefrak.engine.mcp

import com.codefrak.engine.ci.CheckRuns
import com.codefrak.engine.ci.WorkflowRun
import com.codefrak.engine.github.GitHubCompare
import com.codefrak.engine.github.TicketPull
import com.codefrak.engine.repos.CompareCommits
import com.codefrak.engine.repos.RepoChanges
import com.codefrak.engine.repos.TicketTitles

/** The PRs that close a ticket, each with its merge commit once merged; null when the repo has no such issue. */
fun interface TicketMerges {
    fun pulls(repo: String, ticket: Long): List<TicketPull>?
}

/**
 * read_desktop_release (#100117), on [FrakworksMcp] for both roles: which desktop release ships what. A desktop version is
 * `0.2.<run number>` of the repo's `desktop-release.yml` workflow (no git tag is made), so a release is a successful run
 * of it and its commit the run's `head_sha` ([CheckRuns.workflowRuns]). Given `version`, it answers that commit and the
 * tickets merged since `since` (else the previous successful release), as [RepoChanges] reads them; given `ticket`, the
 * first release whose commit contains the ticket's PR merge commit ([GitHubCompare.status] `identical` or `ahead`,
 * binary-searched over the releases, which build main in order).
 * Read-only; `repo` required, no default.
 */
class DesktopReleaseTool(
    private val engine: FrakworksEngine, private val runs: CheckRuns?, private val compare: CompareCommits?,
    private val titles: TicketTitles?, private val contains: GitHubCompare?, private val merges: TicketMerges?,
) {
    companion object {
        const val NAME = "read_desktop_release"
        const val WORKFLOW = "desktop-release.yml"
        const val PREFIX = "0.2."
        /** The newest workflow runs read: GitHub's page, so one call. */
        const val RUN_LIMIT = 100
        private val ARGS = listOf("repo", "version", "since", "ticket")
        private val VERSION = Regex("""^v?(?:0\.2\.)?(\d+)$""")

        val TOOL = McpTool(NAME,
            "Read-only: which desktop release ships what, in a target repo. A desktop version is 0.2.<run number> of the " +
                "`$WORKFLOW` workflow's successful runs on main; its commit is the run's head commit. Pass `version` " +
                "(e.g. `0.2.8`) for {version, commit, since, sinceCommit, tickets: [{number, title, pr}]}: the tickets merged " +
                "after `since` (default: the previous release) up to it; an unknown version is an error. Or pass `ticket` for " +
                "{ticket, merged, mergeCommit, released (the first version containing the merge, or null), newest (the " +
                "newest release checked)}; merged false when no PR of the ticket is merged.",
            obj(
                "repo" to prop("string", "owner/name: the target repo the desktop is released from."),
                "version" to prop("string", "A desktop version, `0.2.N` (or N). Not with `ticket`."),
                "since" to prop("string", "With `version`: the earlier version to list tickets from (default the previous release)."),
                "ticket" to prop("integer", "A ticket (issue) number: the first release that ships its merge. Not with `version`.", "minimum" to 1),
                required = listOf("repo"),
            ))

        fun versionOf(run: WorkflowRun) = "$PREFIX${run.runNumber}"
        fun runNumberOf(v: Any?): Long? = (v as? String)?.trim()?.let { VERSION.matchEntire(it) }?.groupValues?.get(1)?.toLongOrNull()
            ?: wholeNumberOf(v)?.takeIf { it > 0 }
    }

    fun call(args: Map<String, Any?>): McpToolResult {
        val extra = args.keys.filter { it !in ARGS }
        if (extra.isNotEmpty()) return McpToolResult.error("$NAME takes only ${quoted(ARGS)}, not ${quoted(extra)}")
        val targets = engine.repos().map { it.fullName }
        val named = "the target repos are ${targets.joinToString(", ").ifEmpty { "none" }}"
        val asked = args["repo"] as? String
            ?: return McpToolResult.error("\"repo\" is required (owner/name): the target repo the desktop is released from; there is no default repo; $named")
        val repo = engine.targetRepo(asked) ?: return McpToolResult.error("$asked is not a target repo; $named")
        val hasVersion = args["version"] != null
        val hasTicket = args["ticket"] != null
        if (hasVersion == hasTicket) return McpToolResult.error("pass exactly one of \"version\" or \"ticket\"")
        if (!hasVersion && args["since"] != null) return McpToolResult.error("\"since\" goes with \"version\" only")
        val reader = runs ?: return McpToolResult.error("the engine has no GitHub check-run reader, so it cannot read desktop releases")
        val releases = try {
            reader.workflowRuns(repo, WORKFLOW, "main", RUN_LIMIT)
                .filter { it.conclusion == "success" && it.runNumber != null && !it.headSha.isNullOrBlank() }
                .sortedBy { it.runNumber }
        } catch (e: Exception) {
            return McpToolResult.error("could not read the $WORKFLOW runs of $repo: ${e.message}")
        }
        return if (hasVersion) version(repo, releases, args) else ticket(repo, releases, args["ticket"])
    }

    private fun known(releases: List<WorkflowRun>) =
        releases.takeLast(5).reversed().joinToString(", ") { versionOf(it) }.ifEmpty { "none" }

    private fun version(repo: String, releases: List<WorkflowRun>, args: Map<String, Any?>): McpToolResult {
        val n = runNumberOf(args["version"]) ?: return McpToolResult.error("not a desktop version: ${json(args["version"])} (0.2.N)")
        val at = releases.indexOfFirst { it.runNumber == n }
        if (at < 0) return McpToolResult.error("no desktop release $PREFIX$n in $repo; the newest are ${known(releases)}")
        val run = releases[at]
        val base = if (args["since"] != null) {
            val s = runNumberOf(args["since"]) ?: return McpToolResult.error("not a desktop version: ${json(args["since"])} (0.2.N)")
            releases.firstOrNull { it.runNumber == s }
                ?: return McpToolResult.error("no desktop release $PREFIX$s in $repo; the newest are ${known(releases)}")
        } else releases.getOrNull(at - 1)
        val tickets = if (base == null) emptyList() else {
            val c = compare ?: return McpToolResult.error("the engine has no GitHub compare reader, so it cannot list the tickets")
            val list = try { RepoChanges(c, titles ?: TicketTitles { _, _ -> null }).answer(repo, base.headSha!!, run.headSha!!) }
            catch (e: Exception) { return McpToolResult.error("could not compare ${versionOf(base)}...${versionOf(run)} of $repo: ${e.message}") }
            list.changes.flatMap { ch -> ch.tickets.map { linkedMapOf("number" to it.n, "title" to it.title, "pr" to ch.pr) } }
        }
        return McpToolResult.of(linkedMapOf(
            "version" to versionOf(run), "commit" to run.headSha, "since" to base?.let { versionOf(it) },
            "sinceCommit" to base?.headSha, "tickets" to tickets,
        ))
    }

    private fun ticket(repo: String, releases: List<WorkflowRun>, v: Any?): McpToolResult {
        val n = FrakworksMcp.ticketOf(v) ?: return McpToolResult.error("not a ticket: ${json(v)} (an issue number)")
        val m = merges ?: return McpToolResult.error("the engine has no GitHub issue reader, so it cannot find the ticket's merge")
        val pulls = try { m.pulls(repo, n) } catch (e: Exception) {
            return McpToolResult.error("could not read the PRs of $repo#$n: ${e.message}")
        } ?: return McpToolResult.error("$repo has no issue #$n")
        val merge = pulls.firstNotNullOfOrNull { it.mergeCommitSha?.takeIf { s -> s.isNotBlank() } }
            ?: return McpToolResult.of(linkedMapOf("ticket" to n, "merged" to false, "mergeCommit" to null, "released" to null,
                "newest" to releases.lastOrNull()?.let { versionOf(it) }))
        val c = contains ?: return McpToolResult.error("the engine has no GitHub compare reader, so it cannot check the releases")
        val first = try {
            // Releases build main, oldest first: once one contains the merge every later one does, so a binary search.
            var lo = 0; var hi = releases.size
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (c.status(repo, merge, releases[mid].headSha!!) in setOf("identical", "ahead")) hi = mid else lo = mid + 1
            }
            releases.getOrNull(lo)
        } catch (e: Exception) { return McpToolResult.error("could not compare $merge with the releases of $repo: ${e.message}") }
        return McpToolResult.of(linkedMapOf("ticket" to n, "merged" to true, "mergeCommit" to merge,
            "released" to first?.let { versionOf(it) }, "newest" to releases.lastOrNull()?.let { versionOf(it) }))
    }
}

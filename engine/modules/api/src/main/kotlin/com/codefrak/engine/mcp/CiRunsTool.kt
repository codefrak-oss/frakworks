package com.codefrak.engine.mcp

import com.codefrak.engine.ci.CheckRuns
import com.codefrak.engine.ci.CiRuns
import com.codefrak.engine.ci.WorkflowRun

/**
 * read_ci_runs (#1629), on [FrakworksMcp] for First mate only: a target repo's newest Actions workflow runs with their
 * durations and queue times, and the medians over them, for "how long do CI builds take lately". Read through GitHub's
 * workflow-runs API with the repo's token ([CheckRuns.workflowRuns]). Separate from a Smith's [CiLogTool].
 */
class CiRunsTool(private val engine: FrakworksEngine, private val runs: CheckRuns?) {
    companion object {
        const val NAME = "read_ci_runs"
        const val MAX_LIMIT = CiRuns.MAX_LIMIT
        private val ARGS = listOf("repo", "workflow", "branch", "limit")

        val TOOL = McpTool(NAME,
            "Read-only: a target repo's newest GitHub Actions workflow runs, newest first, for how long CI builds take " +
                "lately. Answers `runs` (each {id, workflow, branch, event, status, conclusion, createdAt, startedAt, " +
                "completedAt, durationSeconds, queuedSeconds, url, area, trigger, headSha, runNumber}; area = the workflow " +
                "file's name without its extension (`engine` for engine.yml), trigger = `pr`, `main` (a push to the default " +
                "branch), `branch` (a push elsewhere) or the event name; duration = completedAt - startedAt, queued = " +
                "startedAt - createdAt, null when not known, e.g. a run still going; headSha is the run's head commit SHA " +
                "and runNumber its run number, so a release can be matched to a commit without comparing timestamps) and " +
                "`summary` {count, medianDurationSeconds, medianQueuedSeconds} over the runs answered (null when no run " +
                "has the value). `limit` is at most $MAX_LIMIT; more is refused.",
            obj(
                "repo" to prop("string", "owner/name: the target repo whose runs to read."),
                "workflow" to prop("string", "Only this workflow's runs: its file name (e.g. `engine.yml`) or id."),
                "branch" to prop("string", "Only runs on this branch."),
                "limit" to prop("integer", "At most this many runs, newest first (1 to $MAX_LIMIT).", "minimum" to 1, "maximum" to MAX_LIMIT),
                required = listOf("repo", "limit"),
            ))

        /** The median of the known values, or null when there is none ([CiRuns.median]). */
        fun median(values: List<Long?>): Double? = CiRuns.median(values)

        /** The answer for these runs, from the row-building code `GET /repos/{owner}/{name}/ci/runs` shares ([CiRuns.answer]). */
        fun answer(repo: String, list: List<WorkflowRun>, defaultBranch: String = "main"): Map<String, Any?> =
            CiRuns.answer(repo, list, defaultBranch)
    }

    fun call(args: Map<String, Any?>): McpToolResult {
        val extra = args.keys.filter { it !in ARGS }
        if (extra.isNotEmpty()) return McpToolResult.error("$NAME takes only ${quoted(ARGS)}, not ${quoted(extra)}")
        val targets = engine.repos().map { it.fullName }
        val named = "the target repos are ${targets.joinToString(", ").ifEmpty { "none" }}"
        val asked = args["repo"] as? String
            ?: return McpToolResult.error("\"repo\" is required (owner/name): the target repo whose runs to read; there is no default repo; $named")
        val repo = engine.targetRepo(asked) ?: return McpToolResult.error("$asked is not a target repo; $named")
        val limit = wholeNumberOf(args["limit"])?.takeIf { it in 1..MAX_LIMIT }?.toInt()
            ?: return McpToolResult.error("not a limit: ${json(args["limit"])} (an integer from 1 to $MAX_LIMIT)")
        val workflow = when (val w = args["workflow"]) { null -> null; is String -> w.trim().ifEmpty { null }
            else -> return McpToolResult.error("not a workflow: ${json(w)} (a file name or id)") }
        val branch = when (val b = args["branch"]) { null -> null; is String -> b.trim().ifEmpty { null }
            else -> return McpToolResult.error("not a branch: ${json(b)}") }
        val reader = runs ?: return McpToolResult.error("the engine has no GitHub check-run reader, so it cannot read workflow runs")
        val answer = try { CiRuns.read(reader, repo, workflow, branch, limit) } catch (e: Exception) {
            return McpToolResult.error("could not read the workflow runs of $repo: ${e.message}")
        }
        return McpToolResult.of(answer)
    }
}

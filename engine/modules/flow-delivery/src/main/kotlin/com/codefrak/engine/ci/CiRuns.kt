package com.codefrak.engine.ci

import com.codefrak.engine.repos.TargetRepos
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.OffsetDateTime

/**
 * A target repo's Actions workflow runs as rows (#1935), each with its durations and its kind: `area` (the workflow file's
 * name without its extension) and `trigger` (`pr`, `main`, `branch` or the event name). Shared by `GET
 * /repos/{owner}/{name}/ci/runs` ([CiRunsController]) and First mate's read_ci_runs.
 */
object CiRuns {
    const val MAX_LIMIT = 100

    /** The workflow file's name without its extension: `.github/workflows/engine.yml` is `engine`; null when there is none. */
    fun area(path: String?): String? {
        val file = path?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: return null
        return file.substringBeforeLast('.').ifEmpty { file }
    }

    /** `pr` for a pull request, `main` for a push to [defaultBranch], `branch` for a push elsewhere, else the event itself. */
    fun trigger(event: String?, branch: String?, defaultBranch: String): String? = when (event) {
        "pull_request", "pull_request_target" -> "pr"
        "push" -> if (branch == defaultBranch) "main" else "branch"
        else -> event
    }

    /** The median of the known values, or null when there is none; an even count averages the middle two. */
    fun median(values: List<Long?>): Double? {
        val known = values.filterNotNull().sorted()
        if (known.isEmpty()) return null
        val mid = known.size / 2
        return if (known.size % 2 == 1) known[mid].toDouble() else (known[mid - 1] + known[mid]) / 2.0
    }

    /** Whole seconds from `from` to `to`, or null when either is not known (or not a timestamp). */
    fun seconds(from: String?, to: String?): Long? {
        if (from == null || to == null) return null
        return try { Duration.between(OffsetDateTime.parse(from), OffsetDateTime.parse(to)).seconds } catch (e: Exception) { null }
    }

    /** The answer for these runs: each run with its durations and kind, and the summary over them. */
    fun answer(repo: String, list: List<WorkflowRun>, defaultBranch: String = "main"): Map<String, Any?> {
        val rows = list.map { r ->
            linkedMapOf<String, Any?>("id" to r.id, "workflow" to r.workflow, "branch" to r.branch, "event" to r.event,
                "status" to r.status, "conclusion" to r.conclusion, "createdAt" to r.createdAt, "startedAt" to r.startedAt,
                "completedAt" to r.completedAt, "durationSeconds" to seconds(r.startedAt, r.completedAt),
                "queuedSeconds" to seconds(r.createdAt, r.startedAt), "url" to r.url,
                "area" to area(r.path), "trigger" to trigger(r.event, r.branch, defaultBranch),
                "headSha" to r.headSha, "runNumber" to r.runNumber)
        }
        return linkedMapOf("repo" to repo, "runs" to rows, "summary" to linkedMapOf(
            "count" to rows.size,
            "medianDurationSeconds" to median(rows.map { it["durationSeconds"] as Long? }),
            "medianQueuedSeconds" to median(rows.map { it["queuedSeconds"] as Long? }),
        ))
    }

    /** One GitHub call for the runs and one for the default branch (`main` when that read fails), then [answer]. */
    fun read(runs: CheckRuns, repo: String, workflow: String?, branch: String?, limit: Int): Map<String, Any?> {
        val list = runs.workflowRuns(repo, workflow, branch, limit)
        val default = runCatching { runs.defaultBranch(repo) }.getOrNull() ?: "main"
        return answer(repo, list.take(limit), default)
    }
}

/** Unauthenticated, read-only: a target repo's newest workflow runs ([CiRuns]), for the desktop's Analytics > Builds. */
@RestController
class CiRunsController(private val repos: TargetRepos, private val runs: CheckRuns) {
    @GetMapping("/repos/{owner}/{name}/ci/runs")
    fun runs(@PathVariable("owner") owner: String, @PathVariable("name") name: String, @RequestParam("limit", required = false) limit: String?,
             @RequestParam("workflow", required = false) workflow: String?, @RequestParam("branch", required = false) branch: String?): Map<String, Any?> {
        val asked = "$owner/$name"
        val repo = repos.find(asked)?.fullName ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST,
            "$asked is not a target repo; the target repos are ${repos.list().joinToString(", ") { it.fullName }.ifEmpty { "none" }}")
        val n = limit?.trim()?.toIntOrNull()?.takeIf { it in 1..CiRuns.MAX_LIMIT }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "not a limit: ${limit ?: "none"} (an integer from 1 to ${CiRuns.MAX_LIMIT})")
        return try {
            CiRuns.read(runs, repo, workflow?.trim()?.ifEmpty { null }, branch?.trim()?.ifEmpty { null }, n)
        } catch (e: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "could not read the workflow runs of $repo: ${e.message}")
        }
    }
}

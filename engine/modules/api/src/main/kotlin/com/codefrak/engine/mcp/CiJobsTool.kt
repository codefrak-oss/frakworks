package com.codefrak.engine.mcp

import com.codefrak.engine.ci.CheckRuns
import com.codefrak.engine.ci.CiLog
import com.codefrak.engine.ci.CiLogCache
import com.codefrak.engine.ci.CiLogSearch
import com.codefrak.engine.engineJson
import java.time.Clock
import java.util.Locale

/**
 * read_ci_jobs, read_ci_job and search_ci_log (#2188), on [FrakworksMcp] for First mate and a Smith: a target repo's Actions
 * run's jobs and steps (where the time went, a step possibly stuck), a job's failures and log end, and a bounded search
 * of a job's log, without a whole log ever in the answer. Every answer is cut at
 * [FrakworksMcp.PR_CHAR_BUDGET]. A completed job's cleaned log is kept in [cache], so a read and searches of one job
 * fetch it once. rerun_ci_jobs (#2249) re-runs a PR's or a run's failed jobs, so a flaky failure is cleared without a
 * new commit. A Smith (#100023) calls all four on its run's repo only; it reads its own check's whole log, paged, with
 * [CiLogTool].
 */
class CiJobsTool(private val engine: FrakworksEngine, private val runs: CheckRuns?,
                 private val cache: CiLogCache = CiLogCache(), private val clock: Clock = Clock.systemUTC()) {
    companion object {
        const val READ_JOBS = "read_ci_jobs"
        const val READ_JOB = "read_ci_job"
        const val SEARCH = "search_ci_log"
        const val RERUN = "rerun_ci_jobs"
        val NAMES = listOf(READ_JOBS, READ_JOB, SEARCH, RERUN)
        /** A job conclusion `rerun-failed-jobs` re-runs. */
        val FAILED = setOf("failure", "timed_out", "cancelled")
        /** How many of a PR head's newest workflow runs [RERUN] looks at. */
        const val HEAD_RUNS = 30
        const val BUDGET = FrakworksMcp.PR_CHAR_BUDGET
        private val MB = CiLogCache.MAX_LOG_BYTES / (1024 * 1024)

        private const val STEPS = "each {number, name, status, conclusion, startedAt, completedAt, elapsedSeconds}, the slowest " +
            "marked `slowest: true`, a step still in_progress past ${CiLogSearch.STUCK_FLOOR_SECONDS} s and past " +
            "${CiLogSearch.STUCK_FACTOR}x its job's median finished step marked `possiblyStuck: true`"
        private const val CUT = "Every answer is at most ${FrakworksMcp.PR_CHAR_BUDGET} characters: one that would be longer drops " +
            "its last entries and says so in `cut`."
        private val REPO = "repo" to prop("string", "owner/name: the target repo the run is in.")
        private val JOB = "job_id" to prop("integer", "The Actions job (check run) id: a read_ci_jobs job's `id`, or the number after /job/ in its link.", "minimum" to 1)

        val TOOLS = listOf(
            McpTool(READ_JOBS,
                "Read-only: the jobs of a target repo's GitHub Actions workflow run (a read_ci_runs run's `id`), its latest " +
                    "attempt, each {id, name, status, conclusion, startedAt, completedAt, elapsedSeconds, url, slowestStep, steps}; " +
                    "steps $STEPS. For where a slow or stuck run's time went; reads no log. $CUT",
                obj(REPO, "run_id" to prop("integer", "The workflow run id.", "minimum" to 1), required = listOf("repo", "run_id"))),
            McpTool(READ_JOB,
                "Read-only: one Actions job of a target repo with its steps ($STEPS), and, for a failed job, `failures` (failing " +
                    "Gradle tests and Kotlin `e:` compile errors, at most ${CiLog.MAX_FAILURES}) and `tail` (the log's last " +
                    "${CiLog.TAIL_LINES} lines). `tail` = N (1 to ${CiLogSearch.MAX_TAIL}) reads the last N lines of any job's " +
                    "log, running or finished; a running job's answer also says `lastOutputAt` and `silentSeconds`, how long " +
                    "its log has been quiet. A log above $MB MB is not read: the answer says `logTooLarge` with its size. $CUT",
                obj(REPO, JOB, "tail" to prop("integer", "Read the log's last N lines (1 to ${CiLogSearch.MAX_TAIL}).",
                    "minimum" to 1, "maximum" to CiLogSearch.MAX_TAIL), required = listOf("repo", "job_id"))),
            McpTool(SEARCH,
                "Read-only: search one Actions job's log in a target repo, line by line (timestamps and BuildKit prefixes " +
                    "stripped), for a regex or, with `fixed`, a literal string; with no `pattern`, for lines often worth a " +
                    "look (warnings, deprecations, retries, flaky, cache misses, timeouts, exceptions, errors). Answers `total` " +
                    "(every matching line) and `matches`, the first `max` (default ${CiLogSearch.DEFAULT_MATCHES}, at most " +
                    "${CiLogSearch.MAX_MATCHES}), each {line, text, before, after} with `context` lines each side (default " +
                    "${CiLogSearch.DEFAULT_CONTEXT}, at most ${CiLogSearch.MAX_CONTEXT}). Lines are cut at ${CiLogSearch.MAX_LINE} " +
                    "characters; a search stops after ${CiLogSearch.SEARCH_TIME.seconds} s (`timedOut`). A log above $MB MB is " +
                    "not searched. $CUT",
                obj(REPO, JOB,
                    "pattern" to prop("string", "A regex (Java syntax), or a literal with `fixed`; at most ${CiLogSearch.MAX_PATTERN} characters."),
                    "fixed" to prop("boolean", "Match `pattern` as a literal string."),
                    "context" to prop("integer", "Lines before and after each match (0 to ${CiLogSearch.MAX_CONTEXT}).", "minimum" to 0, "maximum" to CiLogSearch.MAX_CONTEXT),
                    "max" to prop("integer", "At most this many matches (1 to ${CiLogSearch.MAX_MATCHES}).", "minimum" to 1, "maximum" to CiLogSearch.MAX_MATCHES),
                    required = listOf("repo", "job_id"))),
            McpTool(RERUN,
                "Re-run the failed jobs (and the jobs they need) of a target repo's GitHub Actions workflow run, GitHub's " +
                    "\"re-run failed jobs\": of the run `run_id`, or, with `pr`, of the latest run of each workflow on the PR's " +
                    "head commit. For a flaky failure on an unchanged commit: no new commit is pushed. Answers `rerun`, the runs " +
                    "re-run, each {runId, workflow, jobs (the failed jobs' names)}, and `skipped`, the runs left alone, each " +
                    "{runId, workflow, why} (still in progress, or no failed job). A run is re-run only once it has finished.",
                obj(REPO, "run_id" to prop("integer", "The workflow run id; or pass `pr`.", "minimum" to 1),
                    "pr" to prop("integer", "The pull request number whose head commit's runs to re-run; or pass `run_id`.", "minimum" to 1),
                    required = listOf("repo"))),
        )

        /**
         * [answer] as JSON within [budget]: while it is longer, its [list] (a key of the map, or of `job`) loses its last
         * entry, and `cut` says how many were dropped. Then, if still too long, `tail` keeps only its last lines.
         */
        fun fit(answer: MutableMap<String, Any?>, list: String, budget: Int = BUDGET): String {
            var text = engineJson.writeValueAsString(answer)
            if (text.length <= budget) return text
            @Suppress("UNCHECKED_CAST")
            val holder = (if (list in answer) answer else answer["job"] as MutableMap<String, Any?>)
            val items = (holder[list] as List<*>).toMutableList()
            val all = items.size
            fun note() { answer["cut"] = "${all - items.size} of $all $list dropped to stay within $budget characters" }
            holder[list] = items
            note()
            // Halve first, then step: a 50-entry answer is cut in a handful of serialisations.
            while (items.isNotEmpty()) {
                text = engineJson.writeValueAsString(answer)
                if (text.length <= budget) return text
                val drop = if (text.length > budget * 2) items.size / 2 else 1
                repeat(maxOf(1, drop)) { items.removeAt(items.lastIndex) }
                note()
            }
            text = engineJson.writeValueAsString(answer)
            while (text.length > budget && (answer["tail"] as? List<*>)?.isNotEmpty() == true) {
                answer["tail"] = (answer["tail"] as List<*>).drop(1)
                answer["cut"] = "${all - items.size} of $all $list dropped and the tail shortened to stay within $budget characters"
                text = engineJson.writeValueAsString(answer)
            }
            return text
        }
    }

    /** [ownRepo] is a Smith's run repo: it stands in for a missing `repo`, and any other repo is refused. Null for First mate. */
    fun call(tool: String, args: Map<String, Any?>, ownRepo: String? = null): McpToolResult {
        val allowed = when (tool) { READ_JOBS -> listOf("repo", "run_id"); READ_JOB -> listOf("repo", "job_id", "tail")
            RERUN -> listOf("repo", "run_id", "pr")
            else -> listOf("repo", "job_id", "pattern", "fixed", "context", "max") }
        refuseOthers(tool, args, allowed)?.let { return it }
        val targets = engine.repos().map { it.fullName }
        val named = "the target repos are ${targets.joinToString(", ").ifEmpty { "none" }}"
        if (ownRepo != null) {
            val other = args["repo"] as? String
            if (other != null && !other.equals(ownRepo, ignoreCase = true))
                return McpToolResult.error("a Smith may call $tool on its run's repo only: $ownRepo, not $other")
        }
        val asked = args["repo"] as? String ?: ownRepo
            ?: return McpToolResult.error("\"repo\" is required (owner/name): the target repo the run is in; there is no default repo; $named")
        val repo = engine.targetRepo(asked) ?: return McpToolResult.error("$asked is not a target repo; $named")
        if (tool == RERUN) return rerun(repo, args)
        val idKey = if (tool == READ_JOBS) "run_id" else "job_id"
        val id = wholeNumberOf(args[idKey])?.takeIf { it > 0 }
            ?: return McpToolResult.error("not a ${if (tool == READ_JOBS) "workflow run" else "job"} id: ${json(args[idKey])} (a positive integer)")
        val reader = runs ?: return McpToolResult.error("the engine has no GitHub check-run reader, so it cannot read CI jobs")
        return when (tool) {
            READ_JOBS -> jobs(reader, repo, id)
            READ_JOB -> {
                val tail = when (val t = args["tail"]) { null -> null
                    else -> wholeNumberOf(t)?.takeIf { it in 1..CiLogSearch.MAX_TAIL }?.toInt()
                        ?: return McpToolResult.error("not a tail: ${json(t)} (an integer from 1 to ${CiLogSearch.MAX_TAIL})") }
                job(reader, repo, id, tail)
            }
            else -> search(reader, repo, id, args)
        }
    }

    private fun rerun(repo: String, args: Map<String, Any?>): McpToolResult {
        if ((args["run_id"] == null) == (args["pr"] == null)) return McpToolResult.error("$RERUN takes exactly one of \"run_id\" or \"pr\"")
        val key = if (args["pr"] != null) "pr" else "run_id"
        val id = wholeNumberOf(args[key])?.takeIf { it > 0 }
            ?: return McpToolResult.error("not a ${if (key == "pr") "pull request number" else "workflow run id"}: ${json(args[key])} (a positive integer)")
        val reader = runs ?: return McpToolResult.error("the engine has no GitHub check-run reader, so it cannot re-run CI jobs")
        val answer = linkedMapOf<String, Any?>("repo" to repo)
        // A run id is taken as named; a PR's are its head's latest run of each workflow (an earlier one is superseded).
        val targets: List<Pair<Long, String?>> = if (key == "run_id") listOf(id to null) else {
            val head = try { reader.pullHead(repo, id) } catch (e: Exception) {
                return McpToolResult.error("could not read pull request #$id in $repo: ${e.message}")
            } ?: return McpToolResult.error("pull request #$id in $repo has no head commit")
            answer["pr"] = id; answer["headSha"] = head
            val all = try { reader.workflowRunsOn(repo, head, HEAD_RUNS) } catch (e: Exception) {
                return McpToolResult.error("could not list the workflow runs on $head in $repo: ${e.message}")
            }
            all.distinctBy { it.path ?: it.workflow }.map { it.id to it.workflow }
        }
        val rerun = mutableListOf<Map<String, Any?>>(); val skipped = mutableListOf<Map<String, Any?>>()
        for ((runId, workflow) in targets) {
            val jobs = try { reader.jobs(repo, runId) } catch (e: Exception) {
                return McpToolResult.error("could not read the jobs of run $runId in $repo: ${e.message}")
            }
            val failed = jobs.filter { it.conclusion in FAILED }.map { it.name }
            val why = when {
                jobs.any { it.status != "completed" } -> "still in progress: a run is re-run once it has finished"
                failed.isEmpty() -> "no failed job"
                else -> null
            }
            if (why != null) { skipped += linkedMapOf("runId" to runId, "workflow" to workflow, "why" to why); continue }
            try { reader.rerunFailed(repo, runId) } catch (e: Exception) {
                return McpToolResult.error("could not re-run the failed jobs of run $runId in $repo: ${e.message}",
                    mapOf("rerun" to rerun, "skipped" to skipped))
            }
            rerun += linkedMapOf("runId" to runId, "workflow" to workflow, "jobs" to failed)
        }
        if (targets.isEmpty()) answer["why"] = "no workflow run on the pull request's head commit"
        answer["rerun"] = rerun; answer["skipped"] = skipped
        return McpToolResult(fit(answer, "skipped"))
    }

    private fun jobs(reader: CheckRuns, repo: String, runId: Long): McpToolResult {
        val list = try { reader.jobs(repo, runId) } catch (e: Exception) {
            return McpToolResult.error("could not read the jobs of run $runId in $repo: ${e.message}")
        }
        val now = clock.instant()
        val answer = linkedMapOf<String, Any?>("repo" to repo, "runId" to runId, "jobs" to list.map { CiLogSearch.job(it, now) })
        return McpToolResult(fit(answer, "jobs"))
    }

    private fun job(reader: CheckRuns, repo: String, jobId: Long, tail: Int?): McpToolResult {
        val job = try { reader.job(repo, jobId) } catch (e: Exception) {
            return McpToolResult.error("could not read job $jobId in $repo: ${e.message}")
        }
        val answer = linkedMapOf<String, Any?>("repo" to repo, "job" to CiLogSearch.job(job, clock.instant()))
        val failed = job.conclusion in listOf("failure", "timed_out", "cancelled")
        if (!failed && tail == null) return McpToolResult(fit(answer, "steps"))
        val log = try { cache.get(reader, repo, jobId, job.status == "completed") } catch (e: Exception) {
            answer["logError"] = "could not read the log of job $jobId in $repo: ${e.message}"
            return McpToolResult(fit(answer, "steps"))
        }
        val lines = log.lines ?: return McpToolResult(fit(answer.apply { put("logTooLarge", tooLarge(log.size)) }, "steps"))
        if (job.status != "completed") {
            answer["lastOutputAt"] = log.lastOutputAt
            answer["silentSeconds"] = log.lastOutputAt?.let { CiLogSearch.elapsed(it, null, clock.instant()) }
        }
        answer["logLines"] = lines.size
        if (failed) answer["failures"] = CiLog.failures(lines).map(CiLogSearch::shown)
        answer["tail"] = if (tail == null) CiLog.tail(lines).lines() else CiLogSearch.tail(lines, tail)
        return McpToolResult(fit(answer, if (failed) "failures" else "steps"))
    }

    private fun search(reader: CheckRuns, repo: String, jobId: Long, args: Map<String, Any?>): McpToolResult {
        val fixed = when (val f = args["fixed"]) { null -> false; is Boolean -> f
            else -> return McpToolResult.error("not a fixed: ${json(f)} (true or false)") }
        val regex = when (val p = args["pattern"]) {
            null -> CiLogSearch.SUSPICIOUS
            is String -> CiLogSearch.compile(p, fixed).getOrElse { return McpToolResult.error("not a pattern: ${it.message}") }
            else -> return McpToolResult.error("not a pattern: ${json(p)} (a string)")
        }
        val context = when (val c = args["context"]) { null -> CiLogSearch.DEFAULT_CONTEXT
            else -> wholeNumberOf(c)?.takeIf { it in 0..CiLogSearch.MAX_CONTEXT }?.toInt()
                ?: return McpToolResult.error("not a context: ${json(c)} (an integer from 0 to ${CiLogSearch.MAX_CONTEXT})") }
        val max = when (val m = args["max"]) { null -> CiLogSearch.DEFAULT_MATCHES
            else -> wholeNumberOf(m)?.takeIf { it in 1..CiLogSearch.MAX_MATCHES }?.toInt()
                ?: return McpToolResult.error("not a max: ${json(m)} (an integer from 1 to ${CiLogSearch.MAX_MATCHES})") }
        val job = try { reader.job(repo, jobId) } catch (e: Exception) {
            return McpToolResult.error("could not read job $jobId in $repo: ${e.message}")
        }
        val log = try { cache.get(reader, repo, jobId, job.status == "completed") } catch (e: Exception) {
            return McpToolResult.error("could not read the log of job $jobId in $repo: ${e.message}")
        }
        val lines = log.lines ?: return McpToolResult.error("the log of job $jobId in $repo is ${tooLarge(log.size)}; read_ci_job answers its steps",
            mapOf("job" to CiLogSearch.job(job, clock.instant())))
        val found = CiLogSearch.search(lines, regex, context, max)
        val answer = linkedMapOf<String, Any?>("repo" to repo, "jobId" to jobId, "jobStatus" to job.status,
            "pattern" to if (args["pattern"] == null) "(suspicious lines) ${CiLogSearch.SUSPICIOUS.pattern}" else args["pattern"],
            "logLines" to lines.size, "total" to found.total,
            "matches" to found.matches.map { linkedMapOf("line" to it.line, "text" to it.text, "before" to it.before, "after" to it.after) })
        if (found.timedOut) answer["timedOut"] = "the search stopped after ${CiLogSearch.SEARCH_TIME.seconds} s: `total` counts the lines before it stopped"
        return McpToolResult(fit(answer, "matches"))
    }

    private fun tooLarge(size: Long) = "too large to read: ${"%,d".format(Locale.US, size)} bytes (the engine reads at most " +
        "${"%,d".format(Locale.US, CiLogCache.MAX_LOG_BYTES)})"
}

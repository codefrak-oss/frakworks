package com.codefrak.engine.ci

/** One check run on a commit: `status` queued, in_progress or completed; `conclusion` once completed. */
data class CheckRun(val id: Long, val status: String, val conclusion: String?, val url: String)

/** GitHub's check runs and Actions job logs. Tests use a fake. */
interface CheckRuns {
    /** The latest check run named `name` on `sha`, or null when none has started yet. */
    fun latest(repo: String, sha: String, name: String): CheckRun?

    /** The job log behind a check run: an Actions job's id is its check run's id. */
    fun log(repo: String, checkRunId: Long): String

    /**
     * Re-runs the Actions job behind a check run; the re-run is a new check run, with a new id, on the same commit.
     * When GitHub refuses because the job's workflow run is already running (a re-run already under way, by hand,
     * by GitHub or by an earlier try), it throws [RerunAlreadyRunning]: the caller takes that run as its re-run.
     * Any other refusal throws as the HTTP client does.
     */
    fun rerun(repo: String, checkRunId: Long)

    /**
     * The newest Actions workflow runs in `repo`, at most `limit` (GitHub's `per_page`, at most 100), newest first:
     * of one workflow (its file name, e.g. `engine.yml`, or its id) when `workflow` is given, on one branch when `branch` is.
     */
    fun workflowRuns(repo: String, workflow: String?, branch: String?, limit: Int): List<WorkflowRun> =
        error("this check-run reader cannot list workflow runs")

    /** The newest Actions workflow runs on commit `sha` in `repo` (GitHub's `head_sha` filter), newest first, at most `limit`. */
    fun workflowRunsOn(repo: String, sha: String, limit: Int): List<WorkflowRun> = error("this check-run reader cannot list a commit's runs")

    /** A pull request's head commit (`head.sha`), or null when GitHub names none. */
    fun pullHead(repo: String, pr: Long): String? = error("this check-run reader cannot read a pull request")

    /** Re-runs a workflow run's failed jobs, and the jobs they need (GitHub's `rerun-failed-jobs`), as a new attempt of the run. */
    fun rerunFailed(repo: String, runId: Long): Unit = error("this check-run reader cannot re-run a workflow run's failed jobs")

    /** A workflow run's jobs (its latest attempt), each with its steps, from GitHub's jobs API; at most 100. */
    fun jobs(repo: String, runId: Long): List<WorkflowJob> = error("this check-run reader cannot list a run's jobs")

    /** One Actions job (a check run's id is its job's) with its steps. */
    fun job(repo: String, jobId: Long): WorkflowJob = error("this check-run reader cannot read a job")

    /**
     * The job's log as [log] reads it, unless it is longer than [maxBytes]: then [LogFetch.text] is null and
     * [LogFetch.size] says how long it is (or at least how much was read before the read stopped).
     */
    fun boundedLog(repo: String, checkRunId: Long, maxBytes: Long): LogFetch {
        val text = log(repo, checkRunId)
        val size = text.toByteArray().size.toLong()
        return if (size > maxBytes) LogFetch(null, size) else LogFetch(text, size)
    }

    /** `repo`'s default branch (GitHub's `default_branch`), for a run's `trigger` ([CiRuns.trigger]); `main` when unknown. */
    fun defaultBranch(repo: String): String = "main"
}

/** [CheckRuns.rerun]'s refusal when the check run's workflow run is already running: the re-run is under way. */
class RerunAlreadyRunning(val checkRunId: Long, message: String) : RuntimeException(message)

/**
 * One Actions workflow run, as GitHub's workflow-runs API answers it. `completedAt` is the run's `updated_at` once its
 * status is completed (GitHub has no completed_at on a run), else null; `startedAt` its `run_started_at`.
 */
data class WorkflowRun(val id: Long, val workflow: String?, val branch: String?, val event: String?, val status: String?,
                       val conclusion: String?, val createdAt: String?, val startedAt: String?, val completedAt: String?, val url: String?,
                       /** The workflow file's path, e.g. `.github/workflows/engine.yml`; null when GitHub names none. */
                       val path: String? = null,
                       /** The run's head commit SHA (GitHub's `head_sha`), to match a release to a commit without comparing timestamps. */
                       val headSha: String? = null,
                       /** The run's number (GitHub's `run_number`), unique per workflow. */
                       val runNumber: Long? = null)

/** A job's log, or null [text] when it is above the fetch bound; [size] in bytes. */
data class LogFetch(val text: String?, val size: Long)

/** One Actions job of a workflow run, as GitHub's jobs API answers it; times are ISO-8601, null when not known yet. */
data class WorkflowJob(val id: Long, val runId: Long?, val name: String?, val status: String?, val conclusion: String?,
                       val startedAt: String?, val completedAt: String?, val url: String?, val steps: List<JobStep> = emptyList())

/** One step of a job. */
data class JobStep(val number: Int, val name: String?, val status: String?, val conclusion: String?, val startedAt: String?, val completedAt: String?)

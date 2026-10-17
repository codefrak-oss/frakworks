package com.codefrak.engine.ci

import com.codefrak.engine.github.GitHubCallMeter
import com.codefrak.engine.github.RepoTokens
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

/**
 * GitHub's REST API with the repo's token (`repo` scope reads both and re-runs a job). The log endpoint answers with a
 * redirect to a short-lived signed URL; that one is fetched without the token, so the token never
 * leaves api.github.com.
 */
@Component
class RestCheckRuns(
    private val tokens: RepoTokens,
    @Value("\${engine.github.api-url:https://api.github.com}") apiUrl: String,
    /** Counts each call and keeps the rate-limit headers; null (unit tests) counts nothing. */
    meter: GitHubCallMeter? = null,
) : CheckRuns {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
    private val client = RestClient.builder().baseUrl(apiUrl).requestFactory(JdkClientHttpRequestFactory(http))
        .defaultHeader("Accept", "application/vnd.github+json")
        .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
        .requestInterceptors { list -> meter?.let { list.add(it) } }
        .build()
    private val plain = RestClient.builder().requestFactory(JdkClientHttpRequestFactory(http)).build()

    data class Run(val id: Long, val status: String, val conclusion: String? = null, val html_url: String? = null, val started_at: String? = null)
    data class Runs(val check_runs: List<Run> = emptyList())

    override fun latest(repo: String, sha: String, name: String): CheckRun? {
        val token = tokens.forRepo(repo)
        val (owner, repoName) = repo.split('/', limit = 2)
        val runs = client.get().uri("/repos/{owner}/{name}/commits/{sha}/check-runs?check_name={check}&filter=latest", owner, repoName, sha, name)
            .header("Authorization", "Bearer $token").retrieve().body<Runs>()?.check_runs.orEmpty()
        return runs.maxByOrNull { it.id }?.let { CheckRun(it.id, it.status, it.conclusion, it.html_url.orEmpty()) }
    }

    data class ActionsRun(val id: Long, val name: String? = null, val head_branch: String? = null, val event: String? = null,
                          val status: String? = null, val conclusion: String? = null, val created_at: String? = null,
                          val run_started_at: String? = null, val updated_at: String? = null, val html_url: String? = null,
                          val path: String? = null, val head_sha: String? = null, val run_number: Long? = null)
    data class ActionsRuns(val workflow_runs: List<ActionsRun> = emptyList())

    override fun workflowRuns(repo: String, workflow: String?, branch: String?, limit: Int): List<WorkflowRun> {
        val token = tokens.forRepo(repo)
        val (owner, repoName) = repo.split('/', limit = 2)
        val vars = mutableMapOf<String, Any>("owner" to owner, "name" to repoName, "limit" to limit.coerceIn(1, 100))
        var path = if (workflow == null) "/repos/{owner}/{name}/actions/runs?per_page={limit}"
            else "/repos/{owner}/{name}/actions/workflows/{workflow}/runs?per_page={limit}".also { vars["workflow"] = workflow }
        if (branch != null) { path += "&branch={branch}"; vars["branch"] = branch }
        val runs = client.get().uri(path, vars).header("Authorization", "Bearer $token").retrieve().body<ActionsRuns>()?.workflow_runs.orEmpty()
        return runs.map { r ->
            WorkflowRun(r.id, r.name, r.head_branch, r.event, r.status, r.conclusion, r.created_at, r.run_started_at,
                r.updated_at.takeIf { r.status == "completed" }, r.html_url, r.path, r.head_sha, r.run_number)
        }
    }

    override fun workflowRunsOn(repo: String, sha: String, limit: Int): List<WorkflowRun> {
        val token = tokens.forRepo(repo)
        val (owner, repoName) = repo.split('/', limit = 2)
        val runs = client.get().uri("/repos/{owner}/{name}/actions/runs?head_sha={sha}&per_page={limit}", owner, repoName, sha, limit.coerceIn(1, 100))
            .header("Authorization", "Bearer $token").retrieve().body<ActionsRuns>()?.workflow_runs.orEmpty()
        return runs.map { r ->
            WorkflowRun(r.id, r.name, r.head_branch, r.event, r.status, r.conclusion, r.created_at, r.run_started_at,
                r.updated_at.takeIf { r.status == "completed" }, r.html_url, r.path)
        }
    }

    data class PullHead(val sha: String? = null)
    data class Pull(val head: PullHead? = null)

    override fun pullHead(repo: String, pr: Long): String? {
        val token = tokens.forRepo(repo)
        val (owner, repoName) = repo.split('/', limit = 2)
        return client.get().uri("/repos/{owner}/{name}/pulls/{n}", owner, repoName, pr)
            .header("Authorization", "Bearer $token").retrieve().body<Pull>()?.head?.sha
    }

    override fun rerunFailed(repo: String, runId: Long) {
        val token = tokens.forRepo(repo)
        val (owner, repoName) = repo.split('/', limit = 2)
        client.post().uri("/repos/{owner}/{name}/actions/runs/{id}/rerun-failed-jobs", owner, repoName, runId)
            .header("Authorization", "Bearer $token").retrieve().toBodilessEntity()
    }

    data class RepoInfo(val default_branch: String? = null)

    override fun defaultBranch(repo: String): String {
        val token = tokens.forRepo(repo)
        val (owner, repoName) = repo.split('/', limit = 2)
        return client.get().uri("/repos/{owner}/{name}", owner, repoName).header("Authorization", "Bearer $token")
            .retrieve().body<RepoInfo>()?.default_branch ?: "main"
    }

    override fun log(repo: String, checkRunId: Long): String {
        val token = tokens.forRepo(repo)
        val (owner, repoName) = repo.split('/', limit = 2)
        val answer = client.get().uri("/repos/{owner}/{name}/actions/jobs/{id}/logs", owner, repoName, checkRunId)
            .header("Authorization", "Bearer $token").retrieve().toEntity(String::class.java)
        if (answer.statusCode == HttpStatus.FOUND || answer.statusCode.is3xxRedirection) {
            val location = answer.headers.location ?: error("GitHub's log redirect for job $checkRunId has no Location")
            return plain.get().uri(URI.create(location.toString())).retrieve().body<String>().orEmpty()
        }
        return answer.body.orEmpty()
    }

    data class ActionsStep(val number: Int = 0, val name: String? = null, val status: String? = null, val conclusion: String? = null,
                           val started_at: String? = null, val completed_at: String? = null)
    data class ActionsJob(val id: Long, val run_id: Long? = null, val name: String? = null, val status: String? = null,
                          val conclusion: String? = null, val started_at: String? = null, val completed_at: String? = null,
                          val html_url: String? = null, val steps: List<ActionsStep>? = null) {
        fun toJob() = WorkflowJob(id, run_id, name, status, conclusion, started_at, completed_at, html_url,
            steps.orEmpty().map { JobStep(it.number, it.name, it.status, it.conclusion, it.started_at, it.completed_at) })
    }
    data class ActionsJobs(val jobs: List<ActionsJob> = emptyList())

    override fun jobs(repo: String, runId: Long): List<WorkflowJob> {
        val token = tokens.forRepo(repo)
        val (owner, repoName) = repo.split('/', limit = 2)
        return client.get().uri("/repos/{owner}/{name}/actions/runs/{id}/jobs?filter=latest&per_page=100", owner, repoName, runId)
            .header("Authorization", "Bearer $token").retrieve().body<ActionsJobs>()?.jobs.orEmpty().map { it.toJob() }
    }

    override fun job(repo: String, jobId: Long): WorkflowJob {
        val token = tokens.forRepo(repo)
        val (owner, repoName) = repo.split('/', limit = 2)
        return client.get().uri("/repos/{owner}/{name}/actions/jobs/{id}", owner, repoName, jobId)
            .header("Authorization", "Bearer $token").retrieve().body<ActionsJob>()?.toJob()
            ?: error("GitHub answered no job $jobId")
    }

    /** As [log], but the signed URL's body is read only up to [maxBytes]: a longer one is never held whole. */
    override fun boundedLog(repo: String, checkRunId: Long, maxBytes: Long): LogFetch {
        val token = tokens.forRepo(repo)
        val (owner, repoName) = repo.split('/', limit = 2)
        val answer = client.get().uri("/repos/{owner}/{name}/actions/jobs/{id}/logs", owner, repoName, checkRunId)
            .header("Authorization", "Bearer $token").retrieve().toEntity(String::class.java)
        if (!answer.statusCode.is3xxRedirection) {
            val text = answer.body.orEmpty()
            val size = text.toByteArray().size.toLong()
            return if (size > maxBytes) LogFetch(null, size) else LogFetch(text, size)
        }
        val location = answer.headers.location ?: error("GitHub's log redirect for job $checkRunId has no Location")
        return plain.get().uri(URI.create(location.toString())).exchange { _, response ->
            if (response.statusCode.isError) error("GitHub's log for job $checkRunId answered ${response.statusCode}")
            val length = response.headers.contentLength
            if (length > maxBytes) return@exchange LogFetch(null, length)
            val bytes = response.body.use { it.readNBytes((maxBytes + 1).coerceAtMost(Int.MAX_VALUE - 8L).toInt()) }
            if (bytes.size > maxBytes) LogFetch(null, maxOf(length, bytes.size.toLong()))
            else LogFetch(String(bytes, Charsets.UTF_8), bytes.size.toLong())
        } ?: error("GitHub's log for job $checkRunId answered nothing")
    }

    override fun rerun(repo: String, checkRunId: Long) {
        val token = tokens.forRepo(repo)
        val (owner, repoName) = repo.split('/', limit = 2)
        client.post().uri("/repos/{owner}/{name}/actions/jobs/{id}/rerun", owner, repoName, checkRunId)
            .header("Authorization", "Bearer $token").retrieve()
            .onStatus({ it.value() == 403 }) { _, response ->
                val body = response.body.use { String(it.readAllBytes(), Charsets.UTF_8) }
                val message = MESSAGE.find(body)?.groupValues?.get(1).orEmpty()
                if ("already running" in message) throw RerunAlreadyRunning(checkRunId, message)
                throw HttpClientErrorException.create(response.statusCode, response.statusText, response.headers, body.toByteArray(), Charsets.UTF_8)
            }
            .toBodilessEntity()
    }

    private companion object {
        /** The `message` of GitHub's JSON error body. */
        val MESSAGE = Regex("\"message\"\\s*:\\s*\"([^\"]*)\"")
    }
}

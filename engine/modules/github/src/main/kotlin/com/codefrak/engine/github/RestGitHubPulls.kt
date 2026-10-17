package com.codefrak.engine.github

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.body

/** GitHub's pull-request calls, each with the repo's token ([RepoTokens]). */
@Component
class RestGitHubPulls(
    private val tokens: RepoTokens,
    @Value("\${engine.github.api-url:https://api.github.com}") apiUrl: String,
    /** Counts each call and keeps the rate-limit headers ([GitHubCallMeter]); null (unit tests) counts nothing. */
    meter: GitHubCallMeter? = null,
) : GitHubPulls {
    private val log = LoggerFactory.getLogger(RestGitHubPulls::class.java)
    private val client = gitHubRestClient(apiUrl, meter)

    data class Created(val number: Long, val html_url: String, val id: Long? = null)
    data class Repo(val default_branch: String)

    override fun defaultBranch(repo: String): String {
        val (owner, name) = splitRepo(repo)
        return client.get().uri("/repos/{owner}/{name}", owner, name).header("Authorization", "Bearer ${tokens.forRepo(repo)}")
            .retrieve().body<Repo>()?.default_branch ?: error("GitHub returned no repo for $repo")
    }

    override fun open(repo: String, head: String, base: String, title: String, body: String): CreatedPull {
        val created = try {
            client.post().send(tokens, "/repos/{owner}/{name}/pulls", repo,
                mapOf("title" to title, "head" to head, "base" to base, "body" to body)).body<Created>()
        } catch (e: HttpClientErrorException) {
            if (e.statusCode.value() == 422 && e.responseBodyAsString.contains("pull request already exists", ignoreCase = true))
                throw PullExists("$repo $head: ${e.responseBodyAsString.take(500)}")
            throw e
        } ?: error("GitHub returned no pull request for $repo")
        return CreatedPull(created.number, created.html_url)
    }

    data class Pull(val number: Long, val html_url: String, val body: String? = null, val head: Head? = null)
    data class Head(val sha: String)

    override fun headSha(repo: String, number: Long): String? =
        client.get(tokens, "/repos/{owner}/{name}/pulls/$number", repo).body<Pull>()?.head?.sha

    override fun openOnHead(repo: String, head: String): List<OpenPull> {
        val owner = splitRepo(repo).first
        return pages { page ->
            client.get(tokens, "/repos/{owner}/{name}/pulls?state=open&head={head}&per_page=100&page=$page", repo, "$owner:$head")
                .body<List<Pull>>().orEmpty()
        }.map { OpenPull(it.number, it.html_url, it.body ?: "") }
    }

    data class Merged(val sha: String? = null, val merged: Boolean? = null)

    override fun merge(repo: String, number: Long, title: String): String {
        var attempt = 1
        while (true) {
            try {
                return client.put().send(tokens, "/repos/{owner}/{name}/pulls/$number/merge", repo,
                    mapOf("merge_method" to "squash", "commit_title" to "$title (#$number)")).body<Merged>()?.sha
                    ?: error("GitHub returned no merge commit sha for $repo#$number")
            } catch (e: HttpClientErrorException) {
                if (e.statusCode.value() == 405 && e.responseBodyAsString.contains("merge conflict", ignoreCase = true))
                    throw MergeConflict("$repo#$number: ${e.responseBodyAsString.take(500)}")
                // GitHub's 405 when the base moved between its check of the PR and the merge: a race, not a refusal,
                // and GitHub says to try again. Several PRs approved together met it on 2026-10-06 and parked on incidents.
                if (e.statusCode.value() == 405 && e.responseBodyAsString.contains("Base branch was modified", ignoreCase = true)
                    && attempt < BASE_MODIFIED_ATTEMPTS) {
                    log.info("merge {}#{}: base branch was modified, trying again (attempt {})", repo, number, attempt + 1)
                    Thread.sleep(baseModifiedBackoff.toMillis() * attempt)
                    attempt++
                    continue
                }
                throw e
            }
        }
    }

    /** The wait before merging again after GitHub's "Base branch was modified", times the attempt. A test shortens it. */
    var baseModifiedBackoff: java.time.Duration = java.time.Duration.ofSeconds(2)

    private companion object {
        /** Merge attempts in all while GitHub answers "Base branch was modified"; the last such answer is thrown. */
        const val BASE_MODIFIED_ATTEMPTS = 4
    }

    /** `PATCH /repos/{o}/{r}/pulls/{n}` to `closed`, under the repo's token's `repo` scope like the merge. */
    override fun closePull(repo: String, number: Long) {
        client.patch().send(tokens, "/repos/{owner}/{name}/pulls/$number", repo, mapOf("state" to "closed")).toBodilessEntity()
    }

    /** `PATCH /repos/{o}/{r}/pulls/{n}` to `open`, under the same scope as [closePull]. */
    override fun reopenPull(repo: String, number: Long) {
        client.patch().send(tokens, "/repos/{owner}/{name}/pulls/$number", repo, mapOf("state" to "open")).toBodilessEntity()
    }

    /** A PR comment is an issue comment on GitHub (`POST /issues/{n}/comments`). */
    override fun comment(repo: String, number: Long, body: String) {
        client.post().send(tokens, "/repos/{owner}/{name}/issues/$number/comments", repo, mapOf("body" to body)).toBodilessEntity()
    }
}

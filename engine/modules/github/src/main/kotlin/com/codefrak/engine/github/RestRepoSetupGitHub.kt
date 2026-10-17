package com.codefrak.engine.github

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.body

/** GitHub's REST API with the repo's token; `repo` covers every call, and those that need admin on the repo answer null. */
@Component
class RestRepoSetupGitHub(
    private val tokens: RepoTokens,
    @Value("\${engine.github.api-url:https://api.github.com}") apiUrl: String,
    /** Counts each call and keeps the rate-limit headers ([GitHubCallMeter]); null (unit tests) counts nothing. */
    meter: GitHubCallMeter? = null,
) : RepoSetupGitHub {
    private val client = gitHubRestClient(apiUrl, meter)

    data class Permissions(val push: Boolean? = null)
    data class Repo(val default_branch: String, val permissions: Permissions? = null, val allow_squash_merge: Boolean? = null, val private: Boolean = false)
    data class Label(val name: String)
    data class Branch(val protected: Boolean = false)
    data class Reviews(val required_approving_review_count: Int? = null)
    data class Protection(val required_pull_request_reviews: Reviews? = null)
    data class Commit(val sha: String)
    data class Hook(val active: Boolean = true, val events: List<String> = emptyList())

    override fun repo(repo: String): RepoSettings? = probe(repo, tokens.forRepo(repo))

    override fun probe(repo: String, token: String): RepoSettings? = refusedIsNull {
        client.getAs(token, repo, "/repos/{owner}/{name}").body<Repo>()?.let { RepoSettings(it.default_branch, it.permissions?.push, it.allow_squash_merge, it.private) }
    }

    override fun labels(repo: String): List<String> = pages { page ->
        client.get(tokens, "/repos/{owner}/{name}/labels?per_page=100&page=$page", repo).body<List<Label>>().orEmpty()
    }.map { it.name }

    override fun createLabel(repo: String, name: String, color: String) {
        val (owner, repoName) = splitRepo(repo)
        client.post().uri("/repos/{owner}/{name}/labels", owner, repoName).header("Authorization", "Bearer ${tokens.forRepo(repo)}")
            .contentType(MediaType.APPLICATION_JSON).body(mapOf("name" to name, "color" to color)).retrieve().toBodilessEntity()
    }

    override fun isProtected(repo: String, branch: String): Boolean? = try {
        client.get(tokens, "/repos/{owner}/{name}/branches/{branch}", repo, branch).body<Branch>()?.protected ?: false
    } catch (e: HttpClientErrorException) {
        if (e.statusCode.value() == 404) null else throw e
    }

    override fun requiredReviews(repo: String, branch: String): Int? = refusedIsNull {
        client.get(tokens, "/repos/{owner}/{name}/branches/{branch}/protection", repo, branch).body<Protection>()
            ?.required_pull_request_reviews?.let { it.required_approving_review_count ?: 0 } ?: 0
    }

    override fun branchRules(repo: String, branch: String): List<BranchRule>? = refusedIsNull {
        pages { page ->
            client.get(tokens, "/repos/{owner}/{name}/rules/branches/{branch}?per_page=100&page=$page", repo, branch).body<List<Map<String, Any?>>>().orEmpty()
        }.map { rule ->
            val parameters = rule["parameters"] as? Map<*, *>
            BranchRule(rule["type"] as? String ?: "", (parameters?.get("required_approving_review_count") as? Number)?.toInt(),
                (parameters?.get("allowed_merge_methods") as? List<*>)?.map { it.toString() })
        }
    }

    override fun recentCommits(repo: String, branch: String, count: Int): List<String> = try {
        client.get(tokens, "/repos/{owner}/{name}/commits?sha={branch}&per_page=${count.coerceIn(1, 100)}", repo, branch).body<List<Commit>>().orEmpty()
            .map { it.sha }
    } catch (e: HttpClientErrorException) {
        if (e.statusCode.value() == 409) emptyList() else throw e
    }

    override fun hooks(repo: String): List<RepoHook>? = refusedIsNull {
        pages { page -> client.get(tokens, "/repos/{owner}/{name}/hooks?per_page=100&page=$page", repo).body<List<Hook>>().orEmpty() }
            .map { RepoHook(it.events, it.active) }
    }

    /** 403 and 404 are GitHub's two ways of saying the token may not see it. */
    private fun <T> refusedIsNull(read: () -> T?): T? = try { read() } catch (e: HttpClientErrorException) {
        if (e.statusCode.value() == 403 || e.statusCode.value() == 404) null else throw e
    }
}

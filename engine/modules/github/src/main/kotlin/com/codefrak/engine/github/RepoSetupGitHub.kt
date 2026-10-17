package com.codefrak.engine.github

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.body

/**
 * What `GET /repos/{o}/{n}` says about a repo: `push` and `allowSquashMerge` are null when GitHub leaves them out;
 * `isPrivate` false for a public repo, whose issues any token reads.
 */
data class RepoSettings(val defaultBranch: String, val push: Boolean?, val allowSquashMerge: Boolean?, val isPrivate: Boolean = false)


/** One rule a ruleset applies to a branch: `requiredReviews` and `allowedMergeMethods` are set on a `pull_request` rule. */
data class BranchRule(val type: String, val requiredReviews: Int? = null, val allowedMergeMethods: List<String>? = null)

/**
 * One repo webhook: its events and whether it is active. Its payload URL is left out on purpose: the
 * setup check is unauthenticated, and a hook URL often carries a secret (a Slack or Discord token, `?token=`).
 */
data class RepoHook(val events: List<String>, val active: Boolean)

/**
 * The GitHub reads (and the one write, [createLabel]) behind the setup check (engine/README.md,
 * "A new target repo's GitHub setup"); tests use a fake. Every call is the repo's token's, and
 * each that the token may be refused (403/404) answers null for "unknown" instead of throwing.
 */
interface RepoSetupGitHub {
    /** Null when the token cannot read the repo (404, or 403). */
    fun repo(repo: String): RepoSettings?
    /** [repo] read as `token` instead of the repo's own: what `PUT /repos/{o}/{n}/token` tries before it stores one. */
    fun probe(repo: String, token: String): RepoSettings?
    /** Every label's name. */
    fun labels(repo: String): List<String>
    fun createLabel(repo: String, name: String, color: String)
    /** Whether the branch has classic branch protection (`GET …/branches/{b}`'s `protected`); null when GitHub has no such branch (404). */
    fun isProtected(repo: String, branch: String): Boolean?
    /** The classic protection's required approving reviews (0 when it requires none), or null when the token may not read it (admin only). */
    fun requiredReviews(repo: String, branch: String): Int?
    /** The rules every active ruleset applies to the branch, or null when GitHub will not say (403/404: a plan without rulesets). */
    fun branchRules(repo: String, branch: String): List<BranchRule>?
    /** The branch's newest `count` commits' shas, newest first; empty for a repo with no commits (GitHub's 409). */
    fun recentCommits(repo: String, branch: String, count: Int): List<String>
    /** The repo's webhooks, or null when the token may not list them (403/404: it needs admin on the repo). */
    fun hooks(repo: String): List<RepoHook>?
}

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

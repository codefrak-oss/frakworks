package com.codefrak.engine.github

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.body

/**
 * GitHub's compare of two commits (`GET /repos/{o}/{r}/compare/{base}...{head}`), for `DeployedChecks` (#100008):
 * whether a commit is an ancestor of the one running. Tests use a fake.
 */
interface GitHubCompare {
    /** GitHub's `status` for `base...head`: `identical`, `ahead` (head has base and more), `behind` or `diverged`. */
    fun status(repo: String, base: String, head: String): String
}

/** The compare call with the repo's token ([RepoTokens]); the response is read for `status` alone. */
@Component
class RestGitHubCompare(
    private val tokens: RepoTokens,
    @Value("\${engine.github.api-url:https://api.github.com}") apiUrl: String,
    meter: GitHubCallMeter? = null,
) : GitHubCompare {
    private val client = gitHubRestClient(apiUrl, meter)

    data class Comparison(val status: String)

    override fun status(repo: String, base: String, head: String): String =
        client.get(tokens, "/repos/{owner}/{name}/compare/{base}...{head}?per_page=1", repo, base, head).body<Comparison>()?.status
            ?: error("GitHub returned no comparison of $base...$head for $repo")
}

package com.codefrak.engine.github

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.body

/** The compare call ([GitHubCompare]) with the repo's token ([RepoTokens]); the response is read for `status` alone. */
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

package com.codefrak.engine.github

import com.codefrak.engine.repos.TargetRepos
import org.springframework.stereotype.Component

/** [RepoTokens] from the target repo registry: each repo's own sealed token and its Smith token ([TargetRepos.token], [TargetRepos.smithToken]). */
@Component
class StoredRepoTokens(private val repos: TargetRepos) : RepoTokens {
    override fun resolve(repo: String): String? = repos.token(repo)
    override fun resolveSmith(repo: String): String? = repos.smithToken(repo)
}

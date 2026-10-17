package com.codefrak.engine.github

import com.codefrak.engine.repos.TargetRepos
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * [TokenIdentity] from `GET /user` per token (engine/README.md, "Each repo's token"), held in memory only, keyed by
 * a digest of the token so a replaced token is a new lookup and no token is kept here. Null when the repo has no
 * token or GitHub would not say, and the engine starts either way.
 *
 * The same call's `X-OAuth-Scopes` header gives the token's classic [scopes] (engine/README.md,
 * "The token's scopes"): logged, warned about when `workflow` is missing, and null
 * (unknown) for a fine-grained token, which sends no header, or when the call fails.
 */
@Component
class GitHubTokenIdentity(private val details: IssueDetails, private val tokens: RepoTokens, private val repos: TargetRepos? = null) : TokenIdentity {
    private val log = LoggerFactory.getLogger(GitHubTokenIdentity::class.java)

    private val users = ConcurrentHashMap<String, TokenUser>()

    override fun login(repo: String): String? = user(repo)?.login

    override fun scopes(repo: String): Set<String>? = user(repo)?.scopes

    override fun commitIdentity(repo: String): CommitIdentity = user(repo)?.commitIdentity
        ?: throw IllegalStateException("cannot commit on $repo: GitHub would not say who its token is (GET /user), or it has none")

    /** One `GET /user` per distinct token; a failed call is not remembered, so the next ask tries again. */
    private fun user(repo: String): TokenUser? {
        val token = tokens.resolve(repo) ?: return null
        val what = "$repo's token"
        val key = digest(token)
        users[key]?.let { return it }
        val user = runCatching { details.user(token) }
            .onFailure { log.warn("{}: GitHub GET /user failed, its login and scopes stay unknown: {}", what, it.message) }.getOrNull()
            ?: return null
        users[key] = user
        val scopes = user.scopes
        if (scopes == null) {
            log.info("{}: login {}, scopes unknown: fine-grained token or no header", what, user.login)
            return user
        }
        log.info("{}: login {}, scopes [{}]", what, user.login, scopes.sorted().joinToString(", "))
        if (TokenScopes.WORKFLOW !in scopes)
            log.warn("{}: no '{}' scope; a push touching .github/workflows/ fails, and refine refuses a ticket that allows one",
                what, TokenScopes.WORKFLOW)
        return user
    }

    /** At startup: every target repo's token, so the log says who acts where, and which repo has none. */
    @EventListener(ApplicationReadyEvent::class)
    fun resolve() {
        users.clear()
        repos?.list()?.forEach {
            if (it.hasToken) user(it.fullName)
            else log.warn("{}: no token, so nothing can run on it (PUT /repos/{}/token)", it.fullName, it.fullName)
        }
    }

    private fun digest(token: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
}

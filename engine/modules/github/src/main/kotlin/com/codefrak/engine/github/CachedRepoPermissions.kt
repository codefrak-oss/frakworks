package com.codefrak.engine.github

import com.codefrak.engine.repos.TargetRepo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * [RepoPermissions] asked of GitHub with the repo's token and held in memory for the repo's scan interval, per repo
 * and per repo and login, so a read of GET /repos does not cost a GitHub call per repo. A refusal or an error is null,
 * held as long, never a failed read.
 */
@Component
class CachedRepoPermissions(private val details: IssueDetails, private val identity: TokenIdentity) : RepoPermissions {
    private val log = LoggerFactory.getLogger(CachedRepoPermissions::class.java)

    /** Tests move it on. */
    var clock: Clock = Clock.systemUTC()

    private data class Held(val value: String?, val until: Instant)
    private val held = ConcurrentHashMap<Pair<String, String?>, Held>()

    override fun token(repo: TargetRepo): String? {
        if (!repo.hasToken) return null
        return cached(repo, null) { identity.login(repo.fullName)?.let { details.permission(repo.fullName, it) } }
    }

    override fun caller(repo: TargetRepo, login: String): String? {
        if (!repo.hasToken || !GITHUB_LOGIN.matches(login)) return null
        return cached(repo, login.lowercase()) { details.permission(repo.fullName, login) }
    }

    private fun cached(repo: TargetRepo, login: String?, ask: () -> String?): String? {
        val key = repo.fullName.lowercase() to login
        val now = clock.instant()
        held[key]?.takeIf { now.isBefore(it.until) }?.let { return it.value }
        val value = runCatching(ask)
            .onFailure { log.warn("{}: GitHub would not say {}'s permission: {}", repo.fullName, login ?: "its token", it.message) }
            .getOrNull()
        held[key] = Held(value, now.plus(Duration.ofMinutes(repo.scanIntervalMinutes.toLong())))
        return value
    }
}

/** [SmithTokens] over the repo's stored Smith token ([RepoTokens.forSmith]), probed on GitHub ([smithTokenRefusal]) before each run. */
@Component
class CheckedSmithTokens(private val tokens: RepoTokens, private val github: RepoSetupGitHub) : SmithTokens {
    override fun forRun(repo: String): String {
        val token = tokens.forSmith(repo)
        smithTokenRefusal(github, repo, token)?.let { throw IllegalStateException("$it; no Smith runs on $repo until PUT /repos/$repo/smith-token") }
        return token
    }
}

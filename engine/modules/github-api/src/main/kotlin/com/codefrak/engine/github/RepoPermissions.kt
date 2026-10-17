package com.codefrak.engine.github

import com.codefrak.engine.repos.TargetRepo

/** The shape of a GitHub login. */
val GITHUB_LOGIN = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$")

/**
 * Who may do what on a target repo (engine/README.md, "GET /repos"): `tokenPermission`, the permission of the repo
 * token's account ([TokenIdentity.login]), and `callerPermission`, a caller's login's. Both are GitHub's collaborator
 * permission ([IssueDetails.permission]), asked with the repo's token. A refusal or an error is null, never a failed
 * read. The engine's is `CachedRepoPermissions` (engine-github), which holds each answer for the repo's scan interval.
 */
interface RepoPermissions {
    /** The repo token's account's permission; null when the repo has no token or GitHub would not say. */
    fun token(repo: TargetRepo): String?

    /** `login`'s permission on the repo; null when GitHub would not say. */
    fun caller(repo: TargetRepo, login: String): String?
}

/**
 * Why `token` may not be a Smith token on `repo` (engine/README.md, "Each repo's token"), or null when it may: it
 * must read and push to the repo; nothing else is asked of it. GitHub unreachable throws.
 */
fun smithTokenRefusal(github: RepoSetupGitHub, repo: String, token: String): String? {
    val settings = github.probe(repo, token) ?: return "the Smith token cannot read $repo"
    if (settings.push == false) return "the Smith token can read $repo but not push to it"
    return null
}

/**
 * The GH_TOKEN of a Smith sandbox: the repo's Smith token ([RepoTokens.forSmith]), checked against GitHub
 * ([smithTokenRefusal]) before each run, so a token that lost read or push since it was set starts no Smith.
 * The engine's is `CheckedSmithTokens` (engine-github).
 */
fun interface SmithTokens {
    fun forRun(repo: String): String
}

package com.codefrak.engine.github

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

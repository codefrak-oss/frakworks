package com.codefrak.engine.repos

import com.codefrak.engine.github.RepoHook

/**
 * The default branch's merge blockers. `requiresReviews`: a classic protection or a ruleset requires
 * approving reviews; null when the branch is protected, the token may not read the protection (admin
 * only) and no ruleset says so. `forbidsSquash`: the repo disallows squash merges or a ruleset's
 * `pull_request` rule leaves `squash` out of its merge methods; null when GitHub did not say. `exists` is false
 * when GitHub has no such branch (a repo with no commits yet): then it is not protected and nothing else is read.
 */
data class BranchSetup(
    val name: String, val protected: Boolean, val requiresReviews: Boolean?, val forbidsSquash: Boolean?, val exists: Boolean = true,
)

/** The row's `ci_check` and whether it ran on any of the default branch's newest [commitsLooked] commits. */
data class CheckHistory(val name: String, val ranOnDefaultBranch: Boolean, val commitsLooked: Int)

/** `present`: an active hook takes every event of [RepoSetupCheck.HOOK_EVENTS] (or `*`); `missing`: none does; `unknown`: the token may not list hooks. */
data class WebhookSetup(val status: String, val hooks: List<RepoHook>?)

/**
 * `GET /repos/{o}/{n}/setup`. Everything but [repo], [hasToken] and [readable] is null when the token cannot
 * read the repo. [hasToken] is false when the repo has no token (engine/README.md, "Each repo's token"), so
 * nothing could be read.
 * [ciCheck] is null when the row names no check, or when the default branch does not exist yet (nothing to look at).
 */
data class SetupReport(
    val repo: String,
    val hasToken: Boolean,
    val readable: Boolean,
    val push: Boolean? = null,
    val missingLabels: List<String>? = null,
    val defaultBranch: BranchSetup? = null,
    val ciCheck: CheckHistory? = null,
    val webhook: WebhookSetup? = null,
)

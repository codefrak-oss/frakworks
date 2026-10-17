package com.codefrak.engine.github

data class CreatedPull(val number: Long, val url: String)

/** An open pull request as [GitHubPulls.openOnHead] reads it. */
data class OpenPull(val number: Long, val url: String, val body: String)

/** GitHub's 422 for a PR opened on a head that already has an open one ("A pull request already exists for ..."). */
class PullExists(message: String) : RuntimeException(message)

/** The engine's one boundary to GitHub pull requests; tests use a fake. */
interface GitHubPulls {
    fun defaultBranch(repo: String): String
    /** Opens a PR from `head` (a branch in `repo`) into `base`; GitHub's 422 for an open PR on `head` is a [PullExists]. */
    fun open(repo: String, head: String, base: String, title: String, body: String): CreatedPull
    /** The open PRs from `head` (a branch in `repo`): `GET /repos/{o}/{r}/pulls?state=open&head=<owner>:<head>`. */
    fun openOnHead(repo: String, head: String): List<OpenPull>
    /**
     * Squash-merges and answers the merge commit's sha (GitHub's `sha` in the merge response: the squash commit on
     * the base); fails if GitHub will not (a required check, a stale head). GitHub's 405 for
     * merge conflicts is a [MergeConflict]; any other refusal is rethrown as it came.
     */
    fun merge(repo: String, number: Long, title: String): String
    /** Closes it unmerged; its branch and commits stay. */
    fun closePull(repo: String, number: Long)
    /** Reopens a PR closed unmerged; GitHub sends a `pull_request` `reopened` event, which starts its checks again. */
    fun reopenPull(repo: String, number: Long)
    /** The PR's current head commit (`head.sha` of `GET /repos/{o}/{r}/pulls/{n}`): it moves when anyone pushes to the branch; null when GitHub names none. */
    fun headSha(repo: String, number: Long): String?
    /** Comments on the PR (`POST /repos/{o}/{r}/issues/{n}/comments`): a PR number is not a work-item ticket, so never through `Tickets`. */
    fun comment(repo: String, number: Long, body: String)
}

/** GitHub refused a merge because the PR conflicts with its base (405 "Pull Request has merge conflicts"). */
class MergeConflict(message: String) : RuntimeException(message)

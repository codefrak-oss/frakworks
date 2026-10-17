package com.codefrak.engine.github

/**
 * A GitHub for the setup check, set by each test, by repo: a repo absent from [repos] cannot be read,
 * one absent from [hooks] refuses the hooks call, and a protected branch absent from [classicReviews]
 * refuses its protection (admin only), and one in [emptyRepos] has no commits, so no default branch. Every write is recorded in [writes].
 */
class FakeRepoSetupGitHub : RepoSetupGitHub {
    val repos = mutableMapOf<String, RepoSettings>()
    val labels = mutableMapOf<String, MutableList<String>>()
    val protectedBranches = mutableSetOf<String>()
    val classicReviews = mutableMapOf<String, Int>()
    /** Absent: no rulesets; null: GitHub will not say. */
    val rules = mutableMapOf<String, List<BranchRule>?>()
    val commits = mutableMapOf<String, List<String>>()
    val hooks = mutableMapOf<String, List<RepoHook>>()
    val writes = mutableListOf<String>()
    /** Repos with no commits: the branch read answers null (GitHub's 404) and the commits read empty (its 409). */
    val emptyRepos = mutableSetOf<String>()

    fun reset() {
        repos.clear(); labels.clear(); protectedBranches.clear(); classicReviews.clear(); rules.clear(); commits.clear(); hooks.clear(); writes.clear(); emptyRepos.clear()
        badTokens.clear(); probed.clear()
    }

    /** Tokens [probe] refuses (null, as GitHub's 404 would be); every other token reads what the repo's own does. */
    val badTokens = mutableSetOf<String>()
    /** Every token [probe] was tried with, so a test can say a refused one was never stored. */
    val probed = mutableListOf<String>()
    override fun repo(repo: String) = repos[repo]
    override fun probe(repo: String, token: String): RepoSettings? {
        probed += token
        return if (token in badTokens) null else repos[repo]
    }
    override fun labels(repo: String): List<String> = labels[repo].orEmpty().toList()
    override fun createLabel(repo: String, name: String, color: String) {
        writes += "label $repo $name"
        labels.getOrPut(repo) { mutableListOf() } += name
    }
    override fun isProtected(repo: String, branch: String) = if (repo in emptyRepos) null else repo in protectedBranches
    override fun requiredReviews(repo: String, branch: String): Int? = if (repo in protectedBranches) classicReviews[repo] else 0
    override fun branchRules(repo: String, branch: String): List<BranchRule>? = if (repo in rules) rules[repo] else emptyList()
    override fun recentCommits(repo: String, branch: String, count: Int) = if (repo in emptyRepos) emptyList() else commits[repo].orEmpty().take(count)
    override fun hooks(repo: String) = hooks[repo]
}

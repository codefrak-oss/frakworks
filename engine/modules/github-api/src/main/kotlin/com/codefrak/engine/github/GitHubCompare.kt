package com.codefrak.engine.github

/**
 * GitHub's compare of two commits (`GET /repos/{o}/{r}/compare/{base}...{head}`), for `DeployedChecks` (#100008):
 * whether a commit is an ancestor of the one running. The engine's is `RestGitHubCompare` (engine-github); tests use a fake.
 */
interface GitHubCompare {
    /** GitHub's `status` for `base...head`: `identical`, `ahead` (head has base and more), `behind` or `diverged`. */
    fun status(repo: String, base: String, head: String): String
}

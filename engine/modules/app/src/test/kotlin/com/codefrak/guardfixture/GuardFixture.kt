package com.codefrak.guardfixture

import com.codefrak.engine.github.GitHubIssueApi

// Fixture for GitHubIssuesGuardTest: a class outside the github module that takes the GitHub issue client, which the
// guard refuses (tickets are store work items: callers take Tickets; only import and publish call GitHub).

/** Uses GitHubIssueApi directly. */
class ViolatingThing(private val github: GitHubIssueApi) {
    fun title(repo: String, number: Long) = github.issue(repo, number).title
}

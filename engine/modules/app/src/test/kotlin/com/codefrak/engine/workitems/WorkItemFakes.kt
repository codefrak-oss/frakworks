package com.codefrak.engine.workitems

import com.codefrak.engine.github.CreatedIssue
import com.codefrak.engine.github.GitHubIssueApi
import com.codefrak.engine.github.IssueSnapshot
import com.codefrak.engine.github.NewIssue

/**
 * A [GitHubIssueApi] serving [issues] by number to import and recording every call, reads (`issue <n>`) and publish
 * writes alike: a publisher must read nothing, a refused import must call nothing. Issue numbers count up from 100.
 */
class RecordingGitHubIssueApi(val issues: MutableMap<Long, IssueSnapshot> = mutableMapOf()) : GitHubIssueApi {
    val calls = mutableListOf<String>()
    private var next = 100L
    override fun issue(repo: String, number: Long): IssueSnapshot { calls += "issue $number"; return issues.getValue(number) }
    override fun create(repo: String, issue: NewIssue): CreatedIssue { val n = next++; calls += "create $repo#$n"; return CreatedIssue(n, "https://github.com/$repo/issues/$n") }
    override fun close(repo: String, number: Long, reason: String) { calls += "close $number $reason" }
    override fun edit(repo: String, number: Long, title: String?, body: String?, labels: List<String>?) { calls += "edit $number" }
}

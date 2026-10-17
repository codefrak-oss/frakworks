package com.codefrak.engine.github

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/** Closed issues and tickets per repo, set by each test; `login` answers `GET /user`, or throws when null, with [scopesHeader]. */
class FakeIssueDetails : IssueDetails, RepoContents {
    var login: String? = "fleet-bot"
    /** The account's display name; null when it has set none. */
    var name: String? = "Fleet Bot"
    val closed = mutableMapOf<String, MutableList<ClosedIssue>>()
    val tickets = mutableMapOf<Pair<String, Long>, TicketDetail>()
    val failing = mutableSetOf<String>()
    /** REST comment objects per issue, as GitHub would send them. */
    val restComments = mutableMapOf<Pair<String, Long>, List<Map<String, Any?>>>()
    /** What `GET /user` sends as `X-OAuth-Scopes`; null sends no header (a fine-grained token). */
    var scopesHeader: String? = null
    /** Every token `GET /user` was asked as. */
    val asked = mutableListOf<String>()
    override fun user(token: String): TokenUser {
        asked += token
        return TokenUser(login ?: error("GitHub 401: Bad credentials"), TokenScopes.parse(scopesHeader), 7, name)
    }
    /** Collaborator permission per (repo, login); a repo in [failing] throws, an unknown pair is null. Every ask is recorded. */
    val permissions = mutableMapOf<Pair<String, String>, String>()
    val permissionAsks = mutableListOf<Pair<String, String>>()
    override fun permission(repo: String, login: String): String? {
        permissionAsks += repo to login
        check(repo !in failing) { "GitHub 403" }
        return permissions[repo to login]
    }
    /** Merged PRs per repo for [mergedPulls]; a repo in [failing] throws. */
    val merged = mutableMapOf<String, MutableList<MergedPull>>()
    override fun mergedPulls(repo: String, since: java.time.OffsetDateTime): List<MergedPull> {
        check(repo !in failing) { "GitHub 502" }
        return merged[repo].orEmpty().filter { !it.mergedAt.isBefore(since) }
    }
    override fun closedIssues(repo: String, limit: Int): List<ClosedIssue> {
        check(repo !in failing) { "GitHub 502" }
        return closed[repo].orEmpty().take(limit)
    }
    /** Issues and PRs by (repo, number) for [issueOrPull]; one not set is an open issue. */
    val byNumber = mutableMapOf<Pair<String, Long>, IssueOrPull>()
    override fun issueOrPull(repo: String, number: Long): IssueOrPull? {
        check(repo !in failing) { "GitHub 502" }
        return byNumber[repo to number] ?: IssueOrPull(number, false, "OPEN")
    }
    /** Issues per repo for [issuesNaming], which answers those whose body contains the phrase (any case). */
    val named = mutableMapOf<String, MutableList<NamedIssue>>()
    override fun issuesNaming(repo: String, phrase: String): List<NamedIssue> {
        check(repo !in failing) { "GitHub 502" }
        return named[repo].orEmpty().filter { it.body.contains(phrase, ignoreCase = true) }.sortedBy { it.number }
    }
    /** PRs by (repo, head branch) for [pullsOnHead]. */
    val onHead = mutableMapOf<Pair<String, String>, List<TicketPull>>()
    override fun pullsOnHead(repo: String, head: String): List<TicketPull> {
        check(repo !in failing) { "GitHub 502" }
        return onHead[repo to head].orEmpty()
    }
    override fun ticket(repo: String, number: Long): TicketDetail? {
        check(repo !in failing) { "GitHub 502" }
        return tickets[repo to number]
    }
    override fun restComments(repo: String, number: Long): List<Map<String, Any?>>? {
        check(repo !in failing) { "GitHub 502" }
        return restComments[repo to number]
    }
    override fun restComment(repo: String, id: Long): Map<String, Any?>? {
        check(repo !in failing) { "GitHub 502" }
        return restComments.filterKeys { it.first == repo }.values.flatten().firstOrNull { (it["id"] as Number).toLong() == id }
    }
    /** The default branch and closing PRs per issue, for the pitch read with no kept record. */
    val pulls = mutableMapOf<Pair<String, Long>, TicketPulls>()
    override fun ticketPulls(repo: String, number: Long): TicketPulls? {
        check(repo !in failing) { "GitHub 502" }
        return pulls[repo to number]
    }
    /** File texts by (repo, path, ref); a null ref is the default branch. */
    val files = mutableMapOf<Triple<String, String, String?>, String>()
    override fun fileText(repo: String, path: String, ref: String?): String? {
        check(repo !in failing) { "GitHub 502" }
        return files[Triple(repo, path, ref)]
    }
    /** Directory entries by (repo, path) on the default branch, and blob texts by oid. */
    val trees = mutableMapOf<Pair<String, String>, List<TreeEntry>>()
    val blobs = mutableMapOf<String, String>()
    /** Every oid set `blobTexts` was asked for, in order. */
    val blobReads = mutableListOf<Collection<String>>()
    override fun treeEntries(repo: String, path: String): List<TreeEntry> {
        check(repo !in failing) { "GitHub 502" }
        return trees[repo to path].orEmpty()
    }
    override fun blobTexts(repo: String, oids: Collection<String>): Map<String, String> {
        check(repo !in failing) { "GitHub 502" }
        blobReads += oids.toList()
        return oids.mapNotNull { oid -> blobs[oid]?.let { oid to it } }.toMap()
    }
    /** Commit SHAs by (repo, ref), a null ref the default branch; whole trees by (repo, commit); raw files by (repo, path, ref). */
    val commits = mutableMapOf<Pair<String, String?>, String>()
    val repoTrees = mutableMapOf<Pair<String, String>, RepoTree>()
    val raw = mutableMapOf<Triple<String, String, String>, ByteArray>()
    /** Every ref resolved, tree read and contents read, in order. */
    val commitReads = mutableListOf<Pair<String, String?>>()
    val treeReads = mutableListOf<Pair<String, String>>()
    val contentReads = mutableListOf<Triple<String, String, String>>()
    override fun commitSha(repo: String, ref: String?): String? {
        check(repo !in failing) { "GitHub 502" }
        commitReads += repo to ref
        return commits[repo to ref]
    }
    override fun repoTree(repo: String, sha: String): RepoTree {
        check(repo !in failing) { "GitHub 502" }
        treeReads += repo to sha
        return repoTrees[repo to sha] ?: error("GitHub 404: no tree $sha")
    }
    override fun fileBytes(repo: String, path: String, ref: String): ByteArray? {
        check(repo !in failing) { "GitHub 502" }
        contentReads += Triple(repo, path, ref)
        return raw[Triple(repo, path, ref)]
    }
    /** Rendered as `<rendered>text</rendered>`, so a test can tell it was rendered. */
    override fun renderMarkdown(repo: String, text: String): String {
        check(repo !in failing) { "GitHub 502" }
        return "<rendered>$text</rendered>"
    }
}

/**
 * The GitHub reads the UI endpoints make, faked. Every test class of those endpoints imports it
 * with the same properties, so they share one Spring context.
 */
@TestConfiguration
class ReadFakes {
    @Bean @Primary fun details() = FakeIssueDetails()
    @Bean @Primary fun setupGitHub() = FakeRepoSetupGitHub()
}

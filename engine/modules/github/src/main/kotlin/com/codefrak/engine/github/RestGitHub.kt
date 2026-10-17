package com.codefrak.engine.github

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.body
import org.springframework.web.client.toEntity
import java.time.OffsetDateTime

/** GitHub's REST API, each call with the repo's token ([RepoTokens]). */
@Component
class RestGitHub(
    private val tokens: RepoTokens,
    @Value("\${engine.github.api-url:https://api.github.com}") apiUrl: String,
    /** Counts each call and keeps the rate-limit headers ([GitHubCallMeter]); null (unit tests) counts nothing. */
    meter: GitHubCallMeter? = null,
) : GitHubIssueApi, IssueDetails {
    private val client = gitHubRestClient(apiUrl, meter)

    data class Created(val number: Long, val html_url: String, val id: Long? = null)
    data class Issue(val number: Long, val title: String, val body: String?, val user: User)

    override fun issue(repo: String, number: Long): IssueSnapshot {
        val issue = client.get(tokens, "/repos/{owner}/{name}/issues/$number", repo).body<Issue>() ?: error("GitHub returned no issue $repo#$number")
        return IssueSnapshot(issue.number, issue.title, issue.body.orEmpty(), issue.user.login)
    }

    override fun create(repo: String, issue: NewIssue): CreatedIssue {
        val created = client.post().send(tokens, "/repos/{owner}/{name}/issues", repo, issue).body<Created>()
            ?: error("GitHub returned no issue for $repo")
        return CreatedIssue(created.number, created.html_url, created.id)
    }

    override fun close(repo: String, number: Long, reason: String) {
        client.patch().send(tokens, "/repos/{owner}/{name}/issues/$number", repo, mapOf("state" to "closed", "state_reason" to reason))
            .toBodilessEntity()
    }

    override fun edit(repo: String, number: Long, title: String?, body: String?, labels: List<String>?) {
        val fields = buildMap<String, Any> { title?.let { put("title", it) }; body?.let { put("body", it) }; labels?.let { put("labels", it) } }
        if (fields.isEmpty()) return
        client.patch().send(tokens, "/repos/{owner}/{name}/issues/$number", repo, fields).toBodilessEntity()
    }

    data class User(val login: String, val id: Long? = null, val name: String? = null)

    override fun user(token: String): TokenUser {
        require(token.isNotBlank()) { "no GitHub token to look up" }
        val response = client.get().uri("/user").header("Authorization", "Bearer $token").retrieve().toEntity<User>()
        val user = response.body ?: error("GitHub returned no user")
        val id = user.id ?: error("GitHub returned no id for ${user.login}")
        return TokenUser(user.login, TokenScopes.parse(response.headers.getFirst(TokenScopes.HEADER)), id, user.name)
    }

    override fun permission(repo: String, login: String): String? = missingIsNull {
        client.get(tokens, "/repos/{owner}/{name}/collaborators/$login/permission", repo).body<Map<String, Any?>>()
    }?.let { (it["role_name"] as? String)?.ifBlank { null } ?: it["permission"] as? String }

    /** The [ClosedIssue] fields of [closedIssues]. */
    private fun closedIssueOf(n: Map<*, *>) = ClosedIssue((n["number"] as Number).toLong(), n["title"] as String, n["url"] as String, labelsOf(n),
        (n["closedAt"] as? String)?.let(OffsetDateTime::parse), (n["stateReason"] as? String)?.lowercase(),
        closingPulls(n), n["body"] as? String ?: "")

    /** Pages `query` by cursor (a page of 100) up to `limit`, mapping each node of the connection [connectionOf] picks out of the answer. */
    private fun closedIssuePages(
        repo: String,
        query: String,
        variables: Map<String, Any?>,
        connectionOf: (Map<*, *>) -> Map<*, *>,
        limit: Int,
        what: String,
    ): List<ClosedIssue> {
        val all = mutableListOf<ClosedIssue>()
        var cursor: String? = null
        while (all.size < limit) {
            val data = client.graphql(tokens, repo, query, variables + mapOf("f" to minOf(limit - all.size, 100), "c" to cursor), what)
            val connection = connectionOf(data)
            all += nodes(connection).filter { it["number"] != null }.map(::closedIssueOf)
            val page = connection["pageInfo"] as Map<*, *>
            if (page["hasNextPage"] != true) break
            cursor = page["endCursor"] as String
        }
        return all.take(limit)
    }

    /** One GraphQL query a page of 100, by cursor, with each issue's closing PRs. */
    override fun closedIssues(repo: String, limit: Int): List<ClosedIssue> {
        val (owner, name) = splitRepo(repo)
        val query = "query(\$o:String!,\$n:String!,\$f:Int!,\$c:String){repository(owner:\$o,name:\$n){" +
            "issues(states:CLOSED,orderBy:{field:UPDATED_AT,direction:DESC},first:\$f,after:\$c){pageInfo{hasNextPage endCursor} " +
            "nodes{number title body url stateReason closedAt labels(first:50){nodes{name}} " +
            "closedByPullRequestsReferences(first:10,includeClosedPrs:true){nodes{number url state mergedAt}}}}}}"
        return closedIssuePages(repo, query, mapOf("o" to owner, "n" to name),
            { (it["repository"] as Map<*, *>)["issues"] as Map<*, *> }, limit, repo)
    }

    override fun issueOrPull(repo: String, number: Long): IssueOrPull? {
        val (owner, name) = splitRepo(repo)
        val query = "query(\$o:String!,\$n:String!,\$x:Int!){repository(owner:\$o,name:\$n){issueOrPullRequest(number:\$x){__typename " +
            "... on Issue{state stateReason closedAt closedByPullRequestsReferences(first:10,includeClosedPrs:true){nodes{number url state mergedAt}}} " +
            "... on PullRequest{state closedAt mergedAt}}}}"
        val data = client.graphql(tokens, repo, query, mapOf("o" to owner, "n" to name, "x" to number), "$repo#$number", missingOk = true)
        val n = (data["repository"] as? Map<*, *>)?.get("issueOrPullRequest") as? Map<*, *> ?: return null
        val time = { k: String -> (n[k] as? String)?.let(OffsetDateTime::parse) }
        return IssueOrPull(number, n["__typename"] == "PullRequest", n["state"] as String, (n["stateReason"] as? String)?.lowercase(),
            time("closedAt"), time("mergedAt"), closingPulls(n))
    }

    override fun mergedPulls(repo: String, since: OffsetDateTime): List<MergedPull> {
        val (owner, name) = splitRepo(repo)
        val query = "query(\$o:String!,\$n:String!){repository(owner:\$o,name:\$n){" +
            "pullRequests(states:MERGED,orderBy:{field:UPDATED_AT,direction:DESC},first:100){" +
            "nodes{number title body url mergedAt closingIssuesReferences(first:10){nodes{number}}}}}}"
        val data = client.graphql(tokens, repo, query, mapOf("o" to owner, "n" to name), "$repo merged pulls")
        return nodes((data["repository"] as Map<*, *>)["pullRequests"]).mapNotNull { n ->
            val at = (n["mergedAt"] as? String)?.let(OffsetDateTime::parse)?.takeIf { !it.isBefore(since) } ?: return@mapNotNull null
            MergedPull((n["number"] as Number).toLong(), n["title"] as? String ?: "", n["body"] as? String ?: "", n["url"] as String, at,
                nodes(n["closingIssuesReferences"]).map { (it["number"] as Number).toLong() })
        }
    }

    /** GitHub's issue search, `repo:… is:issue in:body "phrase"`, by cursor a page of 100, with each issue's closing PRs and their merge times. */
    override fun issuesNaming(repo: String, phrase: String): List<NamedIssue> {
        val query = "query(\$q:String!,\$c:String){search(query:\$q,type:ISSUE,first:100,after:\$c){pageInfo{hasNextPage endCursor} " +
            "nodes{... on Issue{number body state stateReason labels(first:20){nodes{name}} " +
            "closedByPullRequestsReferences(first:10,includeClosedPrs:true){nodes{number url state mergedAt}}}}}}"
        val q = "repo:$repo is:issue in:body \"${phrase.replace("\"", "")}\" sort:created-asc"
        val all = mutableListOf<NamedIssue>()
        var cursor: String? = null
        do {
            val data = client.graphql(tokens, repo, query, mapOf("q" to q, "c" to cursor), "$repo search")
            val search = data["search"] as Map<*, *>
            all += nodes(search).filter { it["number"] != null }.map { n ->
                NamedIssue((n["number"] as Number).toLong(), n["body"] as? String ?: "", n["state"] as String,
                    (n["stateReason"] as? String)?.lowercase(),
                    closingPulls(n), nodes(n["labels"]).map { it["name"] as String })
            }
            val page = search["pageInfo"] as Map<*, *>
            cursor = if (page["hasNextPage"] == true) page["endCursor"] as String else null
        } while (cursor != null)
        return all.sortedBy { it.number }
    }

    /** One GraphQL search (`is:issue`, so no PRs), sorted by update; the repo's token searches its issues already ([issuesNaming]). */
    override fun searchIssues(repo: String, text: String, state: String?, limit: Int): List<FoundIssue> {
        val query = "query(\$q:String!,\$n:Int!){search(query:\$q,type:ISSUE,first:\$n){" +
            "nodes{... on Issue{number title state updatedAt url labels(first:20){nodes{name}}}}}}"
        val q = "repo:$repo is:issue" + (state?.let { " is:$it" } ?: "") + " sort:updated-desc " + searchWords(text)
        val data = client.graphql(tokens, repo, query, mapOf("q" to q, "n" to limit.coerceIn(1, 100)), "$repo search")
        return nodes(data["search"]).filter { it["number"] != null }.map { n ->
            FoundIssue((n["number"] as Number).toLong(), n["title"] as String, n["state"] as String,
                nodes(n["labels"]).map { it["name"] as String }, OffsetDateTime.parse(n["updatedAt"] as String), n["url"] as String)
        }
    }

    /**
     * One GraphQL query for the issue, its rendered HTML and its closing PRs, repeated by
     * cursor while it has more than 100 comments. The repo's token reads GraphQL on target
     * repos already ([bodyLastEditedAt], [blockedBy]), so no new scope.
     */
    override fun ticket(repo: String, number: Long): TicketDetail? {
        val (owner, name) = splitRepo(repo)
        val query = "query(\$o:String!,\$n:String!,\$i:Int!,\$c:String){repository(owner:\$o,name:\$n){issue(number:\$i){" +
            "id number title body bodyHTML state url createdAt closedAt labels(first:100){nodes{name}} " +
            "comments(first:100,after:\$c){pageInfo{hasNextPage endCursor} nodes{id url author{login} createdAt updatedAt body bodyHTML}} " +
            "closedByPullRequestsReferences(first:10,includeClosedPrs:true){nodes{number title url state isDraft reviewDecision mergeCommit{oid}}}}}}"
        var first: Map<*, *>? = null
        val comments = mutableListOf<TicketComment>()
        var cursor: String? = null
        do {
            val data = client.graphql(tokens, repo, query, mapOf("o" to owner, "n" to name, "i" to number, "c" to cursor), "$repo#$number", missingOk = true)
            val issue = (data["repository"] as? Map<*, *>)?.get("issue") as? Map<*, *> ?: return null
            if (first == null) first = issue
            val page = issue["comments"] as Map<*, *>
            comments += nodes(page).map { c ->
                TicketComment(c["id"] as String, c["url"] as String, ((c["author"] as? Map<*, *>)?.get("login") as? String) ?: "ghost",
                    OffsetDateTime.parse(c["createdAt"] as String), OffsetDateTime.parse(c["updatedAt"] as String),
                    c["body"] as? String ?: "", c["bodyHTML"] as? String ?: "")
            }
            val info = page["pageInfo"] as Map<*, *>
            cursor = if (info["hasNextPage"] == true) info["endCursor"] as String else null
        } while (cursor != null)
        val i = first!!
        return TicketDetail(i["id"] as String, (i["number"] as Number).toLong(), i["title"] as String, i["body"] as? String ?: "",
            i["bodyHTML"] as? String ?: "", i["state"] as String, i["url"] as String, labelsOf(i),
            OffsetDateTime.parse(i["createdAt"] as String), (i["closedAt"] as? String)?.let(OffsetDateTime::parse), comments,
            nodes(i["closedByPullRequestsReferences"]).map { pr ->
                TicketPull((pr["number"] as Number).toLong(), pr["title"] as String, pr["url"] as String, pr["state"] as String,
                    pr["isDraft"] == true, pr["reviewDecision"] as? String, (pr["mergeCommit"] as? Map<*, *>)?.get("oid") as? String)
            })
    }

    /** One GraphQL query: the repo's last 20 PRs from branch [head], in any state. */
    override fun pullsOnHead(repo: String, head: String): List<TicketPull> {
        val (owner, name) = splitRepo(repo)
        val query = "query(\$o:String!,\$n:String!,\$h:String!){repository(owner:\$o,name:\$n){" +
            "pullRequests(headRefName:\$h,first:20,orderBy:{field:CREATED_AT,direction:DESC}){nodes{number title url state isDraft reviewDecision}}}}"
        val data = client.graphql(tokens, repo, query, mapOf("o" to owner, "n" to name, "h" to head), "$repo:$head")
        return nodes((data["repository"] as? Map<*, *>)?.get("pullRequests")).map { pr ->
            TicketPull((pr["number"] as Number).toLong(), pr["title"] as String, pr["url"] as String, pr["state"] as String,
                pr["isDraft"] == true, pr["reviewDecision"] as? String)
        }
    }

    /** `GET /repos/{r}/issues/{n}/comments`, every page, passed through as GitHub sent it. */
    override fun restComments(repo: String, number: Long): List<Map<String, Any?>>? = missingIsNull {
        pages { page ->
            client.get(tokens, "/repos/{owner}/{name}/issues/$number/comments?per_page=100&page=$page", repo).body<List<Map<String, Any?>>>().orEmpty()
        }
    }

    /** `GET /repos/{r}/issues/comments/{id}`, passed through as GitHub sent it. */
    override fun restComment(repo: String, id: Long): Map<String, Any?>? = missingIsNull {
        client.get(tokens, "/repos/{owner}/{name}/issues/comments/$id", repo).body<Map<String, Any?>>()
    }

    /**
     * One GraphQL query: the default branch and the issue's closing PRs, each with its head branch and
     * first 100 files, its merge state and its head commit's check runs and commit statuses (`statusCheckRollup`, #1628).
     * The repo's token reads GraphQL on target repos already ([ticket]), and check runs already (`CiChecks`), so no new scope.
     */
    override fun ticketPulls(repo: String, number: Long): TicketPulls? {
        val (owner, name) = splitRepo(repo)
        val query = "query(\$o:String!,\$n:String!,\$i:Int!){repository(owner:\$o,name:\$n){defaultBranchRef{name} issue(number:\$i){" +
            "closedByPullRequestsReferences(first:10,includeClosedPrs:true){nodes{number url state headRefName mergeable mergeStateStatus files(first:100){nodes{path changeType}} " +
            "commits(last:1){nodes{commit{statusCheckRollup{contexts(first:100){nodes{__typename " +
            "... on CheckRun{name status conclusion startedAt completedAt detailsUrl} " +
            "... on StatusContext{context state createdAt targetUrl}}}}}}}}}}}}"
        val data = client.graphql(tokens, repo, query, mapOf("o" to owner, "n" to name, "i" to number), "$repo#$number", missingOk = true)
        val repository = data["repository"] as? Map<*, *> ?: return null
        val issue = repository["issue"] as? Map<*, *> ?: return null
        val default = (repository["defaultBranchRef"] as? Map<*, *>)?.get("name") as? String ?: error("GitHub returned no default branch for $repo")
        return TicketPulls(default, nodes(issue["closedByPullRequestsReferences"]).map { pr ->
            PullFiles((pr["number"] as Number).toLong(), pr["url"] as? String ?: "", pr["state"] as String, pr["headRefName"] as String,
                nodes(pr["files"]).associate { it["path"] as String to (it["changeType"] as? String ?: "") },
                nodes(pr["commits"]).flatMap { c ->
                    nodes(((c["commit"] as? Map<*, *>)?.get("statusCheckRollup") as? Map<*, *>)?.get("contexts")).map(::pullCheck)
                },
                pr["mergeable"] as? String, pr["mergeStateStatus"] as? String)
        })
    }

    /** A rollup context as a [PullCheck]: a CheckRun as GitHub gives it (lower-cased), a StatusContext mapped onto a check's status. */
    private fun pullCheck(c: Map<*, *>): PullCheck =
        if (c["__typename"] == "StatusContext") {
            val state = (c["state"] as? String ?: "").lowercase()
            val pending = state == "pending" || state == "expected"
            PullCheck(c["context"] as? String ?: "", if (pending) "in_progress" else "completed", if (pending) null else state,
                c["createdAt"] as? String, null, c["targetUrl"] as? String)
        } else PullCheck(c["name"] as? String ?: "",
            when (val s = (c["status"] as? String ?: "").lowercase()) { "completed", "in_progress" -> s; else -> "queued" },
            (c["conclusion"] as? String)?.lowercase(), c["startedAt"] as? String, c["completedAt"] as? String, c["detailsUrl"] as? String)

    /** A GET with its own `Accept` media type (GitHub's REST versioning serves more than JSON); repo read is all it needs. */
    private fun rawGet(repo: String, path: String, accept: String, vararg uriVars: Any): String? = missingIsNull {
        val token = tokens.forRepo(repo)
        val (owner, name) = splitRepo(repo)
        client.get().uri(path, owner, name, *uriVars)
            .header("Authorization", "Bearer $token").header("Accept", accept)
            .retrieve().body<String>() ?: ""
    }

    /** `GET /repos/{r}/contents/{path}?ref=` with GitHub's raw media type. */
    override fun fileText(repo: String, path: String, ref: String?): String? =
        if (ref == null) rawGet(repo, "/repos/{owner}/{name}/contents/$path", "application/vnd.github.raw+json")
        else rawGet(repo, "/repos/{owner}/{name}/contents/$path?ref={ref}", "application/vnd.github.raw+json", ref)

    /** `GET /repos/{r}/pulls/{n}` with GitHub's diff media type. */
    override fun pullDiff(repo: String, number: Long): String? =
        rawGet(repo, "/repos/{owner}/{name}/pulls/$number", "application/vnd.github.diff")

    /** `POST /markdown`, mode gfm with `repo` as the context, so `#n` and relative links resolve there. */
    override fun renderMarkdown(repo: String, text: String): String {
        val token = tokens.forRepo(repo)
        return client.post().uri("/markdown").header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("text" to text, "mode" to "gfm", "context" to repo))
            .retrieve().body<String>() ?: ""
    }

    private fun nodes(connection: Any?): List<Map<*, *>> =
        ((connection as? Map<*, *>)?.get("nodes") as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()

    private fun labelsOf(node: Map<*, *>) = nodes(node["labels"]).mapNotNull { it["name"] as? String }

    private fun closingPulls(node: Map<*, *>): List<ClosingPull> =
        nodes(node["closedByPullRequestsReferences"]).map { pr ->
            ClosingPull((pr["number"] as Number).toLong(), pr["url"] as String, pr["state"] as String,
                (pr["mergedAt"] as? String)?.let(OffsetDateTime::parse))
        }
}

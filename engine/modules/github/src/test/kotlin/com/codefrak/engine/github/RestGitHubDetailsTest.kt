package com.codefrak.engine.github

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RestGitHub's [IssueDetails] against a stub GitHub: what it asks, and how each answer maps. Plain JUnit, no Spring. */
class RestGitHubDetailsTest {
    private lateinit var stub: HttpServer
    private var status = 200
    /** Answers in order, the last one repeated. */
    private val bodies = mutableListOf<String>()
    private val seen = mutableListOf<Triple<String, String, String>>()
    private val queries = mutableListOf<String?>()
    private val accepts = mutableListOf<String?>()
    private lateinit var github: RestGitHub

    @BeforeEach
    fun start() {
        stub = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        stub.createContext("/") { ex ->
            seen += Triple(ex.requestMethod, ex.requestURI.path, ex.requestBody.readAllBytes().decodeToString())
            queries += ex.requestURI.rawQuery
            accepts += ex.requestHeaders.getFirst("Accept")
            val bytes = (if (bodies.size > 1) bodies.removeAt(0) else bodies.single()).toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        stub.start()
        github = RestGitHub(RepoTokens { "t0k" }, "http://127.0.0.1:${stub.address.port}")
    }

    @AfterEach
    fun stop() = stub.stop(0)

    @Test
    fun `user is GET user as the given token, and a 401 throws`() {
        bodies += """{"login":"fleet-bot","id":1,"name":"Fleet Bot"}"""
        val user = github.user("own-tok")
        assertEquals("fleet-bot", user.login)
        assertEquals(CommitIdentity("Fleet Bot", "1+fleet-bot@users.noreply.github.com"), user.commitIdentity)
        assertEquals("GET" to "/user", seen.single().first to seen.single().second)
        status = 401; bodies[0] = """{"message":"Bad credentials"}"""
        assertThrows<Exception> { github.user("own-tok") }
        assertThrows<IllegalArgumentException> { github.user("") }
    }

    @Test
    fun `closed issues map from one GraphQL page, closing PRs included`() {
        bodies += """{"data":{"repository":{"issues":{"pageInfo":{"hasNextPage":true,"endCursor":"c1"},"nodes":[
            {"number":4,"title":"Done thing","body":"The details","url":"https://github.com/o/r/issues/4","stateReason":"COMPLETED","closedAt":"2026-09-24T10:00:00Z",
             "labels":{"nodes":[{"name":"chore"}]},"closedByPullRequestsReferences":{"nodes":[{"number":8,"url":"https://github.com/o/r/pull/8","state":"MERGED"}]}},
            {"number":3,"title":"Dropped","url":"https://github.com/o/r/issues/3","stateReason":"NOT_PLANNED","closedAt":"2026-09-23T10:00:00Z",
             "labels":{"nodes":[]},"closedByPullRequestsReferences":{"nodes":[]}}]}}}}"""
        val issues = github.closedIssues("o/r", 2)
        assertEquals(listOf(
            ClosedIssue(4, "Done thing", "https://github.com/o/r/issues/4", listOf("chore"), OffsetDateTime.parse("2026-09-24T10:00:00Z"),
                "completed", listOf(ClosingPull(8, "https://github.com/o/r/pull/8", "MERGED")), "The details"),
            ClosedIssue(3, "Dropped", "https://github.com/o/r/issues/3", emptyList(), OffsetDateTime.parse("2026-09-23T10:00:00Z"), "not_planned"),
        ), issues)
        val (method, path, body) = seen.single()
        assertEquals("POST" to "/graphql", method to path)
        assertTrue("states:CLOSED" in body && " body " in body && "\"f\":2" in body, body)
    }

    @Test
    fun `a ticket maps every field, and its comments are read page by page`() {
        fun page(next: String?, vararg comments: String) = """{"data":{"repository":{"issue":{
            "id":"I_5","number":5,"title":"Fix it","body":"b","bodyHTML":"<p>b</p>","state":"CLOSED","url":"https://github.com/o/r/issues/5",
            "createdAt":"2026-09-24T10:00:00Z","closedAt":"2026-09-24T11:00:00Z","labels":{"nodes":[{"name":"chore"}]},
            "comments":{"pageInfo":{"hasNextPage":${next != null},"endCursor":${next?.let { "\"$it\"" } ?: "null"}},"nodes":[${comments.joinToString(",")}]},
            "closedByPullRequestsReferences":{"nodes":[{"number":9,"title":"Fix it","url":"https://github.com/o/r/pull/9","state":"MERGED","isDraft":false,"reviewDecision":"APPROVED","mergeCommit":{"oid":"3ba86cf"}},
              {"number":10,"title":"Other","url":"https://github.com/o/r/pull/10","state":"OPEN","isDraft":true,"reviewDecision":null,"mergeCommit":null}]}}}}}"""
        fun comment(id: String, author: String?) = """{"id":"$id","url":"https://github.com/o/r/issues/5#$id",
            "author":${author?.let { "{\"login\":\"$it\"}" } ?: "null"},"createdAt":"2026-09-24T10:05:00Z","updatedAt":"2026-09-24T10:06:00Z",
            "body":"hi $id","bodyHTML":"<p>hi $id</p>"}"""
        bodies += page("c1", comment("IC_1", "fleet-bot")); bodies += page(null, comment("IC_2", null))
        val ticket = github.ticket("o/r", 5)!!
        assertEquals(listOf("IC_1", "IC_2"), ticket.comments.map { it.id })
        assertEquals(TicketComment("IC_2", "https://github.com/o/r/issues/5#IC_2", "ghost", OffsetDateTime.parse("2026-09-24T10:05:00Z"),
            OffsetDateTime.parse("2026-09-24T10:06:00Z"), "hi IC_2", "<p>hi IC_2</p>"), ticket.comments[1])
        assertEquals(ticket.copy(comments = emptyList()), TicketDetail("I_5", 5, "Fix it", "b", "<p>b</p>", "CLOSED",
            "https://github.com/o/r/issues/5", listOf("chore"), OffsetDateTime.parse("2026-09-24T10:00:00Z"),
            OffsetDateTime.parse("2026-09-24T11:00:00Z"), emptyList(),
            listOf(TicketPull(9, "Fix it", "https://github.com/o/r/pull/9", "MERGED", false, "APPROVED", "3ba86cf"),
                TicketPull(10, "Other", "https://github.com/o/r/pull/10", "OPEN", true, null, null))))
        assertEquals(2, seen.size)
        assertTrue("\"c\":\"c1\"" in seen[1].third, seen[1].third)
    }

    @Test
    fun `an issue GitHub does not have is null, and any other GraphQL error throws`() {
        bodies += """{"data":{"repository":{"issue":null}},"errors":[{"type":"NOT_FOUND","message":"Could not resolve to an Issue"}]}"""
        assertNull(github.ticket("o/r", 404))
        bodies[0] = """{"data":null,"errors":[{"type":"FORBIDDEN","message":"no"}]}"""
        assertThrows<IllegalStateException> { github.ticket("o/r", 5) }
    }

    private fun restComment(id: Long) = """{"id":$id,"node_id":"IC_$id","html_url":"https://github.com/o/r/issues/5#issuecomment-$id",
        "user":{"login":"fleet-bot","id":7},"created_at":"2026-09-24T10:05:00Z","updated_at":"2026-09-24T10:06:00Z","body":"hi $id"}"""

    @Test
    fun `REST comments are read page by page with the token and passed through as GitHub sent them`() {
        bodies += "[" + (1L..100L).joinToString(",") { restComment(3300000000 + it) } + "]"
        bodies += "[" + restComment(3300000101) + "]"
        val comments = github.restComments("o/r", 5)!!
        assertEquals(101, comments.size)
        assertEquals(listOf("GET" to "/repos/o/r/issues/5/comments", "GET" to "/repos/o/r/issues/5/comments"), seen.map { it.first to it.second })
        assertEquals<List<String?>>(listOf("per_page=100&page=1", "per_page=100&page=2"), queries)
        val last = comments.last()
        assertEquals(3300000101L, (last["id"] as Number).toLong())
        assertEquals("IC_3300000101", last["node_id"])
        assertEquals("https://github.com/o/r/issues/5#issuecomment-3300000101", last["html_url"])
        assertEquals("fleet-bot", (last["user"] as Map<*, *>)["login"])
        assertEquals("2026-09-24T10:05:00Z", last["created_at"])
        assertEquals("2026-09-24T10:06:00Z", last["updated_at"])
        assertEquals("hi 3300000101", last["body"])
    }

    @Test
    fun `one REST comment by id, null on a 404, and other failures throw`() {
        bodies += restComment(3300000007)
        val comment = github.restComment("o/r", 3300000007)!!
        assertEquals("GET" to "/repos/o/r/issues/comments/3300000007", seen.single().first to seen.single().second)
        assertEquals(3300000007L, (comment["id"] as Number).toLong())
        assertEquals("2026-09-24T10:06:00Z", comment["updated_at"])
        assertEquals("hi 3300000007", comment["body"])
        status = 404; bodies[0] = """{"message":"Not Found"}"""
        assertNull(github.restComment("o/r", 1))
        assertNull(github.restComments("o/r", 404))
        status = 500
        assertThrows<Exception> { github.restComment("o/r", 1) }
    }

    @Test
    fun `a ticket's closing PRs map with their head branch and files, and an unknown issue is null`() {
        bodies += """{"data":{"repository":{"defaultBranchRef":{"name":"main"},"issue":{"closedByPullRequestsReferences":{"nodes":[
            {"number":9,"url":"https://github.com/o/r/pull/9","state":"OPEN","headRefName":"smith/issue-5","files":{"nodes":[
              {"path":"docs/proposals/x.md","changeType":"ADDED"},{"path":"docs/proposals/x.html","changeType":"MODIFIED"}]}}]}}}}}"""
        assertEquals(TicketPulls("main", listOf(PullFiles(9, "https://github.com/o/r/pull/9", "OPEN", "smith/issue-5",
            mapOf("docs/proposals/x.md" to "ADDED", "docs/proposals/x.html" to "MODIFIED")))), github.ticketPulls("o/r", 5))
        assertEquals("POST" to "/graphql", seen.single().first to seen.single().second)
        assertTrue("headRefName" in seen.single().third && "\"i\":5" in seen.single().third, seen.single().third)
        bodies[0] = """{"data":{"repository":{"defaultBranchRef":{"name":"main"},"issue":null}},"errors":[{"type":"NOT_FOUND","message":"Could not resolve to an Issue"}]}"""
        assertNull(github.ticketPulls("o/r", 404))
    }

    @Test
    fun `a PR's check runs and commit statuses map onto checks, with its merge state`() {
        bodies += """{"data":{"repository":{"defaultBranchRef":{"name":"main"},"issue":{"closedByPullRequestsReferences":{"nodes":[
            {"number":9,"url":"u","state":"OPEN","headRefName":"b","mergeable":"MERGEABLE","mergeStateStatus":"BLOCKED","files":{"nodes":[]},
             "commits":{"nodes":[{"commit":{"statusCheckRollup":{"contexts":{"nodes":[
               {"__typename":"CheckRun","name":"engine","status":"IN_PROGRESS","conclusion":null,"startedAt":"2026-10-07T10:00:00Z","completedAt":null,"detailsUrl":"d1"},
               {"__typename":"CheckRun","name":"lint","status":"COMPLETED","conclusion":"SUCCESS","startedAt":"2026-10-07T09:58:00Z","completedAt":"2026-10-07T09:59:00Z","detailsUrl":"d2"},
               {"__typename":"StatusContext","context":"ci/old","state":"PENDING","createdAt":"2026-10-07T09:00:00Z","targetUrl":"t1"},
               {"__typename":"StatusContext","context":"ci/done","state":"FAILURE","createdAt":"2026-10-07T09:01:00Z","targetUrl":"t2"}]}}}}]}}]}}}}}"""
        val pull = github.ticketPulls("o/r", 5)!!.pulls.single()
        assertEquals("MERGEABLE" to "BLOCKED", pull.mergeable to pull.mergeStateStatus)
        assertEquals(listOf(
            PullCheck("engine", "in_progress", null, "2026-10-07T10:00:00Z", null, "d1"),
            PullCheck("lint", "completed", "success", "2026-10-07T09:58:00Z", "2026-10-07T09:59:00Z", "d2"),
            PullCheck("ci/old", "in_progress", null, "2026-10-07T09:00:00Z", null, "t1"),
            PullCheck("ci/done", "completed", "failure", "2026-10-07T09:01:00Z", null, "t2")), pull.checks)
        assertTrue("statusCheckRollup" in seen.single().third && "mergeStateStatus" in seen.single().third, seen.single().third)
    }

    @Test
    fun `a file's text is read raw at a ref, null on a 404`() {
        bodies += "<html>pitch</html>"
        assertEquals("<html>pitch</html>", github.fileText("o/r", "docs/proposals/x.html", "smith/issue-5"))
        assertEquals("GET" to "/repos/o/r/contents/docs/proposals/x.html", seen.single().first to seen.single().second)
        assertTrue(queries.single() in setOf("ref=smith%2Fissue-5", "ref=smith/issue-5"), queries.single())
        assertEquals("application/vnd.github.raw+json", accepts.single())
        status = 404; bodies[0] = """{"message":"Not Found"}"""
        assertNull(github.fileText("o/r", "docs/proposals/x.html", "main"))
    }

    @Test
    fun `import's issue read maps the title, body and author, a missing body as empty`() {
        bodies += """{"number":5,"title":"Build it","body":"the body","user":{"login":"outsider"},"html_url":"https://github.com/o/r/issues/5"}"""
        assertEquals(IssueSnapshot(5, "Build it", "the body", "outsider"), github.issue("o/r", 5))
        assertEquals(listOf("GET" to "/repos/o/r/issues/5"), seen.map { it.first to it.second })
        bodies[0] = """{"number":6,"title":"Empty","body":null,"user":{"login":"outsider"}}"""
        assertEquals("", github.issue("o/r", 6).body)
    }

    @Test
    fun `publish's create, edit and close are one call each, and an empty edit makes none`() {
        bodies += """{"number":7,"html_url":"https://github.com/o/r/issues/7","id":70}"""
        assertEquals(CreatedIssue(7, "https://github.com/o/r/issues/7", 70), github.create("o/r", NewIssue("t", "b")))
        github.edit("o/r", 7, title = "t2")
        github.edit("o/r", 7)
        github.close("o/r", 7, NOT_PLANNED)
        assertEquals(listOf("POST" to "/repos/o/r/issues", "PATCH" to "/repos/o/r/issues/7", "PATCH" to "/repos/o/r/issues/7"),
            seen.map { it.first to it.second })
        assertTrue("\"state_reason\":\"not_planned\"" in seen.last().third, seen.last().third)
    }

    @Test
    fun `a PR's diff is read with GitHub's diff media type, null on a 404`() {
        bodies += "diff --git a/x b/x"
        assertEquals("diff --git a/x b/x", github.pullDiff("o/r", 740))
        assertEquals("GET" to "/repos/o/r/pulls/740", seen.single().first to seen.single().second)
        assertEquals("application/vnd.github.diff", accepts.single())
        status = 404; bodies[0] = """{"message":"Not Found"}"""
        assertNull(github.pullDiff("o/r", 741))
    }

    @Test
    fun `pullsOnHead asks GraphQL for the PRs of one head branch and maps them`() {
        bodies += """{"data":{"repository":{"pullRequests":{"nodes":[
            {"number":12,"title":"Fix the view","url":"https://github.com/o/r/pull/12","state":"OPEN","isDraft":false,"reviewDecision":null}]}}}}"""
        val pulls = github.pullsOnHead("o/r", "smith/issue-100001")
        assertEquals(listOf(TicketPull(12, "Fix the view", "https://github.com/o/r/pull/12", "OPEN", false, null)), pulls)
        assertEquals("POST" to "/graphql", seen.single().first to seen.single().second)
        assertTrue(seen.single().third.contains("smith/issue-100001"))
        assertTrue(seen.single().third.contains("headRefName"))
    }
}

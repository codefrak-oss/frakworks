package com.codefrak.engine.github

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [RestRepoSetupGitHub] against a stub GitHub: what it asks, how each answer maps, and which refusals are "unknown". Plain JUnit, no Spring. */
class RestRepoSetupGitHubTest {
    private lateinit var stub: HttpServer
    /** Status and body by `METHOD path`, else by request path; a path absent answers 404. */
    private val answers = mutableMapOf<String, Pair<Int, String>>()
    private val seen = mutableListOf<Pair<String, String>>()
    private lateinit var github: RestRepoSetupGitHub

    @BeforeEach
    fun start() {
        stub = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        stub.createContext("/") { ex ->
            seen += ex.requestMethod to ex.requestURI.path
            val (status, body) = answers["${ex.requestMethod} ${ex.requestURI.path}"] ?: answers[ex.requestURI.path] ?: (404 to """{"message":"Not Found"}""")
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        stub.start()
        github = RestRepoSetupGitHub(RepoTokens { "t0k" }, "http://127.0.0.1:${stub.address.port}")
    }

    @AfterEach
    fun stop() = stub.stop(0)

    @Test
    fun `the repo maps its default branch, push and squash setting, and 404 is unreadable`() {
        answers["/repos/o/r"] = 200 to """{"default_branch":"trunk","permissions":{"admin":false,"push":true},"allow_squash_merge":false}"""
        assertEquals(RepoSettings("trunk", true, false), github.repo("o/r"))
        assertNull(github.repo("o/gone"))
    }

    @Test
    fun `protection, rules and hooks map, and a refused call is null`() {
        answers["/repos/o/r/branches/main"] = 200 to """{"name":"main","protected":true}"""
        answers["/repos/o/r/branches/main/protection"] = 200 to """{"required_pull_request_reviews":{"required_approving_review_count":1}}"""
        answers["/repos/o/r/rules/branches/main"] = 200 to
            """[{"type":"pull_request","parameters":{"required_approving_review_count":0,"allowed_merge_methods":["squash"]}},{"type":"deletion"}]"""
        answers["/repos/o/r/hooks"] = 200 to """[{"id":1,"active":true,"events":["issues","check_suite"],"config":{"url":"https://w/hooks/github"}}]"""
        assertEquals(true, github.isProtected("o/r", "main"))
        assertEquals(1, github.requiredReviews("o/r", "main"))
        assertEquals(listOf(BranchRule("pull_request", 0, listOf("squash")), BranchRule("deletion")), github.branchRules("o/r", "main"))
        assertEquals(listOf(RepoHook(listOf("issues", "check_suite"), true)), github.hooks("o/r"))

        answers["/repos/o/r/hooks"] = 403 to """{"message":"Must have admin rights to Repository."}"""
        assertNull(github.hooks("o/r"))
        answers.remove("/repos/o/r/branches/main/protection")
        assertNull(github.requiredReviews("o/r", "main"))
        answers["/repos/o/r/branches/main/protection"] = 200 to """{"required_status_checks":{"contexts":["build"]}}"""
        assertEquals(0, github.requiredReviews("o/r", "main"), "protected without required reviews")
    }

    @Test
    fun `labels are read and created`() {
        answers["/repos/o/r/labels"] = 200 to """[{"id":1,"name":"bug","color":"d73a4a"}]"""
        assertEquals(listOf("bug"), github.labels("o/r"))
        answers["/repos/o/r/labels"] = 201 to """{"id":2,"name":"chore"}"""
        github.createLabel("o/r", "chore", "c5def5")
        assertEquals("POST" to "/repos/o/r/labels", seen.last())
    }
}

package com.codefrak.engine.github

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.client.HttpClientErrorException
import java.net.InetSocketAddress
import java.time.Duration
import kotlin.test.assertEquals

/** RestGitHubPulls' merge against a stub GitHub: a moved base is merged again, a conflict is a [MergeConflict]. */
class RestGitHubMergeTest {
    private lateinit var stub: HttpServer
    /** Answers in order, status to body; the last one repeated. */
    private val answers = mutableListOf<Pair<Int, String>>()
    private var merges = 0
    private lateinit var github: RestGitHubPulls

    @BeforeEach
    fun start() {
        stub = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        stub.createContext("/") { ex ->
            ex.requestBody.readAllBytes(); merges++
            val (status, body) = if (answers.size > 1) answers.removeAt(0) else answers.single()
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        stub.start()
        github = RestGitHubPulls(RepoTokens { "t0k" }, "http://127.0.0.1:${stub.address.port}").also { it.baseModifiedBackoff = Duration.ofMillis(10) }
    }

    @AfterEach
    fun stop() = stub.stop(0)

    private val moved = 405 to """{"message":"Base branch was modified. Review and try the merge again.","status":"405"}"""
    private val merged = 200 to """{"sha":"abc123def456","merged":true,"message":"Pull Request successfully merged"}"""

    @Test
    fun `a base that moved under the merge is merged again, and the merge sha is answered`() {
        answers += listOf(moved, moved, merged)
        assertEquals("abc123def456", github.merge("acme/tickets", 7, "Tidy"))
        assertEquals(3, merges)
    }

    @Test
    fun `a merge answer without a sha is an error naming the PR`() {
        answers += 200 to """{"merged":true}"""
        val e = assertThrows<IllegalStateException> { github.merge("acme/tickets", 7, "Tidy") }
        assertEquals("GitHub returned no merge commit sha for acme/tickets#7", e.message)
    }

    @Test
    fun `a base that keeps moving is thrown after four attempts`() {
        answers += moved
        assertThrows<HttpClientErrorException> { github.merge("acme/tickets", 7, "Tidy") }
        assertEquals(4, merges)
    }

    @Test
    fun `a conflict is a MergeConflict at once, never retried`() {
        answers += 405 to """{"message":"Pull Request has merge conflicts","status":"405"}"""
        assertThrows<MergeConflict> { github.merge("acme/tickets", 7, "Tidy") }
        assertEquals(1, merges)
    }
}

package com.codefrak.engine.github

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RestRepoContents against a stub GitHub: what it asks, and how each answer maps. Plain JUnit, no Spring. */
class RestRepoContentsTest {
    private lateinit var stub: HttpServer
    private var status = 200
    /** Answers in order, the last one repeated. */
    private val bodies = mutableListOf<String>()
    private val seen = mutableListOf<Triple<String, String, String>>()
    private val queries = mutableListOf<String?>()
    private val rawPaths = mutableListOf<String>()
    private val accepts = mutableListOf<String?>()
    private lateinit var repoContents: RestRepoContents

    @BeforeEach
    fun start() {
        stub = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        stub.createContext("/") { ex ->
            seen += Triple(ex.requestMethod, ex.requestURI.path, ex.requestBody.readAllBytes().decodeToString())
            queries += ex.requestURI.rawQuery
            rawPaths += ex.requestURI.rawPath
            accepts += ex.requestHeaders.getFirst("Accept")
            val bytes = (if (bodies.size > 1) bodies.removeAt(0) else bodies.single()).toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        stub.start()
        repoContents = RestRepoContents(RepoTokens { "t0k" }, "http://127.0.0.1:${stub.address.port}")
    }

    @AfterEach
    fun stop() = stub.stop(0)

    @Test
    fun `a ref resolves through the commits list, the default branch with none, and a ref GitHub cannot resolve is null`() {
        bodies += """[{"sha":"c0ffee","commit":{}}]"""
        assertEquals("c0ffee", repoContents.commitSha("o/r", null))
        assertEquals("GET" to "/repos/o/r/commits", seen.last().first to seen.last().second)
        assertEquals("per_page=1", queries.last())
        assertEquals("c0ffee", repoContents.commitSha("o/r", "feature/x"))
        assertTrue(queries.last() in setOf("sha=feature%2Fx&per_page=1", "sha=feature/x&per_page=1"), queries.last())
        status = 404; bodies[0] = """{"message":"No commit found for SHA: nope"}"""
        assertNull(repoContents.commitSha("o/r", "nope"))
        status = 422
        assertNull(repoContents.commitSha("o/r", "nope"))
        status = 500
        assertThrows<Exception> { repoContents.commitSha("o/r", "nope") }
    }

    @Test
    fun `a tree is read recursively at a commit, with GitHub's truncated flag`() {
        bodies += """{"sha":"c0ffee","truncated":true,"tree":[{"path":"README.md","mode":"100644","type":"blob","sha":"r1","size":4},{"path":"src","mode":"040000","type":"tree","sha":"d1"}]}"""
        val tree = repoContents.repoTree("o/r", "c0ffee")
        assertEquals(RepoTree(true, listOf(RepoTreeEntry("README.md", "blob", "r1"), RepoTreeEntry("src", "tree", "d1"))), tree)
        assertEquals("GET" to "/repos/o/r/git/trees/c0ffee", seen.single().first to seen.single().second)
        assertEquals("recursive=1", queries.single())
    }

    @Test
    fun `a file's bytes are read raw at a ref, each path segment encoded, null on a 404`() {
        bodies += "# hi"
        assertEquals("# hi", repoContents.fileBytes("o/r", "docs/a b.md", "c0ffee")!!.decodeToString())
        assertEquals("/repos/o/r/contents/docs/a%20b.md", stubRawPath())
        assertEquals("ref=c0ffee", queries.single())
        assertEquals("application/vnd.github.raw+json", accepts.single())
        status = 404; bodies[0] = """{"message":"Not Found"}"""
        assertNull(repoContents.fileBytes("o/r", "docs/none.md", "c0ffee"))
    }

    @Test
    fun `a file reads as text and sha, and putFile updates it with sha, base64 content and the login as author`() {
        bodies += """{"sha":"b10b","content":"IyBy\ndWJyaWMK","encoding":"base64"}"""
        assertEquals(RepoFile("# rubric\n", "b10b"), repoContents.file("o/r", "ops/rubric/ssh.md"))
        assertEquals("GET" to "/repos/o/r/contents/ops/rubric/ssh.md", seen.last().first to seen.last().second)
        bodies[0] = """{"commit":{"sha":"c0mm1t"}}"""
        assertEquals("c0mm1t", repoContents.putFile("o/r", "ops/rubric/ssh.md", "x", "b10b", "teach", "alice"))
        val (method, path, body) = seen.last()
        assertEquals("PUT" to "/repos/o/r/contents/ops/rubric/ssh.md", method to path)
        assertTrue(""""sha":"b10b"""" in body && """"content":"eA=="""" in body && """"name":"alice"""" in body, body)
        status = 404; bodies[0] = """{"message":"Not Found"}"""
        assertNull(repoContents.file("o/r", "ops/rubric/none.md"))
    }

    @Test
    fun `a path with an empty, dot or dot-dot segment is refused before any request`() {
        val before = seen.size
        for (path in listOf("../../x", "a//b.md", "./a.md", "a/..", "/a.md")) {
            assertFailsWith<IllegalArgumentException> { repoContents.file("o/r", path) }
            assertFailsWith<IllegalArgumentException> { repoContents.putFile("o/r", path, "x", "s", "m", "alice") }
            assertFailsWith<IllegalArgumentException> { repoContents.fileBytes("o/r", path, "main") }
        }
        assertEquals(before, seen.size)
    }

    /** The last request's path as sent, before the stub decoded it. */
    private fun stubRawPath() = rawPaths.last()
}

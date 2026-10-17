package com.codefrak.engine.board

import com.codefrak.engine.github.RepoTokens
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.SandboxStatus
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.smith.SmithSandbox
import com.codefrak.engine.smith.SmithSpec
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.net.InetSocketAddress
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** A sandbox that knows only which address is which run; `down` is Docker not answering. */
private class GitAddressSandbox(val runs: Map<String, SmithCaller>, @Volatile var down: Boolean = false) : SmithSandbox {
    override fun start(spec: SmithSpec): String = error("unused")
    override fun status(handle: String): SandboxStatus = error("unused")
    override fun logs(handle: String, maxLines: Int): String = ""
    override fun output(handle: String, path: String): ByteArray? = null
    override fun remove(handle: String) {}
    override fun runAt(address: String): SmithCaller? = if (down) error("docker is down") else runs[address]
}

/** One request the stub upstream saw: method, path and query, the headers the proxy must pass, and the body. */
private data class Seen(val method: String, val path: String, val query: String?, val headers: Map<String, String?>, val body: ByteArray)

/**
 * The context's [TargetRepos] (with a token sealed on `o/r`) behind a controller whose upstream is a stub `HttpServer`
 * standing in for `github.com` (`engine.github.git-url`) and whose sandbox is [GitAddressSandbox].
 */
@SpringBootTest
@Import(com.codefrak.engine.github.ReadFakes::class)
class RepoGitControllerTest {
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var tokens: RepoTokens
    @Autowired lateinit var jdbc: JdbcClient

    private lateinit var stub: HttpServer
    private val seen = mutableListOf<Seen>()
    /** What the stub answers: status, content type, body; `echo` answers the request's body in chunks instead. */
    private var status = 200
    private var contentType = "application/x-git-upload-pack-advertisement"
    private var answer = "001e# service=git-upload-pack\n0000".toByteArray()
    private var echo = false
    private val sandbox = GitAddressSandbox(mapOf(
        "172.18.0.7" to SmithCaller("run-own", 7, "O/R"), "172.18.0.8" to SmithCaller("run-other", 8, "o/elsewhere")))
    private lateinit var mvc: MockMvc
    private val basic = "Basic " + Base64.getEncoder().encodeToString("x-access-token:github_pat_git".toByteArray())

    @BeforeEach
    fun start() {
        stub = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        stub.createContext("/") { ex -> serve(ex) }
        stub.start()
        seen.clear(); status = 200; echo = false; sandbox.down = false; stopped = false
        contentType = "application/x-git-upload-pack-advertisement"
        answer = "001e# service=git-upload-pack\n0000".toByteArray()
        jdbc.sql("DELETE FROM engine.target_repo").update()
        repos.add("o/r"); repos.setToken("o/r", "github_pat_git")
        repos.add("o/bare")
        mvc = MockMvcBuilders.standaloneSetup(RepoGitController(repos, tokens, sandbox, "http://127.0.0.1:${stub.address.port}")).build()
    }

    private var stopped = false

    @AfterEach
    fun stop() { if (!stopped) stub.stop(0); stopped = true }

    private fun serve(ex: HttpExchange) {
        val body = ex.requestBody.readAllBytes()
        seen += Seen(ex.requestMethod, ex.requestURI.path, ex.requestURI.rawQuery,
            listOf("Authorization", "Content-Type", "Content-Encoding", "Git-Protocol", "Accept", "User-Agent").associateWith { ex.requestHeaders.getFirst(it) }, body)
        ex.responseHeaders.add("Content-Type", contentType)
        if (echo) {
            // Chunked, three pieces, flushed each: the proxy must hand them on without waiting for the end.
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.use { out -> body.toList().chunked(maxOf(1, body.size / 3)).forEach { out.write(it.toByteArray()); out.flush() } }
            return
        }
        ex.sendResponseHeaders(status, if (answer.isEmpty()) -1 else answer.size.toLong())
        ex.responseBody.use { it.write(answer) }
    }

    private fun from(address: String) = RequestPostProcessor { it.remoteAddr = address; it }

    @Test
    fun `the advertisement is forwarded with the repo's token as basic auth, Git-Protocol and Accept passed, and comes back as GitHub sent it`() {
        mvc.get("/repos/O/R/git/info/refs?service=git-upload-pack") {
            header("Git-Protocol", "version=2"); header("Accept", "*/*")
        }.andExpect {
            status { isOk() }
            header { string("Content-Type", "application/x-git-upload-pack-advertisement") }
            header { string("Cache-Control", "no-cache") }
            content { bytes(answer) }
        }
        val req = seen.single()
        assertEquals("GET" to "/o/r.git/info/refs", req.method to req.path)
        assertEquals("service=git-upload-pack", req.query)
        assertEquals(basic, req.headers["Authorization"])
        assertEquals("version=2", req.headers["Git-Protocol"])
        assertEquals("*/*", req.headers["Accept"])
        assertEquals(true, req.headers["User-Agent"]!!.startsWith("git/"), "GitHub wants a git agent")
    }

    @Test
    fun `upload-pack is a streamed POST whose body reaches GitHub and whose chunked answer comes back whole`() {
        echo = true; contentType = "application/x-git-upload-pack-result"
        val want = ByteArray(50_000) { (it % 251).toByte() }
        mvc.post("/repos/o/r/git/git-upload-pack") {
            header("Content-Type", "application/x-git-upload-pack-request"); header("Content-Encoding", "identity")
            header("Git-Protocol", "version=2")
            content = want
        }.andExpect {
            status { isOk() }
            header { string("Content-Type", "application/x-git-upload-pack-result") }
            content { bytes(want) }
        }
        val req = seen.single()
        assertEquals("POST" to "/o/r.git/git-upload-pack", req.method to req.path)
        assertContentEquals(want, req.body)
        assertEquals(basic, req.headers["Authorization"])
        assertEquals("application/x-git-upload-pack-request", req.headers["Content-Type"])
        assertEquals("identity", req.headers["Content-Encoding"])
        assertEquals("version=2", req.headers["Git-Protocol"])
    }

    @Test
    fun `receive-pack is 403 on both routes and GitHub is never asked`() {
        mvc.get("/repos/o/r/git/info/refs?service=git-receive-pack").andExpect { status { isForbidden() }; jsonPath("$.error") { exists() } }
        mvc.get("/repos/o/r/git/info/refs").andExpect { status { isForbidden() }; jsonPath("$.error") { exists() } }
        mvc.post("/repos/o/r/git/git-receive-pack") { content = "0000".toByteArray() }.andExpect { status { isForbidden() }; jsonPath("$.error") { exists() } }
        assertEquals(emptyList(), seen)
    }

    @Test
    fun `a repo that is not a target repo, or has no token, is 404 and GitHub is never asked`() {
        mvc.get("/repos/o/none/git/info/refs?service=git-upload-pack").andExpect {
            status { isNotFound() }; jsonPath("$.error") { value("o/none is not a target repo") }
        }
        mvc.get("/repos/o/bare/git/info/refs?service=git-upload-pack").andExpect {
            status { isNotFound() }; jsonPath("$.error") { value("o/bare has no token: PUT /repos/o/bare/token") }
        }
        mvc.post("/repos/o/none/git/git-upload-pack") { content = "0000".toByteArray() }.andExpect { status { isNotFound() } }
        assertEquals(emptyList(), seen)
    }

    @Test
    fun `GitHub answering 5xx, or not answering, is a 502 with an error, and its 404 goes through as it is`() {
        status = 500; answer = "boom".toByteArray()
        mvc.get("/repos/o/r/git/info/refs?service=git-upload-pack").andExpect {
            status { isBadGateway() }; jsonPath("$.error") { value("GitHub: HTTP 500") }
        }
        status = 404; contentType = "text/plain"; answer = "Repository not found.".toByteArray()
        mvc.get("/repos/o/r/git/info/refs?service=git-upload-pack").andExpect {
            status { isNotFound() }; content { string("Repository not found.") }
        }
        stop()
        mvc.get("/repos/o/r/git/info/refs?service=git-upload-pack").andExpect { status { isBadGateway() }; jsonPath("$.error") { exists() } }
        mvc.post("/repos/o/r/git/git-upload-pack") { content = "0000".toByteArray() }.andExpect { status { isBadGateway() }; jsonPath("$.error") { exists() } }
    }

    @Test
    fun `a Smith sandbox clones its own repo, another repo's is a 403, and Docker down is a 503`() {
        mvc.get("/repos/o/r/git/info/refs?service=git-upload-pack") { with(from("172.18.0.7")) }.andExpect { status { isOk() } }
        mvc.get("/repos/o/r/git/info/refs?service=git-upload-pack") { with(from("172.18.0.8")) }.andExpect {
            status { isForbidden() }; jsonPath("$.error") { value("a Smith sandbox (run run-other) reads its own repo's files only") }
        }
        mvc.post("/repos/o/r/git/git-upload-pack") { with(from("172.18.0.8")); content = "0000".toByteArray() }.andExpect { status { isForbidden() } }
        sandbox.down = true
        mvc.get("/repos/o/r/git/info/refs?service=git-upload-pack").andExpect {
            status { isServiceUnavailable() }; jsonPath("$.error") { value("cannot tell whether the caller is a Smith sandbox") }
        }
        mvc.post("/repos/o/r/git/git-upload-pack") { content = "0000".toByteArray() }.andExpect { status { isServiceUnavailable() } }
        assertEquals(1, seen.size, "only the Smith's own-repo read reached GitHub")
    }
}

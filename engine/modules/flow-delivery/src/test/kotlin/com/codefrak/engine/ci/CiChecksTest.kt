package com.codefrak.engine.ci

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.HttpClientErrorException
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The check settings, GitHub's check runs and job logs against a stub, and the log cut down for a finding. Plain JUnit, no Spring. */
class CiChecksTest {
    /** A target repo table with a check on `acme/tools` (`build` on src/) and one with no paths on `acme/docs`. */
    private val rows = RepoCiChecks { repo ->
        when (repo.lowercase()) {
            "acme/tools" -> CiCheck("build", listOf("src/"))
            "acme/docs" -> CiCheck("site", emptyList())
            else -> null
        }
    }

    @Test
    fun `a repo's table row alone names its check`() {
        val checks = CiChecks(rows, "PT3H", true)
        assertEquals(CiCheck("build", listOf("src/")), checks.check("acme/tools"))
        assertEquals("build", checks.needed("acme/tools", listOf("src/a.kt")))
        assertEquals("build", checks.needed("Acme/Tools", listOf("README.md", "src/a.kt")))
        assertNull(checks.needed("acme/tools", listOf("README.md")))
    }

    @Test
    fun `a repo whose row sets no check has none, whatever the branch changes`() {
        val checks = CiChecks(rows, "PT3H", true)
        assertNull(checks.check("codefrak-oss/frakworks"))
        assertNull(checks.needed("codefrak-oss/frakworks", listOf("engine/x.kt")))
    }

    @Test
    fun `a check with no paths is needed for every path`() {
        val checks = CiChecks(rows, "PT3H", true)
        assertEquals("site", checks.needed("acme/docs", listOf("README.md")))
        assertEquals("site", checks.needed("acme/docs", listOf("anything/at/all.txt")))
    }

    @Test
    fun `enabled false turns every check off`() {
        val checks = CiChecks(rows, "PT3H", false)
        assertNull(checks.needed("acme/tools", listOf("src/a.kt")))
        assertNull(checks.needed("acme/docs", listOf("README.md")))
    }

    // ---- GitHub, against a stub ----

    private lateinit var stub: HttpServer
    private val seen = mutableListOf<Triple<String, String?, String?>>()
    private val methods = mutableListOf<String>()
    private var runs = """{"total_count":0,"check_runs":[]}"""
    private lateinit var github: RestCheckRuns

    @BeforeEach
    fun start() {
        stub = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${stub.address.port}"
        stub.createContext("/") { ex ->
            seen += Triple(ex.requestURI.path, ex.requestURI.rawQuery, ex.requestHeaders.getFirst("Authorization"))
            methods += ex.requestMethod
            val (status, body) = when {
                ex.requestURI.path.endsWith("/jobs/99/rerun") -> { ex.responseHeaders.add("Content-Type", "application/json")
                    403 to """{"message":"The workflow run containing this job is already running","documentation_url":"https://docs.github.com"}""" }
                ex.requestURI.path.endsWith("/jobs/98/rerun") -> { ex.responseHeaders.add("Content-Type", "application/json")
                    403 to """{"message":"Resource not accessible by integration"}""" }
                ex.requestURI.path.endsWith("/rerun") -> 201 to ""
                ex.requestURI.path.endsWith("/logs") -> { ex.responseHeaders.add("Location", "$base/signed/log?sig=x"); 302 to "" }
                ex.requestURI.path == "/signed/log" -> 200 to "the log"
                else -> { ex.responseHeaders.add("Content-Type", "application/json"); 200 to runs }
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        stub.start()
        github = RestCheckRuns(com.codefrak.engine.github.RepoTokens { "t0k" }, base)
    }

    @AfterEach
    fun stop() = stub.stop(0)

    @Test
    fun `the latest check run is asked for by name on the sha, and none started is null`() {
        assertNull(github.latest("o/r", "abc123", "engine"))
        assertEquals("/repos/o/r/commits/abc123/check-runs", seen.single().first)
        assertTrue("check_name=engine" in seen.single().second!!, seen.single().second)
        assertTrue("filter=latest" in seen.single().second!!)
        assertEquals("Bearer t0k", seen.single().third)

        runs = """{"total_count":2,"check_runs":[
            {"id":11,"name":"engine","status":"completed","conclusion":"failure","html_url":"https://github.com/o/r/runs/11"},
            {"id":12,"name":"engine","status":"in_progress","conclusion":null,"html_url":"https://github.com/o/r/runs/12"}]}"""
        assertEquals(CheckRun(12, "in_progress", null, "https://github.com/o/r/runs/12"), github.latest("o/r", "abc123", "engine"))
    }

    @Test
    fun `the job log follows GitHub's redirect without the token`() {
        assertEquals("the log", github.log("o/r", 11))
        assertEquals("/repos/o/r/actions/jobs/11/logs" to "Bearer t0k", seen[0].first to seen[0].third)
        assertEquals("/signed/log" to null, seen[1].first to seen[1].third, "the signed URL gets no token")
    }

    @Test
    fun `a re-run posts to the Actions job behind the check run, with the token`() {
        github.rerun("o/r", 11)
        assertEquals(Triple("/repos/o/r/actions/jobs/11/rerun", null, "Bearer t0k"), seen.single())
        assertEquals("POST", methods.single())
    }

    @Test
    fun `a re-run GitHub refuses as already running is reported as such, and any other refusal still raises`() {
        val e = assertFailsWith<RerunAlreadyRunning> { github.rerun("o/r", 99) }
        assertEquals(99, e.checkRunId)
        assertTrue("already running" in e.message!!, e.message)
        val other = assertFailsWith<HttpClientErrorException> { github.rerun("o/r", 98) }
        assertEquals(403, other.statusCode.value())
    }

    // ---- Jev's triage, as a rule on its pick ----

    @Test
    fun `infrastructure or flaky at the floor re-runs once a round, and anything else goes to the work`() {
        assertEquals(CiTriage.Triage(true, "flaky: re-ran the check"), CiTriage.read(CiTriage.FLAKY, 0.8, 0.8, true))
        assertEquals(CiTriage.Triage(true, "infrastructure: re-ran the check"), CiTriage.read(CiTriage.INFRASTRUCTURE, 0.99, 0.8, true))
        assertEquals(CiTriage.Triage(false, "infrastructure, re-ran this round already: to the work"),
            CiTriage.read(CiTriage.INFRASTRUCTURE, 0.99, 0.8, false))
        assertEquals(CiTriage.Triage(false, "flaky below floor 0.8: to the work"), CiTriage.read(CiTriage.FLAKY, 0.79, 0.8, true))
        assertEquals(CiTriage.Triage(false, "code: to the work"), CiTriage.read(CiTriage.CODE, 0.99, 0.8, true))
        assertEquals(CiTriage.Triage(false, "no answer: to the work"), CiTriage.read(null, null, 0.8, true))
        assertEquals(CiTriage.Triage(false, "no answer: to the work"), CiTriage.read(CiTriage.FLAKY, null, 0.8, true))
        assertFalse(CiTriage.read(CiTriage.FLAKY, 1.0, 1.01, true).rerun, "a floor above 1 turns the re-run off")
        assertEquals(setOf("code", "infrastructure", "flaky"), (CiTriage.CAUSES.criteria as Map<*, *>).keys)
    }

    // ---- the log, cut down ----

    private val failedBuild = """
        2026-09-26T10:00:00.1234567Z #11 [build 5/5] RUN --mount=type=cache,target=/gradle-cache gradle test --no-daemon
        2026-09-26T10:00:01.0000000Z #11 12.3 > Task :compileKotlin
        2026-09-26T10:03:00.0000000Z #11 190.1 ChoreProcessTest > a chore is worked() FAILED
        2026-09-26T10:03:00.0000000Z #11 190.1     org.opentest4j.AssertionFailedError at ChoreProcessTest.kt:190
        2026-09-26T10:03:02.0000000Z #11 192.4 CiChecksTest > the setting names a check() FAILED
        2026-09-26T10:03:05.0000000Z #11 195.0 64 tests completed, 2 failed
        2026-09-26T10:03:05.0000000Z #11 195.0 FAILURE: Build failed with an exception.
        2026-09-26T10:03:06.0000000Z #11 ERROR: process "/bin/sh -c gradle test --no-daemon" did not complete successfully: exit code: 1
    """.trimIndent()

    @Test
    fun `a failed build's log gives the failing tests, without timestamps or BuildKit prefixes, then its end`() {
        val lines = CiLog.clean(failedBuild)
        assertEquals("> Task :compileKotlin", lines[1])
        assertEquals(listOf("ChoreProcessTest > a chore is worked() FAILED", "CiChecksTest > the setting names a check() FAILED"), CiLog.failures(lines))
        assertEquals(listOf("e: file:///src/A.kt:3:5 Unresolved reference 'x'."), CiLog.failures(CiLog.clean("#9 40.2 e: file:///src/A.kt:3:5 Unresolved reference 'x'.")))

        val ask = CiLog.ask("engine", "failure", "0123456789abcdef", "https://github.com/o/r/runs/11", failedBuild)
        assertTrue(ask.startsWith("The engine check `engine` (built and tested on the build runners) ended `failure` on the pushed head 0123456789ab: https://github.com/o/r/runs/11."), ask)
        assertTrue("- ChoreProcessTest > a chore is worked() FAILED" in ask, ask)
        assertTrue("FAILURE: Build failed with an exception." in ask)
        assertFalse("2026-09-26T" in ask, "timestamps are stripped")
        assertTrue("Run the failing test class in the sandbox" in ask)
        assertFalse("read_ci_log" in ask, "no check run id, no tool to name")
        val withId = CiLog.ask("engine", "failure", "0123456789abcdef", "https://github.com/o/r/runs/11", failedBuild, 11)
        assertTrue("The whole job log, when the excerpt below does not show the cause: read_ci_log({check_run: 11}) on the frakworks MCP server." in withId, withId)
    }

    @Test
    fun `the tail is bounded, and an unreadable log points at the check's page`() {
        val long = (1..500).joinToString("\n") { "line $it " + "x".repeat(100) }
        val tail = CiLog.tail(CiLog.clean(long))
        assertTrue(tail.length <= CiLog.TAIL_CHARS)
        assertTrue(tail.endsWith("line 500 " + "x".repeat(100)))
        assertTrue(CiLog.tail(CiLog.clean((1..500).joinToString("\n") { "l$it" })).lines().size == CiLog.TAIL_LINES)

        val ask = CiLog.ask("engine", "timed_out", "abc", "https://github.com/o/r/runs/11", null)
        assertTrue("The job log could not be read" in ask, ask)
    }
}

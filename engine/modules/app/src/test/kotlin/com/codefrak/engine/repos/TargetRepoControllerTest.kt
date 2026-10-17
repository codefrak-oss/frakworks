package com.codefrak.engine.repos

import com.codefrak.engine.TestTokens
import com.codefrak.engine.accounts.SecretsKey
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.events.EventRecorder
import com.codefrak.engine.github.FakeIssueDetails
import com.codefrak.engine.github.FakeRepoSetupGitHub
import com.codefrak.engine.github.SmithTokens
import com.codefrak.engine.github.ReadFakes
import com.codefrak.engine.github.RepoPermissions
import com.codefrak.engine.github.RepoSettings
import com.codefrak.engine.github.RepoTokens
import com.codefrak.engine.github.TokenIdentity
import com.codefrak.engine.mcp.FrakworksMcp
import com.codefrak.engine.mcp.McpCaller
import com.codefrak.engine.mcp.McpToolResult
import com.codefrak.engine.mcp.mcpJson
import com.codefrak.engine.smith.SandboxStatus
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.smith.SmithSandbox
import com.codefrak.engine.smith.SmithSpec
import com.codefrak.engine.trigger.ScanCosts
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.RuntimeService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A sandbox that knows only which address is which run; `down` is Docker not answering. */
private class AddressSandbox(val runs: Map<String, SmithCaller>, @Volatile var down: Boolean = false) : SmithSandbox {
    override fun start(spec: SmithSpec): String = error("unused")
    override fun status(handle: String): SandboxStatus = error("unused")
    override fun logs(handle: String, maxLines: Int): String = ""
    override fun output(handle: String, path: String): ByteArray? = null
    override fun remove(handle: String) {}
    override fun runAt(address: String): SmithCaller? = if (down) error("docker is down") else runs[address]
}

/** `/repos` over MockMvc. Jobs are not executed, so a started `smith-run` stays running at its first (async) step. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ReadFakes::class)
class TargetRepoControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var github: FakeRepoSetupGitHub
    @Autowired lateinit var tokens: RepoTokens
    @Autowired lateinit var controller: TargetRepoController
    @Autowired lateinit var running: RunningFlows
    @Autowired lateinit var secrets: SecretsKey
    @Autowired lateinit var costs: ScanCosts
    @Autowired lateinit var access: Access
    @Autowired lateinit var bus: EngineEvents
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var mcp: FrakworksMcp

    // Repos of this test's own: the H2 is shared with the other Spring tests.
    private val repo = "acme/repoctl"

    /**
     * Sending `securityReview` asks who is calling, so those requests go to a controller whose sandbox is
     * [AddressSandbox]: the context's own is Docker's, which a test cannot ask.
     */
    private val sandbox = AddressSandbox(mapOf("172.18.0.7" to SmithCaller("run-own", 7, repo)))
    private lateinit var guarded: MockMvc
    private fun from(address: String) = RequestPostProcessor { it.remoteAddr = address; it }

    @BeforeEach
    fun reset() {
        runtime.createProcessInstanceQuery().variableValueEquals("repo", repo).matchVariableValuesIgnoreCase().list().forEach { runtime.deleteProcessInstance(it.id, "test reset") }
        listOf(repo, "acme/repoctl-checked", "acme/repoctl-bad", "acme/repoctl-unreviewed", "acme/repoctl-fast", "acme/repoctl-events").forEach { repos.remove(it) }
        repos.add(repo)
        github.reset()
        github.repos[repo] = RepoSettings("main", push = true, allowSquashMerge = true)
        sandbox.down = false
        guarded = MockMvcBuilders.standaloneSetup(TargetRepoController(repos, running, secrets, github, sandbox, costs, bus)).build()
    }

    /** The stream (engine/README.md, "Events"): one `repo` frame per write, `changed` naming the fields. */
    @Test
    fun `the stream says a repo was added, changed naming what changed, and removed`() = EventRecorder(bus).use { rec ->
        val events = "acme/repoctl-events"
        val json = MediaType.APPLICATION_JSON
        guarded.post("/repos") { contentType = json; content = """{"repo":"$events"}""" }.andExpect { status { isCreated() } }
        guarded.post("/repos") { contentType = json; content = """{"repo":"$events"}""" }.andExpect { status { isOk() } }
        guarded.patch("/repos/$events") { contentType = json; content = """{"ciCheck":"build","ciPaths":["src/"]}""" }.andExpect { status { isOk() } }
        guarded.patch("/repos/$events") { contentType = json; content = """{"securityReview":false,"scanIntervalMinutes":5}""" }.andExpect { status { isOk() } }
        guarded.put("/repos/$events/token") { contentType = json; content = """{"token":"t0k","verify":false}""" }.andExpect { status { isNoContent() } }
        guarded.put("/repos/$events/webhook-secret") { contentType = json; content = """{"secret":"0123456789abcdef0123456789abcdef"}""" }.andExpect { status { isNoContent() } }
        guarded.delete("/repos/$events/webhook-secret").andExpect { status { isNoContent() } }
        guarded.delete("/repos/$events/token").andExpect { status { isNoContent() } }
        guarded.delete("/repos/$events").andExpect { status { isNoContent() } }
        guarded.delete("/repos/$events").andExpect { status { isNotFound() } }
        val frames = rec.of("repo")
        assertTrue(frames.all { it.str("repo") == events }, frames.toString())
        assertEquals(listOf("added", "changed", "changed", "changed", "changed", "changed", "changed", "removed"), frames.map { it.state })
        assertEquals(listOf(null, listOf("ciCheck"), listOf("securityReview", "scanIntervalMinutes"), listOf("token"), listOf("webhookSecret"),
            listOf("webhookSecret"), listOf("token"), null), frames.map { f -> f.data["fields"]?.takeUnless { it.isNull }?.toList()?.map { it.asString() } })
    }

    private fun putToken(path: String, body: String) = mvc.put(path) { contentType = MediaType.APPLICATION_JSON; content = body }

    /** tokenPermission for a repo with a token, null for one without; callerPermission only with a bearer token's login. */
    @Test
    fun `GET repos reports the token's and the caller's permission`() {
        val bare = "acme/repoctl-bare"
        repos.remove(bare); repos.add(bare)
        guarded.put("/repos/$repo/token") { contentType = MediaType.APPLICATION_JSON; content = """{"token":"t0k","verify":false}""" }
            .andExpect { status { isNoContent() } }
        val details = FakeIssueDetails().apply {
            permissions[repo to "fleet-bot"] = "write"
            permissions[repo to "lundmatt1"] = "admin"
        }
        val withPermissions = TargetRepoController(repos, running, secrets, github, sandbox, costs, bus, RepoPermissions(details, TokenIdentity(details, tokens)))
        try {
            val plain = withPermissions.list().associateBy { it.fullName }
            assertEquals("write", plain[repo]!!.tokenPermission)
            assertNull(plain[repo]!!.callerPermission, "no token, no caller permission")
            assertNull(plain[bare]!!.tokenPermission, "no token")
            val asked = withPermissions.listFor("lundmatt1").associateBy { it.fullName }
            assertEquals("admin", asked[repo]!!.callerPermission)
            assertNull(asked[bare]!!.callerPermission)
            MockMvcBuilders.standaloneSetup(withPermissions).build().get("/repos") { with(TestTokens.proven("lundmatt1")) }.andExpect {
                status { isOk() }
                jsonPath("$[?(@.fullName == '$repo')].tokenPermission") { value("write") }
                jsonPath("$[?(@.fullName == '$repo')].callerPermission") { value("admin") }
            }
            assertEquals(listOf(repo to "fleet-bot", repo to "lundmatt1"), details.permissionAsks.filter { it.first == repo },
                "the second and third reads come from the cache (the shared H2 holds other tests' repos too)")
        } finally {
            repos.remove(bare)
        }
    }

    @Test
    fun `PUT token tries it against GitHub, seals it on the row, and never shows it again`() {
        assertNull(tokens.resolve(repo), "before: no token")
        putToken("/repos/Acme/RepoCtl/token", """{"token":"github_pat_own"}""").andExpect { status { isNoContent() } }
        assertEquals(listOf("github_pat_own"), github.probed)
        assertTrue(row(repo).hasToken)
        assertTrue(row(repo).tokenSetAt != null)
        assertEquals("github_pat_own", tokens.resolve("ACME/repoctl"), "found ignoring case")
        mvc.get("/repos").andExpect {
            status { isOk() }
            jsonPath("$[?(@.fullName == '$repo')].hasToken") { value(true) }
            jsonPath("$[?(@.fullName == '$repo')].token") { doesNotExist() }
        }
        mvc.get("/repos/$repo/setup").andExpect {
            status { isOk() }
            jsonPath("$.hasToken") { value(true) }
            jsonPath("$.readable") { value(true) }
        }

        mvc.delete("/repos/acme/REPOCTL/token").andExpect { status { isNoContent() } }
        assertFalse(row(repo).hasToken)
        assertNull(row(repo).tokenSetAt)
        assertNull(tokens.resolve(repo), "no token again, and nothing stands in for it")
        assertEquals("no GitHub token for $repo: PUT /repos/$repo/token", assertFailsWith<IllegalStateException> { tokens.forRepo(repo) }.message)
        mvc.get("/repos/$repo/setup").andExpect {
            jsonPath("$.hasToken") { value(false) }
            jsonPath("$.readable") { value(false) }
        }
    }

    @Test
    fun `PUT smith-token seals a token that reads and pushes, refuses one that cannot push, asks nothing about issues, and forSmith never falls back`() {
        putToken("/repos/$repo/token", """{"token":"github_pat_repo","verify":false}""").andExpect { status { isNoContent() } }
        assertEquals("no Smith token for $repo: PUT /repos/$repo/smith-token",
            assertFailsWith<IllegalStateException> { tokens.forSmith(repo) }.message, "the repo token never stands in")
        try {
            github.badTokens += "github_pat_unreadable"
            putToken("/repos/$repo/smith-token", """{"token":"github_pat_unreadable"}""").andExpect { status { isBadRequest() } }
            github.repos[repo] = RepoSettings("main", push = false, allowSquashMerge = true, isPrivate = true)
            putToken("/repos/$repo/smith-token", """{"token":"github_pat_readonly"}""").andExpect { status { isBadRequest() } }
            github.repos[repo] = RepoSettings("main", push = true, allowSquashMerge = true, isPrivate = true)
            assertFalse(row(repo).hasSmithToken, "nothing was saved")

            putToken("/repos/Acme/RepoCtl/smith-token", """{"token":"github_pat_smith"}""").andExpect { status { isNoContent() } }
            assertTrue(row(repo).hasSmithToken)
            assertEquals("github_pat_smith", tokens.forSmith(repo))
            assertEquals("github_pat_repo", tokens.forRepo(repo), "the engine keeps the repo token")
            mvc.get("/repos").andExpect {
                jsonPath("$[?(@.fullName == '$repo')].hasSmithToken") { value(true) }
                jsonPath("$[?(@.fullName == '$repo')].smithToken") { doesNotExist() }
            }
            assertEquals("github_pat_smith", SmithTokens(tokens, github).forRun(repo))
            github.badTokens += "github_pat_smith"
            assertFailsWith<IllegalStateException>("unreadable since it was set: refused before the run") { SmithTokens(tokens, github).forRun(repo) }
            github.badTokens -= "github_pat_smith"

            mvc.delete("/repos/$repo/smith-token").andExpect { status { isNoContent() } }
            assertFalse(row(repo).hasSmithToken)
            assertFailsWith<IllegalStateException> { tokens.forSmith(repo) }
            mvc.delete("/repos/acme/nowhere/smith-token").andExpect { status { isNotFound() } }
        } finally {
            repos.clearSmithToken(repo); repos.clearToken(repo)
        }
    }

    @Test
    fun `PUT token that GitHub refuses is 400 and stores nothing, verify false stores it untried`() {
        github.badTokens += "github_pat_bad"
        putToken("/repos/$repo/token", """{"token":"github_pat_bad"}""").andExpect { status { isBadRequest() } }
        assertFalse(row(repo).hasToken)
        github.repos[repo] = RepoSettings("main", push = false, allowSquashMerge = true)
        putToken("/repos/$repo/token", """{"token":"github_pat_readonly"}""").andExpect { status { isBadRequest() } }
        assertFalse(row(repo).hasToken, "read without push is refused too")
        putToken("/repos/$repo/token", """{"token":"github_pat_bad","verify":false}""").andExpect { status { isNoContent() } }
        assertTrue(row(repo).hasToken)
        assertEquals(listOf("github_pat_bad", "github_pat_readonly"), github.probed, "the unverified one was never tried")
    }

    @Test
    fun `PUT token is 404 for a repo that is not a target repo, 400 for a blank or spaced token`() {
        putToken("/repos/acme/nowhere/token", """{"token":"github_pat_x"}""").andExpect { status { isNotFound() } }
        for (body in listOf("""{"token":""}""", """{"token":"  "}""", """{"token":"two words"}""", """{}""")) {
            putToken("/repos/$repo/token", body).andExpect { status { isBadRequest() } }
        }
        assertFalse(row(repo).hasToken)
        mvc.delete("/repos/acme/nowhere/token").andExpect { status { isNotFound() } }
    }

    @Test
    fun `a removed repo takes its token with it`() {
        putToken("/repos/$repo/token", """{"token":"github_pat_own"}""").andExpect { status { isNoContent() } }
        mvc.delete("/repos/$repo").andExpect { status { isNoContent() } }
        assertNull(tokens.resolve(repo))
    }

    private fun row(name: String) = repos.list().single { it.fullName == name }

    @Test
    fun `POST takes a check and its paths, and GET lists them per row`() {
        mvc.post("/repos") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"repo":"acme/repoctl-checked","ciCheck":"build","ciPaths":["src/","lib/"]}"""
        }.andExpect { status { isCreated() } }
        mvc.get("/repos").andExpect {
            status { isOk() }
            jsonPath("$[?(@.fullName == 'acme/repoctl-checked')].ciCheck") { value("build") }
            jsonPath("$[?(@.fullName == 'acme/repoctl-checked')].ciPaths[0]") { value("src/") }
            jsonPath("$[?(@.fullName == 'acme/repoctl-checked')].ciPaths[1]") { value("lib/") }
        }
        assertNull(row(repo).ciCheck, "a repo added without them has none")
        assertEquals(emptyList(), row(repo).ciPaths)
    }

    @Test
    fun `POST with malformed check settings is 400 and adds nothing`() {
        for (body in listOf("""{"repo":"acme/repoctl-bad","ciPaths":{"src/":1}}""", """{"repo":"acme/repoctl-bad","ciPaths":["src/",""]}""",
                            """{"repo":"acme/repoctl-bad","ciCheck":"  "}""")) {
            mvc.post("/repos") { contentType = MediaType.APPLICATION_JSON; content = body }.andExpect { status { isBadRequest() } }
        }
        assertFalse(repos.contains("acme/repoctl-bad"))
    }

    @Test
    fun `PATCH sets, clears and leaves each field`() {
        mvc.patch("/repos/$repo") { contentType = MediaType.APPLICATION_JSON; content = """{"ciCheck":"engine","ciPaths":["engine/"]}""" }
            .andExpect {
                status { isOk() }
                jsonPath("$.fullName") { value(repo) }
                jsonPath("$.ciCheck") { value("engine") }
                jsonPath("$.ciPaths[0]") { value("engine/") }
            }
        assertEquals("engine" to listOf("engine/"), row(repo).let { it.ciCheck to it.ciPaths })

        // ciPaths absent: left; ciCheck changed.
        mvc.patch("/repos/$repo") { contentType = MediaType.APPLICATION_JSON; content = """{"ciCheck":"build"}""" }.andExpect { status { isOk() } }
        assertEquals("build" to listOf("engine/"), row(repo).let { it.ciCheck to it.ciPaths })

        // ciPaths cleared: every path; ciCheck absent: left.
        mvc.patch("/repos/$repo") { contentType = MediaType.APPLICATION_JSON; content = """{"ciPaths":null}""" }.andExpect { status { isOk() } }
        assertEquals("build" to emptyList<String>(), row(repo).let { it.ciCheck to it.ciPaths })

        // ciCheck cleared: back to the property.
        mvc.patch("/repos/$repo") { contentType = MediaType.APPLICATION_JSON; content = """{"ciCheck":null}""" }
            .andExpect { status { isOk() } }
        assertNull(row(repo).ciCheck)
        assertNull(repos.ciCheck(repo))
    }

    @Test
    fun `POST takes securityReview, on when absent, and GET lists it per row`() {
        guarded.post("/repos") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"repo":"acme/repoctl-unreviewed","securityReview":false}"""
        }.andExpect { status { isCreated() } }
        mvc.get("/repos").andExpect {
            status { isOk() }
            jsonPath("$[?(@.fullName == 'acme/repoctl-unreviewed')].securityReview") { value(false) }
            jsonPath("$[?(@.fullName == '$repo')].securityReview") { value(true) }
        }
        assertFalse(row("acme/repoctl-unreviewed").securityReview)
        assertTrue(row(repo).securityReview, "a repo added without it has security reviews")
    }

    @Test
    fun `PATCH turns security reviews off and on, and leaves them when the key is absent`() {
        guarded.patch("/repos/$repo") { contentType = MediaType.APPLICATION_JSON; content = """{"securityReview":false}""" }
            .andExpect {
                status { isOk() }
                jsonPath("$.securityReview") { value(false) }
            }
        assertFalse(row(repo).securityReview)
        assertFalse(repos.securityReview(repo))

        // securityReview absent: left; the check fields change alone.
        guarded.patch("/repos/$repo") { contentType = MediaType.APPLICATION_JSON; content = """{"ciCheck":"build"}""" }.andExpect { status { isOk() } }
        assertEquals("build" to false, row(repo).let { it.ciCheck to it.securityReview })

        guarded.patch("/repos/$repo") { contentType = MediaType.APPLICATION_JSON; content = """{"securityReview":true}""" }
            .andExpect {
                status { isOk() }
                jsonPath("$.securityReview") { value(true) }
            }
        assertEquals("build" to true, row(repo).let { it.ciCheck to it.securityReview }, "the check is left as it was")
    }

    @Test
    fun `PATCH with a securityReview that is not a boolean is 400 naming the field and changes nothing`() {
        repos.update(repo, null, emptyList(), false)
        for (body in listOf("""{"securityReview":null}""", """{"securityReview":"true"}""", """{"securityReview":1}""")) {
            mvc.patch("/repos/$repo") { contentType = MediaType.APPLICATION_JSON; content = body }
                .andExpect { status { isBadRequest() } }
        }
        for (value in listOf(null, "true", 1)) {
            val e = assertFailsWith<org.springframework.web.server.ResponseStatusException> { controller.edit("acme", "repoctl", mapOf("securityReview" to value)) }
            assertTrue("securityReview" in e.reason.orEmpty(), e.reason)
        }
        assertFalse(row(repo).securityReview)
    }

    @Test
    fun `PUT webhook-secret seals it on the row and never shows it, DELETE drops it, and a Smith sandbox may do neither`() {
        val path = "/repos/Acme/RepoCtl/webhook-secret"
        val body = """{"secret":"0123456789abcdef0123456789abcdef"}"""
        guarded.put(path) { contentType = MediaType.APPLICATION_JSON; content = body; with(from("172.18.0.7")) }.andExpect { status { isForbidden() } }
        assertNull(repos.webhookSecret(repo), "a Smith's is not stored")
        for (bad in listOf("""{"secret":"short"}""", """{"secret":"0123456789 abcdef 0123456789"}"""))
            guarded.put(path) { contentType = MediaType.APPLICATION_JSON; content = bad }.andExpect { status { isBadRequest() } }
        guarded.put("/repos/acme/nowhere/webhook-secret") { contentType = MediaType.APPLICATION_JSON; content = body }.andExpect { status { isNotFound() } }
        guarded.put(path) { contentType = MediaType.APPLICATION_JSON; content = body }.andExpect { status { isNoContent() } }
        assertEquals("0123456789abcdef0123456789abcdef", repos.webhookSecret(repo))
        mvc.get("/repos").andExpect {
            status { isOk() }
            jsonPath("$[?(@.fullName == '$repo')].hasWebhookSecret") { value(true) }
            jsonPath("$[?(@.fullName == '$repo')].webhookSecret") { doesNotExist() }
        }
        guarded.delete(path) { with(from("172.18.0.7")) }.andExpect { status { isForbidden() } }
        guarded.delete(path).andExpect { status { isNoContent() } }
        assertNull(repos.webhookSecret(repo))
        assertFalse(row(repo).hasWebhookSecret)
    }

    @Test
    fun `a Smith sandbox may not send securityReview, and Docker not answering is 503`() {
        val off = """{"securityReview":false}"""
        val addOff = """{"repo":"acme/repoctl-unreviewed","securityReview":false}"""
        guarded.patch("/repos/$repo") { contentType = MediaType.APPLICATION_JSON; content = off; with(from("172.18.0.7")) }
            .andExpect { status { isForbidden() } }
        guarded.patch("/repos/$repo") { contentType = MediaType.APPLICATION_JSON; content = """{"securityReview":true}"""; with(from("172.18.0.7")) }
            .andExpect { status { isForbidden() } }
        guarded.post("/repos") { contentType = MediaType.APPLICATION_JSON; content = addOff; with(from("172.18.0.7")) }
            .andExpect { status { isForbidden() } }
        assertTrue(row(repo).securityReview, "left on")
        assertFalse(repos.contains("acme/repoctl-unreviewed"), "not added")
        // The check is not guarded.
        guarded.patch("/repos/$repo") { contentType = MediaType.APPLICATION_JSON; content = """{"ciCheck":"build"}"""; with(from("172.18.0.7")) }
            .andExpect { status { isOk() } }

        sandbox.down = true
        guarded.patch("/repos/$repo") { contentType = MediaType.APPLICATION_JSON; content = off; with(from("10.0.0.5")) }
            .andExpect { status { isServiceUnavailable() } }
        guarded.post("/repos") { contentType = MediaType.APPLICATION_JSON; content = addOff; with(from("10.0.0.5")) }
            .andExpect { status { isServiceUnavailable() } }
        assertTrue(row(repo).securityReview, "left on")
        assertFalse(repos.contains("acme/repoctl-unreviewed"), "not added")

        sandbox.down = false
        guarded.patch("/repos/$repo") { contentType = MediaType.APPLICATION_JSON; content = off; with(from("10.0.0.5")) }
            .andExpect { status { isOk() } }
        assertFalse(row(repo).securityReview, "a caller that is no Smith may")
    }

    @Test
    fun `POST sets the scan interval to 60 unless it is given, and GET lists it with a scan's cost in calls`() {
        guarded.post("/repos") { contentType = MediaType.APPLICATION_JSON; content = """{"repo":"acme/repoctl-fast","scanIntervalMinutes":15}""" }
            .andExpect { status { isCreated() } }
        assertEquals(15, row("acme/repoctl-fast").scanIntervalMinutes)
        assertEquals(60, row(repo).scanIntervalMinutes, "added without one")
        for (bad in listOf(0, 1441)) {
            mvc.post("/repos") { contentType = MediaType.APPLICATION_JSON; content = """{"repo":"acme/repoctl-bad","scanIntervalMinutes":$bad}""" }
                .andExpect { status { isBadRequest() } }
        }
        assertFalse(repos.contains("acme/repoctl-bad"))
        // The trusted list is engine-wide: someone on it may authorize on every repo.
        access.add("repoctl-trusted", "test")
        mvc.get("/repos").andExpect {
            status { isOk() }
            jsonPath("$[?(@.fullName == '$repo')].scanIntervalMinutes") { value(60) }
            jsonPath("$[?(@.fullName == 'acme/repoctl-fast')].scanIntervalMinutes") { value(15) }
            // No label listing since the cutover, and nothing waits: a scan makes no call.
            jsonPath("$[?(@.fullName == '$repo')].scanCallsPerScan") { value(0) }
            jsonPath("$[?(@.fullName == '$repo')].scanCalls.listing") { value(0) }
            jsonPath("$[?(@.fullName == '$repo')].scanCalls.authorized") { value(0) }
            jsonPath("$[?(@.fullName == 'acme/repoctl-fast')].scanCallsPerScan") { value(0) }
        }
        access.remove("repoctl-trusted", "test")
    }

    @Test
    fun `PATCH sets the scan interval from 1 to 1440 and refuses anything else with 400 naming the range`() {
        guarded.patch("/repos/$repo") { contentType = MediaType.APPLICATION_JSON; content = """{"scanIntervalMinutes":5}""" }
            .andExpect {
                status { isOk() }
                jsonPath("$.scanIntervalMinutes") { value(5) }
            }
        assertEquals(5, row(repo).scanIntervalMinutes)
        for (edge in listOf(1, 1440)) {
            guarded.patch("/repos/$repo") { contentType = MediaType.APPLICATION_JSON; content = """{"scanIntervalMinutes":$edge}""" }
                .andExpect { status { isOk() } }
            assertEquals(edge, row(repo).scanIntervalMinutes)
        }
        for (bad in listOf("0", "1441", "-5", "2.5", "2.0", "\"10\"", "null", "true", "99999999999")) {
            mvc.patch("/repos/$repo") { contentType = MediaType.APPLICATION_JSON; content = """{"scanIntervalMinutes":$bad}""" }
                .andExpect { status { isBadRequest() } }
        }
        for (value in listOf(0, 1441, 2.5, "10", null)) {
            val e = assertFailsWith<org.springframework.web.server.ResponseStatusException> { controller.edit("acme", "repoctl", mapOf("scanIntervalMinutes" to value)) }
            assertTrue("from 1 to 1440" in e.reason.orEmpty(), e.reason)
        }
        assertEquals(1440, row(repo).scanIntervalMinutes, "left as it was")

        // Absent: left; the other fields change alone.
        mvc.patch("/repos/$repo") { contentType = MediaType.APPLICATION_JSON; content = """{"ciCheck":"build"}""" }.andExpect { status { isOk() } }
        assertEquals("build" to 1440, row(repo).let { it.ciCheck to it.scanIntervalMinutes })
    }

    @Test
    fun `a Smith sandbox may not set a repo's scan interval`() {
        guarded.patch("/repos/$repo") { contentType = MediaType.APPLICATION_JSON; content = """{"scanIntervalMinutes":1}"""; with(from("172.18.0.7")) }
            .andExpect { status { isForbidden() } }
        guarded.post("/repos") { contentType = MediaType.APPLICATION_JSON; content = """{"repo":"acme/repoctl-fast","scanIntervalMinutes":1}"""; with(from("172.18.0.7")) }
            .andExpect { status { isForbidden() } }
        assertEquals(60, row(repo).scanIntervalMinutes)
        assertFalse(repos.contains("acme/repoctl-fast"))
    }

    @Test
    fun `PATCH of an unknown repo is 404`() {
        mvc.patch("/repos/acme/nowhere") { contentType = MediaType.APPLICATION_JSON; content = """{"ciCheck":"build"}""" }
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `PATCH with a malformed body is 400 and changes nothing`() {
        repos.update(repo, "engine", listOf("engine/"), true)
        for (body in listOf("""["ciCheck"]""", "\"build\"", """{"ciCheck":"build","other":1}""", """{"ciCheck":5}""",
                            """{"ciPaths":"engine/"}""", """{"ciPaths":[1]}""", """{"ciCheck":""}""", """{"ciPaths":[" "]}""")) {
            mvc.patch("/repos/$repo") { contentType = MediaType.APPLICATION_JSON; content = body }
                .andExpect { status { isBadRequest() } }
        }
        assertEquals("engine" to listOf("engine/"), row(repo).let { it.ciCheck to it.ciPaths })
    }

    @Test
    fun `DELETE is 409 naming the running flows, and 204 once they are gone`() {
        val flow = runtime.startProcessInstanceByKey("smith-run", mapOf("repo" to "Acme/RepoCtl", "issue" to 42L))

        mvc.delete("/repos/$repo").andExpect {
            status { isConflict() }
            jsonPath("$.flows[0].processKey") { value("smith-run") }
            jsonPath("$.flows[0].issue") { value(42) }
            jsonPath("$.flows[0].instanceId") { value(flow.id) }
            jsonPath("$.error") { value("$repo has running flows: smith-run #42; cancel or finish them first") }
        }
        assertTrue(repos.contains(repo), "still listed")

        runtime.deleteProcessInstance(flow.id, "test")
        mvc.delete("/repos/$repo").andExpect { status { isNoContent() } }
        assertFalse(repos.contains(repo))
    }

    @Test
    fun `DELETE of an unknown repo is 404`() {
        mvc.delete("/repos/acme/nowhere").andExpect { status { isNotFound() } }
    }

    // ---- doctorBugs and engineDefects (engine/README.md, "Doctor") ----

    private fun patch(name: String, body: String, address: String = "10.0.0.5") =
        guarded.patch("/repos/$name") { contentType = MediaType.APPLICATION_JSON; content = body; with(from(address)) }

    /** The H2 is shared: no other test's repo may hold engineDefects while these run. */
    private fun noDefectsRepo() = jdbc.sql("UPDATE engine.target_repo SET engine_defects = FALSE").update()

    @Test
    fun `GET lists doctorBugs and engineDefects, both off for a new repo`() {
        assertFalse(row(repo).doctorBugs)
        assertFalse(row(repo).engineDefects)
        mvc.get("/repos").andExpect {
            jsonPath("$[?(@.fullName == '$repo')].doctorBugs") { value(false) }
            jsonPath("$[?(@.fullName == '$repo')].engineDefects") { value(false) }
        }
    }

    @Test
    fun `PATCH turns doctorBugs and engineDefects on and off, and doctorBugs false clears engineDefects`() = EventRecorder(bus).use { rec ->
        noDefectsRepo()
        patch(repo, """{"doctorBugs":true,"engineDefects":true}""").andExpect { status { isOk() } }
        assertTrue(row(repo).doctorBugs && row(repo).engineDefects)
        assertEquals(repo, repos.engineDefectsRepo())
        assertTrue(repos.doctorBugs("ACME/RepoCtl"), "ignoring case")
        patch(repo, """{"engineDefects":false}""").andExpect { status { isOk() } }
        assertTrue(row(repo).doctorBugs && !row(repo).engineDefects)
        patch(repo, """{"engineDefects":true}""").andExpect { status { isOk() } }
        patch(repo, """{"doctorBugs":false}""").andExpect { status { isOk() } }
        assertFalse(row(repo).doctorBugs || row(repo).engineDefects, "doctorBugs false clears engineDefects")
        assertNull(repos.engineDefectsRepo())
        assertEquals(listOf(listOf("doctorBugs", "engineDefects"), listOf("engineDefects"), listOf("engineDefects"), listOf("doctorBugs", "engineDefects")),
            rec.of("repo").map { f -> f.data["fields"]!!.toList().map { it.asString() } })
    }

    @Test
    fun `engineDefects needs doctorBugs, and only one repo may have it`() {
        noDefectsRepo()
        patch(repo, """{"engineDefects":true}""").andExpect { status { isBadRequest(); reason(containsString("engineDefects needs doctorBugs on")) } }
        patch(repo, """{"doctorBugs":false,"engineDefects":true}""").andExpect { status { isBadRequest() } }
        patch(repo, """{"doctorBugs":"yes"}""").andExpect { status { isBadRequest(); reason("doctorBugs is true or false") } }
        assertFalse(row(repo).engineDefects)

        val other = "acme/repoctl-checked"
        repos.add(other)
        patch(other, """{"doctorBugs":true,"engineDefects":true}""").andExpect { status { isOk() } }
        patch(repo, """{"doctorBugs":true,"engineDefects":true}""").andExpect {
            status { isConflict(); reason("$other has engineDefects; only one repo can: turn it off there first") }
        }
        assertFalse(row(repo).doctorBugs, "nothing written on a 409")
        patch(repo, """{"doctorBugs":true}""").andExpect { status { isOk() } }
        assertTrue(row(repo).doctorBugs, "doctorBugs alone is any repo's")
        patch(other, """{"engineDefects":true}""").andExpect { status { isOk() } }
        assertEquals(other, repos.engineDefectsRepo())
        noDefectsRepo()
    }

    @Test
    fun `the database holds the rules too - engineDefects without doctorBugs, or on two rows, is refused`() {
        noDefectsRepo()
        assertFailsWith<org.springframework.dao.DataIntegrityViolationException> {
            jdbc.sql("UPDATE engine.target_repo SET engine_defects = TRUE WHERE full_name = :r").param("r", repo).update()
        }
        val other = "acme/repoctl-checked"
        repos.add(other)
        jdbc.sql("UPDATE engine.target_repo SET doctor_bugs = TRUE WHERE full_name IN (:a, :b)").param("a", repo).param("b", other).update()
        assertTrue(repos.setDoctorBugs(other, true, true))
        assertFailsWith<org.springframework.dao.DataIntegrityViolationException> { repos.setDoctorBugs(repo, true, true) }
        noDefectsRepo()
    }

    @Test
    fun `a Smith sandbox may not set doctorBugs or engineDefects, Docker not answering is 503, and POST takes neither`() {
        patch(repo, """{"doctorBugs":true}""", "172.18.0.7").andExpect { status { isForbidden() } }
        patch(repo, """{"engineDefects":false}""", "172.18.0.7").andExpect { status { isForbidden() } }
        sandbox.down = true
        patch(repo, """{"doctorBugs":true}""").andExpect { status { isServiceUnavailable() } }
        assertFalse(row(repo).doctorBugs)
        sandbox.down = false
        for (field in listOf("doctorBugs", "engineDefects")) {
            guarded.post("/repos") { contentType = MediaType.APPLICATION_JSON; content = """{"repo":"acme/repoctl-bad","$field":false}""" }
                .andExpect { status { isBadRequest(); reason(containsString("$field is not taken by POST /repos")) } }
        }
        assertFalse(repos.contains("acme/repoctl-bad"))
    }

    // ---- docsFirst (engine/README.md, "Docs path") ----

    @Test
    fun `GET lists docsFirst, off for a new repo, PATCH turns it on and off, and a Smith sandbox may not set it`() {
        assertFalse(row(repo).docsFirst)
        assertFalse(repos.docsFirst("acme/nowhere"), "no row is not docs-first")
        mvc.get("/repos").andExpect { jsonPath("$[?(@.fullName == '$repo')].docsFirst") { value(false) } }
        patch(repo, """{"docsFirst":true}""", "172.18.0.7").andExpect { status { isForbidden() } }
        assertFalse(row(repo).docsFirst)
        patch(repo, """{"docsFirst":true}""").andExpect { status { isOk() }; jsonPath("$.docsFirst") { value(true) } }
        assertTrue(repos.docsFirst("ACME/RepoCtl"), "ignoring case")
        patch(repo, """{"docsFirst":"yes"}""").andExpect { status { isBadRequest(); reason("docsFirst is true or false") } }
        patch(repo, """{"docsFirst":false}""").andExpect { status { isOk() } }
        assertFalse(row(repo).docsFirst)
    }

    // ---- hitlpSurface is gone: the engine is no HITLP surface (#100013) ----

    @Test
    fun `GET lists no hitlpSurface and PATCH refuses it as an unknown field`() {
        mvc.get("/repos").andExpect { jsonPath("$[?(@.fullName == '$repo')].hitlpSurface") { doesNotExist() } }
        patch(repo, """{"hitlpSurface":true}""").andExpect { status { isBadRequest() } }
    }

    // ---- childMode (engine/README.md, "Split features") ----

    @Test
    fun `GET lists childMode, serial for a new repo, PATCH sets each mode and refuses any other value, and a Smith sandbox may not set it`() {
        assertEquals("serial", row(repo).childMode)
        assertEquals("serial", repos.childMode("acme/nowhere"), "no row is serial")
        mvc.get("/repos").andExpect { jsonPath("$[?(@.fullName == '$repo')].childMode") { value("serial") } }
        patch(repo, """{"childMode":"parallel"}""", "172.18.0.7").andExpect { status { isForbidden() } }
        assertEquals("serial", row(repo).childMode)
        for (bad in listOf("\"fast\"", "true", "null", "\"Serial\"")) {
            patch(repo, """{"childMode":$bad}""").andExpect { status { isBadRequest(); reason("childMode is serial, parallel or live") } }
            assertEquals("serial", row(repo).childMode, "refused: $bad")
        }
        patch(repo, """{"childMode":"parallel"}""").andExpect { status { isOk() }; jsonPath("$.childMode") { value("parallel") } }
        assertEquals("parallel", repos.childMode("ACME/RepoCtl"), "ignoring case")
        patch(repo, """{"childMode":"live"}""").andExpect { status { isOk() }; jsonPath("$.childMode") { value("live") } }
        patch(repo, """{"childMode":"serial"}""").andExpect { status { isOk() }; jsonPath("$.childMode") { value("serial") } }
        assertEquals("serial", row(repo).childMode)
    }

    // ---- publishWorkItems (engine/docs/work-items.md, "Sources") ----

    @Test
    fun `GET lists publishWorkItems, off for a new repo, PATCH turns it on and off, and a Smith sandbox may not set it`() {
        assertFalse(row(repo).publishWorkItems)
        assertFalse(repos.publishWorkItems("acme/nowhere"), "no row publishes nothing")
        mvc.get("/repos").andExpect { jsonPath("$[?(@.fullName == '$repo')].publishWorkItems") { value(false) } }
        patch(repo, """{"publishWorkItems":true}""", "172.18.0.7").andExpect { status { isForbidden() } }
        assertFalse(row(repo).publishWorkItems)
        patch(repo, """{"publishWorkItems":"yes"}""").andExpect { status { isBadRequest(); reason("publishWorkItems is true or false") } }
        patch(repo, """{"publishWorkItems":true}""").andExpect { status { isOk() }; jsonPath("$.publishWorkItems") { value(true) } }
        assertTrue(repos.publishWorkItems("ACME/RepoCtl"), "ignoring case")
        patch(repo, """{"publishWorkItems":false}""").andExpect { status { isOk() }; jsonPath("$.publishWorkItems") { value(false) } }
        assertFalse(row(repo).publishWorkItems)
    }

    // ---- frictionBugs (engine/README.md, "Friction notes", "Two things") ----

    @Test
    fun `GET lists frictionBugs, off for a new repo, and PATCH turns it on and off independently of doctorBugs, naming it in the changed event`() = EventRecorder(bus).use { rec ->
        noDefectsRepo()
        assertFalse(row(repo).frictionBugs)
        mvc.get("/repos").andExpect { jsonPath("$[?(@.fullName == '$repo')].frictionBugs") { value(false) } }
        patch(repo, """{"frictionBugs":true}""").andExpect { status { isOk() }; jsonPath("$.frictionBugs") { value(true) } }
        assertTrue(row(repo).frictionBugs && !row(repo).doctorBugs, "independent of doctorBugs")
        assertTrue(repos.frictionBugs("ACME/RepoCtl"), "ignoring case")
        patch(repo, """{"doctorBugs":true}""").andExpect { status { isOk() } }
        assertTrue(row(repo).frictionBugs && row(repo).doctorBugs)
        patch(repo, """{"frictionBugs":false}""").andExpect { status { isOk() } }
        assertTrue(!row(repo).frictionBugs && row(repo).doctorBugs, "doctorBugs stays")
        patch(repo, """{"frictionBugs":"yes"}""").andExpect { status { isBadRequest(); reason("frictionBugs is true or false") } }
        patch(repo, """{"frictionBugs":null}""").andExpect { status { isBadRequest(); reason("frictionBugs is true or false") } }
        assertEquals(listOf(listOf("frictionBugs"), listOf("doctorBugs"), listOf("frictionBugs")),
            rec.of("repo").filter { it.state == "changed" }.map { f -> f.data["fields"]!!.toList().map { it.asString() } })
        noDefectsRepo()
    }

    @Test
    fun `a Smith sandbox may not set frictionBugs, Docker not answering is 503, POST refuses it, and the MCP tools take and show it`() {
        patch(repo, """{"frictionBugs":true}""", "172.18.0.7").andExpect { status { isForbidden() } }
        sandbox.down = true
        patch(repo, """{"frictionBugs":true}""").andExpect { status { isServiceUnavailable() } }
        sandbox.down = false
        assertFalse(row(repo).frictionBugs)
        guarded.post("/repos") { contentType = MediaType.APPLICATION_JSON; content = """{"repo":"acme/repoctl-bad","frictionBugs":false}""" }
            .andExpect { status { isBadRequest(); reason(containsString("frictionBugs is not taken by POST /repos")) } }
        assertFalse(repos.contains("acme/repoctl-bad"))
        val edited = toolBody(tool("edit_repo", mapOf("repo" to repo, "frictionBugs" to true)))
        assertEquals(true, edited["frictionBugs"])
        assertTrue(row(repo).frictionBugs)
        val one = (toolBody(tool("read_repos", mapOf("repo" to repo)))["repos"] as List<*>).single() as Map<*, *>
        assertEquals(true, one["frictionBugs"])
        assertEquals("frictionBugs is true or false", toolError(tool("edit_repo", mapOf("repo" to repo, "frictionBugs" to "yes"))))
        tool("edit_repo", mapOf("repo" to repo, "frictionBugs" to false))
        assertFalse(row(repo).frictionBugs)
    }

    // ---- The frakworks MCP server's repo tools (#1021): the context's own FrakworksMcp, through the same controller ----

    private val mate = McpCaller.Nobody("127.0.0.1", "someone")
    private fun tool(name: String, args: Map<String, Any?>): McpToolResult = mcp.call(name, args, mate)
    private fun toolBody(r: McpToolResult): Map<*, *> = mcpJson.readValue(r.text, Map::class.java)
    private fun toolError(r: McpToolResult): String {
        assertEquals(true, r.isError, r.text)
        return toolBody(r)["error"] as String
    }

    @Test
    fun `add_repo, edit_repo and remove_repo publish the API's added, changed and removed frames`() = EventRecorder(bus).use { rec ->
        val events = "acme/repoctl-events"
        assertEquals(mapOf("ok" to true, "repo" to events, "outcome" to "added"),
            toolBody(tool("add_repo", mapOf("repo" to events, "ciCheck" to "build", "scanIntervalMinutes" to 30))))
        assertEquals(listOf<Any?>("build", 30), listOf(row(events).ciCheck, row(events).scanIntervalMinutes))
        assertEquals("already there", toolBody(tool("add_repo", mapOf("repo" to events, "ciCheck" to "other")))["outcome"])
        assertEquals("build", row(events).ciCheck, "an existing repo is left as it was")
        val edited = toolBody(tool("edit_repo", mapOf("repo" to events, "ciCheck" to null, "securityReview" to false)))
        assertEquals(listOf<Any?>(events, null, false), listOf(edited["fullName"], edited["ciCheck"], edited["securityReview"]))
        assertEquals(mapOf("ok" to true, "repo" to events, "outcome" to "removed"), toolBody(tool("remove_repo", mapOf("repo" to events))))
        assertFalse(repos.contains(events))
        val frames = rec.of("repo")
        assertTrue(frames.all { it.str("repo") == events }, frames.toString())
        assertEquals(listOf("added", "changed", "removed"), frames.map { it.state })
        assertEquals(listOf("ciCheck", "securityReview"), frames[1].data["fields"]!!.toList().map { it.asString() })
    }

    @Test
    fun `the repo tools refuse what the API refuses, with its text, and set no token or webhook secret`() {
        noDefectsRepo()
        val bad = "acme/repoctl-bad"
        assertEquals("repo is owner/name", toolError(tool("add_repo", mapOf("repo" to "not a repo"))))
        assertEquals("repo is owner/name", toolError(tool("edit_repo", mapOf("repo" to "acme", "ciCheck" to "x"))))
        assertEquals("ciCheck is a check run's name, not blank", toolError(tool("add_repo", mapOf("repo" to bad, "ciCheck" to " "))))
        assertEquals("ciCheck is a check run's name, not blank", toolError(tool("edit_repo", mapOf("repo" to repo, "ciCheck" to " "))))
        assertEquals("ciPaths are path prefixes, not blank, with no commas", toolError(tool("add_repo", mapOf("repo" to bad, "ciPaths" to listOf("a,b")))))
        assertEquals("ciPaths are path prefixes, not blank, with no commas", toolError(tool("edit_repo", mapOf("repo" to repo, "ciPaths" to listOf("a,b")))))
        for (minutes in listOf(0, 1441, 2.5, "5")) {
            assertEquals(INTERVAL_REASON, toolError(tool("add_repo", mapOf("repo" to bad, "scanIntervalMinutes" to minutes))))
            assertEquals(INTERVAL_REASON, toolError(tool("edit_repo", mapOf("repo" to repo, "scanIntervalMinutes" to minutes))))
        }
        for (secret in listOf("token", "webhookSecret")) {
            assertTrue(toolError(tool("edit_repo", mapOf("repo" to repo, secret to "0123456789abcdef0123"))).startsWith("unknown field(s): $secret;"))
            assertTrue(toolError(tool("add_repo", mapOf("repo" to bad, secret to "0123456789abcdef0123"))).endsWith("not \"$secret\""))
        }
        assertFalse(repos.contains(bad))
        assertFalse(row(repo).hasToken || row(repo).hasWebhookSecret, "no tool sets a token or a webhook secret")
        assertEquals(DEFAULT_SCAN_INTERVAL_MINUTES, row(repo).scanIntervalMinutes)

        assertEquals("engineDefects needs doctorBugs on: the repo that takes engine defects files them",
            toolError(tool("edit_repo", mapOf("repo" to repo, "engineDefects" to true))))
        val other = "acme/repoctl-checked"
        repos.add(other)
        assertEquals(false, tool("edit_repo", mapOf("repo" to other, "doctorBugs" to true, "engineDefects" to true)).isError)
        assertEquals("$other has engineDefects; only one repo can: turn it off there first",
            toolError(tool("edit_repo", mapOf("repo" to repo, "doctorBugs" to true, "engineDefects" to true))))
        assertFalse(row(repo).doctorBugs, "nothing written on a 409")
        noDefectsRepo()

        assertEquals("acme/repoctl-nope is not a target repo", toolError(tool("edit_repo", mapOf("repo" to "acme/repoctl-nope", "ciCheck" to "x"))))
        assertEquals("acme/repoctl-nope is not a target repo", toolError(tool("remove_repo", mapOf("repo" to "acme/repoctl-nope"))))
    }

    @Test
    fun `remove_repo is refused naming each running flow, as DELETE is`() {
        val flow = runtime.startProcessInstanceByKey("smith-run", mapOf("repo" to repo, "issue" to 42L))
        assertEquals("$repo has running flows: smith-run #42; cancel or finish them first", toolError(tool("remove_repo", mapOf("repo" to repo))))
        assertTrue(repos.contains(repo), "still listed")
        runtime.deleteProcessInstance(flow.id, "test")
        assertEquals(mapOf("ok" to true, "repo" to repo, "outcome" to "removed"), toolBody(tool("remove_repo", mapOf("repo" to "ACME/RepoCtl"))))
        assertFalse(repos.contains(repo))
    }

    @Test
    fun `read_repos has GET's fields, a token and a webhook secret only as set and since when`() {
        val token = "ghp_readReposTokenValue1234567890"
        val secret = "readReposWebhookSecret0123456789"
        repos.setToken(repo, token)
        repos.setWebhookSecret(repo, secret)
        val text = tool("read_repos", mapOf("repo" to "ACME/repoctl")).text
        val one = (mcpJson.readValue(text, Map::class.java)["repos"] as List<*>).single() as Map<*, *>
        assertEquals(listOf<Any?>(repo, true, true), listOf(one["fullName"], one["hasToken"], one["hasWebhookSecret"]))
        assertTrue(one["tokenSetAt"] is String && one["webhookSecretSetAt"] is String, one.toString())
        val listed = mcpJson.readValue(mvc.get("/repos").andReturn().response.contentAsString, List::class.java)
            .map { it as Map<*, *> }.single { it["fullName"] == repo }
        assertTrue(one.keys.containsAll(listed.keys), "GET /repos has ${listed.keys - one.keys} that read_repos lacks")
        val sealed = jdbc.sql("SELECT token, webhook_secret FROM engine.target_repo WHERE full_name = :n").param("n", repo)
            .query { rs, _ -> listOf(rs.getBytes(1), rs.getBytes(2)) }.single()
        for (leak in listOf(token, secret, secrets.id!!, "key_id", "keyId") + sealed.map { Base64.getEncoder().encodeToString(it) })
            assertFalse(text.contains(leak), "read_repos shows $leak")
        assertEquals("acme/repoctl-nope is not a target repo", toolError(tool("read_repos", mapOf("repo" to "acme/repoctl-nope"))))
        assertTrue((toolBody(tool("read_repos", emptyMap()))["repos"] as List<*>).any { (it as Map<*, *>)["fullName"] == repo })
    }
}

package com.codefrak.engine.repos

import com.codefrak.engine.ci.FakeCheckRuns
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.events.EventRecorder
import com.codefrak.engine.github.FakeRepoSetupGitHub
import com.codefrak.engine.github.ReadFakes
import com.codefrak.engine.github.RepoSettings
import com.codefrak.engine.github.RepoTokens
import com.codefrak.engine.smith.SandboxStatus
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.smith.SmithSandbox
import com.codefrak.engine.smith.SmithSpec
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import kotlin.test.assertEquals

/** A sandbox that knows only which address is which run; `down` is Docker not answering (as [TargetRepoControllerTest] fakes it). */
private class CheckSandbox(val runs: Map<String, SmithCaller>, @Volatile var down: Boolean = false) : SmithSandbox {
    override fun start(spec: SmithSpec): String = error("unused")
    override fun status(handle: String): SandboxStatus = error("unused")
    override fun logs(handle: String, maxLines: Int): String = ""
    override fun output(handle: String, path: String): ByteArray? = null
    override fun remove(handle: String) {}
    override fun runAt(address: String): SmithCaller? = if (down) error("docker is down") else runs[address]
}

/**
 * `POST /repos/setup/check` over a standalone MockMvc: the controller over the context's [TargetRepos], with checks
 * of the test's own that run inline on a bus of its own, so the frames are there when the 202 is.
 */
@SpringBootTest
@Import(ReadFakes::class)
class RepoSetupChecksControllerTest {
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var github: FakeRepoSetupGitHub
    @Autowired lateinit var json: ObjectMapper

    private val repo = "acme/setupcheck"
    private val sandbox = CheckSandbox(mapOf("172.18.0.7" to SmithCaller("run-7", 7, repo)))
    private lateinit var bus: EngineEvents
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        github.reset()
        github.repos[repo] = RepoSettings("main", push = true, allowSquashMerge = true)
        repos.remove(repo); repos.add(repo)
        sandbox.down = false
        bus = EngineEvents(json)
        val checks = RepoSetupChecks(RepoSetupCheck(github, FakeCheckRuns(), RepoTokens { "t0k" }), bus, Clock.systemUTC()) { it() }
        mvc = MockMvcBuilders.standaloneSetup(RepoSetupChecksController(repos, checks, sandbox)).build()
    }

    @AfterEach
    fun tearDown() { repos.remove(repo) }

    private fun from(address: String) = RequestPostProcessor { it.remoteAddr = address; it }

    private fun post(body: String, address: String = "10.0.0.5") =
        mvc.post("/repos/setup/check") { contentType = MediaType.APPLICATION_JSON; content = body; with(from(address)) }

    @Test
    fun `a target repo is 202 with queued, then fresh, and the result is a setup frame`() {
        EventRecorder(bus, repo).use { rec ->
            post("""{"repo":"Acme/SetupCheck"}""").andExpect {
                status { isAccepted() }
                jsonPath("$.queued[0]") { value(repo) }
                jsonPath("$.fresh.length()") { value(0) }
            }
            post("""{"repo":"$repo"}""").andExpect {
                status { isAccepted() }
                jsonPath("$.queued.length()") { value(0) }
                jsonPath("$.fresh[0]") { value(repo) }
            }
            post("""{"repo":"$repo","force":true}""").andExpect {
                status { isAccepted() }
                jsonPath("$.queued[0]") { value(repo) }
            }
            val frames = rec.of("setup")
            assertEquals(listOf("started", "done", "done", "started", "done"), frames.map { it.state })
            assertEquals(true, frames[1].data["report"]["readable"].asBoolean())
        }
        assertEquals(emptyList(), github.writes)
    }

    @Test
    fun `star takes every target repo`() {
        post("""{"repo":"*"}""").andExpect {
            status { isAccepted() }
            jsonPath("$.queued[?(@ == '$repo')]") { exists() }
        }
    }

    @Test
    fun `no repo is 400 naming the target repos, and one that is not a target repo is 404`() {
        post("{}").andExpect {
            status { isBadRequest() }
            status { reason(org.hamcrest.Matchers.containsString(repo)) }
        }
        post("""{"repo":"acme/nowhere"}""").andExpect { status { isNotFound() } }
    }

    @Test
    fun `a Smith sandbox is 403, and 503 when Docker cannot say`() {
        post("""{"repo":"$repo"}""", "172.18.0.7").andExpect { status { isForbidden() } }
        sandbox.down = true
        post("""{"repo":"$repo"}""").andExpect { status { isServiceUnavailable() } }
    }
}

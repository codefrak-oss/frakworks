package com.codefrak.engine.summarize

import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.FakeSandbox
import com.codefrak.engine.smith.SmithSessions
import com.codefrak.engine.smith.SmithSpec
import com.codefrak.engine.smith.SmithTelemetry
import com.codefrak.engine.smith.eventually
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `POST /ticket/{owner}/{repo}/{n}/summarize` over a fake sandbox's stored session, with the LLM faked. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SummarizeFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class SummarizeControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var sandbox: FakeSandbox
    @Autowired lateinit var telemetry: SmithTelemetry
    @Autowired lateinit var sessions: SmithSessions
    @Autowired lateinit var summarizer: FakeSummarizer
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var caller: FakeCallerIdentity

    @BeforeEach
    fun reset() {
        jdbc.sql("DELETE FROM engine.target_repo").update()
        repos.add("acme/widgets")
        summarizer.reset()
        caller.reset()
    }

    /** A live run on acme/widgets#`issue` that has said one thing. */
    private fun live(runId: String, issue: Long, block: () -> Unit) {
        telemetry.started(SmithSpec(runId, "acme/widgets", issue, "claude-opus-5-5", "medium", dryRun = false, mode = "work"), "container-$runId")
        sandbox.print("container-$runId", """{"type":"assistant","message":{"content":[{"type":"text","text":"Adding the session table."}]}}""")
        eventually("the stored line") { sessions.lines(runId).isNotEmpty() }
        try { block() } finally { telemetry.ended(runId) }
    }

    private fun words(s: String) = s.trim().split(Regex("\\s+")).size

    @Test
    fun `a running Smith's session is summarized, within the word limits`() = live("run-sum-1", 5001) {
        summarizer.answer = SessionSummary((1..80).joinToString(" ") { "done$it" }, (1..30).joinToString(" ") { "now$it" })
        val body = mvc.post("/ticket/acme/widgets/5001/summarize").andExpect {
            status { isOk() }
            jsonPath("$.runId") { value("run-sum-1") }
            jsonPath("$.mode") { value("work") }
            jsonPath("$.model") { value("claude-opus-5-5") }
        }.andReturn().response.contentAsString
        val view = jacksonObjectMapper().readValue<Map<String, Any?>>(body)
        val summary = view["summary"] as String
        val now = view["now"] as String
        assertEquals(20, words(summary)); assertTrue(summary.startsWith("done1 done2 "), summary)
        assertTrue("done" !in view.keys)
        assertTrue(words(now) < 15, now)
        val (context, digest, recent) = summarizer.calls.single()
        assertTrue("acme/widgets#5001" in context, context)
        assertEquals("said: Adding the session table.", digest)
        assertEquals("said: Adding the session table.", recent)
    }

    @Test
    fun `a short answer comes back as it is`() = live("run-sum-2", 5002) {
        mvc.post("/ticket/acme/widgets/5002/summarize").andExpect {
            status { isOk() }
            jsonPath("$.summary") { value("It is adding a session table.") }
            jsonPath("$.now") { value("Writing tests.") }
        }
    }

    @Test
    fun `the old path, before #881, is gone`() = live("run-sum-3", 5005) {
        mvc.post("/ticket/acme/widgets/5005/" + "sp" + "y").andExpect { status { isNotFound() } }
        assertTrue(summarizer.calls.isEmpty())
    }

    @Test
    fun `with no Smith running on the ticket it is a 409 with an error, and nothing is summarized`() {
        mvc.post("/ticket/acme/widgets/5003/summarize").andExpect {
            status { isConflict() }
            jsonPath("$.error") { value("no Smith is running on acme/widgets#5003") }
        }
        assertTrue(summarizer.calls.isEmpty())
    }

    @Test
    fun `a run another replica follows, open in the database but not in this one's telemetry, is summarized`() {
        jdbc.sql("""INSERT INTO engine.smith_session (run_id, repo, issue, mode, model, started_at) VALUES ('run-sum-5', 'acme/widgets', 5006, 'work', 'claude-opus-5-5', now())""").update()
        jdbc.sql("""INSERT INTO engine.smith_session_line (run_id, seq, read_at, line) VALUES ('run-sum-5', 0, now(), :l)""")
            .param("l", """{"type":"assistant","message":{"content":[{"type":"text","text":"On the other pod."}]}}""").update()
        try {
            assertTrue(telemetry.active().none { it.runId == "run-sum-5" })
            mvc.post("/ticket/acme/widgets/5006/summarize").andExpect {
                status { isOk() }
                jsonPath("$.runId") { value("run-sum-5") }
                jsonPath("$.mode") { value("work") }
            }
        } finally {
            jdbc.sql("UPDATE engine.smith_session SET ended_at = now() WHERE run_id = 'run-sum-5'").update()
        }
    }

    @Test
    fun `a repo that is not a target repo is a 404`() {
        mvc.post("/ticket/other/repo/1/summarize").andExpect {
            status { isNotFound() }
            jsonPath("$.error") { value("other/repo is not a target repo") }
        }
    }

    @Test
    fun `a Smith sandbox is a 403 naming its run id, and nothing is summarized`() {
        caller.smith = "run-77"
        mvc.post("/ticket/acme/widgets/5007/summarize").andExpect {
            status { isForbidden() }
            jsonPath("$.error") { value("a Smith sandbox (run run-77) may not summarize a run") }
        }
        assertTrue(summarizer.calls.isEmpty())
    }

    @Test
    fun `the sandbox provider failing to answer is a 503, and nothing is summarized`() {
        caller.failure = RuntimeException("boom")
        mvc.post("/ticket/acme/widgets/5008/summarize").andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.error") { value("cannot tell whether the caller is a Smith sandbox") }
        }
        assertTrue(summarizer.calls.isEmpty())
    }

    @Test
    fun `no usable account is a 503, a provider failure a 502`() = live("run-sum-4", 5004) {
        summarizer.failure = SummaryUnavailable("no enabled anthropic account with a credential to summarize with")
        mvc.post("/ticket/acme/widgets/5004/summarize").andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.error") { value("no enabled anthropic account with a credential to summarize with") }
        }
        summarizer.failure = SummaryFailed("anthropic answered 500: overloaded")
        mvc.post("/ticket/acme/widgets/5004/summarize").andExpect {
            status { isBadGateway() }
            jsonPath("$.error") { value("anthropic answered 500: overloaded") }
        }
    }
}

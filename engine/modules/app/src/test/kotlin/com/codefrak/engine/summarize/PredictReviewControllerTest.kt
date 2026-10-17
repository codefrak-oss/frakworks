package com.codefrak.engine.summarize

import com.codefrak.engine.jev.FakeJevDecider
import com.codefrak.engine.jev.JevAnswer
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.FakeSandbox
import com.codefrak.engine.smith.SmithSessions
import com.codefrak.engine.smith.SmithSpec
import com.codefrak.engine.smith.SmithTelemetry
import com.codefrak.engine.smith.eventually
import com.codefrak.engine.workitems.NewWorkItem
import com.codefrak.engine.workitems.Tickets
import com.codefrak.engine.workitems.WorkItemSourceRef
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

/** `POST /ticket/{owner}/{repo}/{n}/predict-review` over a fake sandbox's stored session, with Jev faked (#1746). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SummarizeFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class PredictReviewControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var sandbox: FakeSandbox
    @Autowired lateinit var telemetry: SmithTelemetry
    @Autowired lateinit var sessions: SmithSessions
    @Autowired lateinit var jev: FakeJevDecider
    @Autowired lateinit var tickets: Tickets
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var caller: FakeCallerIdentity

    @BeforeEach
    fun reset() {
        jdbc.sql("DELETE FROM engine.target_repo").update()
        repos.add("acme/widgets")
        jev.reset()
        caller.reset()
    }

    private fun say(runId: String, text: String, lines: Int) {
        sandbox.print("container-$runId", """{"type":"assistant","message":{"content":[{"type":"text","text":"$text"}]}}""")
        eventually("$lines stored lines") { sessions.lines(runId).size == lines }
    }

    /** A live run of `mode` on acme/widgets#`issue` that has said one thing. */
    private fun live(runId: String, issue: Long, mode: String = "review", block: () -> Unit) {
        tickets.create(NewWorkItem("acme/widgets", "# Add the widget table", "chore", WorkItemSourceRef(WorkItemSourceRef.RAISED),
            ticket = issue, author = "someone"))
        telemetry.started(SmithSpec(runId, "acme/widgets", issue, "claude-opus-5-5", "medium", dryRun = false, mode = mode), "container-$runId")
        say(runId, "Reading the diff.", 1)
        try { block() } finally { telemetry.ended(runId) }
    }

    @Test
    fun `a Smith sandbox is a 403 naming its run id, and no Jev call is made`() {
        caller.smith = "run-77"
        mvc.post("/ticket/acme/widgets/6006/predict-review").andExpect {
            status { isForbidden() }
            jsonPath("$.error") { value("a Smith sandbox (run run-77) may not predict a review") }
        }
        assertTrue(jev.asked.isEmpty())
    }

    @Test
    fun `the sandbox provider failing to answer is a 503, and no Jev call is made`() {
        caller.failure = RuntimeException("boom")
        mvc.post("/ticket/acme/widgets/6007/predict-review").andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.error") { value("cannot tell whether the caller is a Smith sandbox") }
        }
        assertTrue(jev.asked.isEmpty())
    }

    @Test
    fun `no live review run is a 409, even with a work run live`() {
        mvc.post("/ticket/acme/widgets/6001/predict-review").andExpect { status { isConflict() } }
        live("run-pr-work", 6002, mode = "work") {
            mvc.post("/ticket/acme/widgets/6002/predict-review").andExpect { status { isConflict() } }
        }
        assertTrue(jev.asked.isEmpty())
    }

    @Test
    fun `a repo that is not a target repo is a 404`() {
        mvc.post("/ticket/acme/nope/1/predict-review").andExpect { status { isNotFound() } }
    }

    @Test
    fun `a deny is a negative value of Jev's confidence, and an unchanged transcript makes no second call`() = live("run-pr-1", 6003) {
        jev.answers[PredictReviewController.QUESTION] = JevAnswer("noul", noul = 0.2, confidence = 0.8)
        mvc.post("/ticket/acme/widgets/6003/predict-review").andExpect {
            status { isOk() }
            jsonPath("$.runId") { value("run-pr-1") }
            jsonPath("$.verdict") { value("deny") }
            jsonPath("$.confidence") { value(0.8) }
            jsonPath("$.value") { value(-80) }
            jsonPath("$.lines") { value(1) }
            jsonPath("$.fresh") { value(true) }
        }
        val (useCase, context, state) = jev.asked.single()
        assertEquals("review-prediction", useCase)
        assertEquals("acme/widgets" to 6003L, context.repo to context.issue)
        val s = state as Map<*, *>
        assertEquals("acme/widgets#6003", s["ticket"]); assertEquals("Add the widget table", s["title"])
        assertEquals("said: Reading the diff.", s["session"])
        assertEquals("noul", jev.questionSets.single()[PredictReviewController.QUESTION]?.type)
        assertEquals(1, jdbc.sql("SELECT count(*) FROM engine.jev_call WHERE id = :i AND use_case = 'review-prediction'")
            .param("i", jev.ids.single()).query(Int::class.java).single())

        mvc.post("/ticket/acme/widgets/6003/predict-review").andExpect {
            status { isOk() }; jsonPath("$.value") { value(-80) }; jsonPath("$.fresh") { value(false) }
        }
        assertEquals(1, jev.asked.size, "no new lines, no Jev call")

        jev.answers[PredictReviewController.QUESTION] = JevAnswer("noul", noul = 0.9, confidence = 0.72)
        say("run-pr-1", "The tests cover it.", 2)
        mvc.post("/ticket/acme/widgets/6003/predict-review").andExpect {
            status { isOk() }
            jsonPath("$.verdict") { value("approve") }
            jsonPath("$.value") { value(72) }
            jsonPath("$.lines") { value(2) }
            jsonPath("$.fresh") { value(true) }
        }
        assertEquals(2, jev.asked.size)
    }

    @Test
    fun `no answer from Jev is a 200 with nulls`() = live("run-pr-2", 6004) {
        val body = mvc.post("/ticket/acme/widgets/6004/predict-review").andExpect { status { isOk() } }.andReturn().response.contentAsString
        val view = jacksonObjectMapper().readValue<Map<String, Any?>>(body)
        assertEquals(jev.ids.single(), view["callId"])
        assertEquals(listOf(null, null, null, true), listOf(view["verdict"], view["confidence"], view["value"], view["fresh"]))
        assertTrue(view.containsKey("value"), body)
    }

    @Test
    fun `no usable Jev account is a 503`() = live("run-pr-3", 6005) {
        jev.noAccount = true
        mvc.post("/ticket/acme/widgets/6005/predict-review").andExpect { status { isServiceUnavailable() } }
    }

    @Test
    fun `the value is the confidence's percentage, signed by the verdict`() {
        assertEquals(-80, PredictReviewController.predictionOf("r", "c", 0.2, 0.8, 1).value)
        assertEquals(72, PredictReviewController.predictionOf("r", "c", 0.7, 0.72, 1).value)
        assertEquals(35, PredictReviewController.predictionOf("r", "c", 0.5, 0.35, 1).value)
        assertEquals(-65, PredictReviewController.predictionOf("r", "c", 0.35, null, 1).value, "no confidence: the noul's side")
        assertEquals(null, PredictReviewController.predictionOf("r", "c", null, null, 1).value)
    }
}

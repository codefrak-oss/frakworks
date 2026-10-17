package com.codefrak.engine.jev

import com.codefrak.engine.github.ReadFakes
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `GET /jev/calls`, `/jev/calls/{id}` and `/jev/summary` over MockMvc, against a seeded `engine.jev_call` in H2. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ReadFakes::class)
class JevControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var calls: JevCalls
    @Autowired lateinit var jdbc: JdbcClient

    private val json = jacksonObjectMapper()
    private val now = OffsetDateTime.now(ZoneOffset.UTC)
    private val day1 = now.minusDays(3).withHour(10).withMinute(0).withSecond(0).withNano(0)
    private val day2 = now.minusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0)
    private lateinit var ids: Map<String, String>

    private fun request(state: Map<String, Any?>, questions: Map<String, Any?>) =
        json.writeValueAsString(mapOf("model" to "jev-1.13.0", "state" to state, "questions" to questions))
    private fun choice(q: String, c: String, p: Double) =
        """{"model":"jev-1.13.0","answers":{"$q":{"type":"choice","choice":"$c","probabilities":{"$c":$p},"confidence":$p}}}"""
    private fun noul(v: Double) = """{"model":"jev-1.13.0","answers":{"accepts":{"type":"noul","noul":$v}}}"""
    private val refine = """{"model":"jev-1.13.0","answers":{
        "tier":{"type":"score","score":5.0,"probabilities":{"5":0.9,"4":0.1},"confidence":0.9},
        "kind":{"type":"choice","choice":"feature","probabilities":{"feature":0.95},"confidence":0.95},
        "featureMode":{"type":"choice","choice":"confirm","probabilities":{"confirm":0.85},"confidence":0.85}}}"""

    private fun seed(
        key: String, useCase: String, at: OffsetDateTime, repo: String?, issue: Long?, response: String?, error: String? = null,
        latency: Long? = 100, threshold: Double? = 0.8, action: String? = null, outcome: String? = null,
    ): Pair<String, String> {
        val id = calls.record(useCase, JevContext("pi-$key", repo, issue), "jev-secret-account", "jev-1.13.0",
            request(mapOf("answer" to "state of $key"), mapOf("q" to mapOf("type" to "noul", "instructions" to "?"))),
            response, error, latency, threshold)
        action?.let { calls.setAction(id, it) }
        outcome?.let { calls.recordOutcome(id, it) }
        jdbc.sql("UPDATE engine.jev_call SET called_at = :at WHERE id = :id").param("at", at).param("id", id).update()
        return key to id
    }

    @BeforeEach
    fun seed() {
        jdbc.sql("DELETE FROM engine.jev_call").update()
        ids = mapOf(
            seed("a1", "acceptance", day1, "acme/a", 1, noul(0.95), latency = 100, threshold = 0.9, action = "re-asked",
                outcome = "confirmed accepted: @u: yes"),
            seed("a2", "acceptance", day1.plusHours(1), "acme/a", 1, noul(0.2), latency = 300, threshold = 0.9, action = "kept rejection"),
            seed("a3", "acceptance", day2, "acme/a", 2, null, error = "no usable jev account", latency = null, threshold = 0.9),
            seed("c1", "ci-triage", day1.plusMinutes(1), "acme/b", 7, choice("cause", "flaky", 0.9), latency = 200, action = "flaky: re-ran the check",
                outcome = "the re-run passed (success): not the code"),
            seed("c2", "ci-triage", day2.plusMinutes(1), "acme/b", 7, choice("cause", "infrastructure", 0.85), latency = 400,
                action = "infrastructure: re-ran the check", outcome = "the re-run failed too (failure)"),
            seed("c3", "ci-triage", day2.plusHours(1), "acme/b", 7, choice("cause", "code", 0.55), latency = 600, action = "code: to the work",
                outcome = "the work Smith's answer to ci-1-1: addressed: fixed the test"),
            seed("i1", "incident-triage", day1.plusMinutes(2), "acme/b", 8, choice("remedy", "retry", 0.95), action = "retry: retried without a Smith",
                outcome = "retried without a Smith: retried"),
            seed("i2", "incident-triage", day2.plusMinutes(2), "acme/b", 8, choice("remedy", "retry", 0.92), action = "retry: retried without a Smith",
                outcome = "retried without a Smith: retried; tripped again (fixFailed): incident x at task-y"),
            seed("r1", "refine-pick", day1.plusMinutes(3), "acme/tickets", 20, refine, action = "filed: kind feature (jev), mode confirm (jev), tier 6 (jev)",
                outcome = "started as feature confirm tier 6; jev: agreed"),
            seed("r2", "refine-pick", day2.plusMinutes(3), "acme/tickets", 21, refine, action = "filed: kind feature (jev), mode confirm (jev), tier 6 (jev)",
                outcome = "started as chore tier 6"),
            seed("old", "acceptance", now.minusDays(40), "acme/a", 1, noul(0.99), action = "re-asked", outcome = "not accepted: @u: no"),
        ).toMap()
    }

    private fun get(url: String): Map<String, Any?> = json.readValue(mvc.get(url).andExpect { status { isOk() } }.andReturn().response.contentAsString)

    @Suppress("UNCHECKED_CAST")
    private fun rows(url: String) = get(url)["calls"] as List<Map<String, Any?>>
    private fun keys(url: String) = rows(url).map { row -> ids.entries.single { it.value == row["id"] }.key }

    @Test
    fun `the calls come newest first with the pick and confidence, and never the request, the response or the account`() {
        val page = get("/jev/calls")
        assertEquals(11, (page["total"] as Number).toInt())
        assertEquals(listOf("c3", "r2", "i2", "c2", "a3", "a2", "r1", "i1", "c1", "a1", "old"),
            keys("/jev/calls"), "newest first")
        val c1 = rows("/jev/calls?use_case=ci-triage").last()
        assertEquals(setOf("id", "called_at", "use_case", "process_instance", "repo", "issue", "model", "latency_ms", "threshold",
            "action", "outcome", "pick", "confidence", "no_answer"), c1.keys)
        assertEquals(listOf("flaky", 0.9, "pi-c1", "acme/b", 7, 200, 0.8, false),
            listOf(c1["pick"], c1["confidence"], c1["process_instance"], c1["repo"], c1["issue"], c1["latency_ms"], c1["threshold"], c1["no_answer"]))
        val body = mvc.get("/jev/calls").andReturn().response.contentAsString
        assertFalse("jev-secret-account" in body, "no account in the list")
        assertFalse("state of" in body, "no request in the list")
        val r1 = rows("/jev/calls?use_case=refine-pick").last()
        assertEquals("feature · confirm · tier 6", r1["pick"], "refine-pick's fields, in order")
        assertEquals(0.85, r1["confidence"], "at the lowest confidence")
        val a3 = rows("/jev/calls?use_case=acceptance&issue=2").single()
        assertEquals(listOf(null, null, true), listOf(a3["pick"], a3["confidence"], a3["no_answer"]))
    }

    @Test
    fun `use_case, repo, issue, action, outcome and since filter, and limit and offset page`() {
        assertEquals(listOf("c3", "c2", "c1"), keys("/jev/calls?use_case=ci-triage"))
        assertEquals(listOf("r2", "r1"), keys("/jev/calls?repo=acme/tickets"))
        assertEquals(listOf("i2", "i1"), keys("/jev/calls?repo=acme/b&issue=8"))
        assertEquals(listOf("c1"), keys("/jev/calls?action=flaky: re-ran"), "the action is a prefix")
        assertEquals(listOf("i2", "i1"), keys("/jev/calls?action=retry"))
        assertEquals(listOf("a3"), keys("/jev/calls?use_case=acceptance&outcome=unset&since=${day2.toLocalDate()}"))
        assertEquals(listOf("a3", "a2"), keys("/jev/calls?use_case=acceptance&outcome=unset"))
        assertEquals(listOf("a1", "old"), keys("/jev/calls?use_case=acceptance&outcome=set"))
        assertEquals(listOf("i2", "i1"), keys("/jev/calls?outcome=retried without a Smith: retried"))
        assertEquals(listOf("c1"), keys("/jev/calls?outcome=the re-run passed"))
        assertEquals(emptyList(), keys("/jev/calls?outcome=the_re-run"), "an _ is literal, not a wildcard")
        assertEquals(10, rows("/jev/calls?since=${now.minusDays(7).toInstant()}").size, "since drops the 40-day-old call")

        val page = get("/jev/calls?limit=2&offset=1")
        assertEquals(11, (page["total"] as Number).toInt())
        assertEquals(listOf("r2", "i2"), keys("/jev/calls?limit=2&offset=1"))
        assertEquals(listOf("old"), keys("/jev/calls?limit=5&offset=10"))
        mvc.get("/jev/calls?limit=0").andExpect { status { isBadRequest() } }
        mvc.get("/jev/calls?limit=501").andExpect { status { isBadRequest() } }
        mvc.get("/jev/calls?offset=-1").andExpect { status { isBadRequest() } }
        mvc.get("/jev/calls?since=yesterday").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `process_instance keeps one flow's calls and until bounds the window from above`() {
        assertEquals(listOf("c2"), keys("/jev/calls?process_instance=pi-c2"))
        assertEquals("pi-c2", rows("/jev/calls?process_instance=pi-c2").single()["process_instance"])
        assertEquals(emptyList(), keys("/jev/calls?process_instance=pi-none"))
        assertEquals(listOf("old"), keys("/jev/calls?until=${now.minusDays(7).toInstant()}"), "until keeps only the 40-day-old call")
        assertEquals(emptyList(), keys("/jev/calls?since=${now.minusDays(7).toInstant()}&until=${now.minusDays(7).toInstant()}"), "until is exclusive")
        val all = rows("/jev/calls?limit=500").map { it["called_at"] as String }
        val until = all[2]
        assertEquals(all.drop(3), rows("/jev/calls?limit=500&until=$until").map { it["called_at"] as String })
        mvc.get("/jev/calls?until=tomorrow").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `one call comes in full, the request as sent and the response as received or the error, and an unknown id is 404`() {
        val c1 = get("/jev/calls/${ids["c1"]}")
        assertEquals(mapOf("answer" to "state of c1"), c1["state"])
        assertEquals(mapOf("q" to mapOf("type" to "noul", "instructions" to "?")), c1["questions"])
        @Suppress("UNCHECKED_CAST")
        val answers = (c1["response"] as Map<String, Any?>)["answers"] as Map<String, Any?>
        assertEquals(setOf("cause"), answers.keys)
        assertNull(c1["error"])
        assertEquals("the re-run passed (success): not the code", c1["outcome"])
        @Suppress("UNCHECKED_CAST")
        val pick = (c1["picks"] as List<Map<String, Any?>>).single()
        assertEquals(listOf("cause", "flaky", 0.9, mapOf("flaky" to 0.9)), listOf(pick["question"], pick["pick"], pick["confidence"], pick["probabilities"]))
        assertFalse("jev-secret-account" in mvc.get("/jev/calls/${ids["c1"]}").andReturn().response.contentAsString)

        val a3 = get("/jev/calls/${ids["a3"]}")
        assertEquals("no usable jev account", a3["error"])
        assertNull(a3["response"])
        mvc.get("/jev/calls/no-such-call").andExpect { status { isNotFound() }; jsonPath("$.error") { exists() } }
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `the summary gives each use case's calls, no-answers, latency, picks, confidence, actions, agreement, runs saved and days`() {
        val summary = get("/jev/summary?since=${now.minusDays(7).toInstant()}")
        val useCases = (summary["useCases"] as List<Map<String, Any?>>).associateBy { it["useCase"] }
        assertEquals(listOf("acceptance", "proposal-decision", "ci-triage", "refine-pick", "incident-triage", "intake-duplicate", "friction-group", "friction-two-things", "review-prediction"),
            useCases.keys.toList(), "the seven, always, in the page's order")

        val totals = summary["totals"] as Map<String, Any?>
        assertEquals(listOf(10, 1, 100, 8, 7, 4), listOf("calls", "noAnswer", "medianLatencyMs", "withOutcome", "judged", "agreed").map { (totals[it] as Number).toInt() })

        val acceptance = useCases.getValue("acceptance")
        assertEquals(listOf(3, 1, 200, 1, 1, 1), listOf("calls", "noAnswer", "medianLatencyMs", "withOutcome", "judged", "agreed")
            .map { (acceptance[it] as Number).toInt() }, "the 40-day-old call is outside the window; the median of 100 and 300")
        assertEquals(mapOf("accepts" to mapOf("yes" to 1, "no" to 1)), acceptance["picks"])
        assertEquals(listOf(0, 0, 1, 0, 0, 0, 0, 0, 0, 1), acceptance["confidence"], "a noul's value is its confidence")
        assertEquals(0.9, acceptance["floor"])
        assertEquals(mapOf("re-asked" to 1, "kept rejection" to 1), acceptance["actions"])
        assertEquals(1.0, acceptance["agreement"])
        assertNull(acceptance["runsSaved"], "only the triage use cases save runs")
        assertEquals(mapOf(day1.toLocalDate().toString() to 2, day2.toLocalDate().toString() to 1), acceptance["perDay"])

        val ci = useCases.getValue("ci-triage")
        assertEquals(mapOf("cause" to mapOf("flaky" to 1, "infrastructure" to 1, "code" to 1)), ci["picks"])
        assertEquals(listOf(0, 0, 0, 0, 0, 1, 0, 0, 1, 1), ci["confidence"])
        assertEquals(listOf(3, 2, 1), listOf("withOutcome", "judged", "agreed").map { (ci[it] as Number).toInt() },
            "the work Smith's free-text answer has an outcome but no verdict")
        assertEquals(0.5, ci["agreement"])
        assertEquals(1, ci["runsSaved"], "the re-run that passed")
        assertEquals(mapOf("flaky: re-ran the check" to 1, "infrastructure: re-ran the check" to 1, "code: to the work" to 1), ci["actions"])

        val incident = useCases.getValue("incident-triage")
        assertEquals(1, incident["runsSaved"], "the retry that tripped again saved nothing")
        assertEquals(0.5, incident["agreement"])

        val refine = useCases.getValue("refine-pick")
        assertEquals(mapOf("kind" to mapOf("feature" to 2), "featureMode" to mapOf("confirm" to 2), "tier" to mapOf("tier 6" to 2)), refine["picks"])
        assertEquals(listOf(2, 1), listOf("judged", "agreed").map { (refine[it] as Number).toInt() },
            "the agreed start, and an older row relabelled to a chore, judged from its `started as` alone")
        assertEquals(mapOf("filed at jev's pick" to 2), refine["actions"])

        val none = useCases.getValue("intake-duplicate")
        assertEquals(listOf(0, 0, 0), listOf("calls", "withOutcome", "judged").map { (none[it] as Number).toInt() })
        assertNull(none["agreement"], "a dash until outcomes exist")
        assertNull(none["medianLatencyMs"])
        assertNull(none["runsSaved"])
        assertEquals(emptyMap<String, Any>(), none["perDay"])
    }

    @Test
    fun `the summary's window defaults to seven days and refuses a since it cannot read`() {
        val summary = get("/jev/summary")
        assertEquals(10, ((summary["totals"] as Map<*, *>)["calls"] as Number).toInt())
        assertTrue((summary["since"] as String) < (summary["until"] as String))
        mvc.get("/jev/summary?since=last week").andExpect { status { isBadRequest() } }
        assertEquals(11, ((get("/jev/summary?since=${now.minusDays(60).toLocalDate()}")["totals"] as Map<*, *>)["calls"] as Number).toInt())
    }
}

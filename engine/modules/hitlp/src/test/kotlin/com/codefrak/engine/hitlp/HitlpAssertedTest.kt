package com.codefrak.engine.hitlp

import com.codefrak.engine.hitlp.HitlpAsserted.Kind
import com.codefrak.engine.hitlp.HitlpAsserted.Wait
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [HitlpAsserted] (`spec/hitlp.md` §5.1 to §5.4): every member of `asserted` for a flow wait, `onBehalfOf` as a run
 * for a Smith's question and as a person for the rest, `requiresHuman` per kind, never `requester` or `attested`, and
 * every built request passing [HitlpSchemas.requesterProblem] against the vendored 0.2 schemas.
 */
class HitlpAssertedTest {
    private val now = OffsetDateTime.of(2026, 10, 11, 10, 0, 0, 0, ZoneOffset.ofHours(2))
    private val question = Wait(Kind.QUESTION, "acme/tickets", 7, runId = "run-1", runLink = "https://engine.example/runs/run-1", askedBy = "lundmatt1")
    private val decision = Wait(Kind.DECISION, "acme/tickets", 7, runId = "run-2", runLink = "https://engine.example/runs/run-2", askedBy = "lundmatt1")

    @Test
    fun `why names what the flow waits for, one sentence per kind`() {
        for (kind in Kind.entries) {
            val why = HitlpAsserted.asserted(decision.copy(kind = kind, runId = "r", runLink = "l"), now)["why"] as String
            assertEquals(kind.why, why)
            assertTrue(why.endsWith(".") && why.count { it == '.' } == 1, "one sentence: $why")
            assertTrue("waits for" in why, why)
        }
    }

    @Test
    fun `a Smith's question is on behalf of its run, with the ticket, and every other wait on behalf of the person who asked`() {
        assertEquals(mapOf("kind" to "run", "id" to "run-1", "ticket" to "acme/tickets#7"), HitlpAsserted.asserted(question, now)["onBehalfOf"])
        for (kind in Kind.entries - Kind.QUESTION)
            assertEquals(mapOf("kind" to "person", "id" to "lundmatt1"), HitlpAsserted.asserted(decision.copy(kind = kind), now)["onBehalfOf"], kind.name)
        assertFailsWith<IllegalArgumentException> { HitlpAsserted.asserted(question.copy(runId = null), now) }
        assertFailsWith<IllegalArgumentException> { HitlpAsserted.asserted(decision.copy(askedBy = null), now) }
        assertFailsWith<IllegalArgumentException> { HitlpAsserted.asserted(decision.copy(askedBy = " "), now) }
    }

    @Test
    fun `recommendation is none, lineage is the ticket then the run, asOf is the build time in UTC`() {
        val asserted = HitlpAsserted.asserted(question, now)
        assertEquals(mapOf("verdict" to "none"), asserted["recommendation"])
        assertEquals(listOf(
            mapOf("kind" to "ticket", "claim" to "frakworks://ticket/acme/tickets/7"),
            mapOf("kind" to "run", "claim" to "https://engine.example/runs/run-1")), asserted["lineage"])
        assertEquals("2026-10-11T08:00:00Z", asserted["asOf"])
        assertEquals(setOf("why", "onBehalfOf", "recommendation", "lineage", "asOf"), asserted.keys)
        // A wait with no run has the ticket link alone.
        assertEquals(listOf(mapOf("kind" to "ticket", "claim" to "frakworks://ticket/acme/tickets/7")),
            HitlpAsserted.asserted(decision.copy(runId = null, runLink = null), now)["lineage"])
    }

    @Test
    fun `requiresHuman is true on a decision, an ack, a legacy acceptance and a merge acceptance, false on a question`() {
        assertEquals(false, HitlpAsserted.envelope(question, now)["requiresHuman"])
        for (kind in listOf(Kind.DECISION, Kind.ACK, Kind.ACCEPTANCE, Kind.MERGE))
            assertEquals(true, HitlpAsserted.envelope(decision.copy(kind = kind), now)["requiresHuman"], kind.name)
        assertEquals(setOf("asserted", "requiresHuman"), HitlpAsserted.envelope(question, now).keys)
    }

    @Test
    fun `never requester, never attested, and withoutServerMembers drops both`() {
        for (kind in Kind.entries) {
            val envelope = HitlpAsserted.envelope(decision.copy(kind = kind), now)
            assertTrue("requester" !in envelope && "attested" !in envelope, kind.name)
            assertTrue("requester" !in (envelope["asserted"] as Map<*, *>) && "attested" !in (envelope["asserted"] as Map<*, *>), kind.name)
        }
        val stored = mapOf("idempotencyKey" to "k", "requester" to mapOf("agent" to "bot"), "attested" to mapOf("requester" to "x", "by" to "y"), "question" to "Q")
        assertEquals(mapOf("idempotencyKey" to "k", "question" to "Q"), HitlpAsserted.withoutServerMembers(stored))
    }

    @Test
    fun `every built request passes the requester check against the vendored 0_2 schemas`() {
        val common = mapOf("idempotencyKey" to "k", "deadline" to "2026-10-12T10:00:00Z", "defaultOnTimeout" to "reject")
        val ask = common + mapOf("question" to "Which?", "responseSchema" to mapOf("type" to "string", "enum" to listOf("a", "b")),
            "options" to listOf(mapOf("value" to "a", "label" to "A"), mapOf("value" to "b", "label" to "B")))
        val approve = common + mapOf("action" to "Merge PR #9", "payload" to mapOf("pr" to 9), "scope" to mapOf("maxUses" to 1, "notAfter" to "2026-10-12T10:00:00Z"))
        assertNull(HitlpSchemas.requesterProblem(HitlpPrimitives.ASK, ask + HitlpAsserted.envelope(question, now)))
        for (kind in Kind.entries - Kind.QUESTION) {
            val wait = decision.copy(kind = kind)
            assertNull(HitlpSchemas.requesterProblem(HitlpPrimitives.ASK, ask + HitlpAsserted.envelope(wait, now)), kind.name)
            assertNull(HitlpSchemas.requesterProblem(HitlpPrimitives.APPROVE, approve + HitlpAsserted.envelope(wait, now)), kind.name)
            assertNull(HitlpSchemas.requesterProblem(HitlpPrimitives.APPROVE, approve + HitlpAsserted.envelope(wait.copy(runId = null, runLink = null), now)), kind.name)
        }
    }
}

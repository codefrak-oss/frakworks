package com.codefrak.engine.hitlp

import com.codefrak.engine.engineJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.readValue

/** [HitlpSchemas] over the vendored schemas: the spec's four examples fit, and a broken request names its failing path. */
class HitlpSchemasTest {
    private fun example(name: String): Map<String, Any?> =
        engineJson.readValue(javaClass.getResourceAsStream("/hitlp/examples/$name.json")!!.readBytes())

    private val ask = example("ask.request")
    private val approve = example("approve.request")

    @Test
    fun `the four spec examples pass their schemas`() {
        assertEquals(emptyList<String>(), HitlpSchemas.problems(HitlpSchemas.of(HitlpSchemas.ASK_REQUEST), ask))
        assertEquals(emptyList<String>(), HitlpSchemas.problems(HitlpSchemas.of(HitlpSchemas.APPROVE_REQUEST), approve))
        assertEquals(emptyList<String>(), HitlpSchemas.problems(HitlpSchemas.of(HitlpSchemas.ASK_RESPONSE), example("ask.response")))
        assertEquals(emptyList<String>(), HitlpSchemas.problems(HitlpSchemas.of(HitlpSchemas.APPROVE_RESPONSE), example("approve.response")))
        assertEquals(emptyList<String>(), HitlpSchemas.problems(HitlpSchemas.of(HitlpSchemas.DECISION_RECORD), example("approve.response")))
    }

    private fun firstAsk(args: Map<String, Any?>) = HitlpSchemas.firstProblem(HitlpSchemas.request(HitlpPrimitives.ASK), args)
    private fun firstApprove(args: Map<String, Any?>) = HitlpSchemas.firstProblem(HitlpSchemas.request(HitlpPrimitives.APPROVE), args)

    @Test
    fun `a missing idempotencyKey or deadline is named at its path, through the envelope ref`() {
        assertTrue(firstAsk(ask - "idempotencyKey")!!.startsWith("/idempotencyKey:"), firstAsk(ask - "idempotencyKey"))
        assertTrue(firstAsk(ask - "deadline")!!.startsWith("/deadline:"), firstAsk(ask - "deadline"))
    }

    @Test
    fun `a malformed deadline fails the date-time format, and a bad defaultOnTimeout its enum`() {
        assertTrue(firstAsk(ask + ("deadline" to "tomorrow"))!!.startsWith("/deadline:"), firstAsk(ask + ("deadline" to "tomorrow")))
        assertTrue(firstAsk(ask + ("defaultOnTimeout" to "approve"))!!.startsWith("/defaultOnTimeout:"), firstAsk(ask + ("defaultOnTimeout" to "approve")))
    }

    @Test
    fun `an approve's payload is an object and an ask's question is required`() {
        assertTrue(firstApprove(approve + ("payload" to "a string"))!!.startsWith("/payload:"), firstApprove(approve + ("payload" to "a string")))
        assertTrue(firstAsk(ask - "question")!!.startsWith("/question:"), firstAsk(ask - "question"))
    }

    @Test
    fun `a record answered without an answer fails the decision-record schema`() {
        val record = example("ask.response") - "answer"
        val problem = HitlpSchemas.firstProblem(HitlpSchemas.of(HitlpSchemas.DECISION_RECORD), record)
        assertTrue(problem!!.startsWith("/answer:"), problem)
    }

    @Test
    fun `a responseSchema compiles and validates an answer, naming the root`() {
        val schema = HitlpSchemas.compile(ask["responseSchema"])
        assertEquals(null, HitlpSchemas.firstProblem(schema, "EUR"))
        assertTrue(HitlpSchemas.firstProblem(schema, "CHF")!!.startsWith("/:"))
        assertTrue(HitlpSchemas.firstProblem(HitlpSchemas.compile(mapOf("type" to "object", "required" to listOf("n"))), mapOf("m" to 1))!!.startsWith("/n:"))
    }

    @Test
    fun `the digest is over the canonical form, so key order does not change it but a value does`() {
        val a = HitlpSchemas.digest(linkedMapOf("b" to 1, "a" to mapOf("y" to true, "x" to "v")))
        val b = HitlpSchemas.digest(linkedMapOf("a" to linkedMapOf("x" to "v", "y" to true), "b" to 1))
        assertEquals(a, b)
        assertTrue(a.startsWith("sha256:") && a.length == 7 + 64, a)
        assertNotEquals(a, HitlpSchemas.digest(linkedMapOf("b" to 2, "a" to mapOf("y" to true, "x" to "v"))))
    }

    @Test
    fun `the engine's requester extensions validate beside the vendored schema, and the record's rule is open`() {
        val extended = approve + ("requester" to mapOf("agent" to "bot", "principal" to "smith:acme/ops#41", "autoApprovable" to false))
        assertEquals(null, HitlpSchemas.requestProblem(HitlpPrimitives.APPROVE, extended))
        assertEquals(null, HitlpSchemas.requestProblem(HitlpPrimitives.ASK, ask + ("requester" to mapOf("principal" to "other:x"))))
        assertEquals("/requester/principal: must be a string", HitlpSchemas.requestProblem(HitlpPrimitives.APPROVE, approve + ("requester" to mapOf("principal" to 7))))
        assertEquals("/requester/autoApprovable: must be a boolean", HitlpSchemas.requestProblem(HitlpPrimitives.APPROVE, approve + ("requester" to mapOf("autoApprovable" to "no"))))
        // 0.1 closed `requester`; 0.2 no longer defines it and the envelope is open at the top level, so an unknown member passes the server's check (#2447).
        assertEquals(null, HitlpSchemas.requestProblem(HitlpPrimitives.APPROVE, approve + ("requester" to mapOf("other" to 1))))
        assertEquals(emptyList<String>(), HitlpSchemas.problems(HitlpSchemas.of(HitlpSchemas.APPROVE_RESPONSE), example("approve.response") + ("rule" to "r1")))
    }

    @Test
    fun `a 0_2 request with asserted and requiresHuman passes the requester check for both primitives`() {
        val asserted = mapOf("why" to "The flow waits for a decision.", "onBehalfOf" to mapOf("kind" to "person", "id" to "lundmatt1"),
            "recommendation" to mapOf("verdict" to "none"), "asOf" to "2026-10-11T10:00:00Z",
            "lineage" to listOf(mapOf("kind" to "ticket", "claim" to "https://github.com/acme/tickets/issues/7"), mapOf("kind" to "run", "claim" to "run-1")))
        assertEquals(null, HitlpSchemas.requesterProblem(HitlpPrimitives.ASK, ask + ("asserted" to asserted) + ("requiresHuman" to true)))
        assertEquals(null, HitlpSchemas.requesterProblem(HitlpPrimitives.APPROVE, approve), "the spec's 0.2 approve example carries asserted")
    }

    @Test
    fun `a requester's request carrying requester or attested is refused by name, before the schema`() {
        val withRequester = HitlpSchemas.requesterProblem(HitlpPrimitives.ASK, ask + ("requester" to mapOf("agent" to "bot")))
        assertTrue(withRequester!!.startsWith("/requester:"), withRequester)
        val withAttested = HitlpSchemas.requesterProblem(HitlpPrimitives.APPROVE, approve + ("attested" to mapOf("requester" to "engine", "by" to "engine")))
        assertTrue(withAttested!!.startsWith("/attested:"), withAttested)
        // Both would pass the schema alone: the 0.2 envelope defines them and is open at the top level.
        assertEquals(null, firstAsk(ask + ("requester" to mapOf("agent" to "bot"))))
        assertEquals(null, firstApprove(approve + ("attested" to mapOf("requester" to "engine", "by" to "engine"))))
        // The schema still runs over the rest.
        assertTrue(HitlpSchemas.requesterProblem(HitlpPrimitives.ASK, ask - "deadline")!!.startsWith("/deadline:"))
    }

    @Test
    fun `a lineage item without kind or claim fails at its path`() {
        fun lineage(item: Map<String, Any?>) = ask + ("asserted" to mapOf("lineage" to listOf(item)))
        val noClaim = HitlpSchemas.requesterProblem(HitlpPrimitives.ASK, lineage(mapOf("kind" to "ticket")))
        assertTrue(noClaim!!.startsWith("/asserted/lineage/0/claim:"), noClaim)
        val noKind = HitlpSchemas.requesterProblem(HitlpPrimitives.ASK, lineage(mapOf("claim" to "Fix the DNS.")))
        assertTrue(noKind!!.startsWith("/asserted/lineage/0/kind:"), noKind)
    }

    @Test
    fun `every request HitlpFiling builds passes requesterProblem`() {
        fun wait(kind: HitlpAsserted.Kind, block: Map<String, Any?>? = null) = FlowWait("pi-1", "ask", kind, "codefrak-oss/frakworks", 42, "Which way?",
            askBlock = block, askedBy = "lundmatt1", runId = "run-1", runLink = "https://engine.example/runs/run-1", pr = 7, head = "abc123")
        for (kind in listOf(HitlpAsserted.Kind.QUESTION, HitlpAsserted.Kind.DECISION, HitlpAsserted.Kind.ACK))
            for (block in listOf(null, mapOf("kind" to "proposal"), mapOf("kind" to "research"), mapOf("options" to listOf("yes", "no")))) {
                val args = HitlpFiling.askRequest(wait(kind, block), refile = 1)
                assertEquals(null, HitlpSchemas.requesterProblem(HitlpPrimitives.ASK, args), "$kind $block: $args")
            }
        for (kind in listOf(HitlpAsserted.Kind.MERGE, HitlpAsserted.Kind.ACCEPTANCE)) {
            val args = HitlpFiling.approveRequest(wait(kind))
            assertEquals(null, HitlpSchemas.requesterProblem(HitlpPrimitives.APPROVE, args), "$kind: $args")
            assertEquals(HitlpSchemas.digest(args["payload"]), args["payloadDigest"])
            assertEquals(true, args["requiresHuman"])
        }
        val proposal = HitlpFiling.askRequest(wait(HitlpAsserted.Kind.DECISION, mapOf("kind" to "proposal")))
        assertEquals(HitlpFiling.PROPOSAL_VERBS, (proposal["options"] as List<*>).map { (it as Map<*, *>)["value"] })
        assertEquals(mapOf("type" to "string", "enum" to HitlpFiling.PROPOSAL_VERBS), proposal["responseSchema"])
        val research = HitlpFiling.askRequest(wait(HitlpAsserted.Kind.ACK, mapOf("kind" to "research")))
        assertEquals(HitlpFiling.RESEARCH_VERBS, (research["responseSchema"] as Map<*, *>)["enum"])
        assertEquals(mapOf("type" to "string"), HitlpFiling.askRequest(wait(HitlpAsserted.Kind.QUESTION))["responseSchema"])
        assertEquals("merge PR #7 of codefrak-oss/frakworks", HitlpFiling.approveRequest(wait(HitlpAsserted.Kind.MERGE))["action"])
    }

    @Test
    fun `a choice ask's responseSchema is a plain string with no enum, its options sent as before`() {
        val block = mapOf("kind" to "choice", "options" to listOf(mapOf("value" to "pg", "label" to "Postgres"), mapOf("value" to "redis", "label" to "Redis")))
        val args = HitlpFiling.askRequest(FlowWait("pi-1", "ask", HitlpAsserted.Kind.QUESTION, "codefrak-oss/frakworks", 42, "Which store?",
            askBlock = block, askedBy = "lundmatt1", runId = "run-1", runLink = "https://engine.example/runs/run-1"))
        assertEquals(mapOf("type" to "string"), args["responseSchema"])
        assertFalse((args["responseSchema"] as Map<*, *>).containsKey("enum"))
        assertEquals(listOf("pg", "redis"), (args["options"] as List<*>).map { (it as Map<*, *>)["value"] })
        assertEquals(null, HitlpSchemas.requesterProblem(HitlpPrimitives.ASK, args))
    }
}

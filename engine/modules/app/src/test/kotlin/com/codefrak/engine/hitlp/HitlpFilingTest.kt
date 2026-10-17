package com.codefrak.engine.hitlp

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [HitlpFiling] against [StubHitlpServer]: an ask and an approve are filed with the stub's token, stored under
 * `<pi>:<utk>:<nth>` (`:r<n>` on a re-file) with the handle's interval, `task.ttl` is the deadline's remaining ms, and
 * the decision URL falls back from `_meta` to the `statusMessage` URL to null; no server refuses naming PUT /settings/hitlp.
 */
@SpringBootTest
class HitlpFilingTest {
    companion object {
        val stub = StubHitlpServer()
        @JvmStatic @DynamicPropertySource fun hitlp(registry: DynamicPropertyRegistry) = stub.register(registry)
        @JvmStatic @AfterAll fun stop() = stub.stop()
    }

    @Autowired lateinit var filing: HitlpFiling
    @Autowired lateinit var requests: HitlpRequests
    @Autowired lateinit var jdbc: JdbcClient

    private val wait = FlowWait("pi-9", "decide", HitlpAsserted.Kind.DECISION, "codefrak-oss/frakworks", 42, "Accept the proposal?",
        askBlock = mapOf("kind" to "proposal"), askedBy = "lundmatt1", runId = "run-1", pr = 7, head = "abc123")

    @BeforeEach
    fun reset() { jdbc.sql("DELETE FROM engine.hitlp_request").update(); stub.reset() }

    @Test
    fun `an ask is filed, stored under its key and answers the handle with the meta decision URL`() {
        val filed = filing.ask(wait)
        val call = stub.calls("tools/call").single()
        assertEquals("Bearer ${StubHitlpServer.TOKEN}", call.authorization)
        assertEquals("human.ask", call.params["name"])
        val args = call.params["arguments"] as Map<*, *>
        assertEquals("pi-9:decide:1", args["idempotencyKey"])
        val ttl = ((call.params["task"] as Map<*, *>)["ttl"] as Number).toLong()
        assertTrue(ttl in Duration.ofDays(7).minusMinutes(1).toMillis()..Duration.ofDays(7).toMillis(), "ttl $ttl")
        val row = requests.get(filed.handle.taskId)!!
        assertEquals("pi-9:decide:1", row.idempotencyKey); assertEquals("pi-9", row.processInstance); assertEquals("decide", row.userTaskKey)
        assertEquals("DECISION", row.kind); assertEquals(10, row.pollIntervalMs); assertTrue(row.open)
        assertEquals("${StubHitlpServer.DECISION_BASE}${filed.handle.taskId}", filed.decisionUrl)
    }

    @Test
    fun `a re-file gets r-n, an approve is human approve, and the URL falls back to the status message, then null`() {
        stub.handle = mapOf("_meta" to null)
        val refiled = filing.ask(wait, refile = 1)
        assertEquals("pi-9:decide:1:r1", refiled.request.idempotencyKey)
        assertEquals("${StubHitlpServer.DECISION_BASE}${refiled.handle.taskId}", refiled.decisionUrl, "from the statusMessage")

        stub.handle = mapOf("_meta" to null, "statusMessage" to "waiting")
        val merge = filing.approve(wait.copy(kind = HitlpAsserted.Kind.MERGE, userTaskKey = "merge", nth = 2))
        assertNull(merge.decisionUrl)
        assertEquals("pi-9:merge:2", merge.request.idempotencyKey)
        val call = stub.calls("tools/call").last()
        assertEquals("human.approve", call.params["name"])
        assertEquals(true, (call.params["arguments"] as Map<*, *>)["requiresHuman"])
        assertEquals(2, requests.openFor("pi-9").size)
    }

    @Test
    fun `an approve is urgent and an ask high, so a surface interrupts for a merge`() {
        assertEquals("high", HitlpFiling.askRequest(wait)["priority"])
        assertEquals("urgent", HitlpFiling.approveRequest(wait.copy(kind = HitlpAsserted.Kind.MERGE))["priority"])
        assertEquals("urgent", HitlpFiling.approveRequest(wait.copy(kind = HitlpAsserted.Kind.ACCEPTANCE))["priority"])
    }

    @Test
    fun `an ask and an approve carry the summary, and the repo and ticket in context data`() {
        val ask = HitlpFiling.askRequest(wait)["context"] as Map<*, *>
        assertEquals("codefrak-oss/frakworks#42", ask["summary"])
        assertEquals(mapOf("repo" to "codefrak-oss/frakworks", "ticket" to 42L), ask["data"])
        val approve = HitlpFiling.approveRequest(wait.copy(kind = HitlpAsserted.Kind.MERGE))["context"] as Map<*, *>
        assertEquals("Accept the proposal?", approve["summary"])
        assertEquals(mapOf("repo" to "codefrak-oss/frakworks", "ticket" to 42L), approve["data"])
        filing.ask(wait)
        val filed = (stub.calls("tools/call").single().params["arguments"] as Map<*, *>)["context"] as Map<*, *>
        assertEquals(42, ((filed["data"] as Map<*, *>)["ticket"] as Number).toInt())
    }

    @Test
    fun `no server refuses naming PUT settings hitlp and stores nothing`() {
        val blank = HitlpFiling(HitlpRequester({ null }, Duration.ofSeconds(1)), requests)
        val e = assertFailsWith<IllegalStateException> { blank.ask(wait) }
        assertTrue("PUT /settings/hitlp" in e.message!!, e.message)
        assertEquals(emptyList(), requests.openFor("pi-9"))
    }
}

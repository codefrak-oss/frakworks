package com.codefrak.engine.hitlp

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [HitlpPoller] against [StubHitlpServer] with a fake [HitlpAnswerer]: no timer, a reconciliation reads every open row,
 * a terminal notification is finished from `tasks/result`, an open task's next poll moved by its interval, a terminal one's record stored and the answerer called once, and an answerer that throws
 * leaves the row open and due for the next tick.
 */
@SpringBootTest
class HitlpPollerTest {
    companion object {
        val stub = StubHitlpServer()
        @JvmStatic @DynamicPropertySource fun hitlp(registry: DynamicPropertyRegistry) = stub.register(registry)
        @JvmStatic @AfterAll fun stop() = stub.stop()
    }

    @Autowired lateinit var filing: HitlpFiling
    @Autowired lateinit var requests: HitlpRequests
    @Autowired lateinit var requester: HitlpRequester
    @Autowired lateinit var tx: TransactionTemplate
    @Autowired lateinit var stream: HitlpTaskStream
    @Autowired lateinit var jdbc: JdbcClient

    /** Records every completion; throws while [fail] is set. */
    class FakeAnswerer : HitlpAnswerer {
        val completed = mutableListOf<Pair<HitlpRequest, Map<String, Any?>>>()
        var fail = false
        override fun complete(request: HitlpRequest, record: Map<String, Any?>) {
            if (fail) throw IllegalStateException("answerer down")
            completed += request to record
        }
    }

    private val answerer = FakeAnswerer()
    private val wait = FlowWait("pi-7", "ask", HitlpAsserted.Kind.QUESTION, "codefrak-oss/frakworks", 42, "Which currency?", runId = "run-1")
    private val record = mapOf("requestId" to "x", "idempotencyKey" to "pi-7:ask:1", "primitive" to "ask", "outcome" to "answered", "answer" to "EUR",
        "decidedBy" to mapOf("type" to "human", "id" to "lundmatt1"), "decidedAt" to "2026-10-11T12:00:01Z", "channel" to "desktop")

    private fun poller() = HitlpPoller(requester, requests, listOf(answerer), tx)
    // Postgres keeps microseconds: a nanosecond `now` would not read back equal.
    private fun later() = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1).truncatedTo(ChronoUnit.MICROS)

    @BeforeEach
    fun reset() { stream.stop(); jdbc.sql("DELETE FROM engine.hitlp_request").update(); stub.reset() }

    @Test
    fun `no timer runs it, and reconcile reads every open row, due or not`() {
        assertTrue(HitlpPoller::class.java.methods.none { it.isAnnotationPresent(Scheduled::class.java) }, "no timer poll (#2463)")
        val filed = filing.ask(wait)
        requests.bump(filed.handle.taskId, 3_600_000)
        stub.reset()
        assertEquals(emptyList(), requests.due(OffsetDateTime.now(ZoneOffset.UTC)), "not due for an hour")
        stub.decide(filed.handle.taskId, record)
        assertEquals(1, poller().reconcile())
        assertEquals(listOf("tasks/get"), stub.calls.map { it.method })
        assertEquals(record, answerer.completed.single().second)
    }

    @Test
    fun `a terminal notification reads tasks-result and answers once, and an open or unknown task is left alone`() {
        val filed = filing.ask(wait)
        assertEquals(false, poller().notified(filed.handle.taskId, "input_required"))
        assertEquals(false, poller().notified("t-unknown", HitlpPrimitives.COMPLETED))
        stub.reset(); stub.decide(filed.handle.taskId, record)
        assertTrue(poller().notified(filed.handle.taskId, HitlpPrimitives.COMPLETED))
        assertEquals(listOf("tasks/result"), stub.calls.map { it.method })
        assertEquals(record, requests.get(filed.handle.taskId)!!.record)
        assertEquals(false, poller().notified(filed.handle.taskId, HitlpPrimitives.COMPLETED), "finished once")
        assertEquals(1, answerer.completed.size)
    }

    @Test
    fun `an open task is bumped, a terminal one finished and answered once`() {
        val filed = filing.ask(wait)
        val now = later()
        assertEquals(0, poller().poll(now))
        val bumped = requests.get(filed.handle.taskId)!!
        assertTrue(bumped.open); assertEquals(now.plusNanos(10_000_000L).toInstant(), bumped.nextPollAt.toInstant())
        assertEquals(emptyList(), answerer.completed)

        stub.decide(filed.handle.taskId, record)
        assertEquals(1, poller().poll(later()))
        assertEquals(record, requests.get(filed.handle.taskId)!!.record)
        assertEquals(filed.handle.taskId, answerer.completed.single().first.taskId)
        assertEquals(record, answerer.completed.single().second)
        assertEquals(0, poller().poll(later().plusSeconds(1)), "a finished row is never polled again")
        assertEquals(1, answerer.completed.size)
    }

    @Test
    fun `an answerer that throws leaves the row open and due, and the next tick finishes it`() {
        val filed = filing.ask(wait)
        stub.decide(filed.handle.taskId, record)
        answerer.fail = true
        assertEquals(0, poller().poll(later()))
        assertNull(requests.get(filed.handle.taskId)!!.record, "the finish rolled back with the answerer")
        assertEquals(listOf(filed.handle.taskId), requests.due(later()).map { it.taskId })
        answerer.fail = false
        assertEquals(1, poller().poll(later()))
        assertEquals(record, requests.get(filed.handle.taskId)!!.record)
        assertEquals(1, answerer.completed.size)
    }
}

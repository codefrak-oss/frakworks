package com.codefrak.engine.hitlp

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [HitlpRequests] over `engine.hitlp_request` (V81) on H2: a row round-trips, `due` answers the open rows whose poll has
 * passed in order, `bump` moves one's next poll by the server's interval, `finish` writes the record once and takes the
 * row out of `due` and `openFor`, and `openFor` is one flow's open rows.
 */
@SpringBootTest
class HitlpRequestsTest {
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var requests: HitlpRequests

    private val t0 = OffsetDateTime.of(2026, 10, 11, 12, 0, 0, 0, ZoneOffset.UTC)

    @BeforeEach
    fun reset() { jdbc.sql("DELETE FROM engine.hitlp_request").update() }

    private fun row(taskId: String, nextPollAt: OffsetDateTime, flow: String = "pi-1", kind: String = "QUESTION") = HitlpRequest(
        taskId = taskId, idempotencyKey = "key-$taskId", processInstance = flow, userTaskKey = "ask", kind = kind,
        pollIntervalMs = 5000, createdAt = t0, nextPollAt = nextPollAt)

    @Test
    fun `a saved row reads back as it was, and an unknown task id is null`() {
        val saved = requests.save(row("t-1", t0.plusSeconds(5)))
        val read = requests.get("t-1")!!
        assertEquals(saved.copy(createdAt = read.createdAt, nextPollAt = read.nextPollAt), read)
        assertEquals(t0.toInstant(), read.createdAt.toInstant()); assertEquals(t0.plusSeconds(5).toInstant(), read.nextPollAt.toInstant())
        assertTrue(read.open); assertNull(read.record); assertNull(read.judgeCallId)
        assertNull(requests.get("nope"))
    }

    @Test
    fun `due is the open rows whose next poll has passed, earliest first, and bump moves one past now`() {
        requests.save(row("late", t0.minusSeconds(10)))
        requests.save(row("later", t0.minusSeconds(5)))
        requests.save(row("future", t0.plusSeconds(60)))
        assertEquals(listOf("late", "later"), requests.due(t0).map { it.taskId })
        assertTrue(requests.bump("late", 2500, now = t0))
        assertEquals(listOf("later"), requests.due(t0).map { it.taskId })
        val bumped = requests.get("late")!!
        assertEquals(2500, bumped.pollIntervalMs); assertEquals(t0.plusNanos(2_500_000_000L).toInstant(), bumped.nextPollAt.toInstant())
        assertEquals(listOf("later", "late"), requests.due(t0.plusSeconds(3)).map { it.taskId })
        assertFalse(requests.bump("nope", 1000, now = t0))
    }

    @Test
    fun `finish writes the record once and the row leaves due and openFor, and a finished row is never bumped`() {
        requests.save(row("t-1", t0.minusSeconds(1)))
        requests.save(row("t-2", t0.minusSeconds(1)))
        requests.save(row("t-3", t0.minusSeconds(1), flow = "pi-2", kind = "DECISION"))
        assertEquals(listOf("t-1", "t-2"), requests.openFor("pi-1").map { it.taskId })
        val record = mapOf("requestId" to "t-1", "idempotencyKey" to "key-t-1", "primitive" to "ask", "outcome" to "answered", "answer" to "EUR",
            "decidedBy" to mapOf("type" to "human", "id" to "lundmatt1"), "decidedAt" to "2026-10-11T12:00:01Z", "channel" to "desktop")
        assertTrue(requests.finish("t-1", record, judgeCallId = "jev-1"))
        assertFalse(requests.finish("t-1", record + ("outcome" to "cancelled")), "a record is written once")
        assertFalse(requests.bump("t-1", 1000, now = t0))
        val finished = requests.get("t-1")!!
        assertEquals(record, finished.record); assertEquals("jev-1", finished.judgeCallId); assertFalse(finished.open)
        assertEquals(listOf("t-2", "t-3"), requests.due(t0).map { it.taskId })
        assertEquals(listOf("t-2"), requests.openFor("pi-1").map { it.taskId })
        assertEquals(listOf("t-3"), requests.openFor("pi-2").map { it.taskId })
        assertFalse(requests.finish("nope", record))
    }
}

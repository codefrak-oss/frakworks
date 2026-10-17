package com.codefrak.engine.hitlp

import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.lease.EngineLease
import com.codefrak.engine.lease.InstanceId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [HitlpTaskStream] against [StubHitlpServer] (#2463): a terminal `notifications/tasks/status` on the stream answers the
 * task; a task that ended while the stream was down is answered by the reconnect's reconciliation; a server answering
 * the GET 405 gets the fallback `tasks/get` loop; an instance without the lease opens no stream. The engine's own
 * stream bean is stopped first, so only the test's stream (with a fake [HitlpAnswerer]) reads the stub. The last test
 * drives the engine's own stream through the settings (#2500): a `setting` frame named `engine.hitlp` restarts it on
 * a new URL and stops it when the URL is cleared.
 *
 * The tests read a stream's own [HitlpTaskStream.opens] and [HitlpTaskStream.connected], never counts of the stub's
 * GETs: the settings row is in the H2 database every cached test context shares, so another context's engine stream
 * may reconnect to this stub too.
 */
@SpringBootTest
class HitlpTaskStreamTest {
    companion object {
        val stub = StubHitlpServer()
        @JvmStatic @DynamicPropertySource fun hitlp(registry: DynamicPropertyRegistry) = stub.register(registry)
        @JvmStatic @AfterAll fun stop() = stub.stop()
    }

    @Autowired lateinit var filing: HitlpFiling
    @Autowired lateinit var requests: HitlpRequests
    @Autowired lateinit var requester: HitlpRequester
    @Autowired lateinit var tx: TransactionTemplate
    @Autowired lateinit var lease: EngineLease
    @Autowired lateinit var template: JdbcTemplate
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var engineStream: HitlpTaskStream
    @Autowired lateinit var bus: EngineEvents
    @Autowired lateinit var settings: HitlpServerSettings
    @Autowired lateinit var seeder: HitlpStubSeeder

    private val answerer = HitlpPollerTest.FakeAnswerer()
    private val wait = FlowWait("pi-9", "ask", HitlpAsserted.Kind.QUESTION, "codefrak-oss/frakworks", 42, "Which currency?", runId = "run-1")
    private val record = mapOf("requestId" to "x", "idempotencyKey" to "pi-9:ask:1", "primitive" to "ask", "outcome" to "answered", "answer" to "EUR",
        "decidedBy" to mapOf("type" to "human", "id" to "lundmatt1"), "decidedAt" to "2026-10-11T12:00:01Z", "channel" to "desktop")
    private var stream: HitlpTaskStream? = null

    private fun stream(lease: EngineLease = this.lease) =
        HitlpTaskStream(HitlpRequester({ HitlpEndpoint(stub.url, StubHitlpServer.TOKEN) }, Duration.ofSeconds(5)), HitlpPoller(requester, requests, listOf(answerer), tx), lease, bus)
            .apply { backoff = Duration.ofMillis(20); fallbackTick = Duration.ofMillis(20) }
            .also { stream = it }

    private fun eventually(what: String, check: () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < until) { if (check()) return; Thread.sleep(10) }
        fail("not within 5 s: $what")
    }

    // A stream stopped mid-connect (the engine's own, started with the context) still lands its GET on the stub: wait
    // for its thread to end, which closes that GET, before dropping the stub's streams, or a late one spoils the counts.
    @BeforeEach
    fun reset() { engineStream.stop(); engineStream.awaitStopped(); stub.drop(); jdbc.sql("DELETE FROM engine.hitlp_request").update(); stub.reset() }

    @AfterEach
    fun close() { stream?.apply { stop(); awaitStopped() }; engineStream.stop(); engineStream.awaitStopped(); seeder.seed() }

    private fun changed(vararg fields: String) = bus.publish(EngineEvent.Setting(HitlpServerSettings.EVENT_NAME, mapOf("changed" to fields.toList())))

    @Test
    fun `a setting frame named engine-hitlp restarts the engine's stream on a new URL and stops it when the URL is cleared`() {
        assertFalse(engineStream.running)
        val opened = engineStream.opens
        changed("requesterToken")
        eventually("the engine's stream opened on the row's URL") { engineStream.running && engineStream.opens == opened + 1 }

        settings.set(mcpUrl = HitlpServerSettings.Field.Set("${stub.url}?moved"))
        changed("mcpUrl")
        eventually("restarted on the new URL") { engineStream.opens == opened + 2 }
        assertTrue(engineStream.running)
        assertEquals("${stub.url}?moved", requester.url)

        settings.set(mcpUrl = HitlpServerSettings.Field.Set(null))
        changed("mcpUrl")
        eventually("stopped: no server") { !engineStream.running }
        assertFalse(requester.configured)
    }

    @Test
    fun `a terminal notification on the stream answers the task`() {
        val filed = filing.ask(wait)
        val s = stream(); s.sync()
        eventually("the stream is open") { s.connected }
        // The (re)connect's reconcile reads the open task with tasks/get after the stream opens; decided before
        // it, the task would be answered inline by tasks/get, not by the notification.
        eventually("the connect reconciled") { stub.calls("tasks/get").isNotEmpty() }
        stub.notify = true
        stub.decide(filed.handle.taskId, record)
        eventually("the task is answered") { answerer.completed.size == 1 }
        assertEquals(record, requests.get(filed.handle.taskId)!!.record)
        assertTrue(stub.calls("tasks/result").isNotEmpty())
        assertFalse(s.fallingBack)
    }

    @Test
    fun `a reconnect reconciles a task that ended while the stream was down`() {
        val filed = filing.ask(wait)
        val s = stream(); s.sync()
        eventually("the stream is open") { s.connected }
        val opened = s.opens
        stub.drop()
        stub.decide(filed.handle.taskId, record) // no notification: the stream is down
        eventually("reconnected and answered") { s.opens > opened && answerer.completed.size == 1 }
        assertEquals(record, requests.get(filed.handle.taskId)!!.record)
    }

    @Test
    fun `a server answering 405 gets the fallback tasks-get loop`() {
        stub.noStream = true
        val s = stream(); s.sync()
        eventually("falling back") { s.fallingBack }
        val filed = filing.ask(wait)
        stub.decide(filed.handle.taskId, record)
        eventually("the loop answered the task") { answerer.completed.size == 1 }
        assertEquals(0, s.opens)
        assertTrue(stub.calls("tasks/get").isNotEmpty())
    }

    @Test
    fun `an instance without the lease opens no stream, and losing it closes the stream`() {
        val follower = EngineLease(template, InstanceId("follower"), object : ApplicationEventPublisher { override fun publishEvent(event: Any) {} })
        val f = stream(follower); f.sync()
        Thread.sleep(100)
        assertFalse(f.running)
        assertEquals(0, f.opens, "no stream without the lease")

        val s = stream(); s.sync()
        eventually("the stream is open") { s.connected }
        s.stop()
        eventually("the stream is closed") { !s.running }
    }
}

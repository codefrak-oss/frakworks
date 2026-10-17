package com.codefrak.engine.lease

import com.codefrak.engine.jobs.JobTickClock
import com.codefrak.engine.jobs.JobTicker
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two engine instances on one database (engine/README.md, "Replicas and the lease"): exactly one holds the lease, a
 * release hands it over on the peer's next heartbeat, a holder that stops beating loses it after the TTL, and the
 * singleton work guarded by it runs on the holder alone. Plain JUnit on an H2 with V60's table and V78's standby; heartbeats by hand.
 */
class EngineLeaseTest {
    private val template = JdbcTemplate(SingleConnectionDataSource("jdbc:h2:mem:lease-${UUID.randomUUID()}", "sa", "", true)).apply {
        execute("CREATE SCHEMA engine")
        for (v in listOf("V60__engine_lease", "V78__engine_lease_standby")) {
            val sql = javaClass.getResource("/db/migration/$v.sql")!!.readText()
            sql.lines().joinToString("\n") { it.substringBefore("--") }.split(';').filter { it.isNotBlank() }.forEach { execute(it) }
        }
    }

    private class Steps(var now: Instant = Instant.parse("2026-10-08T10:00:00Z")) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }

    private val clock = Steps()

    private class Recorder : ApplicationEventPublisher {
        val events = mutableListOf<Any>()
        override fun publishEvent(event: Any) { events += event }
    }

    private fun instance(name: String, events: Recorder = Recorder()) =
        EngineLease(template, InstanceId(name), events).also { it.clock = clock; it.start() }

    @Test
    fun `exactly one of two instances holds the lease, and a lone engine takes it at once`() {
        val a = Recorder(); val b = Recorder()
        val ea = instance("engine-a", a); val eb = instance("engine-b", b)
        ea.beat(); eb.beat()
        assertTrue(ea.held()); assertFalse(eb.held())
        assertEquals("engine-a", ea.holder())
        assertEquals(listOf<Any>(LeaseAcquired("engine-a")), a.events)
        assertEquals(emptyList<Any>(), b.events)
        clock.now = clock.now.plus(EngineLease.HEARTBEAT)
        ea.beat(); eb.beat()
        assertTrue(ea.held()); assertFalse(eb.held(), "a renewed lease stays with its holder")
    }

    @Test
    fun `a graceful stop releases the lease, and the peer takes it on its next heartbeat`() {
        val a = Recorder(); val b = Recorder()
        val ea = instance("engine-a", a); val eb = instance("engine-b", b)
        ea.beat(); eb.beat()
        ea.stop()
        assertEquals(listOf<Any>(LeaseAcquired("engine-a"), LeaseLost("engine-a")), a.events, "the singleton work stops before the row is freed")
        assertEquals(null, eb.holder())
        eb.beat()
        assertTrue(eb.held())
        assertEquals(listOf<Any>(LeaseAcquired("engine-b")), b.events)
    }

    @Test
    fun `a holder that stops beating loses the lease after the TTL, and learns it on its next heartbeat`() {
        val a = Recorder(); val b = Recorder()
        val ea = instance("engine-a", a); val eb = instance("engine-b", b)
        ea.beat()
        clock.now = clock.now.plus(EngineLease.TTL.minusSeconds(1))
        eb.beat()
        assertFalse(eb.held(), "not before the TTL")
        clock.now = clock.now.plusSeconds(2)
        assertFalse(ea.held(), "past its expiry the old holder runs no singleton work, even before it beats")
        eb.beat()
        assertTrue(eb.held())
        ea.beat()
        assertFalse(ea.held())
        assertEquals(listOf<Any>(LeaseAcquired("engine-a"), LeaseLost("engine-a")), a.events)
    }

    @Test
    fun `a due job is started once, by the holder's ticker clock`() {
        val ea = instance("engine-a"); val eb = instance("engine-b")
        ea.beat(); eb.beat()
        val ticks = AtomicInteger()
        val ticker = object : JobTicker(org.mockito.Mockito.mock(com.codefrak.engine.jobs.JobStore::class.java), org.mockito.Mockito.mock(com.codefrak.engine.jobs.JobStarter::class.java)) {
            override fun tick(now: Instant): List<String> { ticks.incrementAndGet(); return listOf("instance-1") }
        }
        JobTickClock(ticker, ea).tick()
        JobTickClock(ticker, eb).tick()
        assertEquals(1, ticks.get(), "the follower's clock starts nothing")
    }

    @Test
    fun `a standby's heartbeat writes standby and standby_at, and the holder sees it warm until it goes stale`() {
        val ea = instance("engine-a"); val eb = instance("engine-b")
        ea.beat()
        assertEquals(null, ea.warmStandby(), "no standby yet")
        eb.beat()
        assertEquals("engine-b", ea.warmStandby())
        assertEquals(null, eb.warmStandby(), "a replica is never its own standby")
        clock.now = clock.now.plus(EngineLease.STANDBY_FRESH).plusSeconds(1)
        assertEquals(null, ea.warmStandby(), "a standby that stopped beating is not warm")
        ea.stop(); eb.beat()
        assertTrue(eb.held())
        assertEquals(null, template.queryForObject("SELECT standby FROM engine.engine_lease", String::class.java),
            "taking the lease clears this instance's own standby entry")
    }

    @Test
    fun `stop marks shutdown, and a standby retries every second`() {
        val ea = instance("engine-a")
        assertFalse(ea.shuttingDown)
        ea.stop()
        assertTrue(ea.shuttingDown)
        assertEquals(java.time.Duration.ofSeconds(1), EngineLease.RETRY)
        assertTrue(EngineLease.RETRY < EngineLease.HEARTBEAT)
    }
}

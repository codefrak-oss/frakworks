package com.codefrak.engine.lease

import com.codefrak.engine.ReadinessController
import com.codefrak.engine.events.NoopEventRelay
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A rollout's handover (engine/README.md, "Replicas and the lease"): two engine contexts, the old holder and the surge
 * pod, each with its own lease, readiness and preStop, on one database and the real clock with their own heartbeat
 * threads. Only the holder is ready; the preStop returns once the standby is warm; the peer holds within 2 s of the
 * stop and its /ready turns 200. H2 with V60 and V78, a connection per call so the threads do not share one.
 */
class LeaseHandoverTest {
    private val template = JdbcTemplate(DriverManagerDataSource("jdbc:h2:mem:handover-${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")).apply {
        execute("CREATE SCHEMA engine")
        for (v in listOf("V60__engine_lease", "V78__engine_lease_standby")) {
            val sql = javaClass.getResource("/db/migration/$v.sql")!!.readText()
            sql.lines().joinToString("\n") { it.substringBefore("--") }.split(';').filter { it.isNotBlank() }.forEach { execute(it) }
        }
    }

    private class Context(template: JdbcTemplate, name: String) {
        val lease = EngineLease(template, InstanceId(name), ApplicationEventPublisher { }, retry = Duration.ofMillis(100)).also { it.start(); it.atStartup() }
        val readiness = ReadinessController(template, lease, NoopEventRelay())
        val preStop = AwaitStandbyController(lease)
    }

    private val contexts = mutableListOf<Context>()

    private fun context(name: String) = Context(template, name).also { contexts += it }

    @AfterEach fun stop() = contexts.forEach { it.lease.stop() }

    @Test
    fun `the preStop waits for a warm standby, the peer takes the lease within 2 s and turns ready`() {
        val old = context("engine-old")
        assertTrue(old.lease.held())
        assertEquals(HttpStatus.OK, old.readiness.ready().statusCode)

        val surge = context("engine-surge")
        val waiting = surge.readiness.ready()
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, waiting.statusCode, "the standby takes no traffic")
        assertEquals("lease held by engine-old", waiting.body!!.reason)

        val warm = old.preStop.await(cap = Duration.ofSeconds(10))
        assertTrue(warm.warm, "the preStop returned because the standby was warm, not at the cap")
        assertEquals("engine-surge", warm.standby)
        val stopping = old.readiness.ready()
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, stopping.statusCode, "503 from the start of shutdown")
        assertEquals("shutting down", stopping.body!!.reason)

        val stoppedAt = System.nanoTime()
        old.lease.stop()
        while (!surge.lease.held() && System.nanoTime() - stoppedAt < Duration.ofSeconds(3).toNanos()) Thread.sleep(50)
        val took = Duration.ofNanos(System.nanoTime() - stoppedAt)
        assertTrue(surge.lease.held(), "the peer holds the lease")
        assertTrue(took <= Duration.ofSeconds(2), "the peer holds within 2 s, took $took")
        assertEquals(HttpStatus.OK, surge.readiness.ready().statusCode)
        assertFalse(old.lease.held())
    }

    @Test
    fun `with no standby the preStop returns at its cap`() {
        val lone = context("engine-lone")
        val r = lone.preStop.await(cap = Duration.ofMillis(600), poll = Duration.ofMillis(100))
        assertFalse(r.warm)
        assertTrue(r.waitedMs >= 600)
        assertEquals(60, AwaitStandbyController.CAP.seconds, "the cap fits terminationGracePeriodSeconds: 90")
    }
}

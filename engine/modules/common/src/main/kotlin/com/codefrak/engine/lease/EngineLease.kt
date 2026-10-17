package com.codefrak.engine.lease

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * This engine instance's name (engine/README.md, "Replicas and the lease"): the lease's holder, the telemetry `host`
 * label, the event relay's origin. The hostname: the pod name on Kubernetes, the container's `hostname:` in the
 * Compose stack, the machine's name for an engine run by hand. One source everywhere, not a branch on the hosting model.
 */
data class InstanceId(val name: String) {
    companion object {
        fun ofHost(env: Map<String, String> = System.getenv()): InstanceId = InstanceId(
            env["HOSTNAME"]?.trim()?.takeIf { it.isNotEmpty() } ?: InetAddress.getLocalHost().hostName,
        )
    }
}

@Configuration
class InstanceIdConfig {
    @Bean fun instanceId(): InstanceId = InstanceId.ofHost()
}

/** This instance took the lease: start the singleton work (resume live runs, start the job executor). */
data class LeaseAcquired(val holder: String)

/** This instance no longer holds the lease (lost it, or released it on shutdown): stop the singleton work. */
data class LeaseLost(val holder: String)

/**
 * The engine's leader lease (engine/README.md, "Replicas and the lease"): the one `engine.engine_lease` row. Every
 * replica serves HTTP; the one holding the row runs the singleton work: the schedulers, the job ticker, the Operaton
 * job executor (so plugin flow jobs run where their jars are loaded), the Smith sweep and exit scans, and following
 * the live Smith runs for their transcripts. A lone engine always takes it at startup.
 *
 * The engine runs as one replica; a rollout's surge pod is the standby until the old pod stops, and only the holder is
 * ready, so traffic follows the lease. A non-holder writes itself into the row's `standby` and `standby_at` on each
 * attempt, and attempts every [RETRY] rather than every [HEARTBEAT], so it takes a released row within [RETRY]. The
 * holder's preStop waits for a warm standby ([warmStandby], `GET /lease/await-standby`) and marks [shuttingDown].
 *
 * Taken and renewed with one conditional UPDATE every [HEARTBEAT]: it succeeds when the row is free, expired, or
 * already this instance's, and then holds it [TTL] on. A crashed holder's row expires after [TTL]; a graceful stop
 * releases it ([stop]), so the peer takes it on its next heartbeat. A renewal that fails (the database unreachable)
 * keeps the lease only while its last expiry is more than a heartbeat away, so this instance gives it up before a
 * peer can take it. Acquisition and loss are published as [LeaseAcquired] and [LeaseLost], synchronously.
 *
 * Times are this instance's clock: the replicas' nodes keep NTP time, well inside the 15 s TTL.
 */
@Component
class EngineLease(
    private val template: JdbcTemplate,
    val instance: InstanceId,
    private val events: ApplicationEventPublisher,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(EngineLease::class.java)

    /** The clock the expiries are read and written by; a test moves it. */
    var clock: Clock = Clock.systemUTC()

    /** Whether this instance took the lease and has not published its loss; [held] also needs [heldUntil] ahead. */
    @Volatile private var holding = false
    @Volatile private var heldUntil: Instant = Instant.EPOCH
    @Volatile private var running = false
    private var heartbeat: ScheduledExecutorService? = null

    /** Set from the start of shutdown (the preStop, or [stop]): `/ready` answers 503 from then on. */
    val shuttingDown: Boolean get() = shutdown

    @Volatile private var shutdown = false

    /** Marks the start of shutdown: this replica takes no more traffic. */
    fun shutdownStarted() { shutdown = true }

    /** The standby another replica wrote within [STANDBY_FRESH], null when there is none. */
    fun warmStandby(): String? = template.query(
        "SELECT standby, standby_at FROM engine.engine_lease WHERE name = ?", { rs, _ ->
            val at = rs.getObject(2, OffsetDateTime::class.java)?.toInstant()
            rs.getString(1)?.takeIf { it != instance.name && at != null && at.isAfter(clock.instant().minus(STANDBY_FRESH)) }
        }, NAME,
    ).firstOrNull()

    /** Whether this instance holds the lease now: the singleton work's guard. */
    fun held(): Boolean = holding && clock.instant().isBefore(heldUntil)

    /** The holder the row names while unexpired, null when free. */
    fun holder(): String? = template.query(
        "SELECT holder, expires_at FROM engine.engine_lease WHERE name = ?", { rs, _ ->
            rs.getString(1)?.takeIf { rs.getObject(2, OffsetDateTime::class.java).toInstant().isAfter(clock.instant()) }
        }, NAME,
    ).firstOrNull()

    /** The first attempt, before the other startup listeners run, so a lone engine's startup work runs at once as before. */
    @EventListener(ApplicationReadyEvent::class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun atStartup() {
        beat()
        synchronized(this) {
            if (!running || heartbeat != null) return
            heartbeat = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "engine-lease").apply { isDaemon = true } }
                .also { schedule(it) }
        }
    }

    /** The next attempt: a holder renews every [HEARTBEAT], a standby retries every [RETRY]. */
    private fun schedule(executor: ScheduledExecutorService) {
        val delay = if (holding) HEARTBEAT else RETRY
        runCatching {
            executor.schedule({ beat(); synchronized(this) { if (running && heartbeat === executor) schedule(executor) } },
                delay.toMillis(), TimeUnit.MILLISECONDS)
        }
    }

    /** One heartbeat: take or renew the row, and publish a change. */
    @Synchronized fun beat() {
        if (!running) return
        val now = clock.instant()
        val took = runCatching { take(now) }.onFailure {
            log.warn("lease: cannot renew as {}: {}", instance.name, it.message)
        }.getOrNull()
        when {
            took == true -> {
                heldUntil = now.plus(TTL)
                if (!holding) {
                    holding = true
                    log.info("lease: {} holds the engine lease; running the singleton work", instance.name)
                    events.publishEvent(LeaseAcquired(instance.name))
                }
            }
            took == false -> {
                runCatching { standby(now) }.onFailure { log.warn("lease: cannot write standby {}: {}", instance.name, it.message) }
                if (holding) lose("taken by ${runCatching { holder() }.getOrNull() ?: "another instance"}")
            }
            took == null && holding && !now.plus(HEARTBEAT).isBefore(heldUntil) -> lose("not renewed in time")
        }
    }

    private fun take(now: Instant): Boolean = template.update(
        """UPDATE engine.engine_lease SET holder = ?, expires_at = ?, heartbeat_at = ?,
                  standby = CASE WHEN standby = ? THEN NULL ELSE standby END
           WHERE name = ? AND (holder = ? OR holder IS NULL OR expires_at < ?)""",
        instance.name, at(now.plus(TTL)), at(now), instance.name, NAME, instance.name, at(now),
    ) == 1

    /** A standby's heartbeat: the holder's preStop reads it as a warm peer. */
    private fun standby(now: Instant) {
        template.update("UPDATE engine.engine_lease SET standby = ?, standby_at = ? WHERE name = ?", instance.name, at(now), NAME)
    }

    private fun lose(why: String) {
        holding = false
        log.warn("lease: {} lost the engine lease ({}); the singleton work stops", instance.name, why)
        events.publishEvent(LeaseLost(instance.name))
    }

    override fun start() { running = true }

    override fun isRunning() = running

    /** Stops first ([getPhase]): the singleton work stops, then the row is freed, so a peer takes it on its next heartbeat. */
    @Synchronized override fun stop() {
        running = false
        shutdown = true
        heartbeat?.shutdownNow()
        heartbeat = null
        if (!holding) return
        holding = false
        runCatching { events.publishEvent(LeaseLost(instance.name)) }.onFailure { log.warn("lease: stopping the singleton work failed: {}", it.message) }
        runCatching {
            template.update("UPDATE engine.engine_lease SET holder = NULL, expires_at = ? WHERE name = ? AND holder = ?",
                at(clock.instant()), NAME, instance.name)
        }.onSuccess { log.info("lease: {} released the engine lease", instance.name) }
            .onFailure { log.warn("lease: not released ({}); it expires within {}", it.message, TTL) }
    }

    override fun getPhase() = Int.MAX_VALUE

    private fun at(t: Instant) = OffsetDateTime.ofInstant(t, ZoneOffset.UTC)

    companion object {
        const val NAME = "engine"
        val TTL: Duration = Duration.ofSeconds(15)
        val HEARTBEAT: Duration = Duration.ofSeconds(5)
        /** A non-holder's attempt interval, so a released row is taken within it. */
        val RETRY: Duration = Duration.ofSeconds(1)
        /** A standby written within this long is warm. */
        val STANDBY_FRESH: Duration = Duration.ofSeconds(3)
    }
}

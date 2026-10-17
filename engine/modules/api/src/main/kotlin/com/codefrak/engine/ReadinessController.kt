package com.codefrak.engine

import com.codefrak.engine.events.EventRelay
import com.codefrak.engine.lease.EngineLease
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** `GET /ready`'s body: whether this instance takes traffic, each check, who holds the lease, and why not when not. */
data class Readiness(val ready: Boolean, val database: Boolean, val lease: Boolean, val relay: Boolean, val instance: String,
                     val holder: String?, val leader: Boolean, val shuttingDown: Boolean = false, val reason: String? = null)

/**
 * Unauthenticated: whether this replica may take traffic (the Kubernetes readiness probe, deploy/k8s/base/engine.yaml;
 * engine/README.md, "Replicas and the lease"). Ready when the database answers (Flyway ran before the context could
 * start), the lease is running, the event relay is listening, and this replica holds the lease: the engine runs as one
 * replica, and during a rollout only the holder takes traffic, never the standby. 503 otherwise, with a `reason`
 * naming the holder (`lease held by <holder>`), and 503 from the start of shutdown (the preStop) on.
 */
@RestController
class ReadinessController(private val template: JdbcTemplate, private val lease: EngineLease, private val relay: EventRelay) {
    @GetMapping("/ready")
    fun ready(): ResponseEntity<Readiness> {
        val database = runCatching { template.queryForObject("SELECT 1", Int::class.java) == 1 }.getOrDefault(false)
        val holder = if (database) runCatching { lease.holder() }.getOrNull() else null
        val leader = lease.held()
        val shutting = lease.shuttingDown
        val reason = when {
            shutting -> "shutting down"
            !database -> "database not answering"
            !lease.isRunning() -> "lease not running"
            !relay.listening() -> "event relay not listening"
            !leader -> "lease held by ${holder ?: "nobody yet"}"
            else -> null
        }
        val r = Readiness(reason == null, database, lease.isRunning(), relay.listening(), lease.instance.name, holder, leader, shutting, reason)
        return ResponseEntity.status(if (r.ready) HttpStatus.OK else HttpStatus.SERVICE_UNAVAILABLE).body(r)
    }
}

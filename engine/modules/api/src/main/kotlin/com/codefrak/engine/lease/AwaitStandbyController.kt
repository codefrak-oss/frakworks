package com.codefrak.engine.lease

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.net.InetAddress
import java.time.Duration

/** `GET /lease/await-standby`'s body: whether a warm standby was there, which, and after how long. */
data class AwaitStandby(val warm: Boolean, val standby: String?, val waitedMs: Long)

/**
 * The engine's preStop (deploy/k8s/base/engine.yaml; engine/README.md, "Replicas and the lease"): loopback only, no
 * token. It marks the start of shutdown, so `/ready` answers 503 at once, then blocks until another replica has written
 * itself into the lease row as a warm standby, or [CAP] (60 s, inside the pod's 90 s terminationGracePeriodSeconds)
 * has passed, logged as a warning. Then the pod gets SIGTERM, the lease is released, and the standby takes it within
 * [EngineLease.RETRY]. A lone engine with no standby (a node drain, `kubectl delete pod`) waits out the cap.
 */
@RestController
class AwaitStandbyController(private val lease: EngineLease) {
    private val log = LoggerFactory.getLogger(AwaitStandbyController::class.java)

    @GetMapping("/lease/await-standby")
    fun awaitStandby(request: HttpServletRequest): ResponseEntity<Any> {
        if (!runCatching { InetAddress.getByName(request.remoteAddr).isLoopbackAddress }.getOrDefault(false))
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "loopback only"))
        return ResponseEntity.ok(await())
    }

    /** Marks shutdown and waits for a warm standby, polling every [poll], at most [cap]. */
    fun await(cap: Duration = CAP, poll: Duration = POLL): AwaitStandby {
        lease.shutdownStarted()
        val start = System.nanoTime()
        val deadline = start + cap.toNanos()
        while (true) {
            val standby = runCatching { lease.warmStandby() }.getOrNull()
            val waited = Duration.ofNanos(System.nanoTime() - start).toMillis()
            if (standby != null) {
                log.info("preStop: warm standby {} after {} ms; {} stops", standby, waited, lease.instance.name)
                return AwaitStandby(true, standby, waited)
            }
            if (System.nanoTime() >= deadline) {
                log.warn("preStop: no warm standby within {} s; {} stops anyway", cap.seconds, lease.instance.name)
                return AwaitStandby(false, null, waited)
            }
            Thread.sleep(poll.toMillis())
        }
    }

    companion object {
        val CAP: Duration = Duration.ofSeconds(60)
        val POLL: Duration = Duration.ofMillis(250)
    }
}

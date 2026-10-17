package com.codefrak.engine.smith

import com.codefrak.engine.lease.EngineLease
import org.operaton.bpm.engine.RuntimeService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/** A `smith-*` container as the sweep sees it; `created` is when Docker created it. */
data class SmithContainer(val id: String, val name: String, val running: Boolean, val created: Instant)

/**
 * The run-id prefix in a container name: [DockerSandbox.start] names it
 * `smith-<issue>-<runId.take(8)>`, and a run id is its smith-run instance's id.
 */
fun runIdPrefix(name: String): String? =
    Regex("""^smith-\d+-([0-9a-f-]{8})$""").matchEntire(name)?.groupValues?.get(1)

/**
 * What to do with each container whose run is not an active smith-run: an exited
 * one is removed, a running one older than [grace] is stopped and removed, anything
 * else (or a name the engine did not make) is left alone. Returns container to reason.
 */
fun orphans(containers: List<SmithContainer>, activeRuns: Collection<String>, now: Instant,
                     grace: Duration = Duration.ofMinutes(5)): Map<SmithContainer, String> =
    containers.mapNotNull { c ->
        val prefix = runIdPrefix(c.name) ?: return@mapNotNull null
        if (activeRuns.any { it.startsWith(prefix) }) return@mapNotNull null
        when {
            !c.running -> c to "exited, no active run $prefix"
            c.created.isBefore(now.minus(grace)) -> c to "running over ${grace.toMinutes()} min, no active run $prefix"
            else -> null
        }
    }.toMap()

/**
 * The belt to cancel's braces (engine/README.md, "Smith runs"): every 10 minutes,
 * a sandbox no active smith-run owns is removed, or stopped and removed: Docker's
 * containers, and the Kubernetes model's Jobs while a cluster is set up. In a pod there is
 * no Docker ([InCluster]): only the Jobs, since a missing socket would end the sweep before them.
 */
@Component
@ConditionalOnProperty("engine.smith.sandbox", havingValue = "docker", matchIfMissing = true)
class SmithSweep(
    private val docker: DockerSandbox, private val kubernetes: KubernetesSandbox, private val runtime: RuntimeService,
    private val inCluster: InCluster,
    private val lease: EngineLease,
) {
    private val log = LoggerFactory.getLogger(SmithSweep::class.java)

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT10M")
    fun sweep() {
        if (!lease.held()) return
        val active = runtime.createProcessInstanceQuery().processDefinitionKey("smith-run").list().map { it.id }
        if (!inCluster.inPod()) orphans(docker.containers(), active, Instant.now()).forEach { (c, reason) ->
            if (c.running) docker.stop(c.id) else docker.remove(c.id)
            log.info("smith sweep: {} {}: {}", if (c.running) "stopped and removed" else "removed", c.name, reason)
        }
        val jobs = runCatching { kubernetes.jobs() }
            .getOrElse { log.warn("smith sweep: could not list kubernetes jobs: {}", it.message); emptyList() }
        orphans(jobs, active, Instant.now()).forEach { (c, reason) ->
            kubernetes.stop(c.id)
            log.info("smith sweep: deleted job {}: {}", c.name, reason)
        }
    }
}

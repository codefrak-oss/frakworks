package com.codefrak.engine.smith

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.model.Capability
import com.github.dockerjava.api.model.Event
import com.github.dockerjava.api.model.HostConfig

/**
 * A `die` event as a [SandboxExit], or null when it names no container. The
 * container id is `Actor.ID`: the top-level `id` (with `status` and `from`)
 * is a legacy field the daemon only writes for old API versions, and
 * docker-java sends no version, so on a current daemon it is absent. Reading
 * `event.id` alone silently dropped every exit and left every run to the
 * 3-minute backstop (seen 2026-09-25, the afternoon #254 landed).
 */
fun exitOf(event: Event): SandboxExit? {
    val attributes = event.actor?.attributes ?: emptyMap()
    return SandboxExit(
        handle = event.actor?.id ?: event.id ?: return null,
        runId = attributes[RUN_LABEL],
        exitCode = attributes["exitCode"]?.toLongOrNull() ?: -1L,
    )
}

/**
 * The Smith whose running container has [address] on [network] (any network when
 * [network] is blank), from its labels; null if none has.
 */
fun callerAt(sandboxes: List<SandboxAddresses>, network: String, address: String): SmithCaller? {
    val ip = callerIp(address) ?: return null
    val s = sandboxes.firstOrNull { s ->
        (if (network.isBlank()) s.ips.values else listOfNotNull(s.ips[network])).any { it == ip }
    } ?: return null
    return SmithCaller.of(s.labels) ?: return null
}

/** A running sandbox as [callerAt] needs it: its labels, and its IP on each network by network name. */
data class SandboxAddresses(val labels: Map<String, String>, val ips: Map<String, String?>)

/**
 * The [HostConfig] for a Smith sandbox: capped at [memoryBytes] and biased to
 * die first under memory pressure (`oomScoreAdj = 500`, the top of the kernel's
 * -1000..1000 range that raises a process's OOM badness) -- so a squeeze on
 * the one-VM stack kills a Smith before the engine or its database, which set
 * `oom_score_adj: -500` in compose.local.yaml. A top-level function (like
 * [reclaim]) so a test can assert on the built [HostConfig] without a live
 * daemon. [cpus] caps its CPU (`NanoCpus`) so concurrent Smiths cannot starve
 * the engine, as they did for 81 s on 2026-09-26.
 */
fun sandboxHostConfig(memoryBytes: Long, cpus: Double): HostConfig =
    HostConfig.newHostConfig()
        .withMemory(memoryBytes)
        .withNanoCPUs((cpus * 1_000_000_000).toLong())
        .withOomScoreAdj(500)
        .withCapDrop(Capability.ALL)
        .withSecurityOpts(listOf("no-new-privileges:true"))
        .withAutoRemove(false)

/**
 * The container name is derived from the run id (`spec.runId.take(8)` in
 * [DockerSandbox.start]), so a pre-existing container of that name is never
 * another run's -- it is this run's own sandbox from an attempt the engine
 * did not finish (engine/README.md, "Smith runs"): a restart between create
 * and start left it in Docker state `Created`, or it died after `start`
 * returned but before the job committed. A **running** one means that
 * earlier attempt actually made it: adopted here rather than failed, since
 * creating a second container under the same name would just 409. A
 * **non-running** one (`Created`, `Exited`) is the create-without-start
 * orphan: removed, so the caller's normal create/start path runs fresh.
 * A top-level function (not a `DockerSandbox` method) so tests can drive it
 * against a fake [DockerClient] without a live daemon.
 */
fun reclaim(docker: DockerClient, name: String, log: org.slf4j.Logger): String? {
    val existing = docker.listContainersCmd().withShowAll(true).withNameFilter(listOf(name)).exec()
        .firstOrNull { c -> c.names.any { it.removePrefix("/") == name } } ?: return null
    if (existing.state == "running") {
        log.info("smith sandbox adopted: container={} name={} already running from an earlier attempt", existing.id.take(12), name)
        return existing.id
    }
    log.info("smith sandbox orphan: container={} name={} state={} -- removing and starting fresh", existing.id.take(12), name, existing.state)
    docker.removeContainerCmd(existing.id).withForce(true).exec()
    return null
}

/**
 * Puts [inputs] in a created container's [INPUT_DIR] (`<work>/in`, [work] being `/work` for a Smith), with
 * [modes]: `/work` is the smith user's, and the tar makes `in/` and the files as that user.
 */
fun copyInputs(docker: DockerClient, container: String, work: String, inputs: Map<String, ByteArray>, modes: Map<String, Int>) {
    docker.copyArchiveToContainerCmd(container).withRemotePath(work)
        .withTarInputStream(inputsTar(inputs, modes).inputStream()).exec()
}

/** [SmithSandbox.outputDir] through Docker: the directory's tar, read no further than one byte past [maxBytes]. */
fun readDir(docker: DockerClient, container: String, path: String, maxBytes: Long): ByteArray? {
    val tar = try {
        docker.copyArchiveFromContainerCmd(container, path).exec().use { it.readNBytes(Math.toIntExact(maxBytes) + 1) }
    } catch (e: com.github.dockerjava.api.exception.NotFoundException) { return null }
    if (tar.size > maxBytes) throw OutputTooLarge(path, maxBytes)
    return tar
}

package com.codefrak.engine.smith

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * The repro carry through real Docker (engine/README.md, "Bug"): a file one container writes to
 * /work/out/repro/x.sh, mode 0755, read out with [readDir] and put in with [copyInputs] as
 * `DockerSandbox.start` does (under /tmp, which busybox has), runs as in/repro/x.sh in the next. Skipped without a Docker socket
 * (the engine's image build has none) or without the busybox image, which it does not pull.
 */
class DockerReproCarryTest {
    private fun docker(): DockerClient? {
        if (!File("/var/run/docker.sock").exists()) return null
        val config = DefaultDockerClientConfig.createDefaultConfigBuilder().withDockerHost("unix:///var/run/docker.sock").build()
        val http = ZerodepDockerHttpClient.Builder().dockerHost(config.dockerHost).responseTimeout(Duration.ofSeconds(60)).build()
        return runCatching { DockerClientImpl.getInstance(config, http).also { it.pingCmd().exec() } }.getOrNull()
    }

    private fun run(docker: DockerClient, script: String, inputs: Map<String, ByteArray> = emptyMap(), modes: Map<String, Int> = emptyMap()): Pair<String, Long> {
        val id = docker.createContainerCmd(IMAGE).withCmd("sh", "-c", script).exec().id
        if (inputs.isNotEmpty()) copyInputs(docker, id, WORK, inputs, modes)
        docker.startContainerCmd(id).exec()
        val exit = docker.waitContainerCmd(id).start().awaitStatusCode(60, TimeUnit.SECONDS).toLong()
        return id to exit
    }

    @Test
    fun `a 0755 file under work-out-repro in one container runs from work-in-repro in the next`() {
        val docker = docker()
        assumeTrue(docker != null, "no Docker socket")
        assumeTrue(runCatching { docker!!.inspectImageCmd(IMAGE).exec() }.isSuccess, "no $IMAGE image here")
        val (first, exit) = run(docker!!, "mkdir -p /work/out/repro && printf '#!/bin/sh\\necho carried\\n' > /work/out/repro/x.sh && chmod 0755 /work/out/repro/x.sh")
        val second = try {
            assertEquals(0L, exit)
            val tar = readDir(docker, first, REPRO_DIR, REPRO_MAX_BYTES)!!
            val files = tarFiles(tar).mapKeys { "$REPRO_INPUT/${it.key.removePrefix("repro/")}" }
            assertEquals(setOf("repro/x.sh"), files.keys)
            run(docker, "test -x $WORK/in/repro/x.sh && [ \"\$($WORK/in/repro/x.sh)\" = carried ]",
                files.mapValues { it.value.data }, files.mapValues { it.value.mode })
        } finally { docker.removeContainerCmd(first).withForce(true).exec() }
        try {
            assertEquals(0L, second.second, "x.sh arrived executable at /work/in/repro")
        } finally { docker.removeContainerCmd(second.first).withForce(true).exec() }
    }

    @Test
    fun `an absent directory reads as none, and one past the cap throws`() {
        val docker = docker()
        assumeTrue(docker != null, "no Docker socket")
        assumeTrue(runCatching { docker!!.inspectImageCmd(IMAGE).exec() }.isSuccess, "no $IMAGE image here")
        val (id, _) = run(docker!!, "mkdir -p /work/out/repro && head -c 4096 /dev/zero > /work/out/repro/big")
        try {
            assertEquals(null, readDir(docker, id, "/work/out/none", REPRO_MAX_BYTES))
            kotlin.test.assertFailsWith<OutputTooLarge> { readDir(docker, id, REPRO_DIR, 1024) }
        } finally { docker.removeContainerCmd(id).withForce(true).exec() }
    }

    private companion object { const val IMAGE = "busybox:latest"
        /** busybox has no /work to copy into before the start; /tmp stands in for it, `in/` the same. */
        const val WORK = "/tmp" }
}

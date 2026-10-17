package com.codefrak.engine.smith

import com.github.dockerjava.api.DockerClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Make sure [image] is there to create a sandbox from (engine/README.md,
 * "Smith runs"). Missing locally: pulled, and a failed or timed-out pull
 * throws, naming the image and the cause, so the start task fails into an
 * incident within [pullTimeout] rather than hanging. Present under a mutable
 * tag on a registry (`registry.example:5000/frakworks-smith:main`): re-pulled
 * when the last pull of it is [refreshInterval] or more old (never, since
 * the engine started, counts), and a failed refresh is a warning -- the run
 * goes on with the local copy. Pinned references (`:sha-<7>`, `@sha256:`)
 * and registry-less names (`frakworks-smith:local`) are never refreshed.
 * [lastRefresh] records attempts, so a registry that is down costs one
 * attempt per interval, not one per run. A pull never touches a running
 * sandbox: a container keeps the image id it was created from. A top-level
 * function (like [reclaim]) so a test can drive it against a mocked
 * [DockerClient]; [pullDocker] is the client without a response timeout.
 */
fun ensureImage(
    docker: DockerClient, image: String, refreshInterval: Duration, pullTimeout: Duration,
    lastRefresh: MutableMap<String, Instant>, clock: Clock, log: org.slf4j.Logger, pullDocker: DockerClient = docker,
) {
    val present = try {
        docker.inspectImageCmd(image).exec(); true
    } catch (e: com.github.dockerjava.api.exception.NotFoundException) { false }
    val now = clock.instant()
    if (!present) {
        log.info("smith image {} not present locally, pulling", image)
        lastRefresh[image] = now
        pullImage(pullDocker, image, pullTimeout)
        log.info("smith image {} pulled", image)
        return
    }
    if (!refreshable(image)) return
    val last = lastRefresh[image]
    if (last != null && Duration.between(last, now) < refreshInterval) return
    lastRefresh[image] = now
    try {
        pullImage(pullDocker, image, pullTimeout)
        log.info("smith image {} refreshed", image)
    } catch (e: Exception) {
        log.warn("smith image {} refresh failed, using the local copy: {}", image, e.message)
    }
}

/** Pull [image], waiting at most [timeout]; throws with the image and the cause on failure or timeout. */
fun pullImage(docker: DockerClient, image: String, timeout: Duration) {
    val cmd = if ('@' in image) docker.pullImageCmd(image)
    else splitImage(image).let { (repo, tag) -> docker.pullImageCmd(repo).withTag(tag) }
    // The callback's own timed awaitCompletion also checks the stream's last
    // item, so a pull cut off mid-download would read as "Could not pull image:
    // Downloading"; a latch on close (onComplete and onError both close) tells
    // a timeout apart from a failure.
    val done = java.util.concurrent.CountDownLatch(1)
    val callback = object : com.github.dockerjava.api.command.PullImageResultCallback() {
        override fun close() = try { super.close() } finally { done.countDown() }
    }
    fun failed(cause: String, e: Throwable? = null) = IllegalStateException("smith image $image could not be pulled: $cause", e)
    fun failedBy(e: Exception): IllegalStateException {
        if (e is InterruptedException) Thread.currentThread().interrupt()
        return failed(e.message ?: e.javaClass.simpleName, e)
    }
    val finished = try {
        cmd.exec(callback)
        done.await(timeout.toMillis(), TimeUnit.MILLISECONDS)
    } catch (e: Exception) { throw failedBy(e) }
    if (!finished) {
        // Closing the callback closes the pull's stream, so nothing keeps waiting on the registry.
        runCatching { callback.close() }
        throw failed("no answer from the registry within $timeout")
    }
    // Returns at once; throws the pull's error (a failed request or the stream's error item), if any.
    try { callback.awaitCompletion() } catch (e: Exception) { throw failedBy(e) }
}

/**
 * The digest of the image container [containerId] was created from: the
 * RepoDigest of [image]'s repo (`registry.example:5000/frakworks-smith@sha256:…`),
 * else the image id (a locally built image has no RepoDigest); null, never a
 * throw, when Docker cannot say. What the "smith sandbox started" line logs.
 */
fun imageDigest(docker: DockerClient, containerId: String, image: String): String? = runCatching {
    val id = docker.inspectContainerCmd(containerId).exec().imageId ?: return@runCatching null
    val repo = if ('@' in image) image.substringBefore('@') else splitImage(image).first
    val digests = runCatching { docker.inspectImageCmd(id).exec().repoDigests }.getOrNull() ?: emptyList()
    digests.firstOrNull { it.substringBefore('@') == repo } ?: id
}.getOrNull()

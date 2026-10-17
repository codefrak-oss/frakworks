package com.codefrak.engine.smith

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.InspectContainerCmd
import com.github.dockerjava.api.command.InspectContainerResponse
import com.github.dockerjava.api.command.InspectImageCmd
import com.github.dockerjava.api.command.InspectImageResponse
import com.github.dockerjava.api.command.PullImageCmd
import com.github.dockerjava.api.command.PullImageResultCallback
import com.github.dockerjava.api.exception.NotFoundException
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `ensureImage`, `pullImage` and `imageDigest` (SmithImage.kt) against a
 * mocked [DockerClient], like DockerSandboxReclaimTest: pull when missing,
 * refresh a mutable tag once per interval, fail within a bound naming the
 * image and the cause (engine/README.md, "Smith runs").
 */
class SmithImageTest {
    private val log = LoggerFactory.getLogger(SmithImageTest::class.java)
    private val image = "registry.example:5000/frakworks-smith:main"
    private val repo = "registry.example:5000/frakworks-smith"
    private val t0 = Instant.parse("2026-09-26T12:00:00Z")
    private val fiveMin = Duration.ofMinutes(5)

    private class Fake(val docker: DockerClient, val pull: PullImageCmd)

    /** A daemon that has [image] locally or not, and whose pull of [pullRepo] behaves as [pullDoes]. */
    private fun docker(present: Boolean, pullRepo: String = repo, pullDoes: (PullImageResultCallback) -> PullImageResultCallback = { cb -> cb.onComplete(); cb }): Fake {
        val docker = mock(DockerClient::class.java)
        val inspect = mock(InspectImageCmd::class.java)
        `when`(docker.inspectImageCmd(anyString())).thenReturn(inspect)
        if (present) `when`(inspect.exec()).thenReturn(mock(InspectImageResponse::class.java))
        else `when`(inspect.exec()).thenThrow(NotFoundException("No such image"))
        val pull = mock(PullImageCmd::class.java)
        `when`(docker.pullImageCmd(pullRepo)).thenReturn(pull)
        `when`(pull.withTag(anyString())).thenReturn(pull)
        `when`(pull.exec(any(PullImageResultCallback::class.java))).thenAnswer { pullDoes(it.getArgument(0)) }
        return Fake(docker, pull)
    }

    private fun clock(at: Instant) = Clock.fixed(at, ZoneOffset.UTC)

    @Test
    fun `a missing image whose pull fails throws naming the image and the cause`() {
        val fake = docker(present = false) { cb -> cb.onError(RuntimeException("manifest unknown")); cb }

        val e = assertFailsWith<IllegalStateException> {
            ensureImage(fake.docker, image, fiveMin, fiveMin, HashMap(), clock(t0), log)
        }

        assertTrue(image in e.message!!, e.message)
        assertTrue("manifest unknown" in e.message!!, e.message)
        verify(fake.pull).withTag("main")
        verify(fake.docker, never()).createContainerCmd(anyString())
    }

    @Test
    fun `a pull that does not complete in time throws a timeout naming the image`() {
        val fake = docker(present = false) { cb -> cb } // never completes

        val started = System.nanoTime()
        val e = assertFailsWith<IllegalStateException> {
            ensureImage(fake.docker, image, fiveMin, Duration.ofMillis(100), HashMap(), clock(t0), log)
        }

        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(10))
        assertTrue(image in e.message!!, e.message)
        assertTrue("within" in e.message!!, e.message)
    }

    @Test
    fun `a missing image is pulled and the start goes ahead`() {
        val fake = docker(present = false)

        ensureImage(fake.docker, image, fiveMin, fiveMin, HashMap(), clock(t0), log)

        verify(fake.pull).exec(any(PullImageResultCallback::class.java))
    }

    @Test
    fun `a present pinned image is never pulled`() {
        for (pinned in listOf("registry.example:5000/frakworks-smith:sha-1234abc", "registry.example:5000/frakworks-smith@sha256:abc", "frakworks-smith:local")) {
            val fake = docker(present = true)
            ensureImage(fake.docker, pinned, Duration.ZERO, fiveMin, HashMap(), clock(t0), log)
            verify(fake.docker, never()).pullImageCmd(anyString())
        }
    }

    @Test
    fun `a present main is refreshed at most once per interval`() {
        val fake = docker(present = true)
        val last = HashMap<String, Instant>()

        ensureImage(fake.docker, image, fiveMin, fiveMin, last, clock(t0), log)          // never pulled since start: refresh
        ensureImage(fake.docker, image, fiveMin, fiveMin, last, clock(t0.plusSeconds(60)), log) // inside the interval: none
        verify(fake.pull, times(1)).exec(any(PullImageResultCallback::class.java))

        ensureImage(fake.docker, image, fiveMin, fiveMin, last, clock(t0.plus(fiveMin)), log)    // interval passed: refresh
        verify(fake.pull, times(2)).exec(any(PullImageResultCallback::class.java))
    }

    @Test
    fun `PT0S refreshes a present main every run`() {
        val fake = docker(present = true)
        val last = HashMap<String, Instant>()

        repeat(3) { ensureImage(fake.docker, image, Duration.ZERO, fiveMin, last, clock(t0), log) }

        verify(fake.pull, times(3)).exec(any(PullImageResultCallback::class.java))
    }

    @Test
    fun `a failed refresh of a present main warns and goes ahead`() {
        val fake = docker(present = true) { cb -> cb.onError(RuntimeException("connection refused")); cb }

        ensureImage(fake.docker, image, fiveMin, fiveMin, HashMap(), clock(t0), log)

        verify(fake.pull).exec(any(PullImageResultCallback::class.java))
    }

    @Test
    fun `refreshable and splitImage read the registry port as part of the repo`() {
        assertEquals(repo to "main", splitImage(image))
        assertEquals("frakworks-smith" to "latest", splitImage("frakworks-smith"))
        assertEquals("registry.example:5000/frakworks-smith" to "latest", splitImage("registry.example:5000/frakworks-smith"))
        assertTrue(refreshable(image))
        assertTrue(refreshable("registry.example:5000/frakworks-smith"))
        assertFalse(refreshable("registry.example:5000/frakworks-smith:sha-1234abc"))
        assertFalse(refreshable("registry.example:5000/frakworks-smith@sha256:abc"))
        assertFalse(refreshable("frakworks-smith:local"))
    }

    private fun digestDocker(repoDigests: List<String>?, inspectFails: Boolean = false): DockerClient {
        val docker = mock(DockerClient::class.java)
        val ic = mock(InspectContainerCmd::class.java)
        `when`(docker.inspectContainerCmd("cid")).thenReturn(ic)
        if (inspectFails) {
            `when`(ic.exec()).thenThrow(RuntimeException("daemon gone"))
            return docker
        }
        val container = mock(InspectContainerResponse::class.java)
        `when`(container.imageId).thenReturn("sha256:imageid")
        `when`(ic.exec()).thenReturn(container)
        val ii = mock(InspectImageCmd::class.java)
        `when`(docker.inspectImageCmd("sha256:imageid")).thenReturn(ii)
        val resp = mock(InspectImageResponse::class.java)
        `when`(resp.repoDigests).thenReturn(repoDigests)
        `when`(ii.exec()).thenReturn(resp)
        return docker
    }

    @Test
    fun `imageDigest prefers the configured repo's RepoDigest`() {
        val docker = digestDocker(listOf("other/repo@sha256:x", "$repo@sha256:y"))
        assertEquals("$repo@sha256:y", imageDigest(docker, "cid", image))
    }

    @Test
    fun `imageDigest falls back to the image id`() {
        assertEquals("sha256:imageid", imageDigest(digestDocker(emptyList()), "cid", "frakworks-smith:local"))
        assertEquals("sha256:imageid", imageDigest(digestDocker(null), "cid", image))
    }

    @Test
    fun `imageDigest returns null rather than throwing when inspect fails`() {
        assertNull(imageDigest(digestDocker(null, inspectFails = true), "cid", image))
    }
}

package com.codefrak.engine.smith

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.ListContainersCmd
import com.github.dockerjava.api.command.RemoveContainerCmd
import com.github.dockerjava.api.model.Container
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `reclaim` (DockerSandbox.kt) against a faked [DockerClient]: the orphan
 * check that covers the 2026-09-25 chore #99 incident (engine/README.md,
 * "Smith runs"). No live daemon is available in this environment, so the
 * Docker types are mocked directly rather than driven end-to-end.
 */
class DockerSandboxReclaimTest {
    private val log = LoggerFactory.getLogger(DockerSandboxReclaimTest::class.java)
    private val name = "smith-99-af16dd70"

    private fun docker(existing: List<Container>): Pair<DockerClient, ListContainersCmd> {
        val docker = mock(DockerClient::class.java)
        val list = mock(ListContainersCmd::class.java)
        `when`(docker.listContainersCmd()).thenReturn(list)
        `when`(list.withShowAll(true)).thenReturn(list)
        `when`(list.withNameFilter(listOf(name))).thenReturn(list)
        `when`(list.exec()).thenReturn(existing)
        return docker to list
    }

    private fun container(state: String, id: String): Container {
        val c = mock(Container::class.java)
        `when`(c.id).thenReturn(id)
        `when`(c.names).thenReturn(arrayOf("/$name"))
        `when`(c.state).thenReturn(state)
        return c
    }

    @Test
    fun `no existing container -- nothing to reclaim, caller creates fresh`() {
        val (docker, _) = docker(emptyList())

        assertNull(reclaim(docker, name, log))
        verify(docker, never()).removeContainerCmd(anyString())
    }

    @Test
    fun `a Created orphan of the same name is removed and the caller creates fresh`() {
        val orphan = container(state = "created", id = "orphan-id")
        val (docker, _) = docker(listOf(orphan))
        val remove = mock(RemoveContainerCmd::class.java)
        `when`(docker.removeContainerCmd("orphan-id")).thenReturn(remove)
        `when`(remove.withForce(true)).thenReturn(remove)

        assertNull(reclaim(docker, name, log))

        verify(remove).exec()
    }

    @Test
    fun `an Exited orphan of the same name is removed too`() {
        val orphan = container(state = "exited", id = "orphan-id")
        val (docker, _) = docker(listOf(orphan))
        val remove = mock(RemoveContainerCmd::class.java)
        `when`(docker.removeContainerCmd("orphan-id")).thenReturn(remove)
        `when`(remove.withForce(true)).thenReturn(remove)

        assertNull(reclaim(docker, name, log))

        verify(remove).exec()
    }

    @Test
    fun `a running container of the same name is adopted, not removed`() {
        val running = container(state = "running", id = "running-id")
        val (docker, _) = docker(listOf(running))

        assertEquals("running-id", reclaim(docker, name, log))

        verify(docker, never()).removeContainerCmd(anyString())
    }

    @Test
    fun `sandbox HostConfig carries the configured memory and CPU caps and a Smith-first oom score`() {
        val host = sandboxHostConfig(2147483648L, 2.0)

        assertEquals(2147483648L, host.memory)
        assertEquals(500, host.oomScoreAdj)
        assertEquals(2000000000L, host.nanoCPUs)
    }
}

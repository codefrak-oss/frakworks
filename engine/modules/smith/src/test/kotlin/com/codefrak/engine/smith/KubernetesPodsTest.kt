package com.codefrak.engine.smith

import io.fabric8.kubernetes.api.model.ContainerStateBuilder
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.PodConditionBuilder
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `podExit` and `podEvicted` on hand-built `Pod` objects, with no mock client needed: both are pure. */
class KubernetesPodsTest {
    @Test
    fun `podExit reads the smith container's terminated exit code, null while it runs`() {
        val terminated = PodBuilder().withNewStatus()
            .withContainerStatuses(ContainerStatusBuilder().withName("smith")
                .withState(ContainerStateBuilder().withNewTerminated().withExitCode(42).endTerminated().build()).build())
            .endStatus().build()
        assertEquals(42L, podExit(terminated))

        val running = PodBuilder().withNewStatus()
            .withContainerStatuses(ContainerStatusBuilder().withName("smith")
                .withState(ContainerStateBuilder().withNewRunning().endRunning().build()).build())
            .endStatus().build()
        assertNull(podExit(running))
    }

    @Test
    fun `podEvicted is true for a DisruptionTarget condition with an eviction reason`() {
        val evicted = PodBuilder().withNewStatus()
            .withConditions(PodConditionBuilder().withType("DisruptionTarget").withStatus("True")
                .withReason("TerminationByKubelet").build())
            .endStatus().build()
        assertTrue(podEvicted(evicted))
    }
}

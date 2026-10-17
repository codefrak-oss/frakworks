package com.codefrak.engine.smith

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SandboxHandleTest {
    @Test
    fun `parse round trips handleOf`() {
        assertEquals("smiths" to "smith-1320-0a1b2c3d", KubernetesSandbox.parse(KubernetesSandbox.handleOf("smiths", "smith-1320-0a1b2c3d")))
    }

    @Test
    fun `parse refuses a handle without the k8s prefix`() {
        assertFailsWith<IllegalArgumentException> { KubernetesSandbox.parse("abc123def456") }
    }

    @Test
    fun `parse refuses k8s prefix with no slash`() {
        assertFailsWith<IllegalArgumentException> { KubernetesSandbox.parse("k8s:smiths") }
    }
}

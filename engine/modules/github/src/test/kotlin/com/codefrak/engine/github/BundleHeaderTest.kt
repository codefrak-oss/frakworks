package com.codefrak.engine.github

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** bundlePrerequisites against raw byte strings, not real git bundles: it reads bytes textually and never calls git. */
class BundleHeaderTest {
    @Test
    fun `v2 header with prerequisites`() {
        val bundle = "# v2 git bundle\n-aaaa1111 first commit\n-bbbb2222 second commit\nrefs/heads/main deadbeef\n\nbinary noise".toByteArray(Charsets.UTF_8)
        assertEquals(listOf("aaaa1111", "bbbb2222"), bundlePrerequisites(bundle))
    }

    @Test
    fun `v3 header with capability line and prerequisites`() {
        val bundle = "# v3 git bundle\n@capability-value\n-cccc3333 third commit\nrefs/heads/main deadbeef\n\nbinary noise".toByteArray(Charsets.UTF_8)
        assertEquals(listOf("cccc3333"), bundlePrerequisites(bundle))
    }

    @Test
    fun `header with no prerequisites`() {
        val bundle = "# v2 git bundle\nrefs/heads/main deadbeef\n\nbinary noise".toByteArray(Charsets.UTF_8)
        assertEquals(emptyList(), bundlePrerequisites(bundle))
    }

    @Test
    fun `bundle with no blank line`() {
        val bundle = "# v2 git bundle\n-abc123 msg\n".toByteArray(Charsets.UTF_8)
        assertEquals(listOf("abc123"), bundlePrerequisites(bundle))
    }
}

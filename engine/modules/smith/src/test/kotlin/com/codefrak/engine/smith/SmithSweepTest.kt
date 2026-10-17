package com.codefrak.engine.smith

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SmithSweepTest {
    private val now = Instant.parse("2026-09-25T03:00:00Z")
    private val old = now.minusSeconds(600)

    @Test
    fun `a container with no active run is swept, a live one is left alone`() {
        val live = SmithContainer("a", "smith-129-b01a3866", true, old)
        val orphan = SmithContainer("b", "smith-130-b1668e1f", true, old)
        val exited = SmithContainer("c", "smith-131-c0ffee00", false, now)
        val young = SmithContainer("d", "smith-132-d0000000", true, now.minusSeconds(60))
        val foreign = SmithContainer("e", "smith-proxy", false, old)
        val swept = orphans(listOf(live, orphan, exited, young, foreign),
            listOf("b01a3866-b887-11f1-9db9-369c9e71ff1d"), now)
        assertEquals(setOf(orphan, exited), swept.keys)
    }

    @Test
    fun `the run id prefix comes from DockerSandbox's naming`() {
        assertEquals("b01a3866", runIdPrefix("smith-129-b01a3866"))
        assertNull(runIdPrefix("smith-129"))
    }
}

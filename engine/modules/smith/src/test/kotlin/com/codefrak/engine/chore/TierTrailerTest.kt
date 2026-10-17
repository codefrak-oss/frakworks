package com.codefrak.engine.chore

import com.codefrak.engine.intake.testFiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TierTrailerTest {
    private val filer = testFiler()

    @Test
    fun `an intake's or a proposal's trailer carries its tier back to the start`() {
        assertEquals(4, ProcessFlowStarter.parseTierFromBody(filer.body("Do it.", "intake abc-123", 4, "p2")))
        assertEquals(3, ProcessFlowStarter.parseTierFromBody(filer.body("Builds: docs/proposals/x.md", "proposal #12", 3, "p3")))
        assertEquals(4, ProcessFlowStarter.parseTierFromBody(filer.body("Do it.", "intake abc-123", 4, "p2", by = "alice")), "with `by` after the priority")
    }

    @Test
    fun `a trailer with no tier leaves the tier to the default`() {
        val body = filer.body("Builds: docs/proposals/x.md", "proposal #12", null, "p3")
        assertEquals("Builds: docs/proposals/x.md\n\n<!-- engine: proposal #12 · p3 -->\n", body)
        assertNull(ProcessFlowStarter.parseTierFromBody(body))
    }
}

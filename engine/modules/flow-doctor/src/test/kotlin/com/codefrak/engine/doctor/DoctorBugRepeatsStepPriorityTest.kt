package com.codefrak.engine.doctor

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [DoctorBugRepeats.stepPriority]: the trailer's priority stepped down one, never above p2. */
class DoctorBugRepeatsStepPriorityTest {
    @Test
    fun `a p4 trailer steps to p3`() {
        val body = "The sandbox dies.\n\n<!-- engine: intake abc · tier 5 · p4 -->"
        val (newBody, stepped) = DoctorBugRepeats.stepPriority(body)!!
        assertEquals("p3", stepped)
        assertEquals("The sandbox dies.\n\n<!-- engine: intake abc · tier 5 · p3 -->", newBody)
    }

    @Test
    fun `a p2 trailer does not step`() {
        val body = "The sandbox dies.\n\n<!-- engine: intake abc · tier 5 · p2 -->"
        assertNull(DoctorBugRepeats.stepPriority(body))
    }

    @Test
    fun `a body with no priority trailer does not step`() {
        val body = "The sandbox dies.\n\nNo trailer here."
        assertNull(DoctorBugRepeats.stepPriority(body))
    }
}

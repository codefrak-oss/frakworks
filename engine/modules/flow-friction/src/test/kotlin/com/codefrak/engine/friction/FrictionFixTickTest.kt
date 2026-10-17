package com.codefrak.engine.friction

import org.junit.jupiter.api.Test
import org.springframework.scheduling.annotation.Scheduled
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The fix sync's first pass comes soon after start, so an engine redeployed on every merge still runs one (#2275). */
class FrictionFixTickTest {
    @Test
    fun `the first pass is not a full interval after start`() {
        val scheduled = FrictionFixTick::class.java.getMethod("syncFixes").getAnnotation(Scheduled::class.java)
        assertEquals(FrictionFixTick.FIRST_PASS, scheduled.initialDelayString)
        assertTrue(Duration.parse(scheduled.initialDelayString) < Duration.ofMinutes(2))
    }
}

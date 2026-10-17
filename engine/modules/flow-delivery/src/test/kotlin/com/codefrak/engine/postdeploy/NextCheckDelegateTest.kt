package com.codefrak.engine.postdeploy

import com.codefrak.engine.smith.Vars
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.variable.value.TypedValue
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import kotlin.test.assertEquals

/** [NextCheckDelegate]: the hint, the section's Re-check, and the ladder, on a mocked execution with a fixed clock. Plain JUnit. */
class NextCheckDelegateTest {
    private val now = Instant.parse("2026-10-13T12:00:00Z")
    private val vars = mutableMapOf<String, Any?>()
    private val execution = mock(DelegateExecution::class.java).also { e ->
        `when`(e.processInstanceId).thenReturn("flow-1")
        `when`(e.getVariable(anyString())).thenAnswer { vars[it.getArgument(0)] }
        doAnswer {
            val v = it.getArgument<Any?>(1)
            vars[it.getArgument(0)] = if (v is TypedValue) v.value else v
            null
        }.`when`(e).setVariable(anyString(), any())
    }
    private val delegate = NextCheckDelegate(Clock.fixed(now, ZoneOffset.UTC))

    private fun runs(n: Int) { vars[Vars.CHECK_HISTORY] = CheckRun.bytes((1..n).map { CheckRun("run-$it", now.toString(), "not_yet", "wait") }) }
    private fun wakeAt(): Instant = (vars[Vars.CHECK_WAKE_AT] as Date).toInstant()

    @Test
    fun `the Smith's at hint stands as it is`() {
        runs(1)
        vars[Vars.CHECK_WAKE_AT] = Date.from(Instant.parse("2026-10-20T08:00:00Z"))
        delegate.execute(execution)
        assertEquals(Instant.parse("2026-10-20T08:00:00Z"), wakeAt())
    }

    @Test
    fun `the section's Re-check sets the first wait only`() {
        vars[Vars.POST_DEPLOY_CHECK] = PostDeployCheck("Watch it.", Duration.ofMinutes(30)).toBytes()
        runs(1); delegate.execute(execution)
        assertEquals(now.plus(Duration.ofMinutes(30)), wakeAt())
        vars.remove(Vars.CHECK_WAKE_AT)
        runs(2); delegate.execute(execution)
        assertEquals(now.plus(Duration.ofHours(4)), wakeAt(), "the second wait is the ladder's")
    }

    @Test
    fun `without a hint or a Re-check the ladder is 1h, 4h, 1d, then daily, by the runs so far`() {
        val expected = listOf(1 to Duration.ofHours(1), 2 to Duration.ofHours(4), 3 to Duration.ofDays(1), 4 to Duration.ofDays(1), 9 to Duration.ofDays(1))
        for ((n, wait) in expected) {
            vars.remove(Vars.CHECK_WAKE_AT)
            runs(n); delegate.execute(execution)
            assertEquals(now.plus(wait), wakeAt(), "after $n runs")
        }
        vars.remove(Vars.CHECK_WAKE_AT); vars.remove(Vars.CHECK_HISTORY)
        delegate.execute(execution)
        assertEquals(now.plus(Duration.ofHours(1)), wakeAt(), "no history counts as the first")
    }

    @Test
    fun `an event hint still gets the ladder's time as the fallback`() {
        runs(2); vars[Vars.CHECK_WAKE_EVENT] = "push:default=true"
        delegate.execute(execution)
        assertEquals(now.plus(Duration.ofHours(4)), wakeAt())
        assertEquals("push:default=true", vars[Vars.CHECK_WAKE_EVENT])
    }
}

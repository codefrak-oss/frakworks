package com.codefrak.engine.intake

import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.operaton.bpm.engine.delegate.DelegateExecution

/** The step off the happy path: a store that fails must not fail the intake. No Spring, no engine. */
class NotifyFilingSessionDelegateTest {
    private fun execution(vars: Map<String, Any?>): DelegateExecution {
        val execution = mock(DelegateExecution::class.java)
        `when`(execution.processInstanceId).thenReturn("intake-1")
        `when`(execution.getVariable(anyString())).thenAnswer { vars[it.getArgument<String>(0)] }
        return execution
    }

    private val filed = mapOf("notifySession" to "first-mate", "outcome" to "file", "ticket" to 928L,
        "ticketUrl" to "https://github.com/acme/tickets/issues/928", "repo" to "acme/tickets", "dryRun" to false)

    @Test
    fun `a store that throws is logged and the step completes`() {
        val store = mock(IntakeNotifications::class.java)
        `when`(store.queue(anyString(), anyString(), anyString(), anyLong(), anyString())).thenThrow(IllegalStateException("database gone"))
        NotifyFilingSessionDelegate(store).execute(execution(filed)) // no throw
    }

    @Test
    fun `a filed ticket with a session is queued with the intake's repo, ticket and url`() {
        val store = mock(IntakeNotifications::class.java)
        `when`(store.queue(anyString(), anyString(), anyString(), anyLong(), anyString())).thenReturn(true)
        NotifyFilingSessionDelegate(store).execute(execution(filed))
        verify(store).queue("intake-1", "first-mate", "acme/tickets", 928L, "https://github.com/acme/tickets/issues/928")
    }

    @Test
    fun `a ticket filed unlabeled for human triage is queued too`() {
        val store = mock(IntakeNotifications::class.java)
        `when`(store.queue(anyString(), anyString(), anyString(), anyLong(), anyString())).thenReturn(true)
        NotifyFilingSessionDelegate(store).execute(execution(filed + ("outcome" to "insufficient-permissions")))
        verify(store).queue("intake-1", "first-mate", "acme/tickets", 928L, "https://github.com/acme/tickets/issues/928")
    }

    @Test
    fun `nothing is queued without a session, on a dry run, or without a filed ticket`() {
        val store = mock(IntakeNotifications::class.java)
        val delegate = NotifyFilingSessionDelegate(store)
        delegate.execute(execution(filed - "notifySession"))
        delegate.execute(execution(filed + ("notifySession" to "  ")))
        delegate.execute(execution(filed + ("dryRun" to true)))
        delegate.execute(execution(filed + ("outcome" to "ask") - "ticket"))
        delegate.execute(execution(filed - "ticket"))
        verifyNoInteractions(store)
    }
}

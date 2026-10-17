package com.codefrak.engine.postdeploy

import com.codefrak.engine.smith.Vars
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.runtime.Execution
import org.operaton.bpm.engine.runtime.MessageCorrelationBuilder
import org.operaton.bpm.engine.variable.Variables
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [CheckEventWaits]: the frame grammar, the hint match, and which waiting flows a frame wakes, on a mocked RuntimeService. */
class CheckEventWaitsTest {
    private val runtime = mock(RuntimeService::class.java, RETURNS_DEEP_STUBS)
    private val correlation = mock(MessageCorrelationBuilder::class.java, RETURNS_SELF)
    private val waits = CheckEventWaits(runtime)

    private fun frame(type: String, data: String) = "event: $type\ndata: $data\n\n"

    private fun waiting(vararg flows: Triple<String, String, String?>) {
        val executions = flows.map { (id, repo, hint) ->
            mock(Execution::class.java).also { e ->
                `when`(e.id).thenReturn("exec-$id"); `when`(e.processInstanceId).thenReturn(id)
                `when`(runtime.getVariables(eq("exec-$id"), anyList())).thenReturn(Variables.createVariables().putValue(Vars.REPO, repo).putValue(Vars.CHECK_WAKE_EVENT, hint))
            }
        }
        `when`(runtime.createExecutionQuery().messageEventSubscriptionName(CHECK_EVENT_MESSAGE).list()).thenReturn(executions)
        `when`(runtime.createMessageCorrelation(CHECK_EVENT_MESSAGE)).thenReturn(correlation)
    }

    @Test
    fun `a frame's type and data are read, and a ping is no frame`() {
        val f = frame("needs", """{"repo":"acme/tickets","kind":"question","issue":42}""")
        assertEquals("needs", CheckEventWaits.type(f))
        assertEquals("question", CheckEventWaits.data(f)["kind"])
        assertNull(CheckEventWaits.type(": ping\n\n"))
        assertEquals(emptyMap(), CheckEventWaits.data("event: x\ndata: not json\n\n"))
    }

    @Test
    fun `a hint takes its type, and its field by value as text, through nested objects`() {
        val data = mapOf<String, Any?>("issue" to 42, "kind" to "question", "run" to mapOf("outcome" to "done"))
        assertTrue(CheckEventWaits.matches("needs", "needs", data))
        assertTrue(CheckEventWaits.matches("needs:issue=42", "needs", data))
        assertTrue(CheckEventWaits.matches("needs:run.outcome=done", "needs", data))
        assertFalse(CheckEventWaits.matches("needs:kind=merge", "needs", data))
        assertFalse(CheckEventWaits.matches("needs:missing=1", "needs", data))
        assertFalse(CheckEventWaits.matches("deploy", "needs", data))
    }

    @Test
    fun `a matching frame of the flow's repo wakes it, and the others stay`() {
        waiting(Triple("p1", "acme/tickets", "needs:kind=question"), Triple("p2", "acme/tickets", "deploy"), Triple("p3", "acme/other", "needs"))
        assertEquals(listOf("p1"), waits.wake("Acme/Tickets", frame("needs", """{"kind":"question"}""")))
        verify(correlation).processInstanceId("p1")
        verify(correlation, never()).processInstanceId("p2")
        verify(correlation, never()).processInstanceId("p3")
        verify(correlation).correlate()
    }

    @Test
    fun `a frame that matches no hint, or a ping, wakes nothing`() {
        waiting(Triple("p1", "acme/tickets", "needs:kind=question"))
        assertEquals(emptyList(), waits.wake("acme/tickets", frame("needs", """{"kind":"merge"}""")))
        assertEquals(emptyList(), waits.wake("acme/tickets", ": ping\n\n"))
        verify(correlation, never()).correlate()
    }
}

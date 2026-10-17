package com.codefrak.engine.postdeploy

import com.codefrak.engine.smith.Vars
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.operaton.bpm.engine.MismatchingMessageCorrelationException
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.runtime.Execution
import org.operaton.bpm.engine.runtime.MessageCorrelationBuilder
import org.operaton.bpm.engine.variable.Variables
import kotlin.test.assertEquals

/** [DeployReachedWaits]: which waiting flows a deploy wakes, on a mocked RuntimeService. Plain JUnit. */
class DeployWaitsTest {
    private val runtime = mock(RuntimeService::class.java, RETURNS_DEEP_STUBS)
    private val correlation = mock(MessageCorrelationBuilder::class.java, RETURNS_SELF)
    /** What the ops check says is deployed, by sha; every call is recorded. */
    private val deployed = mutableSetOf<String>()
    private val asked = mutableListOf<Pair<String, String>>()
    private val waits = DeployReachedWaits(runtime) { repo, sha -> asked += repo to sha; sha in deployed }

    private fun waiting(vararg flows: Triple<String, String, String?>) {
        val executions = flows.map { (id, repo, sha) ->
            mock(Execution::class.java).also { e ->
                `when`(e.id).thenReturn("exec-$id"); `when`(e.processInstanceId).thenReturn(id)
                `when`(runtime.getVariables(eq("exec-$id"), anyList())).thenReturn(Variables.createVariables().putValue(Vars.REPO, repo).putValue(Vars.MERGE_SHA, sha))
            }
        }
        `when`(runtime.createExecutionQuery().messageEventSubscriptionName(DEPLOY_REACHED_MESSAGE).list()).thenReturn(executions)
        `when`(runtime.createMessageCorrelation(DEPLOY_REACHED_MESSAGE)).thenReturn(correlation)
    }

    @Test
    fun `a deploy of the merge sha wakes its flow without asking the ops check`() {
        waiting(Triple("p1", "acme/tickets", "abc1234def"))
        assertEquals(listOf("p1"), waits.wake("Acme/Tickets", "ABC1234DEF5678"))
        verify(correlation).processInstanceId("p1")
        verify(correlation).setVariable(eq(Vars.DEPLOY_EVENT), any())
        verify(correlation).correlate()
        assertEquals(emptyList(), asked)
    }

    @Test
    fun `a deploy of a later commit wakes the flow only when the ops check says the merge went out with it`() {
        waiting(Triple("p1", "acme/tickets", "aaaaaaa1"), Triple("p2", "acme/tickets", "bbbbbbb2"))
        deployed += "bbbbbbb2"
        assertEquals(listOf("p2"), waits.wake("acme/tickets", "ccccccc3"))
        assertEquals(listOf("acme/tickets" to "aaaaaaa1", "acme/tickets" to "bbbbbbb2"), asked)
        verify(correlation).processInstanceId("p2")
        verify(correlation, never()).processInstanceId("p1")
    }

    @Test
    fun `another repo's deploy wakes nothing and asks nothing`() {
        waiting(Triple("p1", "acme/tickets", "abc1234"))
        assertEquals(emptyList(), waits.wake("acme/other", "abc1234"))
        assertEquals(emptyList(), asked)
        verify(correlation, never()).correlate()
    }

    @Test
    fun `a failing ops check leaves that flow waiting and still wakes the others`() {
        waiting(Triple("p1", "acme/tickets", "aaaaaaa1"), Triple("p2", "acme/tickets", "ddddddd4"))
        val failing = DeployReachedWaits(runtime) { _, sha -> if (sha == "aaaaaaa1") error("no ops server") else false }
        assertEquals(listOf("p2"), failing.wake("acme/tickets", "ddddddd4"))
    }

    @Test
    fun `a flow another wake already moved on is skipped`() {
        waiting(Triple("p1", "acme/tickets", "abc1234"))
        doThrow(MismatchingMessageCorrelationException(DEPLOY_REACHED_MESSAGE)).`when`(correlation).correlate()
        assertEquals(emptyList(), waits.wake("acme/tickets", "abc1234"))
    }

    @Test
    fun `shas are the same commit when one is a prefix of the other`() {
        assertEquals(true, DeployReachedWaits.same("abc1234", "abc1234def"))
        assertEquals(false, DeployReachedWaits.same("abc1234", "abd1234"))
        assertEquals(false, DeployReachedWaits.same("", "abc1234"))
    }
}

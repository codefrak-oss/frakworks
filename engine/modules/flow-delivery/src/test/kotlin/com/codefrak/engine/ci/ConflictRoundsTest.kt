package com.codefrak.engine.ci

import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.Vars
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.variable.value.TypedValue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [ConflictRounds]'s caps, with and without a retry's grant, on a mocked execution's variables. Plain JUnit. */
class ConflictRoundsTest {
    private val vars = mutableMapOf<String, Any?>()
    private val execution = mock(DelegateExecution::class.java).also { e ->
        `when`(e.getVariable(anyString())).thenAnswer { vars[it.getArgument(0)] }
        doAnswer {
            val v = it.getArgument<Any?>(1)
            vars[it.getArgument(0)] = if (v is TypedValue) v.value else v
            null
        }.`when`(e).setVariable(anyString(), any())
    }

    private fun conflict() = ConflictRounds.recordConflict(execution, "main", listOf("a.txt"))
    private fun findingId() = Brief.findings(vars[Vars.FINDINGS] as ByteArray).single().id

    @Test
    fun `without a grant the third consecutive conflict fails`() {
        conflict(); conflict()
        val e = assertFailsWith<IllegalStateException> { conflict() }
        assertTrue("after 2 conflict rounds" in e.message!!, e.message)
        assertEquals(2, vars[Vars.CONFLICT_ROUNDS], "nothing is persisted by the failing round")
    }

    @Test
    fun `without a grant the seventh lifetime conflict fails on the lifetime cap`() {
        repeat(ConflictRounds.MAX_TOTAL_CONFLICT_ROUNDS) { conflict(); ConflictRounds.resolved(execution) }
        val e = assertFailsWith<IllegalStateException> { conflict() }
        assertTrue("in the flow's life (the lifetime cap)" in e.message!!, e.message)
    }

    @Test
    fun `a grant allows two more consecutive rounds past either cap, with ids still unique`() {
        repeat(ConflictRounds.MAX_TOTAL_CONFLICT_ROUNDS) { conflict(); ConflictRounds.resolved(execution) }
        // What IncidentRetrier does on a conflict-cap incident.
        vars[Vars.CONFLICT_ROUNDS] = 0; vars[Vars.CONFLICT_ROUNDS_GRANTED] = ConflictRounds.MAX_CONFLICT_ROUNDS
        conflict()
        assertEquals("rebase-7-1", findingId())
        conflict()
        assertEquals("rebase-8-1", findingId())
        assertEquals(0, vars[Vars.CONFLICT_ROUNDS_GRANTED])
        val e = assertFailsWith<IllegalStateException> { conflict() }
        assertTrue("after 2 conflict rounds" in e.message!!, e.message)
    }

    @Test
    fun `a round after a successful push carries no push finding, and other findings stay`() {
        val push = com.codefrak.engine.smith.Finding("refused", "high", id = "push-1-1", review = "push")
        val ci = com.codefrak.engine.smith.Finding("tests fail", id = "ci-1-1", review = "ci")
        assertEquals(listOf(ci), ConflictRounds.afterPush(listOf(push, ci)))
        assertEquals(emptyList(), ConflictRounds.afterPush(listOf(push)))
        assertEquals(null, ConflictRounds.afterPush(listOf(ci)))
    }
}

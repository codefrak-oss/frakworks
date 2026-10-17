package com.codefrak.engine.camunda

import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.history.HistoricVariableInstance
import org.operaton.bpm.engine.history.HistoricVariableInstanceQuery
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [variablesOf] and [ownScopeVariablesOf]. */
class ProcessVariablesTest {
    private val history = mock(HistoryService::class.java)

    private fun stub(instance: String, vararg vars: HistoricVariableInstance) {
        val query = mock(HistoricVariableInstanceQuery::class.java)
        `when`(history.createHistoricVariableInstanceQuery()).thenReturn(query)
        `when`(query.processInstanceId(instance)).thenReturn(query)
        `when`(query.list()).thenReturn(vars.toList())
    }

    private fun variable(name: String, value: Any?, activityInstanceId: String, unreadable: Boolean = false) =
        mock(HistoricVariableInstance::class.java).also {
            `when`(it.name).thenReturn(name)
            `when`(it.activityInstanceId).thenReturn(activityInstanceId)
            if (unreadable) `when`(it.value).thenThrow(RuntimeException("unreadable")) else `when`(it.value).thenReturn(value)
        }

    @Test
    fun `variablesOf keeps the last value of a repeated name`() {
        stub("p1", variable("repo", "first", "p1"), variable("repo", "second", "p1"))
        assertEquals(mapOf("repo" to "second"), history.variablesOf("p1"))
    }

    @Test
    fun `ownScopeVariablesOf prefers the process's own scope over a child activity's`() {
        stub("p1", variable("repo", "child", "child-activity"), variable("repo", "own", "p1"))
        assertEquals(mapOf("repo" to "own"), history.ownScopeVariablesOf("p1"))
    }

    @Test
    fun `ownScopeVariablesOf resolves to null when every copy of a name is unreadable`() {
        stub("p1", variable("repo", null, "p1", unreadable = true))
        assertNull(history.ownScopeVariablesOf("p1")["repo"])
    }

    @Test
    fun `ownScopeVariablesOf falls through an unreadable own-scope value to a readable other one`() {
        stub("p1", variable("repo", "fallback", "child-activity"), variable("repo", null, "p1", unreadable = true))
        assertEquals(mapOf("repo" to "fallback"), history.ownScopeVariablesOf("p1"))
    }
}

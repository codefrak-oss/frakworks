package com.codefrak.engine.smith

import com.codefrak.engine.repos.ImplementationSummaries
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** An accepted `done` result's summary is kept as its ticket's, with no step after it; against H2 with Flyway's migrations. */
@SpringBootTest
class ImplementationSummaryListenerTest(@Autowired val summaries: ImplementationSummaries, @Autowired val listener: ImplementationSummaryListener) {
    private fun execution(repo: String, issue: Long): DelegateExecution = mock(DelegateExecution::class.java).also { e ->
        val vars = mapOf(Vars.REPO to repo, Vars.ISSUE to issue)
        `when`(e.getVariable(anyString())).thenAnswer { vars[it.getArgument<String>(0)] }
    }
    private val first = "Adds a work result field for the change summary and a table that keeps it per ticket."
    private val second = "Serves the kept summary on closed tickets whose pull request merged, and renders it in the desktop."

    @Test
    fun `a done result's summary is stored, the latest round's over an earlier one`() {
        listener.accepted(execution("acme/summary-test", 7), WorkResult("done", "t", "s", implementationSummary = first))
        assertEquals(first, summaries.of("acme/summary-test", 7))
        listener.accepted(execution("acme/summary-test", 7), WorkResult("done", "t", "s", implementationSummary = second))
        assertEquals(second, summaries.of("acme/summary-test", 7))
    }

    @Test
    fun `a result without one, or not done, stores nothing`() {
        listener.accepted(execution("acme/summary-test", 8), WorkResult("done", "t", "s"))
        listener.accepted(execution("acme/summary-test", 8), WorkResult("ask", question = "q", implementationSummary = first))
        assertNull(summaries.of("acme/summary-test", 8))
    }
}

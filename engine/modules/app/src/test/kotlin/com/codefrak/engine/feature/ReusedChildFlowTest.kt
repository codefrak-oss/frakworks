package com.codefrak.engine.feature

import com.codefrak.engine.smith.Vars
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.RuntimeService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * #2542: a retried `task-file-child` that reuses a child whose flow still runs (started by the attempt the retry
 * rolled back) parks on that flow and starts no second one; [runningFlow] is how it finds it.
 */
@SpringBootTest
class ReusedChildFlowTest(@Autowired val runtime: RuntimeService) {
    /** A root flow parked at an async step on [repo]#[issue], as a child's flow in review is. */
    private fun parked(repo: String, issue: Long) = runtime.createProcessInstanceByKey("intake").startBeforeActivity("task-file-unlabeled")
        .setVariable(Vars.REPO, repo).setVariable(Vars.ISSUE, issue).setVariable("outcome", "insufficient-permissions")
        .setVariable("ticket", issue).setVariable("ticketUrl", "https://github.com/$repo/issues/$issue").execute()

    @Test
    fun `a reused child's running root flow is found, so no second flow starts`() {
        val child = parked("acme/reuse", 2532L)
        assertEquals(child.id, runningFlow(runtime, "acme/reuse", 2532L, "parent-instance"))
        assertEquals(1L, runtime.createProcessInstanceQuery().rootProcessInstances()
            .variableValueEquals(Vars.REPO, "acme/reuse").variableValueEquals(Vars.ISSUE, 2532L).count())
    }

    @Test
    fun `no flow is found for another issue, another repo, or the parent itself`() {
        val own = parked("acme/reuse2", 2498L)
        assertNull(runningFlow(runtime, "acme/reuse2", 2532L, own.id))
        assertNull(runningFlow(runtime, "acme/other", 2498L, own.id))
        assertNull(runningFlow(runtime, "acme/reuse2", 2498L, own.id))
    }
}

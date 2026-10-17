package com.codefrak.engine.camunda

import com.codefrak.engine.repos.Access
import com.codefrak.engine.smith.SmithCaller
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.runtime.ProcessInstance
import org.operaton.bpm.engine.runtime.ProcessInstanceQuery
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [ActorAccess.allowed] and [.refusal] for [Actor.Login], [Actor.Smith] and [Actor.System]. */
class ActorAccessTest {
    private val access = mock(Access::class.java)
    private val runtime = mock(RuntimeService::class.java)
    private val history = mock(HistoryService::class.java)
    private val actorAccess = ActorAccess(access, runtime, history)

    /** The root flow [runId] runs under, as `ownRoot()` would resolve it: [rootId], or null when [runId] is not running. */
    private fun stubOwnRoot(runId: String, rootId: String?) {
        val query = mock(ProcessInstanceQuery::class.java)
        `when`(runtime.createProcessInstanceQuery()).thenReturn(query)
        val afterRunId = mock(ProcessInstanceQuery::class.java)
        `when`(query.processInstanceId(runId)).thenReturn(afterRunId)
        if (rootId == null) {
            `when`(afterRunId.singleResult()).thenReturn(null)
            return
        }
        val instance = mock(ProcessInstance::class.java).also { `when`(it.id).thenReturn(rootId) }
        `when`(afterRunId.singleResult()).thenReturn(instance)
        val afterSubId = mock(ProcessInstanceQuery::class.java)
        `when`(query.subProcessInstanceId(rootId)).thenReturn(afterSubId)
        `when`(afterSubId.singleResult()).thenReturn(null)
    }

    private fun smith(runId: String = "run-1", repo: String? = "acme/x") = Actor.Smith(SmithCaller(runId, issue = 1, repo = repo))

    @Test
    fun `a login is allowed only when trusted`() {
        `when`(access.trusted("alice")).thenReturn(true)
        `when`(access.trusted("bob")).thenReturn(false)
        assertTrue(actorAccess.allowed(Actor.Login("alice"), "acme/x", "chore", emptySet()))
        assertFalse(actorAccess.allowed(Actor.Login("bob"), "acme/x", "chore", emptySet()))
    }

    @Test
    fun `the engine's own system actor is always allowed`() {
        assertTrue(actorAccess.allowed(Actor.System("doctor"), "acme/x", "chore", setOf("root-1")))
        assertTrue(actorAccess.allowed(Actor.System("doctor"), "other/repo", "doctor", emptySet()))
    }

    @Test
    fun `a smith is allowed on its own repo when its own run's root is not in the checked roots`() {
        val actor = smith(runId = "run-1", repo = "acme/x")
        stubOwnRoot("run-1", rootId = null)
        assertTrue(actorAccess.allowed(actor, "acme/x", "chore", setOf("root-1")))
    }

    @Test
    fun `a smith is allowed on its own repo when its own run's root differs from the checked roots`() {
        val actor = smith(runId = "run-1", repo = "acme/x")
        stubOwnRoot("run-1", rootId = "root-mine")
        assertTrue(actorAccess.allowed(actor, "acme/x", "chore", setOf("root-other")))
    }

    @Test
    fun `a smith is refused its own run's root`() {
        val actor = smith(runId = "run-1", repo = "acme/x")
        stubOwnRoot("run-1", rootId = "root-mine")
        assertFalse(actorAccess.allowed(actor, "acme/x", "chore", setOf("root-mine")))
    }

    @Test
    fun `a smith is refused a repo other than its own, regardless of roots`() {
        val actor = smith(runId = "run-1", repo = "acme/x")
        assertFalse(actorAccess.allowed(actor, "other/repo", "chore", emptySet()))
    }

    @Test
    fun `refusal text for a login names the login`() {
        assertEquals("@bob is not a trusted login", actorAccess.refusal(Actor.Login("bob"), "retry", "chore", "acme/x"))
    }

    @Test
    fun `refusal text for a smith on its own root names the flow it runs under`() {
        val actor = smith(runId = "run-1", repo = "acme/x")
        assertEquals(
            "${actor.name} may not retry the chore it runs under",
            actorAccess.refusal(actor, "retry", "chore", "acme/x"),
        )
    }

    @Test
    fun `refusal text for a smith on another repo names its own repo and the refused one`() {
        val actor = smith(runId = "run-1", repo = "acme/x")
        assertEquals(
            "${actor.name} may retry only flows in its own repo (acme/x), not a chore on other/repo",
            actorAccess.refusal(actor, "retry", "chore", "other/repo"),
        )
    }

    @Test
    fun `refusal text for a smith with no repo of its own says none, and no checked repo says unknown`() {
        val actor = smith(runId = "run-1", repo = null)
        assertEquals(
            "${actor.name} may retry only flows in its own repo (none), not a chore on an unknown repo",
            actorAccess.refusal(actor, "retry", "chore", null),
        )
    }

    @Test
    fun `refusal text for the engine's own system actor names the part and the repo`() {
        val actor = Actor.System("doctor")
        assertEquals("${actor.name} may not retry a chore on acme/x", actorAccess.refusal(actor, "retry", "chore", "acme/x"))
        assertEquals("${actor.name} may not retry a chore on an unknown repo", actorAccess.refusal(actor, "retry", "chore", null))
    }
}

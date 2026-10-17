package com.codefrak.engine.smith

import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.runtime.ProcessInstance
import org.operaton.bpm.engine.runtime.ProcessInstanceQuery
import kotlin.test.assertEquals

/**
 * [RunSandboxStopper]'s walk, driven through a mocked [RuntimeService]: a `smith-run` instance nested under the root
 * is found and stopped, a sibling that is not a `smith-run` is skipped, and a `smith-run` with no [Vars.HANDLE] is
 * walked to but not counted.
 */
class RunSandboxStopperTest {
    /** Records every handle [stop] is asked to stop. */
    private class FakeSandbox : SmithSandbox {
        val stopped = mutableListOf<String>()
        override fun start(spec: SmithSpec) = throw UnsupportedOperationException()
        override fun status(handle: String) = throw UnsupportedOperationException()
        override fun logs(handle: String, maxLines: Int) = throw UnsupportedOperationException()
        override fun output(handle: String, path: String) = throw UnsupportedOperationException()
        override fun remove(handle: String) = throw UnsupportedOperationException()
        override fun stop(handle: String) { stopped += handle }
    }

    /**
     * A [RuntimeService] whose [tree] is `parent id -> child instance ids` (the `superProcessInstanceId` walk), and
     * whose [smithRuns] is `instance id -> handle or null` for every one of those ids that is a `smith-run` instance
     * (an id absent from it is some other process and is filtered out by `processDefinitionKey`). The final
     * `.list()` is answered from the ids the walk actually reached, via `processInstanceIds`, not from [tree]
     * directly: a walk that misses a nested id fails the test instead of being masked by a fixed stub.
     */
    private fun runtime(tree: Map<String, List<String>>, smithRuns: Map<String, String?>): RuntimeService {
        val runtime = mock(RuntimeService::class.java)
        val query = mock(ProcessInstanceQuery::class.java, Mockito.RETURNS_SELF)
        `when`(runtime.createProcessInstanceQuery()).thenReturn(query)

        var lastParent: String? = null
        var reachedIds: Set<String> = emptySet()
        var filtering = false
        `when`(query.superProcessInstanceId(anyString())).thenAnswer { inv ->
            lastParent = inv.getArgument(0); filtering = false; query
        }
        `when`(query.processInstanceIds(any())).thenAnswer { inv ->
            @Suppress("UNCHECKED_CAST")
            reachedIds = inv.getArgument<Set<String>>(0); query
        }
        `when`(query.processDefinitionKey(anyString())).thenAnswer { filtering = true; query }
        `when`(query.list()).thenAnswer {
            val ids = if (filtering) reachedIds.filter { smithRuns.containsKey(it) } else tree[lastParent].orEmpty()
            ids.map { id -> mock(ProcessInstance::class.java).also { `when`(it.id).thenReturn(id) } }
        }
        smithRuns.forEach { (id, handle) -> `when`(runtime.getVariable(id, Vars.HANDLE)).thenReturn(handle) }
        return runtime
    }

    @Test
    fun `a smith-run nested under the root is found and stopped`() {
        val tree = mapOf("root" to listOf("child"), "child" to listOf("grandchild"))
        val runtime = runtime(tree, mapOf("grandchild" to "handle-1"))
        val sandbox = FakeSandbox()

        val stopped = RunSandboxStopper(runtime, sandbox).stopUnder("root")

        assertEquals(listOf("grandchild" to "handle-1"), stopped)
        assertEquals(listOf("handle-1"), sandbox.stopped)
    }

    @Test
    fun `a sibling that is not a smith-run is skipped`() {
        val tree = mapOf("root" to listOf("smith-child", "other-child"))
        val runtime = runtime(tree, mapOf("smith-child" to "handle-1"))
        val sandbox = FakeSandbox()

        val stopped = RunSandboxStopper(runtime, sandbox).stopUnder("root")

        assertEquals(listOf("smith-child" to "handle-1"), stopped)
        assertEquals(listOf("handle-1"), sandbox.stopped)
    }

    @Test
    fun `a smith-run with no handle is walked to but not counted`() {
        val tree = mapOf("root" to listOf("no-handle"))
        val runtime = runtime(tree, mapOf("no-handle" to null))
        val sandbox = FakeSandbox()

        val stopped = RunSandboxStopper(runtime, sandbox).stopUnder("root")

        assertEquals(emptyList(), stopped)
        assertEquals(emptyList(), sandbox.stopped)
    }
}

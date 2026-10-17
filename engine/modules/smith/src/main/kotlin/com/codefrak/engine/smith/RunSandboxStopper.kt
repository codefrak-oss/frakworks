package com.codefrak.engine.smith

import org.operaton.bpm.engine.RuntimeService

/**
 * Stops every active `smith-run` sandbox under a root process instance: deleting the instance deletes the
 * smith-run activities that would otherwise poll and remove their own sandboxes, so each one is stopped first.
 * Shared by `FlowCanceller.stopSandboxes` (flow-intake) and `DoctorRunEnder.end` (flow-doctor), which sit in
 * sibling flow layers and may not import each other.
 */
class RunSandboxStopper(private val runtime: RuntimeService, private val sandbox: SmithSandbox) {
    /** Descends [root]'s called-process tree, stops the sandbox of every `smith-run` instance found, and
     * returns the (run id, handle) pairs stopped; a run with no [Vars.HANDLE] is walked to but not counted. */
    fun stopUnder(root: String): List<Pair<String, String>> {
        val runs = generateSequence(listOf(root)) { parents ->
            parents.flatMap { runtime.createProcessInstanceQuery().superProcessInstanceId(it).list().map { c -> c.id } }
                .ifEmpty { null }
        }.flatten().toSet()
        return runtime.createProcessInstanceQuery().processInstanceIds(runs).processDefinitionKey(SMITH_RUN).list().mapNotNull { run ->
            val handle = runtime.getVariable(run.id, Vars.HANDLE) as? String ?: return@mapNotNull null
            sandbox.stop(handle)
            run.id to handle
        }
    }

    companion object {
        /** The process definition key of a Smith's run, the engine-wide literal `FlowCanceller` and `DoctorRunEnder` used. */
        const val SMITH_RUN = "smith-run"
    }
}

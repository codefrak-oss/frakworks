package com.codefrak.engine.feature

import com.codefrak.engine.smith.Vars
import org.operaton.bpm.engine.RuntimeService
import org.springframework.stereotype.Component

/**
 * A child's running live parent (#100049; engine/docs/feature.md, "Children ask the parent"): the feature instance of
 * the parent ticket is at `call-parent` with a parent run under it, its `childMode` is `live`, it has not fallen back
 * (`liveFallback`), and the child is an open child of its split. Read by `post the smith's question` to route a child's
 * question to the parent, and by `startSmith` for the child's brief (`liveParent`). Null in every other case: a
 * `parallel` or `serial` split, no split, a live split in fallback, or a parent run that has ended.
 */
@Component
class LiveParents(private val runtime: RuntimeService) {
    companion object {
        /** The feature flow's live parent call activity (bpmn/feature.bpmn). */
        const val CALL_PARENT = "call-parent"
        private const val LIVE = "live"
        /** flow-feature's `LIVE_FALLBACK`, by name: engine-smith is below it. */
        private const val FALLBACK = "liveFallback"
    }

    /** The parent's number, its feature instance, and the parent run's `smith-run` instance (its run id); null when it does not run. */
    data class LiveParent(val number: Long, val instance: String, val runId: String?) {
        /** How the parent is named where a person's login would be: `asked_of`, `answered_by`, the brief's `by`. */
        val by: String get() = "#$number"
    }

    /** The running live parent [parent] of child [child] in [repo], or null. */
    fun of(repo: String, child: Long, parent: Long): LiveParent? {
        val feature = runtime.createProcessInstanceQuery().rootProcessInstances()
            .variableValueEquals(Vars.REPO, repo).variableValueEquals(Vars.ISSUE, parent).list()
            .firstOrNull { it.processDefinitionId.substringBefore(':') == "feature" } ?: return null
        val vars = runtime.getVariables(feature.id)
        if (vars[Vars.CHILD_MODE] != LIVE || vars[FALLBACK] == true) return null
        if (CALL_PARENT !in runtime.getActiveActivityIds(feature.id)) return null
        val split = SplitState.of(vars[Vars.SPLIT] as? ByteArray) ?: return null
        if (split.open().none { it.number == child }) return null
        val run = runtime.createProcessInstanceQuery().superProcessInstanceId(feature.id).list().firstOrNull()?.id
        return LiveParent(parent, feature.id, run)
    }
}

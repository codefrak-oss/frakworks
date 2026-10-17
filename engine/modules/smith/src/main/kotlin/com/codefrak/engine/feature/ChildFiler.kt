package com.codefrak.engine.feature

/**
 * What a live parent's `file_children` did (#100042; engine/docs/feature.md, "Live parent"): the split [state] after the
 * replan was applied and the ready drafts filed, the children [filed] in this pass (the state's `filed` entries, in
 * order), and [stopped], why filing stopped before every ready draft was filed (null when none was left or all went).
 */
data class ChildrenFiled(val state: SplitState, val filed: List<FiledChild>, val stopped: String? = null)

/**
 * The live parent's filing, shared by `file the next child` and the `frakworks` MCP server's `file_children`
 * (engine/docs/feature.md, "Live parent"): a contract in engine-smith, implemented in engine-flow-feature
 * (`ChildFiling`), so engine-api's `EngineCalls` files a child exactly as the flow does without depending on the flow module.
 */
interface ChildFiler {
    /**
     * Applies the parent's replan to feature instance [instance]'s split (`continue` keeps the drafts, `revise`
     * replaces them with [children], `done` drops them; [drop] names drafts to drop first, by title), under
     * `replanRules`, then records each child the engine's own close has closed and files every draft whose
     * dependencies are done, as `file the next child` does, each recorded on the split before the next. The split is
     * written on the feature instance and on the parent's own run [runId] (the `smith-run` instance, whose copy returns
     * to the feature when the run ends). [summary] is commented on the parent with the replan.
     * Throws [IllegalArgumentException] with the rule(s) broken, [IllegalStateException] when the split cannot be read.
     */
    fun fileChildren(instance: String, runId: String, outcome: String, children: List<ChildDraft>, drop: List<String>, summary: String?): ChildrenFiled
}

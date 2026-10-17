package com.codefrak.engine.camunda

import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.runtime.ActivityInstance
import org.operaton.bpm.engine.runtime.TransitionInstance
import org.springframework.stereotype.Component

/**
 * The activity the flow waits at, from the innermost called process (a chore's Smith run,
 * say). Read from the runtime's activity tree, not the history: a step waiting on its
 * `asyncBefore` job is a transition instance there and not yet an activity in the history.
 */
@Component
class CurrentStep(private val history: HistoryService, private val runtime: RuntimeService) {
    fun of(root: String): String? {
        val generations = generateSequence(listOf(root)) { parents ->
            parents.flatMap { history.createHistoricProcessInstanceQuery().superProcessInstanceId(it).list().map { c -> c.id } }
                .ifEmpty { null }
        }.toList()
        for (generation in generations.asReversed()) for (id in generation) {
            val tree = runtime.getActivityInstance(id) ?: continue
            val steps = leaves(tree).filter { it.activityType != "callActivity" }.map { it.activityName ?: it.activityId } +
                transitions(tree).map { it.activityName ?: it.activityId }
            steps.firstOrNull()?.let { return it }
        }
        return null
    }

    fun leaves(node: ActivityInstance): List<ActivityInstance> =
        if (node.childActivityInstances.isEmpty() && node.childTransitionInstances.isEmpty()) listOf(node)
        else node.childActivityInstances.flatMap(::leaves)

    fun transitions(node: ActivityInstance): List<TransitionInstance> =
        node.childTransitionInstances.toList() + node.childActivityInstances.flatMap(::transitions)
}

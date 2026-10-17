package com.codefrak.engine.board

import com.codefrak.engine.intake.ANSWER_TASKS
import com.codefrak.engine.proposal.PitchOption
import com.codefrak.engine.repos.HandledIssues
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.TaskService
import org.springframework.stereotype.Component

/** A ticket's running proposal flow, the options its last draft declared, and the wait it is parked on, if any. */
data class ProposalWait(val instance: String, val options: List<PitchOption>, val task: String?)

/**
 * A proposal ticket's options and whether its flow waits on a choice (engine/README.md, "Proposal"): the latest
 * proposal flow of the ticket, while it runs, its stored propose result's `options`, and its open answer task
 * (`user-task-answer-question` or `user-task-answer-decision`).
 */
@Component
class ProposalChoices(private val handled: HandledIssues, private val runtime: RuntimeService, private val tasks: TaskService) {
    /** The ticket's running proposal flow, or null when it has none (not a proposal, or it ended). */
    fun waiting(repo: String, n: Long): ProposalWait? {
        val row = handled.list(repo).lastOrNull { it.issue == n && it.flow == Pitches.FLOW && it.processInstance != null } ?: return null
        val id = row.processInstance!!
        runtime.createProcessInstanceQuery().processInstanceId(id).singleResult() ?: return null
        val options = Pitches.proposal(runCatching { runtime.getVariables(id) }.getOrDefault(emptyMap()))?.options.orEmpty()
        val task = tasks.createTaskQuery().processInstanceId(id).taskDefinitionKeyLike(ANSWER_TASKS).list().firstOrNull()
        return ProposalWait(id, options, task?.taskDefinitionKey)
    }

    companion object {
        const val DECISION = "user-task-answer-decision"
    }
}

package com.codefrak.engine.feature

import com.codefrak.engine.board.NeedKeys
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.hitlp.FlowWait
import com.codefrak.engine.hitlp.HitlpAsserted
import com.codefrak.engine.hitlp.HitlpFiling
import com.codefrak.engine.intake.AskWaits
import com.codefrak.engine.intake.IntakeQuestion
import com.codefrak.engine.intake.IntakeQuestions
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.SmithRunStats
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.workitems.Tickets
import com.codefrak.engine.workitems.commentOn
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.TaskService
import org.operaton.bpm.engine.variable.Variables
import org.springframework.stereotype.Component

/**
 * A child's question under a running live parent (#100049; engine/docs/feature.md, "Children ask the parent"). `post
 * the smith's question` ([PostFeatureQuestionDelegate]) [asks] the parent instead of the user when [LiveParents] finds
 * one: the `engine.intake_question` row carries `asked_of` `#<parent>`, the child is told, a `question` `asked` event
 * wakes the parent's await_children, and no HITLP request is filed. The parent's answer_child then either [answer]s it,
 * completing the child's user task as `HitlpAnswers.answer` does for a person (the row answered by `#<parent>`,
 * `answers` on the child's flow, `Parent #<n> answered:` on the child), or [escalate]s it: the row's `asked_of` is
 * cleared and the HITLP request filed exactly as today's path files it, so the user sees the same row. `note the live
 * parent ended` [escalateAll]s what still waits on the parent.
 */
@Component
class ParentQuestions(
    private val questions: IntakeQuestions,
    private val tickets: Tickets,
    private val tasks: TaskService,
    private val runtime: RuntimeService,
    private val events: EngineEvents,
    private val hitlp: HitlpFiling,
    private val runs: SmithRunStats,
) {
    companion object {
        private const val ANSWER_TASKS = "user-task-answer-%"

        /** `Parent #<n> answered:` as the child's comment opens; the `by` of the row and the brief's answer. */
        fun by(parent: Long) = "#$parent"

        /**
         * The answer as text, as `HitlpAnswers.answerText` writes a person's: an option of the question's `choice` ask
         * block, named by its value, as `option <id>: <label>`; anything else as it is.
         */
        fun answerText(question: String, answer: String): String {
            @Suppress("UNCHECKED_CAST")
            val options = AskWaits.askBlock(question)?.get("options") as? List<Map<String, Any?>> ?: return answer
            val option = options.firstOrNull { it["value"]?.toString() == answer.trim() } ?: return answer
            return "option ${option["value"]}: ${option["label"] ?: option["value"]}"
        }
    }

    /** Records [question] of child [issue]'s flow [id] as asked of [parent], tells the child, and wakes the parent. */
    fun ask(id: String, repo: String, issue: Long, mode: String, question: String, parent: LiveParents.LiveParent) {
        val nth = questions.list(id).size + 1
        questions.ask(id, repo, issue, question, askedOf = parent.by)
        tickets.commentOn(repo, issue, "The $mode Smith has a question ($nth) before it can go on, asked of its live parent #${parent.number}, " +
            "which answers it or hands it to you:\n\n${question.trimEnd()}\n\n— engine ${kindOf(id)} `$id`; the next run works from the ticket plus every answer.")
        events.publishAfterCommit(EngineEvent.Question(repo, issue, id, "asked", question, askedOf = parent.by))
    }

    /** The question of child [child] waiting on parent [parent] in [repo]; null when none waits. */
    fun waiting(repo: String, parent: Long, child: Long): IntakeQuestion? =
        questions.waitingOn(repo, by(parent)).firstOrNull { it.issue == child }

    /**
     * answer_child: [answer] lands on child [child]'s pending question as the parent's, and its flow goes on. Null when
     * no question of the child waits on parent [parent] (nothing changes); else the question answered.
     */
    fun answer(repo: String, parent: Long, child: Long, answer: String): IntakeQuestion? {
        val pending = waiting(repo, parent, child) ?: return null
        val id = pending.intake
        val task = tasks.createTaskQuery().processInstanceId(id).taskDefinitionKeyLike(ANSWER_TASKS).list().firstOrNull() ?: return null
        val text = answerText(pending.question, answer)
        if (!questions.answer(pending.id, text, by(parent))) return null
        tickets.commentOn(repo, child, "Parent #$parent answered:\n\n${text.trimEnd()}\n\n— engine ${kindOf(id)} `$id`, going on.")
        tasks.complete(task.id, mapOf<String, Any?>(Vars.ANSWERS to Variables.byteArrayValue(Brief.answersBytes(questions.answered(id)))))
        events.publishAfterCommit(EngineEvent.Question(repo, child, id, "answered", pending.question, askedOf = by(parent), by = by(parent)))
        return pending
    }

    /**
     * answer_child with `escalate`: child [child]'s pending question is handed to the user, the HITLP request and the
     * child's comment filed as today's path files them, with [note] (the parent's, or why the parent ended) on the
     * comment. Null when no question of the child waits on parent [parent]; else the question escalated.
     */
    fun escalate(repo: String, parent: Long, child: Long, note: String?): IntakeQuestion? {
        val pending = waiting(repo, parent, child) ?: return null
        val id = pending.intake
        val task = tasks.createTaskQuery().processInstanceId(id).taskDefinitionKeyLike(ANSWER_TASKS).list().firstOrNull() ?: return null
        val instance = runtime.createProcessInstanceQuery().processInstanceId(id).singleResult() ?: return null
        val vars = runtime.getVariables(id)
        val nth = questions.list(id).indexOfFirst { it.id == pending.id } + 1
        if (!questions.escalate(pending.id)) return null
        val runId = runCatching { runs.latestRunId(instance.rootProcessInstanceId) }.getOrNull() ?: id
        val filed = hitlp.ask(FlowWait(id, task.taskDefinitionKey, HitlpAsserted.Kind.QUESTION, repo, child, pending.question,
            AskWaits.askBlock(pending.question), NeedKeys.askerIn(vars), runId, nth = nth))
        runtime.setVariable(id, Vars.HITLP_TASK_ID, filed.handle.taskId)
        val mode = vars[Vars.MODE] as? String ?: "work"
        val why = note?.trim()?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
        tickets.commentOn(repo, child, "The $mode Smith has a question ($nth) before it can go on; its live parent #$parent handed it to you$why:\n\n" +
            "${pending.question.trimEnd()}\n\n— engine ${kindOf(id)} `$id`. ${IntakeQuestions.answerHint(filed.decisionUrl, filed.handle.taskId)}; " +
            "the next run works from the ticket plus every answer.")
        events.publishAfterCommit(EngineEvent.Needs(repo, "question", "added", child, id, forLogin = NeedKeys.askerIn(vars)))
        events.publishAfterCommit(EngineEvent.Question(repo, child, id, "escalated", pending.question, askedOf = by(parent), by = by(parent)))
        return pending
    }

    /** `note the live parent ended`: every question still waiting on parent [parent] goes to the user, with [why] the parent ended. Answers the children escalated. */
    fun escalateAll(repo: String, parent: Long, why: String): List<Long> =
        questions.waitingOn(repo, by(parent)).mapNotNull { escalate(repo, parent, it.issue, "the live parent session ended: $why")?.issue }

    private fun kindOf(id: String): String =
        runtime.createProcessInstanceQuery().processInstanceId(id).singleResult()?.processDefinitionId?.substringBefore(':') ?: "flow"
}

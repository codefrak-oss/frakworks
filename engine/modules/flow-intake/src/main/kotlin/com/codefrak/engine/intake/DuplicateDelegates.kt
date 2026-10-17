package com.codefrak.engine.intake

import com.codefrak.engine.board.OpenTickets
import com.codefrak.engine.hitlp.FlowWait
import com.codefrak.engine.hitlp.HitlpAsserted
import com.codefrak.engine.hitlp.HitlpFiling
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.jev.JevContext
import com.codefrak.engine.jev.JevDecider
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.issue
import com.codefrak.engine.smith.issueOrNull
import com.codefrak.engine.smith.repo
import com.codefrak.engine.workitems.Tickets
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * `task-check-duplicate` in bpmn/intake.bpmn, before the first `refine ask`: reads the intake repo's open tickets
 * from the board ([OpenTickets], `IssueBoard`) and asks Jev whether the ask is one of them ([DuplicateCheck]). Sets
 * `duplicateOf`, `duplicate` (the brief's line), `duplicateCall` and `duplicateQuestion` on a match at `engine.jev.duplicate-floor`
 * or above, and clears them otherwise; any failure (GitHub unreadable, Jev unreachable, over its limit, no jev
 * account) is no match, so the intake refines exactly as before. Bound by `${checkDuplicate}`.
 */
@Component("checkDuplicate")
class CheckDuplicateDelegate(
    private val board: OpenTickets,
    private val jev: JevDecider,
    private val calls: JevCalls,
    @Value("\${engine.jev.duplicate-floor:0.9}") private val floor: Double = 0.9,
) : JavaDelegate {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun execute(execution: DelegateExecution) {
        listOf(Vars.DUPLICATE_OF, Vars.DUPLICATE, Vars.DUPLICATE_CALL, Vars.DUPLICATE_QUESTION).forEach { execution.setVariable(it, null) }
        // A doctor intake skips it (engine/README.md, "Doctor"): the doctor has just asked the same question, and a
        // match would park an authorized intake on a human.
        if (execution.getVariable(AUTHORIZED_BY_ENGINE) != null) return
        val match = runCatching { check(execution) }
            .onFailure { log.warn("intake {}: duplicate check failed; refining as before: {}", execution.processInstanceId, it.message) }
            .getOrNull() ?: return
        execution.setVariable(Vars.DUPLICATE_OF, match.ticket.number)
        execution.setVariable(Vars.DUPLICATE, DuplicateCheck.briefLine(match))
        execution.setVariable(Vars.DUPLICATE_CALL, match.call)
        execution.setVariable(Vars.DUPLICATE_QUESTION, Brief.textValue(DuplicateCheck.question(match, execution.processInstanceId)))
        log.info("intake {}: jev matched the ask to #{} at {}", execution.processInstanceId, match.ticket.number, match.confidence)
    }

    private fun check(execution: DelegateExecution): DuplicateCheck.Match? {
        val repo = execution.repo()
        val issue = execution.issueOrNull()
        val ask = Brief.text(execution.getVariable("ask")) ?: return null
        val candidates = DuplicateCheck.candidates(board.openTickets(repo), issue)
        val context = JevContext(execution.processInstanceId, repo, issue)
        return DuplicateCheck.run(candidates, floor,
            ask = { batch, final ->
                val decision = jev.decide(DuplicateCheck.USE_CASE, context, DuplicateCheck.state(ask),
                    mapOf(DuplicateCheck.QUESTION to DuplicateCheck.question(batch)), if (final) floor else null)
                DuplicateCheck.Asked(decision.callId, decision.answers?.get(DuplicateCheck.QUESTION))
            },
            // The audit is not the decision: a failed write must not throw the pick away.
            act = { call, action ->
                runCatching { calls.setAction(call, action) }
                    .onFailure { log.warn("jev intake-duplicate {}: action not recorded: {}", call, it.message) }
            })
    }
}

/**
 * `task-post-duplicate` in bpmn/intake.bpmn, on a match: records the question "this looks like #n, is it the same
 * work?" in `engine.intake_question`, files it as a HITLP `human.ask` ([HitlpFiling.ask], [Vars.HITLP_TASK_ID]) and
 * comments it on the ask item; the intake then waits at `answer: is it a duplicate?` for the decision record
 * ([HitlpAnswers]), or for the user to cancel it. Bound by `${postDuplicate}`.
 */
@Component("postDuplicate")
class PostDuplicateDelegate(private val tickets: Tickets, private val questions: IntakeQuestions, private val hitlp: HitlpFiling) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val repo = execution.repo()
        val issue = execution.issue()
        val id = execution.processInstanceId
        val question = Brief.text(execution.getVariable(Vars.DUPLICATE_QUESTION))!!
        val nth = questions.list(id).size + 1
        questions.ask(id, repo, issue, question)
        val filed = hitlp.ask(FlowWait(id, AskWaits.answerTask(execution), HitlpAsserted.Kind.DECISION, repo, issue, question,
            AskWaits.askBlock(question), AskWaits.askedBy(execution, repo), nth = nth))
        execution.setVariable(Vars.HITLP_TASK_ID, filed.handle.taskId)
        tickets.commentOn(repo, issue, "Before refining this, a question:\n\n$question\n\n— engine intake `$id`. " +
            "${IntakeQuestions.answerHint(filed.decisionUrl, filed.handle.taskId)}; the refine run works from the ask plus your answer.")
    }
}

/**
 * `task-record-duplicate-answer` in bpmn/intake.bpmn, after the user answered the duplicate question: the answer
 * is the duplicate call's `outcome` (the user did not cancel, so the ask goes on to the refine). A cancel at that
 * question records its own outcome (`IntakeController.cancel`). Bound by `${recordDuplicateAnswer}`.
 */
@Component("recordDuplicateAnswer")
class RecordDuplicateAnswerDelegate(private val calls: JevCalls) : JavaDelegate {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun execute(execution: DelegateExecution) {
        val call = execution.getVariable(Vars.DUPLICATE_CALL) as? String ?: return
        val answer = Brief.answers(execution.getVariable(Vars.ANSWERS) as? ByteArray).lastOrNull() ?: return
        runCatching { calls.recordOutcome(call, "not cancelled; @${answer.by} answered: ${answer.answer.trim().take(OUTCOME_CHARS)}") }
            .onFailure { log.warn("jev intake-duplicate {}: outcome not recorded: {}", call, it.message) }
    }

    private companion object {
        const val OUTCOME_CHARS = 1000
    }
}

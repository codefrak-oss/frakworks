package com.codefrak.engine.proposal

import com.codefrak.engine.workitems.Tickets
import com.codefrak.engine.workitems.commentOn
import com.codefrak.engine.board.NeedKeys
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.hitlp.FlowWait
import com.codefrak.engine.hitlp.HitlpAsserted
import com.codefrak.engine.hitlp.HitlpFiling
import com.codefrak.engine.intake.IntakeQuestions
import com.codefrak.engine.intake.AskWaits
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.issue
import com.codefrak.engine.smith.repo
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.springframework.stereotype.Component

/**
 * `task-post-decision` in bpmn/proposal.bpmn, once the draft passed review and its PR is open: records the
 * decision as the question the flow waits on (a `decision` in `GET /needs`) and posts it on the ticket with
 * the PR, a link to the pitch page on the PR's branch (none for a draft from before pitches), the verbs and
 * an ask block. On a re-ask it first says why the last answer was not understood.
 * It is filed as a HITLP `human.ask` with the six verbs as its options ([HitlpFiling.ask], [Vars.HITLP_TASK_ID]). The
 * flow then waits at `user-task-answer-decision` for the decision record (`HitlpAnswers`).
 * Bound by `${postDecision}`.
 */
@Component("postDecision")
class PostDecisionDelegate(private val tickets: Tickets, private val questions: IntakeQuestions, private val events: EngineEvents, private val hitlp: HitlpFiling) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val repo = execution.repo(); val issue = execution.issue(); val id = execution.processInstanceId
        val proposal = execution.proposal()
        val unclear = if (execution.getVariable(Vars.DECISION) == DecisionVerb.UNCLEAR)
            Brief.answers(execution.getVariable(Vars.ANSWERS) as? ByteArray).lastOrNull()?.answer?.trim() else null
        val question = buildString {
            unclear?.let { append("Your last answer could not be read as one of the verbs below, so it was not understood: `${oneLine(it)}`\n\n") }
            append("The proposal is ready for your decision: ${execution.getVariable(Vars.PR_URL)} (`${proposal.path}`).\n\n")
            proposal.pitch?.let {
                val branch = execution.getVariable(Vars.BRANCH) as? String ?: "smith/issue-$issue"
                append("The pitch: https://github.com/$repo/blob/$branch/$it\n\n")
            }
            append("${proposal.summary!!.trim()}\n\n")
            append("Answer with one of:\n")
            append("- `accept`, or `accept: <why>`: log it, merge the doc, close this ticket;\n")
            append("- `accept and build`: as accept, and file a feature that builds it (confirm mode); `accept and build auto` for auto mode;\n")
            append("- `revise: <what to change>`: the doc is redrafted from your text and reviewed again;\n")
            append("- `shelve: <why>` or `reject: <why>`: log it with that status, merge the doc, close this ticket.\n\n")
            append("```ask\nkind: text\nquestion: Your decision on the proposal?\n```")
        }
        val nth = questions.list(id).size + 1
        questions.ask(id, repo, issue, question)
        val filed = hitlp.ask(FlowWait(id, AskWaits.answerTask(execution), HitlpAsserted.Kind.DECISION, repo, issue, question,
            mapOf("kind" to "proposal"), AskWaits.askedBy(execution, repo), nth = nth))
        execution.setVariable(Vars.HITLP_TASK_ID, filed.handle.taskId)
        tickets.commentOn(repo, issue, "$question\n\n${execution.signature()}. ${IntakeQuestions.answerHint(filed.decisionUrl, filed.handle.taskId)}.")
        // The decision is a row of `GET /needs` once this commits (engine/README.md, "Events"); its answer removes it.
        events.publishAfterCommit(EngineEvent.Needs(repo, "decision", "added", issue, id, forLogin = NeedKeys.askerIn(execution.variables)))
    }

    private fun oneLine(text: String) = text.replace(Regex("""\s+"""), " ").take(200)
}

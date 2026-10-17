package com.codefrak.engine.feature

import com.codefrak.engine.workitems.Tickets
import com.codefrak.engine.workitems.commentOn
import com.codefrak.engine.board.NeedKeys
import com.codefrak.engine.engineJson
import com.codefrak.engine.hitlp.FlowWait
import com.codefrak.engine.hitlp.HitlpAsserted
import com.codefrak.engine.hitlp.HitlpFiling
import com.codefrak.engine.intake.AskWaits
import com.codefrak.engine.intake.IntakeQuestions
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.jev.JevContext
import com.codefrak.engine.jev.JevDecider
import com.codefrak.engine.jev.JevQuestion
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.issue
import com.codefrak.engine.smith.repo
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.operaton.bpm.engine.variable.Variables
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Kept for feature instances started before #583 (engine/README.md, "Feature"):
 * the current bpmn/feature.bpmn has no user acceptance, and runs none of the three
 * acceptance delegates below; an instance on an older version of it still can.
 *
 * `task-post-acceptance` in that older feature.bpmn, confirm mode, after the merge:
 * acceptance is the user's. Posts the plan's criteria as a checklist, records
 * it as the question the feature waits on, filed as a HITLP `human.approve` ([fileAcceptance]), and the flow waits at
 * `user-task-answer-acceptance` for the decision record or the user's answer. Bound by `${postAcceptance}`.
 */
@Component("postAcceptance")
class PostAcceptanceDelegate(private val tickets: Tickets, private val questions: IntakeQuestions, private val hitlp: HitlpFiling) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val plan = DesignResult.plan(execution.getVariable(Vars.PLAN) as? ByteArray ?: error("no plan to take the criteria from"))
        val question = buildString {
            append("${execution.mergedText()}. This feature runs in confirm mode, so acceptance is yours: ")
            append("check each criterion on the default branch and answer.\n\n")
            plan.acceptance.forEach { append("- [ ] ").append(it.trim()).append('\n') }
            append("\nAnswer `accepted` to close it. Anything else is read as not accepted: say what is not met, and the ticket stays open with your answer on it.\n\n")
            append("```ask\nkind: text\nquestion: Do the acceptance criteria hold on the default branch?\n```")
        }
        fileAcceptance(execution, questions, tickets, hitlp, question)
    }
}

/**
 * Records [question] as the question the legacy acceptance waits on, files it as a HITLP `human.approve` (#2467:
 * `payload {repo, pr, head}`, head from [Vars.HEAD_SHA]), keeps the task id in [Vars.HITLP_TASK_ID] and posts the
 * question on the ticket with the hint where to decide it.
 */
internal fun fileAcceptance(execution: DelegateExecution, questions: IntakeQuestions, tickets: Tickets, hitlp: HitlpFiling, question: String) {
    val repo = execution.repo(); val issue = execution.issue(); val id = execution.processInstanceId
    val nth = questions.list(id).size + 1
    questions.ask(id, repo, issue, question)
    val filed = hitlp.approve(FlowWait(id, NeedKeys.ACCEPTANCE_TASK, HitlpAsserted.Kind.ACCEPTANCE, repo, issue, question,
        askedBy = AskWaits.askedBy(execution, repo), pr = (execution.getVariable(Vars.PR) as Number).toLong(),
        head = execution.getVariable(Vars.HEAD_SHA) as? String, nth = nth))
    execution.setVariable(Vars.HITLP_TASK_ID, filed.handle.taskId)
    tickets.commentOn(repo, issue, "$question\n\n— engine feature `$id`. ${IntakeQuestions.answerHint(filed.decisionUrl, filed.handle.taskId)}.")
}

/**
 * `task-read-acceptance`, before #583 (see [PostAcceptanceDelegate]): turns the user's answer into the flow's `outcome`,
 * `accepted` or `rejected`, and keeps it as the acceptance record the close or
 * the report quotes. An answer [ACCEPTED] does not match is put to Jev
 * (engine/README.md, "Feature"): does the user accept the work, given the
 * checklist? At a `noul` of `engine.jev.acceptance-threshold` or more the
 * outcome is `reask` instead, and the flow asks "Did you mean accepted?"
 * (`${postAcceptanceReask}`). The answer to that is read by [ACCEPTED] alone
 * and becomes the Jev call's `outcome`, so an acceptance is asked again at
 * most once, and Jev can never close a ticket. Jev giving no answer, or less,
 * leaves the rule's `rejected`. A HITLP decision record ([Vars.HITLP_RECORD], #2467) is read first and asks no Jev:
 * `approved` is `accepted`, anything else `rejected` with the record's `reason`; the answer text is read only when
 * there is none (the engine's own answer path, or an instance from before #2467). Bound by `${readAcceptance}`.
 */
@Component("readAcceptance")
class ReadAcceptanceDelegate(
    private val jev: JevDecider,
    private val calls: JevCalls,
    @Value("\${engine.jev.acceptance-threshold:0.9}") private val threshold: Double = 0.9,
) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val record = (execution.getVariable(Vars.HITLP_RECORD) as? ByteArray)?.let { engineJson.readValue(it, Map::class.java) }
            ?.takeIf { it["primitive"] == "approve" }
        execution.removeVariable(Vars.HITLP_RECORD)
        if (record != null) return fromRecord(execution, record)
        val answer = Brief.answers(execution.getVariable(Vars.ANSWERS) as? ByteArray).lastOrNull() ?: error("no answer to read")
        val text = answer.answer.trim()
        val accepted = ACCEPTED.containsMatchIn(text)
        val reasked = execution.getVariable(JEV_CALL) as? String
        val outcome = when {
            reasked != null -> {
                calls.recordOutcome(reasked, "${if (accepted) "confirmed accepted" else "not accepted"}: @${answer.by}: $text")
                if (accepted) "accepted" else "rejected"
            }
            accepted -> "accepted"
            else -> askJev(execution, text)
        }
        if (outcome != "reask") execution.setVariable(Vars.ACCEPTANCE, Variables.byteArrayValue(AcceptResult.bytes(AcceptResult(outcome, "@${answer.by}: $text"))))
        execution.setVariable("outcome", outcome)
    }

    /** The outcome of an approve [record]: `accepted` on `approved`, else `rejected`, quoting the decider and the reason. */
    private fun fromRecord(execution: DelegateExecution, record: Map<*, *>) {
        val outcome = if (record["outcome"] == "approved") "accepted" else "rejected"
        val by = record["decidedBy"] as? Map<*, *>
        val who = by?.get("id")?.toString()?.takeIf { it.isNotBlank() } ?: "unknown"
        val decider = if (by?.get("type") == null || by["type"] == "human") "@$who" else "${by["type"]} $who"
        val text = "${record["outcome"]}" + ((record["reason"] as? String)?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
        (execution.getVariable(JEV_CALL) as? String)?.let { calls.recordOutcome(it, "${if (outcome == "accepted") "confirmed accepted" else "not accepted"}: $decider: $text") }
        execution.setVariable(Vars.ACCEPTANCE, Variables.byteArrayValue(AcceptResult.bytes(AcceptResult(outcome, "$decider: $text"))))
        execution.setVariable("outcome", outcome)
    }

    /** `reask` when Jev reads `text` as acceptance at the threshold or above, else `rejected`; the call's action says which. */
    private fun askJev(execution: DelegateExecution, text: String): String {
        val plan = DesignResult.plan(execution.getVariable(Vars.PLAN) as? ByteArray ?: error("no plan to take the criteria from"))
        val decision = runCatching {
            jev.decide("acceptance", JevContext(execution.processInstanceId, execution.repo(), execution.issue()),
                mapOf("checklist" to plan.acceptance.map { it.trim() }, "answer" to text), mapOf(QUESTION to ACCEPTS), threshold)
        }.onFailure { log.warn("jev acceptance call failed; keeping the rule's reading: {}", it.message) }.getOrNull()
            ?: return "rejected"
        val noul = decision.answers?.get(QUESTION)?.noul
        return when {
            noul == null -> "rejected".also { calls.setAction(decision.callId, "no answer, rule") }
            noul >= threshold -> "reask".also {
                calls.setAction(decision.callId, "re-asked")
                execution.setVariable(JEV_CALL, decision.callId)
            }
            else -> "rejected".also { calls.setAction(decision.callId, "kept rejection") }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ReadAcceptanceDelegate::class.java)

        /** An answer that accepts: one of these words first, whatever follows. */
        val ACCEPTED = Regex("""(?i)^\W*(accepted?|yes|ok|okay|lgtm|done|all met|approved?)\b""")

        /** The process variable holding the `engine.jev_call` id of the call that led to a re-ask. */
        const val JEV_CALL = "acceptanceJevCall"

        const val QUESTION = "accepts"
        val ACCEPTS = JevQuestion.noul(
            "The `answer` is the user's reply when asked to check the `checklist` of acceptance criteria on the merged work " +
                "and accept it. Does the user accept the work as it stands?",
            whenTrue = "The user accepts it: approves, says it is good, done or fine to ship, or that the criteria hold",
            whenFalse = "The user does not accept it: names something unmet or wrong, asks for changes, or is unsure",
        )
    }
}

/**
 * `task-post-acceptance-reask`, before #583 (see [PostAcceptanceDelegate]): the rule read the user's acceptance answer as
 * not accepted, but Jev read it as acceptance. Quotes the answer, lists the
 * checklist again and asks "Did you mean accepted?", recorded as the question
 * the feature waits on; the flow waits at `user-task-answer-acceptance`
 * again. Bound by `${postAcceptanceReask}`.
 */
@Component("postAcceptanceReask")
class PostAcceptanceReaskDelegate(private val tickets: Tickets, private val questions: IntakeQuestions, private val hitlp: HitlpFiling) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val plan = DesignResult.plan(execution.getVariable(Vars.PLAN) as? ByteArray ?: error("no plan to take the criteria from"))
        val answer = Brief.answers(execution.getVariable(Vars.ANSWERS) as? ByteArray).lastOrNull() ?: error("no answer to ask about")
        val question = buildString {
            append("@${answer.by} answered:\n\n")
            answer.answer.trim().lines().forEach { append("> ").append(it).append('\n') }
            append("\nThe engine reads only an answer that starts with `accepted` (or yes, ok, lgtm, done, approved) as acceptance, ")
            append("but this one reads like one. The criteria:\n\n")
            plan.acceptance.forEach { append("- [ ] ").append(it.trim()).append('\n') }
            append("\nDid you mean accepted? Answer `accepted` to close it; anything else leaves it open with your answer on it.\n\n")
            append("```ask\nkind: text\nquestion: Did you mean accepted?\n```")
        }
        fileAcceptance(execution, questions, tickets, hitlp, question)
    }
}

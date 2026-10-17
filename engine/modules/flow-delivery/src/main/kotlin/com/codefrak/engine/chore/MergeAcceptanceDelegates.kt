package com.codefrak.engine.chore

import com.codefrak.engine.board.NeedKeys
import com.codefrak.engine.engineJson
import com.codefrak.engine.hitlp.FlowWait
import com.codefrak.engine.hitlp.HitlpAsserted
import com.codefrak.engine.hitlp.HitlpFiling
import com.codefrak.engine.intake.AskWaits
import com.codefrak.engine.intake.IntakeQuestions
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.Finding
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.issue
import com.codefrak.engine.smith.repo
import com.codefrak.engine.smith.round
import com.codefrak.engine.workitems.Tickets
import com.codefrak.engine.workitems.commentOn
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.operaton.bpm.engine.variable.Variables
import org.springframework.stereotype.Component

/**
 * `task-post-merge-acceptance` in bpmn/chore.bpmn, bug.bpmn and feature.bpmn: the branch changes the hitlp module or
 * its migrations ([HitlpPaths], `hitlpChange`), so the approved, up-to-date PR waits for the user before the merge
 * (engine/README.md, "Hitlp changes wait for the user"). Records the question under the flow's instance id and posts
 * it on the ticket, filed as a HITLP `human.approve` ([HitlpFiling.approve], `payload {repo, pr, head}` with the pushed
 * [Vars.HEAD_SHA], its task id in [Vars.HITLP_TASK_ID]); the flow waits at `user-task-answer-merge`, a `GET /needs`
 * approve row, for the decision record (`HitlpAnswers`). Bound by `${postMergeAcceptance}`.
 */
@Component("postMergeAcceptance")
class PostMergeAcceptanceDelegate(
    private val tickets: Tickets,
    private val questions: IntakeQuestions,
    private val hitlp: HitlpFiling,
) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val repo = execution.repo(); val issue = execution.issue(); val id = execution.processInstanceId
        val number = (execution.getVariable(Vars.PR) as Number).toLong()
        val pr = execution.getVariable(Vars.PR_URL) as? String ?: "#$number"
        val question = question(pr, execution.getVariable(Vars.POST_DEPLOY_BLOCKED) as? String,
            execution.getVariable(Vars.HITLP_CHANGE) == true)
        val nth = questions.list(id).size + 1
        questions.ask(id, repo, issue, question)
        val filed = hitlp.approve(FlowWait(id, NeedKeys.MERGE_ACCEPTANCE_TASK, HitlpAsserted.Kind.MERGE, repo, issue, question,
            askedBy = AskWaits.askedBy(execution, repo), pr = number, head = execution.getVariable(Vars.HEAD_SHA) as? String, nth = nth))
        execution.setVariable(Vars.HITLP_TASK_ID, filed.handle.taskId)
        tickets.commentOn(repo, issue, "$question\n\n— engine ${execution.flow()} `$id`. ${IntakeQuestions.answerHint(filed.decisionUrl, filed.handle.taskId)}.")
    }

    companion object {
        /**
         * The question for [pr]: a hitlp change, or a merge [blocked] by its post-deployment check (the reason,
         * [Vars.POST_DEPLOY_BLOCKED]); a blocked merge that is also a [hitlp] change says both, since one accept
         * lets it through both gates.
         */
        fun question(pr: String, blocked: String? = null, hitlp: Boolean = true) = buildString {
            if (blocked == null) {
                append("$pr is approved and up to date, but it changes `${HitlpPaths.MODULE}**` or a hitlp migration, ")
                append("so it merges only once you accept it, whatever the review said.\n\n")
                append("Answer `accept` to merge it. Anything else sends it back to the work with your answer as a finding.\n\n")
                append("```ask\nkind: text\nquestion: Merge this hitlp change?\n```")
                return@buildString
            }
            append("$pr is approved and up to date, but it does not merge yet: $blocked\n\n")
            if (hitlp) append("It also changes `${HitlpPaths.MODULE}**` or a hitlp migration, so it merges only once you accept it, whatever the review said.\n\n")
            append("Answer `accept` once that is fixed: the engine checks again and merges it. Anything else sends it back to the work with your answer as a finding.\n\n")
            append("```ask\nkind: text\nquestion: Merge it now?\n```")
        }
    }
}

/**
 * `task-read-merge-acceptance` (see [PostMergeAcceptanceDelegate]): sets `mergeAccepted` from the HITLP decision record
 * ([Vars.HITLP_RECORD], set by `HitlpAnswers`): true on `approved`. With no record (answered on the engine's own path,
 * or an instance from before #2467) it reads the user's answer, true when it starts with [ACCEPT]. Open/update pr
 * clears it at every push, so the accepted head is the one merged. Anything else is a `merge-<round>-1` finding with
 * the user's words (a record's `reason`), and the flow goes back to the work. The record is removed once read.
 * Bound by `${readMergeAcceptance}`.
 */
@Component("readMergeAcceptance")
class ReadMergeAcceptanceDelegate : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val record = (execution.getVariable(Vars.HITLP_RECORD) as? ByteArray)?.let { engineJson.readValue(it, Map::class.java) }
            ?.takeIf { it["primitive"] == "approve" }
        execution.removeVariable(Vars.HITLP_RECORD) // read once: the next round's acceptance is its own
        val (accepted, why) = if (record != null) {
            (record["outcome"] == "approved") to "${decider(record)} did not accept merging this hitlp change (${record["outcome"]})" +
                ((record["reason"] as? String)?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
        } else {
            val answer = Brief.answers(execution.getVariable(Vars.ANSWERS) as? ByteArray).lastOrNull() ?: error("no answer to read")
            val text = answer.answer.trim()
            ACCEPT.containsMatchIn(text) to "@${answer.by} did not accept merging this hitlp change: $text"
        }
        execution.setVariable(Vars.MERGE_ACCEPTED, accepted)
        if (accepted) return
        val finding = Finding(why, "high",
            id = "merge-${execution.round()}-1", review = "user")
        val raised = Brief.findings(execution.getVariable(Vars.RAISED) as? ByteArray) + finding
        execution.setVariable(Vars.FINDINGS, Variables.byteArrayValue(Brief.findingsBytes(listOf(finding))))
        execution.setVariable(Vars.RAISED, Variables.byteArrayValue(Brief.findingsBytes(raised)))
    }

    companion object {
        /** An answer that accepts: one of these words first, whatever follows. */
        val ACCEPT = Regex("""(?i)^\W*(accept(ed)?|yes|approved?|lgtm)\b""")

        /** Who decided an approve [record]: `@<id>` for a person, else `<type> <id>`. */
        fun decider(record: Map<*, *>): String {
            val by = record["decidedBy"] as? Map<*, *>
            val id = by?.get("id")?.toString()?.takeIf { it.isNotBlank() } ?: "unknown"
            return if (by?.get("type") == "human" || by?.get("type") == null) "@$id" else "${by["type"]} $id"
        }
    }
}

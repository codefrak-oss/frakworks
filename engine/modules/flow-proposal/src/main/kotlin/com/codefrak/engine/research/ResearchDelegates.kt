package com.codefrak.engine.research

import com.codefrak.engine.workitems.commentOn
import com.codefrak.engine.engineJson
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.github.GitHubPulls
import com.codefrak.engine.github.GitPush
import com.codefrak.engine.hitlp.FlowWait
import com.codefrak.engine.hitlp.HitlpAsserted
import com.codefrak.engine.hitlp.HitlpFiling
import com.codefrak.engine.intake.IntakeQuestions
import com.codefrak.engine.intake.AskWaits
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.jev.JevContext
import com.codefrak.engine.jev.JevDecider
import com.codefrak.engine.jev.JevQuestion
import com.codefrak.engine.proposal.DECIDED_BY
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.issue
import com.codefrak.engine.smith.repo
import com.codefrak.engine.workitems.TicketState
import com.codefrak.engine.workitems.Tickets
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.operaton.bpm.engine.variable.Variables
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.LocalDate

private fun DelegateExecution.research() =
    ResearchResult.parse(getVariable(Vars.RESEARCH) as? ByteArray ?: error("no research answer"))
private fun DelegateExecution.round() = (getVariable(Vars.RESEARCH_ROUND) as? Number)?.toInt() ?: 0
private fun DelegateExecution.signature() = "— engine research `$processInstanceId`"

/**
 * `task-keep-answer` in bpmn/research.bpmn: the round is the next one; the answer pair goes into
 * `engine.research_answer` ([ResearchAnswers.keep], an upsert, so a retry changes nothing twice) and the
 * thread variable the next brief carries, and the summary is commented on the ticket with the round.
 * Nothing is pushed. Bound by `${keepAnswer}`.
 */
@Component("keepAnswer")
class KeepAnswerDelegate(private val answers: ResearchAnswers, private val tickets: Tickets) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val repo = execution.repo(); val issue = execution.issue()
        val result = execution.research()
        check(result.outcome == "answered") { "the last research run did not answer (${result.outcome})" }
        val round = execution.round() + 1
        val summary = result.summary!!.trim()
        val answer = result.answer!!
        val md = answer.md!!
        answers.keep(repo, issue, round, execution.processInstanceId, summary, md, answer.html!!)
        val thread = researchThread(execution.getVariable(Vars.RESEARCH_THREAD) as? ByteArray).filter { it.round != round } +
            ResearchRound(round, summary, md)
        execution.setVariable(Vars.RESEARCH_ROUND, round)
        execution.setVariable(Vars.RESEARCH_THREAD, Variables.byteArrayValue(engineJson.writeValueAsBytes(thread)))
        execution.setVariable(Vars.SAVED, null)
        execution.setVariable(Vars.SAVE_REFUSED, null)
        tickets.commentOn(repo, issue, "Answer, round $round: $summary\n\nThe page is kept by the engine (the ticket's Answer section in the desktop, " +
            "`GET /ticket/$repo/$issue/pitch`); nothing is committed unless you say `save`.\n\n${execution.signature()}")
    }
}

/**
 * `task-post-ack`: records the ack as the question the flow waits on (a `decision` in `GET /needs`) and posts
 * it on the ticket with the summary, the round and the verbs. After a refused `save` path it first says why.
 * It is filed as a HITLP `human.ask` with `accept`, `save` and `drop` as its options ([HitlpFiling.ask],
 * [Vars.HITLP_TASK_ID]). The flow then waits at `user-task-answer-decision` for the decision record (`HitlpAnswers`).
 * Bound by `${postAck}`.
 */
@Component("postAck")
class PostAckDelegate(private val tickets: Tickets, private val questions: IntakeQuestions, private val events: EngineEvents, private val hitlp: HitlpFiling) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val repo = execution.repo(); val issue = execution.issue(); val id = execution.processInstanceId
        val round = execution.round()
        val summary = execution.research().summary!!.trim()
        val refused = execution.getVariable(Vars.SAVE_REFUSED) as? String
        val question = buildString {
            refused?.let { append("$it\n\n") }
            append("Answer ready (round $round): $summary\n\n")
            append("Answer with one of:\n")
            append("- `accept`, or `accept: <why>`: the answer stands; this ticket closes as completed;\n")
            append("- `save`, or `save: <directory>`: as accept, and the engine commits answer.md and answer.html to the default branch, " +
                "under `${SaveAnswerDelegate.DEFAULT_DIR}` or the directory given (under `${SaveAnswerDelegate.DEFAULT_DIR}` or `docs/`);\n")
            append("- `drop`, or `drop: <why>`: this ticket closes as not planned;\n")
            append("- anything else is your follow-up question: the Smith answers it in round ${round + 1}, with the whole thread.\n\n")
            append("```ask\nkind: text\nquestion: Accept, save, drop, or ask a follow-up?\n```")
        }
        val nth = questions.list(id).size + 1
        questions.ask(id, repo, issue, question)
        val filed = hitlp.ask(FlowWait(id, AskWaits.answerTask(execution), HitlpAsserted.Kind.ACK, repo, issue, question,
            mapOf("kind" to "research"), AskWaits.askedBy(execution, repo), nth = nth))
        execution.setVariable(Vars.HITLP_TASK_ID, filed.handle.taskId)
        tickets.commentOn(repo, issue, "$question\n\n${execution.signature()}. ${IntakeQuestions.answerHint(filed.decisionUrl, filed.handle.taskId)}.")
        execution.setVariable(Vars.SAVE_REFUSED, null)
        // The ack is a `decision` row of `GET /needs` once this commits (engine/README.md, "Events"); its answer removes it.
        events.publishAfterCommit(EngineEvent.Needs(repo, "decision", "added", issue, id))
    }
}

/**
 * `task-read-ack`: the asker's last answer, read by Jev as a `choice` over the four verbs (use case
 * `research-decision`; the state being the answer, the summary and the round) and resolved by [AckVerb.read]:
 * a pick at `engine.jev.decision-floor` or above is taken; below it, with no answer, or when the call fails,
 * the rule reads it, and the rule's fallback is `follow-up`, never a re-ask. Sets `decision` for the gateway,
 * `decisionWhy` (the why, the directory, or the follow-up) and `decidedBy`. Bound by `${readAck}`.
 */
@Component("readAck")
class ReadAckDelegate(
    private val jev: JevDecider,
    private val calls: JevCalls,
    @Value("\${engine.jev.decision-floor:0.8}") private val floor: Double = 0.8,
) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val answer = Brief.answers(execution.getVariable(Vars.ANSWERS) as? ByteArray).lastOrNull() ?: error("no answer to read")
        val text = answer.answer.trim()
        val call = runCatching {
            jev.decide(USE_CASE, JevContext(execution.processInstanceId, execution.repo(), execution.issue()),
                mapOf("answer" to text, "summary" to execution.research().summary?.trim(), "round" to execution.round()), mapOf(QUESTION to VERBS), floor)
        }.onFailure { log.warn("jev $USE_CASE call failed; keeping the rule's reading: {}", it.message) }.getOrNull()
        val picked = call?.answers?.get(QUESTION)
        val pick = picked?.choice?.let { c -> AckVerb.WRITTEN.entries.firstOrNull { it.value == c }?.key }
        val reading = AckVerb.read(text, pick, picked?.confidence, floor)
        call?.let {
            calls.setAction(it.callId, reading.action)
            reading.outcome?.let { o -> calls.recordOutcome(it.callId, o) }
        }
        execution.setVariable(Vars.DECISION, reading.ack.verb)
        execution.setVariable(Vars.DECISION_WHY, reading.ack.text)
        execution.setVariable(DECIDED_BY, answer.by)
        log.info("research {}: ack read as {} ({})", execution.processInstanceId, reading.ack.verb, reading.action)
    }

    companion object {
        private val log = LoggerFactory.getLogger(ReadAckDelegate::class.java)
        const val USE_CASE = "research-decision"
        const val QUESTION = "decision"
        val VERBS = JevQuestion.choice(
            "The `answer` is the user's reply when asked to ack a research answer (its `summary`, at `round`). " +
                "Which decision does the answer give? Anything that asks, questions or adds to the answer is a follow-up.",
            mapOf(
                "accept" to "The answer stands as it is: close the ticket, keep nothing in the repo",
                "save" to "Accept it and commit the answer to the repo, maybe naming a directory",
                "drop" to "Drop it: close the ticket as not planned",
                "follow-up" to "A follow-up question or request for the Smith: another round answers it",
            ),
        )
    }
}

/**
 * `task-save-answer`, for `save[: <dir>]`: one commit by the engine's own identity on the default branch adding
 * `<dir>/<yyyy-mm-dd>-<slug>.md` and `.html` ([GitPush.commitFiles]), `dir` being the answer's text or
 * [DEFAULT_DIR], which must be under [DEFAULT_DIR] or `docs/`. A path outside them is refused with a comment,
 * `saved` is false and the flow asks for the ack again. Bound by `${saveAnswer}`.
 */
@Component("saveAnswer")
class SaveAnswerDelegate(private val git: GitPush, private val pulls: GitHubPulls, private val tickets: Tickets) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val repo = execution.repo(); val issue = execution.issue()
        val given = execution.getVariable(Vars.DECISION_WHY) as? String
        val dir = directory(given)
        if (dir == null) {
            val why = "Not saved: `${given?.trim()}` is not a directory under `$DEFAULT_DIR` or `docs/`, the only places a research answer may land."
            tickets.commentOn(repo, issue, "$why\n\n${execution.signature()}")
            execution.setVariable(Vars.SAVE_REFUSED, why)
            execution.setVariable(Vars.SAVED, false)
            return
        }
        val result = execution.research()
        val title = (execution.getVariable(Vars.TICKET) as? ByteArray)?.let { Brief.ticket(it).title } ?: "research-$issue"
        val base = "$dir${LocalDate.now()}-${slug(title)}"
        val files = result.answer!!.let { linkedMapOf("$base.md" to it.md!!, "$base.html" to it.html!!) }
        val default = pulls.defaultBranch(repo)
        val commit = git.commitFiles(repo, default, default, files, "Research #$issue: $title")
        execution.setVariable(Vars.SAVE_COMMIT, commit)
        execution.setVariable(Vars.SAVED, true)
        tickets.commentOn(repo, issue, "Saved as `$base.md` and `$base.html`: https://github.com/$repo/commit/$commit\n\n${execution.signature()}")
    }

    companion object {
        const val DEFAULT_DIR = "bots/research/"
        private val ALLOWED = listOf(DEFAULT_DIR, "docs/")

        /** The directory to save under, with one trailing slash, or null when `given` is outside [ALLOWED] (or climbs). */
        fun directory(given: String?): String? {
            val text = given?.trim()?.trim('`', '"', '\'')?.trimEnd('/')?.removePrefix("./").orEmpty()
            if (text.isEmpty()) return DEFAULT_DIR
            val dir = "$text/"
            if (dir.split('/').any { it == ".." } || dir.startsWith("/")) return null
            return dir.takeIf { d -> ALLOWED.any { d == it || d.startsWith(it) } }
        }

        /** A kebab-case slug of `title`, at most 60 characters, never empty. */
        fun slug(title: String): String =
            title.lowercase().replace(Regex("""[^a-z0-9]+"""), "-").trim('-').take(60).trimEnd('-').ifEmpty { "answer" }
    }
}

/**
 * `task-close-accepted` and `task-close-dropped`: comments the decision (and the commit, after a `save`) on
 * the ticket and closes it, completed for `accept` and `save`, not planned for `drop`. Bound by `${closeResearch}`.
 */
@Component("closeResearch")
class CloseResearchDelegate(private val tickets: Tickets) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val repo = execution.repo(); val issue = execution.issue()
        val verb = execution.getVariable(Vars.DECISION) as? String ?: error("no decision to close on")
        val why = (execution.getVariable(Vars.DECISION_WHY) as? String)?.takeIf { verb != AckVerb.SAVE }?.let { " — $it" }.orEmpty()
        val saved = (execution.getVariable(Vars.SAVE_COMMIT) as? String)?.let { "; saved in https://github.com/$repo/commit/$it" }.orEmpty()
        val status = when (verb) { AckVerb.ACCEPT, AckVerb.SAVE -> "accepted"; AckVerb.DROP -> "dropped"; else -> error("'$verb' closes nothing") }
        tickets.commentOn(repo, issue, "Decided: $status$why$saved, after ${execution.round()} round(s). " +
            "The answer stays readable in the ticket's Answer section.\n\n${execution.signature()}")
        tickets.end(repo, issue, if (verb == AckVerb.DROP) TicketState.CANCELLED else TicketState.DONE, status)
    }
}

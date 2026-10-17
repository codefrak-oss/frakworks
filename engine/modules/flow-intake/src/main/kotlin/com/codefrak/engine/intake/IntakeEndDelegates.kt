package com.codefrak.engine.intake

import com.codefrak.engine.board.NeedKeys
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.hitlp.FlowWait
import com.codefrak.engine.hitlp.HitlpAsserted
import com.codefrak.engine.hitlp.HitlpFiling
import com.codefrak.engine.smith.SmithRunStats
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.issue
import com.codefrak.engine.smith.repo
import com.codefrak.engine.workitems.TicketState
import com.codefrak.engine.workitems.Tickets
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * `task-post-question` in bpmn/intake.bpmn, for the `ask` outcome: records the
 * Smith's question in `engine.intake_question` and records it on the intake's ask item
 * as written (prose, then its `ask` block), filed as a HITLP `human.ask` ([HitlpFiling.ask], its task id in
 * [Vars.HITLP_TASK_ID]) whose decision record [HitlpAnswers] completes the wait with. The intake then waits at `answer
 * the smith's question` for that record. With no HITLP server
 * configured the filing throws, and the job (`asyncBefore`) becomes an incident naming it. Bound by `${postQuestion}`.
 */
@Component("postQuestion")
class PostQuestionDelegate(
    private val tickets: Tickets,
    private val questions: IntakeQuestions,
    private val events: EngineEvents,
    private val hitlp: HitlpFiling,
    private val runs: SmithRunStats,
) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val question = RefineResult.of(execution).question ?: error("no question in the smith's result")
        val repo = execution.repo()
        val issue = execution.issue()
        val id = execution.processInstanceId
        val nth = questions.list(id).size + 1
        questions.ask(id, repo, issue, question)
        val filed = hitlp.ask(FlowWait(id, AskWaits.answerTask(execution), HitlpAsserted.Kind.QUESTION, repo, issue, question,
            AskWaits.askBlock(question), NeedKeys.askerIn(execution.variables), AskWaits.runId(execution, runs), nth = nth))
        execution.setVariable(Vars.HITLP_TASK_ID, filed.handle.taskId)
        tickets.commentOn(repo, issue, "The refiner has a question ($nth) before it can file this:\n\n" + question.trimEnd() +
            "\n\n— engine intake `$id`. ${IntakeQuestions.answerHint(filed.decisionUrl, filed.handle.taskId)}; the next refine run works from the ask plus every answer.")
        // The question is a row of `GET /needs` once this commits (engine/README.md, "Events").
        events.publishAfterCommit(EngineEvent.Intake(repo, id, "question", issue, question = question))
        events.publishAfterCommit(EngineEvent.Needs(repo, "question", "added", issue, id, forLogin = NeedKeys.askerIn(execution.variables)))
    }
}

/**
 * `task-close-intake`: says what was filed, on the intake's ask item (engine/docs/intake.md; `issue` is its ticket
 * number, so the entry and the close go to the item, never to GitHub). An ask refined in place into the ticket
 * (`ticket == issue`, the intake's own repo) stays open: the ticket is that item. One that filed elsewhere (a ticket in
 * another target repo, [TICKET_REPO], or a ticket filed unlabeled for triage) is closed, naming the ticket
 * (`owner/name#n` when it is in another repo). Bound by `${closeIntake}`.
 */
@Component("closeIntake")
class CloseIntakeDelegate(private val tickets: Tickets, private val events: EngineEvents) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val repo = execution.repo()
        val issue = execution.issue()
        val ticket = execution.getVariable("ticket")
        val kind = execution.getVariable("kind")
        val outcome = execution.getVariable("outcome")
        val ticketRepo = execution.ticketRepo()
        if ((ticket as? Number)?.toLong() == issue && ticketRepo == repo) {
            val started = if (execution.getVariable("startedFlow") != null) "its $kind flow started" else "its $kind flow has not started"
            tickets.commentOn(repo, issue, "The ask above was refined into this ${kind ?: "ticket"} (outcome: $outcome); $started.\n\n" +
                "— engine intake `${execution.processInstanceId}`")
        } else {
            val started = execution.getVariable("startedFlow")?.let { ", and its $kind started" }.orEmpty()
            val filed = if (ticketRepo == repo) "#$ticket" else "$ticketRepo#$ticket"
            tickets.commentOn(repo, issue, "Done. Filed $filed from this ask (outcome: $outcome)$started.\n\n" +
                "— engine intake `${execution.processInstanceId}`")
            tickets.end(repo, issue, TicketState.DONE, "filed $filed")
        }
        events.publishAfterCommit(EngineEvent.Intake(repo, execution.processInstanceId, "ended", issue, ticket = (ticket as? Number)?.toLong()))
    }
}

/**
 * `task-close-withdrawn`, for the `withdrawn` outcome: the user's answer withdrew the ask (dropped it, or named a ticket
 * that already covers it). Quotes the refiner's reason on the ask item and closes it as not planned; nothing is filed,
 * nothing starts, and no `ticket`, `kind` or `startedFlow` is set. Bound by `${closeWithdrawn}`.
 */
@Component("closeWithdrawn")
class CloseWithdrawnDelegate(private val tickets: Tickets, private val events: EngineEvents) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val repo = execution.repo()
        val issue = execution.issue()
        val reason = RefineResult.of(execution).reason?.trim().orEmpty()
        tickets.commentOn(repo, issue, "Withdrawn: nothing filed from this ask.\n\n" + reason.lines().joinToString("\n") { "> $it" } +
            "\n\n— engine intake `${execution.processInstanceId}`")
        tickets.end(repo, issue, TicketState.CANCELLED, "withdrawn by its answer")
        events.publishAfterCommit(EngineEvent.Intake(repo, execution.processInstanceId, "ended", issue))
    }
}

/**
 * `task-notify-session`, after `closeIntake`: when the ask came with `notifySession` (the submitting Claude Code
 * session's name) and the intake filed a real ticket, queues one `engine.intake_notification` row for the
 * host-side watcher to push into that session with `claude -p` (engine/README.md, "Intake", "Telling the
 * session that filed it"): a ticket filed with its kind (`file`) or unlabeled for human triage
 * (`insufficient-permissions`). Nothing is queued without a session, on a dry run, or when no ticket was filed
 * (the `ask` outcome never reaches this step). The step never fails the intake:
 * an error is logged and the intake still ends at `intake closed`. Bound by `${notifyFilingSession}`, not
 * `${notifySession}`: that is the process variable.
 */
@Component("notifyFilingSession")
class NotifyFilingSessionDelegate(private val notifications: IntakeNotifications) : JavaDelegate {
    private val log = LoggerFactory.getLogger(NotifyFilingSessionDelegate::class.java)

    override fun execute(execution: DelegateExecution) {
        val intake = execution.processInstanceId
        val session = (execution.getVariable(NOTIFY_SESSION) as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (execution.getVariable(Vars.DRY_RUN) == true) { log.info("intake {}: dry run, not telling session {}", intake, session); return }
        if (execution.getVariable("outcome") !in FILED_OUTCOMES) return
        val ticket = (execution.getVariable("ticket") as? Number)?.toLong() ?: return
        val url = execution.getVariable("ticketUrl") as? String ?: return
        val repo = execution.ticketRepo()
        runCatching { notifications.queue(intake, session, repo, ticket, url) }
            .onSuccess { queued -> log.info("intake {}: {} session {} of #{}", intake, if (queued) "queued telling" else "already queued for", session, ticket) }
            .onFailure { log.warn("intake {}: could not queue telling session {} of #{}: {}", intake, session, ticket, it.toString()) }
    }
}

/** The refine outcomes that file a ticket: with its kind label, or unlabeled for human triage. */
private val FILED_OUTCOMES = setOf("file", "insufficient-permissions")

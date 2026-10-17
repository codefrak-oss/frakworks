package com.codefrak.engine.postdeploy

import com.codefrak.engine.chore.flow
import com.codefrak.engine.hitlp.FlowWait
import com.codefrak.engine.hitlp.HitlpAsserted
import com.codefrak.engine.hitlp.HitlpFiling
import com.codefrak.engine.intake.AskWaits
import com.codefrak.engine.intake.IntakeQuestions
import com.codefrak.engine.intake.TicketFiler
import com.codefrak.engine.smith.SmithRunStats
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.issue
import com.codefrak.engine.smith.repo
import com.codefrak.engine.smith.tier
import com.codefrak.engine.workitems.Tickets
import com.codefrak.engine.workitems.commentOn
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.operaton.bpm.engine.impl.util.ClockUtil
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.Date

/** What the user may answer at the check's deadline (`task-post-deadline-question`), in the ask block's order. */
object CheckDeadline {
    const val WAIT = "wait"; const val DROP = "drop"; const val BUG = "bug"
    val ANSWERS = listOf(WAIT, DROP, BUG)
    /** The deadline when the section gives none, and the extension a `wait` gives when it gave no `Deadline: <n>`. */
    val DEFAULT: Duration = Duration.ofDays(7)

    /** The deadline from [now]: the section's instant, else [now] plus its duration, else plus [DEFAULT]. */
    fun at(section: PostDeployCheck?, now: Instant): Instant = section?.deadlineAt ?: now.plus(section?.deadlineAfter ?: DEFAULT)

    /** After a `wait`: [now] plus the section's `Deadline:` duration, else [DEFAULT] (an instant deadline has passed). */
    fun extended(section: PostDeployCheck?, now: Instant): Instant = now.plus(section?.deadlineAfter ?: DEFAULT)

    /** The question, ending in a choice ask block (bots/smith.md, "the ask block") the HITLP filing reads options from. */
    fun question(issue: Long, section: PostDeployCheck?, runs: List<CheckRun>, extension: Duration): String = buildString {
        append("The post-deployment check of #").append(issue).append(" reached its deadline without passing")
        append(if (runs.isEmpty()) ", before any check ran (the merge never reached production)" else " after ${runs.size} run(s)").append(".\n\n")
        section?.let { append("What it checks:\n\n").append(it.what.trimEnd()).append("\n\n") }
        runs.lastOrNull()?.summary?.takeIf { it.isNotBlank() }?.let { append("The last run said:\n\n").append(it.trimEnd()).append("\n\n") }
        append("```ask\nkind: choice\noptions:\n")
        append("  - id: $WAIT\n    label: keep checking for another ${extension.toDays()} day(s)\n")
        append("  - id: $DROP\n    label: stop checking and close the ticket\n")
        append("  - id: $BUG\n    label: file a bug and close the ticket\n")
        append("```\n")
    }

    /** The option an answer names: `option <id>: <label>` as HitlpAnswers records a choice, or the bare id; null for anything else. */
    fun answer(text: String?): String? {
        val t = text?.trim()?.lowercase() ?: return null
        val id = Regex("""^option\s+(\w+)""").find(t)?.groupValues?.get(1) ?: t
        return id.takeIf { it in ANSWERS }
    }
}

/**
 * `${initCheck}` (`task-init-check`): when the check stops and asks the user, `checkDeadlineAt` ([CheckDeadline.at]
 * from the flow's start, which is the merge), and no answer from an earlier deadline.
 */
@Component("initCheck")
class InitCheckDelegate : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val section = PostDeployCheck.of(execution.getVariable(Vars.POST_DEPLOY_CHECK) as? ByteArray)
        execution.setVariable(Vars.CHECK_DEADLINE_AT, Date.from(CheckDeadline.at(section, ClockUtil.getCurrentTime().toInstant())))
        execution.setVariable(Vars.CHECK_DEADLINE_ANSWER, null)
    }
}

/**
 * `${postCheckDeadline}` (`task-post-deadline-question`, after `event-deadline` fired): records the deadline question
 * in `engine.intake_question` under the flow's instance id, files it as a HITLP `human.ask` of kind DECISION whose
 * options are `wait`, `drop` and `bug` ([CheckDeadline.question]'s ask block), and comments it on the ticket. The flow
 * waits at `user-task-answer-deadline` until `HitlpAnswers` completes it, as for a Smith's question.
 */
@Component("postCheckDeadline")
class PostCheckDeadlineDelegate(
    private val tickets: Tickets,
    private val questions: IntakeQuestions,
    private val hitlp: HitlpFiling,
    private val runs: SmithRunStats,
) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val repo = execution.repo(); val issue = execution.issue(); val id = execution.processInstanceId
        val section = PostDeployCheck.of(execution.getVariable(Vars.POST_DEPLOY_CHECK) as? ByteArray)
        val history = CheckRun.list(execution.getVariable(Vars.CHECK_HISTORY) as? ByteArray)
        val question = CheckDeadline.question(issue, section, history, section?.deadlineAfter ?: CheckDeadline.DEFAULT)
        val nth = questions.list(id).size + 1
        questions.ask(id, repo, issue, question)
        val filed = hitlp.ask(FlowWait(id, AskWaits.answerTask(execution), HitlpAsserted.Kind.DECISION, repo, issue, question,
            AskWaits.askBlock(question), AskWaits.askedBy(execution, repo), AskWaits.runId(execution, runs), nth = nth))
        execution.setVariable(Vars.HITLP_TASK_ID, filed.handle.taskId)
        tickets.commentOn(repo, issue, "${question.trimEnd()}\n\n— engine ${execution.flow()} `$id`. " +
            "${IntakeQuestions.answerHint(filed.decisionUrl, filed.handle.taskId)}.")
    }
}

/**
 * `${readCheckDeadline}` (`task-read-deadline-answer`): the user's answer at the deadline (the newest in
 * `engine.intake_question`) as `checkDeadlineAnswer`; `wait` also moves `checkDeadlineAt` on
 * ([CheckDeadline.extended]). An answer that is none of the three is an error: the incident names it.
 */
@Component("readCheckDeadline")
class ReadCheckDeadlineDelegate(private val questions: IntakeQuestions) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val text = questions.answered(execution.processInstanceId).lastOrNull()?.answer
        val answer = CheckDeadline.answer(text) ?: error("the deadline answer '${text.orEmpty()}' is none of ${CheckDeadline.ANSWERS}")
        execution.setVariable(Vars.CHECK_DEADLINE_ANSWER, answer)
        if (answer == CheckDeadline.WAIT) {
            val section = PostDeployCheck.of(execution.getVariable(Vars.POST_DEPLOY_CHECK) as? ByteArray)
            execution.setVariable(Vars.CHECK_DEADLINE_AT, Date.from(CheckDeadline.extended(section, ClockUtil.getCurrentTime().toInstant())))
        }
    }
}

/**
 * `${fileDeadlineBug}` (`task-file-deadline-bug`, the user answered `bug` at the deadline): files a bug in the ticket's
 * repo that the check never passed, with the section's prose and the last run's summary, the Context and trailer
 * [FileCheckBugDelegate] gives a failed check's. Sets `checkBugTicket`.
 */
@Component("fileDeadlineBug")
class FileDeadlineBugDelegate(private val filer: TicketFiler) : JavaDelegate {
    private val log = LoggerFactory.getLogger(FileDeadlineBugDelegate::class.java)

    override fun execute(execution: DelegateExecution) {
        val repo = execution.repo(); val issue = execution.issue()
        val section = PostDeployCheck.of(execution.getVariable(Vars.POST_DEPLOY_CHECK) as? ByteArray)
        val last = CheckRun.list(execution.getVariable(Vars.CHECK_HISTORY) as? ByteArray).lastOrNull()
        val bug = CheckBug("Post-deployment check of #$issue never passed", body(section, last))
        val draft = FileCheckBugDelegate.draft(bug, issue, execution.getVariable(Vars.PR_URL) as? String, execution.getVariable(Vars.MERGE_SHA) as? String)
        val body = filer.body(draft, "check of #$issue", execution.tier(), "p2", by = execution.getVariable(Vars.ASKED_BY) as? String)
        val created = filer.file(repo, bug.title, body, "bug", null)
        execution.setVariable(Vars.CHECK_BUG_TICKET, created.number)
        log.info("check of {}#{}: deadline passed, filed bug {}", repo, issue, created.url)
    }

    companion object {
        fun body(section: PostDeployCheck?, last: CheckRun?): String = buildString {
            append("The post-deployment check reached its deadline without passing, and the user asked for a bug.\n\n")
            section?.let { append("## What it checks\n\n").append(it.what.trimEnd()).append("\n\n") }
            append("## The last run\n\n")
            append(last?.let { "${it.outcome} at ${it.at}: ${it.summary?.trimEnd().orEmpty()}" } ?: "No check ran: the merge never reached production.")
        }
    }
}

package com.codefrak.engine.intake

import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.repos.Access
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.issueOf
import com.codefrak.engine.smith.repoOf
import com.codefrak.engine.workitems.Tickets
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.variable.Variables
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/** `refining`: what the amendment did to the refine ([IntakeAmender.RESTARTED], [IntakeAmender.WAITS], [IntakeAmender.RECORDED]). */
data class Amended(val intakeId: String, val refining: String)

/**
 * Adds the user's follow-up to an intake that has not filed yet (engine/README.md, "Intake", "A follow-up to an
 * intake"): First mate's `amend_intake`. The follow-up is an answered `engine.intake_question` row (the question
 * `Follow-up from @<login>`), so it is in the intake's `answers` from now on, and is commented on the ask item.
 * A refine run in flight never re-reads its brief, so it is stopped and `call-refine-ask` started again on the same
 * instance; a question waiting stays, and the next refine round carries both.
 */
@Component
class IntakeAmender(
    private val runtime: RuntimeService, private val history: HistoryService, private val questions: IntakeQuestions,
    private val access: Access, private val tickets: Tickets, private val canceller: FlowCanceller,
    private val events: EngineEvents, private val links: IntakeTickets,
) {
    private val log = LoggerFactory.getLogger(IntakeAmender::class.java)

    companion object {
        const val REFINE = "call-refine-ask"
        const val RESTARTED = "restarted"
        const val WAITS = "waits on a question"
        const val RECORDED = "recorded"
        /** The steps that file an intake's ticket: once one ran, or is about to, a follow-up goes on the ticket. */
        val FILING = listOf("task-file-ticket", "task-file-unlabeled")

        /** Whether intake [id] has filed its ticket: a [FILING] step finished in its history. amend_ticket uses it too. */
        fun hasFiled(history: HistoryService, id: String): Boolean =
            FILING.any { history.createHistoricActivityInstanceQuery().processInstanceId(id).activityId(it).finished().count() > 0 }
    }

    fun amend(id: String, text: String, by: String): Amended {
        if (text.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "text is blank")
        filed(id)?.let { throw ResponseStatusException(HttpStatus.CONFLICT, "intake $id has already filed #$it; use amend_ticket on #$it") }
        val instance = runtime.createProcessInstanceQuery().processInstanceId(id).singleResult()
            ?.takeIf { it.processDefinitionId.substringBefore(':') == "intake" }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "intake $id is not running")
        val active = runtime.getActiveActivityIds(instance.id)
        if (active.any { it in FILING })
            throw ResponseStatusException(HttpStatus.CONFLICT, "intake $id is filing its ticket; try amend_ticket on the ticket in a moment")
        val repo = runtime.repoOf(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "intake $id is not running")
        val issue = runtime.issueOf(id)
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "intake $id has no ask item yet; try again in a moment")
        if (!access.trusted(by))
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "@$by is not a trusted login")

        questions.followUp(id, repo, issue, "Follow-up from @$by", text, by)
        runtime.setVariable(id, Vars.ANSWERS, Variables.byteArrayValue(Brief.answersBytes(questions.answered(id))))
        val refining = when {
            REFINE in active -> RESTARTED
            active.any { it == ANSWER_TASK || it == DUPLICATE_TASK } -> WAITS
            else -> RECORDED
        }
        if (refining == RESTARTED) {
            // Its smith-run is deleted with the activity, and nothing would stop its sandbox after: stop it first.
            val stopped = canceller.stopSandboxes(id)
            runtime.createProcessInstanceModification(id).cancelAllForActivity(REFINE).startBeforeActivity(REFINE)
                .execute()
            log.info("intake {}: follow-up from {}; {} refine sandbox(es) stopped, refining again", id, by, stopped)
        }
        val next = when (refining) {
            RESTARTED -> "refining again with it"
            WAITS -> "the question still waits; the next refine round reads it"
            else -> "the refine reads it"
        }
        tickets.commentOn(repo, issue, "@$by added:\n\n${text.trimEnd()}\n\n— engine intake `$id`, $next.")
        events.publishAfterCommit(EngineEvent.Intake(repo, id, "amended", issue, by = by))
        return Amended(id, refining)
    }

    /** The ticket [id] filed, when a filing step finished in its history; null when it has not filed. */
    private fun filed(id: String): Long? {
        if (!hasFiled(history, id)) return null
        return links.get(id)?.ticket
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "intake $id has already filed its ticket; use amend_ticket on it")
    }
}

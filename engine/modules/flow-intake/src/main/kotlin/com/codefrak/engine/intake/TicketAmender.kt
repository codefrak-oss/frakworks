package com.codefrak.engine.intake

import com.codefrak.engine.board.targetRepoOr404
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.Amendment
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.workitems.Tickets
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.variable.Variables
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * What amend_ticket did: `work` is [TicketAmender.RESTARTED], [TicketAmender.WAITS] or [TicketAmender.NEXT_REPLAN];
 * `lost` names the stopped work or reproduce run's unpushed commits, null when none was stopped; `failedWrites` the
 * GitHub writes that failed.
 */
data class TicketAmended(val repo: String, val ticket: Long, val work: String, val lost: String?, val failedWrites: List<String>)

/**
 * Adds the user's late information to a filed ticket whose flow runs (engine/README.md, "Intake", "A follow-up to a
 * ticket"): amend_ticket and `POST /ticket/{owner}/{repo}/{n}/amend`. The amendment goes into the flow's `amendments`
 * and its `approvedTicket` snapshot (an `## Amendments` section), so every later brief carries it; a Smith run in
 * flight never re-reads its brief, so it is stopped and its activity cancelled, and the flow loops back to the work
 * (or to the reproduce or the design it was at) on the same instance. A question that waits, and a split parent,
 * only record it. The GitHub writes (the issue body's section and a comment) come after, as in [FlowCanceller.cancel].
 */
@Component
class TicketAmender(
    private val runtime: RuntimeService, private val history: HistoryService, private val access: Access,
    private val tickets: Tickets, private val repos: TargetRepos,
    private val canceller: FlowCanceller,
) {
    private val log = LoggerFactory.getLogger(TicketAmender::class.java)

    companion object {
        const val WORK = "call-do-work"
        const val REPRODUCE = "call-reproduce"
        const val DESIGN = "call-design"
        const val REPLAN = "call-replan"
        const val CHILD_CLOSED = "event-child-closed"
        /** The activities after the work that an amendment cancels, to start the work again. */
        val AFTER_WORK = listOf("call-security-review", "call-review-pr", "call-ci-check")
        const val RESTARTED = "restarted"
        const val WAITS = "waits on a question"
        const val NEXT_REPLAN = "next replan"
        const val SECTION = "## Amendments"

        /** [body] with [entry] appended to its `## Amendments` section, the section added at the end on the first. */
        fun withAmendment(body: String, entry: Amendment): String {
            val line = "- @${entry.by}, ${entry.at}: ${entry.text.trim().replace("\n", "\n  ")}"
            val text = body.replace("\r\n", "\n").trimEnd()
            val start = Regex("(?m)^${Regex.escape(SECTION)}[ \t]*$").find(text)
                ?: return "$text\n\n$SECTION\n\n$line\n"
            // The section runs to the next heading of level 1 or 2, or the end.
            val next = Regex("(?m)^#{1,2} ").find(text, start.range.last + 1)?.range?.first ?: text.length
            val section = text.substring(0, next).trimEnd()
            val rest = text.substring(next)
            return "$section\n$line\n" + (if (rest.isEmpty()) "" else "\n$rest\n")
        }
    }

    fun amend(repoAsked: String?, n: Long, text: String, by: String): TicketAmended {
        if (text.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "text is blank")
        if (text.length > MAX_ANSWER_CHARS)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "text is at most ${MAX_ANSWER_CHARS} characters")
        val targets = repos.list().map { it.fullName }
        if (repoAsked.isNullOrBlank())
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "repo is required (owner/name): there is no default repo; the target repos are ${targets.joinToString()}")
        val repo = targetRepoOr404(repos, repoAsked!!).fullName
        if (!access.trusted(by)) throw ResponseStatusException(HttpStatus.FORBIDDEN, "@$by is not a trusted login")
        val closed = ResponseStatusException(HttpStatus.CONFLICT, "ticket $repo#$n is merged/closed; file a follow-up")
        val intake = history.createHistoricProcessInstanceQuery().processDefinitionKey("intake")
            .variableValueEquals(Vars.REPO, repo).variableValueEquals(Vars.ISSUE, n).list()
        // An intake refined in place becomes its ticket: only one that has not filed yet is still an intake.
        if (intake.any { !IntakeAmender.hasFiled(history, it.id) }) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$repo#$n is an intake; use amend_intake")
        // A closed issue has no flow running on it: the engine closes it as its flow ends, and a cancel deletes the flow.
        val id = canceller.running(repo, n).firstOrNull()?.id ?: throw closed
        if (history.createHistoricActivityInstanceQuery().processInstanceId(id).activityId(MERGE_TASK).finished().count() > 0) throw closed
        val active = runtime.getActiveActivityIds(id)
        val between = ResponseStatusException(HttpStatus.CONFLICT, "ticket $repo#$n is between steps; try again in a moment")
        val split = runtime.getVariable(id, Vars.SPLIT) != null
        // What to cancel and start again: null when the amendment is only recorded.
        val restart: Pair<String, String>? = when {
            split -> if (active.any { it == CHILD_CLOSED || it == REPLAN }) null else throw between
            active.any { it.startsWith(ANSWER_TASKS.removeSuffix("%")) } -> null
            WORK in active -> WORK to WORK
            REPRODUCE in active -> REPRODUCE to REPRODUCE
            DESIGN in active -> DESIGN to DESIGN
            else -> AFTER_WORK.firstOrNull { it in active }?.let { it to WORK } ?: throw between
        }
        val work = when {
            split -> NEXT_REPLAN
            restart == null -> WAITS
            else -> RESTARTED
        }

        // The engine's side first: the snapshot, the amendments, then the loop-back.
        val at = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
        val entry = Amendment(by, at, text.trim())
        val amendments = Brief.amendments(runtime.getVariable(id, Vars.AMENDMENTS) as? ByteArray) + entry
        runtime.setVariable(id, Vars.AMENDMENTS, Variables.byteArrayValue(Brief.amendmentsBytes(amendments)))
        // The snapshot is the body as authorized plus every amendment so far: the issue's body is written from it too.
        val body = (runtime.getVariable(id, Vars.TICKET) as? ByteArray)?.let { Brief.ticket(it) }?.let { ticket ->
            withAmendment(ticket.body, entry).also {
                runtime.setVariable(id, Vars.TICKET, Variables.byteArrayValue(Brief.ticketBytes(ticket.copy(body = it))))
            }
        }
        var lost: String? = null
        if (restart != null) {
            val (cancel, start) = restart
            // Its smith-run is deleted with the activity, and nothing would stop its sandbox after: stop it first.
            val stopped = canceller.stopSandboxes(id)
            if (cancel == WORK || cancel == REPRODUCE)
                lost = "the stopped ${if (cancel == WORK) "work" else "reproduce"} run's unpushed commits"
            runtime.createProcessInstanceModification(id).cancelAllForActivity(cancel).startBeforeActivity(start).execute()
            log.info("{}#{}: amended by {}; {} sandbox(es) stopped, {} cancelled, {} started", repo, n, by, stopped, cancel, start)
        }

        val failed = mutableListOf<String>()
        if (body != null) githubWrite(failed, "edit $repo#$n's body", "amend", log) { tickets.editBody(tickets.ticket(repo, n).id, body, ENGINE_AUTHOR) }
        val next = when (work) {
            RESTARTED -> "the work runs again with it"
            WAITS -> "the question still waits; the next round reads it"
            else -> "the next replan reads it"
        }
        githubWrite(failed, "comment on $repo#$n", "amend", log) {
            tickets.commentOn(repo, n, "@$by added:\n\n${text.trimEnd()}\n\n— engine, $next")
        }
        return TicketAmended(repo, n, work, lost, failed)
    }
}

package com.codefrak.engine.intake

import com.codefrak.engine.github.CreatedIssue
import com.codefrak.engine.github.issueUrl
import com.codefrak.engine.workitems.Tickets
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * An intake's file step, idempotent (engine/README.md, "Intake"): one ask files one ticket however many times the step
 * runs. The step's job may roll back after the create reached GitHub (a later step in the same job failing, a failed
 * commit), and Operaton retries it from before the create. So the create is bracketed by two writes in their own
 * transactions ([IntakeTickets.filing] before, [IntakeTickets.filed] after), which a rollback leaves standing, and a run
 * first looks for what an earlier run filed:
 * - a ticket recorded: reuse it, no GitHub write;
 * - a create begun but no ticket recorded (the create's answer lost, or the run died before recording it): the open
 *   ticket whose trailer names this intake (`<!-- engine: intake <id> …`), when there is one, recorded and reused;
 * - nothing begun: file.
 */
@Component
class FileOnce(private val links: IntakeTickets, private val tickets: Tickets) {
    /**
     * [repo] is the repo the ticket is filed in; [elsewhere] when that is not the intake's own (a refiner that named another
     * target repo), so the row records it as the ticket's repo.
     */
    fun file(intake: String, repo: String, elsewhere: Boolean = false, create: () -> CreatedIssue): CreatedIssue =
        findOrFile(intake, links.get(intake)?.ticket, links.began(intake), { find(intake, repo) }, { links.filing(intake) }, create,
            { links.filed(intake, it.number, ticketRepo = repo.takeIf { elsewhere }) }, repo)

    private fun find(intake: String, repo: String): CreatedIssue? = filedFor(tickets, intake, repo)

    companion object {
        private val log = LoggerFactory.getLogger(FileOnce::class.java)

        /** The logic, without the stores: [recorded] the ticket on record, [began] whether a create was begun. */
        fun findOrFile(intake: String, recorded: Long?, began: Boolean, find: () -> CreatedIssue?, begin: () -> Unit,
                       create: () -> CreatedIssue, record: (CreatedIssue) -> Unit, repo: String): CreatedIssue {
            if (recorded != null) {
                log.info("intake {}: already filed {}#{}, reusing it", intake, repo, recorded)
                return CreatedIssue(recorded, issueUrl(repo, recorded))
            }
            if (began) find()?.let {
                log.info("intake {}: an earlier run filed {}#{} without recording it; reusing it", intake, repo, it.number)
                record(it)
                return it
            }
            begin()
            return create().also(record)
        }

        /** [repo]'s open ticket whose body's trailer names [intake] ([ofIntake]), or null for none: what an earlier run filed. */
        fun filedFor(tickets: Tickets, intake: String, repo: String): CreatedIssue? =
            tickets.open(repo).firstNotNullOfOrNull { item -> item.ticket?.takeIf { ofIntake(item.body, intake) } }?.let { CreatedIssue(it, issueUrl(repo, it)) }

        /** Whether [body]'s engine trailer names [intake] as its origin ([com.codefrak.engine.intake.TicketFiler.body]). */
        fun ofIntake(body: String, intake: String): Boolean =
            Regex("""<!--\s*engine:\s*intake\s+${Regex.escape(intake)}[\s·]""").containsMatchIn(body)
    }
}

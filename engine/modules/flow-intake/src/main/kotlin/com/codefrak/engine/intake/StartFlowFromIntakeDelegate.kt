package com.codefrak.engine.intake

import com.codefrak.engine.camunda.Actor
import com.codefrak.engine.chore.ProcessFlowStarter
import com.codefrak.engine.github.IssueSnapshot
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.Deferred
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.issue
import com.codefrak.engine.smith.repo
import com.codefrak.engine.trigger.DeferredStarts
import com.codefrak.engine.workitems.Tickets
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * `task-start-work` in bpmn/intake.bpmn, for a ticket filed as a chore, a bug,
 * a feature, a proposal or a research (`ProcessFlowStarter.FROM_INTAKE`): the intake's authorization
 * carries over to it (engine/README.md, "Authorization from an intake") when
 * whoever authorized the intake may also authorize that kind in that repo.
 * An intake the engine's doctor authorized ([AUTHORIZED_BY_ENGINE]) carries to
 * a bug or a feature (`ProcessFlowStarter.DOCTOR_FROM_INTAKE`), with no grant; any other kind waits for a person's `authorized`.
 * The flow works from the ticket as filed; a feature also takes the refiner's
 * mode. Otherwise it says on the ticket that it waits for `authorized`. With
 * [DEPENDENCY_HELD] set it starts nothing: `fileTicket` has said why (a
 * `blockedBy` entry naming no intake it knows, an intake in another repo, or
 * a relation it could not write). A named intake that ended without filing
 * does not set it: `fileTicket` ignored it with a note, and this carries over
 * as with no blocker. A
 * ticket with an open "blocked by" issue is deferred instead, as the scan
 * defers a label (README, "Blocked by"), and started by a later scan. Bound
 * by `${startFlowFromIntake}`. The flow runs in the ticket's repo ([TICKET_REPO]): the intake's, or the target
 * repo the refiner named; the ticket is the work item `fileTicket` filed ([FILED_WORK_ITEM]), by its ticket number.
 */
@Component("startFlowFromIntake")
class StartFlowFromIntakeDelegate(
    private val repos: TargetRepos,
    private val access: Access,
    private val start: AuthorizedStart,
    private val tickets: Tickets,
    private val scanner: DeferredStarts,
) : JavaDelegate {
    private val log = LoggerFactory.getLogger(StartFlowFromIntakeDelegate::class.java)

    override fun execute(execution: DelegateExecution) {
        val repo = execution.ticketRepo()
        val intakeRepo = execution.repo()
        val ticket = (execution.getVariable("ticket") as Number).toLong()
        // How the ticket names its intake: its id when the ticket is the intake's own ask item, refined in place;
        // else the ask item's `#N`, or `owner/name#N` when the ticket is in another repo.
        val intake = if (intakeRepo == repo && ticket == execution.issue()) "`${execution.processInstanceId}`"
            else if (intakeRepo == repo) "#${execution.issue()}" else "$intakeRepo#${execution.issue()}"
        val kind = execution.getVariable("kind") as String
        val by = execution.getVariable(AUTHORIZED_BY) as? String
        if (execution.getVariable(DEPENDENCY_HELD) != null) {
            // FileTicketDelegate said why on the ticket: an entry it could not resolve, or a blocker it could not write.
            log.info("intake {}: {} on {}#{} not started, its dependencies are held", execution.processInstanceId, kind, repo, ticket)
            return
        }
        // The engine's doctor and its friction grouper (AUTHORIZED_BY_ENGINE) carry to a bug or a feature with no grant; never a login's way, as no login asked.
        val system = (execution.getVariable(AUTHORIZED_BY_ENGINE) as? String)?.let { Actor.System(it) }
        val allowed = if (system != null) system.part in setOf(DOCTOR_ACTOR, FRICTION_ACTOR) && kind in ProcessFlowStarter.DOCTOR_FROM_INTAKE && repos.contains(repo)
            else by != null && repos.contains(repo) && access.trusted(by)
        if (by == null || !allowed) {
            val who = if (system != null) " (${system.name}, who authorized the intake, may not)" else by?.let { " (@$it, who authorized the intake, is not a trusted login)" }.orEmpty()
            tickets.commentOn(repo, ticket, "Filed as a $kind from intake $intake. It starts when a trusted login " +
                "starts it (`POST /work-items/{id}/start` or First mate's `approve`)$who.\n\n— engine")
            return
        }
        val filed = Brief.ticket(execution.getVariable(FILED_TICKET) as ByteArray)
        val snapshot = IssueSnapshot(filed.number, filed.title, filed.body, filed.author)
        val detail = if (system != null) "authorized by ${system.name} via intake $intake" else "authorized by @$by via intake $intake"
        val dryRun = execution.getVariable(Vars.DRY_RUN) == true
        val mode = if (kind == "feature") execution.getVariable(Vars.FEATURE_MODE) as? String ?: error("a feature needs its mode") else null
        // The tier it was filed at (Jev's pick or the refiner's DraftTicket.tier, 1-10): skips re-parsing the filed issue body.
        val tier = RefinePick.of(execution)?.tier ?: RefineResult.of(execution).ticket?.tier
        val blockers = tickets.blockers(repo, ticket)
        if (blockers.any { it.stillOpen() } && system != null) {
            // A deferred start is re-checked as a login's grant when it wakes, which the engine's doctor has none of: a person labels it.
            tickets.commentOn(repo, ticket, "Filed as a $kind from intake $intake, blocked by an open issue. It starts when someone with access " +
                "for ${kind}s starts it (`POST /work-items/{id}/start` or First mate's `approve`) (${system.name} authorized the intake, but does not wait on blockers).\n\n— engine")
            return
        }
        if (blockers.any { it.stillOpen() }) {
            // Nothing claimed: the scan starts it, as label event 0, once every blocker closed by a merge.
            scanner.defer(Deferred(repo, ticket, 0, by, kind, mode, snapshot, blockers, tier, detail, dryRun))
            log.info("intake {}: {} on {}#{} deferred, {}", execution.processInstanceId, kind, repo, ticket, detail)
            return
        }
        val instance = start.start(repo, kind, snapshot, by, detail, tier, mode, dryRun)
        execution.setVariable("startedFlow", instance)
        log.info("intake {}: {} {} on {}#{}, {}", execution.processInstanceId, kind, instance, repo, ticket, detail)
    }
}

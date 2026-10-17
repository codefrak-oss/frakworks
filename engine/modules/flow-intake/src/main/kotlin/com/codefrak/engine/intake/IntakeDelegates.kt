package com.codefrak.engine.intake

import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.github.issueUrl
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.Ticket
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.issueOrNull
import com.codefrak.engine.smith.repo
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.operaton.bpm.engine.variable.Variables
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * `task-file-ticket` in bpmn/intake.bpmn: files the Smith's drafted ticket as an engine work item
 * (engine/docs/work-items.md, "Asks as work items"; [TicketFiler.fileWorkItem]) at the kind, feature mode and tier
 * `read result` kept ([RefinePick]: Jev's pick or the refiner's choice, with Jev's pick noted at the end of the
 * body), in the target repo the refiner named or the intake's own. Nothing is written to GitHub. The description ends
 * with the engine trailer (who asked, tier, points), as a ticket's body did. Sets `workItem`, the ticket's repo, its
 * number (`ticket`, engine/docs/work-items.md, "Tickets"), `ticketUrl`, `filedTicket`, `kind` and, for a feature, its
 * mode, which `startFlowFromIntake` starts its flow from. Bound by `${fileTicket}`.
 */
@Component("fileTicket")
class FileTicketDelegate(
    private val filer: TicketFiler,
    private val repos: TargetRepos,
) : JavaDelegate {
    private val log = LoggerFactory.getLogger(FileTicketDelegate::class.java)

    override fun execute(execution: DelegateExecution) {
        val refined = RefineResult.of(execution)
        val draft = refined.ticket ?: error("no ticket in the smith's result")
        refined.kind ?: error("no kind in the smith's result")
        val pick = RefinePick.of(execution) ?: RefinePick.refiner(refined)
        val kind = pick.kind
        val draftBody = pick.note?.let { draft.body.trimEnd() + "\n\n" + it } ?: draft.body
        val submittedBy = execution.getVariable(SUBMITTED_BY) as? String
        val body = filer.body(draftBody, "intake ${execution.processInstanceId}", pick.tier, draft.priority, submittedBy,
            pick.call.takeIf { pick.note != null }, points = draft.points)
        val ask = (execution.getVariable(Vars.TICKET) as? ByteArray)?.let { Brief.ticket(it).body }.orEmpty()
        val target = targetRepo(refined, execution.repo(), repos)
        val item = filer.fileWorkItem(target, execution.processInstanceId, ask, draft.title, body, kind, pick.featureMode, pick.tier)
        execution.setVariable(FILED_WORK_ITEM, item.id)
        execution.setVariable(TICKET_REPO, target)
        execution.setVariable("ticket", item.ticket)
        execution.setVariable("ticketUrl", issueUrl(target, item.ticket))
        execution.setVariable("kind", kind)
        if (kind == "feature") execution.setVariable(Vars.FEATURE_MODE, pick.featureMode)
        val author = execution.getVariable(AUTHORIZED_BY) as? String ?: "engine"
        execution.setVariable(FILED_TICKET, Variables.byteArrayValue(Brief.ticketBytes(Ticket(item.ticket, draft.title, body, author))))
        log.info("intake {}: filed work item {} (#{}) as a {}", execution.processInstanceId, item.id, item.ticket, kind)
    }
}

/** The work item `fileTicket` filed, read by `startFlowFromIntake`. */
const val FILED_WORK_ITEM = "workItem"

/**
 * `task-file-unlabeled` in bpmn/intake.bpmn, for the `insufficient-permissions` outcome: files the refiner's
 * drafted ticket in the intake's repo with no labels at all (no kind, no `authorized`), so no scan and no
 * intake starts a flow on it. Before the engine trailer the body gets a `## Needs human triage` section with
 * the refiner's `blocker` (when it gave one), its `reason` and the kind it would have filed; the trailer ends
 * with `· triage`, which is how `Needs` lists it as `insufficient-permissions` until it has a kind label or
 * is closed (engine/README.md, "What needs you"). Sets `ticket` and `ticketUrl`, so
 * `closeIntake` says `Done. Filed #n` and the intake ends cleanly; no `kind`, no `filedTicket` and no
 * `blockedBy` writes, since nothing starts from it. A doctor intake's `engine.doctor_bug` row gets the issue. A retry
 * with `ticket` already set files nothing: nothing after the create can throw. Bound by `${fileUnlabeledTicket}`.
 */
@Component("fileUnlabeledTicket")
class FileUnlabeledTicketDelegate(private val filer: TicketFiler, private val events: EngineEvents, private val links: FiledTicketLinks,
                                  private val once: FileOnce, private val repos: TargetRepos) : JavaDelegate {
    private val log = LoggerFactory.getLogger(FileUnlabeledTicketDelegate::class.java)

    override fun execute(execution: DelegateExecution) {
        if (execution.getVariable("ticket") != null) {
            log.info("intake {}: already filed #{}, not filing it again", execution.processInstanceId, execution.getVariable("ticket"))
            return
        }
        val refined = RefineResult.of(execution)
        val draft = refined.ticket ?: error("no ticket in the smith's result")
        val draftBody = draft.body.trimEnd() + "\n\n" + TicketFiler.triageSection(refined.blocker, refined.reason, refined.kind)
        val body = filer.body(draftBody, "intake ${execution.processInstanceId}", draft.tier, draft.priority, execution.getVariable(SUBMITTED_BY) as? String, triage = true)
        val repo = execution.repo()
        val target = targetRepo(refined, repo, repos)
        val created = once.file(execution.processInstanceId, target, elsewhere = target != repo) { filer.fileUnlabeled(target, draft.title, body) }
        execution.setVariable(TICKET_REPO, target)
        execution.setVariable("ticket", created.number)
        execution.setVariable("ticketUrl", created.url)
        log.info("intake {}: filed {} unlabeled for human triage ({})", execution.processInstanceId, created.url, refined.outcome)
        links.link(execution.getVariable(DOCTOR_BUG) as? Number, execution.getVariable(FRICTION_BUG) as? Number,
            execution.processInstanceId, target, created.number, body)
        events.publishAfterCommit(EngineEvent.Intake(repo, execution.processInstanceId, "triage", execution.issueOrNull(),
            ticket = created.number, ticketUrl = created.url, kind = refined.kind, tier = draft.tier))
    }
}


/**
 * The repo the refined ticket is filed in: the refiner's [RefineResult.repo] in the target repo's own spelling, or
 * [intakeRepo] when it named none or named the intake's own. One that is no target repo fails the step (`read result`
 * refused it already, [RefineResultHandler]; this is a repo removed since).
 */
internal fun targetRepo(refined: RefineResult, intakeRepo: String, repos: TargetRepos): String {
    val named = refined.repo?.trim()?.takeIf { it.isNotEmpty() && !it.equals(intakeRepo, ignoreCase = true) } ?: return intakeRepo
    return repos.find(named)?.fullName
        ?: error(RefineResult.repoProblem(named, repos.list().map { it.fullName }) ?: "repo '$named' is not a target repo")
}

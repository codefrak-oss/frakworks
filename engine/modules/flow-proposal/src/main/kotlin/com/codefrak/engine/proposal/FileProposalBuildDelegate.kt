package com.codefrak.engine.proposal

import com.codefrak.engine.feature.FeatureMode
import com.codefrak.engine.intake.TicketFiler
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.Ticket
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.issue
import com.codefrak.engine.smith.repo
import com.codefrak.engine.smith.tier
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.operaton.bpm.engine.variable.Variables
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * `task-file-build`, for `accept and build` (auto): files a `feature` with the proposal ticket's title that
 * builds the doc (`Builds: docs/proposals/<slug>.md`), in confirm mode, or auto for `accept and build auto`.
 * Its trailer's origin is `proposal #<n>` and its tier the proposal's own, or none when the proposal had none,
 * so the build starts at the default. Sets `builtTicket` and [BUILT_SNAPSHOT]. Bound by `${fileProposalBuild}`.
 */
@Component("fileProposalBuild")
class FileProposalBuildDelegate(private val filer: TicketFiler) : JavaDelegate {
    private val log = LoggerFactory.getLogger(FileProposalBuildDelegate::class.java)

    override fun execute(execution: DelegateExecution) {
        val repo = execution.repo(); val issue = execution.issue(); val path = execution.path()
        val ticket = (execution.getVariable(Vars.TICKET) as? ByteArray)?.let { Brief.ticket(it) }
        val title = ticket?.title ?: path
        val mode = if (execution.getVariable(Vars.DECISION) == DecisionVerb.BUILD_AUTO) FeatureMode.AUTO else FeatureMode.CONFIRM
        val tier = execution.tier()
        val draft = "Builds: $path\n\nThe build of proposal #$issue, accepted in ${execution.getVariable(Vars.PR_URL)}: the doc is the agreed design; " +
            "plan from its implementation outline, and ask only where it leaves a gap."
        // The build is asked for by whoever asked for the proposal (engine/README.md, "Intake", `by`).
        val body = filer.body(draft, "proposal #$issue", tier, "p3", by = execution.getVariable(Vars.ASKED_BY) as? String)
        val created = filer.file(repo, title, body, "feature", mode)
        execution.setVariable(Vars.BUILT_TICKET, created.number)
        val by = execution.getVariable(DECIDED_BY) as? String ?: "engine"
        execution.setVariable(BUILT_SNAPSHOT, Variables.byteArrayValue(Brief.ticketBytes(Ticket(created.number, title, body, by))))
        log.info("proposal {}: filed build {} ({} mode)", execution.processInstanceId, created.url, mode)
    }
}

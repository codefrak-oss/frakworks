package com.codefrak.engine.proposal

import com.codefrak.engine.workitems.Tickets
import com.codefrak.engine.workitems.commentOn
import com.codefrak.engine.feature.FeatureMode
import com.codefrak.engine.github.IssueSnapshot
import com.codefrak.engine.intake.AuthorizedStart
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.issue
import com.codefrak.engine.smith.repo
import com.codefrak.engine.smith.tier
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * `task-start-build`, after the proposal's merge: starts the build ticket authorized by the user who
 * decided, when they have `feature` access on the repo; otherwise says on it that it waits for
 * `authorized`. Bound by `${startProposalBuild}`.
 */
@Component("startProposalBuild")
class StartProposalBuildDelegate(
    private val repos: TargetRepos,
    private val access: Access,
    private val start: AuthorizedStart,
    private val tickets: Tickets,
) : JavaDelegate {
    private val log = LoggerFactory.getLogger(StartProposalBuildDelegate::class.java)

    override fun execute(execution: DelegateExecution) {
        val repo = execution.repo(); val issue = execution.issue()
        val built = Brief.ticket(execution.getVariable(BUILT_SNAPSHOT) as? ByteArray ?: error("no build filed"))
        val by = execution.getVariable(DECIDED_BY) as? String
        val mode = if (execution.getVariable(Vars.DECISION) == DecisionVerb.BUILD_AUTO) FeatureMode.AUTO else FeatureMode.CONFIRM
        if (by == null || !repos.contains(repo) || !access.trusted(by)) {
            tickets.commentOn(repo, built.number, "Filed as a feature from proposal #$issue. It starts when a trusted login " +
                "starts it (`POST /work-items/{id}/start` or First mate's `approve`)${by?.let { " (@$it, who decided the proposal, is not a trusted login)" }.orEmpty()}.\n\n— engine")
            return
        }
        val tier = execution.tier()
        val instance = start.start(repo, "feature", IssueSnapshot(built.number, built.title, built.body, built.author), by,
            "authorized by @$by via proposal #$issue", tier, mode, execution.getVariable(Vars.DRY_RUN) == true)
        log.info("proposal {}: build {} on {}#{}", execution.processInstanceId, instance, repo, built.number)
    }
}

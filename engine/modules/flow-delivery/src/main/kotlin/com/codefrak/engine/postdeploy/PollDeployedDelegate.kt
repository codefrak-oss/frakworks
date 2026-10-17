package com.codefrak.engine.postdeploy

import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.repo
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.operaton.bpm.engine.impl.util.ClockUtil
import org.operaton.bpm.engine.variable.Variables
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * `${pollDeployed}` (`task-poll-deployed` in bpmn/post-deploy-check.bpmn): asks [check] whether `mergeSha` is in
 * production, once when the flow starts and on each 12 h safety poll (a deploy whose wake was lost). Sets `deployed`;
 * deployed, it sets `deployEvent` too (`type` poll), as a deploy's wake would. A check that cannot answer (no ops
 * server, a GitHub error) fails the job: its retries, then the incident that names it.
 */
@Component("pollDeployed")
class PollDeployedDelegate(private val check: ProductionCheck) : JavaDelegate {
    private val log = LoggerFactory.getLogger(PollDeployedDelegate::class.java)

    override fun execute(execution: DelegateExecution) {
        val repo = execution.repo()
        val sha = (execution.getVariable(Vars.MERGE_SHA) as? String)?.trim()?.lowercase() ?: error("no mergeSha to look for in production")
        val deployed = check.deployed(repo, sha)
        execution.setVariable(Vars.DEPLOYED, deployed)
        if (deployed) execution.setVariable(Vars.DEPLOY_EVENT, Variables.byteArrayValue(poll(repo, sha)))
        log.info("{}: merge {} {} (poll)", repo, sha.take(12), if (deployed) "is in production" else "is not in production yet")
    }

    companion object {
        /** `deployEvent` for a merge a poll found deployed. */
        fun poll(repo: String, sha: String): ByteArray = com.codefrak.engine.engineJson.writeValueAsBytes(
            mapOf("type" to "poll", "repo" to repo, "sha" to sha, "at" to ClockUtil.getCurrentTime().toInstant().toString()))
    }
}

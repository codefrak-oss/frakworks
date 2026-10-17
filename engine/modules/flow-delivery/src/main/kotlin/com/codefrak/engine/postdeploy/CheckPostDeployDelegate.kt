package com.codefrak.engine.postdeploy

import com.codefrak.engine.chore.flow
import com.codefrak.engine.chore.prText
import com.codefrak.engine.repos.RepoMcpServers
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.repo
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.operaton.bpm.engine.variable.Variables
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** Whether something can say when a merge on the repo is in production: the post-deployment check needs it before the merge. */
fun interface OpsServers {
    fun ready(repo: String): Boolean
}

/**
 * [OpsServers] as `DeployedChecks` answers: the engine's own repo (`engine.build.repo`) is answered by the engine's
 * build and needs no server; any other repo needs an ops-role MCP server ([RepoMcpServers.hasOpsServer]).
 */
@Component
class RepoOpsServers(private val servers: RepoMcpServers, @Value("\${engine.build.repo:}") buildRepo: String) : OpsServers {
    private val own = buildRepo.trim().takeIf { it.isNotEmpty() }

    override fun ready(repo: String) = own != null && repo.equals(own, ignoreCase = true) || servers.hasOpsServer(repo)
}

/**
 * `task-check-post-deploy` in bpmn/chore.bpmn, bug.bpmn and feature.bpmn, before the hitlp gate and the merge: reads the
 * PR body's `## POST-DEPLOYMENT-CHECK` section ([PostDeploySection], the work Smith's summary, which is the PR body)
 * into [Vars.POST_DEPLOY_CHECK] ([PostDeployCheck.toBytes]; removed when there is none), which sends the merged ticket
 * to `call-post-deploy-check` instead of `task-close-ticket`. The merge waits for the user ([Vars.POST_DEPLOY_BLOCKED],
 * the reason, which `PostMergeAcceptanceDelegate`'s question on the ticket names) when the section cannot be read
 * ([PostDeploySectionError]), or when the repo has no ops server ([OpsServers]) to say when the merge is in production;
 * the user's accept runs this task again, so the merge goes ahead only once the server is there. Bound by
 * `${checkPostDeploy}`.
 */
@Component("checkPostDeploy")
class CheckPostDeployDelegate(private val ops: OpsServers) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val repo = execution.repo()
        val pr = execution.getVariable(Vars.PR_URL) as? String ?: (execution.getVariable(Vars.PR) as? Number)?.let { "#$it" } ?: "the PR"
        execution.removeVariable(Vars.POST_DEPLOY_BLOCKED)
        val body = prText(execution.flow(), execution.getVariable(Vars.WORK) as? ByteArray, execution.getVariable(Vars.PROPOSAL) as? ByteArray,
            execution.getVariable(Vars.TICKET) as? ByteArray).summary
        val section = try {
            PostDeploySection.parse(body, pr)
        } catch (e: PostDeploySectionError) {
            execution.removeVariable(Vars.POST_DEPLOY_CHECK)
            execution.setVariable(Vars.POST_DEPLOY_BLOCKED, unreadable(e.message!!))
            return
        }
        if (section == null) { execution.removeVariable(Vars.POST_DEPLOY_CHECK); return }
        execution.setVariable(Vars.POST_DEPLOY_CHECK, Variables.byteArrayValue(section.toBytes()))
        if (!ops.ready(repo)) execution.setVariable(Vars.POST_DEPLOY_BLOCKED, noOpsServer(repo))
    }

    companion object {
        fun unreadable(error: String) =
            "its ${PostDeploySection.HEADING} section cannot be read ($error), so the merge cannot be checked after the deploy. " +
                "The work fixes the section (bots/smith.md, \"The post-deployment check\")."

        /** `OpsContract.VERSION` and `OpsContract.TOOL` (engine-plugins, out of this module's reach), as `NoOpsServer` names them. */
        private const val CONTRACT = "ops/1"
        private const val TOOL = "is_deployed"

        fun noOpsServer(repo: String) =
            "it has a ${PostDeploySection.HEADING} section, but $repo has no ops server to say when the merge is in production. " +
                "Add an MCP server with role ops that conforms to the $CONTRACT contract (a tool $TOOL(sha) " +
                "answering {state: deployed|not_yet}): `add_repo_mcp_server(repo: \"$repo\", role: \"ops\", ...)`, or " +
                "`PUT /repos/$repo/mcp-servers/{server}` with role ops."
    }
}

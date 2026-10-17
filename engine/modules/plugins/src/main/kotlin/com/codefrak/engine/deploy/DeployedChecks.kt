package com.codefrak.engine.deploy

import com.codefrak.engine.github.GitHubCompare
import com.codefrak.engine.repos.DeployState
import com.codefrak.engine.repos.OpsContract
import com.codefrak.engine.repos.RepoMcpServers
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** The repo has no ops server, so nothing can say what is in production for it; the message names what it needs. */
class NoOpsServer(repo: String) : RuntimeException(
    "$repo has no ops server: add an MCP server with role ops (add_repo_mcp_server(repo: \"$repo\", role: \"ops\", ...), or " +
        "PUT /repos/$repo/mcp-servers/{server} with role ops) that conforms to the ${OpsContract.VERSION} contract: a tool " +
        "${OpsContract.TOOL}(sha) answering {state: deployed|not_yet}")

/**
 * The engine's one answer to "is commit X in production for repo R?" (#100008; engine/docs/feature.md, "The ops
 * role"). For the engine's own repo (`engine.build.repo`) the answer comes from its build: `deployed` when [sha] is
 * `engine.build.commit` or an ancestor of it (GitHub's compare of `sha...commit` says `ahead` or `identical`),
 * `not_yet` otherwise. For any other repo the repo's ops server ([RepoMcpServers.opsServer], [OpsContract.isDeployed])
 * answers; without one the check throws [NoOpsServer]. [sha] must be 7 to 40 hex ([DeployEvents.SHA]): an empty or
 * malformed one (a flow variable never set) is an [IllegalArgumentException], never a silent `deployed`.
 */
@Component
class DeployedChecks(
    private val servers: RepoMcpServers, private val ops: OpsContract, private val compare: GitHubCompare,
    @Value("\${engine.build.repo:}") buildRepo: String, @Value("\${engine.build.commit:}") buildCommit: String,
) {
    /** The repo this engine was built from, null for a build that was not told; `commit` likewise. */
    val buildRepo: String? = buildRepo.trim().takeIf { it.isNotEmpty() }
    val buildCommit: String? = buildCommit.trim().takeIf { it.isNotEmpty() }?.lowercase()

    fun isDeployed(repo: String, sha: String): DeployState {
        val wanted = sha.trim().lowercase()
        require(DeployEvents.SHA.matches(wanted)) { "cannot say whether '${sha.trim()}' is deployed on $repo: ${DeployEvents.SHA_400}" }
        if (buildRepo != null && repo.equals(buildRepo, ignoreCase = true)) {
            val running = buildCommit ?: throw IllegalStateException("the engine's build commit is not known (engine.build.commit is empty): cannot say whether $wanted is deployed on $repo")
            if (running == wanted || running.startsWith(wanted) || wanted.startsWith(running)) return DeployState.DEPLOYED
            return when (compare.status(repo, wanted, running)) { "ahead", "identical" -> DeployState.DEPLOYED; else -> DeployState.NOT_YET }
        }
        val server = servers.opsServer(repo) ?: throw NoOpsServer(repo)
        return ops.isDeployed(server, wanted)
    }
}

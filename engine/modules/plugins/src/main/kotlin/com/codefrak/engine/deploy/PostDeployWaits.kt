package com.codefrak.engine.deploy

import com.codefrak.engine.postdeploy.DeployReachedWaits
import com.codefrak.engine.postdeploy.ProductionCheck
import com.codefrak.engine.repos.DeployState
import org.springframework.stereotype.Component

/** The post-deployment check's [DeployWaits] (bpmn/post-deploy-check.bpmn): every deploy wakes [DeployReachedWaits]. */
@Component
class PostDeployWaits(private val waits: DeployReachedWaits) : DeployWaits {
    override fun wake(repo: String, sha: String) { waits.wake(repo, sha) }
}

/** The flows' [ProductionCheck], answered by [DeployedChecks]: the engine's build, or the repo's ops server. */
@Component
class DeployedProductionCheck(private val checks: DeployedChecks) : ProductionCheck {
    override fun deployed(repo: String, sha: String): Boolean = checks.isDeployed(repo, sha) == DeployState.DEPLOYED
}

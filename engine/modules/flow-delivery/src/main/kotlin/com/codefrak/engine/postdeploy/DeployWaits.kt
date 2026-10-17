package com.codefrak.engine.postdeploy

import com.codefrak.engine.engineJson
import com.codefrak.engine.smith.Vars
import org.operaton.bpm.engine.MismatchingMessageCorrelationException
import org.operaton.bpm.engine.OptimisticLockingException
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.impl.util.ClockUtil
import org.operaton.bpm.engine.variable.Variables
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** The message bpmn/post-deploy-check.bpmn waits at (`receive-deploy-reached`). */
const val DEPLOY_REACHED_MESSAGE = "deploy reached production"

/**
 * "Is the merge [sha] in production for [repo]?": engine-plugins answers it with `DeployedChecks` (the engine's build
 * for its own repo, the repo's ops server for any other), which this module cannot see. Throws when nothing can say.
 */
fun interface ProductionCheck {
    fun deployed(repo: String, sha: String): Boolean
}

/**
 * Wakes the post-deploy-check flows waiting for their merge to reach production (`receive-deploy-reached`), on each
 * deploy of a repo (`DeployEvents.post` calls [wake] through engine-plugins' `DeployWaits`). A flow of that repo is
 * correlated, with `deployEvent` set, when the deployed [wake] sha is its `mergeSha` (either one a prefix of the
 * other), or else when [check] says the merge is deployed (a later commit went out with it). A flow whose check fails
 * is left waiting for its safety poll and does not stop the others; two wakes racing on one flow correlate it once.
 */
@Component
class DeployReachedWaits(private val runtime: RuntimeService, private val check: ProductionCheck) {
    private val log = LoggerFactory.getLogger(DeployReachedWaits::class.java)

    /** The flows it woke, by process instance id. */
    @Synchronized
    fun wake(repo: String, sha: String): List<String> {
        val deployed = sha.trim().lowercase()
        return runtime.createExecutionQuery().messageEventSubscriptionName(DEPLOY_REACHED_MESSAGE).list().mapNotNull { execution ->
            runCatching {
                val vars = runtime.getVariables(execution.id, listOf(Vars.REPO, Vars.MERGE_SHA))
                if (!(vars[Vars.REPO] as? String).equals(repo, ignoreCase = true)) return@runCatching null
                val merge = (vars[Vars.MERGE_SHA] as? String)?.trim()?.lowercase() ?: error("no mergeSha")
                if (!same(merge, deployed) && !check.deployed(repo, merge)) return@runCatching null
                try {
                    runtime.createMessageCorrelation(DEPLOY_REACHED_MESSAGE).processInstanceId(execution.processInstanceId)
                        .setVariable(Vars.DEPLOY_EVENT, Variables.byteArrayValue(event(repo, deployed)))
                        .correlate()
                } catch (e: MismatchingMessageCorrelationException) {
                    return@runCatching null
                } catch (e: OptimisticLockingException) {
                    return@runCatching null
                }
                log.info("{}: merge {} reached production with {}", repo, merge.take(12), deployed.take(12))
                execution.processInstanceId
            }.onFailure { log.warn("deploy wait {}: {}", execution.processInstanceId, it.message) }.getOrNull()
        }
    }

    companion object {
        /** Whether two shas name one commit: equal, or one the other's prefix (a short sha). */
        fun same(a: String, b: String): Boolean = a.isNotEmpty() && b.isNotEmpty() && (a.startsWith(b) || b.startsWith(a))

        /** `deployEvent` as the check's brief reads it: the deploy's repo, sha and when the engine heard of it. */
        fun event(repo: String, sha: String): ByteArray =
            engineJson.writeValueAsBytes(mapOf("type" to "deploy", "repo" to repo, "sha" to sha, "at" to ClockUtil.getCurrentTime().toInstant().toString()))
    }
}

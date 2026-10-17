package com.codefrak.engine.ci

import com.codefrak.engine.github.GitHubPulls
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.trigger.ScanAction
import org.operaton.bpm.engine.MismatchingMessageCorrelationException
import org.operaton.bpm.engine.OptimisticLockingException
import org.operaton.bpm.engine.RuntimeService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** The message `reusable-ci-check` waits at (`receive-ci-check`). */
const val CI_CHECK_MESSAGE = "ci check completed"

/**
 * Wakes the flows waiting for the engine check (engine/README.md, "Chore"). No Smith waits: the
 * flow is parked at a message, and this runs wherever the scan runs, on every webhook-wake wake
 * (a `check_suite` completed delivery is one), on each repo's scan interval (for that repo's flows
 * alone), at startup and on POST /scan. For each
 * waiting flow it reads the check run on the PR's current head ([followHead]: a push to the branch during the wait
 * moves it), and correlates only a completed one that is
 * not the run a triage re-ran (`ciRerunOf`: until GitHub starts the re-run, the latest is the old one).
 * A GitHub error leaves that flow waiting for the next pass and does not stop the others; two
 * passes racing on one flow correlate it once. Each pass then reads `main`'s new builds for flaky tests
 * ([MainBuildFlakes]); a failure there is logged and changes nothing here.
 */
@Component
class CiCheckWaits(private val runtime: RuntimeService, private val runs: CheckRuns, private val pulls: GitHubPulls,
                   private val mainBuilds: MainBuildFlakes) {
    private val log = LoggerFactory.getLogger(CiCheckWaits::class.java)

    /** `only`: the repos whose waiting flows it reads (matched ignoring case), every repo's when null. */
    @Synchronized
    fun poll(only: Set<String>? = null): List<ScanAction> {
        val waiting = runtime.createExecutionQuery().messageEventSubscriptionName(CI_CHECK_MESSAGE).list()
        val wanted = only?.map { it.lowercase() }?.toSet()
        runCatching { mainBuilds.poll(only) }.onFailure { log.warn("main build flaky-test read: {}", it.message) }
        return waiting.mapNotNull { execution ->
            runCatching {
                val vars = runtime.getVariables(execution.id, listOf(Vars.REPO, Vars.ISSUE, Vars.HEAD_SHA, Vars.PR, Vars.CI_CHECK, Vars.CI_RERUN_OF))
                val repo = vars[Vars.REPO] as String
                if (wanted != null && repo.lowercase() !in wanted) return@runCatching null
                val sha = followHead(pulls, repo, vars) { k, v -> runtime.setVariable(execution.id, k, v) }; val check = vars[Vars.CI_CHECK] as String
                val rerunOf = (vars[Vars.CI_RERUN_OF] as? Number)?.toLong()
                val run = runs.latest(repo, sha, check)?.takeIf { it.status == "completed" && it.id != rerunOf } ?: return@runCatching null
                try {
                    runtime.createMessageCorrelation(CI_CHECK_MESSAGE).processInstanceId(execution.processInstanceId)
                        .setVariable(Vars.CI_CONCLUSION, run.conclusion)
                        .setVariable(Vars.CI_RUN_ID, run.id)
                        .setVariable(Vars.CI_RUN_URL, run.url)
                        .correlate()
                } catch (e: MismatchingMessageCorrelationException) {
                    return@runCatching null
                } catch (e: OptimisticLockingException) {
                    return@runCatching null
                }
                val issue = (vars[Vars.ISSUE] as Number).toLong()
                log.info("{}#{}: check {} on {} completed {}", repo, issue, check, sha.take(12), run.conclusion)
                ScanAction(repo, issue, "ci-check", "checked", "$check on ${sha.take(12)}: ${run.conclusion}")
            }.onFailure { log.warn("ci check wait {}: {}", execution.processInstanceId, it.message) }.getOrNull()
        }
    }

    /** How many flows wait on their engine check per repo, lower-cased: one check-runs read each in a pass. No GitHub call. */
    fun waitingByRepo(): Map<String, Int> =
        runtime.createExecutionQuery().messageEventSubscriptionName(CI_CHECK_MESSAGE).list()
            .mapNotNull { runCatching { (runtime.getVariable(it.id, Vars.REPO) as String).lowercase() }.getOrNull() }
            .groupingBy { it }.eachCount()
}

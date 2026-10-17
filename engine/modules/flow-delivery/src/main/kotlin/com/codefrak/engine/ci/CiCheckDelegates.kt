package com.codefrak.engine.ci

import com.codefrak.engine.github.GitHubPulls
import com.codefrak.engine.github.GitPush
import com.codefrak.engine.github.MergeResult
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.jev.JevContext
import com.codefrak.engine.jev.JevDecider
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.Finding
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.issueOrNull
import com.codefrak.engine.smith.repo
import com.codefrak.engine.smith.round
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.operaton.bpm.engine.variable.Variables
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

private fun DelegateExecution.headSha() = getVariable(Vars.HEAD_SHA) as? String ?: error("no pushed head to check")

private fun DelegateExecution.followHead(pulls: GitHubPulls): String =
    followHead(pulls, repo(), mapOf(Vars.HEAD_SHA to getVariable(Vars.HEAD_SHA), Vars.PR to getVariable(Vars.PR))) { k, v -> setVariable(k, v) }
private fun DelegateExecution.rerunOf() = (getVariable(Vars.CI_RERUN_OF) as? Number)?.toLong()

/**
 * `task-ci-check-needed` in bpmn/reusable-ci-check.bpmn: `waiting` when `open/update pr` named a check the
 * pushed branch needs (`ciCheck`), `skipped` otherwise; clears the last wait's check run, and the run the
 * last round re-ran, either way. Bound by `${ciCheckNeeded}`.
 */
@Component("ciCheckNeeded")
class CiCheckNeededDelegate : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        listOf(Vars.CI_CONCLUSION, Vars.CI_RUN_ID, Vars.CI_RUN_URL, Vars.CI_RERUN_OF, Vars.CI_RETRIGGERS).forEach { execution.setVariable(it, null) }
        // A run `open/update pr` re-ran for a round that declined the failure as not its change's: its old, failed
        // conclusion must not wake this wait.
        (execution.getVariable(Vars.CI_RERUN_PENDING) as? Number)?.let {
            execution.setVariable(Vars.CI_RERUN_OF, it.toLong())
            execution.setVariable(Vars.CI_RERUN_PENDING, null)
        }
        execution.setVariable(Vars.CI_OUTCOME, if (execution.getVariable(Vars.CI_CHECK) != null) "waiting" else "skipped")
    }
}

/**
 * `task-ci-conflict` (`mergeable?`), between `check needed?` and the wait: merges the default branch into
 * the pushed branch with the engine's own git, in a bare clone, and pushes nothing. A conflict is `ciOutcome`
 * `conflict`, with the rebase step's `rebase-<n>-1` finding for the work (`ConflictRounds.recordConflict`,
 * the same count, so the third conflict is the incident): GitHub runs a pull request's check on the merge
 * commit it makes, and makes no run at all for a PR it cannot merge, so waiting would only end in the
 * timeout's incident (PR #529, 2026-09-26). Current or clean leaves `waiting`; a clean merge is dropped,
 * since GitHub builds its own. Bound by `${ciCheckConflict}`.
 */
@Component("ciCheckConflict")
class CiCheckConflictDelegate(private val git: GitPush, private val pulls: GitHubPulls) : JavaDelegate {
    private val log = LoggerFactory.getLogger(CiCheckConflictDelegate::class.java)

    override fun execute(execution: DelegateExecution) {
        val repo = execution.repo()
        val branch = execution.getVariable(Vars.BRANCH) as? String ?: error("no pushed branch to probe")
        val default = pulls.defaultBranch(repo)
        val result = git.mergeDefault(repo, branch, default)
        if (result is MergeResult.Conflict) {
            ConflictRounds.recordConflict(execution, default, result.files)
            execution.setVariable(Vars.CI_OUTCOME, "conflict")
            log.info("{} {}#{}: {} conflicts with {} in {}: no check run to wait for", execution.processInstanceId,
                repo, execution.getVariable(Vars.ISSUE), branch, default, result.files.joinToString())
        } else {
            ConflictRounds.resolved(execution)
        }
    }
}

/**
 * `task-ci-check-retrigger`, on the wait's non-interrupting `event-ci-check-retrigger` timer
 * (`engine.ci-check.retrigger-after`, at most `engine.ci-check.retriggers` times per head): when GitHub has made no run of
 * the check on the PR's current head ([followHead]) at all (it dropped the `pull_request` event, #1574), closes and reopens the PR, whose
 * `reopened` event starts the check again, and counts it in `ciRetriggers`. A run that exists, however slow, is
 * left alone, and so is a wait with no PR. The wait itself goes on untouched: its timeout still bounds it, and its
 * incident says how many re-triggers were tried. Closing without reopening would leave the PR closed, so a failed
 * reopen fails the job (retried, then the incident); a failed close only skips this try. Bound by `${ciCheckRetrigger}`.
 */
@Component("ciCheckRetrigger")
class CiCheckRetriggerDelegate(
    private val runs: CheckRuns,
    private val pulls: GitHubPulls,
    private val checks: CiChecks,
) : JavaDelegate {
    private val log = LoggerFactory.getLogger(CiCheckRetriggerDelegate::class.java)

    override fun execute(execution: DelegateExecution) {
        val repo = execution.repo(); val sha = execution.followHead(pulls)
        val check = execution.getVariable(Vars.CI_CHECK) as String
        val issue = execution.getVariable(Vars.ISSUE)
        val tried = (execution.getVariable(Vars.CI_RETRIGGERS) as? Number)?.toInt() ?: 0
        if (tried >= checks.retriggers) return
        val started = runs.latest(repo, sha, check)
        if (started != null) {
            log.info("{} {}#{}: check {} on {} has a run ({}): no re-trigger", execution.processInstanceId, repo, issue, check, sha.take(12), started.status)
            return
        }
        val pr = (execution.getVariable(Vars.PR) as? Number)?.toLong() ?: run {
            log.warn("{} {}#{}: check {} on {} has not started and there is no PR to reopen", execution.processInstanceId, repo, issue, check, sha.take(12))
            return
        }
        val closed = runCatching { pulls.closePull(repo, pr) }
            .onFailure { log.warn("{} {}#{}: could not close PR #{} to re-trigger check {}: {}", execution.processInstanceId, repo, issue, pr, check, it.message) }
        if (closed.isFailure) return
        pulls.reopenPull(repo, pr)
        execution.setVariable(Vars.CI_RETRIGGERS, tried + 1)
        log.info("{} {}#{}: check {} on {} had not started (a dropped pull_request event): closed and reopened PR #{} to re-trigger it ({} of {})",
            execution.processInstanceId, repo, issue, check, sha.take(12), pr, tried + 1, checks.retriggers)
    }
}

/**
 * `task-ci-check-timed-out`, after `engine.ci-check.timeout` with no completed check: reads the check on the
 * PR's current head ([followHead]) once more, in case a wake was lost, and goes on to `read check` when it has completed (and is not the
 * run a triage re-ran, `ciRerunOf`, whose re-run has not started yet). Otherwise it
 * fails, which is the incident; retrying it looks again, so a retry after the check completes goes on
 * from its result. Bound by `${ciCheckTimedOut}`.
 */
@Component("ciCheckTimedOut")
class CiCheckTimedOutDelegate(private val runs: CheckRuns, private val checks: CiChecks, private val pulls: GitHubPulls) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val check = execution.getVariable(Vars.CI_CHECK) as String
        val sha = execution.followHead(pulls)
        val run = runs.latest(execution.repo(), sha, check)
        check(run != null && run.status == "completed" && run.id != execution.rerunOf()) {
            "the engine check `$check` on ${sha.take(12)} did not complete within ${checks.timeout} (${run?.takeIf { it.id != execution.rerunOf() }?.status ?: "not started"}" +
                "${run?.url?.takeIf { it.isNotBlank() }?.let { ", $it" }.orEmpty()}); retry once it has, or see engine/README.md, \"Chore\"" +
                if (run == null) notStarted((execution.getVariable(Vars.CI_RETRIGGERS) as? Number)?.toInt() ?: 0) else ""
        }
        execution.setVariable(Vars.CI_CONCLUSION, run!!.conclusion)
        execution.setVariable(Vars.CI_RUN_ID, run.id)
        execution.setVariable(Vars.CI_RUN_URL, run.url)
    }

    companion object {
        /** Why GitHub may never have made a run, for whoever reads the incident, and the re-triggers the wait tried. */
        fun notStarted(retriggers: Int): String = ". GitHub makes no run for a pull request it cannot merge: if the PR conflicts " +
            "with the default branch, bring the branch up to date and push. GitHub also drops a pull_request event now and then: " +
            if (retriggers > 0) "the engine re-triggered the check $retriggers time(s) by closing and reopening the PR, and no run started; " +
                "check the repo's Actions (runners, workflow paths), then retry"
            else "the engine re-triggers it by closing and reopening the PR (engine.ci-check.retriggers), and did not here; check the " +
                "repo's Actions (runners, workflow paths), then retry"
    }
}

/**
 * `task-read-ci-check`: maps the completed check's conclusion. success, neutral and skipped are
 * `passed`; failure and timed_out are triaged by Jev ([CiTriage]): a `choice` of code, infrastructure or
 * flaky over the finding's text. infrastructure or flaky at `engine.jev.ci-triage-floor` or above, when
 * this round has not re-run the check yet, re-runs its job and answers `rerun`, back to the wait. Any other
 * reading (code, below the floor, no answer, a second failure, GitHub refusing the re-run) is `failed`, with
 * a `ci-<round>-1` finding for the work carrying the failing tests and the log's end (the job log unreadable,
 * the finding points at the check's page), and Jev's pick when it gave one. Any other conclusion
 * (cancelled, action_required, stale) is an incident: nothing says the code is right or wrong. The call's
 * action is what was done; its outcome is the re-run's conclusion, or the work Smith's answer to the finding
 * ([CiTriage.diagnosed]). Bound by `${readCiCheck}`.
 */
@Component("readCiCheck")
class ReadCiCheckDelegate(
    private val runs: CheckRuns,
    private val jev: JevDecider,
    private val calls: JevCalls,
    private val flaky: FlakyTests,
    @Value("\${engine.jev.ci-triage-floor:0.8}") private val floor: Double = 0.8,
) : JavaDelegate {
    private val log = LoggerFactory.getLogger(ReadCiCheckDelegate::class.java)

    override fun execute(execution: DelegateExecution) {
        val repo = execution.repo(); val sha = execution.headSha()
        val check = execution.getVariable(Vars.CI_CHECK) as String
        val conclusion = execution.getVariable(Vars.CI_CONCLUSION) as? String ?: error("the check woke the wait with no conclusion")
        val url = execution.getVariable(Vars.CI_RUN_URL) as? String ?: ""
        // A re-run's conclusion is the outcome of the call that asked for it.
        val reran = execution.rerunOf() != null
        val pending = (execution.getVariable(Vars.CI_JEV_CALL) as? String)?.takeIf { reran }
        when (conclusion) {
            in PASSED -> {
                pending?.let { calls.recordOutcome(it, "the re-run passed ($conclusion): not the code") }
                if (pending != null) execution.setVariable(Vars.CI_JEV_CALL, null)
                execution.setVariable(Vars.CI_OUTCOME, "passed")
                // A test that passed only on a retry goes to the correctness reviewer, never to a ticket: `main`'s build
                // files those (engine/README.md, "Flaky tests"). A failure to read never changes the check's outcome.
                val lines = runCatching {
                    if (!flaky.reads(repo)) return@runCatching emptyList()
                    val id = (execution.getVariable(Vars.CI_RUN_ID) as Number).toLong()
                    CiLog.flaky(CiLog.clean(runs.log(repo, id))).map { "${it.cls} > ${it.method}()" }
                }.onFailure { log.warn("{}: could not read the passing check's flaky tests: {}", repo, it.message) }.getOrDefault(emptyList())
                execution.setVariable(Vars.CI_FLAKY, lines.takeIf { it.isNotEmpty() }?.joinToString("\n"))
                execution.setVariable(Vars.CI_FLAKY_HEAD, sha)
            }
            in FAILED -> {
                execution.setVariable(Vars.CI_FLAKY, null)
                pending?.let { calls.recordOutcome(it, "the re-run failed too ($conclusion)") }
                val id = (execution.getVariable(Vars.CI_RUN_ID) as Number).toLong()
                val text = runCatching { runs.log(repo, id) }
                    .onFailure { log.warn("{}: could not read the log of check run {}: {}", repo, id, it.message) }.getOrNull()
                val round = execution.round()
                val ask = CiLog.ask(check, conclusion, sha, url, text, id)
                val call = runCatching {
                    jev.decide(CiTriage.USE_CASE, JevContext(execution.processInstanceId, repo, execution.issueOrNull()),
                        mapOf("check" to check, "conclusion" to conclusion, "finding" to ask), mapOf(CiTriage.QUESTION to CiTriage.CAUSES), floor)
                }.onFailure { log.warn("jev ci-triage call failed; the finding goes to the work: {}", it.message) }.getOrNull()
                val answer = call?.answers?.get(CiTriage.QUESTION)
                val pick = answer?.choice?.takeIf { it in CiTriage.CAUSES_BY_NAME }
                var triage = CiTriage.read(pick, answer?.confidence, floor, (execution.getVariable(Vars.CI_RERUN_ROUND) as? Number)?.toInt() != round)
                if (triage.rerun) {
                    // A re-run already under way is the re-run: the wait takes it.
                    val rerun = runCatching { try { runs.rerun(repo, id) } catch (e: RerunAlreadyRunning) {
                        log.info("{}: check run {} is already being re-run; the wait takes that run", repo, id) } }
                        .onFailure { log.warn("{}: GitHub refused to re-run check run {}; the finding goes to the work: {}", repo, id, it.message) }
                    if (rerun.isSuccess) {
                        calls.setAction(call!!.callId, triage.action)
                        execution.setVariable(Vars.CI_RERUN_OF, id)
                        execution.setVariable(Vars.CI_RERUN_ROUND, round)
                        execution.setVariable(Vars.CI_JEV_CALL, call.callId)
                        execution.setVariable(Vars.CI_JEV_FINDING, null)
                        execution.setVariable(Vars.CI_OUTCOME, "rerun")
                        log.info("{} {}#{}: check {} on {}: {}, read by jev as {} ({}): re-run", execution.processInstanceId, repo,
                            execution.getVariable(Vars.ISSUE), check, sha.take(12), conclusion, pick, answer?.confidence)
                        return
                    }
                    triage = CiTriage.Triage(false, "$pick: re-run refused: to the work")
                }
                call?.let { calls.setAction(it.callId, triage.action) }
                val findingId = "ci-$round-1"
                val confidence = answer?.confidence
                val note = if (pick != null && confidence != null) CiTriage.note(pick, confidence, floor, reran) else ""
                val finding = Finding(ask + note, "high", id = findingId, review = "ci")
                val raised = Brief.findings(execution.getVariable(Vars.RAISED) as? ByteArray) + finding
                execution.setVariable(Vars.FINDINGS, Variables.byteArrayValue(Brief.findingsBytes(listOf(finding))))
                execution.setVariable(Vars.RAISED, Variables.byteArrayValue(Brief.findingsBytes(raised)))
                // The work Smith's answer to the finding is this call's outcome, when there was a pick to judge.
                execution.setVariable(Vars.CI_JEV_CALL, call?.callId?.takeIf { pick != null })
                execution.setVariable(Vars.CI_JEV_FINDING, findingId.takeIf { pick != null })
                execution.setVariable(Vars.CI_OUTCOME, "failed")
            }
            else -> error("the engine check `$check` on ${sha.take(12)} ended $conclusion, which says nothing about the code; re-run it on GitHub, then retry ($url)")
        }
        log.info("{} {}#{}: check {} on {}: {}", execution.processInstanceId, repo, execution.getVariable(Vars.ISSUE), check, sha.take(12), conclusion)
    }

    companion object {
        val PASSED = setOf("success", "neutral", "skipped")
        val FAILED = setOf("failure", "timed_out")
    }
}

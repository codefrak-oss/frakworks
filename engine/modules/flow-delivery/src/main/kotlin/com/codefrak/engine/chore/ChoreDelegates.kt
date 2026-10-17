package com.codefrak.engine.chore

import com.codefrak.engine.ci.CheckRuns
import com.codefrak.engine.ci.CiChecks
import com.codefrak.engine.ci.ConflictRounds
import com.codefrak.engine.ci.RerunAlreadyRunning
import com.codefrak.engine.github.CreatedPull
import com.codefrak.engine.github.GitHubPulls
import com.codefrak.engine.github.GitPush
import com.codefrak.engine.github.MergeConflict
import com.codefrak.engine.github.PullExists
import com.codefrak.engine.github.PushRejected
import com.codefrak.engine.proposal.ProposeResult
import com.codefrak.engine.repos.DocsOnly
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.WorkResult
import com.codefrak.engine.smith.issue
import com.codefrak.engine.smith.repo
import com.codefrak.engine.smith.round
import org.operaton.bpm.engine.delegate.BpmnError
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.operaton.bpm.engine.impl.context.Context
import org.operaton.bpm.engine.impl.interceptor.Command
import org.operaton.bpm.engine.variable.Variables
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

internal fun DelegateExecution.work() = WorkResult.parse(getVariable(Vars.WORK) as? ByteArray ?: error("no work result to act on"))

/** What the PR says: the work Smith's title and summary, or in the proposal flow a title and the propose Smith's summary. */
internal data class PrText(val title: String, val summary: String)

/**
 * The rule behind [PrText]: the work Smith's title and summary, or in the proposal flow (which has no work
 * result) `Proposal: <ticket title>` and the propose Smith's summary (engine/README.md, "Proposal").
 */
internal fun prText(flow: String?, work: ByteArray?, proposal: ByteArray?, ticket: ByteArray?): PrText {
    if (flow != "proposal") return WorkResult.parse(work ?: error("no work result to act on")).let { PrText(it.title!!, it.summary!!.trim()) }
    val parsed = ProposeResult.parse(proposal ?: error("no proposal to act on"))
    val title = ticket?.let { Brief.ticket(it).title } ?: parsed.path!!
    return PrText("Proposal: $title", parsed.summary!!.trim())
}

private fun DelegateExecution.prText(): PrText =
    prText(flow(), getVariable(Vars.WORK) as? ByteArray, getVariable(Vars.PROPOSAL) as? ByteArray, getVariable(Vars.TICKET) as? ByteArray)

/**
 * `task-open-pr` in bpmn/chore.bpmn: pushes the work Smith's bundle (or `bring pr up to date`'s merge) to
 * `smith/issue-<n>`, opens the PR on the first round and comments the round
 * on later ones. Sets `branch`, `pr`, `prUrl`, and `round` (pushes so far), and for the engine check
 * after it (bpmn/reusable-ci-check.bpmn) `headSha`, the pushed head, and `ciCheck`, the check the branch
 * waits for: null unless its repo has one ([CiChecks]) and the branch changes one of its paths; and
 * `securityReview`, the repo row's setting ([TargetRepos.securityReview]), read here so a toggle takes effect at the next push;
 * and `docsOnly`, whether every path the branch changes is documentation ([DocsOnly]): the docs path skips the
 * security review (engine/README.md, "Docs path"), and any other path keeps the full pipeline;
 * and `hitlpChange`, whether one changes the hitlp module or its migrations ([HitlpPaths]): such a PR waits for the
 * user's acceptance before the merge (engine/README.md, "Hitlp changes wait for the user"), so any earlier
 * `mergeAccepted` is removed at every push.
 * In a chore, bug or feature the push comes first: the engine check, then the security and correctness
 * reviews, run on what it pushed (engine/README.md, "Why the engine check runs before the reviews").
 * A push the remote rejects ([PushRejected]) fails the same way on every try, so it is the incident at
 * once ([failWithoutRetries]), and so does a bundle without the branch ([GitPush.MISSING_BRANCH]), whose retry
 * reruns the Smith; any other failure keeps Operaton's retries. Bound by `${openPr}`.
 */
@Component("openPr")
class OpenPrDelegate(private val git: GitPush, private val pulls: GitHubPulls, private val checks: CiChecks,
                     private val repos: TargetRepos, private val runs: CheckRuns) : JavaDelegate {
    private val log = LoggerFactory.getLogger(OpenPrDelegate::class.java)

    override fun execute(execution: DelegateExecution) {
        val repo = execution.repo(); val issue = execution.issue()
        val text = execution.prText()
        val branch = "smith/issue-$issue"
        val round = execution.round() + 1
        val pr = (execution.getVariable(Vars.PR) as? Number)?.toLong()
        if (pr != null && execution.getVariable(Vars.CI_NOT_MINE) == true) {
            // The work declined the engine check's failure as not this change's and committed nothing: nothing is
            // pushed, so the head, its check and its bundle stay; the failed run is re-run on them, and the next
            // wait ignores the old run (engine/README.md, "Chore", "A ci finding the work declines").
            val failed = (execution.getVariable(Vars.CI_RUN_ID) as? Number)?.toLong() ?: error("no failed check run to re-run")
            // A ticket's first commit has no earlier round's bundle to keep: the pushed head is bundled again for the reviews.
            if (execution.getVariable(Vars.BUNDLE) == null)
                execution.setVariable(Vars.BUNDLE, Variables.byteArrayValue(git.headBundle(repo, branch, pulls.defaultBranch(repo))))
            // The check may have re-run and passed on the head since the finding (a re-run by hand, or GitHub's): the next
            // wait takes that run, so nothing is re-run.
            val head = execution.getVariable(Vars.HEAD_SHA) as? String
            val check = execution.getVariable(Vars.CI_CHECK) as? String
            val latest = if (head != null && check != null) runs.latest(repo, head, check) else null
            val passed = latest != null && latest.id != failed && latest.status == "completed" && latest.conclusion == "success"
            // A re-run already under way (seen on the head, or refused by GitHub as already running) is the re-run:
            // nothing is posted, and the next wait takes it.
            val underWay = latest != null && latest.id != failed && latest.status != "completed"
            val done = when {
                passed -> "it has passed since"
                underWay -> "a re-run was already under way for"
                else -> try { runs.rerun(repo, failed); "re-ran" } catch (e: RerunAlreadyRunning) { "a re-run was already under way for" }
            }
            execution.setVariable(Vars.CI_RERUN_PENDING, if (passed) null else failed)
            execution.setVariable(Vars.CI_NOT_MINE, false)
            execution.setVariable(Vars.ROUND, round)
            execution.setVariable(Vars.SECURITY_REVIEW, repos.securityReview(repo))
            pulls.comment(repo, pr, notMineComment(round, execution.work()))
            log.info("{} {}: round {} declined the check's failure as not its change's; {} check run {}",
                execution.flow(), execution.processInstanceId, round, done, failed)
            return
        }
        val bundle = execution.getVariable(Vars.BUNDLE) as? ByteArray ?: error("the work smith left no commits")
        val default = pulls.defaultBranch(repo)
        val pushed = try {
            git.push(repo, branch, bundle, default)
        } catch (e: PushRejected) {
            log.warn("{} {}: push of {} rejected: {}", execution.flow(), execution.processInstanceId, e.ref, e.reason)
            failWithoutRetries(execution)
            throw e
        } catch (e: IllegalStateException) {
            if (e.message?.let { it.startsWith(GitPush.MISSING_BRANCH) || it.startsWith(GitPush.REWRITES) } == true) {
                log.warn("{} {}: {}", execution.flow(), execution.processInstanceId, e.message)
                failWithoutRetries(execution)
            }
            throw e
        }
        if (pr == null) {
            val marker = "<!-- engine: ${execution.flow()} ${execution.processInstanceId} -->"
            val body = "${text.summary}\n\nCloses #$issue\n\n$marker\n"
            // An earlier try may have opened the PR and rolled back before `pr` was stored: adopt it, never open a second.
            val created = adopt(repo, branch, marker) ?: try {
                pulls.open(repo, branch, default, text.title, body)
            } catch (e: PullExists) {
                adopt(repo, branch, marker) ?: throw e
            }
            execution.setVariable(Vars.PR, created.number)
            execution.setVariable(Vars.PR_URL, created.url)
            log.info("{} {}: opened {}", execution.flow(), execution.processInstanceId, created.url)
        } else {
            val merge = (execution.getVariable(Vars.MERGE) as? ByteArray)?.let { Brief.merge(it) }
            pulls.comment(repo, pr, when {
                merge != null -> catchUpComment(round, merge["default"] as? String ?: "the default branch")
                execution.flow() == "proposal" -> redraftComment(round, text.summary)
                else -> roundComment(round, execution.work())
            })
        }
        execution.setVariable(Vars.BRANCH, branch)
        execution.setVariable(Vars.ROUND, round)
        execution.setVariable(Vars.HEAD_SHA, pushed.head)
        // The last check's flaky tests were that head's: the new one has its own check.
        execution.setVariable(Vars.CI_FLAKY, null)
        execution.setVariable(Vars.CI_CHECK, checks.needed(repo, pushed.paths))
        execution.setVariable(Vars.SECURITY_REVIEW, repos.securityReview(repo))
        execution.setVariable(Vars.DOCS_ONLY, DocsOnly.of(pushed.paths))
        // A new head needs its own acceptance: an earlier round's never carries over to what is pushed now.
        execution.setVariable(Vars.HITLP_CHANGE, HitlpPaths.matches(pushed.paths))
        execution.removeVariable(Vars.MERGE_ACCEPTED)
        // The push landed: a retried refusal's `push-<n>-1` finding is answered and is dropped (IncidentRetrier).
        ConflictRounds.afterPush(Brief.findings(execution.getVariable(Vars.FINDINGS) as? ByteArray))?.let {
            execution.setVariable(Vars.FINDINGS, if (it.isEmpty()) null else Variables.byteArrayValue(Brief.findingsBytes(it)))
        }
        // `bundle` and `merge` stay: the security review after the engine check reviews exactly these
        // pushed commits; the next work run's collect replaces both.
    }

    /**
     * The open PR on `branch` whose body carries this flow's `marker`, or null when `branch` has none open.
     * An open PR without it (a person's, or an earlier flow's on the same ticket) is not adopted and not
     * opened over: it fails with Operaton's retries kept, so it is the incident once they run out.
     */
    private fun adopt(repo: String, branch: String, marker: String): CreatedPull? {
        val open = pulls.openOnHead(repo, branch)
        if (open.isEmpty()) return null
        val own = open.firstOrNull { marker in it.body }
            ?: error("$repo $branch already has an open pull request the engine did not open for this flow: " +
                open.joinToString { "#${it.number}" } + "; close it, then retry")
        return CreatedPull(own.number, own.url)
    }

    /**
     * Leaves this job's last try: its retries set to 1 in a transaction of their own (this one rolls back
     * with the throw), so Operaton's failed-job handling takes them to 0 and makes the incident, with the
     * exception's message, after this attempt. `task-open-pr` has a `failedJobRetryTimeCycle` (`R3/PT30S`),
     * and Operaton's retry command takes a job with no stored exception for its first execution and resets
     * its retries to the cycle's 3; so the same transaction stores an exception message too, the job counts
     * as failed before, and the cycle only decrements the 1. Operaton then overwrites the message with the
     * real one. A retry of the incident sets the retries to 1 again and pushes the stored bundle.
     */
    private fun failWithoutRetries(execution: DelegateExecution) {
        val job = execution.processEngineServices.managementService.createJobQuery()
            .processInstanceId(execution.processInstanceId).activityId(execution.currentActivityId).singleResult() ?: return
        Context.getProcessEngineConfiguration().commandExecutorTxRequiresNew.execute(Command { context ->
            context.jobManager.findJobById(job.id)?.apply { retries = 1; exceptionMessage = "push rejected" }
        })
    }

    companion object {
        /** A round that only merged the default branch in: a catch-up, not rework. */
        fun catchUpComment(round: Int, default: String) =
            "Round $round, catch-up: merged $default into the branch; no changes from review"

        /** A round that changed nothing because the engine check's failure is not the branch's: why, and that the check is re-run. */
        fun notMineComment(round: Int, work: WorkResult) = buildString {
            appendLine("Round $round: no changes. The engine check's failure is not from this branch, so it is re-run on the same commits.")
            work.declined.forEach { appendLine().append("- ${it.finding}: ${it.why}") }
            appendLine()
        }

        /** A proposal's later round: the propose Smith redrafted the doc, from findings or the user's answers. */
        fun redraftComment(round: Int, summary: String) = "Round $round, redrafted: $summary"

        /** What changed in a later round, for the humans watching the PR; the findings themselves stay in the engine. */
        fun roundComment(round: Int, work: WorkResult) = buildString {
            appendLine("Round $round: ${work.summary!!.trim()}")
            if (work.addressed.isNotEmpty()) appendLine().appendLine("Addressed: ${work.addressed.joinToString { it.finding }}")
            if (work.declined.isNotEmpty()) {
                appendLine().appendLine("Declined:")
                work.declined.forEach { appendLine("- ${it.finding}: ${it.why}") }
            }
        }
    }
}

/**
 * `task-merge-pr` in bpmn/chore.bpmn, bug.bpmn, feature.bpmn and proposal.bpmn: squash-merges the PR once
 * both reviews pass (a proposal's, once it is reviewed and decided). GitHub's merge conflict is the BPMN error `merge-conflict`, which goes back to `bring pr up to date`.
 * The merge commit's sha is kept as `mergeSha` ([Vars.MERGE_SHA]; #100008, for the post-deploy check). Bound by `${mergePr}`.
 */
@Component("mergePr")
class MergePrDelegate(private val pulls: GitHubPulls) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        try {
            val sha = pulls.merge(execution.repo(), (execution.getVariable(Vars.PR) as Number).toLong(), execution.prText().title)
            execution.setVariable(Vars.MERGE_SHA, sha)
        } catch (e: MergeConflict) {
            throw BpmnError("merge-conflict", e.message)
        }
    }
}

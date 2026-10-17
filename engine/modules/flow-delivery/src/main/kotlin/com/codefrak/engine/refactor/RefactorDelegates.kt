package com.codefrak.engine.refactor

import com.codefrak.engine.engineJson
import com.codefrak.engine.feature.FeatureMode
import com.codefrak.engine.github.IssueSnapshot
import com.codefrak.engine.intake.AuthorizedStart
import com.codefrak.engine.intake.TicketFiler
import com.codefrak.engine.jobs.JOB_OUTCOME
import com.codefrak.engine.jobs.JobCreators
import com.codefrak.engine.jobs.JobRuns
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.issue
import com.codefrak.engine.smith.repo
import com.codefrak.engine.workitems.TicketState
import com.codefrak.engine.workitems.Tickets
import com.codefrak.engine.workitems.commentOn
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** The label every refactoring ticket carries, and what a run lists to find the keys already open. */
const val REFACTOR_LABEL = "refactor"

/** The keys on the repo's open tickets flagged `refactor`, from their trailers (`TicketFiler.keyOf`): one listing, no read per ticket. */
fun Tickets.openRefactorKeys(repo: String): Set<String> =
    open(repo).filter { it.flags.refactor }.mapNotNull { TicketFiler.keyOf(it.body) }.toSet()

/** The kind and label of the ticket a refactoring job files, worked by bpmn/refactor-scan.bpmn. */
const val REFACTOR_SCAN = "refactor-scan"

/** The repo's config when refactoring is on and its job is [jobId] (any job when null); null when off. */
fun RefactorConfigStore.inForce(repo: String, jobId: String?): RefactorConfig? =
    row(repo)?.takeIf { it.enabled && (jobId == null || it.jobId == jobId) }

/**
 * `task-file-refactor-scan` in bpmn/job.bpmn (engine/README.md, "Refactoring jobs"): the scheduled job's only work. It
 * files a `refactor-scan` ticket whose body is the instructions ([RefactorPrompt], from the config's values and caps and
 * the keys open now), at the config's tier, and starts it through [AuthorizedStart] (label event 0, `authorized` added as
 * the visible record), the way approval `none` starts a feature. Nothing is filed, and `outcome` says why, when the config
 * is off or its job another (`failed: refactoring is off for <repo>`), or has no tier (`failed: refactoring on <repo> has
 * no tier`: a scan never runs outside the tier map). While a `refactor-scan` issue is open on the repo the run files
 * nothing and `summary` names it, so a short schedule does not pile up scans.
 */
@Component("fileRefactorScan")
class FileRefactorScanDelegate(
    private val configs: RefactorConfigStore, private val jobs: JobCreators, private val tickets: Tickets,
    private val filer: TicketFiler, private val start: AuthorizedStart, private val runs: JobRuns,
) : JavaDelegate {
    private val log = LoggerFactory.getLogger(FileRefactorScanDelegate::class.java)

    override fun execute(execution: DelegateExecution) {
        val repo = execution.repo()
        val jobId = execution.getVariable(Vars.JOB_ID) as String
        val config = configs.inForce(repo, jobId)
        if (config == null) {
            log.info("refactor job {} on {}: skipped, refactoring is off", jobId, repo)
            execution.setVariable(JOB_OUTCOME, "failed: refactoring is off for $repo")
            return
        }
        val tier = config.tier
        if (tier == null) {
            log.info("refactor job {} on {}: skipped, no tier", jobId, repo)
            execution.setVariable(JOB_OUTCOME, "failed: refactoring on $repo has no tier: set one with PUT /repos/$repo/refactor")
            return
        }
        val listed = tickets.open(repo)
        listed.firstOrNull { it.kind == REFACTOR_SCAN }?.let { open ->
            execution.setVariable(FileRefactorTicketsDelegate.SUMMARY, "filed nothing: refactor-scan #${open.ticket} is still open")
            log.info("refactor job {} on {}: #{} still open, nothing filed", jobId, repo, open.ticket)
            return
        }
        val open = listed.filter { it.flags.refactor }.mapNotNull { TicketFiler.keyOf(it.body) }.toSet()
        val by = jobs.createdBy(jobId) ?: "engine"
        val title = "Refactoring scan of $repo"
        val body = filer.body(RefactorPrompt.build(config.values, config.maxTickets, config.maxLines, open), "refactor job $jobId", tier = tier, priority = "p3", by = by)
        val created = filer.file(repo, title, body, REFACTOR_SCAN, featureMode = null)
        // As approval `none` starts a feature: the engine's start is label event 0, the fleet's `authorized` label the visible record.
        start.start(repo, REFACTOR_SCAN, IssueSnapshot(created.number, title, body, by), by, "refactor job $jobId", tier = tier, mode = null,
            dryRun = false, label = "authorized")
        runs.scanFiled(execution.processInstanceId, created.number)
        execution.setVariable(FileRefactorTicketsDelegate.SUMMARY, "filed refactor-scan #${created.number}")
        log.info("refactor job {} on {}: filed refactor-scan #{} at tier {}", jobId, repo, created.number, tier)
    }
}

/**
 * `task-prepare-scan` in bpmn/refactor-scan.bpmn: the scan's prompt for `call-callable-smith`, the ticket's body as filed
 * (the instructions) less the engine's trailer. With the repo's refactoring off meanwhile, [SKIPPED] is true, `outcome`
 * is `failed: refactoring is off for <repo>` and no sandbox starts. The model and effort came with the start, from the
 * ticket's tier.
 */
@Component("prepareRefactorScan")
class PrepareRefactorScanDelegate(private val configs: RefactorConfigStore) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val repo = execution.repo()
        if (configs.inForce(repo, null) == null) {
            execution.setVariable(JOB_OUTCOME, "failed: refactoring is off for $repo")
            execution.setVariable(SKIPPED, true)
            return
        }
        val ticket = Brief.ticket(execution.getVariable(Vars.TICKET) as ByteArray)
        val prompt = ticket.body.replace(TRAILER, "").trimEnd() + "\n"
        execution.setVariable(SKIPPED, false)
        // Bytes, not a String: the prompt quotes the values text and runs past the 4000 characters a String variable holds (#1238).
        execution.setVariable(Vars.PROMPT, Brief.textValue(prompt))
    }

    companion object {
        /** True when the scan ended before any sandbox: `gateway-scan-prepared` reads it. */
        const val SKIPPED = "refactorSkipped"
        private val TRAILER = Regex("""<!--\s*engine:[^>]*-->""")
    }
}

/**
 * `task-close-scan` in bpmn/refactor-scan.bpmn: comments what the scan filed (`summary`), or why it filed nothing
 * (`outcome`), on the ticket and closes it: completed when the scan ran to `done`, not planned otherwise. Both go on the
 * job run that filed the scan ([JobRuns.scanEnded]).
 */
@Component("closeRefactorScan")
class CloseRefactorScanDelegate(private val tickets: Tickets, private val runs: JobRuns) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val repo = execution.repo()
        val issue = execution.issue()
        val outcome = execution.getVariable(JOB_OUTCOME) as? String
        val summary = execution.getVariable(FileRefactorTicketsDelegate.SUMMARY) as? String
        val done = outcome == "done" && summary != null
        runs.scanEnded(repo, issue, outcome, summary.takeIf { done })
        tickets.commentOn(repo, issue, (if (done) "Refactoring scan done: $summary." else "Refactoring scan ended ${outcome ?: "without an outcome"}; nothing filed.") + "\n\n— engine")
        tickets.end(repo, issue, if (done) TicketState.DONE else TicketState.CANCELLED, "refactoring scan ${outcome ?: "ended"}")
    }
}

/**
 * `task-file-refactor-tickets` in bpmn/refactor-scan.bpmn: files the scan's proposals as tickets. A `failed` Smith outcome passes
 * through unchanged with nothing filed. The result's `summary.proposals` (a wrong shape is `failed: the run's summary has
 * no proposals array`) is filtered: a key already open (re-read now, not at the prompt) or seen earlier in the run, a
 * `lines` over the cap, or a malformed proposal is dropped; the first `maxTickets` left are filed through [TicketFiler]
 * as `feature` + `refactor`, trailer `refactor-scan #<n> · by <asker> · key <key>`, with the cap and the values quoted under the body.
 * Approval `human`: `feature:confirm`, no `authorized`, a human authorizes and confirms each round. Approval `none`:
 * `feature:auto`, the feature started at once through [AuthorizedStart] (label event 0, so the scan skips a later `authorized`
 * label) and `authorized` added as the visible record once the job commits. `outcome` is `done`, `summary` says what
 * was filed and skipped; the filed numbers, the skips by reason and whether the scan proposed nothing at all go on the
 * job run that filed the scan ([JobRuns.scanResult]).
 */
@Component("fileRefactorTickets")
class FileRefactorTicketsDelegate(
    private val configs: RefactorConfigStore, private val tickets: Tickets,
    private val filer: TicketFiler, private val start: AuthorizedStart, private val runs: JobRuns,
) : JavaDelegate {
    private val log = LoggerFactory.getLogger(FileRefactorTicketsDelegate::class.java)

    override fun execute(execution: DelegateExecution) {
        val repo = execution.repo()
        val issue = execution.issue()
        val scan = "$REFACTOR_SCAN #$issue"
        val outcome = execution.getVariable(JOB_OUTCOME) as? String
        if (outcome != "done") {
            log.info("{} on {}: the run ended {}, nothing filed", scan, repo, outcome)
            return
        }
        val config = configs.inForce(repo, null)
        if (config == null) {
            execution.setVariable(JOB_OUTCOME, "failed: refactoring is off for $repo")
            return
        }
        // Text, or the bytes `setCallableResult` stores a result past 4000 characters as (a few full ticket bodies are).
        val result = Brief.text(execution.getVariable(Vars.RESULT))?.let { runCatching { engineJson.readTree(it) }.getOrNull() }
        val proposals = result?.get("summary")?.get("proposals")?.takeIf { it.isArray }
        if (proposals == null) {
            execution.setVariable(JOB_OUTCOME, "failed: the run's summary has no proposals array")
            return
        }
        // An empty scan names what it read (#2283): scans that wrote the prompt's empty fallback in 15 s, unread, looked the same as
        // scans that surveyed the code and found nothing.
        if (proposals.size() == 0 && !inspectedSomething(result.get("summary"))) {
            execution.setVariable(JOB_OUTCOME, "failed: the run proposed nothing and named nothing it inspected")
            return
        }
        val open = tickets.openRefactorKeys(repo).toMutableSet()
        var skippedOpen = 0; var skippedOversize = 0; var skippedMalformed = 0; var skippedOverCap = 0
        val filed = mutableListOf<Long>()
        // The scan flow has no `askedBy`: the asker is the scan ticket's trailer `by` (the job's), else its author.
        val by = Brief.ticket(execution.getVariable(Vars.TICKET) as ByteArray).let { TicketFiler.askedByOf(it.body) ?: it.author }
        for (node in proposals) {
            val proposal = RefactorProposal.of(node)
            if (proposal == null) { skippedMalformed++; continue }
            if (proposal.key in open) { skippedOpen++; continue }
            if (proposal.lines > config.maxLines) { skippedOversize++; continue }
            if (filed.size >= config.maxTickets) { skippedOverCap++; continue }
            open += proposal.key
            val body = filer.body(ticketBody(proposal, config, scan), scan, tier = null, priority = "p3", by = by, key = proposal.key)
            val mode = if (config.approval == RefactorApproval.NONE) FeatureMode.AUTO else FeatureMode.CONFIRM
            val created = filer.file(repo, proposal.title, body, "feature", mode, extraLabels = listOf(REFACTOR_LABEL))
            filed += created.number
            log.info("{} on {}: filed #{} ({}, {} lines) as feature:{}", scan, repo, created.number, proposal.key, proposal.lines, mode)
            if (config.approval == RefactorApproval.NONE) {
                // The start is the engine's (label event 0) and the `authorized` label its visible record, which the scan skips on a
                // label-event-0 ticket. The label is written after this job commits (AuthorizedStart), so the scan its webhook
                // wakes finds the claim (#1802).
                start.start(repo, "feature", IssueSnapshot(created.number, proposal.title, body, by), by, scan, tier = null, mode = FeatureMode.AUTO,
                    dryRun = false, label = "authorized")
            }
        }
        val summary = buildString {
            append(if (proposals.size() == 0) "found nothing" else if (filed.isEmpty()) "filed nothing" else "filed ${filed.joinToString { "#$it" }}")
            val skipped = listOfNotNull("$skippedOpen open".takeIf { skippedOpen > 0 }, "$skippedOversize oversize".takeIf { skippedOversize > 0 },
                "$skippedOverCap over the cap".takeIf { skippedOverCap > 0 }, "$skippedMalformed malformed".takeIf { skippedMalformed > 0 })
            if (skipped.isNotEmpty()) append("; skipped ").append(skipped.joinToString(", "))
        }
        execution.setVariable(SUMMARY, summary)
        runs.scanResult(repo, issue, filed, linkedMapOf("open" to skippedOpen, "oversize" to skippedOversize, "overCap" to skippedOverCap,
            "malformed" to skippedMalformed).filterValues { it > 0 }, foundNothing = proposals.size() == 0)
        log.info("{} on {}: {}", scan, repo, summary)
    }

    companion object {
        /** Whether a scan's `summary` has an `inspected` array with at least one non-blank path: what an empty scan must show. */
        fun inspectedSomething(summary: tools.jackson.databind.JsonNode?): Boolean =
            summary?.get("inspected")?.takeIf { it.isArray }?.any { it.isTextual && it.asText().isNotBlank() } == true

        /** The variable with what the scan filed and skipped (and, on the job, what it filed). */
        const val SUMMARY = "summary"
        /** The heading under which the filed ticket quotes the values. */
        const val VALUES_HEADING = "### The values this serves"
    }
}

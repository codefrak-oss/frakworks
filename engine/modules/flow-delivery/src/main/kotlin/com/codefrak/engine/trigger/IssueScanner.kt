package com.codefrak.engine.trigger

import com.codefrak.engine.chore.FlowStarter
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.chore.ProcessFlowStarter
import com.codefrak.engine.feature.FeatureMode
import com.codefrak.engine.github.Blocker
import com.codefrak.engine.github.IssueEvent
import com.codefrak.engine.github.IssueSnapshot
import com.codefrak.engine.github.issueUrl
import com.codefrak.engine.intake.IntakeRefs
import com.codefrak.engine.intake.TicketFiler
import com.codefrak.engine.repos.Deferred
import com.codefrak.engine.repos.DeferredIssues
import com.codefrak.engine.repos.HandledIssues
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.workitems.Tickets
import com.codefrak.engine.workitems.blockers
import com.codefrak.engine.workitems.commentOn
import com.codefrak.engine.workitems.labelEvents
import com.codefrak.engine.workitems.snapshot
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/** `repos`: the target repos the scan covered. */
data class ScanReport(val actions: List<ScanAction>, val failures: List<String>, val repos: List<String> = emptyList())

/**
 * Acts on approvals of tickets and wakes the ones waiting (engine/README.md, "Starting a flow"). An approval
 * ([consider]) comes from the engine itself, never from a label on GitHub: an intake's carry-over or a trusted login's
 * `approve` ([IssueApprovals.approveTicket]) on a work item (engine/docs/work-items.md, "Tickets"). It starts the flow
 * of the one kind the ticket has, only if its actor may authorize that kind there. Each approval is acted on once
 * (`engine.handled_issue`). An approval of work with an open blocker is not acted on but deferred
 * (`engine.deferred_issue`), and each scan (startup, a repo's scan interval, a wake, or POST /scan) starts the deferred
 * ones whose blockers all closed by a merge, and wakes the split features waiting on their children.
 */
@Component
class IssueScanner(
    private val repos: TargetRepos,
    private val access: Access,
    private val handled: HandledIssues,
    private val tickets: Tickets,
    private val starter: FlowStarter,
    private val deferred: DeferredIssues,
    private val intakes: IntakeRefs,
    private val clock: ScanClock,
    private val events: EngineEvents,
    private val deferredBlockers: DeferredBlockers,
) : DeferredStarts {
    private val log = LoggerFactory.getLogger(IssueScanner::class.java)

    /**
     * Scans `only` those target repos' waiting approvals, or every one's when null: the scheduler's tick passes the
     * repos whose interval is up, everything else scans them all. Records each covered repo's scan with [ScanClock], so
     * a wake or POST /scan also restarts its interval.
     */
    @Synchronized
    fun scan(only: Set<String>? = null): ScanReport {
        val actions = mutableListOf<ScanAction>()
        val failures = mutableListOf<String>()
        val targets = repos.list().map { it.fullName }.filter { only == null || it in only }
        for (row in deferred.list().filter { it.repo in targets }) {
            runCatching { resume(row)?.let { actions += it } }
                .onFailure { failures += "${row.repo}#${row.issue}: ${it.message}"; log.warn("resume {}#{}: {}", row.repo, row.issue, it.message) }
        }
        clock.scanned(targets, clock.now())
        if (actions.isNotEmpty() || failures.isNotEmpty()) log.info("scan: {} acted on, {} failed", actions.size, failures.size)
        return ScanReport(actions, failures, targets)
    }

    /** Who asked for the ticket (engine/README.md, "Intake", `by`): its trailer's `by`, else its GitHub author. */
    internal fun askerOf(ticket: IssueSnapshot): String = TicketFiler.askedByOf(ticket.body) ?: ticket.author

    /**
     * A `reauthorize` row of `GET /needs` appeared or went (engine/README.md, "Events"): a deferred row with a blocker closed
     * without a merge (`stalled`) and none open. Published after the command commits when a delegate called the scan.
     */
    private fun reauthorizeNeed(repo: String, issue: Long, state: String) =
        events.publishAfterCommit(EngineEvent.Needs(repo, "reauthorize", state, issue, issueUrl(repo, issue)))

    /** Removes the row, and says its `reauthorize` need went when it had one. */
    internal fun forget(row: Deferred) {
        deferred.remove(row.repo, row.issue)
        if (row.stalled.isNotEmpty()) reauthorizeNeed(row.repo, row.issue, "removed")
    }

    internal fun forget(repo: String, issue: Long) = deferred.get(repo, issue)?.let(::forget)

    /** The blocker's [com.codefrak.engine.trigger.intakeNote], resolving its intake ref first. */
    private fun intakeNote(repo: String, b: Blocker): String {
        if (!b.repo.equals(repo, ignoreCase = true)) return ""
        val ref = runCatching { intakes.resolve(repo, "#${b.number}") }.getOrNull() ?: return ""
        return intakeNote(b, ref)
    }

    /**
     * Resumes, at once, the split features waiting on `repo`#`issue`, called when the flow on it has
     * just ended with its own close (CloseWake): a feature child closed at its merge but ended only
     * after its accept Smith's check, which nothing on GitHub announces. Each row goes through the
     * scan's own [resume], so a blocker still open, closed without a merge, or a withdrawn parent
     * waits as it would in a scan. Ordinary deferred work is left to the scan.
     */
    @Synchronized
    fun wakeWaitingOn(repo: String, issue: Long): List<ScanAction> {
        val targets = repos.list().map { it.fullName }.toSet()
        val rows = deferred.list().filter { row ->
            row.waitingInstance != null && row.repo in targets && row.blockers.any { it.repo == repo && it.number == issue }
        }
        return rows.mapNotNull { row ->
            runCatching { resume(row) }
                .onFailure { log.warn("wake {}#{} on {}#{}: {}", row.repo, row.issue, repo, issue, it.message) }
                .getOrNull()
        }.also { if (it.isNotEmpty()) log.info("wake on {}#{}: {}", repo, issue, it.joinToString { a -> "${a.repo}#${a.issue} ${a.outcome}" }) }
    }

    /** `firstMate` is First mate's approval ([IssueApprovals.approveTicket]): its own label event, with the caller's login as actor. */
    fun consider(repo: String, issue: Long, firstMate: IssueEvent? = null): ScanAction? {
        val events = tickets.labelEvents(repo, issue)
        val approval = firstMate ?: events.lastOrNull { it.event == "labeled" && it.label == AUTHORIZED } ?: return null
        if (handled.seen(repo, issue, approval.id)) return null
        // The engine started it itself (label event 0: an intake's carry-over, a refactor job with approval `none`) and
        // may have put `authorized` on it as the visible record. That first `authorized` approves nothing, whoever's login
        // it is under (a trusted fleet login too, #1802: its events may list `authorized` before the kind label, and the
        // flow it records is running), and a refused row for it would hide the running flow on the board: skip it, with no
        // row and no comment. A later one (removed and added again, by a person) is an approval like any other.
        if (firstMate == null && approval.id == events.firstOrNull { it.event == "labeled" && it.label == AUTHORIZED }?.id &&
            handled.seen(repo, issue, 0L)) return null
        // Waiting on this very approval: the resume pass decides when it starts.
        if (deferred.get(repo, issue)?.labelEvent == approval.id) return null

        // Snapshot first, then check for edits: an edit after this read cannot reach the snapshot,
        // and one before it is caught below.
        val ticket = tickets.snapshot(repo, issue)
        val kinds = kindsAt(approval, events)
        val flow = kinds.singleOrNull()
        // A feature also says how it runs, `feature:auto` or `feature:confirm`, and needs exactly one.
        val modes = if (flow == "feature") labelsAt(FeatureMode.LABELS, approval, events) else emptySet()
        // A split feature parked on a child closed by hand: this approval resumes it rather than starting a run.
        val parked = deferred.get(repo, issue)?.takeIf { it.waitingInstance != null && it.flow == flow }
            ?.let { row -> deferredBlockers.stalledChild(row)?.let { row to it } }
        val refusal = approvalRefusal(
            kinds, flow, modes, approval.actor, parked != null,
            { access.trusted(approval.actor) },
            { handled.startedBefore(repo, issue, flow!!).any { starter.counts(it) } },
            kindChangedAfter(approval, events),
            { editedAfter(approval.at, events, null) },
        )
        if (refusal == null && parked != null) return wake(parked.first, approval, parked.second)
        val mode = modes.singleOrNull()?.let(FeatureMode::ofLabel)
        if (refusal == null && flow in ProcessFlowStarter.WORK_FLOWS) {
            val blockers = tickets.blockers(repo, issue)
            if (blockers.any { it.stillOpen() }) {
                return defer(Deferred(repo, issue, approval.id, approval.actor, flow!!, mode, ticket, blockers))
            }
        }
        return act(repo, issue, approval, flow, flow ?: kinds.joinToString(",").ifEmpty { "none" }, mode, ticket, refusal)
            // A fresh approval with no open blocker left, after a hand-closed one: it no longer waits.
            ?.also { if (it.outcome == "started") forget(repo, issue) }
    }

    /**
     * Records `row` as waiting, and says so on the ticket the first time: a replaced approval on an
     * issue already waiting says nothing new. Nothing is claimed, so the approval stays unhandled.
     */
    override fun defer(row: Deferred): ScanAction {
        val open = row.blockers.filter { it.stillOpen() }.minBy { it.number }
        // A replaced approval forgets what was said about blockers closed without a merge: its `reauthorize` need goes with it.
        val before = deferred.get(row.repo, row.issue)
        if (deferred.defer(row)) {
            tickets.commentOn(row.repo, row.issue, "Waiting on ${open.ref(row.repo)} (open). " +
                "Starts on its own when every blocker is closed by a merged PR.\n\n— engine")
        }
        if (before != null && before.stalled.isNotEmpty() && deferred.get(row.repo, row.issue)?.stalled.isNullOrEmpty()) reauthorizeNeed(row.repo, row.issue, "removed")
        log.info("scan: {}#{} {} deferred, waiting on {}", row.repo, row.issue, row.flow, open.ref(row.repo))
        return ScanAction(row.repo, row.issue, row.flow, "deferred", "waiting on ${open.ref(row.repo)}")
    }

    /**
     * Claims `approval` and starts `flow` (or refuses it, with a comment); null when another scan
     * already claimed it. The one start path, for a fresh label and a deferred approval alike.
     */
    internal fun act(repo: String, issue: Long, approval: IssueEvent, flow: String?, recorded: String, mode: String?,
                    ticket: IssueSnapshot, refusal: String?, detail: String? = null, tier: Int? = null, dryRun: Boolean = false): ScanAction? {
        val outcome = if (refusal == null) "started" else "refused"
        if (!handled.claim(repo, issue, approval.id, recorded, outcome, refusal ?: detail, askerOf(ticket), TicketFiler.pointsOf(ticket.body))) return null

        return try {
            if (refusal == null) {
                val extra = buildMap<String, Any> {
                    if (dryRun) put(Vars.DRY_RUN, true)
                    mode?.let { put(Vars.FEATURE_MODE, it) }
                }
                val instance = starter.start(flow!!, repo, ticket, approval.actor, tier = tier, extra = extra)
                if (instance == null) {
                    // An intake already authorized by its submitter, or long finished: nothing to hand this to.
                    val nothing = "nothing was waiting for this authorization"
                    handled.outcome(repo, issue, approval.id, "ignored", nothing)
                    return ScanAction(repo, issue, flow, "ignored", nothing)
                }
                handled.started(repo, issue, approval.id, instance)
                log.info("scan: {}#{} started {} {} (authorized by @{})", repo, issue, flow, instance, approval.actor)
                ScanAction(repo, issue, flow, outcome, instance)
            } else {
                tickets.commentOn(repo, issue, "Not starting: $refusal.\n\n— engine")
                ScanAction(repo, issue, recorded, outcome, refusal)
            }
        } catch (e: Exception) {
            handled.release(repo, issue, approval.id) // try again next scan
            throw e
        }
    }

    /**
     * A fresh approval on a split feature parked on `child`, closed without a merge: claims it as
     * `resumed` and wakes the parked flow with `child closed` and no PR, so the parent records the
     * child as satisfied and goes on to the replan. Null when another scan already claimed it.
     * `through` names the way it came other than a label (First mate's [IssueApprovals.reauthorize]), whose comment failure goes to `notes`.
     */
    internal fun wake(row: Deferred, approval: IssueEvent, child: Blocker, through: String = "", notes: MutableList<String>? = null): ScanAction? {
        val instance = row.waitingInstance!!
        val detail = "re-authorized by @${approval.actor}$through: child ${child.ref(row.repo)}, closed without a merge, treated as satisfied"
        if (!handled.claim(row.repo, row.issue, approval.id, row.flow, "resumed", detail, askerOf(row.snapshot), TicketFiler.pointsOf(row.snapshot.body))) return null
        try {
            check(starter.childClosed(instance, child.number, null)) { "feature $instance no longer waits on ${child.ref(row.repo)}" }
        } catch (e: Exception) {
            handled.release(row.repo, row.issue, approval.id) // try again next scan
            throw e
        }
        handled.started(row.repo, row.issue, approval.id, instance)
        // A parallel split's other children still block it: the row narrows to them ([narrow]); serial forgets it.
        val blockers = row.blockers.takeIf { it.size > 1 }?.let { deferredBlockers.blockersOf(row) }.orEmpty()
        if (blockers.size > 1) narrow(row, blockers, child, row.stalled) else forget(row)
        val who = if (through.isEmpty()) "" else " (re-authorized by @${approval.actor}$through)"
        runCatching {
            tickets.commentOn(row.repo, row.issue, "Resumed$who: ${child.ref(row.repo)} was closed without a merge and counts as satisfied; " +
                "on to the replan.\n\n— engine")
        }.onFailure { e -> notes?.add("the comment was not posted: ${e.message}") ?: throw e }
        log.info("scan: {}#{} resumed {} (authorized by @{}{}): child {} treated as satisfied", row.repo, row.issue, instance, approval.actor, through, child.ref(row.repo))
        return ScanAction(row.repo, row.issue, row.flow, "resumed", detail)
    }

    /**
     * One waiting approval, re-read: still blocked, blocked by a close that was not a merge
     * (said once per blocker), withdrawn, or clear, when it starts from what was stored.
     */
    private fun resume(row: Deferred): ScanAction? {
        val blockers = deferredBlockers.blockersOf(row)
        if (row.waitingInstance != null && blockers.size > 1) return resumeEach(row, blockers)
        if (blockers.any { it.stillOpen() }) {
            deferred.checked(row.repo, row.issue, blockers, row.stalled)
            return null
        }
        // Merged, its flow still at its acceptance check: wait on, silently,
        // and forget a stall recorded for it before this rule (no `reauthorize` need).
        val running = blockers.filter(deferredBlockers::closedStillRunning)
        if (running.isNotEmpty()) {
            val result = stallsAfter(row.stalled, running, emptyList())
            deferred.checked(row.repo, row.issue, blockers, result.stalled)
            if (result.need == ReauthorizeNeed.REMOVED) reauthorizeNeed(row.repo, row.issue, "removed")
            return null
        }
        val unmerged = blockers.filterNot(deferredBlockers::mergedByEngine)
        if (unmerged.isNotEmpty()) {
            val result = stallsAfter(row.stalled, emptyList(), unmerged)
            for (b in result.toComment) {
                tickets.commentOn(row.repo, row.issue, "Blocker ${b.ref(row.repo)} was closed without a merge${intakeNote(row.repo, b)}; re-authorize to proceed" +
                    (if (row.waitingInstance != null) ". Re-authorizing it (First mate's `reauthorize`) resumes it and treats ${b.ref(row.repo)} as satisfied" else "") +
                    "\n\n— engine")
            }
            deferred.checked(row.repo, row.issue, blockers, result.stalled)
            // The row `GET /needs` shows as `reauthorize` from now on, said once: when the first stall is recorded.
            if (result.need == ReauthorizeNeed.ADDED) reauthorizeNeed(row.repo, row.issue, "added")
            return null
        }
        deferredBlockers.withdrawn(row)?.let { why ->
            forget(row)
            log.info("resume: {}#{} no longer waits: {}", row.repo, row.issue, why)
            return ScanAction(row.repo, row.issue, row.flow, "ignored", why)
        }
        // A split feature waiting on its child: wake the parked flow with the merge (or, no PR, the nothing-to-do close), no start.
        row.waitingInstance?.let { instance ->
            val child = blockers.maxByOrNull { it.number } ?: error("nothing blocked ${row.repo}#${row.issue}")
            val pr = child.closedByPr ?: ProcessFlowStarter.WORK_FLOWS.firstNotNullOfOrNull { flow ->
                handled.startedBefore(child.repo, child.number, flow).firstNotNullOfOrNull(starter::mergedPr)
            }
            val closed = wakeOn(instance, row, child, pr)
            forget(row)
            log.info("resume: {}#{} woken: child {} {}", row.repo, row.issue, child.ref(row.repo), closed)
            return ScanAction(row.repo, row.issue, row.flow, "resumed", "child ${child.ref(row.repo)} $closed")
        }
        val approval = IssueEvent(row.labelEvent, "labeled", row.authorizedBy, row.firstSeen ?: OffsetDateTime.now(), AUTHORIZED)
        val refusal = if (access.trusted(row.authorizedBy)) null
            else "authorized by @${row.authorizedBy}$NOT_TRUSTED"
        val action = act(row.repo, row.issue, approval, row.flow, row.flow, row.featureMode, row.snapshot, refusal,
            row.detail, row.tier, row.dryRun)
        forget(row)
        if (action?.outcome == "started") {
            val closed = blockers.joinToString(", ") { it.ref(row.repo) }.ifEmpty { "every blocker" }
            tickets.commentOn(row.repo, row.issue, "Started: $closed closed.\n\n— engine")
        }
        return action
    }

    /**
     * A parallel split's parent parked on several open children (engine/README.md, "Split features"): wakes it for the
     * lowest-numbered child the engine's own close ended, with its PR, and narrows the row to the rest, forgetting it
     * only when none is left. Children still open, or merged with their flow still at its check, do not hold the wake;
     * a child closed without a merge is said once and the parent waits on the others (or a re-authorize, [wake]).
     */
    private fun resumeEach(row: Deferred, blockers: List<Blocker>): ScanAction? {
        val instance = row.waitingInstance!!
        val closed = blockers.filter { !it.stillOpen() && !deferredBlockers.closedStillRunning(it) }
        val unmerged = closed.filterNot(deferredBlockers::mergedByEngine)
        val result = stallsAfter(row.stalled, blockers.filter(deferredBlockers::closedStillRunning), unmerged)
        for (b in result.toComment) {
            tickets.commentOn(row.repo, row.issue, "Child ${b.ref(row.repo)} was closed without a merge${intakeNote(row.repo, b)}; the parent goes on waiting on its other children. " +
                "Re-authorizing it (First mate's `reauthorize`) resumes it and treats ${b.ref(row.repo)} as satisfied\n\n— engine")
        }
        if (result.need == ReauthorizeNeed.ADDED) reauthorizeNeed(row.repo, row.issue, "added")
        if (result.need == ReauthorizeNeed.REMOVED) reauthorizeNeed(row.repo, row.issue, "removed")
        val child = (closed - unmerged.toSet()).minByOrNull { it.number }
        if (child == null) {
            deferred.checked(row.repo, row.issue, blockers, result.stalled)
            return null
        }
        deferredBlockers.withdrawn(row)?.let { why ->
            forget(row)
            log.info("resume: {}#{} no longer waits: {}", row.repo, row.issue, why)
            return ScanAction(row.repo, row.issue, row.flow, "ignored", why)
        }
        val pr = child.closedByPr ?: ProcessFlowStarter.WORK_FLOWS.firstNotNullOfOrNull { flow ->
            handled.startedBefore(child.repo, child.number, flow).firstNotNullOfOrNull(starter::mergedPr)
        }
        val what = wakeOn(instance, row, child, pr)
        narrow(row, blockers, child, result.stalled)
        log.info("resume: {}#{} woken: child {} {}", row.repo, row.issue, child.ref(row.repo), what)
        return ScanAction(row.repo, row.issue, row.flow, "resumed", "child ${child.ref(row.repo)} $what")
    }

    /**
     * Wakes the parked parent [instance] for its closed [child]: with the drafts it split into when it closed itself
     * as split (its parent was not parked on it then: engine/README.md, "A child that splits"), else with its merge's
     * PR, or none for a nothing-to-do close. Answers how the child closed, for the log.
     */
    private fun wakeOn(instance: String, row: Deferred, child: Blocker, pr: Long?): String {
        val drafts = deferredBlockers.splitDrafts(child)
        val woken = if (drafts != null) starter.childSplit(instance, child.number, drafts) else starter.childClosed(instance, child.number, pr)
        check(woken) { "feature $instance no longer waits on ${child.ref(row.repo)}" }
        return if (drafts != null) "split by its work run" else pr?.let { "merged (PR #$it)" } ?: "closed with nothing to do"
    }

        /** The row past the woken [child]: its other blockers, or gone when none is left. */
    private fun narrow(row: Deferred, blockers: List<Blocker>, child: Blocker, stalled: Set<String>) {
        val rest = blockers.filterNot { it.repo == child.repo && it.number == child.number }
        if (rest.isEmpty()) return forget(row)
        val left = stalled - "${child.repo}#${child.number}"
        deferred.checked(row.repo, row.issue, rest, left)
        if (stalled.isNotEmpty() && left.isEmpty()) reauthorizeNeed(row.repo, row.issue, "removed")
    }

    companion object {
        /** The approval label. Applied by a trusted login, it starts the kind's flow. */
        const val AUTHORIZED = "authorized"
        /** How a refusal for an untrusted labeller ends: [IssueApprovals.approveTicket] re-runs such a refused approval. */
        const val NOT_TRUSTED = ", who is not a trusted login"
    }
}

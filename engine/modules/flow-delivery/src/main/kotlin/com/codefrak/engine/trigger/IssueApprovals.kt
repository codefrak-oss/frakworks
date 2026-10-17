package com.codefrak.engine.trigger

import com.codefrak.engine.chore.FlowStarter
import com.codefrak.engine.chore.ProcessFlowStarter
import com.codefrak.engine.github.IssueEvent
import com.codefrak.engine.intake.IntakeRefs
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.DeferredIssues
import com.codefrak.engine.repos.Handled
import com.codefrak.engine.repos.HandledIssues
import com.codefrak.engine.workitems.Tickets
import com.codefrak.engine.workitems.commentOn
import com.codefrak.engine.workitems.flag
import com.codefrak.engine.workitems.isOpen
import com.codefrak.engine.workitems.labelEvents
import com.codefrak.engine.workitems.snapshot
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * The explicit approval actions a person or First mate calls, in-process rather than by a label
 * ([IssueScanner]'s own scan reads only the label): [approveIntake], [approveTicket] and
 * [reauthorize]. Reaches the scan's own start/defer/refuse path through [scan]'s internal
 * `consider`, `act`, `forget`, `wake` and `askerOf` rather than duplicating them.
 */
@Component
class IssueApprovals(
    private val access: Access,
    private val handled: HandledIssues,
    private val tickets: Tickets,
    private val starter: FlowStarter,
    private val deferred: DeferredIssues,
    private val intakes: IntakeRefs,
    private val deferredBlockers: DeferredBlockers,
    private val scan: IssueScanner,
) {
    private val log = LoggerFactory.getLogger(IssueApprovals::class.java)

    /**
     * Starts the intake waiting on ask item `repo`#`issue` (its ticket number) as `by`, whose trust the caller has
     * checked: First mate's `approve` (engine/README.md, "The frakworks server") and `POST /work-items/{id}/start` on an
     * `ask` item (engine/docs/intake.md, "Authorization"). It hands the waiting intake its authorization and records it
     * on the item as a `conversation` entry. No label is written: the ask lives only in the engine. Null when the item is
     * closed or no intake on it waits for an authorization; nothing is written then.
     */
    @Synchronized
    fun approveIntake(repo: String, issue: Long, by: String): Approved? {
        if (!tickets.isOpen(repo, issue)) return null
        val ticket = tickets.snapshot(repo, issue)
        val instance = starter.start("intake", repo, ticket, by) ?: return null
        log.info("approve: {}#{} intake {} authorized by @{}", repo, issue, instance, by)
        val notes = mutableListOf<String>()
        runCatching {
            tickets.commentOn(repo, issue, "Authorized by @$by: intake `$instance` goes on to refining.\n\n— engine")
        }.onFailure { e -> notes += "the entry was not recorded: ${e.message}" }
        return Approved(instance, notes)
    }

    /**
     * First mate's `approve` for a filed ticket (engine/README.md, "approve"): authorizes open `repo`#`issue`, which
     * carries exactly one kind label other than `intake` and no [IssueScanner.AUTHORIZED] (What needs you's `authorize` row), as
     * `by`, doing under the scan's own lock what an [IssueScanner.AUTHORIZED] label from `by` does in `consider`. It writes the
     * label with the repo's token, reads that label event back and hands it to `consider` with `by` as its actor, so
     * the start, deferral or refusal is the scan's own and the event is claimed in `engine.handled_issue` for later
     * scans. Refused, with nothing written, when there is nothing to authorize or `by` may not authorize the flow
     * there ([Access.trusted]).
     */
    @Synchronized
    fun approveTicket(repo: String, issue: Long, by: String): TicketApproved {
        if (!tickets.isOpen(repo, issue)) return TicketApproved.Refused("$repo#$issue is closed")
        val before = tickets.labelEvents(repo, issue)
        val now = IssueEvent(Long.MIN_VALUE, "now", by, OffsetDateTime.now())
        if (IssueScanner.AUTHORIZED in labelsAt(setOf(IssueScanner.AUTHORIZED), now, before)) {
            val label = before.lastOrNull { it.event == "labeled" && it.label == IssueScanner.AUTHORIZED }
            val row = label?.let { l -> handled.list(repo).firstOrNull { it.issue == issue && it.labelEvent == l.id } }
            if (label != null && row != null && refusedUntrusted(row)) return rerun(repo, issue, label, row, by)
            return TicketApproved.Refused("$repo#$issue already carries `${IssueScanner.AUTHORIZED}`: the scan acts on it")
        }
        val kinds = kindsAt(now, before)
        val flow = kinds.singleOrNull()
            ?: return TicketApproved.Refused(if (kinds.isEmpty()) "$repo#$issue has no kind label (${ProcessFlowStarter.FLOWS.joinToString { "`$it`" }})"
                else "$repo#$issue has more than one kind label (${kinds.joinToString { "`$it`" }}); keep one")
        if (flow == "intake") return TicketApproved.Refused("$repo#$issue is an intake's ask")
        if (!access.trusted(by)) return TicketApproved.Refused("@$by is not a trusted login")
        val last = before.maxOfOrNull { it.id } ?: 0L
        tickets.flag(repo, issue, IssueScanner.AUTHORIZED)
        val label = awaitLabelEvent(repo, issue, last)
            ?: return TicketApproved.Refused("the `${IssueScanner.AUTHORIZED}` label was written but its event is not listed yet: the next scan acts on it as the token's login")
        val action = scan.consider(repo, issue, label.copy(actor = by))
            ?: return TicketApproved.Refused("$repo#$issue's approval was just acted on by another call")
        log.info("approve: {}#{} {} {} (authorized by @{} through First mate)", repo, issue, flow, action.outcome, by)
        val notes = mutableListOf<String>()
        runCatching {
            tickets.commentOn(repo, issue, "Authorized by @$by$THROUGH_FIRST_MATE: the $flow ${action.outcome} (${action.detail}).\n\n— engine")
        }.onFailure { e -> notes += "the comment was not posted: ${e.message}" }
        return TicketApproved.Done(flow, action.outcome, action.detail, label.id, notes)
    }

    /**
     * [approveTicket] on a ticket whose `authorized` label the scan refused for an untrusted labeller: gives that
     * refused claim back and hands the same label event to `consider` with `by` as its actor, so the start, deferral
     * or refusal is a fresh approval's. No label is written: it is still on the issue.
     */
    private fun rerun(repo: String, issue: Long, label: IssueEvent, row: Handled, by: String): TicketApproved {
        if (!access.trusted(by)) return TicketApproved.Refused("@$by is not a trusted login")
        handled.release(repo, issue, label.id)
        val action = scan.consider(repo, issue, label.copy(actor = by))
        if (action == null) {
            // Nothing acted (another call claimed it, or it now waits): put the refused row back if none took its place.
            if (!handled.seen(repo, issue, label.id)) handled.claim(repo, issue, label.id, row.flow, row.outcome, row.detail, row.askedBy, row.points)
            return TicketApproved.Refused("$repo#$issue's approval was just acted on by another call")
        }
        log.info("approve: {}#{} {} {} (refused approval re-run, authorized by @{} through First mate)", repo, issue, action.flow, action.outcome, by)
        val notes = mutableListOf<String>()
        runCatching {
            tickets.commentOn(repo, issue, "Authorized by @$by$THROUGH_FIRST_MATE: the ${action.flow} ${action.outcome} (${action.detail}).\n\n— engine")
        }.onFailure { e -> notes += "the comment was not posted: ${e.message}" }
        return TicketApproved.Done(action.flow, action.outcome, action.detail, label.id, notes)
    }

    /** What [approveTicket] did: the flow, the scan's outcome for it (`started`, `deferred`, `refused`, …) and its detail, or why it did nothing. */
    sealed interface TicketApproved {
        data class Done(val flow: String, val outcome: String, val detail: String, val labelEvent: Long, val notes: List<String>) : TicketApproved
        data class Refused(val why: String) : TicketApproved
    }

    /** What [approveIntake] did: the intake it authorized, and what failed after the authorization. */
    data class Approved(val instance: String, val notes: List<String>)

    /**
     * First mate's `reauthorize` (engine/README.md, "The frakworks server"): re-authorizes `repo`#`issue`, a
     * feature, chore or bug waiting in `engine.deferred_issue` with every blocker closed and one closed without a
     * merge (the `reauthorize` need), as `by`, doing under the scan's own lock what a fresh `authorized` label by
     * `by` does in `consider`. A split feature parked on its child is woken with the child satisfied ([IssueScanner.wake])
     * under a negative label event, no GitHub id: its own label event is already claimed by its start, and no label is
     * written, so no new event is left for a scan to read. A deferred start goes through [IssueScanner.act] on the stored
     * label event with `by` as its actor, so a later scan finds that event handled. Refused, with nothing written, when
     * there is nothing to re-authorize or `by` may not authorize the flow there ([Access.trusted]).
     */
    @Synchronized
    fun reauthorize(repo: String, issue: Long, by: String): Reauthorized {
        if (!tickets.isOpen(repo, issue)) return Reauthorized.Refused("$repo#$issue is closed")
        val row = deferred.get(repo, issue)
        if (row == null) {
            val labelled = tickets.labelEvents(repo, issue).lastOrNull { it.label == "intake" && it.event in setOf("labeled", "unlabeled") }
            val intake = labelled?.event == "labeled" || runCatching { intakes.resolve(repo, "#$issue") }.getOrNull() != null
            return Reauthorized.Refused(if (intake) "$repo#$issue is an intake's ask, not a feature, chore or bug; approve authorizes an intake"
                else "$repo#$issue is not waiting on a blocker (no deferred approval on it): nothing to re-authorize")
        }
        if (row.flow !in ProcessFlowStarter.WORK_FLOWS) return Reauthorized.Refused("$repo#$issue waits as a ${row.flow}, not a feature, chore or bug")
        val blockers = deferredBlockers.blockersOf(row)
        if (blockers.isEmpty()) return Reauthorized.Refused("$repo#$issue has no blocker listed any more: the next scan acts on it, no re-authorization needed")
        // A parallel split's parent, parked on several children, is re-authorized per child: its siblings may still be open.
        val perChild = row.waitingInstance != null && blockers.size > 1
        if (!perChild) {
            blockers.firstOrNull { it.stillOpen() }?.let { return Reauthorized.Refused("$repo#$issue still waits on ${it.ref(repo)}, which is open") }
            blockers.firstOrNull(deferredBlockers::closedStillRunning)?.let {
                return Reauthorized.Refused("${it.ref(repo)} merged and its flow is still at its acceptance check: $repo#$issue goes on by itself once it ends")
            }
        }
        val unmerged = if (perChild) listOfNotNull(deferredBlockers.stalledChild(row)) else blockers.filterNot(deferredBlockers::mergedByEngine)
        if (perChild && unmerged.isEmpty()) return Reauthorized.Refused("no child of $repo#$issue was closed without a merge: it goes on by itself as each closes")
        if (unmerged.isEmpty()) return Reauthorized.Refused("every blocker of $repo#$issue closed by a merge: the scan goes on with it by itself, no re-authorization needed")
        if (!access.trusted(by)) return Reauthorized.Refused("@$by is not a trusted login")
        // A fresh label the scan has not read yet is the scan's to act on.
        val events = tickets.labelEvents(repo, issue)
        val latest = events.lastOrNull { it.event == "labeled" && it.label == IssueScanner.AUTHORIZED }
        if (latest != null && latest.id != row.labelEvent && !handled.seen(repo, issue, latest.id))
            return Reauthorized.Refused("$repo#$issue has a fresh `${IssueScanner.AUTHORIZED}` label the scan has not acted on yet: the next scan does")
        val notes = mutableListOf<String>()
        val closed = unmerged.joinToString(", ") { it.ref(repo) }
        if (row.waitingInstance != null) {
            val child = unmerged.maxBy { it.number }
            val approval = IssueEvent(-System.currentTimeMillis(), "labeled", by, OffsetDateTime.now(), IssueScanner.AUTHORIZED)
            scan.wake(row, approval, child, THROUGH_FIRST_MATE, notes) ?: return Reauthorized.Refused("$repo#$issue was just resumed by another call")
            return Reauthorized.Done("resumed", row.waitingInstance!!, notes)
        }
        deferredBlockers.withdrawn(row)?.let { why -> return Reauthorized.Refused("$repo#$issue's stored approval no longer stands ($why); label it `${IssueScanner.AUTHORIZED}` again") }
        val approval = IssueEvent(row.labelEvent, "labeled", by, row.firstSeen ?: OffsetDateTime.now(), IssueScanner.AUTHORIZED)
        val action = scan.act(repo, issue, approval, row.flow, row.flow, row.featureMode, row.snapshot, null,
            "re-authorized by @$by$THROUGH_FIRST_MATE: $closed closed without a merge", row.tier, row.dryRun)
            ?: return Reauthorized.Refused("$repo#$issue's approval was just acted on by another call")
        scan.forget(row)
        if (action.outcome != "started") return Reauthorized.Refused("nothing was waiting for its start: ${action.detail}")
        runCatching {
            tickets.commentOn(repo, issue, "Started: re-authorized by @$by$THROUGH_FIRST_MATE; $closed was closed without a merge.\n\n— engine")
        }.onFailure { e -> notes += "the comment was not posted: ${e.message}" }
        log.info("reauthorize: {}#{} started {} {} (re-authorized by @{} through First mate)", repo, issue, row.flow, action.detail, by)
        return Reauthorized.Done("started", action.detail, notes)
    }

    /** What [reauthorize] did: `resumed` the parked flow or `started` a new one (its instance), or why it did nothing. */
    sealed interface Reauthorized {
        data class Done(val outcome: String, val instance: String, val notes: List<String>) : Reauthorized
        data class Refused(val why: String) : Reauthorized
    }

    /**
     * Polls for the just-written `AUTHORIZED` `labeled` event on `repo`#`issue` past `after`, up to
     * [LABEL_READS] times, [LABEL_READ_PAUSE_MS] apart.
     */
    private fun awaitLabelEvent(repo: String, issue: Long, after: Long): IssueEvent? =
        // GitHub lists a new event a moment after the write.
        (1..LABEL_READS).firstNotNullOfOrNull { n ->
            if (n > 1) Thread.sleep(LABEL_READ_PAUSE_MS)
            tickets.labelEvents(repo, issue).lastOrNull { it.event == "labeled" && it.label == IssueScanner.AUTHORIZED && it.id > after }
        }

    private fun refusedUntrusted(row: Handled) = row.outcome == "refused" && row.detail?.endsWith(IssueScanner.NOT_TRUSTED) == true

    companion object {
        /** How a record of First mate's [reauthorize] names the way it came. */
        private const val THROUGH_FIRST_MATE = " through First mate"
        /** How often [approveTicket] reads the issue's events for its own label, and the pause between reads. */
        private const val LABEL_READS = 3
        private const val LABEL_READ_PAUSE_MS = 1000L
    }
}

package com.codefrak.engine.trigger

import com.codefrak.engine.chore.FlowStarter
import com.codefrak.engine.chore.ProcessFlowStarter
import com.codefrak.engine.github.Blocker
import com.codefrak.engine.github.IssueEvent
import com.codefrak.engine.repos.Deferred
import com.codefrak.engine.repos.HandledIssues
import com.codefrak.engine.workitems.Tickets
import com.codefrak.engine.workitems.blockers
import com.codefrak.engine.workitems.isOpen
import com.codefrak.engine.workitems.labelEvents
import org.springframework.stereotype.Component

/**
 * Reads a deferred row's blockers and decides whether its approval still stands
 * (engine/README.md, "Starting a flow from an issue"): used by [IssueScanner]'s
 * `consider` and `resume`, and [IssueApprovals]'s `reauthorize`.
 */
@Component
class DeferredBlockers(
    private val tickets: Tickets,
    private val handled: HandledIssues,
    private val starter: FlowStarter,
) {
    /**
     * The blockers that decide `row`, read now. A split feature waiting on its child counts only that
     * child (a parallel split, each open child), the one FileChildDelegate recorded (and [DeferredIssues.checked] keeps, narrowed the same
     * way): the parent stays blocked by every child filed before, and one closed by hand and resumed
     * past must not stall it again.
     */
    fun blockersOf(row: Deferred): List<Blocker> {
        val all = tickets.blockers(row.repo, row.issue)
        if (row.waitingInstance == null) return all
        val current = row.blockers.map { it.repo to it.number }.toSet()
        return all.filter { (it.repo to it.number) in current }
    }

    /**
     * The child a split feature's `row` is parked on that closed without a merge, once none is open;
     * null when a child is still open or every close was the engine's own (the resume pass wakes that).
     * A parallel split's row, parked on several children, is resumed per child: its lowest such child,
     * whatever its siblings' state.
     */
    fun stalledChild(row: Deferred): Blocker? {
        val blockers = blockersOf(row)
        if (blockers.size > 1) return blockers.filter { !it.stillOpen() && !closedStillRunning(it) && !mergedByEngine(it) }.minByOrNull { it.number }
        if (blockers.isEmpty() || blockers.any { it.stillOpen() || closedStillRunning(it) }) return null
        return blockers.filterNot(::mergedByEngine).maxByOrNull { it.number }
    }

    /** Closed by the engine's own flow on it, which ended in its "Done. Merged" close or its nothing-to-do close. */
    fun mergedByEngine(b: Blocker): Boolean = !b.stillOpen() &&
        ProcessFlowStarter.WORK_FLOWS.any { flow -> handled.startedBefore(b.repo, b.number, flow).any(starter::mergedClose) }

    /**
     * The drafts a child closed itself with when its work split it and its parent was not parked on it then
     * (FlowStarter.splitDrafts; engine/README.md, "A child that splits"), or null for any other close.
     */
    fun splitDrafts(b: Blocker): ByteArray? = if (b.stillOpen()) null else
        ProcessFlowStarter.WORK_FLOWS.firstNotNullOfOrNull { flow -> handled.startedBefore(b.repo, b.number, flow).firstNotNullOfOrNull(starter::splitDrafts) }

    /**
     * Merged, its flow not ended yet (a feature at its accept Smith's check): closed by that flow,
     * or by GitHub at the merge of a PR carrying `Closes #n` while the flow on it still runs.
     */
    fun closedStillRunning(b: Blocker): Boolean = !b.stillOpen() &&
        ProcessFlowStarter.WORK_FLOWS.any { flow ->
            handled.startedBefore(b.repo, b.number, flow).any { starter.closedStillRunning(it) || (b.closedByPrMerged == true && starter.running(it)) }
        }

    /**
     * Why a waiting approval no longer stands, or null. As before deferral existed, closing the
     * issue or taking `authorized` off withdraws an approval that has not started yet; so does a
     * change of kind after it. An intake carry-over (label event 0) has no label to check, nor
     * does a split feature waiting on its child.
     */
    fun withdrawn(row: Deferred): String? {
        if (!tickets.isOpen(row.repo, row.issue)) return "closed while it waited"
        if (row.labelEvent == 0L) return null
        val events = tickets.labelEvents(row.repo, row.issue)
        val latest = events.lastOrNull { it.event == "labeled" && it.label == IssueScanner.AUTHORIZED }
        if (latest?.id != row.labelEvent) return "approved again since, or the approval is gone"
        if (events.dropWhile { it.id != row.labelEvent }.any { it.event == "unlabeled" && it.label == IssueScanner.AUTHORIZED })
            return "`${IssueScanner.AUTHORIZED}` was removed while it waited"
        if (kindChangedAfter(latest, events)) return "its kind label changed while it waited"
        return null
    }
}

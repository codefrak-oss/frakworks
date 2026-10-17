package com.codefrak.engine.chore

import com.codefrak.engine.feature.AcceptResult
import com.codefrak.engine.feature.ChildDraft
import com.codefrak.engine.feature.mergedText
import com.codefrak.engine.proposal.DecisionVerb
import com.codefrak.engine.repos.DeferredIssues
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.VerifyResult
import com.codefrak.engine.smith.issue
import com.codefrak.engine.smith.repo
import com.codefrak.engine.workitems.TicketState
import com.codefrak.engine.workitems.Tickets
import com.codefrak.engine.workitems.commentOn
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.springframework.stereotype.Component

/** The wake the close delegates need once their ticket closes: who provides it is `trigger.CloseWake`. */
fun interface IssueCloseWake {
    fun afterCommit(repo: String, issue: Long)
}

/**
 * `task-close-nothing-to-do` in bpmn/chore.bpmn, bug.bpmn and feature.bpmn: the work Smith found
 * the ticket needs no change. Comments its reason and closes the ticket as completed, with no PR,
 * review, merge or acceptance. Bound by `${closeNothingToDo}`.
 */
@Component("closeNothingToDo")
class CloseNothingToDoDelegate(private val tickets: Tickets, private val wake: IssueCloseWake) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val repo = execution.repo(); val issue = execution.issue()
        val reason = execution.work().reason?.trim() ?: error("no reason in the work result")
        tickets.commentOn(repo, issue, "Closed: nothing to do. $reason\n\n— engine ${execution.flow()} `${execution.processInstanceId}`")
        tickets.end(repo, issue, TicketState.DONE, "nothing to do")
        wake.afterCommit(repo, issue)
    }
}

/**
 * `task-close-split` in bpmn/chore.bpmn, bug.bpmn and feature.bpmn (engine/README.md, "A child that splits"): the
 * work Smith on a child of a split feature found it should split. Lists the drafts on the child and closes it as
 * `not_planned`, handing the drafts to the parent's flow in one of two ways, by the parent's state:
 * - parked on this child at `child closed` (its `engine.deferred_issue` row's `waiting_instance` lists it: serial,
 *   parallel, or live after a fallback): deletes the row and correlates `child closed` with the drafts, all in this
 *   job's transaction, so the scan never sees the row again and the parent is woken once, by this;
 * - running, not parked on it (a live parent's Smith watching the children, or a parent between its replan and its
 *   next park): the close alone. The drafts stay in this flow's `work` result, and whatever records the close reads
 *   them from there (FlowStarter.splitDrafts): the scan's wake once the parent parks on it again, or the live
 *   parent's `reconcile the children` after its run.
 * No feature flow running on the parent (cancelled or ended) is an error (an incident) naming what a human can do,
 * and nothing is closed. Bound by `${closeSplit}`.
 */
@Component("closeSplit")
class CloseSplitDelegate(private val tickets: Tickets, private val deferred: DeferredIssues, private val starter: FlowStarter) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val repo = execution.repo(); val issue = execution.issue(); val id = execution.processInstanceId
        val work = execution.work()
        val ticket = Brief.ticket(execution.getVariable(Vars.TICKET) as? ByteArray ?: error("no ticket"))
        val parent = Brief.parent(ticket.body) ?: error("#$issue names no parent (no part line); a split is only a child's")
        val drafts = ChildDraft.bytes(work.children.map(ChildDraft::of))
        val row = deferred.get(repo, parent)?.takeIf { r -> r.waitingInstance != null && r.blockers.any { it.number == issue && it.repo.equals(repo, ignoreCase = true) } }
        // Parked on this child: woken now, with the drafts. The row goes only once the wake took.
        val handed = row != null && starter.childSplit(row.waitingInstance!!, issue, drafts)
        if (handed) deferred.remove(repo, parent)
        else parentFlow(execution, repo, parent, id) ?: error("parent #$parent has no feature flow running (it was cancelled or has ended)" +
            (row?.let { ", though its deferred row still names `${it.waitingInstance}`" }.orEmpty()) + "; nothing to hand the split to. " +
            "A human files the drafts (this flow's work result) under #$parent by hand, or as new tickets, then cancels this flow")
        val parts = work.children.mapIndexed { i, c ->
            "${i + 1}. ${c.title} (${c.kind}${c.points?.let { p -> ", $p points" }.orEmpty()}" +
                (c.dependsOn.takeIf { it.isNotEmpty() }?.let { d -> ", after ${d.joinToString { "${it + 1}" }}" }.orEmpty()) + ")"
        }.joinToString("\n")
        tickets.commentOn(repo, issue, "Split into ${work.children.size} parts by the work run; handed back to #$parent, which files them in this ticket's place:\n\n" +
            "$parts\n\n${work.summary?.trim().orEmpty()}\n\n— engine ${execution.flow()} `$id`")
        tickets.end(repo, issue, TicketState.CANCELLED, "split")
    }

    /** The feature flow running on [parent] (a root instance other than this child's own [self]), or null when none runs. */
    private fun parentFlow(execution: DelegateExecution, repo: String, parent: Long, self: String): String? =
        execution.processEngineServices.runtimeService.createProcessInstanceQuery().rootProcessInstances()
            .variableValueEquals(Vars.REPO, repo).variableValueEquals(Vars.ISSUE, parent).list().firstOrNull { it.id != self }?.id
}

/**
 * `task-close-ticket`: says which PR merged, then closes the ticket. Bound by `${closeTicket}`. In
 * bpmn/post-deploy-check.bpmn it closes after the post-deployment check too, and the comment says how the check ended.
 */
@Component("closeTicket")
class CloseTicketDelegate(private val tickets: Tickets, private val wake: IssueCloseWake) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val repo = execution.repo(); val issue = execution.issue()
        // A split parent's text lists its children; it had no rounds of its own.
        val merged = execution.mergedText().let { if (execution.getVariable(Vars.SPLIT) != null) "$it." else "$it after ${execution.getVariable(Vars.ROUND)} round(s)." }
        tickets.commentOn(repo, issue, "Done. $merged${verified(execution)}${accepted(execution)}${decided(execution)}${checked(execution)}\n\n— engine ${execution.flow()} `${execution.processInstanceId}`")
        tickets.end(repo, issue, TicketState.DONE, "merged")
        // A split parent waiting on this issue wakes once the flow's end commits (engine/README.md, "Split features").
        wake.afterCommit(repo, issue)
    }

    /**
     * How the post-deployment check ended (bpmn/post-deploy-check.bpmn): passed, failed with the bug it filed, or the
     * user's `drop` or `bug` at its deadline; nothing for a flow with no check.
     */
    private fun checked(execution: DelegateExecution): String {
        val outcome = execution.getVariable(Vars.CHECK_OUTCOME) as? String
        val deadline = execution.getVariable(Vars.CHECK_DEADLINE_ANSWER) as? String
        if (outcome == null && deadline == null) return ""
        val summary = (execution.getVariable(Vars.CHECK_SUMMARY) as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { " — $it" }.orEmpty()
        val bug = (execution.getVariable(Vars.CHECK_BUG_TICKET) as? Number)?.let { "; bug filed as #$it" }.orEmpty()
        return when {
            deadline == "drop" -> "\n\nPost-deployment check dropped at its deadline by the user; its last run: ${outcome ?: "none"}$summary"
            deadline == "bug" -> "\n\nPost-deployment check did not pass by its deadline$bug; its last run: ${outcome ?: "none"}$summary"
            outcome == "passed" -> "\n\nPost-deployment check passed$summary"
            outcome == "failed" -> "\n\nPost-deployment check failed$bug$summary"
            else -> ""
        }
    }

    /** A proposal's decision, and the build it filed; nothing for any other flow. */
    private fun decided(execution: DelegateExecution): String {
        val verb = execution.getVariable(Vars.DECISION) as? String ?: return ""
        val why = (execution.getVariable(Vars.DECISION_WHY) as? String)?.let { " — $it" }.orEmpty()
        val built = (execution.getVariable(Vars.BUILT_TICKET) as? Number)?.let { "; build filed as #$it" }.orEmpty()
        return "\n\nDecided: ${DecisionVerb.status(verb)}$why$built"
    }

    /** A feature's acceptance, when the flow had one: the accept Smith's check, or the user's answer before #583. */
    private fun accepted(execution: DelegateExecution): String =
        (execution.getVariable(Vars.ACCEPTANCE) as? ByteArray)?.let { AcceptResult.parse(it) }?.let {
            val checklist = it.checklist().takeIf { c -> c.isNotEmpty() }?.let { c -> "\n\n$c" }.orEmpty()
            when (it.outcome) {
                "unverified" -> "\n\n**Done, but not every criterion could be checked**: ${it.summary!!.trim()}$checklist"
                "accepted" -> "\n\nAccepted: ${it.summary!!.trim()}$checklist"
                else -> ""
            }
        }.orEmpty()

    /** A bug's verify summary, when the flow had one, or a flaky-test flow's optimistic line; nothing for a chore. */
    private fun verified(execution: DelegateExecution): String =
        if (execution.getVariable(Vars.FLAKY_TEST) == true)
            "\n\n$OPTIMISTIC $SUSPECTED_CAUSE ${suspectedCause(execution.work().summary) ?: "not named"}"
        else (execution.getVariable(Vars.VERIFICATION) as? ByteArray)?.let { VerifyResult.parse(it) }?.let {
            if (it.outcome == "unverified") "\n\n**Fixed, but not verified**: ${it.summary!!.trim()}"
            else "\n\nVerified on the default branch: ${it.summary!!.trim()}"
        }.orEmpty()

    companion object {
        const val OPTIMISTIC = "Optimistic flaky-test fix, not verified."
        /** What a flaky-test work run's summary names its cause after, and what `FlakyTests` reads back from the `Done.` comment. */
        const val SUSPECTED_CAUSE = "Suspected cause:"

        /** The text after the first `Suspected cause:` in [text], up to its line's end; null when there is none. */
        fun suspectedCause(text: String?): String? =
            text?.lineSequence()?.filter { SUSPECTED_CAUSE in it }?.map { it.substringAfter(SUSPECTED_CAUSE).trim() }?.firstOrNull { it.isNotEmpty() }
    }
}

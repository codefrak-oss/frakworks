package com.codefrak.engine.chore

import com.codefrak.engine.feature.ChildDraft
import com.codefrak.engine.github.IssueSnapshot
import com.codefrak.engine.intake.RefinePickOutcome
import com.codefrak.engine.intake.TicketFiler
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.SteerEntry
import com.codefrak.engine.smith.Ticket
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.WorkResult
import com.codefrak.engine.tier.TierTable
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.variable.Variables
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

/** Acts on an authorized issue by its kind; tests of the scan use a fake. */
interface FlowStarter {
    /**
     * Starts `flow` on the snapshot, or for an `intake` hands the authorization
     * to the intake waiting on that issue. Returns the process instance id, or
     * null when nothing was waiting for it.
     *
     * `tier` is the ticket's own tier (1-10), already resolved by the caller
     * (a refiner-filed ticket's `DraftTicket.tier`) — when set it is used
     * instead of parsing `ticket.body`'s `Tier:` line. `model`/`effort` are a
     * manual pin (`ChoreController`) that bypasses tier resolution entirely.
     */
    fun start(flow: String, repo: String, ticket: IssueSnapshot, authorizedBy: String,
              model: String? = null, effort: String? = null, tier: Int? = null, extra: Map<String, Any> = emptyMap()): String?

    /**
     * Whether an earlier run still stands in the way of a new one on the same issue: it is
     * still active (a parked incident included), or it got as far as opening a PR. A run
     * that ended or was cancelled without a PR changed nothing on GitHub, so a new approval
     * may run it again. Null (a start still being claimed) always counts.
     */
    fun counts(instance: String?): Boolean

    /**
     * Whether the flow ended with one of its own closes (shared by chore, bug and feature): the
     * "Done. Merged" close (`task-close-ticket`) after its PR merged, or the nothing-to-do close
     * (`task-close-nothing-to-do`) when the work found the ticket needed no change. A run cancelled
     * after opening a PR reached neither. What "Blocked by" and a split's wake count as satisfied.
     */
    fun mergedClose(instance: String?): Boolean

    /**
     * Whether the flow already merged its PR (`task-merge-pr`) or made one of [mergedClose]'s closes
     * but has not ended: a feature merged and at its accept Smith's check (or, started before #583, a
     * confirm-mode feature parked at the user's acceptance), its issue closed by the engine or by GitHub
     * at the merge (the PR's `Closes #n`). Not a close without a
     * merge; what waits on it waits on, until the flow ends and [mergedClose] holds.
     */
    fun closedStillRunning(instance: String?): Boolean = false

    /** Whether the flow has not ended yet (a parked incident included). */
    fun running(instance: String?): Boolean = false

    /** The PR a flow merged (its `pr` variable), or null when it opened none. */
    fun mergedPr(instance: String?): Long? = null

    /**
     * Wakes a split feature parked at `child closed` (bpmn/feature.bpmn) with the child that
     * closed and, for a merge, its PR; false when no such instance waits there.
     */
    fun childClosed(instance: String, child: Long, pr: Long?): Boolean = false

    /**
     * Wakes a split feature parked at `child closed` with a child its work run split: [drafts] (ChildDraft JSON
     * bytes) take its place in the parent's plan (engine/README.md, "A child that splits"). False when none waits.
     */
    fun childSplit(instance: String, child: Long, drafts: ByteArray): Boolean = false

    /**
     * The drafts (ChildDraft JSON bytes) a child's flow [instance] closed itself with at `task-close-split` when its
     * parent was not parked on it (engine/README.md, "A child that splits"): its `work` result's children. Null when
     * the flow made no split close, so the wake that finds the child closed records it as a merge or a satisfied close.
     */
    fun splitDrafts(instance: String?): ByteArray? = null

    /**
     * Appends a steer from a split parent's replan ([by], `#<parent>`) to the `steer` variable of root instance
     * [instance], so every later brief of that child carries it (engine/README.md, "Steer"); its current run goes on
     * untouched. False when the instance no longer runs.
     */
    fun steer(instance: String, by: String, message: String): Boolean
}

@Component
class ProcessFlowStarter(
    private val runtime: RuntimeService, private val history: HistoryService, private val tiers: TierTable, private val jevCalls: JevCalls? = null,
) : FlowStarter {
    private val log = LoggerFactory.getLogger(ProcessFlowStarter::class.java)

    override fun start(flow: String, repo: String, ticket: IssueSnapshot, authorizedBy: String,
                       model: String?, effort: String?, tier: Int?, extra: Map<String, Any>): String? {
        val snapshot = Variables.byteArrayValue(Brief.ticketBytes(Ticket(ticket.number, ticket.title, ticket.body, ticket.author)))
        return when (flow) {
            // bpmn/chore.bpmn, bug.bpmn, feature.bpmn, proposal.bpmn, research.bpmn and refactor-scan.bpmn start the same way, on
            // "<kind> filed"; a refactor-scan only from its refactoring job (AuthorizedStart), never from a label.
            "chore", "bug", "feature", "proposal", "research", "refactor-scan" -> {
                val vars = buildMap<String, Any> {
                    put(Vars.REPO, repo); put(Vars.ISSUE, ticket.number); put(Vars.TICKET, snapshot)
                    put("authorizedBy", authorizedBy)
                    put(Vars.ASKED_BY, TicketFiler.askedByOf(ticket.body) ?: ticket.author)
                    if (model != null && effort != null) {
                        // A manual pin (ChoreController): tier resolution is skipped entirely.
                        put(Vars.MODEL, model); put(Vars.EFFORT, effort)
                    } else {
                        putAll(resolveTier(tier ?: parseTierFromBody(ticket.body), flow, repo, ticket.number))
                    }
                    putAll(extra)
                }
                runtime.createMessageCorrelation("$flow filed").setVariables(vars).correlateStartMessage().id.also {
                    // A ticket the intake filed at Jev's pick: what it started as is the pick's outcome (a relabel shows here).
                    jevCalls?.let { calls -> runCatching { RefinePickOutcome.started(calls, flow, ticket.body, vars[Vars.TIER] as? Int, extra) }
                        .onFailure { e -> log.warn("$flow $repo#${ticket.number}: refine-pick outcome not recorded: ${e.message}") } }
                }
            }
            // bpmn/intake.bpmn waits at "intake authorized" for an intake whose submitter could not authorize it.
            "intake" -> runtime.createMessageCorrelation("intake authorized")
                .processInstanceVariableEquals(Vars.REPO, repo).processInstanceVariableEquals(Vars.ISSUE, ticket.number)
                .setVariable("authorizedBy", authorizedBy).setVariable(Vars.TICKET, snapshot)
                .correlateAllWithResult().firstOrNull()?.execution?.processInstanceId
            else -> error("no flow '$flow'; there is ${FLOWS.joinToString()}")
        }
    }

    override fun counts(instance: String?): Boolean {
        if (instance == null) return true
        if (runtime.createProcessInstanceQuery().processInstanceId(instance).count() > 0) return true
        return history.createHistoricVariableInstanceQuery().processInstanceId(instance).variableName(Vars.PR).count() > 0
    }

    override fun mergedClose(instance: String?): Boolean {
        if (instance == null) return false
        val ended = history.createHistoricProcessInstanceQuery().processInstanceId(instance).finished().count() > 0
        return ended && CLOSES.any { history.createHistoricActivityInstanceQuery().processInstanceId(instance)
            .activityId(it).finished().count() > 0 }
    }

    override fun closedStillRunning(instance: String?): Boolean {
        if (instance == null) return false
        if (!running(instance)) return false
        return (CLOSES + MERGE_PR).any { history.createHistoricActivityInstanceQuery().processInstanceId(instance)
            .activityId(it).finished().count() > 0 }
    }

    override fun running(instance: String?): Boolean =
        instance != null && runtime.createProcessInstanceQuery().processInstanceId(instance).count() > 0

    override fun mergedPr(instance: String?): Long? {
        if (instance == null) return null
        return (history.createHistoricVariableInstanceQuery().processInstanceId(instance).variableName(Vars.PR).singleResult()?.value as? Number)?.toLong()
    }

    override fun childClosed(instance: String, child: Long, pr: Long?): Boolean {
        val correlated = runtime.createMessageCorrelation(CHILD_CLOSED).processInstanceId(instance)
            .setVariable(Vars.CLOSED_CHILD, child).setVariable(Vars.CLOSED_CHILD_PR, pr)
            // A split before it must not be applied again.
            .setVariable(Vars.CLOSED_CHILD_SPLIT, null)
            .correlateAllWithResult()
        if (correlated.isEmpty()) log.warn("feature {}: nothing waits at '{}' for #{}", instance, CHILD_CLOSED, child)
        return correlated.isNotEmpty()
    }

    override fun childSplit(instance: String, child: Long, drafts: ByteArray): Boolean {
        val correlated = runtime.createMessageCorrelation(CHILD_CLOSED).processInstanceId(instance)
            .setVariable(Vars.CLOSED_CHILD, child).setVariable(Vars.CLOSED_CHILD_PR, null)
            .setVariable(Vars.CLOSED_CHILD_SPLIT, Variables.byteArrayValue(drafts))
            .correlateAllWithResult()
        if (correlated.isEmpty()) log.warn("feature {}: nothing waits at '{}' for #{}'s split", instance, CHILD_CLOSED, child)
        return correlated.isNotEmpty()
    }

    override fun splitDrafts(instance: String?): ByteArray? {
        if (instance == null) return null
        if (history.createHistoricActivityInstanceQuery().processInstanceId(instance).activityId(CLOSE_SPLIT).finished().count() == 0L) return null
        val work = history.createHistoricVariableInstanceQuery().processInstanceId(instance).variableName(Vars.WORK).list()
            .firstNotNullOfOrNull { it.value as? ByteArray } ?: return null
        return WorkResult.parse(work).children.takeIf { it.isNotEmpty() }?.let { c -> ChildDraft.bytes(c.map(ChildDraft::of)) }
    }

    override fun steer(instance: String, by: String, message: String): Boolean {
        if (!running(instance)) return false
        val entries = Brief.steer(runtime.getVariable(instance, Vars.STEER) as? ByteArray) + SteerEntry(by, Instant.now().toString(), message)
        runtime.setVariable(instance, Vars.STEER, Variables.byteArrayValue(Brief.steerBytes(entries)))
        return true
    }

    /**
     * `Vars.MODEL`/`Vars.EFFORT`/`Vars.TIER` for the flow's start variables: `tier`'s
     * row of the tier map when it resolved to one, else the sandbox default with one
     * warning naming the ticket (a flow this permissive must not fail over a missing tier).
     */
    private fun resolveTier(tier: Int?, flow: String, repo: String, issue: Long): Map<String, Any> {
        if (tier == null) {
            log.warn("$flow $repo#$issue: no tier found, falling back to " +
                "${TierTable.SANDBOX_DEFAULT.model}/${TierTable.SANDBOX_DEFAULT.effort}")
            return mapOf(Vars.MODEL to TierTable.SANDBOX_DEFAULT.model, Vars.EFFORT to TierTable.SANDBOX_DEFAULT.effort)
        }
        val resolved = tiers.resolve(tier)
        return mapOf(Vars.MODEL to resolved.model, Vars.EFFORT to resolved.effort, Vars.TIER to tier)
    }

    companion object {
        /** The kinds an authorized issue can act on, each by a label of its name. */
        val FLOWS = setOf("chore", "bug", "feature", "proposal", "research", "intake")

        /** The kinds whose flow does work on the repo: a new run only when no earlier one still [counts]. */
        val WORK_FLOWS = setOf("chore", "bug", "feature", "proposal")

        /** The step that closes a work flow's ticket once its PR merged (bpmn/chore.bpmn, bug.bpmn, feature.bpmn). */
        const val CLOSE_TICKET = "task-close-ticket"

        /** The step that closes a work flow's ticket when the work Smith found nothing to do, with no PR. */
        const val CLOSE_NOTHING_TO_DO = "task-close-nothing-to-do"

        /** The step that squash-merges a work flow's PR; a feature's acceptance check comes after it. */
        const val MERGE_PR = "task-merge-pr"

        /**
         * The step that closes a split feature's child when its work split it, handing the drafts to the parent
         * (engine/README.md, "A child that splits"); the parent was woken by it, with the drafts, not by the scan.
         */
        const val CLOSE_SPLIT = "task-close-split"

        /**
         * The closes [mergedClose] counts as satisfied. A split close counts too: the drafts that took its place are
         * what the parent waits on next, and a later wake must not take the split child for a close without a merge.
         */
        val CLOSES = listOf(CLOSE_TICKET, CLOSE_NOTHING_TO_DO, CLOSE_SPLIT)

        /** The message a split feature waits on after filing a child (bpmn/feature.bpmn, `event-child-closed`). */
        const val CHILD_CLOSED = "child closed"

        /** The kinds an intake's authorization carries to ("Authorization from an intake"). */
        val FROM_INTAKE = setOf("chore", "bug", "feature", "proposal", "research")

        /**
         * The kinds an intake the engine's doctor authorized carries to (engine/README.md, "Doctor"), with no grant:
         * a `bug` or a `feature` (the feature taking the refiner's mode). Any other kind its refiner files waits for an
         * `authorized` label.
         */
        val DOCTOR_FROM_INTAKE = setOf("bug", "feature")

        /**
         * `Tier: <n>` or `- Tier: <n>` at a line start (the convention on a `submit_ask`), or the
         * `<!-- engine: intake … · tier <n> · … -->` trailer `FileTicketDelegate` leaves on every
         * ticket it files, so a refiner-filed ticket re-authorized by label later still resolves; or
         * the `<!-- engine: proposal #<n> · tier <n> · … -->` one on a proposal's build ticket.
         */
        private val TIER_LINE = Regex("""(?m)^-?\s*Tier:\s*(\d+)""")
        private val TIER_TRAILER = Regex("""<!-- engine: (?:intake|proposal #\d+) [^>]*?·\s*tier\s+(\d+)""")

        /** The ticket body's own tier, kept only when it is 1-10. */
        fun parseTierFromBody(body: String): Int? =
            (TIER_LINE.find(body) ?: TIER_TRAILER.find(body))?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..10 }

        /** Whether text carries a `Tier:` line (the convention on a `submit_ask`). */
        fun hasTierLine(text: String): Boolean = TIER_LINE.containsMatchIn(text)
    }
}

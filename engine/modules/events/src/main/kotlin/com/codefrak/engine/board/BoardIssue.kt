package com.codefrak.engine.board

import com.codefrak.engine.camunda.IncidentView
import com.codefrak.engine.intake.IntakeQuestion
import com.codefrak.engine.intake.IntakeView
import java.time.OffsetDateTime

/**
 * What the engine did with an issue's latest approval, and where that flow is now.
 * `outcome` is `handled_issue`'s (started, refused, ignored), or `deferred` for
 * an approval waiting on an open blocker (`engine.deferred_issue`; `detail` says
 * "waiting on #n"); the rest is null for anything but a start. `step` is the activity the flow waits at, from the
 * innermost called process (a chore's Smith run, say); null once `ended`. `stage` is the id of the activity the
 * outermost process (the chore, bug, feature or intake flow itself) waits at, such as `call-do-work` or
 * `task-merge-pr`; null once `ended`. `incident` puts the ticket in the desktop's "Needs you" group, with
 * its Fix and Propose a fix (engine/README.md, "Fix and Propose a fix").
 */
data class FlowView(
    val flow: String, val outcome: String?, val detail: String?, val instance: String?,
    val startedAt: OffsetDateTime?, val endedAt: OffsetDateTime?, val ended: Boolean, val step: String?,
    /** A chore's PR and an intake's filed ticket, once there are any. */
    val prUrl: String?, val ticketUrl: String?,
    val stage: String? = null,
    /** The open incident the flow, or a process it called, is stuck on (engine/README.md, "Incidents"); null when none or `ended`. */
    val incident: IncidentView? = null,
    /**
     * When the flow entered `stage` (its activity started, or for one still waiting on its `asyncBefore` job, when
     * the step before it ended); `stageEstimateMs` how long `stage` should take ([StageEstimates]), null for a stage
     * that waits on someone; `stageRound` how many times the flow has entered `stage`, so a rework round is > 1.
     * All three are null once `ended`, and on an approval that started no flow (refused, ignored, deferred).
     */
    val stageStartedAt: OffsetDateTime? = null,
    val stageEstimateMs: Long? = null,
    val stageRound: Int? = null,
    /**
     * Where `stageEstimateMs` came from ([StageEstimates.Estimate]): `stageEstimateBasis` is `scope`, `tier`, `kind`,
     * `role`, `default` or `prior-round`, `stageEstimateRuns` how many runs its median rests on (0 for a default or
     * a prior round). Both null whenever `stageEstimateMs` is.
     */
    val stageEstimateBasis: String? = null,
    val stageEstimateRuns: Int? = null,
    /** A proposal's pitch page (`docs/proposals/<slug>.html`), from its stored propose result; null for any other flow or none. */
    val pitch: String? = null,
)

/** One open issue in a target repo, as GitHub lists it, with the engine's state joined on. */
data class BoardIssue(
    val repo: String, val number: Long, val title: String, val author: String, val labels: List<String>, val url: String,
    val createdAt: OffsetDateTime, val updatedAt: OffsetDateTime,
    /** The one kind label (`chore`, `intake`), or null when it has none or several. */
    val kind: String?,
    val authorized: Boolean,
    val flow: FlowView?,
    val pendingQuestion: IntakeQuestion?,
    /** GitHub's "blocked by" issues, open or closed; empty when none, or when GitHub could not say. */
    val blockedBy: List<BlockedBy> = emptyList(),
    /** Its approval waits on an open blocker (engine/README.md, "Blocked by"), or it is a split parent waiting on a child. */
    val deferred: Boolean = false,
    /** A split feature's children, filed and pending (engine/README.md, "Split features"); empty for any other issue. */
    val children: List<ChildView> = emptyList(),
    /** The split feature this issue is a child of, if any. */
    val parent: ParentView? = null,
    /** The intake that filed this issue (engine/README.md, "The issues board"); null for one no intake filed. */
    val intake: IntakeView? = null,
    /** Who asked for the ticket (`engine.handled_issue.asked_by`, engine/README.md, "Intake", `by`); null when the engine has not seen it. */
    val askedBy: String? = null,
    /** Its story points, from its latest `engine.handled_issue` row that has them; null when the engine has not seen it, or it was filed unpointed. */
    val points: Int? = null,
)

/** One "blocked by" issue: `#number` in the same repo, `state` open or closed. */
data class BlockedBy(val number: Long, val state: String)

/**
 * One part of a split: `state` pending (not filed yet), open, merged, satisfied (closed with nothing to do, no PR), or dropped; number and url once filed;
 * `points` the filed part's story points (`engine.handled_issue.points`), null while pending or when it was filed unpointed.
 */
data class ChildView(val index: Int, val title: String, val kind: String, val state: String, val number: Long? = null, val url: String? = null,
                     val prNumber: Long? = null, val points: Int? = null)

data class ParentView(val number: Long, val url: String)

/**
 * One issue's board row, read by its number ([IssueBoard.row]): what a `flow`, `ticket` or `created` frame carries
 * (engine/README.md, "Events"). An interface so a frame's source can be tested without the board's joins.
 */
fun interface BoardRows {
    fun row(repo: String, number: Long): BoardIssue?
}

package com.codefrak.engine.intake

import com.codefrak.engine.at
import com.codefrak.engine.board.NeedKeys
import java.time.OffsetDateTime

/**
 * `repo`: the target repo (`owner/name`) the ask item and its ticket are filed in (400 if it is no target repo);
 * absent, the calling Smith's own repo when it is a target repo; any other request without one is a 400: there is
 * no default repo, every intake names its own.
 * `submittedBy`: the submitter's GitHub login. `authorize`: null authorizes the intake at once if the
 * submitter may; true authorizes it as the submitter or refuses the request (400 without one, 403 if
 * they may not); false never authorizes at filing, so it waits for an `authorized` label. `fromRun`: the
 * Smith run a frakworks MCP server in a sandbox says it files for; checked against the caller's address, never
 * taken as given (engine/README.md, "The frakworks server in a Smith").
 */
data class AskRequest(val ask: String, val dryRun: Boolean = false, val fakeOutcome: String? = null, val submittedBy: String? = null,
                      val authorize: Boolean? = null, val fromRun: FromRun? = null, val repo: String? = null,
                      /** The submitting Claude Code session's name, told the ticket once filed ("Telling the session that filed it"). */
                      val notifySession: String? = null,
                      /** What the filing session wrote about the conversation before the ask; not the user's words. Blank is none. */
                      val context: String? = null)
/** What a Smith's frakworks server claims about itself: `SMITH_ID`, `SMITH_ISSUE`, `SMITH_REPO`. */
data class FromRun(val runId: String, val issue: Long? = null, val repo: String? = null)
/**
 * POST /intake's answer. `ticket` and `ticketUrl` are the ask item's, set by `open the ask item`, which runs inside the
 * request (no `asyncBefore`); the refiner rewrites that item in place, so they name the filed ticket unless it files in
 * another repo. Null only if the started instance has already ended.
 */
data class IntakeStarted(val intakeId: String, val repo: String? = null, val ticket: Long? = null, val ticketUrl: String? = null)
/**
 * `startedAt`/`endedAt` in UTC; `endedAt` is null while the step is still open. `estimateMs`, `estimateBasis` and
 * `estimateRuns`: how long the step should take, from the same [StageEstimates] the board's `stageEstimateMs` comes
 * from ([StageEstimates.Estimate]); set only on an open step of the root flow while it runs, and never on one that
 * waits on someone (`user-task-*`, `event-*`) or has no start.
 */
data class Step(val process: String, val activity: String, val name: String?,
                val startedAt: OffsetDateTime? = null, val endedAt: OffsetDateTime? = null,
                val estimateMs: Long? = null, val estimateBasis: String? = null, val estimateRuns: Int? = null)
/**
 * `pendingQuestion`: the question the intake waits on, or null. `questions`: every question asked on it, answered or not.
 * `failedWrites`: after a cancel, each GitHub write that failed (what, and GitHub's message); empty otherwise.
 */
data class IntakeState(val intakeId: String, val ended: Boolean, val steps: List<Step>, val variables: Map<String, Any?>,
                       val pendingQuestion: IntakeQuestion?, val questions: List<IntakeQuestion>,
                       val failedWrites: List<String> = emptyList())
/** One file attached to the answer of the question a flow waits on (engine/README.md, "Answer attachments"): `content` is base64. */
data class AttachmentUpload(val name: String, val mime: String? = null, val content: String, val by: String? = null)
/** What was stored: the name, the type it is kept as, its size in bytes. */
data class AttachmentStored(val intakeId: String, val name: String, val mime: String, val size: Int)
/** `by`: the canceller's GitHub login; it must be a trusted login. */
data class CancelRequest(val by: String? = null, val reason: String)

/** The service task every flow that opens a PR merges it at (chore, bug, feature, proposal). */
const val MERGE_TASK = "task-merge-pr"

/** The user task in bpmn/intake.bpmn an intake waits at after the `ask` outcome. */
const val ANSWER_TASK = "user-task-answer-question"

/** The user task in bpmn/intake.bpmn an intake waits at when Jev matched its ask to an open ticket. */
const val DUPLICATE_TASK = "user-task-answer-duplicate"

/**
 * Every user task an answer completes: the intake's two (the refiner's question, the duplicate question), a feature's three
 * (bpmn/feature.bpmn).
 */
const val ANSWER_TASKS = "user-task-answer-%"

/** POST /intake's `context`: the filing session's summary of the conversation, set only when given. */
const val CONTEXT = "context"

/** The `GET /needs` kind of the row a flow waiting at `taskKey` shows, as `Needs.wanted` names it. */
fun needKind(taskKey: String?): String = when (taskKey) {
    NeedKeys.DECISION_TASK -> "decision"
    NeedKeys.ACCEPTANCE_TASK, NeedKeys.MERGE_ACCEPTANCE_TASK -> "acceptance"
    else -> "question"
}

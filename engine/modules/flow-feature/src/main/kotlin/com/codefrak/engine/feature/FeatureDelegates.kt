package com.codefrak.engine.feature

import com.codefrak.engine.chore.FlowStarter
import com.codefrak.engine.chore.flow
import com.codefrak.engine.github.Blocker
import com.codefrak.engine.github.CreatedIssue
import com.codefrak.engine.github.IssueListing
import com.codefrak.engine.github.IssueSnapshot
import com.codefrak.engine.intake.AUTHORIZED_BY
import com.codefrak.engine.intake.IntakeQuestions
import com.codefrak.engine.intake.AskWaits
import com.codefrak.engine.board.NeedKeys
import com.codefrak.engine.hitlp.FlowWait
import com.codefrak.engine.hitlp.HitlpAsserted
import com.codefrak.engine.hitlp.HitlpFiling
import com.codefrak.engine.repos.DEFAULT_CHILD_MODE
import com.codefrak.engine.repos.Deferred
import com.codefrak.engine.repos.DeferredIssues
import com.codefrak.engine.repos.HandledIssues
import com.codefrak.engine.proposal.ProposeResult
import com.codefrak.engine.research.ResearchResult
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.PARENT_ENDED
import com.codefrak.engine.smith.ReproduceResult
import com.codefrak.engine.smith.SmithRunStats
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.WorkResult
import com.codefrak.engine.smith.issue
import com.codefrak.engine.smith.mode
import com.codefrak.engine.smith.repo
import com.codefrak.engine.smith.tier
import com.codefrak.engine.workitems.Tickets
import com.codefrak.engine.workitems.blockers
import com.codefrak.engine.workitems.commentOn
import com.codefrak.engine.workitems.item
import com.codefrak.engine.workitems.listings
import com.codefrak.engine.workitems.snapshot
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.operaton.bpm.engine.variable.Variables
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

private fun DelegateExecution.output() = getVariable(Vars.OUTPUT) as? ByteArray ?: error("the smith wrote no result; see its log")
private fun DelegateExecution.split() = SplitState.of(getVariable(Vars.SPLIT) as? ByteArray)
private fun DelegateExecution.setSplit(state: SplitState) = setVariable(Vars.SPLIT, Variables.byteArrayValue(state.bytes()))

/** `childMode` `live` with no live parent session ended early yet: a parent Smith watches the children ("Split features", "Live"). */
private fun DelegateExecution.liveParent() = getVariable(Vars.CHILD_MODE) == LIVE && getVariable(LIVE_FALLBACK) != true

private const val LIVE = "live"
/** The parent's work item id on the work-item path, as the intake's work-item path sets it. */
const val WORK_ITEM = "workItem"
/** Set by `task-note-live-ended`: the split goes on one replan per close, as `parallel` does, until a trusted login restarts the live parent (`restart live parent`). */
const val LIVE_FALLBACK = "liveFallback"
/** Set by `task-reconcile-children`: every child closed and the parent's result is not `ended`; `apply the replan` applies it. */
const val LIVE_CLOSED = "liveClosed"

/**
 * `task-file-child` in bpmn/feature.bpmn (engine/README.md, "Split features"):
 * files the next unfiled draft whose dependencies merged, one at a time (under `childMode` `parallel` or
 * `live`, every such draft in one pass), through [ChildFiling] (the intake's `TicketFiler` at the parent's tier as a
 * work item, shared with the live parent's `file_children`); records the parent as blocked by it in the
 * work-item store; starts the child's flow with the
 * parent's authorizer, as an intake carry-over does; and parks the parent as an
 * `engine.deferred_issue` row naming this instance, blocked by the child just filed (parallel: by every open
 * child), which the scan wakes with `child closed` once a child merged. A feature child never runs looser than
 * its parent: under a confirm parent every feature child is confirm. A parent that builds a proposal
 * (`Builds:`) puts its line at the top of each child's body. Bound by `${fileChild}`.
 */
@Component("fileChild")
class FileChildDelegate(
    private val filing: ChildFiling,
    private val deferred: DeferredIssues,
) : JavaDelegate {
    private val log = LoggerFactory.getLogger(FileChildDelegate::class.java)

    override fun execute(execution: DelegateExecution) = try { fileNext(execution) } catch (e: Exception) {
        // Logged here, before Operaton handles it: a persistence error while it does would otherwise be all the incident names (#2581).
        log.error("feature {}: filing the next child failed: {}", execution.processInstanceId, e.message, e)
        throw e
    }

    private fun fileNext(execution: DelegateExecution) {
        val repo = execution.repo(); val parent = execution.issue(); val id = execution.processInstanceId
        var state = execution.split() ?: error("no split state: nothing to file")
        // `live parent?` reads it in its expression, so it must exist before the first fallback sets it.
        if (!execution.hasVariable(LIVE_FALLBACK)) execution.setVariable(LIVE_FALLBACK, false)
        val parallel = (execution.getVariable(Vars.CHILD_MODE) as? String ?: DEFAULT_CHILD_MODE) != DEFAULT_CHILD_MODE
        val by = execution.getVariable(AUTHORIZED_BY) as? String
        val ticket = Brief.ticket(execution.getVariable(Vars.TICKET) as ByteArray)
        val target = filing.target(execution)
        val blockers = if (!parallel) {
            val draft = state.next() ?: error("no draft whose dependencies have merged; the replan should have revised or dropped them")
            state = filing.file(target, state, draft)
            listOf(state.filed.last())
        } else {
            // Parallel and live: every ready draft in one pass, each recorded before the next (ChildFiling.fileReady). A
            // failure after the first keeps what was filed and parks on it; the drafts not filed are still ready at the
            // next pass (the next close).
            state = filing.fileReady(target, state).first
            state.open().ifEmpty { error("no child open and no draft whose dependencies have merged; the replan should have revised or dropped them") }
        }
        // Live: the parent Smith watches the children, and the flow reconciles their closes after it; nothing is parked
        // for the scan to wake. The row comes back if that session ends early (`liveFallback`).
        if (execution.liveParent()) return deferred.remove(repo, parent)
        // One row, replacing the last: blocked by the child just filed (serial) or by every open child (parallel and live).
        val stalled = deferred.get(repo, parent)?.stalled.orEmpty()
        val rows = blockers.map { Blocker(repo, it.number, "open") }
        deferred.defer(Deferred(repo, parent, 0, by ?: "engine", "feature", execution.getVariable(Vars.FEATURE_MODE) as? String,
            IssueSnapshot(ticket.number, ticket.title, ticket.body, ticket.author),
            rows, execution.tier(), target.detail, execution.getVariable(Vars.DRY_RUN) == true, waitingInstance = id))
        // A sibling already said to be closed without a merge is not said again after the re-park.
        val kept = stalled.filter { s -> rows.any { "${it.repo}#${it.number}" == s } }.toSet()
        if (parallel && kept.isNotEmpty()) deferred.checked(repo, parent, rows, kept)
    }
}

/**
 * The open issue [engine] (the login of the repo's token, which files children) already filed for part [part] of
 * [total] of [parent] titled [title]: its body ends with the part line and the `split of #parent` trailer
 * [FileChildDelegate] writes. Null when there is none.
 */
internal fun filedChild(open: List<IssueListing>, engine: String, parent: Long, part: Int, total: Int, title: String): CreatedIssue? =
    open.firstOrNull { it.author.equals(engine, ignoreCase = true) && it.title == title && it.body.orEmpty().let { b -> "Part $part of $total of #$parent." in b && "<!-- engine: split of #$parent " in b } }
        ?.let { CreatedIssue(it.number, it.url) }

/** The id of a root flow running on [repo]#[issue] other than [except] (the parent's own), or null when none runs. */
fun runningFlow(runtime: org.operaton.bpm.engine.RuntimeService, repo: String, issue: Long, except: String): String? =
    runtime.createProcessInstanceQuery().rootProcessInstances().variableValueEquals(Vars.REPO, repo).variableValueEquals(Vars.ISSUE, issue).list()
        .firstOrNull { it.id != except }?.id

/** [filedChild] among the closed issues of [repo] in [blockers] (the parent's), each read by [read]. */
internal fun filedClosedChild(repo: String, blockers: List<Blocker>, read: (Long) -> IssueSnapshot, engine: String, parent: Long, part: Int, total: Int, title: String): CreatedIssue? =
    filedChild(blockers.filter { !it.stillOpen() && it.repo.equals(repo, ignoreCase = true) }.map { read(it.number).listing(repo) }, engine, parent, part, total, title)

private fun IssueSnapshot.listing(repo: String) =
    java.time.OffsetDateTime.MIN.let { IssueListing(number, title, author, emptyList(), "https://github.com/$repo/issues/$number", it, it, body) }

/**
 * `task-apply-replan`: records the closed child (merged, satisfied with nothing to do, or split by its work run,
 * its drafts put in its place: engine/README.md, "A child that splits") on the split state, then acts on
 * the replan Smith's outcome: `continue` keeps the drafts, `revise` replaces
 * them, `done` drops them. Sets `draftsLeft` for the gateway, and `pr` to the
 * last merged child's PR so the acceptance and the close have one. Then delivers each of the replan's `steer`
 * entries to its open child (engine/README.md, "Steer"): a comment on the child's issue and the entry on its root
 * instance's `steer` variable, for every later brief; the child's current run is neither killed nor restarted.
 * Bound by `${applyReplan}`.
 */
@Component("applyReplan")
class ApplyReplanDelegate(private val tickets: Tickets, private val handled: HandledIssues, private val starter: FlowStarter) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        if (execution.getVariable(LIVE_CLOSED) == true) return applyLive(execution)
        val replan = ReplanResult.parse(execution.getVariable(Vars.REPLAN) as? ByteArray ?: error("no replan result to apply"))
        val pr = (execution.getVariable(Vars.CLOSED_CHILD_PR) as? Number)?.toLong()
        val parts = ChildDraft.parse(execution.getVariable(Vars.CLOSED_CHILD_SPLIT) as? ByteArray)
        val child = (execution.getVariable(Vars.CLOSED_CHILD) as? Number)?.toLong() ?: error("no closed child to record")
        // A child its work split: its drafts take its place before the replan's outcome applies ("A child that splits").
        var state = (execution.split() ?: error("no split state")).closed(child, pr, parts)
        state = when (replan.outcome) {
            "revise" -> state.revised(replan.children)
            "done" -> state.done()
            else -> state
        }
        execution.setSplit(state)
        // The last merged child's PR, so the board's prUrl and the close have one; the parent opened none itself.
        pr?.let {
            execution.setVariable(Vars.PR, it)
            execution.setVariable(Vars.PR_URL, "https://github.com/${execution.repo()}/pull/$it")
            execution.setVariable(Vars.BRANCH, "smith/issue-$child")
        }
        execution.setVariable("draftsLeft", state.next() != null)
        // Parallel and live: siblings still open bring the flow back to re-park on them (`more to do?`); serial has none.
        val parallel = (execution.getVariable(Vars.CHILD_MODE) as? String ?: DEFAULT_CHILD_MODE) != DEFAULT_CHILD_MODE
        execution.setVariable("childrenOpen", parallel && state.open().isNotEmpty())
        val what = when (replan.outcome) {
            "revise" -> "the remaining drafts were revised: ${state.drafts.size} left" + state.dropped.takeIf { it.isNotEmpty() }?.let { " (dropped: ${it.joinToString()})" }.orEmpty()
            "done" -> "nothing more to file" + state.dropped.takeIf { it.isNotEmpty() }?.let { " (dropped: ${it.joinToString()})" }.orEmpty()
            else -> if (state.drafts.isEmpty() && (!parallel || state.open().isEmpty())) "every part closed; on to acceptance"
                else "${state.drafts.size} draft(s) left" + if (parallel && state.open().isNotEmpty()) ", ${state.open().size} child(ren) open" else ""
        }
        val closed = parts?.let { "split into ${it.size} parts by its work run" } ?: pr?.let { "merged (PR #$it)" } ?: "closed with nothing to do"
        val steers = steer(execution, replan.steer, state)
        tickets.commentOn(execution.repo(), execution.issue(), "Child #$child $closed; replan: ${replan.outcome}, $what." + steers +
            replan.summary?.takeIf { it.isNotBlank() }?.let { "\n\n${it.trim()}" }.orEmpty() + "\n\n— engine feature `${execution.processInstanceId}`")
    }

    /**
     * Live: the parent Smith's result after every child closed (`task-reconcile-children` recorded the closes), applied
     * as a replan's. `ended` here (every child closed first) keeps the drafts, as `continue` does.
     */
    private fun applyLive(execution: DelegateExecution) {
        val parent = ParentResult.parse(execution.getVariable(Vars.PARENT) as? ByteArray ?: error("no parent result to apply"))
        execution.setVariable(LIVE_CLOSED, false)
        var state = execution.split() ?: error("no split state")
        state = when (parent.outcome) {
            "revise" -> state.revised(parent.children)
            "done" -> state.done()
            else -> state
        }
        execution.setSplit(state)
        state.filed.lastOrNull { it.prNumber != null }?.let {
            execution.setVariable(Vars.PR, it.prNumber)
            execution.setVariable(Vars.PR_URL, "https://github.com/${execution.repo()}/pull/${it.prNumber}")
            execution.setVariable(Vars.BRANCH, "smith/issue-${it.number}")
        }
        execution.setVariable("draftsLeft", state.next() != null)
        execution.setVariable("childrenOpen", false)
        val what = when {
            parent.outcome == "revise" -> "the remaining drafts were revised: ${state.drafts.size} left"
            parent.outcome == "done" -> "nothing more to file"
            state.drafts.isEmpty() -> "every part closed; on to acceptance"
            else -> "${state.drafts.size} draft(s) left"
        } + state.dropped.takeIf { it.isNotEmpty() && parent.outcome != "continue" }?.let { " (dropped: ${it.joinToString()})" }.orEmpty()
        tickets.commentOn(execution.repo(), execution.issue(), "Every child closed under the live parent; its result: ${parent.outcome}, $what." +
            parent.summary?.takeIf { it.isNotBlank() }?.let { "\n\n${it.trim()}" }.orEmpty() + "\n\n— engine feature `${execution.processInstanceId}`")
    }

    /** Delivers each steer to its child; the line the parent's replan comment carries about them, empty without any. */
    private fun steer(execution: DelegateExecution, steers: List<Steer>, state: SplitState): String {
        if (steers.isEmpty()) return ""
        val repo = execution.repo(); val parent = execution.issue()
        val (steered, ended) = steers.partition { s ->
            val kind = state.filed.firstOrNull { it.number == s.child }?.kind
            val instance = kind?.let { handled.startedBefore(repo, s.child, it).lastOrNull { i -> i != null } }
            if (instance != null && starter.steer(instance, "#$parent", s.message)) {
                tickets.commentOn(repo, s.child, "Steer from parent #$parent: ${s.message.trim()}\n\n— engine feature `${execution.processInstanceId}`")
                true
            } else false
        }
        return buildString {
            if (steered.isNotEmpty()) append(" Steered ${steered.joinToString { "#${it.child}" }}.")
            if (ended.isNotEmpty()) append(" Not steerable, their flow has ended: ${ended.joinToString { "#${it.child}" }}.")
        }
    }
}

/**
 * `task-reconcile-children` (engine/README.md, "Split features", "Live"): after the live parent run, records each
 * filed child still open on the split state that the work-item store shows closed by the engine's own close (its flow ended with
 * a merge or a nothing-to-do close, as the scan's wake counts it), with its PR, or by its own split close, with the
 * drafts that take its place (engine/README.md, "A child that splits"): [ChildFiling.reconcile], which the parent's
 * `file_children` runs mid-run too. Sets `liveClosed` when no child is
 * left open and the parent's result is not `ended`, or (every child closed) it is: the drafts are kept then.
 * Bound by `${reconcileChildren}`.
 */
@Component("reconcileChildren")
class ReconcileChildrenDelegate(private val filing: ChildFiling) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val state = filing.reconcile(execution.repo(), execution.issue(), execution.split() ?: error("no split state"))
        execution.setSplit(state)
        execution.setVariable(LIVE_CLOSED, state.open().isEmpty())
    }
}

/**
 * `task-note-live-ended`: the live parent session ended (its turn budget, the Job deadline, a failed run, or a
 * result written early) with children still open. Escalates every child question still waiting on the parent to the
 * user ([ParentQuestions.escalateAll]), says so and sets `liveFallback`, so `file the next child` re-parks
 * the parent on the open children at `child closed` and the rest of the split runs one replan per close.
 * Bound by `${noteLiveEnded}`.
 */
@Component("noteLiveEnded")
class NoteLiveEndedDelegate(private val tickets: Tickets, private val parentQuestions: ParentQuestions? = null) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val open = (execution.split() ?: error("no split state")).open()
        val why = execution.getVariable(PARENT_ENDED) as? String
            ?: (execution.getVariable(Vars.PARENT) as? ByteArray)?.let(ParentResult::parse)?.let { p ->
                if (p.outcome == ParentResult.ENDED) p.summary?.trim() else "it wrote `${p.outcome}` before its children closed"
            } ?: "its run failed"
        // Every child question still waiting on the parent goes to the user before the fallback (#100049).
        parentQuestions?.escalateAll(execution.repo(), execution.issue(), why)
        execution.setVariable(LIVE_FALLBACK, true)
        execution.setVariable(LIVE_CLOSED, false)
        val text = "The live parent session ended ($why) with ${open.joinToString { it.item ?: "#${it.number}" }} still open; " +
            "continuing one child at a time."
        tickets.commentOn(execution.repo(), execution.issue(), "$text\n\n— engine feature `${execution.processInstanceId}`")
    }
}

/** What `restart live parent` carries: the trusted login that restarted it (`POST /ticket/{owner}/{repo}/{n}/live-parent`). */
const val LIVE_RESTART_BY = "liveRestartBy"

/**
 * `task-restart-live-parent`: a trusted login sent `restart live parent` to a fallen-back live split parked at
 * `child closed`. Clears `liveFallback` (and `liveClosed`), drops the parent's deferred row so the scan no longer
 * wakes it, and says so; `call-parent` starts next and watches the children still open, which keep running
 * untouched. A child closed in between is recorded by `reconcile the children` after the parent run, as always.
 * Bound by `${restartLiveParent}`.
 */
@Component("restartLiveParent")
class RestartLiveParentDelegate(private val tickets: Tickets, private val deferred: DeferredIssues) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val open = (execution.split() ?: error("no split state")).open()
        execution.setVariable(LIVE_FALLBACK, false)
        execution.setVariable(LIVE_CLOSED, false)
        val by = execution.getVariable(LIVE_RESTART_BY) as? String
        val text = "The live parent was restarted${by?.let { " by @$it" } ?: ""}; it watches " +
            "${open.joinToString { it.item ?: "#${it.number}" }.ifEmpty { "the children" }}."
        val repo = execution.repo(); val parent = execution.issue()
        deferred.remove(repo, parent)
        tickets.commentOn(repo, parent, "$text\n\n— engine feature `${execution.processInstanceId}`")
    }
}

/**
 * `task-note-overflow`: the work run died overflowing its context window
 * (`overflowHit`). Says so on the ticket and sends the flow back to design with
 * `overflowed` in the brief, so the design Smith splits it instead of the same
 * brief being retried. Bound by `${noteOverflow}`.
 */
@Component("noteOverflow")
class NoteOverflowDelegate(private val tickets: Tickets) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        execution.setVariable(Vars.OVERFLOWED, true)
        tickets.commentOn(execution.repo(), execution.issue(), "The work run overflowed its context window (`Prompt is too long`). " +
            "Designing again with a note that this ticket should split into parts.\n\n— engine feature `${execution.processInstanceId}`")
    }
}

/**
 * The question step of every ticket flow that asks the user: `task-post-question`
 * in bpmn/feature.bpmn, for a design or work run's `ask` outcome and for a
 * confirm-mode plan whose questions the user must answer; bpmn/proposal.bpmn,
 * for a propose run's `ask` or its important questions; and bpmn/chore.bpmn and
 * bpmn/bug.bpmn, for a work (or a bug's reproduce) run's `ask`. Records the
 * question in `engine.intake_question` under the flow's instance id and posts
 * it on the ticket, filed as a HITLP `human.ask` ([HitlpFiling.ask], its task id in
 * [Vars.HITLP_TASK_ID]). The flow then waits at a `user-task-answer-*` task for the
 * decision record (`HitlpAnswers`), and the answers ride in every
 * later brief. A child of a live split whose parent runs ([LiveParents]) asks the parent
 * instead ([ParentQuestions.ask], #100049): no HITLP request until the parent escalates it.
 * Bound by `${postFeatureQuestion}`, the name the feature gave it
 * first; kept so the flows' deployed versions and their waiting jobs resolve.
 */
@Component("postFeatureQuestion")
class PostFeatureQuestionDelegate(
    private val tickets: Tickets,
    private val questions: IntakeQuestions,
    private val hitlp: HitlpFiling,
    private val runs: SmithRunStats,
    private val liveParents: LiveParents? = null,
    private val parentQuestions: ParentQuestions? = null,
) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val mode = execution.mode()
        val question = when (mode) {
            "design" -> DesignResult.parse(execution.output()).let { if (it.outcome == "ask") it.question else it.userQuestion() }
            "work" -> WorkResult.parse(execution.output()).question
            "reproduce" -> ReproduceResult.parse(execution.output()).question
            "propose" -> ProposeResult.parse(execution.output()).let { if (it.outcome == "ask") it.question else it.userQuestion() }
            "research" -> ResearchResult.parse(execution.output()).question
            else -> error("a $mode run does not ask")
        } ?: error("no question in the $mode smith's result")
        val repo = execution.repo(); val issue = execution.issue(); val id = execution.processInstanceId
        // A child of a live split whose parent runs asks the parent first (#100049): no HITLP request, no `/needs` row.
        val parent = (execution.getVariable(Vars.TICKET) as? ByteArray)?.let(Brief::ticket)?.body?.let(Brief::parent)
            ?.let { liveParents?.of(repo, issue, it) }
        if (parent != null && parentQuestions != null) return parentQuestions.ask(id, repo, issue, mode, question, parent)
        val nth = questions.list(id).size + 1
        questions.ask(id, repo, issue, question)
        val filed = hitlp.ask(FlowWait(id, AskWaits.answerTask(execution), HitlpAsserted.Kind.QUESTION, repo, issue, question,
            AskWaits.askBlock(question), NeedKeys.askerIn(execution.variables), AskWaits.runId(execution, runs), nth = nth))
        execution.setVariable(Vars.HITLP_TASK_ID, filed.handle.taskId)
        tickets.commentOn(repo, issue, "The $mode Smith has a question ($nth) before it can go on:\n\n${question.trimEnd()}\n\n" +
            "— engine ${execution.flow()} `$id`. ${IntakeQuestions.answerHint(filed.decisionUrl, filed.handle.taskId)}; " +
            "the next run works from the ticket plus every answer.")
    }
}

/**
 * `task-report-unmet`: the feature merged, but the accept Smith found a
 * criterion unmet (or, on an instance started before #583, the user did not accept). Comments why and leaves the
 * ticket open for a human; the flow ends, since another round would start
 * from a PR that is already merged. Bound by `${reportUnmet}`.
 */
@Component("reportUnmet")
class ReportUnmetDelegate(private val tickets: Tickets) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val acceptance = AcceptResult.parse(execution.getVariable(Vars.ACCEPTANCE) as? ByteArray ?: error("no acceptance to report"))
        val who = if (acceptance.outcome == "rejected") "the user did not accept it" else "the acceptance check found a criterion unmet"
        val checklist = acceptance.checklist().takeIf { it.isNotEmpty() }?.let { "\n\n$it" }.orEmpty()
        tickets.commentOn(execution.repo(), execution.issue(), "${execution.mergedText()}, but $who:\n\n" +
            "${acceptance.summary!!.trim()}$checklist\n\nLeaving this open for a human.\n\n— engine feature `${execution.processInstanceId}`")
    }
}

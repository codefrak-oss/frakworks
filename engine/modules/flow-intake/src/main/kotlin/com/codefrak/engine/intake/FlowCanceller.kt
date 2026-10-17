package com.codefrak.engine.intake

import com.codefrak.engine.board.NeedKeys
import com.codefrak.engine.camunda.Actor
import com.codefrak.engine.camunda.ActorAccess
import com.codefrak.engine.chore.ProcessFlowStarter
import com.codefrak.engine.doctor.DoctorBugLinks
import com.codefrak.engine.doctor.DoctorRunEnding
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.feature.SplitState
import com.codefrak.engine.github.GitHubPulls
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.repos.DeferredIssues
import com.codefrak.engine.smith.RunSandboxStopper
import com.codefrak.engine.smith.SmithSandbox
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.workitems.TicketState
import com.codefrak.engine.workitems.Tickets
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.OptimisticLockingException
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.TaskService
import org.operaton.bpm.engine.runtime.ProcessInstance
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/**
 * A flow [FlowCanceller.stop] ended: its kind, how many Smith sandboxes it stopped, the PR it opened and had not
 * merged (null if none) or the one it merged, and its branch.
 */
internal data class Stopped(val id: String, val kind: String, val sandboxes: Int, val pr: Long?, val merged: Long?, val branch: String?,
                            /** The `GET /needs` kind of the question it waited on and withdrew, null when it waited on none. */
                            val question: String? = null)

/** Closes the stopped flow's open PR, and says in a sentence each what was and was not undone. [prClosed] is null when there was no PR to close. */
internal fun undoNotes(flow: Stopped, prClosed: Boolean?): List<String> = buildList {
    if (flow.sandboxes > 0) add("Its running Smith is stopped.")
    flow.pr?.let { pr -> add(if (prClosed == true) "PR #$pr is closed unmerged." else "PR #$pr could not be closed; close it by hand.") }
    flow.merged?.let { add("PR #$it was already merged and stays merged.") }
    flow.branch?.let { add("Kept: branch `$it` and every commit on it.") }
}

internal fun cancelComment(head: String, notes: List<String>) =
    listOf(head, notes.joinToString(" "), "— engine").filter { it.isNotEmpty() }.joinToString("\n\n")

/**
 * Cancels a running root flow: what `DELETE /intake/{id}` does for a login, and what
 * frakworks-engine-diagnostics' `act_on_incident` does for a Smith (engine/README.md,
 * "Cancel and the sweep", "The diagnostics server").
 */
@Component
class FlowCanceller(
    private val runtime: RuntimeService, private val history: HistoryService, private val tasks: TaskService,
    private val questions: IntakeQuestions, private val actors: ActorAccess,
    private val sandbox: SmithSandbox, private val pulls: GitHubPulls, private val deferred: DeferredIssues,
    private val jevCalls: JevCalls, private val events: EngineEvents, private val doctorBugs: DoctorBugLinks,
    private val doctorRuns: DoctorRunEnding, private val hitlp: HitlpAnswers,
    private val tickets: Tickets,
    private val stopPauseMillis: Long = STOP_PAUSE_MILLIS,
) {
    private val log = LoggerFactory.getLogger(FlowCanceller::class.java)
    private val sandboxStopper = RunSandboxStopper(runtime, sandbox)

    /**
     * Cancels a running root flow of any kind (an intake, chore, bug, feature or proposal), parked on
     * an incident or not (engine/README.md, "Cancel and the sweep"). [actor] must be allowed its kind
     * on its repo ([ActorAccess]: a login's access, a Smith's own repo), and for a split feature each
     * open child's kind too (403 if not; 404 if it is not running; 400 if a parent calls it, or with no
     * reason). Engine first: a split parent's parked row goes, then each flow cancelled has its Smith
     * sandboxes stopped, its instance deleted and any question it waits on withdrawn (its HITLP task cancelled). GitHub after: an
     * open PR is closed unmerged (branch and commits kept), and the issue is closed as not planned with
     * a comment naming [actor], the reason and what was and was not undone. A split feature's open
     * children go the same way, each with its own comment. A GitHub write that fails is logged and
     * returned; the flows are gone either way. A doctor run is checked against its ticket's kind and repo, and
     * only it is ended ([cancelDoctor]). Each flow's [stop] is retried on an [OptimisticLockingException] (a flow
     * seconds old is still writing the rows it deletes), [STOP_ATTEMPTS] times in all; when every attempt conflicts,
     * the last one is thrown and nothing is written to GitHub.
     */
    fun cancel(id: String, actor: Actor, reason: String): List<String> {
        val instance = runtime.createProcessInstanceQuery().processInstanceId(id).singleResult()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "$id is not running")
        history.createHistoricProcessInstanceQuery().processInstanceId(id).singleResult()?.superProcessInstanceId
            ?.let { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$id is called by $it; cancel that") }
        val kind = instance.processDefinitionId.substringBefore(':')
        // A doctor run is its ticket's to cancel: the ticket's repo, and the kind of its flow (ActorAccess.ticketOf).
        val ticket = actors.ticketOf(instance)
        val repo = ticket.repo
        val issue = ticket.issue
        if (reason.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "reason is blank")
        if (repo == null || !actors.allowed(actor, repo, ticket.kind, listOf(id)))
            throw ResponseStatusException(HttpStatus.FORBIDDEN, actors.refusal(actor, "cancel", ticket.described, repo))
        if (ticket.doctor) return cancelDoctor(id, repo, issue, ticket.kind, actor, reason)
        val split = if (kind == "feature") SplitState.of(runtime.getVariable(id, Vars.SPLIT) as? ByteArray) else null
        val children = split?.filed.orEmpty().filter { it.state !in SplitState.DONE }
        children.firstOrNull { !actors.allowed(actor, repo, it.kind, running(repo, it.number).map { f -> f.id }) }?.let {
            val who = if (actor is Actor.Login) actor.by else actor.name
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "$who may not cancel child #${it.number}, a ${it.kind}, on $repo")
        }

        // The engine's side first, so a GitHub failure never leaves a flow running.
        if (issue != null && deferred.get(repo, issue)?.waitingInstance == id) deferred.remove(repo, issue)
        val cancelledChildren = children.map { child ->
            deferred.remove(repo, child.number)
            child to running(repo, child.number).map { stopRetrying(it.id, it.processDefinitionId.substringBefore(':'), actor, reason) }
        }
        // An intake that filed rewrote its issue into the ticket (one issue per ask): that issue stays open.
        val filed = if (kind == "intake") (runtime.getVariable(id, "ticket") as? Number)?.toLong() else null
        val intakeKind = filed?.let { runtime.getVariable(id, "kind") as? String }
        val doctorBug = if (kind == "intake") (runtime.getVariable(id, DOCTOR_BUG) as? Number)?.toLong() else null
        // Whom its question row was for, read before the instance goes.
        val asker = NeedKeys.askerIn(runCatching { runtime.getVariables(id) }.getOrDefault(emptyMap()))
        val root = stopRetrying(id, kind, actor, reason)
        // A doctor intake cancelled before it filed: its bug is declined, with no issue, and keeps its signature (engine/README.md, "Doctor").
        doctorBug?.let { doctorBugs.declineUnfiled(it) }
        // An intake's step, and the `GET /needs` row its question was (engine/README.md, "Events"); the `flow` frame is the history handler's.
        if (kind == "intake") events.publishAfterCommit(EngineEvent.Intake(repo, id, "cancelled", issue, by = actor.name))
        root.question?.let { events.publishAfterCommit(EngineEvent.Needs(repo, it, "removed", issue, id, forLogin = asker)) }

        val failed = mutableListOf<String>()
        val why = reason.trim()
        cancelledChildren.forEach { (child, flows) ->
            val notes = if (flows.isEmpty()) listOf("No flow was running on it.") else flows.flatMap { undo(repo, it, failed) }
            val flow = flows.firstOrNull()?.let { "this ${it.kind} (`${it.id}`)" } ?: "this child"
            githubWrite(failed, "comment on $repo#${child.number}", "cancel", log) {
                tickets.commentOn(repo, child.number, cancelComment("${actor.label} cancelled $flow with its parent #$issue: $why", notes))
            }
            githubWrite(failed, "close $repo#${child.number}", "cancel", log) { tickets.end(repo, child.number, TicketState.CANCELLED, "cancelled with its parent #$issue: $why") }
        }
        if (issue != null) {
            val notes = undo(repo, root, failed).toMutableList()
            if (cancelledChildren.isNotEmpty())
                notes += "Open child${if (cancelledChildren.size == 1) "" else "ren"} ${cancelledChildren.joinToString { "#${it.first.number}" }} cancelled and closed as not planned."
            split?.filed.orEmpty().filter { it.state in SplitState.DONE }.takeIf { it.isNotEmpty() }?.let { done ->
                notes += "Kept: finished children ${done.joinToString { c -> "#${c.number}" + (c.prNumber?.let { " (PR #$it)" } ?: "") }}."
            }
            split?.drafts?.size?.takeIf { it > 0 }?.let { notes += "Dropped: $it part${if (it == 1) "" else "s"} not filed yet." }
            val keep = filed != null && filed == issue
            if (keep) notes += "The ticket it filed is this issue, #$issue, and it stays open: to cancel its " +
                "${intakeKind ?: "flow"}, cancel that flow by its id (`DELETE /intake/{id}` with the ${intakeKind ?: "flow"}'s instance id)."
            // A ticket already closed (merged, say, with a stray second flow on it: #2542) keeps its state, reason and labels.
            val alreadyClosed = kind in ProcessFlowStarter.FLOWS && !keep && runCatching { !tickets.ticket(repo, issue).open }
                .onFailure { log.warn("cancel {}: could not read whether {}#{} is open: {}", id, repo, issue, it.message) }.getOrDefault(false)
            if (alreadyClosed) notes += "The ticket was already closed and is left as it is."
            githubWrite(failed, "comment on $repo#$issue", "cancel", log) {
                tickets.commentOn(repo, issue, cancelComment("${actor.label} cancelled this $kind (`$id`): $why", notes))
            }
            if (kind in ProcessFlowStarter.FLOWS && !keep && !alreadyClosed) githubWrite(failed, "close $repo#$issue", "cancel", log) { tickets.end(repo, issue, TicketState.CANCELLED, "cancelled: $why") }
        }
        return failed
    }

    /**
     * Cancels the doctor run [id] alone (engine/README.md, "Doctor"): [DoctorRunEnding] stops its Smith, deletes it and
     * finishes its `engine.doctor_run` row as `cancelled`. Its ticket's issue only gets a comment naming [actor] and the
     * reason: it is never closed or relabelled, and the ticket's own flow goes on.
     */
    private fun cancelDoctor(id: String, repo: String, issue: Long?, kind: String, actor: Actor, reason: String): List<String> {
        val why = reason.trim()
        val stopped = doctorRuns.end(id, DoctorRunEnding.CANCELLED, "cancelled by ${actor.label}: $why")
        val failed = mutableListOf<String>()
        if (issue != null) {
            val notes = listOfNotNull("Its running Smith is stopped.".takeIf { stopped > 0 }, "The $kind and its own flow are left as they are.")
            githubWrite(failed, "comment on $repo#$issue", "cancel", log) {
                tickets.commentOn(repo, issue, cancelComment("${actor.label} cancelled the doctor run (`$id`) on this $kind: $why", notes))
            }
        }
        return failed
    }

    /**
     * The root flows running on [repo]#[number], by their `repo` and `issue` variables: a split feature's
     * child's, and the flow act_on_incident cancels by ticket (engine/README.md, "The diagnostics server").
     */
    fun running(repo: String, number: Long): List<ProcessInstance> = runtime.createProcessInstanceQuery().rootProcessInstances()
        .variableValueEquals(Vars.REPO, repo).variableValueEquals(Vars.ISSUE, number).list()

    /**
     * [stop], retried on an [OptimisticLockingException] up to [STOP_ATTEMPTS] times in all, [stopPauseMillis] apart ([STOP_PAUSE_MILLIS] by default):
     * each attempt re-reads the instance, which may have moved on. Any other exception is not retried.
     */
    private fun stopRetrying(id: String, kind: String, actor: Actor, reason: String): Stopped {
        var attempt = 1
        while (true) {
            try {
                return stop(id, kind, actor, reason)
            } catch (e: OptimisticLockingException) {
                if (attempt >= STOP_ATTEMPTS) {
                    log.warn("cancel {}: optimistic-locking conflict on every one of {} attempts: {}", id, attempt, e.message)
                    throw e
                }
                log.info("cancel {}: optimistic-locking conflict on attempt {}, retrying", id, attempt)
                Thread.sleep(stopPauseMillis)
                attempt++
            }
        }
    }

    /**
     * Stops every Smith sandbox under [id] and deletes it, after reading what [undo] needs. A split
     * parent opened no PR of its own: its `pr` is a merged child's (engine/README.md, "Split features").
     */
    private fun stop(id: String, kind: String, actor: Actor, reason: String): Stopped {
        val own = runtime.getVariable(id, Vars.SPLIT) == null
        val pr = (runtime.getVariable(id, Vars.PR) as? Number)?.toLong()?.takeIf { own }
        val branch = (runtime.getVariable(id, Vars.BRANCH) as? String)?.takeIf { own }
        val merged = history.createHistoricActivityInstanceQuery().processInstanceId(id).activityId(MERGE_TASK).finished().count() > 0
        val question = if (questions.pending(id) == null) null
            else needKind(tasks.createTaskQuery().processInstanceId(id).taskDefinitionKeyLike(ANSWER_TASKS).list().firstOrNull()?.taskDefinitionKey)
        val sandboxes = stopSandboxes(id)
        duplicateCancelled(id, actor, reason)
        runtime.deleteProcessInstance(id, "cancelled by ${actor.label}: $reason")
        questions.withdraw(id, actor.name, reason.trim())
        // The HITLP tasks it was filed as end too (#2462): `tasks/cancel`, and a local `cancelled` record on each row.
        hitlp.withdraw(id, actor.name, reason.trim())
        return Stopped(id, kind, sandboxes, pr.takeUnless { merged }, pr.takeIf { merged }, branch, question)
    }

    /**
     * An intake cancelled at its duplicate question (engine/README.md, "Intake", "Is this ask already a ticket?"):
     * the user took Jev's match, which is that call's `outcome`. Never stops the cancel.
     */
    private fun duplicateCancelled(id: String, actor: Actor, reason: String) {
        runCatching {
            if (tasks.createTaskQuery().processInstanceId(id).taskDefinitionKey(DUPLICATE_TASK).count() == 0L) return
            val call = runtime.getVariable(id, Vars.DUPLICATE_CALL) as? String ?: return
            jevCalls.recordOutcome(call, "cancelled at the duplicate question by ${actor.label}: ${reason.trim().take(1000)}")
        }.onFailure { log.warn("intake {}: duplicate outcome not recorded: {}", id, it.message) }
    }

    /** Closes the stopped flow's open PR, and says in a sentence each what was and was not undone. */
    private fun undo(repo: String, flow: Stopped, failed: MutableList<String>): List<String> {
        val prClosed = flow.pr?.let { pr ->
            githubWrite(failed, "close PR $repo#$pr", "cancel", log) {
                pulls.comment(repo, pr, "Closed unmerged: its ${flow.kind} `${flow.id}` was cancelled.\n\n— engine")
                pulls.closePull(repo, pr)
            }
        }
        return undoNotes(flow, prClosed)
    }

    /**
     * Deleting the instance deletes the smith-run activities that would poll and remove
     * their sandboxes, so every active smith-run under [root] has its sandbox stopped first.
     * Returns how many were stopped. [IntakeAmender] stops a refine run's this way before it restarts the refine.
     */
    fun stopSandboxes(root: String): Int {
        val stopped = sandboxStopper.stopUnder(root)
        stopped.forEach { (runId, handle) -> log.info("run {}: sandbox {} stopped on cancel", runId, handle.take(12)) }
        return stopped.size
    }

    companion object {
        /** How many times in all a cancel tries one flow's engine-side [stop] when it loses a row lock. */
        const val STOP_ATTEMPTS = 4
        /** The pause between those attempts. */
        const val STOP_PAUSE_MILLIS = 500L
    }
}

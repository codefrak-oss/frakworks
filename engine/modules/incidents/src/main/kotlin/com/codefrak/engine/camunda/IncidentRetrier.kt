package com.codefrak.engine.camunda

import com.codefrak.engine.github.GitPush
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.Finding
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.CI_NOT_MINE_CAP
import com.codefrak.engine.workitems.Tickets
import com.codefrak.engine.workitems.WorkItemRefused
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.runtime.Incident
import org.operaton.bpm.engine.runtime.ProcessInstance
import org.operaton.bpm.engine.variable.Variables
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/**
 * Retries an incident (engine/README.md, "Incidents"). Any incident of a flow may be named:
 * one a failure propagated up a call activity is followed to the one that caused it. What
 * `POST /camunda/incidents/{id}/retry` does, and what a Fix run's `retry` remedy applies
 * (`ApplyIncidentFixDelegate`).
 */
@Component
class IncidentRetrier(
    private val runtime: RuntimeService, private val history: HistoryService, private val management: ManagementService,
    private val actors: ActorAccess, private val tickets: Tickets, private val repository: RepositoryService,
) {
    companion object {
        /** The process whose failed run is re-run rather than its job retried. */
        const val SMITH_RUN = "smith-run"

        /**
         * Whether a failed job's retry runs the Smith again rather than the job: a `smith-run` whose result is set and is
         * not `success`, or whose message says the engine rejected it (an unappliable bundle's among them, [unappliable]).
         */
        fun reruns(definitionKey: String, result: String?, message: String?): Boolean =
            definitionKey == SMITH_RUN && result != null && (result != "success" || message?.startsWith(GitPush.UNAPPLIABLE.trimEnd()) == true)

        /**
         * Whether `message` is `CliGitPush.verify`'s: the bundle's prerequisite commit(s) are on no branch of the repo, or
         * it has no `refs/heads/smith/issue-<n>` ([GitPush.MISSING_BRANCH]).
         */
        /**
         * The call activity that ran the work Smith whose bundle `task-open-pr` failed to push with
         * [GitPush.MISSING_BRANCH] or [GitPush.REWRITES] in a `definitionKey` flow, or null when the incident is not that one.
         */
        fun pushRerun(definitionKey: String, activityId: String?, message: String?): String? =
            if (activityId != OPEN_PR || message?.let { it.startsWith(GitPush.MISSING_BRANCH) || it.startsWith(GitPush.REWRITES) } != true) null
            else when (definitionKey) { "chore", "bug", "feature" -> "call-do-work"; "proposal" -> "call-draft"; else -> null }

        /** The review name of [pushFinding]'s finding; `open/update pr` drops it once a push lands. */
        const val PUSH_REVIEW = "push"

        /**
         * The `push-<n>-1` finding a [pushRerun] leaves for the rerun Smith (as a conflict round leaves `rebase-<n>-1`):
         * why the last round was thrown away, and how to build on the branch so the push lands (#2165 looped on rebases).
         */
        fun pushFinding(n: Int, branch: String, message: String) = Finding(
            "the engine refused to push the last round's bundle: `$message`. The engine pushes `$branch` fast-forward only, " +
                "so build on the branch as your clone has it and commit on top of it. To bring it up to date with the default " +
                "branch, `git fetch origin` and `git merge origin/<default>` (origin/HEAD's branch) into it; never rebase, amend " +
                "or reset commits that are already on the branch. If the ticket or an amendment asks for a rewrite that cannot " +
                "be done this way, answer with `ask` instead of doing it.",
            "high", id = "push-$n-1", review = PUSH_REVIEW,
        )

        /** The work flows' push of the Smith's bundle (`OpenPrDelegate`). */
        const val OPEN_PR = "task-open-pr"

        /**
         * Rounds a retry of a conflict-cap incident grants: `ConflictRounds.MAX_CONFLICT_ROUNDS` (flow-delivery, which
         * this module does not depend on; `ChoreProcessTest` holds the two equal).
         */
        const val GRANTED_CONFLICT_ROUNDS = 2

        /** The activities whose job fails when a flow's conflict rounds are used up (`ConflictRounds.recordConflict`). */
        val CONFLICT_ACTIVITIES = setOf("task-rebase-pr", "task-ci-conflict")

        /** Whether the incident is `ConflictRounds`'s cap, consecutive or lifetime: a retry grants more rounds. */
        fun conflictCap(activityId: String?, message: String?): Boolean =
            activityId in CONFLICT_ACTIVITIES && message != null && message.startsWith("merge conflict with") && "conflict rounds" in message

        /** Whether the incident is the work result refused past [Vars.CI_NOT_MINE_MAX] rounds declining the same check's failure. */
        fun ciNotMineCap(message: String?): Boolean = message?.contains(CI_NOT_MINE_CAP) == true

        fun unappliable(message: String?): Boolean =
            message?.startsWith("${GitPush.UNAPPLIABLE}the bundle cannot be applied") == true
    }

    /**
     * A failed job is given one more try, except in a Smith run that already ended failed,
     * or whose exit-0 result was rejected by `ReadResultDelegate.enforce` (its incident
     * message starts with "the smith's result breaks the rules"): its remaining steps
     * would only fail again on the stored result, so the call activity that started it is
     * started over and a new Smith runs (a new account pick included).
     * 404 for an incident that is not open, 400 for one that is not a failed job, 403 when
     * `by` is not a trusted login. Comments on the flow's issue (a doctor run's: its ticket).
     */
    fun retry(id: String, by: String): Retried = retry(id, Actor.Login(by))

    /**
     * [retry] as the engine itself, `actor` naming the part of it that retries (the doctor: engine/README.md, "Doctor"):
     * no repo-access check, since no user asked. The comment on the issue names the engine's `actor`, not a login.
     */
    fun retryAsSystem(id: String, actor: String): Retried = retry(id, Actor.System(actor))

    /** [retry] for any [Actor]: a Smith (frakworks-engine-diagnostics) is held to [ActorAccess]'s rule, a login to [Access][com.codefrak.engine.repos.Access], the engine to none.
     *
     * With [rerunCi] (act_on_incident's `rerun_ci`, #2249), only for a [ciNotMineCap] incident: the declining-round streak
     * is set back to 0 and the stored result read again, so `open/update pr` re-runs the check's failed job on the same
     * head and the flow waits for it. Each such retry grants at most [Vars.CI_NOT_MINE_MAX] more automatic re-runs, so a
     * check that truly fails comes back to a person. Any other incident is refused (400) and nothing is touched.
     */
    fun retry(id: String, actor: Actor, rerunCi: Boolean = false): Retried {
        val cause = cause(id)
        if (cause.incidentType != Incident.FAILED_JOB_HANDLER_TYPE)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "incident $id is a ${cause.incidentType}, not a failed job")
        if (rerunCi && !ciNotMineCap(cause.incidentMessage))
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "rerun_ci retries only an incident where the work declined the engine " +
                "check's failure ${Vars.CI_NOT_MINE_MAX} rounds in a row on an unchanged head; incident $id is not one")

        val root = rootOf(cause.processInstanceId)
        // A doctor run's incident is its ticket's to retry: the ticket's repo, and the kind of its flow (ActorAccess.ticketOf).
        val ticket = actors.ticketOf(root)
        val repo = ticket.repo
        val issue = ticket.issue
        if (repo == null || !actors.allowed(actor, repo, ticket.kind, listOf(root.id)))
            throw ResponseStatusException(HttpStatus.FORBIDDEN, actors.refusal(actor, "retry", ticket.described, repo))

        val definitionKey = cause.processDefinitionId.substringBefore(':')
        val result = runtime.getVariable(cause.processInstanceId, Vars.RESULT) as? String
        val caller = history.createHistoricProcessInstanceQuery().processInstanceId(cause.processInstanceId)
            .singleResult()?.superProcessInstanceId
        // A result the engine rejected (ReadResultDelegate.enforce) leaves RESULT == "success" even though the
        // Smith's output failed the rules, so a plain job retry would just re-validate the same stored, still-
        // rejected result forever; treat that incident message like a failed Smith run too.
        // An unappliable bundle is one too: its prerequisite is on no branch and the sandbox's commits are gone, so
        // only a new run helps; its stored bundle is dropped so the rerun does not get it.
        // A pushed bundle without the branch fails in the flow itself, not in a smith-run: the same bundle can
        // only fail again, so the work call activity is started over, its stored bundle dropped, with a `push-<n>-1`
        // finding that says why, so the rerun builds on the branch instead of rewriting it again (#2165).
        val work = pushRerun(definitionKey, cause.activityId, cause.incidentMessage)
        val action = if (rerunCi) {
            // Not a Smith rerun: the stored decline is read again with a fresh streak, and `open/update pr` re-runs the check.
            setOf(cause.processInstanceId, root.id).forEach { runtime.setVariable(it, Vars.CI_NOT_MINE_ROUNDS, 0) }
            management.setJobRetries(cause.configuration, retriesOf(cause)); "ci"
        } else if (work != null) {
            listOf(Vars.BUNDLE, Vars.PREVIOUS_BUNDLE).forEach { runtime.setVariable(cause.processInstanceId, it, null) }
            leavePushFinding(cause.processInstanceId, issue, cause.incidentMessage!!)
            runtime.createProcessInstanceModification(cause.processInstanceId)
                .startBeforeActivity(work).cancelAllForActivity(OPEN_PR).execute()
            "smith"
        } else if (caller != null && reruns(definitionKey, result, cause.incidentMessage)) {
            if (unappliable(cause.incidentMessage)) listOf(Vars.BUNDLE, Vars.PREVIOUS_BUNDLE).forEach { runtime.setVariable(caller, it, null) }
            rerunSmith(caller, cause.processInstanceId); "smith"
        } else if (conflictCap(cause.activityId, cause.incidentMessage)) {
            // The job would meet the same conflict with the same count and throw again: grant fresh rounds first, on the
            // instance the job reads (reusable-ci-check's own copy for task-ci-conflict) and on the root flow.
            setOf(cause.processInstanceId, root.id).forEach {
                runtime.setVariable(it, Vars.CONFLICT_ROUNDS, 0)
                runtime.setVariable(it, Vars.CONFLICT_ROUNDS_GRANTED, GRANTED_CONFLICT_ROUNDS)
            }
            management.setJobRetries(cause.configuration, retriesOf(cause)); "job"
        } else {
            management.setJobRetries(cause.configuration, retriesOf(cause)); "job"
        }
        val what = if (action == "smith" && unappliable(cause.incidentMessage))
            "started its Smith run again on the PR branch (${cause.incidentMessage!!.substringAfter(GitPush.UNAPPLIABLE)})"
        else if (action == "smith") "started its Smith run again"
        else if (action == "ci") "re-ran the failed engine check on the same head (up to ${Vars.CI_NOT_MINE_MAX} more re-runs) and retried `${cause.activityId}`"
        else if (conflictCap(cause.activityId, cause.incidentMessage)) "granted $GRANTED_CONFLICT_ROUNDS more conflict rounds and retried `${cause.activityId}`"
        else "retried `${cause.activityId}`"
        val on = if (ticket.doctor) "the doctor run (`${root.id}`) on this ${ticket.kind}" else "this ${ticket.kind} (`${root.id}`)"
        issue?.let { n ->
            val item = tickets.forTicket(repo, n) ?: throw WorkItemRefused("no ticket $repo#$n")
            tickets.comment(item.id, "engine", "${actor.label} $what on $on.\n\n— engine")
        }
        return Retried(id, root.id, action, cause.activityId)
    }

    /**
     * The tries a retried job gets: the count of its activity's `failedJobRetryTimeCycle` (3 for `R3/PT30S`), so a
     * transient error on the retried attempt is tried again on the cycle as on the first run; 1 when it has none.
     * The job keeps its stored exception, so Operaton takes the cycle's intervals from here and does not reset the count.
     */
    fun retriesOf(cause: Incident): Int {
        val activity = cause.activityId ?: return 1
        val element = repository.getBpmnModelInstance(cause.processDefinitionId)
            .getModelElementById<org.operaton.bpm.model.bpmn.instance.BaseElement>(activity) ?: return 1
        val cycle = element.extensionElements?.domElement?.childElements
            ?.firstOrNull { it.localName == "failedJobRetryTimeCycle" }?.textContent?.trim() ?: return 1
        return Regex("^R(\\d+)/").find(cycle)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 } ?: 1
    }

    /** The open incident `id` names, followed to its root cause; 404 when it is not open. */
    fun cause(id: String): Incident {
        val named = runtime.createIncidentQuery().incidentId(id).singleResult()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no open incident $id")
        return named.rootCauseIncidentId?.takeIf { it != named.id }
            ?.let { runtime.createIncidentQuery().incidentId(it).singleResult() } ?: named
    }

    /** The flow a process instance runs under: itself, or the outermost process that called it. 404 when it is not running. */
    fun rootOf(processInstanceId: String): ProcessInstance {
        var p = runtime.createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "$processInstanceId is not running")
        while (true) {
            val parent = runtime.createProcessInstanceQuery().subProcessInstanceId(p.id).singleResult() ?: return p
            p = parent
        }
    }

    /** Counts the rejection in [Vars.PUSH_REJECTIONS] and leaves [pushFinding] in the flow's `findings` and `raised`. */
    private fun leavePushFinding(flow: String, issue: Long?, message: String) {
        val n = ((runtime.getVariable(flow, Vars.PUSH_REJECTIONS) as? Number)?.toInt() ?: 0) + 1
        val finding = pushFinding(n, "smith/issue-${issue ?: "<n>"}", message)
        val findings = Brief.findings(runtime.getVariable(flow, Vars.FINDINGS) as? ByteArray).filter { it.id != finding.id } + finding
        val raised = Brief.findings(runtime.getVariable(flow, Vars.RAISED) as? ByteArray).filter { it.id != finding.id } + finding
        runtime.setVariable(flow, Vars.PUSH_REJECTIONS, n)
        runtime.setVariable(flow, Vars.FINDINGS, Variables.byteArrayValue(Brief.findingsBytes(findings)))
        runtime.setVariable(flow, Vars.RAISED, Variables.byteArrayValue(Brief.findingsBytes(raised)))
    }

    /** Starts the caller's call activity over; cancelling the old one ends the failed run and its incidents. */
    private fun rerunSmith(caller: String, failedRun: String) {
        val call = history.createHistoricActivityInstanceQuery().processInstanceId(caller).activityType("callActivity")
            .unfinished().list().firstOrNull { it.calledProcessInstanceId == failedRun }
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "no call activity in $caller runs $failedRun")
        // Start before cancelling, so the caller never has no activity left and ends.
        runtime.createProcessInstanceModification(caller)
            .startBeforeActivity(call.activityId)
            .cancelActivityInstance(call.id)
            .execute()
    }
}

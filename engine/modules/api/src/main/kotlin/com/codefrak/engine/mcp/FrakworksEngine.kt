package com.codefrak.engine.mcp

import com.codefrak.engine.board.Need
import com.codefrak.engine.repos.AccessView
import com.codefrak.engine.repos.EngineAccessController
import com.codefrak.engine.camunda.TicketRun
import com.codefrak.engine.camunda.TicketTimelineController
import com.codefrak.engine.smith.SmithRunStat
import com.codefrak.engine.smith.SmithRunStats
import com.codefrak.engine.board.Pitches
import com.codefrak.engine.github.BundleChanges
import com.codefrak.engine.github.GitPush
import com.codefrak.engine.github.IssueDetails
import com.codefrak.engine.github.TicketPulls
import com.codefrak.engine.intake.Amended
import com.codefrak.engine.intake.AskRequest
import com.codefrak.engine.intake.IntakeStarted
import com.codefrak.engine.intake.FlowCanceller
import com.codefrak.engine.intake.IntakeAmender
import com.codefrak.engine.intake.TicketAmended
import com.codefrak.engine.intake.TicketAmender
import com.codefrak.engine.intake.IntakeController
import com.codefrak.engine.intake.IntakeQuestion
import com.codefrak.engine.proposal.ProposeResult
import com.codefrak.engine.repos.AddRepo
import com.codefrak.engine.repos.DeferredIssues
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.EngineToken
import com.codefrak.engine.repos.EngineTokens
import com.codefrak.engine.repos.MintedToken
import com.codefrak.engine.repos.TargetRepo
import com.codefrak.engine.repos.TargetRepoController
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.trigger.IssueApprovals
import jakarta.servlet.http.HttpServletRequest

/**
 * A ticket's latest `proposal` flow, for read_ticket_pr on a proposal with no PR: its instance, whether it still
 * runs, the steps it waits at, its stored propose result (null until the first draft is stored) and the
 * `engine.intake_question` rows still open on it.
 */
data class ProposalFlow(
    val instance: String, val running: Boolean, val steps: List<String>, val result: ProposeResult?, val questions: List<IntakeQuestion>,
)

/**
 * The engine calls [FrakworksMcp]'s tools make: the same ones the HTTP API
 * serves (POST /intake, DELETE /intake/{id}, GET /needs), and amend_intake's [IntakeAmender], called
 * in-process with the MCP request, so each checks the caller's address as it
 * does over HTTP, read_ticket_pr's GitHub reads ([IssueDetails], repo read
 * only), approve's in-process authorization ([IssueApprovals.approveIntake]) and reauthorize's
 * ([IssueApprovals.reauthorize]), and the repo tools' GET, POST, PATCH and DELETE /repos ([TargetRepoController]), so
 * each refuses what the HTTP API refuses, with its text, and publishes the same `repo` event. close_ticket's close
 * is the work item's ([com.codefrak.engine.workitems.Tickets.end]), with the running flows
 * [FlowCanceller.running] finds and the deferred start ([DeferredIssues]).
 * An interface so the tools are tested without Spring.
 */
interface FrakworksEngine {
    /** The new intake's id. */
    fun submit(ask: AskRequest, request: HttpServletRequest?): IntakeStarted
    fun needs(): List<Need>
    /** DELETE /intake/{id} as `by` ([IntakeController.cancel]): the GitHub writes that failed; its 400, 403 and 404 thrown with the API's text. */
    fun cancelIntake(intakeId: String, by: String, reason: String, request: HttpServletRequest?): List<String>
    /** Adds `text` to an intake still refining as `by` ([IntakeAmender.amend]); its refusals thrown with their text. */
    fun amendIntake(intakeId: String, text: String, by: String): Amended
    /** Adds `text` to filed ticket `repo`#`ticket` as `by` and loops its flow back ([TicketAmender.amend]); its refusals thrown with their text. */
    fun amendTicket(repo: String?, ticket: Long, text: String, by: String): TicketAmended
    /** Null when the engine has no transcript store (`engine.telemetry.logs-url` empty). */
    fun transcript(ticket: Long): Transcript?
    /** One named run's session ([TranscriptReader.read] with a [NamedRun]); null with no transcript store. */
    fun transcript(ticket: Long, run: NamedRun): Transcript?
    /** The Smith run `runId` (a `smith-run` process instance) as read_transcript needs it, with the ticket and repo it ran on; null when there is none. */
    fun smithRun(runId: String): SmithRunRef?
    /** `repo`#`ticket`'s Smith runs, oldest first ([TicketTimelineController.runs] joined with [SmithRunStats.forIssue]); null when no process instance is tied to it. */
    fun ticketRuns(repo: String, ticket: Long): List<TicketRunRow>?
    /**
     * The stuck tickets (repo, issue) a doctor-filed intake names, when the Smith run [runId] refines one: its root
     * flow is that intake, and the doctor bug the intake files has a sighting on each ticket. Empty for any other run.
     */
    fun doctorStuckTickets(runId: String): List<Pair<String, Long>>
    /** The root flow the Smith run [runId] belongs to (the ticket's or intake's flow), from history; null when the engine has no such run. */
    fun runRoot(runId: String): String? = null
    /**
     * The split the Smith run [runId] parents ([ParentTools]): its run's mode is `parent`, and its root flow (the
     * feature's instance) keeps a `split` variable ([SplitState][com.codefrak.engine.feature.SplitState]). Null for any other run.
     */
    fun parentSplit(runId: String): ParentSplit? = null
    /**
     * steer_child: delivers [message] to child [child] of [split] as the replan's steer does: appended to the child flow's
     * `steer` variable ([FlowStarter.steer][com.codefrak.engine.chore.FlowStarter.steer]), then `Steer from parent #<n>: …`
     * commented on the child. False when the child's flow no longer runs (nothing is posted).
     */
    fun steerChild(split: ParentSplit, child: Long, message: String): Boolean = false
    /**
     * steer_child on a work-item split's child [item] (engine/docs/work-items.md, "Live parent on work items"): appended to
     * the child flow's `steer` as [steerChild] does, then a `conversation` entry `steer from parent <item>: …` on the child
     * item instead of a GitHub comment. False when no flow of the child's runs (an entry is still written).
     */
    fun steerItem(split: ParentSplit, item: String, message: String): Boolean = false
    /**
     * file_children (#100042): applies the live parent's replan to [split]'s feature instance (`outcome` `continue`,
     * `revise` with [children], or `done`; [drop] names drafts to drop first) and files every ready draft as `file the
     * next child` does ([ChildFiler.fileChildren][com.codefrak.engine.feature.ChildFiler.fileChildren]); the split is
     * written on the feature and on the parent's run. Throws IllegalArgumentException with the rules broken.
     */
    fun fileChildren(split: ParentSplit, outcome: String, children: List<com.codefrak.engine.feature.ChildDraft>, drop: List<String>, summary: String?): com.codefrak.engine.feature.ChildrenFiled =
        throw IllegalStateException("this engine files no children")
    /**
     * answer_child (#100049): [answer] lands on child [child]'s question waiting on [split]'s parent, as
     * [ParentQuestions.answer][com.codefrak.engine.feature.ParentQuestions.answer] does; the question answered, or null
     * when none of the child's waits on the parent.
     */
    fun answerChild(split: ParentSplit, child: Long, answer: String): String? = null
    /**
     * answer_child with `escalate` (#100049): child [child]'s question waiting on [split]'s parent is handed to the user
     * with [note], the HITLP request filed as today's path files it; the question escalated, or null when none waits.
     */
    fun escalateChild(split: ParentSplit, child: Long, note: String?): String? = null
    /** Work item [item]'s Smith runs, oldest first, found by its flow; null when no flow has run on it. */
    fun itemRuns(repo: String, item: String): List<TicketRunRow>? = null
    /** The newest Smith session of work item [item]'s, or its run [run]'s; null when none ran or there is no transcript store. */
    fun itemTranscript(repo: String, item: String, run: String?): Transcript? = null
    /** `repo`'s name as its target repo row spells it ([TargetRepos.find], ignoring case); null when it is no target repo. */
    fun targetRepo(repo: String): String?
    /** The PRs that close the ticket, with their files ([IssueDetails.ticketPulls]); null when the repo has no such issue. */
    fun ticketPulls(repo: String, ticket: Long): TicketPulls?
    /** A file's text at `ref` ([IssueDetails.fileText]); null when it is not there. */
    fun fileText(repo: String, path: String, ref: String): String?
    /** `repo`#`ticket`'s latest `proposal` flow, from history as `GET /ticket/.../pitch` finds it ([Pitches]); null when it had none. */
    fun proposalFlow(repo: String, ticket: Long): ProposalFlow?
    /**
     * What that flow's unpushed `bundle` changes against `default`, with `path`'s text at its head as `after`
     * ([GitPush.proposalChanges], nothing pushed); null when the flow keeps no bundle.
     */
    fun draftChanges(repo: String, ticket: Long, default: String, path: String): BundleChanges?
    /** A PR's unified diff ([IssueDetails.pullDiff]); null when the repo has no such PR. */
    fun pullDiff(repo: String, number: Long): String?
    /** Authorizes the intake on ask item `repo`#`ticket` as `by` ([IssueApprovals.approveIntake]); null when no intake on it waits for that. */
    fun approve(repo: String, ticket: Long, by: String): IssueApprovals.Approved?
    /** Authorizes filed ticket `repo`#`ticket` (a research, chore, bug, feature or proposal) as `by` ([IssueApprovals.approveTicket]). */
    fun approveTicket(repo: String, ticket: Long, by: String): IssueApprovals.TicketApproved
    /** Re-authorizes `repo`#`ticket`, waiting on a blocker closed without a merge, as `by` ([IssueApprovals.reauthorize]). */
    fun reauthorize(repo: String, ticket: Long, by: String): IssueApprovals.Reauthorized
    /** Whether `login` is a trusted login ([Access.trusted]): its actions count on every target repo. */
    fun trusted(login: String): Boolean
    /** The instance ids of the root flows running on `repo`#`ticket` ([FlowCanceller.running]). */
    fun runningFlows(repo: String, ticket: Long): List<String>
    /** Whether a start of `repo`#`ticket` is deferred behind a blocker ([DeferredIssues.get]). */
    fun deferred(repo: String, ticket: Long): Boolean
    /** Removes that deferred start ([DeferredIssues.remove]). */
    fun dropDeferred(repo: String, ticket: Long)
    /**
     * Closes work item `repo`#`ticket` with `reason`, `completed` (done in the store) or `not_planned` (cancelled), always
     * named ([com.codefrak.engine.workitems.Tickets.end]).
     */
    fun close(repo: String, ticket: Long, reason: String)
    /** Every target repo as GET /repos lists it ([TargetRepoController.list]), with `login`'s permission when given. */
    fun repos(login: String? = null): List<TargetRepo>
    /** POST /repos ([TargetRepoController.add]): true when the repo was added, false when it was already there. */
    fun addRepo(body: AddRepo, request: HttpServletRequest?): Boolean
    /** PATCH /repos/{owner}/{name} ([TargetRepoController.edit]) with `fields` as its body: the row after. */
    fun editRepo(repo: String, fields: Map<String, Any?>, request: HttpServletRequest?): TargetRepo
    /** DELETE /repos/{owner}/{name} ([TargetRepoController.remove]); its 404 and 409 thrown with the API's text. */
    fun removeRepo(repo: String)
    /** GET /access ([EngineAccessController.list]): the trusted logins and every change to them. */
    fun access(): AccessView
    /** POST /access ([Access.add]): true when `login` was added, false when it was already there. */
    fun addAccess(login: String, by: String): Boolean
    /** DELETE /access/{login} ([Access.remove]): true when `login` was removed, false when it was not on the list. */
    fun removeAccess(login: String, by: String): Boolean
    /** GET /tokens ([EngineTokens.list]): every token row, newest first, never a hash. */
    fun tokens(): List<EngineToken>
    /** POST /tokens ([EngineTokens.mint]) for a trusted `login`, recorded as created by `by`: the row and, once, the token. */
    fun mintToken(login: String, name: String, expiresInDays: Int?, by: String): MintedToken
    /** DELETE /tokens/{id} ([EngineTokens.revoke]): true revoked now, false already revoked, null no such token. */
    fun revokeToken(id: String): Boolean?
    /** Whether any unrevoked token exists ([EngineTokens.anyActive]): once one does, the token tools need a token. */
    fun anyActiveToken(): Boolean
}

/** A Smith run by id: the ticket it ran on, what [NamedRun] needs, and its sandbox output tail (`logTail`) once collected. */
data class SmithRunRef(val runId: String, val repo: String?, val issue: Long?, val run: NamedRun, val logTail: String? = null)

/** A parent run's split: the run, its repo and parent ticket, the feature instance, and each filed child's number with its kind. */
data class ParentSplit(val runId: String, val repo: String, val parent: Long, val instance: String, val children: Map<Long, String>,
                       /** When the parent run's Job is cut (its start plus `engine.smith.k8s.active-deadline`, #2596); null when unknown. */
                       val deadline: java.time.Instant? = null,
                       /** A work-item split's children: each child item id with its kind (`SplitState.filed[].item`). */
                       val items: Map<String, String> = emptyMap(),
                       /** The parent's own work item on the work-item path; null on the GitHub path. */
                       val parentItem: String? = null)

/** One read_ticket_runs row: the timeline's step for the run, and its `engine.smith_run_stats` row once collected. */
data class TicketRunRow(val run: TicketRun, val stat: SmithRunStat?)

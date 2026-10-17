package com.codefrak.engine.mcp

import com.codefrak.engine.firstmate.FirstMateSessionPods
import com.codefrak.engine.firstmate.FirstMateSessions
import com.codefrak.engine.repos.AccessView
import com.codefrak.engine.camunda.TicketTimelineController
import com.codefrak.engine.camunda.ownScopeVariablesOf
import com.codefrak.engine.camunda.variablesOf
import com.codefrak.engine.smith.SmithRunStats
import com.codefrak.engine.board.Needs
import com.codefrak.engine.board.Pitches
import com.codefrak.engine.github.BundleChanges
import com.codefrak.engine.github.GitPush
import com.codefrak.engine.github.IssueDetails
import com.codefrak.engine.intake.Amended
import com.codefrak.engine.intake.AskRequest
import com.codefrak.engine.intake.FlowCanceller
import com.codefrak.engine.intake.CancelRequest
import com.codefrak.engine.intake.IntakeAmender
import com.codefrak.engine.intake.TicketAmended
import com.codefrak.engine.intake.TicketAmender
import com.codefrak.engine.intake.IntakeController
import com.codefrak.engine.intake.IntakeQuestions
import com.codefrak.engine.repos.HandledIssues
import com.codefrak.engine.repos.AddRepo
import com.codefrak.engine.repos.DeferredIssues
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.EngineTokens
import com.codefrak.engine.repos.TargetRepo
import com.codefrak.engine.repos.TargetRepoController
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.chore.FlowStarter
import com.codefrak.engine.feature.SplitState
import com.codefrak.engine.trigger.IssueApprovals
import com.codefrak.engine.workitems.commentOn
import jakarta.servlet.http.HttpServletRequest
import org.operaton.bpm.engine.HistoryService
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/** A feature flow's parent work item variable (flow-feature's `WORK_ITEM`). */
private const val WORK_ITEM = "workItem"

@Component
class EngineCalls(
    private val intake: IntakeController, private val needs: Needs, private val transcripts: TranscriptReader,
    private val details: IssueDetails, private val repos: TargetRepos, private val access: Access, private val tokens: EngineTokens, private val approvals: IssueApprovals,
    private val repoApi: TargetRepoController, private val timeline: TicketTimelineController,
    private val stats: SmithRunStats, private val handled: HandledIssues, private val history: HistoryService,
    private val questions: IntakeQuestions, private val git: GitPush, private val amender: IntakeAmender,
    private val ticketAmender: TicketAmender,
    private val canceller: FlowCanceller, private val deferredIssues: DeferredIssues,
    private val tickets: com.codefrak.engine.workitems.Tickets,
    private val jdbc: JdbcClient, private val firstMateSessions: FirstMateSessions,
    private val firstMatePods: FirstMateSessionPods, private val starter: FlowStarter,
    /** A Job's `activeDeadlineSeconds`: a parent run's deadline is its start plus this (#2596). */
    @org.springframework.beans.factory.annotation.Value("\${engine.smith.k8s.active-deadline:PT24H}")
    private val activeDeadline: java.time.Duration = java.time.Duration.ofHours(24),
    private val workItems: com.codefrak.engine.feature.WorkItemSplitting? = null,
    /** The flow's child filing (flow-feature's `ChildFiling`), which file_children shares with `file the next child` (#100042). */
    private val childFiler: com.codefrak.engine.feature.ChildFiler? = null,
    /** A child's question routed to its live parent (flow-feature's `ParentQuestions`), which answer_child answers or escalates (#100049). */
    private val parentQuestions: com.codefrak.engine.feature.ParentQuestions? = null,
) : FrakworksEngine {
    override fun submit(ask: AskRequest, request: HttpServletRequest?) = intake.submit(ask, request)
    override fun needs() = needs.list()
    override fun cancelIntake(intakeId: String, by: String, reason: String, request: HttpServletRequest?) =
        intake.cancel(intakeId, CancelRequest(reason = reason), request).failedWrites
    override fun amendIntake(intakeId: String, text: String, by: String) = amender.amend(intakeId, text, by)
    override fun amendTicket(repo: String?, ticket: Long, text: String, by: String) = ticketAmender.amend(repo, ticket, text, by)
    override fun transcript(ticket: Long) = if (transcripts.configured) transcripts.read(ticket) else null
    override fun transcript(ticket: Long, run: NamedRun) = if (transcripts.configured) transcripts.read(ticket, run) else null
    override fun smithRun(runId: String): SmithRunRef? {
        val p = history.createHistoricProcessInstanceQuery().processInstanceId(runId).singleResult() ?: return null
        if (p.processDefinitionKey != TicketTimelineController.SMITH_RUN) return null
        val vars = history.ownScopeVariablesOf(runId)
        val stat = stats.get(runId)
        return SmithRunRef(runId, vars[Vars.REPO] as? String, (vars[Vars.ISSUE] as? Number)?.toLong(),
            NamedRun(runId, vars[Vars.MODE] as? String ?: stat?.mode ?: "unknown",
                stat?.started?.toInstant() ?: p.startTime.toInstant(), p.endTime == null),
            when (val t = vars[Vars.LOG_TAIL]) { is String -> t; is ByteArray -> String(t); else -> null })
    }
    override fun runRoot(runId: String): String? =
        history.createHistoricProcessInstanceQuery().processInstanceId(runId).singleResult()?.rootProcessInstanceId

    override fun parentSplit(runId: String): ParentSplit? {
        val run = smithRun(runId)?.takeIf { it.run.phase == ParentTools.PARENT_MODE } ?: return null
        val repo = run.repo ?: return null; val parent = run.issue ?: return null
        val root = runRoot(runId) ?: return null
        val state = SplitState.of(history.ownScopeVariablesOf(root)[Vars.SPLIT] as? ByteArray) ?: return null
        val (items, numbered) = state.filed.partition { it.item != null }
        return ParentSplit(runId, repo, parent, root, numbered.associate { it.number to it.kind }, run.run.started.plus(activeDeadline),
            items.associate { it.item!! to it.kind }, history.ownScopeVariablesOf(root)[WORK_ITEM] as? String)
    }
    override fun steerItem(split: ParentSplit, item: String, message: String): Boolean {
        // The child's flow, when one was started on it, takes the steer as a numbered child's does; the entry is the comment's place.
        val instance = SplitState.of(history.ownScopeVariablesOf(split.instance)[Vars.SPLIT] as? ByteArray)
            ?.filed?.firstOrNull { it.item == item }?.instance
        val by = split.parentItem ?: "#${split.parent}"
        val steered = instance != null && starter.steer(instance, by, message)
        (workItems ?: error("no work-item store in this engine")).converse(item, "steer from parent $by: ${message.trim()}")
        return steered
    }
    override fun fileChildren(split: ParentSplit, outcome: String, children: List<com.codefrak.engine.feature.ChildDraft>, drop: List<String>, summary: String?) =
        (childFiler ?: error("no child filing in this engine")).fileChildren(split.instance, split.runId, outcome, children, drop, summary)
    override fun steerChild(split: ParentSplit, child: Long, message: String): Boolean {
        // As FeatureDelegates' replan steer: the child's latest flow, its steer variable, then the comment.
        val kind = split.children[child] ?: return false
        val instance = handled.startedBefore(split.repo, child, kind).lastOrNull { it != null } ?: return false
        if (!starter.steer(instance, "#${split.parent}", message)) return false
        tickets.commentOn(split.repo, child, "Steer from parent #${split.parent}: ${message.trim()}\n\n— engine feature `${split.instance}`")
        return true
    }

    override fun answerChild(split: ParentSplit, child: Long, answer: String): String? =
        (parentQuestions ?: error("this engine routes no child question")).answer(split.repo, split.parent, child, answer)?.question
    override fun escalateChild(split: ParentSplit, child: Long, note: String?): String? =
        (parentQuestions ?: error("this engine routes no child question")).escalate(split.repo, split.parent, child, note)?.question

    override fun doctorStuckTickets(runId: String): List<Pair<String, Long>> {
        val root = history.createHistoricProcessInstanceQuery().processInstanceId(runId).singleResult()?.rootProcessInstanceId
            ?: return emptyList()
        return jdbc.sql("""SELECT DISTINCT s.repo, s.issue FROM engine.doctor_sighting s
            JOIN engine.doctor_bug b ON b.id = s.bug_id WHERE b.intake_id = :intake""")
            .param("intake", root).query { rs, _ -> rs.getString("repo") to rs.getLong("issue") }.list()
    }
    override fun ticketRuns(repo: String, ticket: Long): List<TicketRunRow>? {
        val runs = timeline.runs(repo, ticket) ?: return null
        val byId = stats.forIssue(repo, ticket).associateBy { it.runId }
        return runs.map { TicketRunRow(it, byId[it.runId]) }
    }
    override fun targetRepo(repo: String) = repos.find(repo)?.fullName
    override fun ticketPulls(repo: String, ticket: Long) = details.ticketPulls(repo, ticket)
    override fun fileText(repo: String, path: String, ref: String) = details.fileText(repo, path, ref)
    override fun proposalFlow(repo: String, ticket: Long): ProposalFlow? {
        val id = proposalInstance(repo, ticket) ?: return null
        val running = history.createHistoricProcessInstanceQuery().processInstanceId(id).unfinished().count() > 0
        val steps = if (!running) emptyList() else history.createHistoricActivityInstanceQuery().processInstanceId(id).unfinished().list()
            .filter { it.activityType !in SCOPES }.map { it.activityName ?: it.activityId }
        return ProposalFlow(id, running, steps, Pitches.proposal(history.variablesOf(id)), questions.list(id).filter { it.answeredAt == null })
    }
    override fun draftChanges(repo: String, ticket: Long, default: String, path: String): BundleChanges? {
        val id = proposalInstance(repo, ticket) ?: return null
        val variables = history.variablesOf(id)
        val bundle = variables[Vars.BUNDLE] as? ByteArray ?: return null
        val branch = (variables[Vars.BRANCH] as? String)?.takeIf { it.isNotBlank() } ?: "smith/issue-$ticket"
        return git.proposalChanges(repo, branch, default, bundle, path, null)
    }
    private fun proposalInstance(repo: String, ticket: Long) =
        handled.list(repo).lastOrNull { it.issue == ticket && it.flow == Pitches.FLOW && it.processInstance != null }?.processInstance
    override fun pullDiff(repo: String, number: Long) = details.pullDiff(repo, number)
    override fun approve(repo: String, ticket: Long, by: String) = approvals.approveIntake(repo, ticket, by)
    override fun approveTicket(repo: String, ticket: Long, by: String) = approvals.approveTicket(repo, ticket, by)
    override fun reauthorize(repo: String, ticket: Long, by: String) = approvals.reauthorize(repo, ticket, by)
    override fun trusted(login: String) = access.trusted(login)
    override fun runningFlows(repo: String, ticket: Long) = canceller.running(repo, ticket).map { it.id }
    override fun deferred(repo: String, ticket: Long) = deferredIssues.get(repo, ticket) != null
    override fun dropDeferred(repo: String, ticket: Long) = deferredIssues.remove(repo, ticket)
    override fun close(repo: String, ticket: Long, reason: String) {
        tickets.end(repo, ticket, com.codefrak.engine.workitems.TicketState.ofReason(reason), "closed as $reason")
    }
    override fun repos(login: String?) = repoApi.listFor(login)
    override fun addRepo(body: AddRepo, request: HttpServletRequest?) = repoApi.add(body, request).statusCode.value() == HttpStatus.CREATED.value()
    override fun editRepo(repo: String, fields: Map<String, Any?>, request: HttpServletRequest?): TargetRepo {
        val (owner, name) = repo.split("/", limit = 2)
        return repoApi.edit(owner, name, fields, request)
    }
    override fun access() = AccessView(access.list(), access.history())
    override fun addAccess(login: String, by: String) = access.add(login, by)
    override fun removeAccess(login: String, by: String) = access.remove(login, by)
    override fun tokens() = tokens.list()
    override fun mintToken(login: String, name: String, expiresInDays: Int?, by: String) = tokens.mint(login, name, expiresInDays, by)
    /** Revoking a token ends the first-mate sessions started with it, as `DELETE /tokens/{id}` does. */
    override fun revokeToken(id: String) = tokens.revoke(id).also {
        if (it == true) {
            val open = firstMateSessions.openForToken(id)
            firstMateSessions.endForToken(id)
            open.forEach(firstMatePods::stop)
        }
    }
    override fun anyActiveToken() = tokens.anyActive()
    override fun removeRepo(repo: String) {
        val (owner, name) = repo.split("/", limit = 2)
        val answer = repoApi.remove(owner, name)
        when (answer.statusCode.value()) {
            HttpStatus.NOT_FOUND.value() -> throw ResponseStatusException(HttpStatus.NOT_FOUND, "$repo is not a target repo")
            // The 409's body names each running flow, as DELETE /repos answers it.
            HttpStatus.CONFLICT.value() -> throw ResponseStatusException(HttpStatus.CONFLICT, ((answer.body as? Map<*, *>)?.get("error") as? String) ?: "$repo has running flows")
            else -> {}
        }
    }

    private companion object {
        /** Activity types that only hold the step a flow waits at, not a step themselves. */
        val SCOPES = setOf("subProcess", "processDefinition")
    }
}

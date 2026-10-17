package com.codefrak.engine.board

import com.codefrak.engine.at
import com.codefrak.engine.camunda.CurrentStep
import com.codefrak.engine.camunda.OpenIncidents
import com.codefrak.engine.camunda.variablesOf
import com.codefrak.engine.chore.ProcessFlowStarter
import com.codefrak.engine.feature.SplitState
import com.codefrak.engine.github.Blocker
import com.codefrak.engine.github.IssueListing
import com.codefrak.engine.github.issueUrl
import com.codefrak.engine.intake.IntakeQuestion
import com.codefrak.engine.intake.IntakeQuestions
import com.codefrak.engine.intake.IntakeRefs
import com.codefrak.engine.intake.IntakeTickets
import com.codefrak.engine.intake.IntakeView
import com.codefrak.engine.repos.Deferred
import com.codefrak.engine.repos.DeferredIssues
import com.codefrak.engine.repos.Handled
import com.codefrak.engine.repos.HandledIssues
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.trigger.IssueScanner
import com.codefrak.engine.workitems.Tickets
import com.codefrak.engine.workitems.blockers
import com.codefrak.engine.workitems.openListing
import com.codefrak.engine.workitems.openListings
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.history.HistoricProcessInstance
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** `failures`: repos GitHub could not be read for; their issues are missing, the rest are there. */
data class Board(val issues: List<BoardIssue>, val failures: List<String>)

/**
 * The issues board (engine/README.md, "The issues board"): every open issue of
 * every target repo, read live from GitHub, with what the engine knows about
 * each joined on: the approval it acted on (`engine.handled_issue`), that
 * flow's place in Operaton's history, and a question waiting on the user.
 * Read-only; nothing here is stored.
 */
@Component
class IssueBoard(
    private val repos: TargetRepos,
    private val tickets: Tickets,
    private val handled: HandledIssues,
    private val deferred: DeferredIssues,
    private val questions: IntakeQuestions,
    private val history: HistoryService,
    private val runtime: RuntimeService,
    private val incidents: OpenIncidents,
    private val estimates: StageEstimates,
    private val intakes: IntakeRefs,
    private val intakeLinks: IntakeTickets,
    private val currentStep: CurrentStep,
) : BoardRows, OpenTickets {
    private val log = LoggerFactory.getLogger(IssueBoard::class.java)

    companion object {
        /** The one flow that splits, so the only one whose approval can have a `split` variable (`Splits.kt`). */
        private const val FEATURE_FLOW = "feature"
    }

    /** Every target repo's open issues, or only those of `only`, a target repo's full name matched ignoring case. */
    fun board(only: String? = null): Board {
        val failures = mutableListOf<String>()
        val pending = questions.open().associateBy { it.repo to it.issue }
        // Read once per board, and only when some flow is running.
        val estimate = lazy { estimates.snapshot() }
        val targets = repos.list().map { it.fullName }.filter { only == null || it.equals(only, ignoreCase = true) }
        val issues = targets.flatMap { repo ->
            val listed = runCatching { tickets.openListings(repo) }
                .getOrElse { failures += "$repo: ${it.message}"; log.warn("board {}: {}", repo, it.message); emptyList() }
            val blockers = if (listed.isEmpty()) emptyMap() else runCatching { listed.associate { it.number to tickets.blockers(repo, it.number) } }
                .getOrElse { failures += "$repo blocked by: ${it.message}"; log.warn("board {} blocked by: {}", repo, it.message); emptyMap() }
            val state = repoState(repo, listed, blockers, pending, estimate)
            listed.map { issue -> row(issue, state) }
        }.sortedByDescending { it.updatedAt }
        return Board(issues, failures)
    }

    /**
     * One issue's row, as [board] would list it (engine/README.md, "Events": the row a `flow` frame carries): from
     * one read of the issue and one of its blockers (a blockers read that fails leaves `blockedBy` empty, as the
     * board's does). Null for an issue GitHub does not list: unknown, a pull request, or closed. Throws when the
     * issue cannot be read.
     */
    override fun row(repo: String, number: Long): BoardIssue? {
        val issue = tickets.openListing(repo, number) ?: return null
        val blockers = runCatching { mapOf(number to tickets.blockers(repo, number)) }
            .getOrElse { log.warn("row {}#{} blocked by: {}", repo, number, it.message); emptyMap() }
        val pending = questions.open().filter { it.repo == repo && it.issue == number }.associateBy { it.repo to it.issue }
        return row(issue, repoState(repo, listOf(issue), blockers, pending, lazy { estimates.snapshot() }, everySplit = true))
    }

    /** What a repo's rows are joined on, read once per repo (or once per row for [row]). */
    private class RepoState(
        val repo: String,
        /** The latest approval per issue is the one that says what the engine did with it. */
        val latest: Map<Long, Handled>,
        /** Each issue's story points, from its latest row that has them ([HandledIssues.points]'s rule). */
        val points: Map<Long, Int>,
        /** A waiting approval is always the latest: a fresh one replaces it, or withdraws it on the next scan. */
        val waiting: Map<Long, Deferred>,
        val blockers: Map<Long, List<Blocker>>,
        /** A split parent's children, from its flow's split state, by the parent's number; each filed child's row gets the parent back. */
        val splits: Map<Long, SplitState>,
        val parents: Map<Long, ParentView>,
        val filedBy: Map<Long, IntakeView>,
        val pending: Map<Pair<String, Long>, IntakeQuestion>,
        val estimate: Lazy<StageEstimates.Snapshot>,
    )

    /**
     * `everySplit`: read the split of every latest `feature` approval too, not only the listed issues' ([row] lists
     * one issue, and its parent is another). Only a feature flow carries a `split` variable (`Splits.kt`), so the
     * read stays one history query per feature the repo has had, not one per approval; a `flow` frame is built
     * every second for an active issue, and this is its widest read. The board reads the listed ones', as it always has.
     */
    private fun repoState(repo: String, listed: List<IssueListing>, blockers: Map<Long, List<Blocker>>,
                          pending: Map<Pair<String, Long>, IntakeQuestion>, estimate: Lazy<StageEstimates.Snapshot>,
                          everySplit: Boolean = false): RepoState {
        val rows = handled.list(repo).groupBy { it.issue }
        val latest = rows.mapValues { (_, r) -> r.last() }
        val points = rows.mapNotNull { (issue, r) -> r.lastOrNull { it.points != null }?.points?.let { issue to it } }.toMap()
        val waiting = deferred.list(repo).associateBy { it.issue }
        val listedNumbers = listed.map { it.number }.toSet()
        val splits = latest.values.filter { it.issue in listedNumbers || (everySplit && it.flow == FEATURE_FLOW) }
            .mapNotNull { row -> history.splitOf(row.processInstance)?.let { row.issue to it } }.toMap()
        // Which intake filed each ticket: one history read per repo, not one per issue.
        val filedBy = if (listed.isEmpty()) emptyMap() else intakes.filedBy(repo)
        val parents = splits.flatMap { (parent, split) ->
            val url = listed.firstOrNull { it.number == parent }?.url ?: issueUrl(repo, parent)
            split.filed.map { it.number to ParentView(parent, url) }
        }.toMap()
        return RepoState(repo, latest, points, waiting, blockers, splits, parents, filedBy, pending, estimate)
    }

    /** The per-issue builder [board] and [row] share. */
    private fun row(issue: IssueListing, state: RepoState): BoardIssue {
        val repo = state.repo
        val wait = state.waiting[issue.number]
        val kinds = issue.labels.filter { it in ProcessFlowStarter.FLOWS }
        val flow = state.latest[issue.number]?.let { flowView(it, state.estimate) }
        return BoardIssue(
            repo, issue.number, issue.title, issue.author, issue.labels, issue.url, issue.createdAt, issue.updatedAt,
            kind = kinds.singleOrNull(), authorized = IssueScanner.AUTHORIZED in issue.labels,
            flow = wait?.let { w ->
                val open = w.blockers.filter { it.stillOpen() }.minByOrNull { it.number }
                val waitingOn = open?.let { "waiting on ${it.ref(repo)}" } ?: "waiting on its blockers"
                // A split parent waits inside its running flow; an approval waits before any flow starts.
                if (w.waitingInstance != null && flow != null) flow.copy(step = waitingOn, detail = waitingOn)
                else FlowView(w.flow, "deferred", waitingOn, null, null, null, false, null, null, null)
            } ?: flow ?: intakeFlowView(issue.url, state.estimate),
            pendingQuestion = state.pending[repo to issue.number],
            blockedBy = state.blockers[issue.number].orEmpty().map { BlockedBy(it.number, it.state) },
            deferred = wait != null,
            children = state.splits[issue.number]?.let { children(repo, it) }.orEmpty(),
            parent = state.parents[issue.number],
            intake = state.filedBy[issue.number],
            askedBy = state.latest[issue.number]?.askedBy,
            points = state.points[issue.number],
        )
    }

    /**
     * One repo's open issues as GitHub lists them, newest update first, bodies included and nothing joined on:
     * the board's own read, for the intake's duplicate check (engine/README.md, "Intake"). Any repo, target or not;
     * throws when GitHub cannot be read.
     */
    override fun openTickets(repo: String): List<IssueListing> = tickets.openListings(repo).sortedByDescending { it.updatedAt }

    private fun children(repo: String, split: SplitState): List<ChildView> =
        split.filed.map { ChildView(it.index, it.title, it.kind, it.state, it.number, it.url, it.prNumber, handled.points(repo, it.number)) } +
            split.drafts.mapIndexed { i, d -> ChildView(split.filed.size + i, d.title, d.kind, "pending") } +
            split.dropped.mapIndexed { i, t -> ChildView(split.total + i, t, "", "dropped") }

    private fun flowView(row: Handled, estimate: Lazy<StageEstimates.Snapshot>): FlowView {
        val instance = row.processInstance?.let { history.createHistoricProcessInstanceQuery().processInstanceId(it).singleResult() }
        if (instance == null) return FlowView(row.flow, row.outcome, row.detail, row.processInstance, null, null, false, null, null, null)
        val ended = instance.endTime != null
        val variables = history.variablesOf(instance.id)
        return withStage(FlowView(row.flow, row.outcome, row.detail, instance.id, instance.startTime.at(), instance.endTime?.at(), ended,
            step = if (ended) null else currentStep.of(instance.id),
            prUrl = variables[Vars.PR_URL] as? String, ticketUrl = variables["ticketUrl"] as? String,
            stage = if (ended) null else currentStage(instance.id),
            incident = if (ended) null else incidents.of(instance.id),
            pitch = Pitches.pitchOf(row.flow, variables)), instance, variables, estimate)
    }

    /**
     * An issue with no `handled_issue` row can still have a flow: an intake started directly by
     * `POST /intake` never writes one. Find it by its `engine.intake_ticket` row, which
     * `OpenAskItemDelegate` writes with its ask item's url; the newest intake with that url wins.
     */
    private fun intakeFlowView(issueUrl: String, estimate: Lazy<StageEstimates.Snapshot>): FlowView? {
        val link = intakeLinks.byIssueUrl(issueUrl) ?: return null
        val instance = history.createHistoricProcessInstanceQuery().processInstanceId(link.intake).singleResult() ?: return null
        val ended = instance.endTime != null
        val variables = history.variablesOf(instance.id)
        return withStage(FlowView("intake", null, null, instance.id, instance.startTime.at(), instance.endTime?.at(), ended,
            step = if (ended) null else currentStep.of(instance.id),
            prUrl = variables[Vars.PR_URL] as? String, ticketUrl = variables["ticketUrl"] as? String,
            stage = if (ended) null else currentStage(instance.id),
            incident = if (ended) null else incidents.of(instance.id)), instance, variables, estimate)
    }

    /**
     * Adds `stageStartedAt`, `stageRound` and `stageEstimateMs` to a running flow's view, from one
     * historic-activity query on its root instance. An unfinished row is the stage under way; with none, the
     * stage still waits on its `asyncBefore` job and began when the latest finished step ended. A rework round
     * is estimated from this ticket's own first round of the stage when it finished, and any other from the
     * ticket's tier and the size of its Allowed scope.
     */
    private fun withStage(view: FlowView, instance: HistoricProcessInstance, variables: Map<String, Any?>,
                          estimate: Lazy<StageEstimates.Snapshot>): FlowView {
        val stage = view.stage ?: return view
        val activities = history.createHistoricActivityInstanceQuery().processInstanceId(instance.id).list()
        val rounds = activities.filter { it.activityId == stage }.sortedBy { it.startTime }
        val current = rounds.lastOrNull { it.endTime == null }
        val startedAt = current?.startTime
            ?: activities.mapNotNull { it.endTime }.maxOrNull()
            ?: instance.startTime
        val round = if (current != null) rounds.size else rounds.size + 1
        val first = rounds.firstOrNull()?.takeIf { round > 1 && it.endTime != null }?.durationInMillis
        val est = estimate.value.estimate(stage, instance.processDefinitionKey, estimates.tierOf(variables, instance.id), first,
            StageEstimates.scopeOf(variables))
        return view.copy(stageStartedAt = startedAt.at(), stageRound = round,
            stageEstimateMs = est?.ms, stageEstimateBasis = est?.basis, stageEstimateRuns = est?.runs)
    }

    /**
     * The id of the activity the outermost process waits at: a call activity while a Smith run
     * (or any called process) is under way, else the task or event itself. From the runtime's
     * activity tree like [CurrentStep.of], so an `asyncBefore` job counts as its activity.
     */
    private fun currentStage(root: String): String? {
        val tree = runtime.getActivityInstance(root) ?: return null
        return (currentStep.leaves(tree).filter { it.id != tree.id }.map { it.activityId } + currentStep.transitions(tree).map { it.activityId }).firstOrNull()
    }
}

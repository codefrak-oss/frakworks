package com.codefrak.engine.camunda

import com.codefrak.engine.github.issueUrl
import com.codefrak.engine.errorResponse
import com.codefrak.engine.intake.IntakeTickets
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.HandledIssues
import com.codefrak.engine.smith.Finding
import com.codefrak.engine.smith.SmithRunStat
import com.codefrak.engine.smith.SmithRunStats
import com.codefrak.engine.smith.SmithSessions
import org.operaton.bpm.engine.impl.util.ClockUtil
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * Unauthenticated, read-only: one ticket's timeline, the contract the desktop Timeline widget reads (TicketTimeline.kt;
 * engine/README.md, "Ticket timeline"), served from the projection in the engine's schema ([TimelineProjection],
 * [SmithRunStats]) with no history query: its only Operaton read is the in-memory [SmithSessions.live] of a running
 * run. No GitHub call: it is polled every 15 s while a flow runs. A ticket with no `engine.timeline_backfill` row is
 * filled from history first ([TimelineBackfill]).
 */
@RestController
class TicketTimelineController(
    private val projection: TimelineProjection, private val stats: SmithRunStats, private val handled: HandledIssues,
    private val intakeTickets: IntakeTickets, private val intakes: IntakeRecords, private val access: Access,
    private val sessions: SmithSessions, private val backfill: TimelineBackfill,
) {
    companion object {
        const val SMITH_RUN = "smith-run"
        const val INTAKE = "intake"
        /** The activity a chore, bug or feature pushes a round with. */
        const val PUSH = "task-open-pr"
        /** The work Smith's call: a round with none before its push was opened by `bring pr up to date`'s merge. */
        const val WORK = "call-do-work"
        /** Modes whose result carries findings. */
        val REVIEW_MODES = setOf("review", "security")
        /** `reusable-ci-check`'s receive task, `wait for the engine check`: its span is a `ci` wait. */
        const val CI_WAIT = "receive-ci-check"
        /** A Smith mode to the role the widget draws; any other mode keeps its own name. */
        val ROLES = mapOf("work" to "coding", "security" to "security", "review" to "correctness")
        /** Modes whose run comes before its round's push; every other run, security included since 2026-09-26, belongs to the push before it. */
        private val BEFORE_PUSH = setOf("work")
        /** Modes a merge-from-main push re-runs; only these are marked `catchUp`. */
        private val AFTER_MERGE = setOf("security", "review")
    }

    /** A Smith run as a step, and the question its result.json asked, for the user task after it. */
    private data class Run(val placed: PlacedStep, val question: String?, val run: TicketRun)

    /** One root flow with everything the projection holds under it. */
    private class Tree(val root: FlowRow, val instances: List<FlowRow>, val activities: List<ActivityRow>, val incidents: List<IncidentRow>,
                       val ciWaits: List<CiWaitRow>, val runs: Map<String, SmithRunStat>)

    /**
     * The ticket's Smith runs, oldest first, read as the timeline reads its steps (read_ticket_runs, #1162);
     * null when no process instance was ever tied to the issue.
     */
    fun runs(repo: String, n: Long): List<TicketRun>? {
        val roots = roots(repo, n)
        if (roots.isEmpty()) return null
        val now = ClockUtil.getCurrentTime().time
        return roots.map(::load).flatMap { runsOf(it, now) }.sortedBy { it.placed.span.start }.map { it.run }
    }

    /** The ticket's root flows, newest first, after its one-time fill from history when it has none recorded. */
    private fun roots(repo: String, n: Long): List<FlowRow> {
        if (!backfill.recorded(repo, n)) backfill.ensure(repo, n, "request")
        val fromRows = handled.list(repo).filter { it.issue == n }.mapNotNull { it.processInstance }
        return projection.roots(repo, n, fromRows, intakeTickets.byIssueUrl(issueUrl(repo, n))?.intake)
    }

    private fun load(root: FlowRow) = Tree(root, projection.tree(root.id), projection.activities(root.id), projection.incidents(root.id),
        projection.ciWaits(root.id), stats.forRoot(root.id).associateBy { it.runId })

    private fun runsOf(t: Tree, now: Long): List<Run> {
        val own = t.activities.filter { it.instance == t.root.id }
        val pushes = own.filter { it.activityId == PUSH && it.ended != null }.map { it.ended!! }.sorted()
        val works = own.filter { it.activityId == WORK }.map { it.started }
        return t.instances.filter { it.kind == SMITH_RUN }.map { run -> step(run, t, pushes, works, now) }
    }

    /** 404 with a JSON `error` when no process instance was ever tied to the issue. */
    @GetMapping("/ticket/{owner}/{repo}/{n}/timeline", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun timeline(@PathVariable owner: String, @PathVariable repo: String, @PathVariable n: Long): ResponseEntity<Any> {
        val full = "$owner/$repo"
        val roots = roots(full, n)
        if (roots.isEmpty())
            return errorResponse(HttpStatus.NOT_FOUND, "no process instances for $full#$n")
        return ResponseEntity.ok(build(n, roots, ClockUtil.getCurrentTime().time))
    }

    private fun build(n: Long, roots: List<FlowRow>, now: Long): Timeline {
        val steps = mutableListOf<PlacedStep>()
        val marks = mutableListOf<Mark>()
        val retrier = access.list().joinToString(", ") { it.login }.ifEmpty { null }
        for (root in roots) {
            val t = load(root)
            val runs = runsOf(t, now)
            steps += runs.map { it.placed }
            for (p in t.instances.filter { it.kind != SMITH_RUN }) marks += marks(p, t, runs, now)
            for (p in t.instances) marks += incidents(p.id, t, retrier, now)
        }
        val start = roots.minOf { it.started }
        val end = if (roots.any { it.ended == null }) now else roots.maxOf { it.ended!! }
        val main = roots.firstOrNull { it.kind != INTAKE } ?: roots.first()
        val flows = (listOf(main) + roots).mapNotNull { projection.ticketFlow(it.id) }
        val title = flows.firstNotNullOfOrNull { it.title }
        val ticket = TimelineTicket(n, title, if (roots.any { it.ended == null }) "OPEN" else "CLOSED",
            steps.maxOfOrNull { it.step.round } ?: 1, main.kind, flows.firstOrNull { it.root == main.id }?.tier, blocked(roots))
        return TicketTimeline.build(ticket, steps, Span(start, maxOf(start, end)), marks)
    }

    /**
     * The latest intake's refine result (the `output` `call-refine-ask` copies onto the intake, parsed into its
     * `engine.intake_ticket` row by the projection) as the `insufficient-permissions` banner; null for any other outcome.
     * An intake that filed its ticket unlabeled (`task-file-unlabeled`) and closed still has it.
     */
    private fun blocked(roots: List<FlowRow>): TimelineBlocked? {
        val result = roots.filter { it.kind == INTAKE }.sortedByDescending { it.started }
            .firstNotNullOfOrNull { intakeTickets.refineResult(it.id) } ?: return null
        return TicketTimeline.blocked(result.outcome, result.blocker, result.reason)
    }

    /**
     * A run is working from its start to its end, or to its first incident: a run stuck on a
     * failed job is not working, and the incident's wait follows it (one whose job was
     * retried in place and runs again is running). Its row in `engine.smith_run_stats` exists from
     * `start smith` on (model, effort) and is finished by `collect result` (outcome, cost, turns). Its span reaches out to its
     * `reusable-smith-run` wrapper's own start and end (`TicketTimeline.runSpan`), so the wrapper's own activities
     * (`start-smith-run`, `task-read-result`, `end-smith-run`) don't show up as separate waits.
     * A retry re-runs `call-smith-run` in place (`IncidentRetrier.rerunSmith`), so a wrapper
     * can hold several runs: only the first reaches back to `start-smith-run`, and only the
     * last (the one the wrapper actually finished through) reaches forward to `end-smith-run`.
     * A security or review run is `catchUp` when its round (2 or later) began with a merge from
     * the default branch: no work run started between the round's previous push and this run.
     * A running run whose `collect result` has not run has its live turns and tokens
     * ([SmithSessions.live]) and no cost: Claude Code has no running dollar figure.
     */
    private fun step(run: FlowRow, t: Tree, pushes: List<Long>, works: List<Long>, now: Long): Run {
        val stat = t.runs[run.id]
        val mode = stat?.mode ?: "unknown"
        val incidents = rootIncidents(run.id, t)
        val stuckAt = incidents.minOfOrNull { it.created }
        val wrapper = t.instances.firstOrNull { it.id == run.superInstance }
        val calls = wrapper?.let { w -> t.instances.filter { it.superInstance == w.id && it.kind == SMITH_RUN }.sortedBy { it.started } }
        val wrapperStart = wrapper?.started?.takeIf { calls?.firstOrNull()?.id == run.id }
        val wrapperEnd = wrapper?.ended?.takeIf { calls?.lastOrNull()?.id == run.id }
        val (start, end) = TicketTimeline.runSpan(run.started, run.ended, stuckAt, incidents.any { it.ended == null }, wrapperStart, wrapperEnd)
        val pushed = pushes.count { it <= start }
        val round = if (mode in BEFORE_PUSH) pushed + 1 else maxOf(1, pushed)
        val catchUp = mode in AFTER_MERGE && round >= 2 && pushes.getOrNull(round - 2)?.let { prev -> works.none { it in prev..start } } == true
        val collected = stat?.ended != null
        val outcome = stat?.outcome ?: if (end == null) "in progress" else null
        val live = if (end == null && !collected) runCatching { sessions.live(run.id) }.getOrNull() else null
        val turns = if (collected) stat?.turns else live?.turns
        val tokens = live?.tokens?.let { TimelineTokens(it.input, it.output, it.cacheRead, it.cacheWrite) }
        val step = TimelineStep(ROLES[mode] ?: mode, round, TicketTimeline.seconds(start), end?.let(TicketTimeline::seconds),
            (end ?: now) - start, stat?.model, stat?.effort, if (collected) stat?.cost else null, turns, outcome, end == null, catchUp, tokens, stat?.account)
        val findings: List<Finding>? = if (mode in REVIEW_MODES) stats.findings(run.id) else null
        return Run(PlacedStep(step, Span(start, maxOf(start, end ?: now))), stat?.question, TicketRun(run.id, mode, round, step, findings))
    }

    /**
     * What a flow's own activities show: a user task is the user's question, the catch
     * waiting for `authorized` the user's authorization, `receive-ci-check` the engine check,
     * and every activity but a call (whose called process is read on its own) names what the
     * engine did.
     */
    private fun marks(p: FlowRow, t: Tree, runs: List<Run>, now: Long): List<Mark> {
        val acts = t.activities.filter { it.instance == p.id && it.activityType != "callActivity" }
        val tasks = acts.filter { it.activityType == "userTask" }.sortedBy { it.started }
        val rows = if (tasks.isEmpty()) emptyList() else intakes.questions(p.id)
        val waits = t.ciWaits.associateBy { it.id }
        return acts.flatMap { a ->
            val span = Span(a.started, maxOf(a.started, a.ended ?: now))
            val activity = Mark(Mark.Kind.ACTIVITY, span, a.activityId)
            when {
                a.activityType == "userTask" -> {
                    // Asked and answered in `engine.intake_question`, one row per wait: by order when every
                    // wait has one, else the last one asked before this wait; a flow that records none
                    // (a chore's question) has the question in the result.json of the run before it.
                    val nth = tasks.indexOf(a)
                    val row = if (rows.size == tasks.size) rows[nth]
                        else rows.lastOrNull { it.askedAt.toInstant().toEpochMilli() <= span.start + 1000 }
                    val detail = if (row != null) mapOf("question" to row.question, "answer" to row.answer)
                        else mapOf("question" to askedBefore(runs, span.start), "answer" to null)
                    listOf(activity, Mark(Mark.Kind.QUESTION, span, a.activityId, detail))
                }
                a.activityType == "intermediateMessageCatch" && a.activityId.endsWith("-authorized") ->
                    listOf(activity, Mark(Mark.Kind.AUTHORIZATION, span, a.activityId))
                a.activityId == CI_WAIT -> listOf(activity, Mark(Mark.Kind.CI, span, a.activityId,
                    mapOf("check" to waits[a.id]?.check, "conclusion" to waits[a.id]?.let(::ciConclusion))))
                else -> listOf(activity)
            }
        }
    }

    /**
     * How one `receive-ci-check` wait ended: null while it waits; `rerun` when `read check` re-ran the check after it
     * (its `ciOutcome`), else the check run's `ciConclusion` (null when the flow recorded none). No GitHub read.
     */
    private fun ciConclusion(w: CiWaitRow): String? = when {
        w.ended == null -> null
        w.outcome == "rerun" -> "rerun"
        else -> w.conclusion
    }

    private fun askedBefore(runs: List<Run>, at: Long): String? =
        AskedBefore.askedBefore(runs.map { AskedRun(it.placed.span.end, it.placed.step.running, it.question) }, at)

    /** An incident's root cause only: one propagated up a call activity is the same stall. */
    private fun incidents(processInstance: String, t: Tree, retrier: String?, now: Long): List<Mark> =
        rootIncidents(processInstance, t).map { i ->
            Mark(Mark.Kind.STUCK, Span(i.created, maxOf(i.created, i.ended ?: now)), i.activityId ?: "incident",
                mapOf("incident" to listOfNotNull(i.activityId, i.message).joinToString(": "), "retrier" to retrier))
        }

    private fun rootIncidents(processInstance: String, t: Tree) =
        t.incidents.filter { it.instance == processInstance && RootCauseIncidents.isRootCause(it.rootCause, it.id) }
}

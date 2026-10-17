package com.codefrak.engine.feature

import com.codefrak.engine.chore.FlowStarter
import com.codefrak.engine.github.IssueSnapshot
import com.codefrak.engine.github.TokenIdentity
import com.codefrak.engine.intake.AUTHORIZED_BY
import com.codefrak.engine.intake.TicketFiler
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.HandledIssues
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.Ticket
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.issue
import com.codefrak.engine.smith.repo
import com.codefrak.engine.smith.tier
import com.codefrak.engine.workitems.Tickets
import com.codefrak.engine.workitems.blockers
import com.codefrak.engine.workitems.commentOn
import com.codefrak.engine.workitems.item
import com.codefrak.engine.workitems.listings
import com.codefrak.engine.workitems.snapshot
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.variable.Variables
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Filing a split feature's children (engine/docs/feature.md, "Split features", "Live parent"), shared by
 * `file the next child` ([FileChildDelegate]) and the live parent's `file_children` ([ChildFiler], called by engine-api's
 * `EngineCalls`): [file] files one draft through the intake's [TicketFiler] at the parent's tier with the
 * `Part N of M of #<parent>.` line, blocks the parent by it, records it on the split state and starts its flow with the
 * parent's authorizer; [fileReady] files every draft whose dependencies are done, each recorded before the next;
 * [reconcile] records the children the engine's own close has closed, as `reconcile the children` does. The place the
 * split state is read from and written to is the [Target]'s: the delegate's execution, or the feature instance (and the
 * parent's run) through [RuntimeService] when the parent Smith files mid-run.
 */
@Component
class ChildFiling(
    private val filer: TicketFiler,
    private val tickets: Tickets,
    private val repos: TargetRepos,
    private val access: Access,
    private val handled: HandledIssues,
    private val starter: FlowStarter,
    private val identity: TokenIdentity,
    private val runtime: RuntimeService,
) : ChildFiler {
    private val log = LoggerFactory.getLogger(ChildFiling::class.java)

    /**
     * The split feature a child is filed for: its feature [instance], the parent's repo and number, tier, authorizer
     * ([by]) and asker, its mode, the approved ticket, and [save], which writes the split state where this target keeps it.
     */
    class Target(
        val instance: String, val repo: String, val parent: Long, val tier: Int?, val by: String?, val askedBy: String?,
        val featureMode: String?, val ticket: Ticket, val dryRun: Boolean, val save: (SplitState) -> Unit,
    ) {
        val detail get() = "authorized by @$by via parent #$parent"
    }

    /** The delegate's target: `file the next child`'s execution, the split written as its variable. */
    fun target(execution: DelegateExecution) = Target(
        execution.processInstanceId, execution.repo(), execution.issue(), execution.tier(), execution.getVariable(AUTHORIZED_BY) as? String,
        execution.getVariable(Vars.ASKED_BY) as? String, execution.getVariable(Vars.FEATURE_MODE) as? String,
        Brief.ticket(execution.getVariable(Vars.TICKET) as ByteArray), execution.getVariable(Vars.DRY_RUN) == true,
    ) { execution.setVariable(Vars.SPLIT, Variables.byteArrayValue(it.bytes())) }

    /**
     * The live parent's target: feature [instance], read through [RuntimeService] while its `call-parent` waits on the
     * parent run [runId]. The split is written on both: the run was started with a copy of every variable
     * (`camunda:in variables="all"`) that returns to the feature when it ends (`out variables="all"`), so a copy left
     * stale on the run would overwrite the feature's when the run ends.
     */
    fun target(instance: String, runId: String): Target {
        check(runtime.createProcessInstanceQuery().processInstanceId(instance).count() > 0) { "feature $instance is not running" }
        val vars = runtime.getVariables(instance)
        val repo = vars[Vars.REPO] as? String ?: error("feature $instance has no repo")
        val parent = (vars[Vars.ISSUE] as? Number)?.toLong() ?: error("feature $instance has no issue")
        val ticket = (vars[Vars.TICKET] as? ByteArray)?.let { Brief.ticket(it) } ?: error("feature $instance has no approved ticket")
        return Target(instance, repo, parent, (vars[Vars.TIER] as? Number)?.toInt(), vars[AUTHORIZED_BY] as? String,
            vars[Vars.ASKED_BY] as? String, vars[Vars.FEATURE_MODE] as? String, ticket, vars[Vars.DRY_RUN] == true) { state ->
            val value = Variables.byteArrayValue(state.bytes())
            runtime.setVariable(instance, Vars.SPLIT, value)
            if (runtime.createProcessInstanceQuery().processInstanceId(runId).count() > 0) runtime.setVariable(runId, Vars.SPLIT, value)
        }
    }

    /** The split state feature [instance] keeps now, or null when it has none. */
    fun split(instance: String): SplitState? = SplitState.of(runtime.getVariable(instance, Vars.SPLIT) as? ByteArray)

    override fun fileChildren(instance: String, runId: String, outcome: String, children: List<ChildDraft>, drop: List<String>, summary: String?): ChildrenFiled {
        val target = target(instance, runId)
        var state = split(instance) ?: error("feature $instance has no split state")
        // The children the engine closed since the last look: a draft waiting on one is ready only once that is recorded.
        state = reconcile(target.repo, target.parent, state)
        val problems = buildList {
            if (outcome !in ReplanResult.OUTCOMES) add("outcome '$outcome' is not one of ${ReplanResult.OUTCOMES}")
            if (drop.isNotEmpty() && outcome != "continue") add("'drop' goes with outcome 'continue': 'revise' replaces every draft left, 'done' drops them all")
            drop.filter { t -> state.drafts.none { it.title == t } }.forEach { add("drop: \"$it\" is not a draft left to file (${state.drafts.joinToString { "\"${it.title}\"" }.ifEmpty { "none" }})") }
            val kept = state.drafts.filter { it.title !in drop }
            addAll(replanRules(outcome, children, emptyList(), state.filed, kept))
        }
        require(problems.isEmpty()) { problems.joinToString("; ") }
        if (drop.isNotEmpty()) state = state.copy(drafts = state.drafts.filter { it.title !in drop }, dropped = state.dropped + drop)
        state = when (outcome) {
            "revise" -> state.revised(children)
            "done" -> state.done()
            else -> state
        }
        target.save(state)
        val before = state.filed.size
        val (after, stopped) = fileReady(target, state)
        val filed = after.filed.drop(before)
        val what = when (outcome) {
            "revise" -> "the drafts left were revised: ${after.drafts.size} left"
            "done" -> "nothing more to file"
            else -> "${after.drafts.size} draft(s) left"
        } + after.dropped.takeIf { it.isNotEmpty() }?.let { " (dropped: ${it.joinToString()})" }.orEmpty()
        tickets.commentOn(target.repo, target.parent, "The live parent replanned: $outcome, $what; filed ${filed.joinToString { "#${it.number}" }.ifEmpty { "nothing" }}" +
            (if (after.open().isNotEmpty()) "; ${after.open().joinToString { "#${it.number}" }} open." else ".") +
            summary?.takeIf { it.isNotBlank() }?.let { "\n\n${it.trim()}" }.orEmpty() + "\n\n— engine feature `${target.instance}`")
        return ChildrenFiled(after, filed, stopped)
    }

    /**
     * Files every ready draft of [state] in one pass, each recorded (saved) before the next. A failure after the first
     * keeps what was filed, comments on the parent, and leaves the drafts not filed ready for the next pass; the message
     * is the second of the pair. A failure on the first throws.
     */
    fun fileReady(target: Target, state: SplitState): Pair<SplitState, String?> {
        var current = state
        for ((n, draft) in state.ready().withIndex()) {
            current = try { file(target, current, draft) } catch (e: Exception) {
                if (n == 0) throw e
                log.warn("feature {}: filing {} failed after {} filed: {}", target.instance, draft.title, n, e.message)
                val why = "Filing stopped at \"${draft.title}\": ${e.message}. The drafts not filed yet are filed at the next pass."
                tickets.commentOn(target.repo, target.parent, "$why\n\n— engine feature `${target.instance}`")
                return current to why
            }
        }
        return current to null
    }

    /**
     * Records on [state] each open child GitHub shows closed by the engine's own close (the parent's blocked-by list, the
     * child's flow ended with a merge or a nothing-to-do close), with its PR, or by its own split close, with the drafts
     * that take its place (engine/docs/feature.md, "A child that splits"). Nothing is saved here.
     */
    fun reconcile(repo: String, parent: Long, state: SplitState): SplitState {
        var current = state
        val closes = tickets.blockers(repo, parent).filter { it.repo.equals(repo, ignoreCase = true) }.associateBy { it.number }
        for (child in state.open()) {
            val blocker = closes[child.number] ?: continue
            if (blocker.stillOpen() || !starter.mergedClose(child.instance)) continue
            // A child its work split closed itself (the live parent parks nothing to hand it to): its drafts take its place.
            val drafts = ChildDraft.parse(starter.splitDrafts(child.instance))
            current = current.closed(child.number, if (drafts != null) null else blocker.closedByPr ?: starter.mergedPr(child.instance), drafts)
        }
        return current
    }

    /** Files [draft], blocks the parent by it, records it on the split state (saved on [target]) and starts its flow when it may. */
    fun file(target: Target, before: SplitState, draft: ChildDraft): SplitState {
        val repo = target.repo; val parent = target.parent; val id = target.instance
        val part = before.filed.size + 1; val total = before.total
        val tier = target.tier
        val by = target.by; val detail = target.detail; val ticket = target.ticket
        // A feature child takes its parent's mode, auto or confirm; the splitter's own per-child mode is ignored (#2306).
        val mode = if (draft.kind == "feature") target.featureMode ?: FeatureMode.CONFIRM else null
        // A split of a proposal's build: each child builds the same doc, so its design and work briefs carry it too.
        val builds = Brief.builds(ticket.body)?.takeIf { Brief.builds(draft.body) == null }?.let { "Builds: $it\n\n" }.orEmpty()
        // The child is asked for by whoever asked for the parent (engine/README.md, "Intake", `by`).
        val askedBy = target.askedBy
        val body = filer.body(builds + draft.body.trimEnd() + "\n\nPart $part of $total of #$parent.", "split of #$parent", tier, "p2", by = askedBy, points = draft.points)
        // A retry after a failure that followed the create (the job's transaction rolled the split state back, #2535)
        // finds the child already filed and reuses it rather than filing it again. Only an issue the engine's own
        // token wrote is reused: anyone can open one with the same title and markers.
        // A reused child may already be closed (merged while the parent was failing; #2542): it is found among the
        // closed issues the parent is blocked by, and starts nothing.
        val engine = identity.login(repo)
        val reused = engine?.let { filedChild(tickets.listings(repo), it, parent, part, total, draft.title) }
        val closed = if (reused != null || engine == null) null else filedClosedChild(repo, tickets.blockers(repo, parent),
            { tickets.snapshot(repo, it) }, engine, parent, part, total, draft.title)
        val created = (reused ?: closed)
            ?.also { log.info("feature {}: reusing child #{} already filed for {}", id, it.number, draft.title) }
            ?: filer.file(repo, draft.title, body, draft.kind, mode)
        tickets.block(tickets.item(repo, parent).id, tickets.item(repo, created.number).id)
        var state = before.filed(draft, created.number, created.url, draft.kind)
        target.save(state)
        tickets.commentOn(repo, parent, "Filed child #${created.number} (part $part of $total); waiting on it.\n\n— engine feature `$id`")

        val snapshot = IssueSnapshot(created.number, draft.title, body, by ?: "engine")
        // A reused child's flow may still run (started by the attempt the retry rolled back, #2542): the parent parks on
        // it as on one it had started, and starts no second flow on the same branch.
        val runningFlow = if (reused == null) null else runningFlow(runtime, repo, created.number, id)
        if (closed != null) {
            // Closed already: the parent's row is blocked by a closed issue, which the scan hands to the child-closed path.
            log.info("feature {}: reused child #{} is already closed; no flow started", id, created.number)
        } else if (runningFlow != null) {
            state = state.started(created.number, runningFlow)
            target.save(state)
            log.info("feature {}: reused child #{} already runs `{}`; no second flow started", id, created.number, runningFlow)
        } else if (by == null || !repos.contains(repo) || !access.trusted(by)) {
            tickets.commentOn(repo, created.number, "Filed as a ${draft.kind}, part $part of $total of #$parent. It starts when a trusted login " +
                "starts it (`POST /work-items/{id}/start` or First mate's `approve`)${by?.let { " (@$it, who authorized the parent, is not a trusted login)" }.orEmpty()}.\n\n— engine")
        } else {
            // Label event 0: no label approved it. A later `authorized` label on it is refused as already started.
            check(handled.claim(repo, created.number, 0, draft.kind, "started", detail, askedBy ?: snapshot.author, draft.points)) { "$repo#${created.number} was already started" }
            try {
                val extra = buildMap<String, Any> {
                    if (target.dryRun) put(Vars.DRY_RUN, true)
                    mode?.let { put(Vars.FEATURE_MODE, it) }
                }
                val instance = starter.start(draft.kind, repo, snapshot, by, tier = tier, extra = extra) ?: error("the ${draft.kind} did not start")
                handled.started(repo, created.number, 0, instance)
                state = state.started(created.number, instance)
                target.save(state)
                tickets.commentOn(repo, created.number, "${draft.kind.replaceFirstChar { it.uppercase() }} started${mode?.let { " in $it mode" }.orEmpty()}: " +
                    "$detail (`$instance`).\n\n— engine")
            } catch (e: Exception) {
                handled.release(repo, created.number, 0)
                throw e
            }
        }
        log.info("feature {}: filed child {} (part {} of {}) for {}#{}", id, created.url, part, total, repo, parent)
        return state
    }
}

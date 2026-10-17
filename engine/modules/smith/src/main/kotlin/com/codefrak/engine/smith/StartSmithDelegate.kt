package com.codefrak.engine.smith

import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.github.TokenIdentity
import com.codefrak.engine.intake.AnswerAttachments
import com.codefrak.engine.repos.RepoDocs
import com.codefrak.engine.repos.DEFAULT_CHILD_MODE
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.tier.TierTable
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * "start smith": resolve the run's tier, pick the account, create and start
 * the sandbox, remember its handle; the process then waits for the sandbox's
 * exit message (`SmithExitNotifier`). A caller that set no `account` gets
 * the picker's choice for the model's provider
 * (engine/README.md, "AI accounts"). With no usable account of that provider
 * a real run fails here, as an incident; a dry run starts anyway, since it
 * needs no credential. For a `claude-fable-*` model an account at its Fable
 * limit is not usable, picked or pinned (engine/README.md, "The usage meter").
 *
 * The tier is mode-dependent (engine/README.md, "Smith runs"): a `security`
 * run always uses `TierTable.securityTier()`, a `check` run (the post-deployment
 * check) `TierTable.checkTier()`, a `refine` run (the intake
 * refiner's own Smith) always uses the refiner tier stored in the database
 * (`IntakeLookup.refinerTier`, read as the run starts), regardless of what
 * `Vars.MODEL`/`Vars.EFFORT`/`Vars.TIER` the flow instance carries — both are
 * readable off `Vars.MODE` alone, so no `bpmn/` process change is needed. Every
 * other mode re-reads the flow's `Vars.TIER` from the tier map at each run;
 * only a flow started with a manual model pin and no tier keeps the
 * `Vars.MODEL`/`Vars.EFFORT` its start put on the process instance.
 */
@Component("startSmith")
class StartSmithDelegate(
    private val sandbox: SmithSandbox,
    private val account: SmithRunAccount,
    private val tiers: TierTable,
    private val intakeLookup: IntakeLookup,
    private val telemetry: SmithTelemetry,
    private val identity: TokenIdentity,
    private val events: EngineEvents,
    private val docs: RepoDocs? = null,
    private val attachments: AnswerAttachments? = null,
    private val stats: SmithRunStats? = null,
    private val repos: TargetRepos? = null,
    private val workItems: WorkItemBriefs? = null,
    private val parentNotes: ParentNotes? = null,
    /** A child's running live parent, for its work brief's `liveParent` (#100049). */
    private val liveParents: com.codefrak.engine.feature.LiveParents? = null,
    /** A Job's `activeDeadlineSeconds` ([KubernetesSandbox]): a parent run's brief says when its run is cut (#2596). */
    @org.springframework.beans.factory.annotation.Value("\${engine.smith.k8s.active-deadline:PT24H}")
    private val activeDeadline: java.time.Duration = java.time.Duration.ofHours(24),
    /** A parent run's turn budget, `SMITH_TURNS` (#100042; engine/docs/smith-runs.md, "Turn budget"): one session spans the whole split. */
    @org.springframework.beans.factory.annotation.Value("\${engine.smith.parent-turns:1000}")
    private val parentTurns: Int = 1000,
) : JavaDelegate {
    private val log = LoggerFactory.getLogger(StartSmithDelegate::class.java)
    private companion object { const val PARENT_MODE = "parent" }
    override fun execute(execution: DelegateExecution) {
        val mode = execution.mode()
        val (model, effort, tier) = resolveTier(mode, execution)
        val repo = execution.repo()
        val spec = SmithSpec(
            runId = execution.processInstanceId,
            repo = repo,
            issue = execution.issueOrNull(),
            jobId = execution.getVariable(Vars.JOB_ID) as? String,
            model = model,
            effort = effort,
            dryRun = execution.getVariable(Vars.DRY_RUN) as? Boolean ?: false,
            mode = mode,
            dryOutcome = execution.getVariable(Vars.FAKE_OUTCOME) as? String,
            ghScopes = ghScopes(mode) { identity.scopes(repo) },
            turns = if (mode == PARENT_MODE) parentTurns else null,
        )
            .let { it.copy(inputs = inputs(it.mode, execution, it.issue?.let { n -> intakes(it.repo, n, execution) }.orEmpty(), if (it.mode == "refine") tiers.all() else emptyList(), privateDocs(it.repo), attachments(rootOf(execution)),
                if (it.mode == "refine") repos?.docsFirst(it.repo) ?: false else null, lineage(it.repo, execution), childMode(it.mode, it.repo, execution),
                it.issue?.let { n -> workItem(it.repo, n, execution) }, parentNotes(it.mode, execution), deadline(it.mode), liveParent(it.mode, it.repo, it.issue, execution)), account = account.choose(it.model, execution),
                inputModes = if (it.mode == "work" || it.mode == "verify") reproInputs(execution).mapValues { (_, f) -> f.mode } else emptyMap()) }
        val handle = sandbox.start(spec)
        execution.setVariable(Vars.HANDLE, handle)
        // The run's `engine.smith_run_stats` row, from now on (engine/README.md, "Smith runs"): the one home of the run's
        // model, effort and tier, which `collect result` reads back; `collect result` finishes it.
        runCatching {
            stats?.start(SmithRunStat(
                runId = spec.runId, rootFlowKind = null, repo = repo, issue = spec.issue, tier = tier, model = model, effort = effort,
                account = spec.account, started = org.operaton.bpm.engine.impl.util.ClockUtil.getCurrentTime().toInstant().atOffset(java.time.ZoneOffset.UTC),
                ended = null, exitCode = null, peakBytes = null, oomKilled = null, mode = mode, scopePaths = null,
                root = rootOf(execution), wrapper = wrapperOf(execution),
            ))
        }.onFailure { log.warn("run {}: stats row not started: {}", spec.runId, it.message) }
        // The transcript and smith_run_active (SmithTelemetry): never allowed to fail the start.
        runCatching { telemetry.started(spec, handle) }
            .onFailure { log.warn("run {}: transcript and gauge not started: {}", spec.runId, it.message) }
        // `session` `started`, once this job commits (engine/README.md, "Events"), with the labels the transcript pane queries by.
        val labels = telemetry.labels(spec)
        events.publishAfterCommit(EngineEvent.Session(spec.repo, spec.issue, spec.runId, rootOf(execution), mode, model, tier, spec.account,
            "started", labels.filename, labels.host, jobId = spec.jobId))
        // The resolution is logged, not written back: `smith-run` is a called process
        // whose variables return to the calling flow, so a `security` or `refine` run
        // writing its own fixed tier here would overwrite the ticket's for every later
        // run in the flow (seen 2026-09-25: #140's second work round went from tier 8
        // to the security tier 5). A work run's values are already the flow's.
        if (tier != null) {
            log.info("run {}: tier {} -> {} {}", spec.runId, tier, model, effort)
        } else {
            log.warn("run {}: no tier resolved for {} {}, falling back to {} {}", spec.runId, spec.repo, spec.issue?.let { "#$it" } ?: "job ${spec.jobId}", model, effort)
        }
        log.info("run {}: started sandbox {} for {} {} on {}", spec.runId, handle.take(12), spec.repo, spec.issue?.let { "#$it" } ?: "job ${spec.jobId}",
            spec.account?.let { "account $it" } ?: "no account (dry run)")
    }

    /**
     * The earlier intakes the ticket names (for a refine, the ask and its answers), resolved now, so a ticket filed
     * while one still refined sees the ticket it has filed since (engine/README.md, "Blocked by", "An earlier intake").
     * Never fails the start: a lookup that throws leaves the brief without them.
     */
    private fun intakes(repo: String, issue: Long, execution: DelegateExecution): List<IntakeRef> {
        val ticket = (execution.getVariable(Vars.TICKET) as? ByteArray)?.let { Brief.ticket(it) } ?: return emptyList()
        val text = listOf(ticket.body) + Brief.answers(execution.getVariable(Vars.ANSWERS) as? ByteArray).map { it.answer }
        return runCatching { intakeLookup.references(repo, text.joinToString("\n\n"), issue) }
            .onFailure { log.warn("run {}: intake references not resolved: {}", execution.processInstanceId, it.message) }
            .getOrDefault(emptyList())
    }

    /**
     * The run's lineage (engine/README.md, "Lineage"): the one its flow captured, or built now from the ticket's
     * snapshot and kept as [Vars.LINEAGE], so every later run on the ticket is handed the same. Null with no ticket;
     * never fails the start.
     */
    private fun lineage(repo: String, execution: DelegateExecution): Lineage? = runCatching {
        (execution.getVariable(Vars.LINEAGE) as? ByteArray)?.let { return@runCatching Lineage.of(it) }
        val ticket = (execution.getVariable(Vars.TICKET) as? ByteArray)?.let { Brief.ticket(it) } ?: return@runCatching null
        Lineage.build(repo, ticket, intakeLookup).also {
            execution.setVariable(Vars.LINEAGE, org.operaton.bpm.engine.variable.Variables.byteArrayValue(it.toBytes()))
        }
    }.onFailure { log.warn("run {}: lineage not built: {}", execution.processInstanceId, it.message) }.getOrNull()

    /**
     * A feature's child-work mode for the brief (engine/README.md, "Split features"): a design run reads the repo's
     * `child_mode` as it is now (`serial` when no repo table is wired); every other run reads `Vars.CHILD_MODE`, which
     * `FeatureResultHandler` froze on the feature instance when its design split. Null for a run of any other flow.
     */
    /** The ticket's work item for the brief, fenced; null when it has none, or when the store cannot be read (logged). */
    private fun workItem(repo: String, issue: Long, execution: DelegateExecution): Map<String, Any?>? = runCatching {
        workItems?.section(repo, issue)
    }.onFailure { log.warn("run {}: work item not read: {}", execution.processInstanceId, it.message) }.getOrNull()

    /**
     * A parent run's notes from the split's earlier parent sessions (#2596): the root flow is the feature's instance, the
     * split's key. Empty when the store cannot be read (logged); null for any other mode.
     */
    private fun parentNotes(mode: String, execution: DelegateExecution): List<Map<String, Any?>>? {
        if (mode != PARENT_MODE) return null
        return runCatching { parentNotes?.notes(rootOf(execution)).orEmpty() }
            .onFailure { log.warn("run {}: parent notes not read: {}", execution.processInstanceId, it.message) }.getOrDefault(emptyList())
    }

    /** A parent run's deadline: now (its Job is created next) plus the Job's active deadline. Null for any other mode. */
    /** The running live parent's number for a work run on a child of a split (the ticket's part line names the parent); null otherwise. */
    private fun liveParent(mode: String, repo: String?, issue: Long?, execution: DelegateExecution): Long? {
        if (mode != "work" || repo == null || issue == null) return null
        val parent = (execution.getVariable(Vars.TICKET) as? ByteArray)?.let(Brief::ticket)?.body?.let(Brief::parent) ?: return null
        return liveParents?.of(repo, issue, parent)?.number
    }

    private fun deadline(mode: String): String? = if (mode != PARENT_MODE) null else
        org.operaton.bpm.engine.impl.util.ClockUtil.getCurrentTime().toInstant().plus(activeDeadline).toString()

    private fun childMode(mode: String, repo: String, execution: DelegateExecution): String? =
        if (mode == "design") repos?.childMode(repo) ?: DEFAULT_CHILD_MODE else execution.getVariable(Vars.CHILD_MODE) as? String

    /**
     * `security` and `refine` runs always resolve to their own fixed tier,
     * whatever the flow instance carries. Every other mode resolves the flow's
     * `Vars.TIER` against the current tier map at each run (writing the result
     * back as `Vars.MODEL`/`Vars.EFFORT`); a flow with no tier (a manual model
     * pin) keeps its own `Vars.MODEL`/`Vars.EFFORT`, falling back to the
     * sandbox default with a warning if the flow set none (a start outside
     * `ProcessFlowStarter`, which otherwise always sets a default itself).
     */
    private fun resolveTier(mode: String, execution: DelegateExecution): Triple<String, String, Int?> = when (mode) {
        "security" -> tiers.securityTier().let { t -> tiers.resolve(t).let { Triple(it.model, it.effort, t) } }
        // A post-deployment check runs at the map's check tier (`check.tier`), whatever the checked ticket's own.
        "check" -> tiers.checkTier().let { t -> tiers.resolve(t).let { Triple(it.model, it.effort, t) } }
        "refine" -> intakeLookup.refinerTier().let { t -> tiers.resolve(t).let { Triple(it.model, it.effort, t) } }
        else -> {
            val tier = execution.tier()
            // The tier is read against the map as it is now, not as it was when the flow started, so moving a tier
            // to another model reaches flows already running and their retried incidents (engine/README.md, "Smith runs").
            tier?.takeIf { tiers.maps(it) }?.let { t -> tiers.resolve(t) }?.let {
                execution.setVariable(Vars.MODEL, it.model); execution.setVariable(Vars.EFFORT, it.effort)
            }
            val model = execution.getVariable(Vars.MODEL) as? String
            val effort = execution.getVariable(Vars.EFFORT) as? String
            if (model != null && effort != null) Triple(model, effort, tier)
            else Triple(TierTable.SANDBOX_DEFAULT.model, TierTable.SANDBOX_DEFAULT.effort, null)
        }
    }

    /** The repo's private docs for the brief (engine/README.md, "Private documents"): names and lengths; none when the store cannot be read. */
    private fun privateDocs(repo: String): List<Map<String, Any?>> =
        runCatching { docs?.list(repo).orEmpty().map { mapOf("name" to it.name, "chars" to it.chars) } }
            .onFailure { log.warn("private docs of {} not listed for the brief: {}", repo, it.message) }.getOrDefault(emptyList())

    /** The flow's answer attachments for the brief (engine/README.md, "Answer attachments"): names, types and sizes; none when the store cannot be read. */
    private fun attachments(flow: String): List<Map<String, Any?>> =
        runCatching { attachments?.list(flow).orEmpty().map { mapOf("name" to it.name, "mime" to it.mime, "size" to it.size) } }
            .onFailure { log.warn("attachments of flow {} not listed for the brief: {}", flow, it.message) }.getOrDefault(emptyList())
}

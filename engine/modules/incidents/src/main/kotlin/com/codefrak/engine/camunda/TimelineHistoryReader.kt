package com.codefrak.engine.camunda

import com.codefrak.engine.accounts.ClaudeUsage
import com.codefrak.engine.accounts.ClaudeUsageLedger
import com.codefrak.engine.at
import com.codefrak.engine.engineJson
import com.codefrak.engine.smith.RESULT_REJECTED
import com.codefrak.engine.smith.ReviewResult
import com.codefrak.engine.smith.SmithRunStat
import com.codefrak.engine.smith.SmithRunStats
import com.codefrak.engine.smith.Vars
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.history.HistoricActivityInstance
import org.operaton.bpm.engine.history.HistoricProcessInstance
import org.operaton.bpm.engine.impl.history.event.HistoricActivityInstanceEventEntity
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/**
 * The timeline's history walk, moved out of the controller (engine/README.md, "Ticket timeline"): reads a ticket's root
 * instances and everything they called from Operaton's history and replays them into the projection ([TimelineProjection],
 * through the same [com.codefrak.engine.events.FlowRecorder] calls the live handler makes) and the Smith run rows
 * ([SmithRunStats]), so a ticket from before the projection reads back exactly as one the handler fed. History keeps a
 * variable's last value only, so a `receive-ci-check` wait another one followed in its flow ended `rerun`, and the last
 * one with the flow's `ciOutcome` or `ciConclusion`, as the controller used to read them.
 */
@Component
class TimelineHistoryReader(
    private val history: HistoryService, private val projection: TimelineProjection, private val stats: SmithRunStats,
    private val ledger: ClaudeUsageLedger, private val jdbc: JdbcClient,
) {
    private val log = LoggerFactory.getLogger(TimelineHistoryReader::class.java)

    companion object {
        private const val START = "task-start-smith"; private const val COLLECT = "task-collect-result"; private const val READ_RESULT = "task-read-result"
    }

    /** Replays [root] and its tree; the number of instances written. */
    fun replay(root: HistoricProcessInstance): Int {
        val tree = descendants(root)
        for (p in tree) projection.processStarted(p.id, root.id, p.superProcessInstanceId, p.processDefinitionKey, p.startTime)
        val vars = tree.associate { it.id to history.ownScopeVariablesOf(it.id) }
        val acts = tree.associate { it.id to activities(it.id) }
        for (p in tree) {
            val v = vars.getValue(p.id)
            if (p.id == root.id) for (name in listOf(Vars.REPO, Vars.ISSUE, Vars.TIER, Vars.TICKET, Vars.FILED_TICKET))
                v[name]?.let { projection.variable(p.id, root.id, name, it, true) }
            v[Vars.CI_CHECK]?.let { projection.variable(p.id, root.id, Vars.CI_CHECK, it, true) }
            val ordered = acts.getValue(p.id).sortedWith(compareBy({ it.startTime }, { seq(it) }))
            for (a in ordered) {
                projection.activityStarted(a.id, p.id, root.id, a.activityId, a.activityType, seq(a), a.startTime)
                if (a.endTime != null) projection.activityEnded(a.id, p.id, root.id, a.activityId, a.activityType, seq(a), a.endTime)
            }
            val waits = ordered.filter { it.activityId == TicketTimelineController.CI_WAIT }
            waits.dropLast(1).forEach { projection.ciWaitRerun(it.id) }
            if (waits.isNotEmpty()) {
                v[Vars.CI_CONCLUSION]?.let { projection.variable(p.id, root.id, Vars.CI_CONCLUSION, it, true) }
                v[Vars.CI_OUTCOME]?.let { projection.variable(p.id, root.id, Vars.CI_OUTCOME, it, true) }
            }
            for (i in history.createHistoricIncidentQuery().processInstanceId(p.id).list()) {
                val state = when { i.isOpen -> "open"; i.isDeleted -> "deleted"; else -> "resolved" }
                projection.incident(i.id, p.id, root.id, i.activityId, i.incidentMessage, i.rootCauseIncidentId, state, i.createTime, i.endTime)
            }
            if (p.processDefinitionKey == TicketTimelineController.SMITH_RUN) run(p, root, tree, vars, acts)
            if (p.endTime != null) {
                val cancelled = p.state == HistoricProcessInstance.STATE_EXTERNALLY_TERMINATED || p.deleteReason != null
                projection.processEnded(p.id, root.id, p.endTime, cancelled)
            }
        }
        if (root.processDefinitionKey == TicketTimelineController.INTAKE) vars.getValue(root.id)[Vars.OUTPUT]?.let { projection.variable(root.id, root.id, Vars.OUTPUT, it, true) }
        return tree.size
    }

    /**
     * A Smith run's `engine.smith_run_stats` row, its findings and, for a collected run with no ledger row, its cost, as the delegates would
     * have written them. `runModel`, `runEffort`, `runTier`, `cost`, `turns`, `durationMs` and the wrapper's `reviews` are read by name:
     * they were process variables until #1976, and a ticket worked before has them only in Operaton's history. This is their one reader.
     */
    private fun run(p: HistoricProcessInstance, root: HistoricProcessInstance, tree: List<HistoricProcessInstance>,
                    vars: Map<String, Map<String, Any?>>, acts: Map<String, List<HistoricActivityInstance>>) {
        val v = vars.getValue(p.id)
        val own = acts.getValue(p.id)
        val done = own.filter { it.endTime != null }.associateBy { it.activityId }
        val collected = COLLECT in done
        val started = START in done
        val mode = v[Vars.MODE] as? String
        val wrapper = tree.firstOrNull { it.id == p.superProcessInstanceId }
        val siblings = tree.filter { it.superProcessInstanceId == wrapper?.id && it.processDefinitionKey == TicketTimelineController.SMITH_RUN }.sortedBy { it.startTime }
        val last = wrapper != null && siblings.lastOrNull()?.id == p.id
        val wrapperVars = wrapper?.let { vars[it.id] }.orEmpty()
        val output = if (collected) v[Vars.OUTPUT] as? ByteArray else null
        val resultJson = output?.let { runCatching { engineJson.readTree(it) }.getOrNull() }
        val result = if (collected) v[Vars.RESULT] as? String else null
        val rejected = last && wrapper?.endTime != null && wrapperVars["outcome"] == RESULT_REJECTED
        val outcome = if (rejected) RESULT_REJECTED else resultJson?.get("outcome")?.takeIf { it.isString }?.asString() ?: result
        val readDone = wrapper != null && acts[wrapper.id].orEmpty().any { it.activityId == READ_RESULT && it.endTime != null }
        val reviewNo = if (mode in TicketTimelineController.REVIEW_MODES && last && readDone) (wrapperVars["reviews"] as? Number)?.toInt() else null
        val ended = if (collected) done.getValue(COLLECT).endTime?.at() else null
        val repo = v[Vars.REPO] as? String ?: return
        val issue = (v[Vars.ISSUE] as? Number)?.toLong()
        stats.backfill(SmithRunStat(
            runId = p.id, rootFlowKind = root.processDefinitionKey, repo = repo, issue = issue,
            tier = if (started) (v["runTier"] as? Number)?.toInt() else null,
            model = if (started) v["runModel"] as? String else null, effort = if (started) v["runEffort"] as? String else null,
            account = v[Vars.BILLED_ACCOUNT] as? String, started = p.startTime.at(), ended = ended,
            exitCode = if (collected) (v[Vars.EXIT_CODE] as? Number)?.toLong() else null,
            peakBytes = if (collected) (v[Vars.PEAK_MEMORY] as? Number)?.toLong() else null, oomKilled = null, mode = mode,
            root = root.id, wrapper = wrapper?.id, outcome = outcome,
            question = resultJson?.get("question")?.takeIf { it.isString }?.asString()?.take(4000),
            turns = if (collected) (v["turns"] as? Number)?.toLong() else null,
            durationMs = if (collected) (v["durationMs"] as? Number)?.toLong() else null, result = result, reviewNo = reviewNo,
        ))
        if (reviewNo != null && output != null && mode != null)
            runCatching { stats.findings(p.id, reviewNo, ReviewResult.parse(output).stamped(mode, reviewNo)) }
                .onFailure { log.warn("backfill: findings of run {} not read: {}", p.id, it.message) }
        // Cost keeps its home in engine.claude_usage: a run collected before the ledger (#1938) gets its row from the `cost` variable.
        if (ended != null && jdbc.sql("SELECT COUNT(*) FROM engine.claude_usage WHERE run_id = :r").param("r", p.id).query(Long::class.java).single() == 0L)
            ledger.record(ClaudeUsage(caller = ClaudeUsageLedger.callerOf(root.processDefinitionKey), role = ClaudeUsageLedger.roleOf(mode ?: "work"),
                runId = p.id, repo = repo, issue = issue, account = v[Vars.BILLED_ACCOUNT] as? String, model = v["runModel"] as? String,
                started = p.startTime.at(), ended = ended, durationMs = (v["durationMs"] as? Number)?.toLong(),
                costUsd = (v["cost"] as? Number)?.toDouble()))
    }

    private fun descendants(root: HistoricProcessInstance): List<HistoricProcessInstance> {
        val all = mutableListOf<HistoricProcessInstance>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            all += p
            queue += history.createHistoricProcessInstanceQuery().superProcessInstanceId(p.id).list()
        }
        return all
    }

    private fun activities(processInstance: String): List<HistoricActivityInstance> =
        history.createHistoricActivityInstanceQuery().processInstanceId(processInstance).list()

    /** Operaton's sequence counter of the activity instance (the entity carries it, the interface does not): the live handler's `seq`. */
    private fun seq(a: HistoricActivityInstance): Long = (a as? HistoricActivityInstanceEventEntity)?.sequenceCounter ?: 0L
}

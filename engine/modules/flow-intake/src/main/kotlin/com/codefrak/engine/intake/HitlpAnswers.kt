package com.codefrak.engine.intake

import com.codefrak.engine.board.NeedKeys
import com.codefrak.engine.engineJson
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.hitlp.FlowWait
import com.codefrak.engine.hitlp.HitlpAnswerer
import com.codefrak.engine.hitlp.HitlpAsserted
import com.codefrak.engine.hitlp.HitlpFiling
import com.codefrak.engine.hitlp.HitlpRequest
import com.codefrak.engine.hitlp.HitlpRequester
import com.codefrak.engine.hitlp.HitlpRequests
import com.codefrak.engine.proposal.DecisionVerb
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.SmithRunStats
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.workitems.Tickets
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.TaskService
import org.operaton.bpm.engine.variable.Variables
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Continues an ask wait from its HITLP decision record (#2462): every `user-task-answer-*` a posting delegate filed
 * through [HitlpFiling.ask] (a Smith's question, the intake's duplicate question, the proposal's decision, the research
 * ack). The decider is named from the record's `decidedBy` ([decider]): the `engine.intake_question` row answered, the "<decider>
 * answered:" comment with the same trailer, the user task completed with [Vars.ANSWERS] and [Vars.HITLP_RECORD] (and,
 * for a proposal decision's `accept` or `accept and build auto`, [Vars.DECISION_CHOICE] so no Jev call reads it), and
 * the `GET /needs` `removed` and intake `answered` events.
 *
 * A `timed_out` record files the question again (`:r<n>`, [HitlpFiling.key]) and says so on the ticket once; a
 * `cancelled` one ends nothing, as the flow that was cancelled ([withdraw]) already moved on. A wait already answered
 * is left as it is.
 *
 * An approve wait (a merge acceptance or a legacy feature acceptance, filed through [HitlpFiling.approve], #2467) is
 * answered the same way with the text `approved` or `rejected` (plus `: <reason>`), completing with
 * [Vars.MERGE_ACCEPTED] or the legacy `outcome`; `task-read-merge-acceptance` and `task-read-acceptance` read the
 * record. A `timed_out` or `cancelled` approve record completes as not accepted (a `merge-<round>-1` finding carrying
 * its `reason`), never re-filed: the pushed head waits for a new acceptance after the work.
 */
@Component
class HitlpAnswers(
    private val questions: IntakeQuestions,
    private val tickets: Tickets,
    private val tasks: TaskService,
    private val runtime: RuntimeService,
    private val events: EngineEvents,
    private val filing: HitlpFiling,
    private val requests: HitlpRequests,
    private val requester: HitlpRequester,
    private val runs: SmithRunStats,
) : HitlpAnswerer {
    companion object {
        private val log = LoggerFactory.getLogger(HitlpAnswers::class.java)
        private val ASKS = setOf(HitlpAsserted.Kind.QUESTION.name, HitlpAsserted.Kind.DECISION.name, HitlpAsserted.Kind.ACK.name)
        private val APPROVES = setOf(HitlpAsserted.Kind.MERGE.name, HitlpAsserted.Kind.ACCEPTANCE.name)
        private val REFILE = Regex(""":r(\d+)$""")

        /** Who decided [record], by its `decidedBy.type`: `@<id> (<channel>)`, `rule <id>`, `model <id> (<confidence>)`, `policy <id>`. */
        fun decider(record: Map<String, Any?>): String {
            val by = record["decidedBy"] as? Map<*, *> ?: emptyMap<String, Any?>()
            val id = by["id"]?.toString()?.takeIf { it.isNotBlank() } ?: "unknown"
            return when (by["type"]) {
                "rule" -> "rule $id"
                "model" -> "model $id" + (by["confidence"]?.let { " ($it)" } ?: "")
                "policy" -> "policy $id"
                else -> "@$id" + ((record["channel"] as? String)?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
            }
        }

        /** Who the answer row and the brief's `answers` name: a person's id, else the [decider]. */
        fun answeredBy(record: Map<String, Any?>): String {
            val by = record["decidedBy"] as? Map<*, *>
            return if (by?.get("type") == "human") by["id"]?.toString()?.takeIf { it.isNotBlank() } ?: decider(record) else decider(record)
        }

        /**
         * The answer as text: an option of a `choice` ask block ([options], from the question's block) as `option <id>:
         * <label>`, as the Pitch view answers one; any other value as it is (a proposal's or a research ack's verb, a
         * free answer). An `answer` object gives its `value`.
         */
        fun answerText(record: Map<String, Any?>, options: List<Map<String, Any?>>?): String {
            val answer = record["answer"].let { if (it is Map<*, *>) it["value"] ?: it["id"] else it }?.toString().orEmpty()
            val option = options?.firstOrNull { it["value"]?.toString() == answer } ?: return answer
            return "option ${option["value"]}: ${option["label"] ?: option["value"]}"
        }

        /** The structured choice of a proposal decision's verb: `accept and build auto` and `accept`; null for one `task-read-decision` parses. */
        fun decisionChoice(verb: String): String? = when (verb.trim().lowercase()) {
            "accept and build auto" -> DecisionVerb.BUILD_AUTO
            "accept" -> DecisionVerb.ACCEPT
            else -> null
        }
    }

    override fun complete(request: HitlpRequest, record: Map<String, Any?>) {
        if (request.kind in APPROVES) {
            if (record["primitive"] == "approve") approve(request, record)
            return
        }
        if (request.kind !in ASKS || record["primitive"] == "approve") return
        when (record["outcome"]) {
            "answered" -> answer(request, record)
            "timed_out" -> refile(request)
            else -> log.info("hitlp task {} ({}) ended {}: nothing to continue", request.taskId, request.idempotencyKey, record["outcome"] ?: record["status"])
        }
    }

    private fun answer(request: HitlpRequest, record: Map<String, Any?>) {
        val id = request.processInstance
        val pending = questions.pending(id) ?: return
        val task = tasks.createTaskQuery().processInstanceId(id).taskDefinitionKey(request.userTaskKey).singleResult() ?: return
        val instance = runtime.createProcessInstanceQuery().processInstanceId(id).singleResult() ?: return
        val kind = instance.processDefinitionId.substringBefore(':')
        val decision = request.userTaskKey == NeedKeys.DECISION_TASK
        val text = answerText(record, if (decision) null else AskWaits.askBlock(pending.question)?.get("options") as? List<Map<String, Any?>>)
        if (text.isBlank()) error("hitlp task ${request.taskId}: the record has no answer")
        val by = answeredBy(record)
        if (!questions.answer(pending.id, text, by)) return
        val next = if (kind == "intake") "refining again" else "going on"
        tickets.commentOn(pending.repo, pending.issue, "${decider(record)} answered:\n\n${text.trimEnd()}\n\n— engine $kind `$id`, $next.")
        val choice = if (decision && kind == "proposal") decisionChoice(text) else null
        val vars = buildMap<String, Any?> {
            put(Vars.ANSWERS, Variables.byteArrayValue(Brief.answersBytes(questions.answered(id))))
            put(Vars.HITLP_RECORD, Variables.byteArrayValue(engineJson.writeValueAsBytes(record)))
            choice?.let { put(Vars.DECISION_CHOICE, it); put(Vars.DECISION_CHOICE_WHY, "$text, decided by ${decider(record)} on the HITLP server") }
        }
        // Read before the completion: a flow that ends inside it has no variables left, and a failed read in the
        // poller's transaction marks it rollback-only, undoing the completion (#2467).
        val asker = NeedKeys.askerIn(runtime.getVariables(id))
        tasks.complete(task.id, vars)
        events.publish(EngineEvent.Needs(pending.repo, needKind(task.taskDefinitionKey), "removed", pending.issue, id, forLogin = asker))
        if (kind == "intake") events.publish(EngineEvent.Intake(pending.repo, id, "answered", pending.issue, by = by))
    }

    /**
     * Completes an approve wait from [record], whatever its outcome: the answer text is `approved` or `rejected` (any
     * other outcome, `timed_out` or `cancelled`, as itself), `: <reason>` added when the record gives one.
     */
    private fun approve(request: HitlpRequest, record: Map<String, Any?>) {
        val id = request.processInstance
        val pending = questions.pending(id) ?: return
        val task = tasks.createTaskQuery().processInstanceId(id).taskDefinitionKey(request.userTaskKey).singleResult() ?: return
        val instance = runtime.createProcessInstanceQuery().processInstanceId(id).singleResult() ?: return
        val kind = instance.processDefinitionId.substringBefore(':')
        val outcome = record["outcome"]?.toString() ?: record["status"]?.toString() ?: "rejected"
        val accepted = outcome == "approved"
        val text = outcome + ((record["reason"] as? String)?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
        val by = answeredBy(record)
        if (!questions.answer(pending.id, text, by)) return
        tickets.commentOn(pending.repo, pending.issue, "${decider(record)} answered:\n\n${text.trimEnd()}\n\n— engine $kind `$id`, going on.")
        val vars = buildMap<String, Any?> {
            put(Vars.ANSWERS, Variables.byteArrayValue(Brief.answersBytes(questions.answered(id))))
            put(Vars.HITLP_RECORD, Variables.byteArrayValue(engineJson.writeValueAsBytes(record)))
            if (request.kind == HitlpAsserted.Kind.MERGE.name) put(Vars.MERGE_ACCEPTED, accepted)
            else put("outcome", if (accepted) "accepted" else "rejected")
        }
        // Read before the completion: a flow that ends inside it has no variables left, and a failed read in the
        // poller's transaction marks it rollback-only, undoing the completion (#2467).
        val asker = NeedKeys.askerIn(runtime.getVariables(id))
        tasks.complete(task.id, vars)
        events.publish(EngineEvent.Needs(pending.repo, needKind(task.taskDefinitionKey), "removed", pending.issue, id, forLogin = asker))
    }

    /** Files the question of a `timed_out` [request] again, as the next `:r<n>`, and says so on the ticket. */
    private fun refile(request: HitlpRequest) {
        val id = request.processInstance
        val pending = questions.pending(id) ?: return
        tasks.createTaskQuery().processInstanceId(id).taskDefinitionKey(request.userTaskKey).singleResult() ?: return
        val instance = runtime.createProcessInstanceQuery().processInstanceId(id).singleResult() ?: return
        val vars = runtime.getVariables(id)
        val kind = HitlpAsserted.Kind.valueOf(request.kind)
        val refile = (REFILE.find(request.idempotencyKey)?.groupValues?.get(1)?.toInt() ?: 0) + 1
        val nth = request.idempotencyKey.removePrefix("$id:${request.userTaskKey}:").substringBefore(':').toIntOrNull() ?: 1
        val block = when {
            request.userTaskKey != NeedKeys.DECISION_TASK -> AskWaits.askBlock(pending.question)
            instance.processDefinitionId.startsWith("research:") -> mapOf("kind" to "research")
            else -> mapOf("kind" to "proposal")
        }
        val askedBy = NeedKeys.askerIn(vars) ?: if (kind == HitlpAsserted.Kind.QUESTION) null else pending.repo.substringBefore('/')
        val runId = if (kind == HitlpAsserted.Kind.QUESTION) runCatching { runs.latestRunId(instance.rootProcessInstanceId) }.getOrNull() ?: id else null
        val filed = filing.ask(FlowWait(id, request.userTaskKey, kind, pending.repo, pending.issue, pending.question, block, askedBy, runId, nth = nth), refile)
        runtime.setVariable(id, Vars.HITLP_TASK_ID, filed.handle.taskId)
        tickets.commentOn(pending.repo, pending.issue, "The question timed out on the HITLP server after ${HitlpFiling.ASK_TTL.toDays()} days; " +
            "filed again as ${filed.decisionUrl ?: "HITLP task ${filed.handle.taskId}"}.")
    }

    /**
     * Ends flow [processInstance]'s open HITLP requests, as a cancel withdraws its questions: `tasks/cancel` for each
     * (a server that refuses or cannot be reached is a WARN), and the row finished with a local `cancelled` record so
     * the poller never reads it again.
     */
    fun withdraw(processInstance: String, by: String, reason: String) {
        for (open in runCatching { requests.openFor(processInstance) }.getOrElse { log.warn("hitlp requests of {} not read: {}", processInstance, it.message); return }) {
            runCatching { requester.cancel(open.taskId) }
                .onFailure { log.warn("hitlp task {} ({}): tasks/cancel failed: {}", open.taskId, open.idempotencyKey, it.message) }
            requests.finish(open.taskId, linkedMapOf(
                "requestId" to open.taskId, "idempotencyKey" to open.idempotencyKey,
                "primitive" to if (open.kind in ASKS) "ask" else "approve", "outcome" to "cancelled", "reason" to "cancelled: $reason",
                "decidedBy" to mapOf("type" to "policy", "id" to "engine cancel by $by"),
                "decidedAt" to OffsetDateTime.now(ZoneOffset.UTC).withNano(0).toString(),
            ))
        }
    }
}

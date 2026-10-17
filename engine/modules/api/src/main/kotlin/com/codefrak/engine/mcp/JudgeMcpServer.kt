package com.codefrak.engine.mcp

import com.codefrak.engine.hitlp.HitlpFiling
import com.codefrak.engine.hitlp.HitlpHandle
import com.codefrak.engine.hitlp.HitlpRequest
import com.codefrak.engine.hitlp.HitlpRequester
import com.codefrak.engine.hitlp.HitlpRequests
import com.codefrak.engine.hitlp.HitlpSchemas
import com.codefrak.engine.hitlp.HitlpPrimitives
import com.codefrak.engine.hitlp.JudgeEscalation
import com.codefrak.engine.jev.JudgeRequest
import com.codefrak.engine.jev.Judgment
import com.codefrak.engine.jev.SshJudge
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * `judge` (engine/README.md, "The judge server"): the SSH command judge at `POST /mcp/judge`, one tool [COMMAND] that
 * asks Jev ([SshJudge], use case `ssh-judge`) whether a command may run under a rubric. Same transport and bearer-token
 * refusal as every engine MCP server ([EngineMcpController]); an [McpCaller.Unknown] is refused.
 *
 * On verdict `escalate` the server files the escalation itself: a `human.approve` on the HITLP server through
 * [HitlpRequester] ([approveRequest]: `asserted.recommendation` from the verdict, `asserted.onBehalfOf` the caller,
 * `requiresHuman: false`, no `requester`, `context.data.judge {callId, rubricPath, repo}`), its handle stored in
 * `engine.hitlp_request` with `judge_call_id` ([JudgeEscalation]), and the answer carries `{taskId, decisionUrl,
 * pollInterval}` alongside the verdict. A [TaskServer] for `tasks/get` and `tasks/cancel` only: both are proxied to the
 * HITLP server for a task this caller filed here, any other task id is [TaskCall.Refused.REFUSED] (`-32001`).
 * [COMMAND] stays a plain tool ([plainTools]).
 */
@Component
open class JudgeMcpServer(
    private val judge: SshJudge,
    private val requester: HitlpRequester,
    private val requests: HitlpRequests,
    private val jdbc: JdbcClient,
) : TaskServer {
    private val log = LoggerFactory.getLogger(JudgeMcpServer::class.java)

    companion object {
        const val NAME = "judge"
        const val COMMAND = "judge.command"
        val REQUIRED = listOf("rubric", "command", "why", "ticketGoal", "priorCommands", "files", "host", "requester")
        private val STRINGS = listOf("rubric", "command", "why", "ticketGoal", "host", "requester")
        private val LISTS = listOf("priorCommands", "files")
        /** How long an escalation waits for a person: the merge acceptance's [HitlpFiling.APPROVE_TTL]. */
        val ESCALATION_TTL: Duration = HitlpFiling.APPROVE_TTL
        private val ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME

        /**
         * The `human.approve` arguments of [judgment]'s escalation (verdict `escalate`) of [request], for [onBehalfOf]
         * (`{kind: person, id, login}` or `{kind: run, id}`), filed under [repo] with the rubric at [rubricPath] (null
         * when the caller named none) and Jev's [model], at [now].
         */
        fun approveRequest(judgment: Judgment, request: JudgeRequest, repo: String, rubricPath: String?, model: String?,
                           onBehalfOf: Map<String, Any?>, now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)): Map<String, Any?> {
            val payload = linkedMapOf("command" to request.command, "host" to request.host, "why" to request.why)
            val deadline = ISO.format(now.plus(ESCALATION_TTL).withOffsetSameInstant(ZoneOffset.UTC).withNano(0))
            val recommendation = linkedMapOf<String, Any?>("verdict" to "escalate").apply {
                judgment.confidence?.let { put("confidence", it) }
                put("by", linkedMapOf("judge" to SshJudge.USE_CASE).apply { model?.let { put("model", it) }; put("rubric", rubricPath ?: "inline") })
                put("reasons", listOfNotNull(judgment.citedPassage, judgment.reasoning.takeIf { it.isNotBlank() }))
            }
            return linkedMapOf(
                "idempotencyKey" to "judge:${judgment.callId}",
                "deadline" to deadline,
                "defaultOnTimeout" to "reject",
                // A command an agent waits on: a surface interrupts for it (HITLP spec 4, 7.7).
                "priority" to HitlpFiling.APPROVE_PRIORITY,
                "context" to mapOf("summary" to "${request.command} on ${request.host}",
                    "data" to mapOf("judge" to linkedMapOf("callId" to judgment.callId, "rubricPath" to rubricPath, "repo" to repo))),
                "action" to "run `${request.command}` on ${request.host}",
                "payload" to payload,
                "payloadDigest" to HitlpSchemas.digest(payload),
                "scope" to mapOf("maxUses" to 1, "notAfter" to deadline),
                "asserted" to linkedMapOf("why" to request.why, "onBehalfOf" to onBehalfOf, "recommendation" to recommendation,
                    "asOf" to ISO.format(now.withOffsetSameInstant(ZoneOffset.UTC))),
                "requiresHuman" to false,
            )
        }

        /** Who [caller] is to an escalation: its `onBehalfOf` and the [JudgeEscalation] owner key; null for a caller with no login and no run. */
        fun party(caller: McpCaller): Pair<Map<String, Any?>, String>? = when (caller) {
            is McpCaller.Smith -> mapOf("kind" to "run", "id" to caller.caller.runId) to JudgeEscalation.runOwner(caller.caller.runId)
            is McpCaller.Nobody -> caller.login?.trim()?.takeIf { it.isNotEmpty() }?.let {
                mapOf("kind" to "person", "id" to it, "login" to it) to JudgeEscalation.loginOwner(it) }
            is McpCaller.Unknown -> null
        }
    }

    override val plainTools = setOf(COMMAND)

    override val name = NAME
    override val version = "1"

    private val listed = listOf(McpTool(COMMAND,
        "Judge one SSH command against a rubric (Principles / Allow / Escalate / Never / Decisions). Answers {verdict " +
            "(allow|escalate|deny), confidence (0..1), citedPassage (a passage of the rubric, verbatim), reasoning, callId}, " +
            "or {noAnswer: true, reason, callId} when Jev gave no answer, was below the threshold or cited nothing in the " +
            "rubric: treat that as not allowed. On escalate the engine files a human.approve on the HITLP server for it and " +
            "adds {taskId, decisionUrl, pollInterval}: poll it with tasks/get here (or answers escalationError when it could not).",
        obj(
            "rubric" to prop("string", "the rubric, markdown, with ## sections"),
            "command" to prop("string", "the command to run"),
            "why" to prop("string", "why it is run"),
            "ticketGoal" to prop("string", "the goal of the ticket it serves"),
            "priorCommands" to prop("array", "the commands run before it in this session", "items" to mapOf("type" to "string")),
            "files" to prop("array", "files the command reads or writes", "items" to mapOf("type" to "string")),
            "host" to prop("string", "the host it runs on"),
            "requester" to prop("string", "who asks (a run, a login)"),
            "repo" to prop("string", "owner/name the call's jev_call row is filed under; a Smith's is its run's"),
            "rubricPath" to prop("string", "the rubric's path in repo, a plain relative .md path; POST /judge/teach commits under its ## Decisions"),
            required = REQUIRED)))

    override fun tools(): List<McpTool> = listed

    override fun callTask(tool: String, args: Map<String, Any?>, caller: McpCaller): TaskCall {
        if (caller is McpCaller.Unknown) return TaskCall.Refused.caller("the caller could not be identified")
        return judgeCommand(tool, args, caller)
    }

    override fun getTask(taskId: String, caller: McpCaller): TaskCall = owned(taskId, caller) { TaskCall.Ok(task(requester.get(taskId))) }

    override fun cancelTask(taskId: String, caller: McpCaller): TaskCall = owned(taskId, caller) { TaskCall.Ok(task(requester.cancel(taskId))) }

    /** No listing: a caller polls the task ids its escalations answered. */
    override fun listTasks(caller: McpCaller, cursor: String?): TaskCall = TaskCall.Ok(mapOf("tasks" to emptyList<Any?>()))

    private fun owned(taskId: String, caller: McpCaller, then: () -> TaskCall): TaskCall {
        val owner = party(caller)?.second
        if (owner == null || !JudgeEscalation.ownedBy(requests.get(taskId), owner))
            return TaskCall.Refused.caller("task $taskId is no judge escalation this caller filed")
        return then()
    }

    private fun task(handle: HitlpHandle): Map<String, Any?> = linkedMapOf<String, Any?>("taskId" to handle.taskId, "status" to handle.status,
        "pollInterval" to handle.pollInterval).apply {
        handle.ttl?.let { put("ttl", it) }; handle.statusMessage?.let { put("statusMessage", it) }
        handle.meta?.let { put("_meta", it) }; handle.result?.let { put("result", it) }
    }

    override fun call(tool: String, args: Map<String, Any?>, caller: McpCaller): McpToolResult {
        if (caller is McpCaller.Unknown) return McpToolResult.error("the caller could not be identified")
        return when (val r = judgeCommand(tool, args, caller)) {
            is TaskCall.Ok -> McpToolResult.of(r.result)
            is TaskCall.Refused -> McpToolResult.error(r.message)
        }
    }

    /** [COMMAND]: the judgment, with the escalation's handle on `escalate`; a bad argument is [TaskCall.Refused.invalid]. */
    private fun judgeCommand(tool: String, args: Map<String, Any?>, caller: McpCaller): TaskCall {
        fun bad(why: String) = TaskCall.Refused.invalid(why)
        if (tool != COMMAND) return bad("unknown tool $tool")
        val extra = args.keys.filter { it !in REQUIRED + "repo" + "rubricPath" }
        if (extra.isNotEmpty()) return bad("$tool takes only ${quoted(REQUIRED + "repo" + "rubricPath")}, not ${quoted(extra)}")
        val missing = REQUIRED.filter { args[it] == null } +
            STRINGS.filter { args[it] != null && args[it] !is String } + LISTS.filter { args[it] != null && args[it] !is List<*> }
        if (missing.isNotEmpty()) return bad("missing or mistyped: ${missing.distinct().joinToString()}")
        val rubric = args["rubric"] as String
        if (rubric.isBlank()) return bad("rubric is empty")
        val repo = (caller as? McpCaller.Smith)?.caller?.repo ?: (args["repo"] as? String)?.takeIf { it.isNotBlank() }
            ?: return bad("$tool needs a repo: there is no default repo (the target repos: ${targetRepos().joinToString(", ").ifEmpty { "none" }})")
        fun list(key: String) = (args[key] as List<*>).map { it.toString() }
        val rubricPath = args["rubricPath"]?.let { it as? String ?: return bad("missing or mistyped: rubricPath") }?.takeIf { it.isNotBlank() }
        val request = JudgeRequest(rubric, args["command"] as String, args["why"] as String, args["ticketGoal"] as String,
            list("priorCommands"), list("files"), args["host"] as String, args["requester"] as String)
        val judgment = judge.judge(request, repo)
        if (judgment.noAnswer || judgment.verdict != "escalate") return TaskCall.Ok(judgment.toMap())
        return TaskCall.Ok(judgment.toMap() + escalate(judgment, request, repo, rubricPath, caller))
    }

    /** The target repos a refusal names; open so a test can stand in for the table. */
    internal open fun targetRepos(): List<String> =
        jdbc.sql("SELECT full_name FROM engine.target_repo ORDER BY full_name").query(String::class.java).list().filterNotNull()

    /** Files [judgment]'s escalation and stores its handle: `{taskId, decisionUrl, pollInterval}`, or `{escalationError}` when it could not be filed. */
    private fun escalate(judgment: Judgment, request: JudgeRequest, repo: String, rubricPath: String?, caller: McpCaller): Map<String, Any?> {
        val (onBehalfOf, owner) = party(caller) ?: return mapOf("escalationError" to "the caller has no login and no run to file the escalation for")
        return runCatching {
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            val args = approveRequest(judgment, request, repo, rubricPath, model(judgment.callId), onBehalfOf, now)
            HitlpSchemas.requesterProblem(HitlpPrimitives.APPROVE, args)?.let { throw IllegalStateException("the escalation breaks the approve schema: $it") }
            val handle = requester.call("human.${HitlpPrimitives.APPROVE}", args, ESCALATION_TTL.toMillis())
            requests.save(HitlpRequest(taskId = handle.taskId, idempotencyKey = args["idempotencyKey"] as String,
                processInstance = JudgeEscalation.processInstance(judgment.callId), userTaskKey = owner, kind = JudgeEscalation.KIND,
                pollIntervalMs = handle.pollInterval.toInt(), createdAt = now, nextPollAt = now.plusNanos(handle.pollInterval * 1_000_000L),
                judgeCallId = judgment.callId))
            linkedMapOf<String, Any?>("taskId" to handle.taskId, "decisionUrl" to HitlpFiling.decisionUrl(handle), "pollInterval" to handle.pollInterval)
        }.getOrElse {
            log.warn("judge escalation of call {} not filed: {}", judgment.callId, it.message)
            mapOf("escalationError" to (it.message ?: it.toString()))
        }
    }

    /** The Jev model of [callId]'s `engine.jev_call` row, when it can be read. */
    private fun model(callId: String): String? = runCatching {
        jdbc.sql("SELECT model FROM engine.jev_call WHERE id = :id").param("id", callId).query(String::class.java).optional().orElse(null)
    }.getOrNull()
}

package com.codefrak.engine.hitlp

import com.codefrak.engine.engineJson
import com.codefrak.engine.utc
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import tools.jackson.module.kotlin.readValue
import java.time.OffsetDateTime

/**
 * `GET /jev/ssh-verdicts` (engine/README.md, "SSH verdicts"): the `ssh-judge` jev calls newest first, each with what
 * the judge was asked (host, command, from the request's state), what it answered (verdict, confidence, cited passage,
 * reasoning, from the `judge` merged into the response) and the human decision of the escalation `/mcp/judge` filed
 * for it, when one followed: the call's `judge_call_id` row in `engine.hitlp_request` names the task, read with
 * `tasks/get` on the HITLP server ([HitlpRequester.get]), never from `engine.hitlp_task`. Its `recommendation` is the
 * request's `asserted.recommendation`, falling back to `context.data.judge` ([RubricTeacher.Judge.of]). A task the
 * server cannot answer shows the stored record, else `status: unknown`. Read-only; the desktop's SSH verdicts view draws it.
 */
@RestController
class SshVerdictsController(private val jdbc: JdbcClient, private val requests: HitlpRequests, private val requester: HitlpRequester) {
    @GetMapping("/jev/ssh-verdicts")
    fun list(
        @RequestParam(required = false) repo: String?,
        @RequestParam(required = false, defaultValue = "100") limit: Int,
    ): Map<String, Any?> {
        if (limit !in 1..MAX_LIMIT) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "limit is 1 to $MAX_LIMIT")
        val r = repo?.trim()?.takeIf { it.isNotEmpty() }
        val calls = jdbc.sql("SELECT id, called_at, repo, request, response, action FROM engine.jev_call WHERE use_case = :u" +
            (if (r != null) " AND LOWER(repo) = :r" else "") + " ORDER BY called_at DESC, id DESC LIMIT :n")
            .param("u", USE_CASE).param("n", limit).apply { if (r != null) param("r", r.lowercase()) }
            .query { rs, _ -> Call(rs.getString("id"), rs.getObject("called_at", OffsetDateTime::class.java), rs.getString("repo"),
                rs.getString("request"), rs.getString("response"), rs.getString("action")) }.list()
        val decisions = decisions(calls.map { it.id }.toSet())
        return linkedMapOf("verdicts" to calls.map { verdict(it, decisions[it.id]) })
    }

    /** The judge escalations of [ids] by call id, each as the HITLP server answers its task now. */
    private fun decisions(ids: Set<String>): Map<String, Map<String, Any?>> =
        requests.byJudgeCalls(ids).mapValues { (_, row) ->
            val handle = runCatching { requester.get(row.taskId) }.getOrNull()
            val record = handle?.record ?: row.record
            decisionOf(row.taskId, handle?.status ?: if (row.record != null) "completed" else "unknown", record,
                (record?.get("decidedAt") as? String)?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() })
        }

    private data class Call(val id: String, val calledAt: OffsetDateTime, val repo: String?, val request: String, val response: String?, val action: String?)

    companion object {
        const val USE_CASE = "ssh-judge"
        const val MAX_LIMIT = 500

        private fun parse(text: String?): Any? = text?.let { runCatching { engineJson.readValue<Any>(it) }.getOrNull() }

        /**
         * The human decision of a judge-escalation task: its outcome (`approved`, `rejected`, …) once decided, else null,
         * and the judge's recommendation as the record's request carries it ({verdict, confidence}, null without one).
         */
        fun decisionOf(taskId: String, status: String, decision: Map<*, *>?, decidedAt: OffsetDateTime?): Map<String, Any?> {
            val judge = (decision?.get("request") as? Map<*, *>)?.let { RubricTeacher.Judge.of(it, decision["recommendation"] as? Map<*, *>) }
            return linkedMapOf(
                "task_id" to taskId, "status" to status, "outcome" to decision?.get("outcome"),
                "decided_by" to (decision?.get("decidedBy") ?: decision?.get("by")), "decided_at" to decidedAt?.let { utc(it) },
                "recommendation" to judge?.let { mapOf("verdict" to it.verdict, "confidence" to it.confidence) },
            )
        }

        private fun verdict(call: Call, decision: Map<String, Any?>?): Map<String, Any?> {
            val state = (parse(call.request) as? Map<*, *>)?.get("state") as? Map<*, *>
            val judge = (parse(call.response) as? Map<*, *>)?.get("judge") as? Map<*, *>
            return linkedMapOf(
                "id" to call.id, "called_at" to utc(call.calledAt), "repo" to call.repo,
                "host" to state?.get("host"), "command" to state?.get("command"),
                "verdict" to (judge?.get("verdict") ?: call.action?.takeIf { it == "no-answer" }),
                "confidence" to judge?.get("confidence"), "cited_passage" to judge?.get("citedPassage"),
                "reasoning" to (judge?.get("reasoning") ?: judge?.get("reason")), "decision" to decision,
            )
        }
    }
}

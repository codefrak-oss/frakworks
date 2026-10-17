package com.codefrak.engine.hitlp

import com.codefrak.engine.engineJson
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.readValue
import java.sql.ResultSet
import java.time.OffsetDateTime

/**
 * `engine.hitlp_request` (V81; #2447): the store of the handles the engine holds as a HITLP requester, one row per
 * request filed ([save]). The tick (part 2) reads the open rows whose poll is due ([due]), moves each one's next poll
 * by the interval the server's handle gave ([bump]) and, once the task is terminal, writes the decision record in one
 * conditional update ([finish]) after which the row is never due again. [openFor] finds a flow's open requests to
 * cancel or resume; [get] one row by its task id. `record` is JSON kept as TEXT, as `hitlp_task` keeps its request.
 */
@Repository
class HitlpRequests(private val jdbc: JdbcClient) {
    companion object {
        private const val COLUMNS = "task_id, idempotency_key, process_instance, user_task_key, kind, poll_interval_ms, created_at, next_poll_at, record, judge_call_id"
    }

    /** Inserts [request] as it is; the same row. The caller has the server's handle, so the task id is known. */
    fun save(request: HitlpRequest): HitlpRequest {
        jdbc.sql("""INSERT INTO engine.hitlp_request ($COLUMNS) VALUES (:id, :key, :pi, :utk, :kind, :poll, :created, :next, :record, :judge)""")
            .param("id", request.taskId).param("key", request.idempotencyKey).param("pi", request.processInstance)
            .param("utk", request.userTaskKey).param("kind", request.kind).param("poll", request.pollIntervalMs)
            .param("created", request.createdAt).param("next", request.nextPollAt)
            .param("record", request.record?.let { engineJson.writeValueAsString(it) }).param("judge", request.judgeCallId).update()
        return request
    }

    fun get(taskId: String): HitlpRequest? =
        jdbc.sql("SELECT $COLUMNS FROM engine.hitlp_request WHERE task_id = :id").param("id", taskId).query { rs, _ -> row(rs) }.optional().orElse(null)

    /** The open requests (no record yet) whose `next_poll_at` has passed at [now], earliest first: what the tick polls. */
    fun due(now: OffsetDateTime): List<HitlpRequest> =
        jdbc.sql("SELECT $COLUMNS FROM engine.hitlp_request WHERE record IS NULL AND next_poll_at <= :now ORDER BY next_poll_at, task_id")
            .param("now", now).query { rs, _ -> row(rs) }.list()

    /** Moves [taskId]'s next poll to [now] plus [pollIntervalMs] (the server's interval, §7.4) and keeps the interval; true when the row is open and changed. */
    fun bump(taskId: String, pollIntervalMs: Int, now: OffsetDateTime = OffsetDateTime.now()): Boolean =
        jdbc.sql("UPDATE engine.hitlp_request SET poll_interval_ms = :poll, next_poll_at = :next WHERE task_id = :id AND record IS NULL")
            .param("poll", pollIntervalMs).param("next", now.plusNanos(pollIntervalMs * 1_000_000L)).param("id", taskId).update() == 1

    /** Writes [record] on [taskId] once: true when the row was open and is now finished, false when it was already finished or is unknown. */
    fun finish(taskId: String, record: Map<String, Any?>, judgeCallId: String? = null): Boolean =
        jdbc.sql("UPDATE engine.hitlp_request SET record = :record, judge_call_id = COALESCE(:judge, judge_call_id) WHERE task_id = :id AND record IS NULL")
            .param("record", engineJson.writeValueAsString(record)).param("judge", judgeCallId).param("id", taskId).update() == 1

    /** The judge escalation the engine filed for Jev call [callId] (`judge_call_id`; engine/README.md, "The judge server"), the newest when there are several. */
    fun byJudgeCall(callId: String): HitlpRequest? =
        jdbc.sql("SELECT $COLUMNS FROM engine.hitlp_request WHERE judge_call_id = :j ORDER BY created_at DESC, task_id DESC")
            .param("j", callId).query { rs, _ -> row(rs) }.list().firstOrNull()

    /** The judge escalations of [callIds] by call id, the newest per call. */
    fun byJudgeCalls(callIds: Collection<String>): Map<String, HitlpRequest> {
        if (callIds.isEmpty()) return emptyMap()
        return jdbc.sql("SELECT $COLUMNS FROM engine.hitlp_request WHERE judge_call_id IN (:j) ORDER BY created_at, task_id")
            .param("j", callIds.toList()).query { rs, _ -> row(rs) }.list().associateBy { it.judgeCallId!! }
    }

    /** The open requests of flow [processInstance], oldest first. */
    fun openFor(processInstance: String): List<HitlpRequest> =
        jdbc.sql("SELECT $COLUMNS FROM engine.hitlp_request WHERE process_instance = :pi AND record IS NULL ORDER BY created_at, task_id")
            .param("pi", processInstance).query { rs, _ -> row(rs) }.list()

    private fun row(rs: ResultSet) = HitlpRequest(
        taskId = rs.getString("task_id"), idempotencyKey = rs.getString("idempotency_key"), processInstance = rs.getString("process_instance"),
        userTaskKey = rs.getString("user_task_key"), kind = rs.getString("kind"), pollIntervalMs = rs.getInt("poll_interval_ms"),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java), nextPollAt = rs.getObject("next_poll_at", OffsetDateTime::class.java),
        record = rs.getString("record")?.let { engineJson.readValue(it) }, judgeCallId = rs.getString("judge_call_id"),
    )
}

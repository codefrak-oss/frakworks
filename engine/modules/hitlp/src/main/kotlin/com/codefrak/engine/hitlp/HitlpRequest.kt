package com.codefrak.engine.hitlp

import java.time.OffsetDateTime

/**
 * One row of `engine.hitlp_request` (V81; #2447): a HITLP request the engine filed on the HITLP server as a requester
 * and the handle it holds for it, the durable checkpoint rule R3 asks of an agent that stops and resumes. [taskId] is
 * the server's Task id; [idempotencyKey] the key the engine chose (R4: a retry reuses it); [processInstance] and
 * [userTaskKey] name the flow wait the request stands for; [kind] is the [HitlpAsserted.Kind] name. [nextPollAt] is
 * when the tick next asks `tasks/get` ([pollIntervalMs] after the last); [record] the §6 decision record once the task
 * is terminal, null while open; [judgeCallId] the Jev call that judged the record, when one did (part 2).
 */
data class HitlpRequest(
    val taskId: String,
    val idempotencyKey: String,
    val processInstance: String,
    val userTaskKey: String,
    val kind: String,
    val pollIntervalMs: Int,
    val createdAt: OffsetDateTime,
    val nextPollAt: OffsetDateTime,
    val record: Map<String, Any?>? = null,
    val judgeCallId: String? = null,
) {
    val open: Boolean get() = record == null
}

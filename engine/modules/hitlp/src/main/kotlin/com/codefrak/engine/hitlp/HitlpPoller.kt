package com.codefrak.engine.hitlp

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * What finishes the requests the engine filed (#2458, #2463; `spec/hitlp.md` §7.4). No timer runs it: [HitlpTaskStream]
 * calls [notified] on a terminal `notifications/tasks/status`, [reconcile] on every stream (re)connect, and [poll] in
 * its fallback loop when the server offers no stream. Each [HitlpRequests.due] row is read with `tasks/get`: while open
 * its next poll moves by the server's `pollInterval`; once terminal the record is stored ([HitlpRequests.finish]) and
 * every [HitlpAnswerer] called, in one transaction per row. A failure on one row is a WARN and leaves it open, so the
 * next reconciliation or pass retries it.
 */
@Component
class HitlpPoller(
    private val requester: HitlpRequester,
    private val requests: HitlpRequests,
    private val answerers: List<HitlpAnswerer>,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(HitlpPoller::class.java)

    /** One `tasks/get` per open row, due or not (a stream (re)connect); how many were finished. */
    fun reconcile(): Int = poll(OffsetDateTime.now(ZoneOffset.UTC).plusYears(100))

    /** One pass over the due rows at [now]; how many were finished. */
    fun poll(now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)): Int {
        var finished = 0
        for (row in runCatching { requests.due(now) }.getOrElse { log.warn("hitlp poll failed: {}", it.message); return 0 }) {
            runCatching {
                val handle = requester.get(row.taskId)
                if (!handle.terminal) { requests.bump(row.taskId, handle.pollInterval.toInt(), now); return@runCatching }
                if (finish(row, handle.record ?: handle.result ?: mapOf("status" to handle.status))) finished++
            }.onFailure { log.warn("hitlp poll of task {} ({}) failed: {}", row.taskId, row.idempotencyKey, it.message) }
        }
        return finished
    }

    /**
     * The server said [taskId] reached the terminal [status]: its result is read with `tasks/result` and the row
     * finished as [poll] would. False when the task is not an open row of this engine's, or the finish failed (a WARN;
     * the next reconciliation retries it).
     */
    fun notified(taskId: String, status: String): Boolean {
        if (status !in HitlpPrimitives.TERMINAL) return false
        val row = runCatching { requests.get(taskId) }.getOrNull() ?: return false
        if (!row.open) return false
        return runCatching {
            val result = requester.result(taskId)
            val record = HitlpHandle(taskId, status, 0, null, null, null, result).record ?: result
            finish(row, record)
        }.onFailure { log.warn("hitlp result of task {} ({}) failed: {}", taskId, row.idempotencyKey, it.message) }.getOrDefault(false)
    }

    private fun finish(row: HitlpRequest, record: Map<String, Any?>): Boolean {
        var done = false
        tx.executeWithoutResult {
            if (requests.finish(row.taskId, record)) {
                val finished = row.copy(record = record)
                answerers.forEach { it.complete(finished, record) }
                done = true
            }
        }
        return done
    }
}

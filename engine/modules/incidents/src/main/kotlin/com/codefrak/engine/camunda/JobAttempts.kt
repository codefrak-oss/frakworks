package com.codefrak.engine.camunda

import com.codefrak.engine.at
import org.operaton.bpm.engine.HistoryService
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/** One failed attempt of a job: when, its exception message, and the retries the job had left (the log row's `jobRetries`). */
data class JobAttempt(val at: OffsetDateTime?, val message: String?, val retriesLeft: Int)

/**
 * Every failed attempt of a job, oldest first, from Operaton's historic job log (`history-level=full` keeps a
 * `failureLog` row per attempt, a retry with `retries = 1` on the same job included). The incident's message is the
 * last of them. A job with no failure rows, or one that never existed, answers an empty list.
 */
@Component
class JobAttempts(private val history: HistoryService) {
    fun of(jobId: String?): List<JobAttempt> {
        if (jobId.isNullOrBlank()) return emptyList()
        return history.createHistoricJobLogQuery().jobId(jobId).failureLog()
            .orderByTimestamp().asc().orderPartiallyByOccurrence().asc().list()
            .map { JobAttempt(it.timestamp?.at(), it.jobExceptionMessage, it.jobRetries) }
    }

    /** [of] as the briefs and the diagnostics tool carry it: `at` as an ISO-8601 instant. */
    fun rows(jobId: String?): List<Map<String, Any?>> = of(jobId).map {
        linkedMapOf("at" to it.at?.toInstant()?.toString(), "message" to it.message, "retriesLeft" to it.retriesLeft)
    }
}

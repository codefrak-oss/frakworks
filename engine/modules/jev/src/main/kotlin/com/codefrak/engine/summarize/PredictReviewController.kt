package com.codefrak.engine.summarize

import com.codefrak.engine.CallerIdentity
import com.codefrak.engine.CallerRefusal
import com.codefrak.engine.errorResponse
import com.codefrak.engine.jev.HttpJevDecider
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.jev.JevContext
import com.codefrak.engine.jev.JevDecider
import com.codefrak.engine.jev.JevQuestion
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.workitems.Tickets
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * What `POST /ticket/{owner}/{repo}/{n}/predict-review` answers: `verdict` `approve` or `deny`, `confidence` in 0..1 and
 * `value` its percentage, negated for `deny` (all three null when Jev answered nothing); `lines` the session lines the
 * digest was built from; `fresh` false when the kept prediction was answered without a Jev call.
 */
data class ReviewPrediction(
    val runId: String, val callId: String, val verdict: String?, val confidence: Double?, val value: Int?, val lines: Int, val fresh: Boolean,
)

/**
 * The review prediction (engine/README.md, "Predict a review: Jev on the live review run", #1746): Jev asked whether
 * the ticket's live review run will end `approved`, with the run's session digest as the state. No summarizer, no
 * other model call. The last prediction per run is kept in memory with its line count, so a call over a transcript
 * that has not grown answers it again without a Jev call. Refused to a Smith sandbox, as summarize is.
 */
@RestController
class PredictReviewController(
    private val repos: TargetRepos,
    private val sessions: LiveRunSessions,
    private val jev: JevDecider,
    private val calls: JevCalls,
    private val sandbox: CallerIdentity,
    private val tickets: Tickets,
) {
    private val log = LoggerFactory.getLogger(PredictReviewController::class.java)
    private val kept = ConcurrentHashMap<String, ReviewPrediction>()

    /**
     * 200 [ReviewPrediction]; 404 `{error}` for a repo that is not a target repo; 409 `{error}` when no review run is live
     * on the ticket; 503 when no Jev account is usable (or the caller cannot be told from a Smith); 502 when Jev fails;
     * 403 to a Smith sandbox.
     */
    @PostMapping("/ticket/{owner}/{repo}/{n:\\d+}/predict-review", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun predict(@PathVariable owner: String, @PathVariable repo: String, @PathVariable n: Long, request: HttpServletRequest?): ResponseEntity<Any> {
        CallerRefusal.smithRefusal(sandbox, request, log, "predict a review")?.let { (s, m) -> return errorResponse(s, m) }
        val full = repos.find("$owner/$repo")?.fullName
            ?: return errorResponse(HttpStatus.NOT_FOUND, "$owner/$repo is not a target repo")
        val run = sessions.liveReview(full, n) ?: return errorResponse(HttpStatus.CONFLICT, "no review run is live on $full#$n")
        val (digest, lines) = sessions.counted(run.runId)
        kept[run.runId]?.takeIf { it.lines == lines }?.let { return ResponseEntity.ok(it.copy(fresh = false)) }
        val state = mapOf("ticket" to "$full#$n", "title" to title(full, n), "session" to digest)
        val decision = jev.decide(USE_CASE, JevContext(null, full, n), state, mapOf(QUESTION to QUESTION_ASKED), null)
        val answer = decision.answers?.get(QUESTION)?.takeIf { it.noul != null }
        if (answer == null) {
            val failure = calls.get(decision.callId)?.error
            if (failure == HttpJevDecider.NO_ACCOUNT) return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, failure)
            if (failure != null && (failure.startsWith("no answer:") || failure.startsWith("jev answered"))) {
                log.warn("predict-review {}#{} (run {}): {}", full, n, run.runId, failure.take(200))
                return errorResponse(HttpStatus.BAD_GATEWAY, failure)
            }
        }
        val prediction = predictionOf(run.runId, decision.callId, answer?.noul, answer?.confidence, lines)
        kept[run.runId] = prediction
        return ResponseEntity.ok(prediction)
    }

    /** The ticket's title, or null when it cannot be read: the prediction goes on without it. */
    private fun title(repo: String, n: Long): String? = runCatching { tickets.forTicket(repo, n)?.title ?: error("no ticket $repo#$n") }
        .onFailure { log.warn("predict-review {}#{}: no title: {}", repo, n, it.message) }.getOrNull()

    companion object {
        const val USE_CASE = "review-prediction"
        const val QUESTION = "approved"
        val QUESTION_ASKED = JevQuestion.noul(
            "Will this review run end with outcome `approved` (1) rather than `changes` (0)? The state is the review " +
                "Smith's session so far on the ticket. A review run ends with one of two outcomes: `approved`, the PR " +
                "meets the ticket and may merge, or `changes`, the reviewer asks the work run for changes.",
            whenTrue = "The reviewer will approve: outcome `approved`.",
            whenFalse = "The reviewer will ask for changes: outcome `changes`.",
        )

        /**
         * The prediction from Jev's noul: `approve` at 0.5 or above; the confidence is Jev's own when it gives one, else
         * the noul's distance toward the side it picked (`max(noul, 1 - noul)`); `value` its percentage, signed.
         */
        fun predictionOf(runId: String, callId: String, noul: Double?, confidence: Double?, lines: Int): ReviewPrediction {
            if (noul == null) return ReviewPrediction(runId, callId, null, null, null, lines, true)
            val approve = noul >= 0.5
            val sure = (confidence ?: maxOf(noul, 1 - noul)).coerceIn(0.0, 1.0)
            val magnitude = (sure * 100).roundToInt()
            return ReviewPrediction(runId, callId, if (approve) "approve" else "deny", sure, if (approve) magnitude else -magnitude, lines, true)
        }
    }
}

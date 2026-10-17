package com.codefrak.engine.summarize

import com.codefrak.engine.CallerIdentity
import com.codefrak.engine.CallerRefusal
import com.codefrak.engine.errorResponse
import com.codefrak.engine.repos.TargetRepos
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/** What `POST /ticket/{owner}/{repo}/{n}/summarize` answers. */
data class SummaryView(val runId: String, val mode: String, val model: String, val summary: String, val now: String)

/**
 * The summarize endpoint (engine/README.md, "Summarize: a live run summarized"): the ticket's
 * running Smith's session so far, from `engine.smith_session_line`,
 * summarized by an LLM into the whole session in a few words and what it is
 * doing now, the latter from its last turns ([LiveRunSessions.recent]). A POST: it
 * spends a model call. Refused to a Smith sandbox: a run has no business
 * spending tokens on, or reading, another run's session.
 */
@RestController
class SummarizeController(
    private val repos: TargetRepos,
    private val sessions: LiveRunSessions,
    private val summarizer: SessionSummarizer,
    private val sandbox: CallerIdentity,
) {
    private val log = LoggerFactory.getLogger(SummarizeController::class.java)

    /**
     * 200 [SummaryView]; 404 `{error}` for a repo that is not a target repo; 409 `{error}` when no Smith
     * runs on the ticket; 503 when no account can summarize (or the caller cannot be told from a Smith);
     * 502 when the provider fails; 403 to a Smith sandbox.
     */
    @PostMapping("/ticket/{owner}/{repo}/{n:\\d+}/summarize", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun summarize(@PathVariable owner: String, @PathVariable repo: String, @PathVariable n: Long, request: HttpServletRequest?): ResponseEntity<Any> {
        CallerRefusal.smithRefusal(sandbox, request, log, "summarize a run")?.let { (s, m) -> return errorResponse(s, m) }
        val full = repos.find("$owner/$repo")?.fullName
            ?: return errorResponse(HttpStatus.NOT_FOUND, "$owner/$repo is not a target repo")
        val run = sessions.latest(full, n) ?: return errorResponse(HttpStatus.CONFLICT, "no Smith is running on $full#$n")
        val digest = sessions.digest(run.runId)
        if (digest.isBlank()) return errorResponse(HttpStatus.CONFLICT, "the Smith on $full#$n has written nothing yet")
        val summary = try {
            summarizer.summarize("Ticket $full#$n, a ${run.phase} run on ${run.model}.", digest, sessions.recent(run.runId)).limited()
        } catch (e: SummaryUnavailable) {
            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, e.message ?: "no account to summarize with")
        } catch (e: Exception) {
            log.warn("summarize {}#{} (run {}): {}", full, n, run.runId, e.message)
            return errorResponse(HttpStatus.BAD_GATEWAY, e.message ?: e.javaClass.simpleName)
        }
        return ResponseEntity.ok(SummaryView(run.runId, run.phase, run.model, summary.summary, summary.now))
    }
}

package com.codefrak.engine.board

import com.codefrak.engine.errorResponse
import com.codefrak.engine.github.IssueDetails
import com.codefrak.engine.github.TicketDetail
import com.codefrak.engine.proposal.PitchOption
import com.codefrak.engine.proposal.ProposalPitches
import com.codefrak.engine.research.ResearchAnswers
import com.codefrak.engine.repos.HandledIssues
import com.codefrak.engine.repos.ImplementationSummaries
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.workitems.WorkItemDetails
import com.codefrak.engine.workitems.WorkItemTickets
import com.fasterxml.jackson.annotation.JsonUnwrapped
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/** A [TicketDetail]'s fields, with the repo it is in beside them, and `askedBy`: who asked for it (`engine.handled_issue.asked_by`), null when the engine has not seen it,
 * and `points`: its story points (`engine.handled_issue.points`, bots/story-points.md), null when the engine has not seen it or it has none,
 * and `implementationSummary`: what its merged work changed (`engine.implementation_summary`), null unless it is closed with a merged PR. */
data class TicketView(val repo: String, @get:JsonUnwrapped val ticket: TicketDetail, val askedBy: String? = null, val points: Int? = null,
                      val implementationSummary: String? = null)

/**
 * A proposal's pitch: `kind` `html` (the pitch page as committed) or `markdown` (the doc, rendered by GitHub); or a
 * research ticket's latest answer page (`html`, no `path` since it is kept by the engine, `prUrl` empty, and its `round`).
 */
data class PitchView(val kind: String, val path: String?, val prUrl: String, val content: String, val round: Int? = null,
                     val options: List<PitchOption> = emptyList(), val awaitingChoice: Boolean = false,
                     val awaitingDecision: Boolean = false)

/**
 * Unauthenticated, read-only: one ticket as GitHub has it, with its rendered HTML and the PRs
 * that close it, what the desktop's ticket detail shows, and its comments as REST objects, what
 * the desktop's answer flow reads (engine/README.md). One GitHub call (more for over 100
 * comments) a request, so it is not for polling. A work item (numbered from [WorkItemTickets.FIRST] up) is read from
 * the engine's store instead ([WorkItemDetails]), its PRs by their `smith/issue-<n>` branch.
 */
@RestController
class TicketDetailController(private val details: IssueDetails, private val repos: TargetRepos, private val pitches: Pitches,
                             private val pulled: ProposalPitches, private val research: ResearchAnswers,
                             private val handled: HandledIssues, private val choices: ProposalChoices,
                             private val items: WorkItemDetails, private val summaries: ImplementationSummaries) {
    private val log = LoggerFactory.getLogger(TicketDetailController::class.java)

    /** 404 `{error}` for an issue GitHub does not have or a repo that is not a target repo; 502 `{error}` when GitHub fails. */
    @GetMapping("/ticket/{owner}/{repo}/{n}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun ticket(@PathVariable owner: String, @PathVariable repo: String, @PathVariable n: Long): ResponseEntity<Any> =
        read(owner, repo, "ticket $n", { "no issue $it#$n" }) { full -> (if (n >= WorkItemTickets.FIRST) items.ticket(full, n) else details.ticket(full, n))?.let { TicketView(full, it, handled.askedBy(full, n), handled.points(full, n), summaryOf(full, it)) } }

    /** The stored summary of a ticket completed by merge (closed, a closing PR merged); null for any other. */
    private fun summaryOf(full: String, t: TicketDetail): String? =
        if (t.state == "CLOSED" && t.prs.any { it.state == "MERGED" }) summaries.of(full, t.number) else null

    /**
     * The issue's comments as GitHub's REST API sends them, oldest first, every page: what the
     * desktop's answer flow fingerprints (numeric `id`, `updated_at`, `body`). Same 404/502 as [ticket].
     */
    @GetMapping("/ticket/{owner}/{repo}/{n:\\d+}/comments", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun comments(@PathVariable owner: String, @PathVariable repo: String, @PathVariable n: Long): ResponseEntity<Any> =
        read(owner, repo, "comments of $n", { "no issue $it#$n" }) { full ->
            if (n >= WorkItemTickets.FIRST) items.restComments(full, n) else details.restComments(full, n)
        }

    /** One issue comment by its REST id, as GitHub sends it. Same 404/502 as [ticket]. */
    @GetMapping("/ticket/{owner}/{repo}/comments/{id:\\d+}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun comment(@PathVariable owner: String, @PathVariable repo: String, @PathVariable id: Long): ResponseEntity<Any> =
        read(owner, repo, "comment $id", { "no comment $id in $it" }) { full -> details.restComment(full, id) ?: items.restComment(full, id) }

    /**
     * A proposal ticket's pitch, what the desktop's Pitch view shows: the pitch page its last propose Smith
     * committed, read at its branch or, once that is gone, on the default branch; or, for a proposal with no
     * pitch, its doc rendered by GitHub. With no such record, the one pitch page the ticket's closing PR adds
     * or changes ([ProposalPitches]), from the PR's branch or, once it is merged, the default branch. For a research
     * ticket, its latest answer page from `engine.research_answer` with its `round`, also once the ticket is closed
     * (engine/README.md, "Research"). 404 `{error}` for a ticket with none of these, or a file on neither ref;
     * otherwise the same 404/502 as [ticket]. A drafted proposal's page carries the `options` its result declared and
     * `awaitingChoice`, true while its flow waits on a question or the decision (decided on the HITLP server), and
     * `awaitingDecision`, true when that wait is the decision (the Pitch view's Build / Record buttons).
     */
    @GetMapping("/ticket/{owner}/{repo}/{n:\\d+}/pitch", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun pitch(@PathVariable owner: String, @PathVariable repo: String, @PathVariable n: Long): ResponseEntity<Any> =
        read(owner, repo, "pitch of $n", { "no proposal with a PR, and no research answer, on $it#$n" }) { full ->
            research.latest(full, n)?.let { PitchView("html", null, "", it.html, it.round) } ?: pitches.draft(full, n)?.let { draft ->
                val path = draft.pitch ?: draft.path
                val text = details.fileText(full, path, draft.branch) ?: details.fileText(full, path, null) ?: return@let null
                val wait = choices.waiting(full, n)
                if (draft.pitch != null) PitchView("html", path, draft.prUrl, text, options = wait?.options.orEmpty(),
                    awaitingChoice = wait?.task != null && wait.options.isNotEmpty(),
                    awaitingDecision = wait?.task == ProposalChoices.DECISION && wait.options.isNotEmpty())
                else PitchView("markdown", path, draft.prUrl, details.renderMarkdown(full, text))
            } ?: pulled.page(full, n)?.let { PitchView("html", it.path, it.prUrl, it.html) }
        }

    /** 404 for a repo that is not a target repo or a null read, 502 when GitHub fails. */
    private fun read(owner: String, repo: String, what: String, missing: (String) -> String, fetch: (String) -> Any?): ResponseEntity<Any> {
        val full = repos.find("$owner/$repo")?.fullName
            ?: return errorResponse(HttpStatus.NOT_FOUND, "$owner/$repo is not a target repo")
        val value = try {
            fetch(full)
        } catch (e: Exception) {
            log.warn("{} {}: {}", full, what, e.message)
            return errorResponse(HttpStatus.BAD_GATEWAY, "GitHub: ${e.message}")
        } ?: return errorResponse(HttpStatus.NOT_FOUND, missing(full))
        return ResponseEntity.ok(value)
    }
}

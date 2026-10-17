package com.codefrak.engine.board

import com.codefrak.engine.errorResponse
import com.codefrak.engine.github.IssueDetails
import com.codefrak.engine.repos.TargetRepos
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Unauthenticated, read-only: the proposal docs on each target repo's default branch ([ProposalDocs]), and
 * one doc's page, what the desktop's Proposals branch and proposal view show (engine/README.md, "Proposal").
 */
@RestController
class ProposalsController(private val docs: ProposalDocs, private val details: IssueDetails, private val repos: TargetRepos) {
    private val log = LoggerFactory.getLogger(ProposalsController::class.java)

    /**
     * `repo`'s docs, or every target repo's when absent: `{docs, errors}`. 404 `{error}` for a `repo` that is
     * not a target repo, 502 `{error}` when GitHub fails for it; in All repos a failing repo is one of `errors`.
     */
    @GetMapping("/proposals", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun proposals(@RequestParam(required = false) repo: String?): ResponseEntity<Any> {
        if (repo != null) {
            val full = repos.find(repo)?.fullName ?: return errorResponse(HttpStatus.NOT_FOUND, "$repo is not a target repo")
            val found = try { docs.list(full) } catch (e: Exception) {
                log.warn("{} proposals: {}", full, e.message)
                return errorResponse(HttpStatus.BAD_GATEWAY, "GitHub: ${e.message}")
            }
            return ResponseEntity.ok(ProposalListing(found, emptyList()))
        }
        val all = mutableListOf<ProposalDoc>()
        val errors = mutableListOf<Map<String, String>>()
        for (r in repos.list()) {
            try { all += docs.list(r.fullName) } catch (e: Exception) {
                log.warn("{} proposals: {}", r.fullName, e.message)
                errors += mapOf("repo" to r.fullName, "error" to "GitHub: ${e.message}")
            }
        }
        return ResponseEntity.ok(ProposalListing(all, errors))
    }

    /**
     * One doc's page from the default branch, in `GET /ticket/.../pitch`'s shape with an empty `prUrl`: its pitch
     * page `docs/proposals/<slug>.html` as `html`, else `<slug>.md` rendered by GitHub as `markdown`. For a doc
     * with no ticket to read the pitch through. 404 `{error}` for a repo that is not a target repo, a bad slug or
     * no such doc; 502 `{error}` when GitHub fails.
     */
    @GetMapping("/proposals/{owner}/{repo}/{slug}/page", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun page(@PathVariable owner: String, @PathVariable repo: String, @PathVariable slug: String): ResponseEntity<Any> {
        val full = repos.find("$owner/$repo")?.fullName ?: return errorResponse(HttpStatus.NOT_FOUND, "$owner/$repo is not a target repo")
        if (!ProposalDocs.SLUG.matches(slug)) return errorResponse(HttpStatus.NOT_FOUND, "no proposal $slug in $full")
        val html = "${ProposalDocs.DIR}/$slug.html"
        val md = "${ProposalDocs.DIR}/$slug.md"
        val view = try {
            details.fileText(full, html, null)?.let { PitchView("html", html, "", it) }
                ?: details.fileText(full, md, null)?.let { PitchView("markdown", md, "", details.renderMarkdown(full, it)) }
        } catch (e: Exception) {
            log.warn("{} proposal page {}: {}", full, slug, e.message)
            return errorResponse(HttpStatus.BAD_GATEWAY, "GitHub: ${e.message}")
        } ?: return errorResponse(HttpStatus.NOT_FOUND, "no proposal $slug in $full")
        return ResponseEntity.ok(view)
    }
}

package com.codefrak.engine.board

import com.codefrak.engine.errorResponse
import com.codefrak.engine.feature.DesignDeck
import com.codefrak.engine.feature.DesignResult
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.Vars
import org.operaton.bpm.engine.HistoryService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * The design result a confirm-mode feature flow on a ticket put to the user (`Vars.DESIGN_DECK`), its newest flow
 * first, running or ended; null when no feature flow on it ever did (an auto-mode feature, or any other flow).
 */
@Component
class DesignDecks(private val history: HistoryService) {
    fun result(repo: String, issue: Long): DesignResult? =
        history.createHistoricProcessInstanceQuery().processDefinitionKey(FEATURE)
            .variableValueEquals(Vars.REPO, repo).variableValueEquals(Vars.ISSUE, issue)
            .orderByProcessInstanceStartTime().desc().list().firstNotNullOfOrNull { flow ->
                (history.createHistoricVariableInstanceQuery().processInstanceId(flow.id).variableName(Vars.DESIGN_DECK)
                    .singleResult()?.value as? ByteArray)?.let { runCatching { DesignResult.parse(it) }.getOrNull() }
            }

    private companion object { const val FEATURE = "feature" }
}

/** The deck as the desktop reads it: one self-contained, scriptless HTML page. */
data class DesignDeckView(val html: String)

/**
 * `GET /ticket/{owner}/{repo}/{n}/design-deck`: a confirm-mode feature's design proposal as a dark slide deck
 * (engine/docs/feature.md, "The design deck"), rendered from the stored design result. 404 `{error}` for a repo that
 * is not a target repo, or a ticket with no confirm design that went to the user.
 */
@RestController
class DesignDeckController(private val decks: DesignDecks, private val repos: TargetRepos) {
    @GetMapping("/ticket/{owner}/{repo}/{n:\\d+}/design-deck", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun deck(@PathVariable owner: String, @PathVariable repo: String, @PathVariable n: Long): ResponseEntity<Any> {
        val full = repos.find("$owner/$repo")?.fullName ?: return errorResponse(HttpStatus.NOT_FOUND, "$owner/$repo is not a target repo")
        val result = decks.result(full, n) ?: return errorResponse(HttpStatus.NOT_FOUND, "no confirm-mode design proposal on $full#$n")
        return ResponseEntity.ok(DesignDeckView(DesignDeck.render(result, full, n)))
    }
}

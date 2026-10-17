package com.codefrak.engine.repos

import com.codefrak.engine.board.targetRepoOr404
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.smith.SmithRefusal.refuseSmith
import com.codefrak.engine.smith.SmithSandbox
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/** `POST /repos/setup/check`: `repo` a target repo's full name or `*` for every one; `force` re-reads GitHub inside [RepoSetupChecks.FRESH_FOR]. */
data class SetupCheckRequest(val repo: String? = null, val force: Boolean = false)

/** The 202's body: `queued` the repos a background check was started or already running for, `fresh` those whose kept report was re-published. */
data class SetupCheckQueued(val queued: List<String>, val fresh: List<String>)

/**
 * `POST /repos/setup/check` (engine/README.md, "A new target repo's GitHub setup"): 202 before any GitHub read. A body
 * without `repo` is 400 naming the target repos (there is no default repo); one that is not a target repo is 404.
 * A Smith sandbox, told by its source address, is 403 (503 when Docker cannot say), as `TargetRepoController` refuses.
 */
@RestController
class RepoSetupChecksController(private val repos: TargetRepos, private val checks: RepoSetupChecks, private val sandbox: SmithSandbox) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/repos/setup/check")
    fun check(@RequestBody(required = false) body: SetupCheckRequest?, request: HttpServletRequest? = null): ResponseEntity<SetupCheckQueued> {
        refuseSmith(sandbox, request, log, "ask for a setup check")
        val repo = body?.repo ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST,
            "repo is required: a target repo (${repos.list().joinToString(", ") { it.fullName }.ifEmpty { "none yet" }}) or * for every repo")
        val rows = if (repo == EngineEvent.FLEET) repos.list()
            else listOf(targetRepoOr404(repos, repo))
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(checks.request(rows, body.force))
    }

}

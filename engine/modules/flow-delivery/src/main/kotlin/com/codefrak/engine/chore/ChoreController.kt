package com.codefrak.engine.chore

import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.workitems.Tickets
import com.codefrak.engine.workitems.snapshot
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

data class ChoreRequest(val repo: String, val issue: Long, val model: String? = null, val effort: String? = null)
data class ChoreStarted(val choreId: String)

/**
 * Unauthenticated until the API is closed down: starts `chore`
 * (bpmn/chore.bpmn) on an issue by hand, with the issue as it is now as the
 * approved ticket. The usual way in is a `chore` label, which the scan checks
 * (engine/README.md, "Starting a flow from an issue"). Only on a target repo
 * (`POST /repos`). Read it back with GET /intake/{choreId}.
 */
@RestController
class ChoreController(private val repos: TargetRepos, private val tickets: Tickets, private val starter: FlowStarter) {
    @PostMapping("/chore")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun start(@RequestBody body: ChoreRequest): ChoreStarted {
        if (!repos.contains(body.repo)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "${body.repo} is not a target repo; POST /repos first")
        val started = starter.start("chore", body.repo, tickets.snapshot(body.repo, body.issue), "by hand (POST /chore)", body.model, body.effort)
        return ChoreStarted(started ?: error("the chore did not start"))
    }
}

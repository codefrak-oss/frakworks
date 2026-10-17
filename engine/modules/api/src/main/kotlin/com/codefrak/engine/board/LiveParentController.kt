package com.codefrak.engine.board

import com.codefrak.engine.feature.LIVE_FALLBACK
import com.codefrak.engine.feature.LIVE_RESTART_BY
import com.codefrak.engine.refuseBy
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.SmithRefusal
import com.codefrak.engine.smith.SmithSandbox
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.tokenLogin
import jakarta.servlet.http.HttpServletRequest
import org.operaton.bpm.engine.RuntimeService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/** Empty: the restarter is the bearer token's login, which must be trusted; a `by` is 400. */
data class LiveParentRequest(val by: String? = null)

/** The feature flow the live parent was restarted on, and the login that restarted it. */
data class LiveParentRestarted(val repo: String, val issue: Long, val instanceId: String, val by: String)

/**
 * Restarts the live parent of a split feature that fell back to one child at a time (docs/feature.md, "Live parent",
 * #2572): correlates `restart live parent` on its flow parked at `child closed`. 404 when no feature flow runs on the
 * ticket, 409 (nothing changed) unless its `childMode` is `live`, `liveFallback` is set and it waits at `child closed`.
 */
@Component
class LiveParentRestarter(private val runtime: RuntimeService, private val repos: TargetRepos) {
    fun restart(repo: String, issue: Long, by: String): LiveParentRestarted {
        if (!repos.contains(repo)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "$repo is not a target repo")
        val flows = runtime.createProcessInstanceQuery().processDefinitionKey("feature")
            .variableValueEquals(Vars.REPO, repo).variableValueEquals(Vars.ISSUE, issue).list()
        val flow = flows.singleOrNull() ?: throw ResponseStatusException(
            if (flows.isEmpty()) HttpStatus.NOT_FOUND else HttpStatus.CONFLICT,
            if (flows.isEmpty()) "no feature flow runs on $repo#$issue" else "${flows.size} feature flows run on $repo#$issue")
        val mode = runtime.getVariable(flow.id, Vars.CHILD_MODE)
        if (mode != "live") conflict("$repo#$issue is not a live split (childMode ${mode ?: "unset"})")
        if (runtime.getVariable(flow.id, LIVE_FALLBACK) != true) conflict("$repo#$issue has not fallen back: its live parent was never ended early")
        if (CHILD_CLOSED !in runtime.getActiveActivityIds(flow.id)) conflict("$repo#$issue is not parked at child closed")
        val correlated = runtime.createMessageCorrelation(MESSAGE).processInstanceId(flow.id)
            .setVariable(LIVE_RESTART_BY, by).correlateAllWithResult()
        if (correlated.isEmpty()) conflict("$repo#$issue no longer waits at child closed")
        return LiveParentRestarted(repo, issue, flow.id, by)
    }

    private fun conflict(why: String): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, why)

    companion object {
        const val MESSAGE = "restart live parent"
        const val CHILD_CLOSED = "event-child-closed"
    }
}

/** `POST /ticket/{owner}/{repo}/{n}/live-parent`: [LiveParentRestarter.restart] for a trusted login; never a Smith. */
@RestController
class LiveParentController(private val restarter: LiveParentRestarter, private val access: Access, private val sandbox: SmithSandbox) {
    private val log = LoggerFactory.getLogger(LiveParentController::class.java)

    @PostMapping("/ticket/{owner}/{repo}/{n:\\d+}/live-parent")
    fun restart(@PathVariable owner: String, @PathVariable repo: String, @PathVariable n: Long,
                @RequestBody(required = false) body: LiveParentRequest? = null, request: HttpServletRequest? = null): LiveParentRestarted {
        SmithRefusal.refuseSmith(sandbox, request, log, "restart a live parent")
        refuseBy(body?.by)
        val by = request.tokenLogin()
        if (!access.trusted(by)) throw ResponseStatusException(HttpStatus.FORBIDDEN, "@$by is not a trusted login")
        return restarter.restart("$owner/$repo", n, by)
    }
}

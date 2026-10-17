package com.codefrak.engine.board

import com.codefrak.engine.smith.SmithRefusal
import com.codefrak.engine.smith.SmithSandbox
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.Logger
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * A Smith sandbox (by its source address, engine/README.md, "The frakworks server in a Smith") reads its own
 * ticket's repo only: `null` to go on, [HttpStatus.FORBIDDEN] when its repo is not [repo], or
 * [HttpStatus.SERVICE_UNAVAILABLE] when the sandbox provider cannot say who is calling. Never taken for a
 * request from outside a sandbox.
 */
fun smithRepoRefusal(sandbox: SmithSandbox, request: HttpServletRequest?, repo: String, log: Logger): Pair<HttpStatus, String>? {
    val smith = try { SmithRefusal.smithAt(sandbox, request, log) } catch (e: ResponseStatusException) {
        return HttpStatus.valueOf(e.statusCode.value()) to (e.reason ?: "")
    } ?: return null
    if (smith.repo?.equals(repo, ignoreCase = true) == true) return null
    return HttpStatus.FORBIDDEN to "a Smith sandbox (run ${smith.runId}) reads its own repo's files only"
}

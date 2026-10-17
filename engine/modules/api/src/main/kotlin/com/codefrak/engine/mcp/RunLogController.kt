package com.codefrak.engine.mcp

import com.codefrak.engine.smith.SmithSandbox
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * `GET /runs/{id}/log` (#1634): read_run_log ([RunLogTool]) over HTTP, for the calling Smith, told by its source
 * address as [EngineMcpController] tells it. Plain text; a caller that is no Smith sandbox is refused (403).
 */
@RestController
class RunLogController(engine: FrakworksEngine, private val sandbox: SmithSandbox) {
    private val tool = RunLogTool(engine)

    @GetMapping("/runs/{id}/log")
    fun log(@PathVariable id: String, request: HttpServletRequest): ResponseEntity<String> {
        val caller = try { sandbox.runAt(request.remoteAddr) } catch (e: Exception) {
            return text(503, "cannot tell whether the caller is a Smith sandbox: ${e.message}")
        } ?: return text(403, "only a Smith sandbox reads a run's log here")
        return when (val r = tool.read(id, caller.runId, caller.repo, caller.issue)) {
            is RunLogTool.Read.Log -> text(200, r.text)
            is RunLogTool.Read.Refused -> text(r.status, r.why)
        }
    }

    private fun text(status: Int, body: String) = ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body(body)
}

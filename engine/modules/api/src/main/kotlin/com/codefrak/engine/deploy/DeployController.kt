package com.codefrak.engine.deploy

import com.codefrak.engine.badRequest
import com.codefrak.engine.refuseUnknownFields
import com.codefrak.engine.smith.SmithRefusal.refuseSmith
import com.codefrak.engine.smith.SmithSandbox
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Tells the engine of a deploy (#100008; engine/README.md, "Deploys"): `POST /repos/{owner}/{name}/deploys
 * {sha, source?}` records the row ([DeployEvents.post]: the `deploy` frame and every [DeployWaits] follow), 201 with
 * the row; 404 for a repo that is not a target repo; 400 without a 7-40 hex `sha` or with an unknown field. `GET
 * .../deploys?limit=` lists the last rows, newest first (`limit` 1 to 500, default 20). Both behind the bearer token,
 * as every call is, and 403 from a Smith sandbox (by its source address, as every write under `/repos/`): a Smith must not
 * mark its own merge deployed, nor read a repo's deploy history.
 */
@RestController
class DeployController(private val deploys: DeployEvents, private val sandbox: SmithSandbox) {
    private val log = LoggerFactory.getLogger(DeployController::class.java)

    @PostMapping("/repos/{owner}/{name}/deploys")
    fun post(@PathVariable owner: String, @PathVariable name: String, @RequestBody body: Any?, request: HttpServletRequest?): ResponseEntity<DeployEvent> {
        refuseSmith(sandbox, request, log, "record a repo's deploy")
        val repo = deploys.repo("$owner/$name")
        if (body !is Map<*, *>) throw badRequest("the body is an object: {sha, source?}")
        refuseUnknownFields(body.keys.map { it.toString() }.sorted(), setOf("sha", "source"), "sha, source")
        val sha = (body["sha"] as? String)?.trim()?.takeIf { DeployEvents.SHA.matches(it) } ?: throw badRequest(DeployEvents.SHA_400)
        val source = body["source"]?.let { it as? String ?: throw badRequest("source is a string: who deployed (a workflow, a person, a tool)") }
        return ResponseEntity.status(HttpStatus.CREATED).body(deploys.post(repo, sha, source))
    }

    @GetMapping("/repos/{owner}/{name}/deploys")
    fun list(@PathVariable owner: String, @PathVariable name: String, @RequestParam(required = false) limit: Int?, request: HttpServletRequest?): List<DeployEvent> {
        refuseSmith(sandbox, request, log, "read a repo's deploys")
        val repo = deploys.repo("$owner/$name")
        val n = limit ?: 20
        if (n !in 1..500) throw badRequest("limit is 1 to 500")
        return deploys.list(repo, n)
    }
}

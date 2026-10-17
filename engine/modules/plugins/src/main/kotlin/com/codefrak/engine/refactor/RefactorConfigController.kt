package com.codefrak.engine.refactor

import com.codefrak.engine.provenLogin
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.smith.SmithRefusal.refuseSmith
import com.codefrak.engine.smith.SmithSandbox
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode

/**
 * `GET`/`PUT /repos/{owner}/{name}/refactor` (engine/README.md, "Refactoring jobs"): a repo's refactoring config, by
 * [RefactorConfigs]. The read is open like every read. The write is guarded as `PATCH /repos/{owner}/{name}` is: a
 * Smith sandbox is refused by its source address (403; 503 when Docker cannot be asked; enabling starts work in its own
 * repo), any other caller may, and its bearer token's login is recorded as `updatedBy`. A write puts
 * a `repo` frame `changed` with field `refactor` on the stream.
 */
@RestController
class RefactorConfigController(private val configs: RefactorConfigs, private val sandbox: SmithSandbox, private val events: EngineEvents) {
    private val log = LoggerFactory.getLogger(RefactorConfigController::class.java)

    @GetMapping("/repos/{owner}/{name}/refactor")
    fun get(@PathVariable owner: String, @PathVariable name: String): RefactorConfigView = configs.get("$owner/$name").view()

    @PutMapping("/repos/{owner}/{name}/refactor")
    fun set(@PathVariable owner: String, @PathVariable name: String, @RequestBody body: JsonNode, request: HttpServletRequest?): RefactorConfigView {
        refuseSmith(sandbox, request, log, "set a repo's refactoring")
        val repo = "$owner/$name"
        val config = configs.set(repo, body, request?.provenLogin())
        events.publish(EngineEvent.Repo(repo, "changed", listOf("refactor")))
        return config.view()
    }
}

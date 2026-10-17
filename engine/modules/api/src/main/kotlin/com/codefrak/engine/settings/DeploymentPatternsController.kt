package com.codefrak.engine.settings

import com.codefrak.engine.badRequest
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.github.DeploymentPatterns
import com.codefrak.engine.provenLogin
import com.codefrak.engine.smith.SmithRefusal.refuseSmith
import com.codefrak.engine.smith.SmithSandbox
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * `GET`/`PUT`/`DELETE /settings/deployment-patterns` (#100115; engine/README.md, "Deployment patterns"): the patterns
 * every Smith push is checked against, sealed in [DeploymentPatterns]. No answer ever carries a value: `GET` says only
 * whether and when each is set. A Smith sandbox and a caller with no proven login may not write (403). A write that
 * changed something publishes one `setting` frame named `engine.deployment-patterns`, `{changed: [field names]}`.
 */
@RestController
class DeploymentPatternsController(private val settings: DeploymentPatterns, private val events: EngineEvents, private val sandbox: SmithSandbox) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        val FIELDS = setOf("patterns", "allow")
    }

    /** `{patterns: {set, setAt}, allow: {set, setAt}}`. */
    @GetMapping("/settings/deployment-patterns")
    fun get(): Map<String, Any?> {
        val v = settings.get()
        return linkedMapOf("patterns" to mapOf("set" to (v.patternsSetAt != null), "setAt" to v.patternsSetAt),
            "allow" to mapOf("set" to (v.allowSetAt != null), "setAt" to v.allowSetAt))
    }

    /**
     * Body: `{patterns, allow}`, each a string (one per line) or null; both replace what is set, an absent, null or blank
     * one clearing it, so `{}` clears both. Each pattern must compile as a regex (a 400 names its line number only).
     */
    @PutMapping("/settings/deployment-patterns")
    fun set(@RequestBody body: Map<String, Any?>, request: HttpServletRequest? = null): Map<String, Any?> {
        if (!FIELDS.containsAll(body.keys)) throw badRequest("the body is {\"patterns\": <string or null>, \"allow\": <string or null>}")
        val patterns = text(body, "patterns")
        val allow = text(body, "allow")
        patterns?.lines()?.forEachIndexed { i, line ->
            if (line.isNotBlank() && !line.trimStart().startsWith("#"))
                runCatching { Regex(line.trimEnd('\r')) }.onFailure { throw badRequest("patterns line ${i + 1} is not a regex") }
        }
        return write(patterns, allow, request)
    }

    @DeleteMapping("/settings/deployment-patterns")
    fun clear(request: HttpServletRequest? = null): Map<String, Any?> = write(null, null, request)

    private fun write(patterns: String?, allow: String?, request: HttpServletRequest?): Map<String, Any?> {
        refuseSmith(sandbox, request, log, "set the deployment patterns")
        request?.provenLogin() ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "a login is needed to set the deployment patterns: a bearer token the engine issued")
        val before = settings.get()
        settings.set(patterns, allow)
        val changed = listOfNotNull(
            "patterns".takeIf { !patterns.isNullOrBlank() || before.patternsSetAt != null },
            "allow".takeIf { !allow.isNullOrBlank() || before.allowSetAt != null })
        if (changed.isNotEmpty()) events.publish(EngineEvent.Setting(DeploymentPatterns.EVENT_NAME, mapOf("changed" to changed)))
        return get()
    }

    private fun text(body: Map<String, Any?>, field: String): String? = when (val raw = body[field]) {
        null -> null
        is String -> raw
        else -> throw badRequest("$field is a string or null")
    }
}

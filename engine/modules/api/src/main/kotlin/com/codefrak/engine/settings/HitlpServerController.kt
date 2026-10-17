package com.codefrak.engine.settings

import com.codefrak.engine.badRequest
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.hitlp.HitlpServerSettings
import com.codefrak.engine.hitlp.HitlpServerSettings.Field
import com.codefrak.engine.provenLogin
import com.codefrak.engine.smith.SmithRefusal.refuseSmith
import com.codefrak.engine.smith.SmithSandbox
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * `GET`/`PUT /settings/hitlp` (#2500, part 1 of #2498; the surface URL and credentials went with the relay, #100013;
 * engine/README.md, "The HITLP requester"): the HITLP server the engine talks to, every value in [HitlpServerSettings],
 * never a secret in a reply. A Smith sandbox (403, by its address) and a caller whose bearer token proves no login
 * (403) may not write: a Smith could point the fleet's flow waits at its own server. Each write that changed something
 * publishes one `setting` frame named `engine.hitlp` whose value is `{changed: [field names]}`, no values.
 */
@RestController
class HitlpServerController(private val settings: HitlpServerSettings, private val events: EngineEvents, private val sandbox: SmithSandbox) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        val FIELDS = setOf("mcpUrl", "requesterToken")
    }

    /** `{mcpUrl, requesterToken: {set, setAt}}`. */
    @GetMapping("/settings/hitlp")
    fun get(): Map<String, Any?> = view()

    private fun view(): Map<String, Any?> {
        val v = settings.get()
        return linkedMapOf("mcpUrl" to v.mcpUrl,
            "requesterToken" to mapOf("set" to (v.requesterTokenSetAt != null), "setAt" to v.requesterTokenSetAt))
    }

    /**
     * Body: any subset of `mcpUrl` (an absolute http(s) URL with a host, trimmed, a trailing `/`
     * dropped; null clears) and `requesterToken` (a non-blank string; null clears). Another key, an empty body or a wrong
     * type is a 400 and changes nothing. Answers the view, as `GET` does.
     */
    @PutMapping("/settings/hitlp")
    fun set(@RequestBody body: Map<String, Any?>, request: HttpServletRequest? = null): Map<String, Any?> {
        if (body.isEmpty() || !FIELDS.containsAll(body.keys))
            throw badRequest("the body is any of {\"mcpUrl\": <absolute http(s) URL or null>, \"requesterToken\": <string or null>}")
        val mcpUrl = urlField(body, "mcpUrl")
        val token: Field<String?> = if ("requesterToken" !in body) Field.Unset else when (val raw = body["requesterToken"]) {
            null -> Field.Set(null)
            is String -> if (raw.isBlank()) throw badRequest("requesterToken is a non-blank string or null") else Field.Set(raw)
            else -> throw badRequest("requesterToken is a string or null")
        }
        refuse(request, "set the HITLP server")
        val before = settings.get()
        settings.set(mcpUrl, token)
        val changed = listOfNotNull(
            "mcpUrl".takeIf { mcpUrl is Field.Set && mcpUrl.value != before.mcpUrl },
            // A token has no readable old value: setting one is a change; clearing one that was set is a change.
            "requesterToken".takeIf { token is Field.Set && (token.value != null || before.requesterTokenSetAt != null) })
        publish(changed)
        return view()
    }

    private fun urlField(body: Map<String, Any?>, field: String): Field<String?> {
        if (field !in body) return Field.Unset
        return when (val raw = body[field]) {
            null -> Field.Set(null)
            is String -> Field.Set(raw.trim().trimEnd('/').also { u -> PublicUrl.problem(u, field)?.let { throw badRequest(it) } })
            else -> throw badRequest("$field is a string or null")
        }
    }

    /** 403 for a Smith sandbox and for a caller with no proven login. */
    private fun refuse(request: HttpServletRequest?, what: String) {
        refuseSmith(sandbox, request, log, what)
        request?.provenLogin() ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "a login is needed to $what: a bearer token the engine issued")
    }

    private fun publish(changed: List<String>) {
        if (changed.isNotEmpty()) events.publish(EngineEvent.Setting(HitlpServerSettings.EVENT_NAME, mapOf("changed" to changed)))
    }
}

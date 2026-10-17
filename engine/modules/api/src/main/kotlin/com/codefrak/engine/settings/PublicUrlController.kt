package com.codefrak.engine.settings

import com.codefrak.engine.badRequest
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * `GET`/`PUT /settings/public-url` (engine/README.md, "The public URL"; #2253): `{publicUrl}`, null while unset.
 * Behind the bearer token like the rest of the engine's API (`BearerTokenFilter`).
 */
@RestController
class PublicUrlController(private val store: PublicUrl, private val events: EngineEvents) {
    @GetMapping("/settings/public-url")
    fun get(): Map<String, String?> = mapOf("publicUrl" to store.get())

    /**
     * Body: `{"publicUrl": "<absolute http(s) URL>"}`, or `{"publicUrl": null}` to clear it. A trailing `/` is
     * dropped. Anything else (another key, a non-string, a relative or non-http(s) URL) is a 400 and changes nothing.
     * A change publishes one `setting` frame named `engine.public-url`.
     */
    @PutMapping("/settings/public-url")
    fun set(@RequestBody body: Map<String, Any?>): Map<String, String?> {
        if (body.keys != setOf("publicUrl")) throw badRequest("the body is {\"publicUrl\": <absolute http(s) URL or null>}")
        val raw = body["publicUrl"]
        val url = when (raw) {
            null -> null
            is String -> raw.trim().trimEnd('/').also { u -> PublicUrl.problem(u)?.let { throw badRequest(it) } }
            else -> throw badRequest("publicUrl is a string or null")
        }
        val current = store.get()
        store.set(url)
        if (url != current) events.publish(EngineEvent.Setting(PublicUrl.NAME, url))
        return mapOf("publicUrl" to url)
    }
}

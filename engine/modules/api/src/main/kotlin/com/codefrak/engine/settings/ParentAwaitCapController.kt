package com.codefrak.engine.settings

import com.codefrak.engine.badRequest
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * `GET`/`PUT /settings/parent-await-cap` (engine/README.md, "Live parent"; #2571): `{seconds}`, how long await_children
 * waits before `{changed: false}`. Behind the bearer token like the rest of the engine's API (`BearerTokenFilter`).
 */
@RestController
class ParentAwaitCapController(private val store: ParentAwaitCap, private val events: EngineEvents) {
    @GetMapping("/settings/parent-await-cap")
    fun get(): Map<String, Long> = mapOf("seconds" to store.get().seconds)

    /** Body: `{"seconds": 60..3600}`; anything else is a 400 and changes nothing. A change publishes one `setting` frame. */
    @PutMapping("/settings/parent-await-cap")
    fun set(@RequestBody body: Map<String, Any?>): Map<String, Long> {
        val raw = body["seconds"]
        if (body.keys != setOf("seconds") || raw !is Number || raw.toDouble() != raw.toLong().toDouble())
            throw badRequest("the body is {\"seconds\": <whole seconds, ${ParentAwaitCap.RANGE.first} to ${ParentAwaitCap.RANGE.last}>}")
        val seconds = raw.toLong()
        ParentAwaitCap.problem(seconds)?.let { throw badRequest(it) }
        val current = store.get().seconds
        store.set(seconds)
        if (seconds != current) events.publish(EngineEvent.Setting(ParentAwaitCap.NAME, seconds))
        return mapOf("seconds" to seconds)
    }
}

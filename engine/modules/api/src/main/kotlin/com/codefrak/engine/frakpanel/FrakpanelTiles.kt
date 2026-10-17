package com.codefrak.engine.frakpanel

import com.codefrak.engine.engineSetting
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.upsertEngineSetting
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Whether the engine hosts frakpanel tiles (engine/README.md, "frakpanel tiles"), row
 * `frakpanel.tiles` of `engine.engine_setting`. V52 seeds it with false; while false
 * every tile URL answers 404.
 */
@Repository
class FrakpanelTiles(private val jdbc: JdbcClient) {
    fun enabled(): Boolean = jdbc.engineSetting(NAME) == "true"

    fun set(enabled: Boolean) = jdbc.upsertEngineSetting(NAME, enabled.toString())

    companion object {
        const val NAME = "frakpanel.tiles"
    }
}

data class FrakpanelTilesView(val enabled: Boolean)

/** Unauthenticated, like the rest of the engine's API: the frakpanel tiles switch. */
@RestController
class FrakpanelTilesController(private val store: FrakpanelTiles, private val events: EngineEvents) {
    @GetMapping("/frakpanel/tiles/enabled")
    fun get() = FrakpanelTilesView(store.enabled())

    /** Body: `{"enabled": true|false}`; anything else is a 400 and leaves the setting as it was. */
    @PutMapping("/frakpanel/tiles/enabled")
    fun set(@RequestBody body: Map<String, Any?>): FrakpanelTilesView {
        val enabled = body["enabled"]
        if (body.keys != setOf("enabled") || enabled !is Boolean)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "the body is {\"enabled\": true|false}")
        store.set(enabled)
        events.publish(EngineEvent.Setting(FrakpanelTiles.NAME, enabled))
        return FrakpanelTilesView(enabled)
    }
}

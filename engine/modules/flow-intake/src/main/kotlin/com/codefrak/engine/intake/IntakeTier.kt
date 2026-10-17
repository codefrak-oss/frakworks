package com.codefrak.engine.intake

import com.codefrak.engine.badRequest
import com.codefrak.engine.engineSetting
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.tier.TierTable
import com.codefrak.engine.upsertEngineSetting
import com.codefrak.engine.tier.intFrom
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * The intake refiner's own tier (engine/README.md, "Intake"), row `intake.tier` of
 * `engine.engine_setting`: every `refine` run resolves its model and effort from it
 * when it starts (`StartSmithDelegate`). V21 seeds it with 5.
 */
@Repository
class IntakeTier(private val jdbc: JdbcClient) {
    fun get(): Int = jdbc.engineSetting(NAME)?.toInt()
        ?: throw IllegalStateException("no $NAME row in engine.engine_setting (V21 seeds it)")

    fun set(tier: Int) = jdbc.upsertEngineSetting(NAME, tier.toString())

    companion object {
        const val NAME = "intake.tier"
    }
}

/** The refiner's tier and what its row of the tier map (`TierTable`) makes of it. */
data class IntakeTierView(val tier: Int, val model: String, val effort: String)

/** Unauthenticated, like the rest of the engine's API: the intake refiner's tier. */
@RestController
class IntakeTierController(private val store: IntakeTier, private val tiers: TierTable, private val events: EngineEvents) {
    @GetMapping("/intake/tier")
    fun get(): IntakeTierView = view(store.get())

    /**
     * Body: `{"tier": n}`, a JSON integer 1-10, a tier of the map (`TierTable`).
     * Anything else (a string, a fraction, a tier outside 1-10, another key) is a 400 and leaves the stored tier as it was.
     */
    @PutMapping("/intake/tier")
    fun set(@RequestBody body: Map<String, Any?>): IntakeTierView {
        if (body.keys != setOf("tier")) throw badRequest("the body is {\"tier\": n}")
        val tier = intFrom(body, "tier") ?: throw badRequest("tier is an integer")
        if (!tiers.maps(tier)) throw badRequest("tier $tier is not 1-10")
        store.set(tier)
        events.publish(EngineEvent.Setting(IntakeTier.NAME, tier))
        return view(tier)
    }

    private fun view(tier: Int) = tiers.resolve(tier).let { IntakeTierView(tier, it.model, it.effort) }
}

package com.codefrak.engine.tier

import com.codefrak.engine.badRequest
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * Unauthenticated, like the rest of the engine's API: the tier map (engine/README.md, "Smith runs").
 * Always ten tiers: a tier's model and effort can be changed, a tier cannot be added or removed.
 * Each write publishes a fleet-wide `setting` event (`tiers` with the row, `security.tier` or `check.tier` with the tier).
 */
@RestController
class TierController(private val tiers: TierTable, private val events: EngineEvents, private val accounts: TierAccounts) {
    @GetMapping("/tiers")
    fun all(): List<TierRow> = tiers.all()

    /** Each tier's account right now ([TierAccounts]): a peek, never a pick, so the rotation is untouched. */
    @GetMapping("/tiers/accounts")
    fun accounts(): List<TierAccountRow> = accounts.rows()

    /**
     * Body: `{"model": m, "effort": e}`, the model a short name (`opus`) or its full id.
     * A tier outside 1-10, an unknown model, an effort the model does not take, a missing or blank
     * field or another key is a 400 with the reason, and leaves the row as it was.
     */
    @PutMapping("/tiers/{tier}")
    fun set(@PathVariable tier: String, @RequestBody body: Map<String, Any?>): TierRow {
        val n = tier.toIntOrNull() ?: throw badRequest("tier $tier is not 1-10")
        if (!body.keys.all { it == "model" || it == "effort" }) throw badRequest("the body is {\"model\": m, \"effort\": e}")
        val model = body["model"]?.let { it as? String ?: throw badRequest("model is a string") }
        val effort = body["effort"]?.let { it as? String ?: throw badRequest("effort is a string") }
        val row = refused { tiers.set(n, model, effort) }
        events.publish(EngineEvent.Setting(SETTING, mapOf("tier" to row.tier, "model" to row.model, "effort" to row.effort)))
        return row
    }

    @GetMapping("/security/tier")
    fun security(): SecurityTierView = securityView()

    /** Body: `{"tier": n}`, a JSON integer 1-10; anything else is a 400 and leaves the stored tier as it was. */
    @PutMapping("/security/tier")
    fun setSecurity(@RequestBody body: Map<String, Any?>): SecurityTierView {
        if (body.keys != setOf("tier")) throw badRequest("the body is {\"tier\": n}")
        val tier = intFrom(body, "tier") ?: throw badRequest("tier is an integer")
        refused { tiers.setSecurityTier(tier) }
        events.publish(EngineEvent.Setting(TierTable.SECURITY, tier))
        return securityView()
    }

    private fun securityView() = tiers.securityTier().let { t -> tiers.resolve(t).let { SecurityTierView(t, it.model, it.effort) } }

    /** The tier every post-deployment check run (mode `check`) runs at, beside the security tier. */
    @GetMapping("/check/tier")
    fun check(): SecurityTierView = checkView()

    /** Body: `{"tier": n}`, a JSON integer 1-10; anything else is a 400 and leaves the stored tier as it was. */
    @PutMapping("/check/tier")
    fun setCheck(@RequestBody body: Map<String, Any?>): SecurityTierView {
        if (body.keys != setOf("tier")) throw badRequest("the body is {\"tier\": n}")
        val tier = intFrom(body, "tier") ?: throw badRequest("tier is an integer")
        refused { tiers.setCheckTier(tier) }
        events.publish(EngineEvent.Setting(TierTable.CHECK, tier))
        return checkView()
    }

    private fun checkView() = tiers.checkTier().let { t -> tiers.resolve(t).let { SecurityTierView(t, it.model, it.effort) } }

    private fun <T> refused(write: () -> T): T = try { write() } catch (e: TierRefused) { throw badRequest(e.message.orEmpty()) }

    companion object {
        /** The `setting` event's name for a tier row's write. */
        const val SETTING = "tiers"
    }
}

/** The security tier (or the check tier, `GET /check/tier`) and what its row makes of it. */
data class SecurityTierView(val tier: Int, val model: String, val effort: String)

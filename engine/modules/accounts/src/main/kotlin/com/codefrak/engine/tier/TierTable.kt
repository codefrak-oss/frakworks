package com.codefrak.engine.tier

import com.codefrak.engine.accounts.ClaudeModels
import com.codefrak.engine.engineSetting
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/** A tier's resolution: the full model id and the effort to run it at. */
data class Resolved(val model: String, val effort: String)

/** One row of the map as the API answers it: the model as the full id a Smith runs with. */
data class TierRow(val tier: Int, val model: String, val effort: String)

/** A write the map refuses, with the reason the API says back (a 400). */
class TierRefused(message: String) : IllegalArgumentException(message)

/** `body[key]` as a JSON int, long, short or byte within [Int] range; null for anything else, including a missing key. */
fun intFrom(body: Map<String, Any?>, key: String): Int? = when (val t = body[key]) {
    is Int -> t
    is Long, is Short, is Byte -> (t as Number).toLong().takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    else -> null
}

/**
 * The tier map (engine/README.md, "Smith runs"): `engine.tier`'s ten rows, each a tier 1-10's
 * model and effort, and `engine.engine_setting`'s `security.tier`, the tier every security review
 * runs at. V42 seeds both from what `bots/tiers.toml` held until 2026-10-05 (#1099). Every read
 * goes to the database, so a write (`TierController`, the desktop's Config > frakworks > Tiers)
 * applies to the next run that resolves a tier, with no redeploy. Construction (and so startup)
 * fails when a row is missing: a map this engine runs on must be complete.
 */
@Component
class TierTable(private val jdbc: JdbcClient) {
    init {
        val present = jdbc.sql("SELECT tier FROM engine.tier").query(Int::class.javaObjectType).list().toSet()
        val security = setting(SECURITY)
        val checkTier = setting(CHECK)
        check(present.containsAll(TIERS.toList()) && security != null && checkTier != null) {
            "engine.tier must hold tiers 1-10 and engine.engine_setting security.tier and check.tier ($MIGRATION and $CHECK_MIGRATION seed them); " +
                "missing: ${(TIERS - present).joinToString().ifEmpty { "-" }}${if (security == null) ", security.tier" else ""}${if (checkTier == null) ", check.tier" else ""}"
        }
    }

    /** The ten tiers in order, each with its full model id and effort. */
    fun all(): List<TierRow> =
        jdbc.sql("SELECT tier, model, effort FROM engine.tier ORDER BY tier")
            .query { rs, _ -> TierRow(rs.getInt("tier"), ClaudeModels.fullId(rs.getString("model")), rs.getString("effort")) }.list()

    /** `tier`'s `(model, effort)`. */
    fun resolve(tier: Int): Resolved =
        jdbc.sql("SELECT model, effort FROM engine.tier WHERE tier = :t").param("t", tier)
            .query { rs, _ -> Resolved(ClaudeModels.fullId(rs.getString("model")), rs.getString("effort")) }.optional()
            .orElseThrow { IllegalStateException("no row for tier $tier in engine.tier") }

    /** Whether `tier` is one of the ten (each always has a row). */
    fun maps(tier: Int): Boolean = tier in TIERS

    /** The one tier every security review runs at, whatever the reviewed ticket's own tier. */
    fun securityTier(): Int = setting(SECURITY)?.toInt() ?: error("no $SECURITY row in engine.engine_setting ($MIGRATION seeds it)")

    /** The security review's resolution: [securityTier]'s row. */
    fun securityResolved(): Resolved = resolve(securityTier())

    /** The one tier every post-deployment check run (mode `check`) runs at, whatever the checked ticket's own tier. */
    fun checkTier(): Int = setting(CHECK)?.toInt() ?: error("no $CHECK row in engine.engine_setting ($CHECK_MIGRATION seeds it)")

    /** The check run's resolution: [checkTier]'s row. */
    fun checkResolved(): Resolved = resolve(checkTier())

    /**
     * Sets `tier`'s model (a short name or a full id, stored as the short name) and effort.
     * Throws [TierRefused] with the reason for a tier outside 1-10, a blank or unknown model,
     * or a blank effort or one the model does not take.
     */
    fun set(tier: Int, model: String?, effort: String?): TierRow {
        if (tier !in TIERS) throw TierRefused("tier $tier is not 1-10")
        if (model.isNullOrBlank()) throw TierRefused("model is empty")
        if (effort.isNullOrBlank()) throw TierRefused("effort is empty")
        val short = ClaudeModels.shortName(model) ?: throw TierRefused("unknown model '$model': one of ${ClaudeModels.CLAUDE_MODELS.keys.joinToString()} or its full id")
        val efforts = EFFORTS.getValue(short)
        if (effort !in efforts) throw TierRefused("$short does not take effort '$effort': ${efforts.joinToString()}")
        jdbc.sql("UPDATE engine.tier SET model = :m, effort = :e, updated_at = CURRENT_TIMESTAMP WHERE tier = :t")
            .param("m", short).param("e", effort).param("t", tier).update()
        return TierRow(tier, ClaudeModels.fullId(short), effort)
    }

    /** Sets the security tier; throws [TierRefused] for one outside 1-10. */
    fun setSecurityTier(tier: Int) = setTierSetting(SECURITY, tier)

    /** Sets the check tier; throws [TierRefused] for one outside 1-10. */
    fun setCheckTier(tier: Int) = setTierSetting(CHECK, tier)

    private fun setTierSetting(name: String, tier: Int) {
        if (tier !in TIERS) throw TierRefused("tier $tier is not 1-10")
        jdbc.sql("UPDATE engine.engine_setting SET setting = :v, updated_at = CURRENT_TIMESTAMP WHERE name = :n")
            .param("v", tier.toString()).param("n", name).update()
    }

    private fun setting(name: String): String? = jdbc.engineSetting(name)

    companion object {
        /** The scale: always exactly these ten. */
        val TIERS = 1..10

        /** `engine.engine_setting`'s row for the security tier. */
        const val SECURITY = "security.tier"

        /** `engine.engine_setting`'s row for the post-deployment check tier (V95 seeds 4). */
        const val CHECK = "check.tier"

        /** The migration that creates and seeds the map, named when a row is missing. */
        const val MIGRATION = "V42__tier.sql"

        /** The migration that seeds [CHECK], named when its row is missing. */
        const val CHECK_MIGRATION = "V95__check_tier.sql"

        /** The sandbox default: a run's model and effort when its flow resolved no tier. */
        val SANDBOX_DEFAULT = Resolved("claude-sonnet-5", "medium")

        /** The efforts each model takes. Haiku 4.5 takes no effort parameter, so it is `low` only. */
        val EFFORTS = mapOf(
            "haiku" to listOf("low"),
            "sonnet" to listOf("low", "medium", "high", "xhigh", "max"),
            "opus" to listOf("low", "medium", "high", "xhigh", "max"),
            "fable" to listOf("low", "medium", "high", "xhigh", "max"),
        )
    }
}

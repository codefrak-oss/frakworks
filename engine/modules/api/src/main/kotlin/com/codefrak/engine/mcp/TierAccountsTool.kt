package com.codefrak.engine.mcp

import com.codefrak.engine.tier.TierAccounts

/**
 * read_tier_accounts (#1260): `GET /tiers/accounts` in-process, each tier's account right now. A peek,
 * never a pick: it does not touch the rotation. Read-only, for the same callers as [DoctorHistoryTool].
 */
class TierAccountsTool(private val accounts: TierAccounts) {
    companion object {
        const val NAME = "read_tier_accounts"

        val TOOL = McpTool(NAME,
            "Read-only: which AI account a Smith run at each tier would get right now (what GET /tiers/accounts " +
                "answers), one row per tier, tier ascending: {tier, model, effort, account, blockedUntil}. `account` is " +
                "null when none is usable, and `blockedUntil` then says when the first one gets back in (null when none " +
                "will by itself). A peek, never a pick: it does not move the rotation.",
            mapOf("type" to "object", "properties" to emptyMap<String, Any>(), "additionalProperties" to false))

        /** The rows as `GET /tiers/accounts` serializes them. */
        fun rows(accounts: TierAccounts) = accounts.rows().map {
            linkedMapOf("tier" to it.tier, "model" to it.model, "effort" to it.effort, "account" to it.account,
                "blockedUntil" to it.blockedUntil)
        }
    }

    fun call(args: Map<String, Any?>): McpToolResult {
        if (args.isNotEmpty()) return McpToolResult.error("$NAME takes no arguments, not ${args.keys.joinToString(", ") { mcpJson.writeValueAsString(it) }}")
        return McpToolResult.of(rows(accounts))
    }
}

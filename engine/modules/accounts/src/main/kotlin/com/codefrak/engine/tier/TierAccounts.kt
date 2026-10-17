package com.codefrak.engine.tier

import com.codefrak.engine.accounts.AiAccounts
import com.codefrak.engine.accounts.isFable
import com.codefrak.engine.accounts.providerOf
import org.springframework.stereotype.Component
import java.time.format.DateTimeFormatter

/** One tier and the account a Smith run at it would get now: `GET /tiers/accounts` and `read_tier_accounts`; `blockedUntil` ISO-8601. */
data class TierAccountRow(val tier: Int, val model: String, val effort: String, val account: String?, val blockedUntil: String?)

/**
 * Which account the picker would choose right now for each tier (engine/README.md, "AI accounts"):
 * [AiAccounts.peek], never `pick`, so reading it does not touch the rotation. With no account, when the
 * first one gets back in: [AiAccounts.blockedUntil], and for a Fable model the earlier of it and
 * [AiAccounts.fableBlockedUntil].
 */
@Component
class TierAccounts(private val tiers: TierTable, private val accounts: AiAccounts) {
    fun rows(): List<TierAccountRow> = tiers.all().sortedBy { it.tier }.map { t ->
        val provider = runCatching { providerOf(t.model) }.getOrNull()
        val account = provider?.let { accounts.peek(it, t.model) }
        val blocked = if (account != null || provider == null) null else {
            val until = accounts.blockedUntil(provider)
            if (isFable(t.model)) listOfNotNull(until, accounts.fableBlockedUntil()).minOrNull() else until
        }
        TierAccountRow(t.tier, t.model, t.effort, account, blocked?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
    }
}

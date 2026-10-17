package com.codefrak.engine.smith

import com.codefrak.engine.accounts.AccountUsages
import com.codefrak.engine.accounts.AiAccounts
import com.codefrak.engine.accounts.isFable
import com.codefrak.engine.accounts.providerOf
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * The account [StartSmithDelegate]'s run bills to: the caller's pinned
 * account if there is one, else the picker's; either is
 * recorded as the run's `billedAccount`, never as `account`, so a later run
 * in the same flow picks afresh instead of reading it as a pin (#178). A real Fable run fails, as an incident, when its
 * pinned account is at the Fable limit or every anthropic account is. Any real run
 * fails the same way when its pinned account is rejected (5-hour, 7-day or a recorded limit, until its reset) or
 * when the picker found nothing and at least one account was skipped as rejected; that is checked before the
 * Fable gate, which still applies on top for `claude-fable-*`. A pinned jev account fails any run.
 */
@Component
class SmithRunAccount(
    private val accounts: AiAccounts,
    private val usages: AccountUsages,
) {
    private val log = LoggerFactory.getLogger(SmithRunAccount::class.java)

    fun choose(model: String, execution: DelegateExecution): String? {
        val real = execution.getVariable(Vars.DRY_RUN) as? Boolean != true
        (execution.getVariable(Vars.ACCOUNT) as? String)?.let { pinned ->
            // A jev account is never billed to, so no meter, Fable or limit record lands on one (engine/README.md, "AI accounts").
            if (accounts.get(pinned)?.provider == "jev") {
                log.warn("run {}: pinned account {} is a jev account", execution.processInstanceId, pinned)
                throw IllegalStateException("account $pinned is a jev account; no Smith runs on it")
            }
            val until = if (real) usages.get(pinned)?.rejectedUntil() else null
            if (until != null) {
                log.warn("run {}: pinned account {} is rejected until {}", execution.processInstanceId, pinned, until)
                throw IllegalStateException("account $pinned is pinned and rejected until ${until.toInstant()}")
            }
            val usage = if (real && isFable(model)) usages.get(pinned)?.takeIf { it.fableOut() } else null
            if (usage != null) {
                log.warn("run {}: pinned account {} is at its Fable limit", execution.processInstanceId, pinned)
                throw IllegalStateException("account $pinned is pinned and at its Fable limit until ${usage.fableOutUntil()?.toInstant() ?: "an unknown reset"}")
            }
            execution.setVariable(Vars.BILLED_ACCOUNT, pinned)
            return pinned
        }
        val picked = runCatching { accounts.pick(providerOf(model), model) }
            .onFailure { log.warn("run {}: no account picked for model {}: {}", execution.processInstanceId, model, it.message) }
            .getOrNull()
        execution.setVariable(Vars.BILLED_ACCOUNT, picked)
        // A Fable run that another model could run now waits on Fable alone, so it says so, whatever else is rejected.
        if (picked == null && real && isFable(model) && accounts.peek(providerOf(model)) != null)
            accounts.fableBlockedUntil()?.let { throw IllegalStateException(noFable(it)) }
        if (picked == null && real) accounts.blockedUntil(providerOf(model))?.let { throw IllegalStateException(noAccount(it)) }
        if (picked == null && real && isFable(model)) accounts.fableBlockedUntil()?.let { throw IllegalStateException(noFable(it)) }
        if (picked == null && real)
            throw IllegalStateException("no usable ai account for model $model (provider ${providerOf(model)}): add one, set its credential, enable it")
        return picked
    }

    private fun noAccount(until: OffsetDateTime) = "no account available until ${until.toInstant()}"
    private fun noFable(until: OffsetDateTime?) = "no account can run Fable until ${until?.toInstant() ?: "an unknown reset"}"
}

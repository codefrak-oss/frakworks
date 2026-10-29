package com.codefrak.engine.accounts

import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.smith.ModelCount
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/** `email` is the user's note of whose login it is, never used by the engine; omitted or empty is none. */
data class AddAccount(val name: String, val provider: String, val email: String? = null)
data class SetEmail(val email: String? = null)
/** `verify: false` saves without asking the provider: for when it cannot be reached, never to get past a refusal. */
data class SetCredential(val token: String, val verify: Boolean = true)
data class SetEnabled(val enabled: Boolean)
data class Rename(val name: String)
/** An API or admin key, sent once; `verify: false` is not offered: a key is always tried before it is stored. */
data class SetKey(val key: String)
/** The day the plan's API credit cycle renews (ISO date; its day of the month repeats monthly) and the grant per cycle, in cents. */
data class SetBilling(val cycleDate: java.time.LocalDate, val grantCents: Long)

/** [AccountUsage] plus the derived `rejected`, evaluated now, and the Fable limit as recorded and as metered. */
data class UsageView(
    val account: String,
    val fiveHourUtilization: Double?, val fiveHourStatus: String?, val fiveHourReset: java.time.OffsetDateTime?,
    val sevenDayUtilization: Double?, val sevenDayStatus: String?, val sevenDayReset: java.time.OffsetDateTime?,
    val status: String?, val rejected: Boolean, val httpStatus: Int, val requests: Long, val observedAt: java.time.OffsetDateTime,
    val fableLimit: String, val fableLimitSince: java.time.OffsetDateTime?, val fableLimitReset: java.time.OffsetDateTime?,
    val limitRejectedSince: java.time.OffsetDateTime?, val limitRejectedReset: java.time.OffsetDateTime?,
    /** Whether the picker skips the account now, and until when ([AccountUsage.rejectedUntil]). */
    val rejectedUntil: java.time.OffsetDateTime?,
    /** The Fable limit's meter, the `7d_oi` headers ([AccountUsage.fableUtilization]): the last Fable reply's; null when none was read or its reset passed. */
    val fableUtilization: Double?, val fableReset: java.time.OffsetDateTime?,
) {
    constructor(u: AccountUsage) : this(
        u.account, u.fiveHourUtilization, u.fiveHourStatus, u.fiveHourReset, u.sevenDayUtilization, u.sevenDayStatus, u.sevenDayReset,
        u.status, u.rejected(), u.httpStatus, u.requests, u.observedAt, u.fableLimit, u.fableLimitSince, u.fableLimitReset,
        u.limitRejectedSince, u.limitRejectedReset, u.rejectedUntil(), u.fableUtilization, u.fableReset,
    )
}

/**
 * One row of `GET /accounts`: [AiAccount] plus `running`, the live Smith runs
 * billed to it per model ([RunningModels.running]), empty when none is live.
 */
data class AccountView(
    val name: String, val provider: String, val email: String?, val enabled: Boolean,
    val hasCredential: Boolean, val credentialSetAt: java.time.OffsetDateTime?, val addedAt: java.time.OffsetDateTime,
    val running: List<ModelCount>,
    /** Whether each key is stored; a key itself is never in a view (engine/docs/ai-accounts.md, "API keys and credit"). */
    val apiKeySet: Boolean, val adminKeySet: Boolean,
    val billingCycleDate: java.time.LocalDate?, val grantCents: Long?,
    /** The current cycle ([cycleStart] today, UTC), null without a date. */
    val cycleStart: java.time.LocalDate?, val cycleEnd: java.time.LocalDate?,
    /** Cents spent this cycle as last read, the grant less that, when it was read, and why the last refresh failed. */
    val usedCents: java.math.BigDecimal?, val remainingCents: java.math.BigDecimal?,
    val creditRefreshedAt: java.time.OffsetDateTime?, val creditError: String?,
) {
    constructor(a: AiAccount, running: List<ModelCount>, today: java.time.LocalDate) : this(
        a.name, a.provider, a.email, a.enabled, a.hasCredential, a.credentialSetAt, a.addedAt, running,
        a.apiKeySet, a.adminKeySet, a.billingCycleDate, a.grantCents,
        a.billingCycleDate?.let { cycleStart(it, today) }, a.billingCycleDate?.let { cycleEnd(it, cycleStart(it, today)) },
        a.usedCents, a.usedCents?.let { used -> a.grantCents?.let { java.math.BigDecimal.valueOf(it) - used } },
        a.creditRefreshedAt, a.creditError,
    )

    companion object {
        fun of(accounts: List<AiAccount>, running: Map<String, List<ModelCount>>) =
            accounts.map { AccountView(it, running[it.name].orEmpty(), java.time.LocalDate.now(java.time.ZoneOffset.UTC)) }
    }
}

private val NAME = Regex("[a-z0-9][a-z0-9_.-]*")
/** `engine.ai_account.name` is a VARCHAR(100). */
private const val NAME_MAX = 100

private fun checkName(name: String) {
    if (!NAME.matches(name) || name.length > NAME_MAX)
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "name is lowercase letters, digits, _ . -, at most $NAME_MAX characters")
}

/**
 * Unauthenticated, like the rest of the engine's API: the AI accounts
 * (engine/README.md, "AI accounts"). A credential goes in and never comes out
 * here; the one way out is [AccountPickController], for First mate.
 */
@RestController
class AiAccountController(private val accounts: AiAccounts, private val secrets: SecretsKey, private val usages: AccountUsages,
                          private val verifier: CredentialVerifier, private val telemetry: RunningModels, private val events: EngineEvents,
                          private val credit: ApiCreditRefresh) {
    @GetMapping("/accounts")
    fun list() = AccountView.of(accounts.list(), telemetry.running())

    /** One `setting` `accounts` frame after each write here (engine/README.md, "Events"); `value` names the account. */
    private fun changed(name: String) = events.publish(EngineEvent.Setting("accounts", name))

    /** The plan meter per account (engine/README.md, "The usage meter"); */
    @GetMapping("/accounts/usage")
    fun usage() = usages.list().map { UsageView(it) }

    @GetMapping("/accounts/{name}/usage")
    fun usage(@PathVariable name: String): ResponseEntity<UsageView> =
        usages.get(name)?.let { ResponseEntity.ok(UsageView(it)) } ?: ResponseEntity.notFound().build()

    /**
     * Re-reads an Anthropic account's meter now (engine/README.md, "The usage
     * meter"): the verify probe on its stored credential, whose reply's
     * headers are recorded like a Smith's. A Haiku reply has no `7d_oi` pair,
     * so the Fable meter keeps its last reading. A 429 is a reading too.
     * 409 for a provider with no meter, 404 for no account or no credential,
     * 502 when Anthropic gave no reading; nothing is recorded in those.
     */
    @PostMapping("/accounts/{name}/usage/refresh")
    fun refreshUsage(@PathVariable name: String): ResponseEntity<UsageView> {
        val account = accounts.get(name) ?: return ResponseEntity.notFound().build()
        if (account.provider != "anthropic")
            throw ResponseStatusException(HttpStatus.CONFLICT, "a ${account.provider} account has no usage meter to refresh")
        val token = accounts.credential(name) ?: return ResponseEntity.notFound().build()
        val reply = try { verifier.probeAnthropic(token) }
            catch (e: ProbeUnreachable) { throw ResponseStatusException(HttpStatus.BAD_GATEWAY, e.message) }
        val reading = MeterReading.of(reply.headers)
            ?: throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "anthropic answered ${reply.status} with no usage meter headers; nothing was recorded")
        usages.record(name, reading, reply.status)
        changed(name)
        return ResponseEntity.ok(UsageView(usages.get(name)!!))
    }

    @PostMapping("/accounts")
    fun add(@RequestBody body: AddAccount): ResponseEntity<Unit> {
        checkName(body.name)
        if (body.provider !in PROVIDERS) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "provider is one of $PROVIDERS")
        checkEmail(body.email)
        val added = accounts.add(body.name, body.provider, body.email)
        if (added) changed(body.name)
        return ResponseEntity.status(if (added) HttpStatus.CREATED else HttpStatus.OK).build()
    }

    @PutMapping("/accounts/{name}/credential")
    fun setCredential(@PathVariable name: String, @RequestBody body: SetCredential): ResponseEntity<Unit> {
        val account = accounts.list().firstOrNull { it.name == name } ?: return ResponseEntity.notFound().build()
        if (!secrets.configured) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ENGINE_SECRETS_KEY is not set")
        val problem = if (account.provider == "codex") CodexAuthFile.problem(body.token)
            else credentialProblem(account.provider, body.token)
        problem?.let { throw ResponseStatusException(HttpStatus.BAD_REQUEST, it) }
        if (body.verify) {
            // Tried before it is stored: a token the provider refuses would only fail at the next Smith run.
            if (account.provider != "codex") {
                val verdict = verifier.verify(account.provider, body.token)
                if (!verdict.valid && verdict.reachable) throw ResponseStatusException(HttpStatus.BAD_REQUEST, verdict.detail)
                if (!verdict.valid) throw ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "${verdict.detail}; nothing was saved. To save it unverified, send \"verify\": false")
            }
        }
        return if (accounts.setCredential(name, body.token)) { changed(name); ResponseEntity.noContent().build() } else ResponseEntity.notFound().build()
    }

    /**
     * Sets an Anthropic account's API key or admin key (engine/docs/ai-accounts.md,
     * "API keys and credit"), after trying it against Anthropic: 400 for a key
     * of the wrong shape, 422 with Anthropic's message when it refuses the key,
     * 502 when it could not be asked; nothing is stored in any of those. 409
     * for a provider that is not anthropic, and for an admin key on an account
     * with no billing-cycle date: the date is what the credit is counted from.
     * A stored admin key's credit is read at once.
     */
    private fun setKey(name: String, kind: ApiKeyKind, key: String): ResponseEntity<Unit> {
        val account = accounts.get(name) ?: return ResponseEntity.notFound().build()
        if (account.provider != "anthropic") throw ResponseStatusException(HttpStatus.CONFLICT, "only an anthropic account takes an ${kind.label}")
        if (kind == ApiKeyKind.ADMIN && account.billingCycleDate == null)
            throw ResponseStatusException(HttpStatus.CONFLICT, "set $name's billing-cycle date and grant (PUT /accounts/$name/billing) before its admin key")
        if (!secrets.configured) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ENGINE_SECRETS_KEY is not set")
        kind.problem(key)?.let { throw ResponseStatusException(HttpStatus.BAD_REQUEST, it) }
        val verdict = verifier.verifyKey(kind, key)
        if (!verdict.valid && verdict.reachable) throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, verdict.detail)
        if (!verdict.valid) throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "${verdict.detail}; nothing was saved")
        if (!accounts.storeKey(name, kind, key)) return ResponseEntity.notFound().build()
        if (kind == ApiKeyKind.ADMIN) credit.refresh(name) else changed(name)
        return ResponseEntity.noContent().build()
    }

    private fun clearKey(name: String, kind: ApiKeyKind): ResponseEntity<Unit> =
        if (accounts.clearKey(name, kind)) { changed(name); ResponseEntity.noContent().build() } else ResponseEntity.notFound().build()

    @PutMapping("/accounts/{name}/api-key")
    fun setApiKey(@PathVariable name: String, @RequestBody body: SetKey) = setKey(name, ApiKeyKind.API, body.key)

    @DeleteMapping("/accounts/{name}/api-key")
    fun clearApiKey(@PathVariable name: String) = clearKey(name, ApiKeyKind.API)

    @PutMapping("/accounts/{name}/admin-key")
    fun setAdminKey(@PathVariable name: String, @RequestBody body: SetKey) = setKey(name, ApiKeyKind.ADMIN, body.key)

    @DeleteMapping("/accounts/{name}/admin-key")
    fun clearAdminKey(@PathVariable name: String) = clearKey(name, ApiKeyKind.ADMIN)

    /**
     * Sets the billing-cycle date and the grant per cycle, in cents. 400 for a
     * negative grant, 409 for a provider that is not anthropic. With an admin
     * key stored, the credit is read again at once for the cycle the date gives.
     */
    @PutMapping("/accounts/{name}/billing")
    fun setBilling(@PathVariable name: String, @RequestBody body: SetBilling): ResponseEntity<Unit> {
        if (body.grantCents < 0) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "grantCents is 0 or more")
        val account = accounts.get(name) ?: return ResponseEntity.notFound().build()
        if (account.provider != "anthropic") throw ResponseStatusException(HttpStatus.CONFLICT, "only an anthropic account has API credit")
        if (!accounts.setBilling(name, body.cycleDate, body.grantCents)) return ResponseEntity.notFound().build()
        if (account.adminKeySet) credit.refresh(name) else changed(name)
        return ResponseEntity.noContent().build()
    }

    /** Re-reads the account's credit from cost_report now; 409 with no admin key or date, 502 with the error when it failed. */
    @PostMapping("/accounts/{name}/credit/refresh")
    fun refreshCredit(@PathVariable name: String): ResponseEntity<Unit> {
        val account = accounts.get(name) ?: return ResponseEntity.notFound().build()
        if (!account.adminKeySet || account.billingCycleDate == null)
            throw ResponseStatusException(HttpStatus.CONFLICT, "$name has no admin key and billing-cycle date to read credit with")
        if (!credit.refresh(name)) throw ResponseStatusException(HttpStatus.BAD_GATEWAY, accounts.get(name)?.creditError ?: "refresh failed")
        return ResponseEntity.noContent().build()
    }

    /** Tries the stored credential against its provider now; 404 when there is none. */
    @PostMapping("/accounts/{name}/verify")
    fun verify(@PathVariable name: String): ResponseEntity<Verdict> {
        val account = accounts.get(name) ?: return ResponseEntity.notFound().build()
        if (account.provider == "codex")
            throw ResponseStatusException(HttpStatus.CONFLICT, "Codex auth state is stored locally; it cannot be verified until Smith execution is connected")
        val token = accounts.credential(name) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(verifier.verify(account.provider, token))
    }

    private fun checkEmail(email: String?) {
        if ((noteOf(email)?.length ?: 0) > EMAIL_MAX) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "email is at most $EMAIL_MAX characters")
    }

    /** The user's note of whose login the account is, for their reference only; empty or null clears it. */
    @PutMapping("/accounts/{name}/email")
    fun setEmail(@PathVariable name: String, @RequestBody body: SetEmail): ResponseEntity<Unit> {
        checkEmail(body.email)
        return if (accounts.setEmail(name, body.email)) { changed(name); ResponseEntity.noContent().build() } else ResponseEntity.notFound().build()
    }

    @PutMapping("/accounts/{name}/enabled")
    fun setEnabled(@PathVariable name: String, @RequestBody body: SetEnabled): ResponseEntity<Unit> =
        if (accounts.setEnabled(name, body.enabled)) { changed(name); ResponseEntity.noContent().build() } else ResponseEntity.notFound().build()

    /**
     * Renames the account, carrying its usage reading, run stats and Jev calls
     * over ([AiAccounts.rename]). 409 for a taken name, and while a Smith run
     * is live on it: its sandbox and flow carry the old name; 409 for `engine`,
     * the engine's own token's usage name. A stale usage reading left under
     * the new name by a removed account is replaced. The same name is
     * a no-op.
     */
    @PutMapping("/accounts/{name}/name")
    fun rename(@PathVariable name: String, @RequestBody body: Rename): ResponseEntity<Unit> {
        checkName(body.name)
        val account = accounts.get(name) ?: return ResponseEntity.notFound().build()
        if (body.name == name) return ResponseEntity.noContent().build()
        if (body.name == "engine") throw ResponseStatusException(HttpStatus.CONFLICT, "engine is the engine's own token's usage name")
        if (accounts.get(body.name) != null) throw ResponseStatusException(HttpStatus.CONFLICT, "an account named ${body.name} already exists")
        if (telemetry.running()[name].orEmpty().isNotEmpty())
            throw ResponseStatusException(HttpStatus.CONFLICT, "account $name is in use by a live Smith run; rename it when the run ends")
        if (account.hasCredential && !secrets.configured) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ENGINE_SECRETS_KEY is not set")
        if (!accounts.rename(name, body.name)) return ResponseEntity.notFound().build()
        changed(body.name)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/accounts/{name}")
    fun remove(@PathVariable name: String): ResponseEntity<Unit> =
        if (accounts.remove(name)) { changed(name); ResponseEntity.noContent().build() } else ResponseEntity.notFound().build()
}

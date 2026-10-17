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
) {
    constructor(a: AiAccount, running: List<ModelCount>) :
        this(a.name, a.provider, a.email, a.enabled, a.hasCredential, a.credentialSetAt, a.addedAt, running)

    companion object {
        fun of(accounts: List<AiAccount>, running: Map<String, List<ModelCount>>) =
            accounts.map { AccountView(it, running[it.name].orEmpty()) }
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
                          private val verifier: CredentialVerifier, private val telemetry: RunningModels, private val events: EngineEvents) {
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

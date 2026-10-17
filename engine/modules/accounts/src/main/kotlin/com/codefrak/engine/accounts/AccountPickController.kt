package com.codefrak.engine.accounts

import com.codefrak.engine.CallerRefusal
import com.codefrak.engine.CallerIdentity
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/** The model First mate's child is about to start on: a full id, or a tier map short name (`ClaudeModels.CLAUDE_MODELS`) (`fable`, `opus`). */
data class PickAccount(val model: String)

/** The picked account, the env var its token goes in, and the token in the clear; its `toString` leaves the token out. */
data class PickedAccount(val account: String, val provider: String, val env: String, val token: String) {
    override fun toString() = "PickedAccount(account=$account, provider=$provider, env=$env)"
}

/**
 * The one place a Smith provider's credential leaves the engine over HTTP
 * (engine/README.md, "First mate's account"): First mate is a `claude -p`
 * child of the desktop app on the host, not a sandbox the engine starts, so
 * the engine cannot put the token in its environment itself. The account is
 * the one a Smith on that model would get ([AiAccounts.pick], marked picked,
 * so First mate takes its turn in the rotation). A Smith sandbox is refused,
 * known by its source address as everywhere else.
 */
@RestController
class AccountPickController(private val accounts: AiAccounts, private val sandbox: CallerIdentity) {
    private val log = LoggerFactory.getLogger(AccountPickController::class.java)

    /**
     * 200 [PickedAccount]; 400 for a model no provider serves; 403 to a Smith sandbox; 503 when
     * the caller cannot be told from a Smith, or no account of the provider is usable now.
     */
    @PostMapping("/accounts/pick")
    fun pick(@RequestBody body: PickAccount, request: HttpServletRequest? = null): PickedAccount {
        CallerRefusal.refuseSmith(sandbox, request, log, "pick an account")
        val model = ClaudeModels.CLAUDE_MODELS[body.model] ?: body.model
        val provider = try { providerOf(model) } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        }
        val name = accounts.pick(provider, model)
            ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "no usable $provider account for $model")
        val token = accounts.credential(name)
            ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "account $name has no credential")
        log.info("first mate: account {} picked for {}", name, model)
        return PickedAccount(name, provider, credentialEnv(provider), token)
    }
}

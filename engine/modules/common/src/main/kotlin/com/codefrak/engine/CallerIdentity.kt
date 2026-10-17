package com.codefrak.engine

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.Logger
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * Who a request comes from, by its source address: the one question the engine modules ask of the Smith sandboxes
 * (engine/README.md, "Engine modules"). `SandboxCallers` (engine-smith) answers it from the `SmithSandbox`.
 */
fun interface CallerIdentity {
    /** The run id of the Smith sandbox at `address`, or null for a caller that is no Smith; throws when the sandbox provider cannot be asked. */
    fun smithRunAt(address: String): String?
}

/** The one check the controllers run: 403 for a Smith sandbox, by its source address; 503 when the sandbox provider cannot be asked. */
object CallerRefusal {
    /** Null to go on; otherwise the status and message a controller that answers `{error}` bodies returns as its own response. */
    fun smithRefusal(callers: CallerIdentity, request: HttpServletRequest?, logger: Logger, what: String): Pair<HttpStatus, String>? {
        val address = request?.remoteAddr ?: return null
        val smith = try { callers.smithRunAt(address) } catch (e: Exception) {
            logger.warn("could not tell whether {} is a Smith sandbox: {}", address, e.message)
            return HttpStatus.SERVICE_UNAVAILABLE to "cannot tell whether the caller is a Smith sandbox"
        }
        return smith?.let { HttpStatus.FORBIDDEN to "a Smith sandbox (run $it) may not $what" }
    }

    fun refuseSmith(callers: CallerIdentity, request: HttpServletRequest?, logger: Logger, what: String) {
        smithRefusal(callers, request, logger, what)?.let { (status, message) -> throw ResponseStatusException(status, message) }
    }
}

/** Request attribute: the login a valid bearer token proved (engine/README.md, "Tokens"), set by the engine's bearer filter. */
const val TOKEN_LOGIN_ATTRIBUTE = "frakworks.token.login"
/** Request attribute: the id of the bearer token that proved [TOKEN_LOGIN_ATTRIBUTE]. */
const val TOKEN_ID_ATTRIBUTE = "frakworks.token.id"
/** Request attribute: the `SmithCaller` the bearer filter found at the source address, so a controller need not look it up again. */
const val SMITH_AT_ADDRESS_ATTRIBUTE = "frakworks.smith.caller"
/** The login a bearer token proved for this request, or null when it carried none. */
fun HttpServletRequest.provenLogin(): String? = getAttribute(TOKEN_LOGIN_ATTRIBUTE) as? String
/** The id of the bearer token that proved this request's login, or null. */
fun HttpServletRequest.provenTokenId(): String? = getAttribute(TOKEN_ID_ATTRIBUTE) as? String

/**
 * The login this request's bearer token proved; 401 `no such token` without one (a Smith sandbox, whose header the
 * engine ignores, or a request that reached the controller some other way). The token is the only way a caller that
 * is no Smith names its login (engine/README.md, "Tokens").
 */
fun HttpServletRequest?.tokenLogin(): String =
    this?.provenLogin() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "no such token")

/** 400 for a body or query that still names its login in `by`: the bearer token names it. */
fun refuseBy(by: Any?) {
    if (by != null) throw badRequest("by is not a field: the bearer token names the login")
}

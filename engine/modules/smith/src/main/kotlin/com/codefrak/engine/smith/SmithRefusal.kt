package com.codefrak.engine.smith

import com.codefrak.engine.CallerIdentity
import com.codefrak.engine.CallerRefusal
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.Logger
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/** The one check ten controllers ran privately: 403 for a Smith sandbox, by its source address; 503 when the sandbox provider cannot be asked. */
object SmithRefusal {
    fun refuseSmith(sandbox: SmithSandbox, request: HttpServletRequest?, logger: Logger, what: String) =
        CallerRefusal.refuseSmith(SandboxCallers(sandbox), request, logger, what)

    /**
     * The Smith sandbox the request came from, by its source address, or null (engine/README.md,
     * "The frakworks server in a Smith"). 503 if the sandbox provider cannot be asked: a request
     * is never taken for a human's because Docker did not answer. Null without a request (a direct call).
     */
    fun smithAt(sandbox: SmithSandbox, request: HttpServletRequest?, logger: Logger): SmithCaller? {
        val address = request?.remoteAddr ?: return null
        return try { sandbox.runAt(address) } catch (e: Exception) {
            logger.warn("could not tell whether {} is a Smith sandbox: {}", address, e.message)
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "cannot tell whether the caller is a Smith sandbox")
        }
    }
}

/** [CallerIdentity] for the engine modules (engine/README.md, "Engine modules"): the run id of the sandbox's [SmithSandbox.runAt]. */
@Component
class SandboxCallers(private val sandbox: SmithSandbox) : CallerIdentity {
    override fun smithRunAt(address: String): String? = sandbox.runAt(address)?.runId
}

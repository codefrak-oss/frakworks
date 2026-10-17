package com.codefrak.engine.runtoken

import com.codefrak.engine.SMITH_AT_ADDRESS_ATTRIBUTE
import com.codefrak.engine.badRequest
import com.codefrak.engine.smith.RunTokens
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * A Smith run's identity token, for the servers that check it (#2453; engine/README.md, "Run identity tokens";
 * docs/run-identity-token.md). `GET /.well-known/jwks.json` and `POST /run-tokens/introspect` need no engine token
 * (`BearerTokenFilter.EXEMPT`): a downstream server verifies with them alone. `POST /run-tokens/rotate` and
 * `POST /run-tokens/{runId}/revoke` are a login's, behind the bearer token, and refused to a Smith sandbox.
 */
@RestController
class RunTokenController(private val tokens: RunTokens) {
    @GetMapping("/.well-known/jwks.json")
    fun jwks(): Map<String, Any> = tokens.jwks()

    /**
     * Body: `{"token": "<jwt>", "audience": "<the caller's own MCP URL>"}` (`audience` optional but advised). RFC 7662's
     * answer: `{active: false}`, or `active: true` with the claims, while the run's session is open.
     */
    @PostMapping("/run-tokens/introspect")
    fun introspect(@RequestBody body: Map<String, Any?>): Map<String, Any?> {
        val token = body["token"] as? String ?: throw badRequest("the body is {\"token\": <run token>, \"audience\": <your URL, optional>}")
        val audience = body["audience"]?.let { it as? String ?: throw badRequest("audience is a string") }
        return tokens.introspect(token.trim(), audience)
    }

    /** Retires the signing key and makes a new one: `{kid}`. The old key stays in the JWKS until its tokens expire. */
    @PostMapping("/run-tokens/rotate")
    fun rotate(request: HttpServletRequest): Map<String, String> {
        notSmith(request)
        return mapOf("kid" to tokens.rotate())
    }

    /** Every token of [runId] answers inactive from now on: `{runId, revoked: true}`. */
    @PostMapping("/run-tokens/{runId}/revoke")
    fun revoke(@PathVariable runId: String, request: HttpServletRequest): Map<String, Any> {
        notSmith(request)
        tokens.revoke(runId)
        return mapOf("runId" to runId, "revoked" to true)
    }

    private fun notSmith(request: HttpServletRequest) {
        if (request.getAttribute(SMITH_AT_ADDRESS_ATTRIBUTE) != null)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "a Smith cannot rotate or revoke run tokens")
    }
}

package com.codefrak.engine

import com.codefrak.engine.firstmate.FirstMateSessionPods
import com.codefrak.engine.firstmate.FirstMateSessions
import com.codefrak.engine.firstmate.push.PushDevices
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.EngineToken
import com.codefrak.engine.repos.EngineTokens
import com.codefrak.engine.repos.LOGIN
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.time.OffsetDateTime

/** `GET /tokens`'s row: never the hash. */
data class TokenView(val id: String, val login: String, val name: String, val prefix: String, val createdAt: OffsetDateTime,
                     val createdBy: String?, val expiresAt: OffsetDateTime?, val lastUsedAt: OffsetDateTime?, val revokedAt: OffsetDateTime?,
                     val purpose: String?) {
    companion object {
        fun of(t: EngineToken) = TokenView(t.id, t.login, t.name, t.prefix, t.createdAt, t.createdBy, t.expiresAt, t.lastUsedAt, t.revokedAt, t.purpose)
    }
}

/** `POST /tokens`'s answer: the one time the token itself is shown. */
data class MintedView(val id: String, val token: String, val login: String, val name: String, val prefix: String, val expiresAt: OffsetDateTime?,
                      val purpose: String?)

/** `GET /whoami`: the login the presented bearer token proves. */
data class WhoAmI(val login: String, val tokenId: String, val name: String)

/**
 * The engine's bearer tokens (engine/README.md, "Tokens"): `POST /tokens` mints one for a trusted login, `GET /tokens` lists them without hashes, `DELETE
 * /tokens/{id}` revokes (and ends the first-mate sessions started with that token whatever its purpose, a plain sign-in
 * bearer's as a phone's, no other token's, deleting their pods and revoking their pod tokens, and unregisters the push
 * devices it registered), `GET /whoami` says who the presented token is. A Smith sandbox is 403 on all of them
 * ([CallerRefusal]). While no unrevoked token exists the three `/tokens` endpoints need none (the bootstrap door: the
 * first token is minted through it and logged); once one exists they need a bearer token like any other write.
 */
@RestController
class TokenController(private val tokens: EngineTokens, private val access: Access, private val callers: CallerIdentity,
                      private val sessions: FirstMateSessions, private val pods: FirstMateSessionPods,
                      private val devices: PushDevices) {
    private val log = LoggerFactory.getLogger(TokenController::class.java)

    companion object {
        const val NEEDS_TOKEN = "a bearer token is needed to manage tokens (the first token needs none)"
        val EXPIRES_IN_DAYS = 1..3650
        const val MAX_NAME = 100
    }

    @PostMapping("/tokens")
    fun mint(@RequestBody body: JsonNode, request: HttpServletRequest): ResponseEntity<Any> {
        refuse(request, "mint a token")?.let { return it }
        if (!body.isObject) throw badRequest("the body is {login, name, expiresInDays?, purpose?}")
        refuseUnknownFields(body.propertyNames().toSet(), setOf("login", "name", "expiresInDays", "purpose"), "login, name, expiresInDays and purpose")
        val login = body.get("login")?.takeIf { it.isString }?.asString()?.trim()?.removePrefix("@").orEmpty()
        if (!LOGIN.matches(login)) throw badRequest("login is a GitHub login")
        if (!access.trusted(login)) throw badRequest("@$login is not a trusted login: trust it first (POST /access), then mint")
        val name = body.get("name")?.takeIf { it.isString }?.asString()?.trim().orEmpty()
        if (name.isEmpty() || name.length > MAX_NAME) throw badRequest("name is non-blank and at most $MAX_NAME characters")
        val days = body.get("expiresInDays")?.let { n ->
            n.takeIf { it.isIntegralNumber && it.canConvertToInt() && it.asInt() in EXPIRES_IN_DAYS }?.asInt()
                ?: throw badRequest("expiresInDays is a whole number of days from ${EXPIRES_IN_DAYS.first} to ${EXPIRES_IN_DAYS.last}, or absent")
        }
        val purpose = body.get("purpose")?.takeUnless { it.isNull }?.let { p ->
            p.takeIf { it.isString && it.asString() in EngineTokens.PURPOSES }?.asString()
                ?: throw badRequest("purpose is ${EngineTokens.FIRST_MATE} (a phone's first-mate token), or absent")
        }
        val proven = request.provenLogin()
        val createdBy = when {
            proven != null -> proven
            tokens.anyActive() -> return errorResponse(HttpStatus.UNAUTHORIZED, "a bearer token is needed to mint a token (the first token needs none)")
            else -> {
                log.warn("first token minted through the bootstrap door for @{} from {}", login, request.remoteAddr)
                login
            }
        }
        val minted = tokens.mint(login, name, days, createdBy, purpose)
        log.info("token {} ({}) minted for @{} by @{}", minted.row.id, minted.row.prefix, login, createdBy)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(MintedView(minted.row.id, minted.token, minted.row.login, minted.row.name, minted.row.prefix, minted.row.expiresAt, minted.row.purpose))
    }

    @GetMapping("/tokens")
    fun list(request: HttpServletRequest): ResponseEntity<Any> {
        refuse(request, "list tokens")?.let { return it }
        gate(request)?.let { return it }
        return ResponseEntity.ok(tokens.list().map(TokenView::of))
    }

    /** 204 revoked; 200 when it already was (idempotent); 404 for an unknown id. */
    @DeleteMapping("/tokens/{id}")
    fun revoke(@PathVariable id: String, request: HttpServletRequest): ResponseEntity<Any> {
        refuse(request, "revoke a token")?.let { return it }
        gate(request)?.let { return it }
        return when (tokens.revoke(id)) {
            null -> errorResponse(HttpStatus.NOT_FOUND, "no token $id")
            true -> {
                val open = sessions.openForToken(id)
                val ended = sessions.endForToken(id)
                // The sessions' pods go, and with them their first-mate-pod tokens: revoked here too, so none outlives its
                // ended session when there is no cluster to delete a pod in.
                open.forEach(pods::stop)
                open.forEach { tokens.revokeForSession(it.id) }
                val unregistered = devices.deleteForToken(id)
                log.info("token {} revoked by @{}; {} first-mate session(s) ended, {} push device(s) unregistered", id, request.provenLogin() ?: "the bootstrap door", ended, unregistered)
                ResponseEntity.noContent().build()
            }
            false -> ResponseEntity.ok().build()
        }
    }

    @GetMapping("/whoami")
    fun whoami(request: HttpServletRequest): ResponseEntity<Any> {
        val login = request.provenLogin(); val id = request.provenTokenId()
        if (login == null || id == null) return errorResponse(HttpStatus.UNAUTHORIZED, BearerTokenFilter.NO_SUCH_TOKEN)
        val row = tokens.get(id) ?: return errorResponse(HttpStatus.UNAUTHORIZED, BearerTokenFilter.NO_SUCH_TOKEN)
        return ResponseEntity.ok(WhoAmI(login, id, row.name))
    }

    private fun refuse(request: HttpServletRequest, what: String): ResponseEntity<Any>? =
        CallerRefusal.smithRefusal(callers, request, log, what)?.let { (status, message) -> errorResponse(status, message) }

    /** 401 once a token exists and the request proved none; open before that (the bootstrap door). */
    private fun gate(request: HttpServletRequest): ResponseEntity<Any>? =
        if (request.provenLogin() == null && tokens.anyActive()) errorResponse(HttpStatus.UNAUTHORIZED, NEEDS_TOKEN) else null
}

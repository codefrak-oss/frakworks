package com.codefrak.engine.firstmate

import com.codefrak.engine.badRequest
import com.codefrak.engine.errorResponse
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.provenLogin
import com.codefrak.engine.provenTokenId
import com.codefrak.engine.repos.EngineTokens
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.Lineage
import com.codefrak.engine.tier.TierTable
import com.codefrak.engine.tier.intFrom
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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

/**
 * The phone's first-mate sessions (engine/README.md, "First-mate sessions"):
 * `POST /first-mate/sessions {repo, ask?, tier?}` starts one (`tier`, the desktop's, is kept on the session and its pod
 * runs at it; the phone sends none), `POST .../{id}/messages {text, clientId?, images?}` adds the phone's message
 * (a repeat with the same `clientId` stores nothing and answers 200 with the first row; `images`, the desktop's, go in
 * the row's body under `FirstMateImages`' allowlist) and publishes its `first-mate`
 * frame (`{"type":"user", "text", "images": <count>}`, what wakes the pod's inbox on any replica, never the data), `GET
 * .../{id}/transcript` reads the chat, `DELETE .../{id}` ends it. The bearer is a signed-in login's own token (purpose
 * null: the desktop's and the web page's sign-in bearer, which mint no token of their own) or a `first-mate` token (the
 * phone's); 401 without a token, 403 with any other (a session pod's `first-mate-pod` token never drives a session; a
 * token row that is gone). The session row keeps the bearer's token id whatever its purpose, so `DELETE /tokens/{id}`
 * ends the sessions it started (`TokenController.revoke`). The repo is a target repo (400 naming them); a session is its
 * owner's alone, and any other login gets 404 for it. A message to a frozen session (`FirstMateFreezer`) resumes it in a
 * new pod from its snapshot first.
 */
@RestController
class FirstMateSessionController(
    private val sessions: FirstMateSessions, private val tokens: EngineTokens, private val repos: TargetRepos,
    private val mcp: FirstMateMcpConfig, private val pods: FirstMateSessionPods, private val snapshots: FirstMateSnapshots,
    private val events: EngineEvents, private val tiers: TierTable,
) {
    private val log = LoggerFactory.getLogger(FirstMateSessionController::class.java)

    companion object {
        const val NEEDS_TOKEN = "a bearer token is needed: a signed-in login's own token, or one minted for the phone (POST /tokens with purpose first-mate)"
        const val WRONG_TOKEN = "this token may not drive a first-mate session: use a signed-in login's own token or a first-mate token (POST /tokens {login, name, purpose: \"first-mate\"}); a first-mate-pod token never"
        const val MAX_TEXT = 100_000
        const val MAX_CLIENT_ID = 128
        const val BAD_TIER = "tier is a whole number 1-10 in the engine's tier map"
        const val DROPPED_NOTE = "The working folder was over 200 MB when this session was frozen, so it was not kept: the first mate resumes without its files."
    }

    /** The proven caller: its login, token id and the token itself; or the refusal. */
    private data class Caller(val login: String, val tokenId: String, val token: String)

    private fun caller(request: HttpServletRequest): Pair<Caller?, ResponseEntity<Any>?> {
        val login = request.provenLogin(); val id = request.provenTokenId()
        val token = request.getHeader("Authorization")?.takeIf { it.length > 7 && it.substring(0, 7).equals("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
        if (login == null || id == null || token == null) return null to errorResponse(HttpStatus.UNAUTHORIZED, NEEDS_TOKEN)
        // A plain sign-in bearer (purpose null) or the phone's first-mate token; a pod's first-mate-pod token, or a row
        // that is gone, is 403 (BearerTokenFilter already keeps a pod token to its own /first-mate/pod/{session}/ routes).
        val row = tokens.get(id) ?: return null to errorResponse(HttpStatus.FORBIDDEN, WRONG_TOKEN)
        if (row.purpose != null && row.purpose != EngineTokens.FIRST_MATE) return null to errorResponse(HttpStatus.FORBIDDEN, WRONG_TOKEN)
        return Caller(login, id, token) to null
    }

    /** The caller's own session [id], or 404: another login's session is not shown to exist. */
    private fun owned(id: String, c: Caller): FirstMateSession? = sessions.get(id)?.takeIf { it.login.equals(c.login, ignoreCase = true) }

    private fun noSession(id: String) = errorResponse(HttpStatus.NOT_FOUND, "no first-mate session $id")

    private fun view(s: FirstMateSession, firstLine: String? = null, dropped: List<DroppedMcpServer>? = null): Map<String, Any?> =
        linkedMapOf<String, Any?>("id" to s.id, "login" to s.login, "repo" to s.repo, "state" to s.state, "createdAt" to s.createdAt, "endedAt" to s.endedAt, "tier" to s.tier)
            .apply { if (dropped != null) { put("dropped", dropped); put("firstLine", firstLine) } }

    @PostMapping("/first-mate/sessions")
    fun start(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<Any> {
        val (c, refusal) = caller(request); c ?: return refusal!!
        val asked = (body["repo"] as? String)?.trim().orEmpty()
        if (asked.isEmpty()) throw badRequest("repo is required: owner/name of a target repo")
        val repo = repos.find(asked) ?: throw badRequest("$asked is not a target repo; the target repos are ${repos.list().joinToString(", ") { it.fullName }.ifEmpty { "none" }}")
        // The session's lineage (engine/README.md, "Lineage"): captured once, rooted at the chat's ask, `ask` here (the
        // phone sends its first message with it); without one the claim says what the session is.
        val ask = (body["ask"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "First-mate session on ${repo.fullName}."
        val tier = body["tier"]?.let { intFrom(body, "tier")?.takeIf { t -> tiers.maps(t) } ?: throw badRequest(BAD_TIER) }
        val session = sessions.start(c.login, c.tokenId, repo.fullName, tier) { id -> String(Lineage.session(c.login, ask, id).toBytes()) }
        val config = mcp.forSession(ServletUriComponentsBuilder.fromContextPath(request).toUriString(), c.token, repo.fullName)
        config.firstLine()?.let { sessions.append(session.id, "system", mapOf("text" to it)) }
        pods.start(session, config)
        log.info("first-mate session {} started on {} by @{} (tier {})", session.id, repo.fullName, c.login, tier ?: "default")
        return ResponseEntity.status(HttpStatus.CREATED).body(view(session, config.firstLine(), config.dropped))
    }

    /**
     * The desktop First mate's lineage (engine/README.md, "Lineage"): `POST /first-mate/lineage {ask}` with the signed-in
     * login's own token answers `{asOf, links: [{kind: session, claim, source: {login}, by}], truncated}`, which the
     * desktop hands to the session it spawns as data. Any proven login; 401 without one.
     */
    @PostMapping("/first-mate/lineage")
    fun lineage(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<Any> {
        val login = request.provenLogin() ?: return errorResponse(HttpStatus.UNAUTHORIZED, "a bearer token is needed")
        val ask = (body["ask"] as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_TEXT }
            ?: throw badRequest("ask is a non-blank string of at most $MAX_TEXT characters")
        return ResponseEntity.ok(Lineage.session(login, ask))
    }

    @PostMapping("/first-mate/sessions/{id}/messages")
    fun message(@PathVariable id: String, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<Any> {
        val (c, refusal) = caller(request); c ?: return refusal!!
        val session = owned(id, c) ?: return noSession(id)
        if (session.state == FirstMateSessions.ENDED) return errorResponse(HttpStatus.CONFLICT, "first-mate session $id has ended")
        val text = (body["text"] as? String)?.takeIf { it.isNotBlank() && it.length <= MAX_TEXT }
            ?: throw badRequest("text is a non-blank string of at most $MAX_TEXT characters")
        val clientId = body["clientId"]?.let { (it as? String)?.takeIf { s -> s.isNotBlank() && s.length <= MAX_CLIENT_ID } ?: throw badRequest("clientId is a non-blank string of at most $MAX_CLIENT_ID characters") }
        val images = FirstMateImages.validate(body["images"])
        if (session.frozen && sessions.thaw(id)) resume(session, c, request)
        val stored: Map<String, Any> = if (images.isEmpty()) mapOf("text" to text) else mapOf("text" to text, "images" to images)
        val (row, inserted) = sessions.appendUser(id, stored, clientId)
        if (!inserted) return ResponseEntity.ok(row)
        val frame: Map<String, Any> = if (images.isEmpty()) mapOf("type" to "user", "text" to text) else mapOf("type" to "user", "text" to text, "images" to images.size)
        events.publish(EngineEvent.FirstMate(session.repo, id, row.seq, frame))
        return ResponseEntity.status(HttpStatus.CREATED).body(row)
    }

    /** Starts a new pod for thawed [session] from its snapshot (an empty one when none was taken), noting a dropped folder. */
    private fun resume(session: FirstMateSession, c: Caller, request: HttpServletRequest) {
        val config = mcp.forSession(ServletUriComponentsBuilder.fromContextPath(request).toUriString(), c.token, session.repo)
        if (session.workdirDropped) sessions.append(session.id, "system", mapOf("text" to DROPPED_NOTE))
        pods.start(session, config, snapshots.get(session.id) ?: FirstMateSnapshot(null, null, 0))
        log.info("first-mate session {} resumed on {} by @{}", session.id, session.repo, c.login)
    }

    @GetMapping("/first-mate/sessions/{id}/transcript")
    fun transcript(@PathVariable id: String, request: HttpServletRequest): ResponseEntity<Any> {
        val (c, refusal) = caller(request); c ?: return refusal!!
        val session = owned(id, c) ?: return noSession(id)
        return ResponseEntity.ok(view(session) + ("messages" to sessions.transcript(id)))
    }

    /** 204 ended; 200 when it already was (idempotent). */
    @DeleteMapping("/first-mate/sessions/{id}")
    fun end(@PathVariable id: String, request: HttpServletRequest): ResponseEntity<Any> {
        val (c, refusal) = caller(request); c ?: return refusal!!
        val session = owned(id, c) ?: return noSession(id)
        if (!sessions.end(id)) return ResponseEntity.ok().build()
        pods.stop(session)
        return ResponseEntity.noContent().build()
    }
}

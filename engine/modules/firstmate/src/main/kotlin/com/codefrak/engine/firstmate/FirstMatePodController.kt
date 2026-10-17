package com.codefrak.engine.firstmate

import com.codefrak.engine.badRequest
import com.codefrak.engine.errorResponse
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.firstmate.push.PushNotifier
import com.codefrak.engine.provenTokenId
import com.codefrak.engine.repos.EngineTokens
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
import java.util.Base64

/**
 * The session pod's side of a first-mate session (engine/README.md, "First-mate sessions"; docs/proposals/
 * leader-only-traffic.md, "Protocol"): the pod's relay pulls the phone's messages and pushes claude's output, so any
 * replica serves it. `GET .../inbox?after=&wait=` long-polls the messages after a seq ([FirstMateInbox]); `POST
 * .../inbox/ack {seq}` records the highest written to claude (never lowered); `POST .../outbox {from, lines}` stores
 * claude's lines once each, numbered `out_seq` in the pod's epoch, and answers `{accepted}`; `POST .../status {state,
 * code?}` notes the pod's state in the transcript; `GET .../snapshot` answers what a resumed pod restores. Only the
 * session's own `first-mate-pod` token is accepted (`BearerTokenFilter` keeps it on these paths; 401 without one, 403
 * with another).
 */
@RestController
class FirstMatePodController(
    private val sessions: FirstMateSessions, private val tokens: EngineTokens, private val inbox: FirstMateInbox,
    private val snapshots: FirstMateSnapshots, private val events: EngineEvents, private val push: PushNotifier,
) {
    companion object {
        const val NEEDS_TOKEN = "the session pod's first-mate-pod token is needed"
        const val WRONG_TOKEN = "only this session's first-mate-pod token reaches its pod endpoints"
        /** The most lines one outbox post may carry. */
        const val MAX_LINES = 500
        val STATES = setOf("starting", "running", "exited")
        private val JSON = jacksonObjectMapper()
    }

    /** Session [id] when the caller holds its pod token; else the refusal. */
    private fun session(id: String, request: HttpServletRequest): Pair<FirstMateSession?, ResponseEntity<Any>?> {
        val tokenId = request.provenTokenId() ?: return null to errorResponse(HttpStatus.UNAUTHORIZED, NEEDS_TOKEN)
        val token = tokens.get(tokenId)
        if (token?.purpose != EngineTokens.FIRST_MATE_POD || token.sessionId != id) return null to errorResponse(HttpStatus.FORBIDDEN, WRONG_TOKEN)
        val session = sessions.get(id) ?: return null to errorResponse(HttpStatus.NOT_FOUND, "no first-mate session $id")
        return session to null
    }

    private fun long(v: Any?): Long? = (v as? Number)?.takeIf { it is Int || it is Long || it is Short }?.toLong()

    /** The messages after [after], `[]` once [wait] seconds (at most 25) pass without one, `{"freeze": true}` when freezing. */
    @GetMapping("/first-mate/pod/{id}/inbox")
    fun inbox(@PathVariable id: String, @RequestParam(defaultValue = "0") after: Long, @RequestParam(defaultValue = "25") wait: Long,
              request: HttpServletRequest): ResponseEntity<Any> {
        val (s, refusal) = session(id, request); s ?: return refusal!!
        val answer = inbox.await(s, after, Duration.ofSeconds(wait.coerceIn(0, FirstMateInbox.MAX_WAIT.seconds)))
        return ResponseEntity.ok(if (answer.freeze) mapOf("freeze" to true) else answer.messages)
    }

    @PostMapping("/first-mate/pod/{id}/inbox/ack")
    fun ack(@PathVariable id: String, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<Any> {
        val (s, refusal) = session(id, request); s ?: return refusal!!
        val seq = long(body["seq"])?.takeIf { it >= 0 } ?: throw badRequest("seq is a whole number, 0 or more")
        return ResponseEntity.ok(mapOf("deliveredSeq" to sessions.ack(s.id, seq)))
    }

    /** Stores each line once (a retried or overlapping batch adds only what is new) and publishes a frame per new row. */
    @PostMapping("/first-mate/pod/{id}/outbox")
    fun outbox(@PathVariable id: String, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<Any> {
        val (s, refusal) = session(id, request); s ?: return refusal!!
        val from = long(body["from"])?.takeIf { it >= 1 } ?: throw badRequest("from is the first line's out_seq, 1 or more")
        val lines = (body["lines"] as? List<*>)?.takeIf { it.size <= MAX_LINES && it.all { l -> l is String } }
            ?: throw badRequest("lines is a list of at most $MAX_LINES strings")
        val parsed = lines.map { line -> runCatching { JSON.readValue(line as String, Any::class.java) }.getOrElse { line } }
        val (stored, accepted) = sessions.appendOutbox(s.id, from, parsed)
        for (row in stored) {
            KubernetesFirstMateSessionPods.claudeId(row.body)?.let { sessions.setClaudeSessionId(s.id, it) }
            events.publish(EngineEvent.FirstMate(s.repo, s.id, row.seq, row.body))
            push.chatAnswered(s, row.body)
        }
        return ResponseEntity.ok(mapOf("accepted" to accepted))
    }

    /** Notes the pod's state (`starting`, `running`, `exited` with its exit code) as a system row. */
    @PostMapping("/first-mate/pod/{id}/status")
    fun status(@PathVariable id: String, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<Any> {
        val (s, refusal) = session(id, request); s ?: return refusal!!
        val state = (body["state"] as? String)?.takeIf { it in STATES } ?: throw badRequest("state is one of ${STATES.joinToString(", ")}")
        val code = body["code"]?.let { long(it) ?: throw badRequest("code is a whole number") }
        val row = sessions.append(s.id, "system", linkedMapOf<String, Any?>("type" to "pod-status", "state" to state).apply { if (code != null) put("code", code) })
        events.publish(EngineEvent.FirstMate(s.repo, s.id, row.seq, row.body))
        return ResponseEntity.ok(row)
    }

    /** What a resumed pod restores: the Claude session id, its file and the working folder's tar.gz, base64; 404 without one. */
    @GetMapping("/first-mate/pod/{id}/snapshot")
    fun snapshot(@PathVariable id: String, request: HttpServletRequest): ResponseEntity<Any> {
        val (s, refusal) = session(id, request); s ?: return refusal!!
        val snap = snapshots.get(s.id) ?: return errorResponse(HttpStatus.NOT_FOUND, "first-mate session $id has no snapshot")
        val b64 = Base64.getEncoder()
        return ResponseEntity.ok(linkedMapOf("claudeSessionId" to s.claudeSessionId, "cwd" to s.cwd, "deliveredSeq" to s.deliveredSeq,
            "sessionFile" to snap.sessionFile?.let(b64::encodeToString), "workdirTar" to snap.workdirTar?.let(b64::encodeToString),
            "size" to snap.size, "dropped" to snap.dropped))
    }
}

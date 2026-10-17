package com.codefrak.engine.events

import com.codefrak.engine.board.targetRepo
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.CallerRefusal.refuseSmith
import com.codefrak.engine.CallerIdentity
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * `GET /events?repo=<owner/name|*>`: the engine's event stream (engine/README.md, "Events"), a `text/event-stream`
 * that never ends from the engine's side but at shutdown or when the client falls behind. `repo` is required (400
 * naming the target repos: there is no default repo); one that is not a target repo is refused as `GET /needs`
 * refuses it. Unauthenticated like every read, but a Smith sandbox, told by its source address, is 403 (503 when
 * Docker cannot say), and a request with an `Origin` header is 403, the DNS-rebinding guard the MCP endpoint applies.
 */
@RestController
class EventsController(
    private val bus: EngineEvents,
    private val repos: TargetRepos,
    private val sandbox: CallerIdentity,
    /** How long an idle stream goes before a `: ping`; [EngineEvents.PING_INTERVAL] but in a test. */
    private val ping: Duration,
) {
    @Autowired constructor(bus: EngineEvents, repos: TargetRepos, sandbox: CallerIdentity) : this(bus, repos, sandbox, EngineEvents.PING_INTERVAL)

    private val log = LoggerFactory.getLogger(EventsController::class.java)

    companion object {
        private val TEXT = MediaType("text", "plain", StandardCharsets.UTF_8)
    }

    @GetMapping("/events")
    fun events(@RequestParam(required = false) repo: String?, request: HttpServletRequest? = null): ResponseEntity<SseEmitter> {
        if (request?.getHeader("Origin") != null) throw ResponseStatusException(HttpStatus.FORBIDDEN, "a request with an Origin header is refused")
        refuseSmith(sandbox, request, log, "read the event stream")
        if (repo == null) throw ResponseStatusException(HttpStatus.BAD_REQUEST,
            "repo is required: a target repo (${repos.list().joinToString(", ") { it.fullName }.ifEmpty { "none yet" }}) or * for every repo")
        val subscription = if (repo == EngineEvent.FLEET) EngineEvent.FLEET else targetRepo(repos, repo)
        // No timeout of its own: the default would be spring.mvc.async.request-timeout, and the container would end every stream at it.
        val emitter = SseEmitter(0L)
        // Subscribed before hello is written, so every frame published after hello is on the stream.
        val sub = bus.subscribe(subscription)
        val end = Runnable { bus.unsubscribe(sub) }
        emitter.onCompletion(end); emitter.onTimeout(end); emitter.onError { end.run() }
        write(emitter, bus.hello(subscription))
        Thread.ofVirtual().name("events-${subscription}").start { drain(sub, emitter) }
        return ResponseEntity.ok()
            .header("Cache-Control", "no-cache")
            .header("X-Accel-Buffering", "no")
            .body(emitter)
    }

    /** The subscriber's own thread: its queue into the emitter, a `: ping` when nothing came for [ping]. */
    private fun drain(sub: EventSubscription, emitter: SseEmitter) {
        while (true) {
            val frame = sub.next(ping) ?: bus.ping
            if (frame == EventSubscription.CLOSE) break
            try { write(emitter, frame) } catch (e: Exception) {
                log.debug("events: a subscriber of {} went away: {}", sub.repo, e.toString())
                bus.unsubscribe(sub)
                runCatching { emitter.completeWithError(e) }
                return
            }
        }
        bus.unsubscribe(sub)
        runCatching { emitter.complete() }
    }

    /** Writes the frame as it is: the grammar is the bus's, not Spring's event builder's. */
    private fun write(emitter: SseEmitter, frame: String) = emitter.send(setOf(ResponseBodyEmitter.DataWithMediaType(frame, TEXT)))

}

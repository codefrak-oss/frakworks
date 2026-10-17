package com.codefrak.engine.firstmate

import com.codefrak.engine.CallerIdentity
import com.codefrak.engine.CallerRefusal.refuseSmith
import com.codefrak.engine.engineJson
import com.codefrak.engine.tokenLogin
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The desktop channel's endpoints (#100105, engine/docs/desktop-channel.md), each with the desktop's sign-in bearer
 * and never a Smith's (403 by source address):
 * `GET /desktop/channel?allowed=<bool>` holds the stream open (a `hello` frame `{type, id}`, then `capture` frames
 * `{type, requestId, pane}`, a `: ping` every 15 s); `POST /desktop/channel/{id}/state {allowed?, focused?}` reports the
 * setting and focus; `POST /desktop/capture/{requestId} {image, mimeType} | {error}` answers a capture.
 */
@RestController
class DesktopChannelController(private val channel: DesktopChannel, private val sandbox: CallerIdentity, private val ping: Duration) {
    @Autowired constructor(channel: DesktopChannel, sandbox: CallerIdentity) : this(channel, sandbox, Duration.ofSeconds(15))

    private val log = LoggerFactory.getLogger(DesktopChannelController::class.java)
    private val text = MediaType("text", "plain", StandardCharsets.UTF_8)

    @GetMapping("/desktop/channel")
    fun open(@RequestParam(required = false) allowed: Boolean?, request: HttpServletRequest?): ResponseEntity<SseEmitter> {
        refuseSmith(sandbox, request, log, "hold the desktop channel")
        val login = request.tokenLogin()
        val desktop = channel.connect(login, allowed == true)
        val emitter = SseEmitter(0L)
        val end = Runnable { channel.disconnect(desktop.id) }
        emitter.onCompletion(end); emitter.onTimeout(end); emitter.onError { end.run() }
        write(emitter, mapOf("type" to "hello", "id" to desktop.id))
        Thread.ofVirtual().name("desktop-${desktop.id}").start {
            try {
                while (channel.desktop(desktop.id) != null) {
                    val frame = desktop.frames.poll(ping.toMillis(), TimeUnit.MILLISECONDS)
                    if (frame == null) emitter.send(setOf(ResponseBodyEmitter.DataWithMediaType(": ping\n\n", text))) else write(emitter, frame)
                }
            } catch (e: Exception) {
                log.debug("desktop channel {} went away: {}", desktop.id, e.toString())
                runCatching { emitter.completeWithError(e) }
            } finally {
                channel.disconnect(desktop.id)
            }
        }
        return ResponseEntity.ok().header("Cache-Control", "no-cache").header("X-Accel-Buffering", "no").body(emitter)
    }

    @PostMapping("/desktop/channel/{id}/state")
    fun state(@PathVariable id: String, @RequestBody body: Map<String, Any?>, request: HttpServletRequest?): Map<String, Any?> {
        refuseSmith(sandbox, request, log, "report a desktop's state")
        val login = request.tokenLogin()
        if (!channel.report(login, id, body["allowed"] as? Boolean, body["focused"] as? Boolean))
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "no desktop channel $id of yours")
        return mapOf("ok" to true)
    }

    @PostMapping("/desktop/capture/{requestId}")
    fun capture(@PathVariable requestId: String, @RequestBody body: Map<String, Any?>, request: HttpServletRequest?): Map<String, Any?> {
        refuseSmith(sandbox, request, log, "answer a capture")
        val login = request.tokenLogin()
        channel.answer(login, requestId, body["image"] as? String, body["mimeType"] as? String, body["error"] as? String)
            ?.let { throw ResponseStatusException(if (it.startsWith("no capture")) HttpStatus.NOT_FOUND else HttpStatus.BAD_REQUEST, it) }
        return mapOf("ok" to true)
    }

    private fun write(emitter: SseEmitter, frame: Map<String, Any?>) =
        emitter.send(setOf(ResponseBodyEmitter.DataWithMediaType("data: ${engineJson.writeValueAsString(frame)}\n\n", text)))
}

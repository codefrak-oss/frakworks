package com.codefrak.engine.firstmate

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * The desktop channel (#100105, engine/docs/desktop-channel.md): each signed-in desktop app holds `GET /desktop/channel`
 * open, and [capture] asks one of its login's desktops for a screenshot over it and waits for the PNG it POSTs back.
 * In memory only: a connection is this replica's, and no image is ever kept once [capture] answers.
 *
 * Several desktops of one login: the one focused most recently answers, else the newest connection ([pick]).
 */
@Component
class DesktopChannel(private val timeout: Duration) {
    @Autowired constructor() : this(TIMEOUT)

    companion object {
        /** How long a tool call waits for the desktop's capture before it is refused. */
        val TIMEOUT: Duration = Duration.ofSeconds(15)
        val PANES = listOf("left", "middle", "right")
        /** The image cap, as `FirstMateImages`' per image. */
        const val MAX_BYTES = FirstMateImages.MAX_BYTES
        val MIME_TYPES = setOf("image/png", "image/jpeg")
        const val SETTING = "Let First mate screenshot this app"
        const val NO_DESKTOP = "no desktop app of yours is connected to the engine: open the desktop app and sign in, then ask again"
        const val SETTING_OFF = "the desktop app has \"$SETTING\" off (Config > frakworks): the user must turn it on first"
        val TIMED_OUT = "the desktop app did not answer within ${TIMEOUT.seconds} s"
    }

    private val seq = AtomicLong()

    /** One connected desktop: its frames to send, its setting and when it was last focused. */
    class Desktop(val id: String, val login: String, val connectedSeq: Long) {
        val frames = LinkedBlockingQueue<Map<String, Any?>>()
        @Volatile var allowed: Boolean = false
        @Volatile var focusedSeq: Long = 0
    }

    private class Pending(val login: String, val desktop: String, val answer: CompletableFuture<Capture>)

    sealed interface Capture {
        data class Image(val bytes: ByteArray, val mimeType: String) : Capture
        data class Refused(val why: String) : Capture
    }

    private val desktops = ConcurrentHashMap<String, Desktop>()
    private val pending = ConcurrentHashMap<String, Pending>()

    fun connect(login: String, allowed: Boolean): Desktop =
        Desktop(UUID.randomUUID().toString(), login.lowercase(), seq.incrementAndGet()).also { it.allowed = allowed; desktops[it.id] = it }

    fun disconnect(id: String) {
        desktops.remove(id)
        pending.values.filter { it.desktop == id }.forEach { it.answer.complete(Capture.Refused("the desktop app disconnected before it answered")) }
    }

    /** The desktop's own report: its setting, and whether its window has focus now. False for another login's or a gone one. */
    fun report(login: String, id: String, allowed: Boolean?, focused: Boolean?): Boolean {
        val d = desktops[id]?.takeIf { it.login == login.lowercase() } ?: return false
        if (allowed != null) d.allowed = allowed
        if (focused == true) d.focusedSeq = seq.incrementAndGet()
        return true
    }

    fun desktop(id: String): Desktop? = desktops[id]

    /** The login's desktop that answers: the most recently focused, else the newest connection. */
    fun pick(login: String): Desktop? = desktops.values.filter { it.login == login.lowercase() }
        .maxWithOrNull(compareBy<Desktop> { it.focusedSeq }.thenBy { it.connectedSeq })

    /** Asks [login]'s desktop for [pane] (null: the whole window) and waits up to the timeout. */
    fun capture(login: String, pane: String?): Capture {
        val d = pick(login) ?: return Capture.Refused(NO_DESKTOP)
        if (!d.allowed) return Capture.Refused(SETTING_OFF)
        val requestId = UUID.randomUUID().toString()
        val p = Pending(d.login, d.id, CompletableFuture())
        pending[requestId] = p
        try {
            d.frames.put(linkedMapOf("type" to "capture", "requestId" to requestId, "pane" to pane))
            return p.answer.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            return Capture.Refused(TIMED_OUT)
        } finally {
            pending.remove(requestId)
        }
    }

    /**
     * The desktop's answer to [requestId]: `{image: base64, mimeType}` or `{error}`. Null when taken; else why it is
     * refused (no such request, another login's, a bad image).
     */
    fun answer(login: String, requestId: String, image: String?, mimeType: String?, error: String?): String? {
        val p = pending[requestId]?.takeIf { it.login == login.lowercase() } ?: return "no capture request $requestId waits for you"
        if (error != null) { p.answer.complete(Capture.Refused("the desktop app refused: $error")); return null }
        val type = mimeType ?: "image/png"
        if (type !in MIME_TYPES) return "mimeType is image/png or image/jpeg"
        val bytes = image?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: return "image is base64 image data"
        if (bytes.size > MAX_BYTES) return "the image is over ${MAX_BYTES / (1024 * 1024)} MiB"
        p.answer.complete(Capture.Image(bytes, type))
        return null
    }
}

package com.codefrak.engine.hitlp

import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.lease.EngineLease
import com.codefrak.engine.lease.LeaseAcquired
import com.codefrak.engine.lease.LeaseLost
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * The HITLP server pushes task updates to the engine (#2463; `spec/hitlp.md` §7.4): on the instance holding the lease,
 * one thread keeps the session's standalone SSE stream open ([HitlpRequester.openStream]) and, on a
 * `notifications/tasks/status` with a terminal status, has [HitlpPoller.notified] read `tasks/result` and finish the
 * row. On every (re)connect it reconciles once ([HitlpPoller.reconcile]: `tasks/get` per open row), so a task that
 * ended while the stream was down is answered too. A dropped or failed stream is reconnected after [backoff], doubled
 * per failure up to [maxBackoff] and reset by a connect, each drop logged. Losing the lease closes the stream.
 *
 * The only timer: a server that answers the GET with 405 offers no stream. That is logged once (WARN, naming the
 * server) and the thread falls back to [HitlpPoller.poll] every [fallbackTick] (the spec's default `pollInterval`,
 * 5 s; each row is still read only at its own `pollInterval`). Nothing runs with no server configured.
 *
 * The server is a setting (#2500, [HitlpServerSettings]): a `setting` frame named `engine.hitlp` on this instance's
 * bus runs [sync], which restarts the stream when the URL it runs on is no longer the one set and stops it when the
 * server was cleared, so a `PUT /settings/hitlp` needs no restart. A replica that did not take the PUT reads the new
 * endpoint at its next reconnect, since every [HitlpRequester.openStream] reads it live.
 */
@Component
class HitlpTaskStream(
    private val requester: HitlpRequester,
    private val poller: HitlpPoller,
    private val lease: EngineLease,
    private val events: EngineEvents,
) {
    private val log = LoggerFactory.getLogger(HitlpTaskStream::class.java)

    /** The first reconnect delay, doubled per failure; a test shortens it. */
    var backoff: Duration = Duration.ofSeconds(1)
    /** The longest reconnect delay. */
    var maxBackoff: Duration = Duration.ofMinutes(1)
    /** The fallback loop's pass interval when the server offers no stream; a test shortens it. */
    var fallbackTick: Duration = Duration.ofMillis(HitlpHandle.DEFAULT_POLL_INTERVAL)

    @Volatile private var thread: Thread? = null
    @Volatile private var current: HitlpSseStream? = null
    /** The MCP URL the running thread was started for; a setting change to another URL restarts it. */
    @Volatile private var streamUrl: String? = null
    @Volatile private var warnedNoStream = false

    /** Whether the stream thread runs (the lease held and a server configured). */
    val running: Boolean get() = thread?.isAlive == true

    /** Whether the server answered the stream's GET with 405 and the fallback loop runs instead. */
    val fallingBack: Boolean get() = noStream
    @Volatile private var noStream = false

    /** Whether a stream is open now. */
    val connected: Boolean get() = current != null

    /**
     * How many streams this instance opened. A test reads it rather than counting GETs on its stub, which another cached
     * test context's stream, following the shared settings row, may reach too.
     */
    val opens: Int get() = openCount.get()
    private val openCount = AtomicInteger()

    @PostConstruct
    fun listen() = events.onPublish { if (it is EngineEvent.Setting && it.name == HitlpServerSettings.EVENT_NAME) sync() }

    @EventListener(ApplicationReadyEvent::class)
    fun atStartup() = sync()

    @EventListener(LeaseAcquired::class)
    fun acquired() = sync()

    @EventListener(LeaseLost::class)
    fun lost() = sync()

    @PreDestroy
    fun destroy() = stop()

    /**
     * Starts the stream thread when this instance holds the lease and a server is set, stops it when the lease is not
     * held or the server was cleared, and restarts it when the server's URL is no longer the one it runs on.
     */
    @Synchronized fun sync() {
        val url = requester.url
        if (url == null) { stop("no HITLP server is configured"); return }
        if (!lease.held()) { stop("lease not held"); return }
        if (running && streamUrl != url) stop("the HITLP server changed to $url")
        if (!running) start(url)
    }

    fun stop() = stop("lease not held")

    @Synchronized private fun stop(why: String) {
        val t = thread ?: return
        thread = null
        stopped = t
        current?.close()
        t.interrupt()
        log.info("hitlp stream closed ({})", why)
    }

    /** The thread [stop] last stopped, until it ends. */
    @Volatile private var stopped: Thread? = null

    /** Waits up to [timeout] for the stopped thread to end, its interrupted GET closed; a test reads the server only then. */
    fun awaitStopped(timeout: Duration = Duration.ofSeconds(10)) { stopped?.join(timeout) }

    private fun start(url: String) {
        streamUrl = url
        thread = Thread.ofVirtual().name("hitlp-stream").start { loop() }
    }

    private fun live() = thread === Thread.currentThread() && lease.held()

    private fun loop() {
        var delay = backoff
        while (live()) {
            try {
                val stream = requester.openStream()
                if (stream == null) { fallback(); return }
                current = stream
                if (!live()) { stream.close(); return }
                openCount.incrementAndGet()
                log.info("hitlp stream open on {}", streamUrl)
                delay = backoff
                poller.reconcile()
                stream.use { it.each(::onMessage) }
                if (live()) log.warn("hitlp stream on {} ended: reconnecting in {}", streamUrl, delay)
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                if (!live()) return
                log.warn("hitlp stream on {} failed: {}; reconnecting in {}", streamUrl, e.message, delay)
            } finally {
                current = null
            }
            try { Thread.sleep(delay.toMillis()) } catch (e: InterruptedException) { return }
            delay = minOf(delay.multipliedBy(2), maxBackoff)
        }
    }

    private fun onMessage(message: Map<String, Any?>) {
        if (message["method"] != "notifications/tasks/status") return
        val params = message["params"] as? Map<*, *> ?: return
        val taskId = params["taskId"]?.toString() ?: return
        poller.notified(taskId, params["status"]?.toString() ?: return)
    }

    private fun fallback() {
        if (!warnedNoStream) {
            warnedNoStream = true
            log.warn("hitlp server {} answered 405 to the stream GET: it offers no task stream, so the engine polls tasks/get every {}", streamUrl, fallbackTick)
        }
        noStream = true
        try {
            poller.reconcile()
            while (live()) {
                Thread.sleep(fallbackTick.toMillis())
                if (live()) poller.poll()
            }
        } catch (e: InterruptedException) {
            return
        } finally {
            noStream = false
        }
    }
}

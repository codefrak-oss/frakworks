package com.codefrak.engine.firstmate.push

import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.firstmate.FirstMateSession
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The first mate's pushes (engine/README.md, "Push notifications"): a `needs` frame with state `added` goes to the
 * devices of the login it is `for`, or to every registered device when it is for everyone; a session's stream-json
 * `result` line (the end of a reply, [chatAnswered]) goes to its owner's devices alone. Every push is sent on one
 * thread of its own with a bounded queue, never on the publishing or the request thread: a full queue drops the push
 * (DEBUG). APNs saying a device token is gone deletes the row; any other failure is logged once at WARN and not retried.
 * With no credentials or no device nothing is sent, and that is logged at DEBUG only.
 */
@Component
class PushNotifier(
    events: EngineEvents, private val devices: PushDevices, private val settings: ApnsSettings, private val client: ApnsClient,
) {
    private val log = LoggerFactory.getLogger(PushNotifier::class.java)
    private val json = jacksonObjectMapper()
    private val jwt = ApnsJwt()
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue(QUEUE)) { r -> Thread(r, "apns-push").apply { isDaemon = true } }
        .apply { setRejectedExecutionHandler { _, _ -> log.debug("APNs: {} pushes queued, one dropped", QUEUE) } }

    companion object {
        const val QUEUE = 500
        /** The `needs` frame state that means a row appeared (`removed` is the other). */
        const val ADDED = "added"
        /** The stream-json line that ends a reply. */
        const val RESULT = "result"

        /** What a `needs` row of [kind] reads as in a notification. */
        fun kindLabel(kind: String) = when (kind) {
            "question", "ask" -> "A question"
            "approval", "approve" -> "An approval"
            "acceptance" -> "An acceptance"
            "decision" -> "A decision"
            else -> "A ${kind.replace('-', ' ')}"
        }

        /** The notification body of [e]: its kind and `<repo>#<n>`, or the HITLP task's action, or its repo. */
        fun needsBody(e: EngineEvent.Needs): String {
            val where = when {
                e.number != null && e.repo != EngineEvent.FLEET -> "${e.repo}#${e.number}"
                e.action != null -> e.action!!
                e.repo != EngineEvent.FLEET -> e.repo
                else -> "the engine"
            }
            return "${kindLabel(e.kind)} waits for you: $where"
        }
    }

    init { events.onPublish(::onEvent) }

    private fun onEvent(event: EngineEvent) {
        if (event !is EngineEvent.Needs || event.state != ADDED) return
        val fm = linkedMapOf<String, Any?>("type" to "needs", "kind" to event.kind, "repo" to event.repo.takeIf { it != EngineEvent.FLEET },
            "number" to event.number, "actionId" to event.actionId)
        submit("needs ${event.kind} ${event.actionId}") {
            val targets = event.forLogin?.let { devices.forLogin(it) } ?: devices.all()
            Triple(targets, Alert("Needs you", needsBody(event)), fm)
        }
    }

    /** Session [s]'s outbox stored [line]; a `result` line pushes "your chat answered" to its owner's devices. */
    fun chatAnswered(s: FirstMateSession, line: Any?) {
        if ((line as? Map<*, *>)?.get("type") != RESULT) return
        submit("chat ${s.id}") {
            Triple(devices.forLogin(s.login), Alert("First mate answered", "Your chat on ${s.repo} has an answer."),
                linkedMapOf<String, Any?>("type" to "chat", "session" to s.id, "repo" to s.repo))
        }
    }

    private data class Alert(val title: String, val body: String)

    /** Queues one push; [what] names it in the logs, [plan] picks the devices, the alert and the `fm` payload on the push thread. */
    private fun submit(what: String, plan: () -> Triple<List<PushDevice>, Alert, Map<String, Any?>>) {
        executor.execute {
            try { send(what, plan()) } catch (e: Exception) { log.warn("APNs: {} not sent: {}", what, e.toString()) }
        }
    }

    private fun send(what: String, plan: Triple<List<PushDevice>, Alert, Map<String, Any?>>) {
        val (targets, alert, fm) = plan
        if (targets.isEmpty()) { log.debug("APNs: no device for {}", what); return }
        val c = settings.get() ?: run { log.debug("APNs: no credentials set; {} not sent", what); return }
        val payload = json.writeValueAsString(mapOf("aps" to mapOf("alert" to mapOf("title" to alert.title, "body" to alert.body), "sound" to "default"), "fm" to fm))
        val token = jwt.token(c)
        for (d in targets) {
            val request = ApnsRequest(d.environment, "/3/device/${d.deviceToken}",
                linkedMapOf("authorization" to "bearer $token", "apns-topic" to c.bundleId, "apns-push-type" to "alert", "apns-priority" to "10"), payload)
            when (val r = client.send(request)) {
                ApnsResult.Sent -> log.debug("APNs: {} sent to {}…", what, d.deviceToken.take(8))
                is ApnsResult.Gone -> {
                    devices.delete(d.deviceToken)
                    log.info("APNs: device {}… of @{} is gone ({} {}); its row is deleted", d.deviceToken.take(8), d.login, r.status, r.reason ?: "")
                }
                is ApnsResult.Failed -> log.warn("APNs: {} to {}… failed: {} {}", what, d.deviceToken.take(8), r.status ?: "no status", r.reason)
            }
        }
    }

    /** Waits until every queued push is sent, up to [timeout]; true when idle. For tests. */
    fun awaitIdle(timeout: Duration = Duration.ofSeconds(10)): Boolean {
        val end = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < end) {
            if (executor.queue.isEmpty() && executor.activeCount == 0) return true
            Thread.sleep(20)
        }
        return false
    }

    @PreDestroy fun stop() { executor.shutdownNow() }
}

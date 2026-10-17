package com.codefrak.engine.smith

import com.codefrak.engine.engineJson
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * One run's rows on their way to VictoriaLogs: `offer` (the stream's
 * thread) stamps a line and queues it, never blocking; a virtual thread
 * posts what is queued in batches. A full queue drops lines, a failed post
 * drops its batch; each is logged at WARN once per streak.
 */
internal class VictoriaLogsShipper(
    private val labels: RunLabels,
    private val logsUrl: String,
    private val timeout: Duration,
    private val http: HttpClient,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(VictoriaLogsShipper::class.java)
    private val queue = LinkedBlockingQueue<String>(MAX_QUEUED)
    @Volatile private var finishing = false
    private var last = Instant.EPOCH
    private var dropped = 0
    private var failing = false
    val thread: Thread = Thread.ofVirtual().name("smith-telemetry-${labels.runId.take(8)}").start(::loop)

    /** `_time` is when the engine read the line, strictly increasing per run so the reader's sort keeps the order. */
    @Synchronized fun offer(line: String) {
        val now = Instant.now().let { if (it.isAfter(last)) it else last.plusNanos(1_000) }
        last = now
        val row = engineJson.writeValueAsString(linkedMapOf(
            "_msg" to line, "_time" to now.toString(),
            "issue" to (labels.issue?.toString() ?: ""), "job" to (labels.jobId ?: ""), "host" to labels.host, "filename" to labels.filename,
            "phase" to labels.phase, "model" to labels.model, "repo" to labels.repo, "run" to labels.runId,
        ))
        if (!queue.offer(row) && dropped++ == 0)
            log.warn("run {}: transcript queue full ({} rows); dropping lines until it drains", labels.runId, MAX_QUEUED)
    }

    fun finish() { finishing = true }

    private fun loop() {
        while (true) {
            val first = try { queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { return }
            if (first == null) { if (finishing) return else continue }
            val batch = mutableListOf(first)
            queue.drainTo(batch, MAX_BATCH - 1)
            if (!post(batch) && finishing) { queue.clear(); return }
        }
    }

    private fun post(rows: List<String>): Boolean {
        val error = try {
            val uri = URI.create(logsUrl.trimEnd('/') + "/insert/jsonline?_stream_fields=issue,host,filename&_msg_field=_msg&_time_field=_time")
            val request = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Content-Type", "application/stream+json")
                .POST(HttpRequest.BodyPublishers.ofString(rows.joinToString("\n", postfix = "\n"))).build()
            val status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
            if (status in 200..299) null else "HTTP $status"
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); "interrupted"
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
        if (error == null) {
            if (failing) log.info("run {}: VictoriaLogs takes rows again", labels.runId)
            failing = false; dropped = 0
            return true
        }
        if (!failing) log.warn("run {}: VictoriaLogs post failed ({}), {} rows dropped; the run goes on", labels.runId, error, rows.size)
        else log.debug("run {}: VictoriaLogs post failed again ({}), {} rows dropped", labels.runId, error, rows.size)
        failing = true
        return false
    }

    private companion object {
        const val MAX_QUEUED = 10_000
        const val MAX_BATCH = 500
        const val POLL_MILLIS = 250L
    }
}

package com.codefrak.engine.mcp

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

/** One rendered event: its `_time` and its lines ([TranscriptFormat.formatEvent]). */
data class TranscriptEvent(val time: String?, val lines: List<String>)

/**
 * A ticket's newest Smith session. `live`: a `smith_run_active` series for the
 * ticket exists now; `liveKnown`: VictoriaMetrics answered, run or no run.
 * `session` is the newest log `filename`, null when nothing was shipped.
 * `error` says what failed; the rest is kept.
 */
data class Transcript(
    val ticket: Long, val live: Boolean = false, val liveKnown: Boolean = false,
    val host: String? = null, val kind: String? = null, val model: String? = null,
    val session: String? = null, val events: List<TranscriptEvent> = emptyList(), val error: String? = null,
)

/**
 * One Smith run read_transcript is asked for by id (#1162): its phase (the run's mode) and its start, which
 * bound the log query instead of [TranscriptReader.WINDOW]; `running` while its process instance has not ended.
 */
data class NamedRun(val runId: String, val phase: String, val started: Instant, val running: Boolean = false)

/**
 * Reads a ticket's newest Smith session for read_transcript (engine/README.md,
 * "Engine-served MCP servers"), from where [com.codefrak.engine.smith.SmithTelemetry] ships
 * it: the live run from VictoriaMetrics' `smith_run_active{issue=…}` (its
 * `host` narrows the log query), the events from VictoriaLogs, the newest
 * `filename` only. A port of the desktop's `src/transcript-data.js`
 * `fetchTranscript`. Read-only: one VictoriaMetrics query and two LogsQL
 * queries, built from the ticket number and the labels the stores return,
 * never from text a caller sent.
 */
@Component
class TranscriptReader(
    /** VictoriaLogs' base URL (ENGINE_TELEMETRY_LOGS_URL); empty: no transcript store. */
    @Value("\${engine.telemetry.logs-url:}") private val logsUrl: String,
    /** VictoriaMetrics' base URL (ENGINE_TELEMETRY_METRICS_URL); empty: whether a run is live stays unknown. */
    @Value("\${engine.telemetry.metrics-url:}") private val metricsUrl: String,
    /** Connect and request timeout of one query. */
    @Value("\${engine.telemetry.query-timeout:PT10S}") private val timeout: Duration,
) {
    /** One request: the URI, a form body for a POST (null: a GET); the status and body back. Tests replace it. */
    fun interface Http { fun send(uri: URI, form: String?): Pair<Int, String> }

    companion object {
        /** How far back to look for a run's log; long enough that a late read still finds the run's first event. */
        const val WINDOW = "24h"
        /** Most events one read returns: the newest. */
        const val LIMIT = 500

        fun activeRunQuery(ticket: Long) = "smith_run_active{issue=\"$ticket\"}"

        // The exact-match form (`field:="value"`): VictoriaLogs' word filter tokenises on `-`,
        // so `host:engine` would also match `engine-2`.
        private fun q(v: String) = mcpJson.writeValueAsString(v)

        fun sessionQuery(ticket: Long, host: String?) =
            "_time:$WINDOW issue:=${q(ticket.toString())}" + (host?.let { " AND host:=${q(it)}" } ?: "") +
                " | sort by (_time) desc | limit 1 | fields _time, filename, host, phase, model"

        fun eventsQuery(ticket: Long, host: String?, filename: String) =
            "_time:$WINDOW issue:=${q(ticket.toString())}" + (host?.let { " AND host:=${q(it)}" } ?: "") +
                " AND filename:=${q(filename)} | sort by (_time) desc | limit $LIMIT | fields _time, _msg, filename"

        /** A run's log `filename`, as [com.codefrak.engine.smith.RunLabels.filename] names it. */
        fun filename(ticket: Long, run: NamedRun) = "issue-$ticket-${run.phase}-${run.runId}.log"

        /**
         * A named run's events, bounded by its own start (a minute early for clock skew) rather than
         * [WINDOW], so a run days old is still found.
         */
        fun runEventsQuery(ticket: Long, run: NamedRun) =
            "_time:>=${run.started.minusSeconds(60)} issue:=${q(ticket.toString())} AND filename:=${q(filename(ticket, run))}" +
                " | sort by (_time) desc | limit $LIMIT | fields _time, _msg, filename, host, model"

        /** Rows (newest first, as queried) to the rendered events, oldest first; a row that is no JSON object, or renders to nothing, is dropped. */
        fun renderRows(rows: List<Map<*, *>>): List<TranscriptEvent> = rows.reversed().mapNotNull { row ->
            val raw = row["_msg"]?.toString().orEmpty()
            if (!raw.startsWith("{")) return@mapNotNull null
            val event = runCatching { mcpJson.readValue(raw, Map::class.java) }.getOrNull() ?: return@mapNotNull null
            TranscriptFormat.formatEvent(event).takeIf { it.isNotEmpty() }?.let { TranscriptEvent(row["_time"] as? String, it) }
        }
    }

    private val client: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(timeout).build() }

    var http: Http = Http { uri, form ->
        val request = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json")
        if (form != null) request.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form))
        val response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
        response.statusCode() to response.body()
    }

    /** Whether there is a transcript store to read at all. */
    val configured get() = logsUrl.isNotBlank()

    /** Never throws: a failed lookup becomes `error`, and what was read is kept. */
    fun read(ticket: Long): Transcript {
        var doc = Transcript(ticket)
        fun fail(what: String, e: Exception) {
            val msg = "$what: ${e.message ?: e.javaClass.simpleName}"
            doc = doc.copy(error = doc.error?.let { "$it | $msg" } ?: msg)
        }
        // No VictoriaMetrics configured: whether the run is live stays unknown, and the logs are read all the same.
        if (metricsUrl.isNotBlank()) try {
            val run = activeRun(ticket)
            doc = doc.copy(liveKnown = true)
            if (run != null) doc = doc.copy(live = true, host = run["host"] as? String, kind = run["kind"] as? String, model = run["model"] as? String)
        } catch (e: Exception) {
            // A dead VictoriaMetrics must not cost the transcript: the log query works without a host.
            fail("live-run lookup failed", e)
        }
        try {
            val session = logs(sessionQuery(ticket, doc.host)).firstOrNull() ?: return doc
            val filename = session["filename"] as? String ?: return doc
            doc = doc.copy(session = filename, host = session["host"] as? String ?: doc.host, model = doc.model ?: session["model"] as? String)
            doc = doc.copy(events = renderRows(logs(eventsQuery(ticket, doc.host, filename))))
        } catch (e: Exception) {
            fail("transcript lookup failed", e)
        }
        return doc
    }

    /** One named run's session; `session` stays null when nothing of it was shipped. Never throws. */
    fun read(ticket: Long, run: NamedRun): Transcript {
        var doc = Transcript(ticket, live = run.running, liveKnown = true, kind = run.phase)
        try {
            val rows = logs(runEventsQuery(ticket, run))
            if (rows.isEmpty()) return doc
            val first = rows.first()
            doc = doc.copy(session = filename(ticket, run), host = first["host"] as? String, model = first["model"] as? String,
                events = renderRows(rows))
        } catch (e: Exception) {
            doc = doc.copy(error = "transcript lookup failed: ${e.message ?: e.javaClass.simpleName}")
        }
        return doc
    }

    /** The live run's labels, or null: the gauge exists only while the run does, so none is "no live run". */
    private fun activeRun(ticket: Long): Map<*, *>? {
        val uri = URI.create(metricsUrl.trimEnd('/') + "/api/v1/query?query=" + URLEncoder.encode(activeRunQuery(ticket), StandardCharsets.UTF_8))
        val (status, body) = http.send(uri, null)
        if (status != 200) throw IllegalStateException("VictoriaMetrics HTTP $status")
        val parsed = try { mcpJson.readValue(body, Map::class.java) } catch (e: Exception) {
            throw IllegalStateException("VictoriaMetrics produced no JSON: ${e.message}")
        }
        val result = ((parsed["data"] as? Map<*, *>)?.get("result") as? List<*>).orEmpty()
        return (result.firstOrNull() as? Map<*, *>)?.let { it["metric"] as? Map<*, *> ?: emptyMap<String, Any>() }
    }

    /** One LogsQL query; VictoriaLogs answers one JSON object per line. */
    private fun logs(query: String): List<Map<*, *>> {
        val uri = URI.create(logsUrl.trimEnd('/') + "/select/logsql/query")
        val (status, body) = http.send(uri, "query=" + URLEncoder.encode(query, StandardCharsets.UTF_8))
        if (status != 200) throw IllegalStateException("VictoriaLogs HTTP $status")
        return body.lineSequence().filter { it.isNotBlank() }
            .mapNotNull { runCatching { mcpJson.readValue(it, Map::class.java) }.getOrNull() }.toList()
    }
}

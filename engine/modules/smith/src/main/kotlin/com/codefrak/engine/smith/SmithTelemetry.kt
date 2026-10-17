package com.codefrak.engine.smith

import com.codefrak.engine.promLabel
import com.codefrak.engine.lease.InstanceId
import com.codefrak.engine.accounts.RunningModels
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A live Smith's transcript and liveness, for the desktop's transcript pane
 * (`observability/desktop/src/transcript-data.js`). While a run is live its sandbox's stdout is
 * followed ([SmithSandbox.follow]) and each line becomes one VictoriaLogs row
 * (`/insert/jsonline`), and the run is one `smith_run_active` series on
 * `GET /metrics`, which VictoriaMetrics scrapes. The series leaves `/metrics`
 * when the sandbox's stream ends (it exited) or at `collect result`, whichever
 * is first; VictoriaMetrics' staleness marker then ends it.
 *
 * The same lines go to [SmithSessions], the engine's own copy of the session
 * (engine/README.md, "Summarize: a live run summarized"), so a run is followed
 * whenever there is a session store, even with shipping and `/metrics` off.
 *
 * Nothing here may touch a run: every call from a delegate is wrapped, rows are
 * posted from a thread of their own, and an unset, down or slow VictoriaLogs is
 * a WARN and dropped rows, never a failed job.
 */
@Component
class SmithTelemetry(
    private val sandbox: SmithSandbox,
    /** VictoriaLogs' base URL (ENGINE_TELEMETRY_LOGS_URL), e.g. http://victorialogs:9428; empty: no rows are shipped. */
    @Value("\${engine.telemetry.logs-url:}") private val logsUrl: String,
    /** Whether `GET /metrics` serves the gauge (ENGINE_TELEMETRY_METRICS_ENABLED); false: it answers 404. */
    @Value("\${engine.telemetry.metrics.enabled:true}") val metricsEnabled: Boolean,
    /** The `host` label and field: which engine instance follows the run (its hostname, a pod's name; [InstanceId]). */
    instance: InstanceId,
    /** Connect and request timeout of one VictoriaLogs post. */
    @Value("\${engine.telemetry.timeout:PT5S}") private val timeout: Duration,
    /** Where each line is stored in the engine's database; null (unit tests) stores nothing. */
    private val sessions: SmithSessions? = null,
    /** The plugins' series (engine/README.md, "Custom jobs and the SDK"), appended to [metrics]; null (unit tests) appends none. */
    private val plugins: PluginMetrics? = null,
    /** The engine's GitHub calls and rate limit ([com.codefrak.engine.github.GitHubCallMeter]), appended to [metrics]; null (unit tests) appends none. */
    private val github: com.codefrak.engine.github.GitHubCallMeter? = null,
) : RunningModels {
    private val log = LoggerFactory.getLogger(SmithTelemetry::class.java)
    private val host = instance.name
    private val live = ConcurrentHashMap<String, Live>()
    private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(timeout).build() }
    private val shipping get() = logsUrl.isNotBlank()

    private inner class Live(val labels: RunLabels, val handle: String, val shipper: VictoriaLogsShipper?) {
        @Volatile var follow: AutoCloseable? = null
        @Volatile var exiting = false
        val refollows = AtomicInteger(0)
    }

    /** `start smith`: the sandbox is running. Idempotent per run id (a retried start adopts the same sandbox). */
    fun started(spec: SmithSpec, handle: String) = track(RunLabels.of(spec, host), handle, fromNow = false)

    /** The labels [started] tracks `spec` under, without tracking: what the `session` frame carries (engine/README.md, "Events"). */
    fun labels(spec: SmithSpec): RunLabels = RunLabels.of(spec, host)

    /** The same, for a run whose spec is gone (`collect result`): from the run's variables. */
    fun labels(runId: String, issue: Long?, repo: String, model: String, phase: String, account: String?, jobId: String? = null): RunLabels =
        RunLabels(runId, issue, repo, model, phase, host, account, jobId)

    /** A run already live when the engine started: follow from now on, so nothing it shipped before is sent twice. */
    fun resumed(labels: RunLabels, handle: String) = track(labels, handle, fromNow = true)

    private fun track(labels: RunLabels, handle: String, fromNow: Boolean) {
        if (!shipping && !metricsEnabled && sessions == null) return
        val run = Live(labels, handle, if (shipping) VictoriaLogsShipper(labels, logsUrl, timeout, http) else null)
        if (live.putIfAbsent(labels.runId, run) != null) { run.shipper?.finish(); return }
        runCatching { sessions?.open(labels) }.onFailure { log.warn("run {}: session not stored: {}", labels.runId, it.message) }
        follow(run, fromNow)
        log.info("run {}: following sandbox {} for the transcript and smith_run_active (filename {})",
            labels.runId, handle.take(12), labels.filename)
    }

    private fun follow(run: Live, fromNow: Boolean) {
        run.follow = runCatching {
            sandbox.follow(run.handle, fromNow, { line -> run.shipper?.offer(line); sessions?.offer(run.labels.runId, line) }, { error -> streamEnded(run, error) })
        }.onFailure { log.warn("run {}: cannot follow sandbox {}: {}", run.labels.runId, run.handle.take(12), it.message) }.getOrNull()
    }

    /**
     * The followed stream ended. An exited sandbox ends the run's telemetry now;
     * a stream that dropped while the sandbox still runs is followed again from
     * now, a few times. Anything else leaves the gauge to `collect result`.
     */
    /**
     * This instance lost the lease (engine/README.md, "Replicas and the lease"): stop following every live run, without
     * ending it. Its session stays open and its series leaves `/metrics` here; the new holder resumes both
     * ([SmithTelemetryResume]) from its own start.
     */
    fun handOff() {
        for (run in live.values.toList()) {
            val mine = synchronized(run) { if (run.exiting) false else { run.exiting = true; true } }
            if (!mine) continue
            runCatching { run.follow?.close() }
            run.shipper?.finish()
            runCatching { sessions?.detach(run.labels.runId) }
            live.remove(run.labels.runId, run)
            log.info("run {}: no longer followed here (lease handed off)", run.labels.runId)
        }
    }

    private fun streamEnded(run: Live, error: Throwable?) {
        if (live[run.labels.runId] !== run || run.exiting) return
        val running = runCatching { sandbox.status(run.handle).running }.getOrNull()
        when {
            running == false -> ended(run.labels.runId)
            running == true && run.refollows.incrementAndGet() <= MAX_REFOLLOWS -> {
                log.info("run {}: log stream of {} ended while it runs ({}); following again", run.labels.runId, run.handle.take(12), error?.message)
                follow(run, fromNow = true)
            }
            else -> log.warn("run {}: log stream of {} ended ({}); the gauge ends at collect", run.labels.runId, run.handle.take(12), error?.message)
        }
    }

    /**
     * The run is over (its stream ended, or `collect result`): stop following,
     * let the shipper post what it holds and the session store what it holds
     * (then set `ended_at`), then take the series off `/metrics`,
     * at most [DRAIN] later. Idempotent; returns at once.
     */
    fun ended(runId: String) {
        val run = live[runId] ?: return
        synchronized(run) { if (run.exiting) return; run.exiting = true }
        runCatching { run.follow?.close() }
        run.shipper?.finish()
        runCatching { sessions?.close(runId) }
        Thread.ofVirtual().name("smith-telemetry-end-${runId.take(8)}").start {
            runCatching { run.shipper?.thread?.join(DRAIN) }
            live.remove(runId, run)
            log.info("run {}: smith_run_active ended", runId)
        }
    }

    /**
     * `collect result` read the run's own cost and turns: what its live tally counted, for the log,
     * and the tally forgotten (SmithSessions.forget). Null with no session store or no tally.
     */
    fun collected(runId: String): LiveFigures? {
        val figures = runCatching { sessions?.live(runId) }.getOrNull()
        sessions?.forget(runId)
        return figures
    }

    /** The runs `/metrics` shows as live. */
    fun active(): List<RunLabels> = live.values.map { it.labels }

    /**
     * The live runs per account and model, for `GET /accounts`'s `running`:
     * each account's models sorted by count descending, then by model id.
     * A run with no account (a dry run, or one resumed without a recorded
     * `billedAccount`) is counted nowhere.
     */
    override fun running(): Map<String, List<ModelCount>> =
        active().filter { it.account != null }.groupBy { it.account!! }.mapValues { (_, runs) ->
            runs.groupingBy { it.model }.eachCount().map { (model, n) -> ModelCount(model, n) }
                .sortedWith(compareByDescending<ModelCount> { it.count }.thenBy { it.model })
        }

    /** Whether `runId`'s series is on `/metrics`. */
    fun isActive(runId: String) = live.containsKey(runId)

    /** The Prometheus text exposition `GET /metrics` serves. */
    fun metrics(now: Instant = Instant.now()): String = buildString {
        append("# HELP smith_run_active 1 while an engine-started Smith run is live; the series is absent otherwise.\n")
        append("# TYPE smith_run_active gauge\n")
        for (r in active().sortedBy { it.runId }) {
            // `job` is on a job run's series only: a ticket run's series stays as the dashboards know it.
            append("smith_run_active{host=\"").append(promLabel(r.host)).append("\",issue=\"").append(r.issue ?: "")
            r.jobId?.let { append("\",job=\"").append(promLabel(it)) }
            append("\",kind=\"").append(promLabel(r.kind)).append("\",model=\"").append(promLabel(r.model))
                .append("\",repo=\"").append(promLabel(r.repo)).append("\"} 1\n")
        }
        plugins?.let { append(it.render()) }
        github?.let { append(it.render()) }
        append("# HELP smith_fleet_last_scrape_timestamp_seconds When the engine last answered /metrics, unix seconds.\n")
        append("# TYPE smith_fleet_last_scrape_timestamp_seconds gauge\n")
        append("smith_fleet_last_scrape_timestamp_seconds{host=\"").append(promLabel(host)).append("\"} ").append(now.epochSecond).append('\n')
    }

    private companion object {
        const val MAX_REFOLLOWS = 5
        val DRAIN: Duration = Duration.ofSeconds(10)
    }
}

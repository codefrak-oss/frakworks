package com.codefrak.engine.sdk

import com.codefrak.engine.smith.FakeSandbox
import com.codefrak.engine.smith.SmithSpec
import com.codefrak.engine.smith.SmithTelemetry
import com.codefrak.sdk.Metrics
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The SDK's [Metrics], used as a plugin would: each series is on `GET /metrics`' text after `smith_run_active`, prefixed `frak_plugin_`. */
class EngineMetricsTest {
    private val registry = EngineMetrics()
    private val metrics: Metrics = registry
    private val sandbox = FakeSandbox()
    private val telemetry = SmithTelemetry(sandbox, "", metricsEnabled = true, instance = com.codefrak.engine.lease.InstanceId("engine"), timeout = Duration.ofSeconds(1), plugins = registry)

    @Test
    fun `a counter and a gauge render after smith_run_active with their labels, and a job run's series carries its job id`() {
        metrics.counter("tickets_filed_total", mapOf("repo" to "acme/widgets")).increment()
        metrics.counter("tickets_filed_total", mapOf("repo" to "acme/widgets")).increment(2.0)
        var depth = 3.0
        metrics.gauge("queue_depth") { depth }
        telemetry.started(SmithSpec("run-job", "acme/widgets", null, "claude-sonnet-5", "low", dryRun = true, mode = "job", jobId = "job-7"), "container-x")

        val text = telemetry.metrics()
        val active = text.indexOf("smith_run_active{")
        val plugin = text.indexOf("frak_plugin_")
        assertTrue(active in 0 until plugin, "plugin series come after smith_run_active:\n$text")
        assertTrue("smith_run_active{host=\"engine\",issue=\"\",job=\"job-7\",kind=\"job\",model=\"claude-sonnet-5\",repo=\"acme/widgets\"} 1" in text, text)
        assertTrue("# TYPE frak_plugin_tickets_filed_total counter" in text, text)
        assertTrue("frak_plugin_tickets_filed_total{repo=\"acme/widgets\"} 3.0" in text, text)
        assertTrue("frak_plugin_queue_depth 3.0" in text, text)
        depth = 4.0
        assertTrue("frak_plugin_queue_depth 4.0" in telemetry.metrics(), "a gauge is read at each scrape")
    }

    @Test
    fun `a name that is not a metric name is refused, and the same name and labels is one series`() {
        assertFailsWith<IllegalArgumentException> { metrics.counter("not a name") }
        metrics.counter("runs", mapOf("a" to "1")).increment()
        metrics.counter("runs", mapOf("a" to "1")).increment()
        assertEquals(1, registry.render().lines().count { it.startsWith("frak_plugin_runs{") })
        assertTrue("frak_plugin_runs{a=\"1\"} 2.0" in registry.render())
    }
}

package com.codefrak.engine.smith

import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** `smith_run_active` for VictoriaMetrics to scrape (observability/vmetrics/scrape.local.yml). */
@RestController
class SmithMetricsController(private val telemetry: SmithTelemetry) {
    @GetMapping("/metrics")
    fun metrics(): ResponseEntity<String> =
        if (!telemetry.metricsEnabled) ResponseEntity.notFound().build()
        else ResponseEntity.ok().contentType(MediaType.parseMediaType("text/plain; version=0.0.4; charset=utf-8")).body(telemetry.metrics())
}

/** The plugins' series for `GET /metrics` (engine/README.md, "Custom jobs and the SDK"): the root's `EngineMetrics`. */
fun interface PluginMetrics {
    /** The Prometheus text of every series; empty with none. */
    fun render(): String
}

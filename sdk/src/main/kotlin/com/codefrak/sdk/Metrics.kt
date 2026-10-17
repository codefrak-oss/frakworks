package com.codefrak.sdk

/** A monotonically increasing count. */
interface Counter {
    fun increment(by: Double = 1.0)
}

/**
 * Metrics for a plugin (engine/README.md, "Custom jobs and the SDK"): each series lands on the engine's
 * `GET /metrics` after `smith_run_active`, named `frak_plugin_<name>` with its labels, so the fleet's
 * VictoriaMetrics scrapes it with the engine's own. A name is `[a-zA-Z_:][a-zA-Z0-9_:]*`; the same name and
 * labels is the same series.
 */
interface Metrics {
    /** The counter for [name] and [labels], made on first use. */
    fun counter(name: String, labels: Map<String, String> = emptyMap()): Counter
    /** Registers a gauge read from [supplier] at each scrape; a later call with the same name and labels replaces it. */
    fun gauge(name: String, labels: Map<String, String> = emptyMap(), supplier: () -> Double)
}

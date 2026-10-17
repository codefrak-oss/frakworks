package com.codefrak.engine.smith

/** The plugins' series for `GET /metrics` (engine/README.md, "Custom jobs and the SDK"): the root's `EngineMetrics`. */
fun interface PluginMetrics {
    /** The Prometheus text of every series; empty with none. */
    fun render(): String
}

package com.codefrak.engine.sdk

import com.codefrak.sdk.Counter
import com.codefrak.sdk.Metrics
import com.codefrak.engine.promLabel
import com.codefrak.engine.smith.PluginMetrics
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.DoubleAdder

/**
 * [Metrics] for plugins (engine/README.md, "Custom jobs and the SDK"): one registry for the engine, whose series
 * `SmithTelemetry.metrics` appends to `GET /metrics` after `smith_run_active`, each named `frak_plugin_<name>`.
 * A series is its name and labels; a counter is kept, a gauge is read at each [render].
 */
@Component
class EngineMetrics : Metrics, PluginMetrics {
    private data class Key(val name: String, val labels: Map<String, String>)
    private class PluginCounter : Counter {
        val value = DoubleAdder()
        override fun increment(by: Double) { value.add(by) }
    }

    private val counters = ConcurrentHashMap<Key, PluginCounter>()
    private val gauges = ConcurrentHashMap<Key, () -> Double>()

    override fun counter(name: String, labels: Map<String, String>): Counter = counters.computeIfAbsent(Key(check(name), labels.toSortedMap())) { PluginCounter() }

    override fun gauge(name: String, labels: Map<String, String>, supplier: () -> Double) { gauges[Key(check(name), labels.toSortedMap())] = supplier }

    /** The Prometheus text of every series, counters then gauges, each name once with its HELP and TYPE; empty with none. */
    override fun render(): String = buildString {
        val byName = (counters.keys.map { it to "counter" } + gauges.keys.map { it to "gauge" }).groupBy { it.first.name }.toSortedMap()
        for ((name, keys) in byName) {
            val full = PREFIX + name
            val type = keys.first().second
            append("# HELP ").append(full).append(" A plugin's ").append(type).append(".\n# TYPE ").append(full).append(' ').append(type).append('\n')
            for ((key, kind) in keys.sortedBy { it.first.labels.toString() }) {
                val value = if (kind == "counter") counters[key]!!.value.sum() else runCatching { gauges[key]!!() }.getOrElse { Double.NaN }
                append(full)
                if (key.labels.isNotEmpty()) append(key.labels.entries.joinToString(",", "{", "}") { (k, v) -> "$k=\"${promLabel(v)}\"" })
                append(' ').append(value).append('\n')
            }
        }
    }

    private fun check(name: String): String = name.takeIf { NAME.matches(it) } ?: throw IllegalArgumentException("not a metric name: $name")

    companion object {
        const val PREFIX = "frak_plugin_"
        private val NAME = Regex("[a-zA-Z_:][a-zA-Z0-9_:]*")
    }
}

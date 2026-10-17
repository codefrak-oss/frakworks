package com.codefrak.engine.smith

import com.codefrak.engine.accounts.MeterReading
import com.codefrak.engine.engineJson
import tools.jackson.module.kotlin.readValue
import java.time.OffsetDateTime

/**
 * `meter.json` as `engine/smith/meter-proxy.py` writes it: `observedAt`, the
 * reply's HTTP `status`, and each `anthropic-ratelimit-unified-*` header by
 * its full name (null when the reply lacked it).
 */
data class SandboxMeter(val observedAt: OffsetDateTime?, val httpStatus: Int, val reading: MeterReading?) {
    companion object {
        fun parse(bytes: ByteArray): SandboxMeter {
            val m: Map<String, Any?> = engineJson.readValue(bytes)
            val headers = m.filter { (k, v) -> k.startsWith(MeterReading.PREFIX) && v != null }
                .mapValues { (_, v) -> listOf(v.toString()) }
            val observedAt = (m["observedAt"] as? String)?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
            val status = (m["status"] as? Number)?.toInt() ?: error("no HTTP status in meter.json")
            return SandboxMeter(observedAt, status, MeterReading.of(headers))
        }
    }
}

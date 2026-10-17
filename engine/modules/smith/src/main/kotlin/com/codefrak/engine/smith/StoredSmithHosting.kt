package com.codefrak.engine.smith

import com.codefrak.engine.engineSetting
import com.codefrak.engine.upsertEngineSetting
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * [SmithHosting] in `engine.engine_setting`: rows `smith.hosting`, `smith.k8s.namespace`, `smith.k8s.image` and
 * `smith.k8s.engine-url` (V51 seeds them, `docker` and blanks), and `smith.k8s.memory-request-bytes` and
 * `smith.k8s.cpu-request` (V56 seeds them, 1 GiB and 0.5).
 */
@Repository
class StoredSmithHosting(private val jdbc: JdbcClient) : SmithHosting {
    override fun get(): SmithHostingSettings = SmithHostingSettings(
        hosting = read(SmithHosting.HOSTING), namespace = read(SmithHosting.NAMESPACE), image = read(SmithHosting.IMAGE), engineUrl = read(SmithHosting.ENGINE_URL),
        memoryRequestBytes = read(SmithHosting.MEMORY_REQUEST).toLong(), cpuRequest = read(SmithHosting.CPU_REQUEST).toDouble(),
    )

    override fun model(): HostingModel = HostingModel.of(read(SmithHosting.HOSTING))
        ?: throw IllegalStateException("${SmithHosting.HOSTING} in engine.engine_setting is neither docker nor kubernetes")

    override fun set(settings: SmithHostingSettings) {
        write(SmithHosting.HOSTING, settings.hosting); write(SmithHosting.NAMESPACE, settings.namespace)
        write(SmithHosting.IMAGE, settings.image); write(SmithHosting.ENGINE_URL, settings.engineUrl)
        write(SmithHosting.MEMORY_REQUEST, settings.memoryRequestBytes.toString()); write(SmithHosting.CPU_REQUEST, settings.cpuRequest.toString())
    }

    private fun read(name: String): String = jdbc.engineSetting(name)
        ?: throw IllegalStateException("no $name row in engine.engine_setting (V51 or V56 seeds it)")

    private fun write(name: String, value: String) = jdbc.upsertEngineSetting(name, value)
}

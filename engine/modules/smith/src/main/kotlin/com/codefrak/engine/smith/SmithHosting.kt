package com.codefrak.engine.smith

import com.codefrak.engine.engineSetting
import com.codefrak.engine.upsertEngineSetting
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** The two places a Smith can run (engine/README.md, "Smith runs", "Hosting models"). */
enum class HostingModel(val value: String) {
    DOCKER("docker"), KUBERNETES("kubernetes");

    companion object {
        fun of(value: String): HostingModel? = entries.firstOrNull { it.value == value }
    }
}

/**
 * The hosting model and the Kubernetes model's settings: the namespace its Jobs run in, the Smith
 * image as the cluster pulls it, the engine's URL as a pod reaches it (`ENGINE_URL` in the sandbox), and a Smith
 * pod's memory and CPU requests (Burstable: its limits stay `engine.smith.memory-bytes` and `engine.smith.cpus`).
 */
data class SmithHostingSettings(
    val hosting: String, val namespace: String, val image: String, val engineUrl: String,
    val memoryRequestBytes: Long = DEFAULT_MEMORY_REQUEST, val cpuRequest: Double = DEFAULT_CPU_REQUEST,
) {
    /** The Kubernetes settings still blank, by their API names: a Kubernetes run cannot start while any is. */
    fun missing(): List<String> = buildList {
        if (namespace.isBlank()) add("namespace")
        if (image.isBlank()) add("image")
        if (engineUrl.isBlank()) add("engineUrl")
    }

    companion object {
        /** What V56 seeds: 1 GiB and half a CPU. */
        const val DEFAULT_MEMORY_REQUEST = 1L shl 30
        const val DEFAULT_CPU_REQUEST = 0.5
    }
}

/**
 * Where new Smith runs start, engine-wide: rows `smith.hosting`, `smith.k8s.namespace`, `smith.k8s.image` and
 * `smith.k8s.engine-url` of `engine.engine_setting` (V51 seeds them, `docker` and blanks), and
 * `smith.k8s.memory-request-bytes` and `smith.k8s.cpu-request` (V56 seeds them, 1 GiB and 0.5). Read at every start
 * (`SmithSandboxes`); a run already started keeps the model it started on.
 */
@Repository
class SmithHosting(private val jdbc: JdbcClient) {
    fun get(): SmithHostingSettings = SmithHostingSettings(
        hosting = read(HOSTING), namespace = read(NAMESPACE), image = read(IMAGE), engineUrl = read(ENGINE_URL),
        memoryRequestBytes = read(MEMORY_REQUEST).toLong(), cpuRequest = read(CPU_REQUEST).toDouble(),
    )

    fun model(): HostingModel = HostingModel.of(read(HOSTING))
        ?: throw IllegalStateException("$HOSTING in engine.engine_setting is neither docker nor kubernetes")

    fun set(settings: SmithHostingSettings) {
        write(HOSTING, settings.hosting); write(NAMESPACE, settings.namespace)
        write(IMAGE, settings.image); write(ENGINE_URL, settings.engineUrl)
        write(MEMORY_REQUEST, settings.memoryRequestBytes.toString()); write(CPU_REQUEST, settings.cpuRequest.toString())
    }

    private fun read(name: String): String = jdbc.engineSetting(name)
        ?: throw IllegalStateException("no $name row in engine.engine_setting (V51 or V56 seeds it)")

    private fun write(name: String, value: String) = jdbc.upsertEngineSetting(name, value)

    companion object {
        const val HOSTING = "smith.hosting"
        const val NAMESPACE = "smith.k8s.namespace"
        const val IMAGE = "smith.k8s.image"
        const val ENGINE_URL = "smith.k8s.engine-url"
        const val MEMORY_REQUEST = "smith.k8s.memory-request-bytes"
        const val CPU_REQUEST = "smith.k8s.cpu-request"
    }
}

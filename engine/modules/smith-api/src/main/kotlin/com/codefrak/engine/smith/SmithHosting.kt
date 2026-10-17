package com.codefrak.engine.smith

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
 * Where new Smith runs start, engine-wide: the hosting model and the Kubernetes model's settings, read at every start
 * (`SmithSandboxes`); a run already started keeps the model it started on. The store is `StoredSmithHosting`
 * (engine-smith, rows of `engine.engine_setting`); every module above programs against this.
 */
interface SmithHosting {
    fun get(): SmithHostingSettings

    /** The model new runs start on; throws when the row is neither docker nor kubernetes. */
    fun model(): HostingModel

    fun set(settings: SmithHostingSettings)

    companion object {
        const val HOSTING = "smith.hosting"
        const val NAMESPACE = "smith.k8s.namespace"
        const val IMAGE = "smith.k8s.image"
        const val ENGINE_URL = "smith.k8s.engine-url"
        const val MEMORY_REQUEST = "smith.k8s.memory-request-bytes"
        const val CPU_REQUEST = "smith.k8s.cpu-request"
    }
}

package com.codefrak.engine.smith

import com.codefrak.engine.badRequest
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** Unauthenticated, like the rest of the engine's API: where new Smith runs start. */
@RestController
class SmithHostingController(
    private val store: SmithHosting,
    private val events: EngineEvents,
    private val inCluster: InCluster,
    /** A Smith pod's limits, which its requests may not pass. */
    @Value("\${engine.smith.memory-bytes:4294967296}") private val memoryLimit: Long = 4294967296L,
    @Value("\${engine.smith.cpus:2}") private val cpuLimit: Double = 2.0,
) {
    private val log = LoggerFactory.getLogger(SmithHostingController::class.java)

    /** Once, at startup: in a pod a `docker` row means no run can start until it is set to kubernetes. */
    @EventListener(ApplicationReadyEvent::class)
    fun warnDockerInPod() {
        if (inCluster.inPod() && store.get().hosting == HostingModel.DOCKER.value)
            log.warn("smith.hosting is docker, but the engine runs in a pod with no Docker socket: no Docker run can start; " +
                "PUT /settings/smith-hosting {\"hosting\": \"kubernetes\"} (engine/README.md, \"Hosting models\")")
    }

    @GetMapping("/settings/smith-hosting")
    fun get(): SmithHostingSettings = store.get()

    /**
     * Body: any of `{"hosting": "docker"|"kubernetes", "namespace": "…", "image": "…", "engineUrl": "…"}`, all
     * strings, and `"memoryRequestBytes"` and `"cpuRequest"`, positive numbers; a key left out keeps its value. A
     * request above its limit (`engine.smith.memory-bytes`, `engine.smith.cpus`) is a 400 naming it. A namespace that is not a DNS label, an engine URL that is not
     * http(s), another key, `kubernetes` while a Kubernetes setting is blank, or `docker` while the engine runs
     * in a pod ([InCluster]) is a 400 and changes nothing.
     */
    @PutMapping("/settings/smith-hosting")
    fun set(@RequestBody body: Map<String, Any?>): SmithHostingSettings {
        val unknown = body.keys - KEYS
        if (unknown.isNotEmpty() || body.isEmpty()) throw badRequest("the body has any of ${KEYS.joinToString()}")
        fun str(key: String): String? = body[key]?.let { it as? String ?: throw badRequest("$key is a string") }?.trim()
        fun num(key: String): Number? = body[key]?.let { it as? Number ?: throw badRequest("$key is a number") }
        val current = store.get()
        val next = SmithHostingSettings(
            hosting = str("hosting") ?: current.hosting,
            namespace = str("namespace") ?: current.namespace,
            image = str("image") ?: current.image,
            engineUrl = str("engineUrl") ?: current.engineUrl,
            memoryRequestBytes = num("memoryRequestBytes")?.toLong() ?: current.memoryRequestBytes,
            cpuRequest = num("cpuRequest")?.toDouble() ?: current.cpuRequest,
        )
        val model = HostingModel.of(next.hosting) ?: throw badRequest("hosting is docker or kubernetes")
        if (model == HostingModel.DOCKER && str("hosting") != null && inCluster.inPod()) throw badRequest(InCluster.NO_DOCKER)
        if (next.namespace.isNotEmpty() && !DNS_LABEL.matches(next.namespace)) throw badRequest("namespace ${next.namespace} is not a DNS label")
        if (next.engineUrl.isNotEmpty() && !Regex("^https?://\\S+$").matches(next.engineUrl)) throw badRequest("engineUrl is an http(s) URL")
        if (next.memoryRequestBytes <= 0) throw badRequest("memoryRequestBytes is positive")
        if (next.memoryRequestBytes > memoryLimit)
            throw badRequest("memoryRequestBytes ${next.memoryRequestBytes} is above the memory limit $memoryLimit (engine.smith.memory-bytes)")
        if (next.cpuRequest <= 0) throw badRequest("cpuRequest is positive")
        if (next.cpuRequest > cpuLimit) throw badRequest("cpuRequest ${next.cpuRequest} is above the CPU limit $cpuLimit (engine.smith.cpus)")
        if (model == HostingModel.KUBERNETES && next.missing().isNotEmpty())
            throw badRequest("kubernetes needs ${next.missing().joinToString()} set first")
        store.set(next)
        events.publish(EngineEvent.Setting(SmithHosting.HOSTING, next.hosting))
        return next
    }

    private companion object {
        val KEYS = setOf("hosting", "namespace", "image", "engineUrl", "memoryRequestBytes", "cpuRequest")
        val DNS_LABEL = Regex("^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$")
    }
}

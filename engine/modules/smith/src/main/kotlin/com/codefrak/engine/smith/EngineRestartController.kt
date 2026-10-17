package com.codefrak.engine.smith

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.io.File
import java.time.Duration

/** Where a restart went: the engine's own Deployment, rolled by its pod template's `restartedAt`. */
data class EngineRestartResponse(val namespace: String, val deployment: String)

/**
 * `POST /engine/restart`: a deploy, by hand (images.yml no longer calls it; the user deploys when it suits), as can
 * the desktop. Refused from a Smith sandbox (403), and outside a cluster (409): a Compose engine is updated with
 * `docker compose`. With `argoApp` (`namespace/name` of the Argo CD Application that deploys the engine) it first
 * waits, up to 5 minutes, for that Application to be synced ([ArgoSync]), and answers 504 without restarting when it
 * is not. The response comes back before the old pod stops; `/version`'s `commit` says when the new one is up.
 */
@RestController
class EngineRestartController(
    private val inCluster: InCluster,
    private val sandbox: SmithSandbox,
    private val restarter: EngineRestarter,
    private val argo: ArgoSync,
    private val namespace: () -> String = { File(NAMESPACE_FILE).readText().trim() },
) {
    @org.springframework.beans.factory.annotation.Autowired
    constructor(inCluster: InCluster, sandbox: SmithSandbox, restarter: EngineRestarter, argo: ArgoSync) :
        this(inCluster, sandbox, restarter, argo, { File(NAMESPACE_FILE).readText().trim() })

    private val log = LoggerFactory.getLogger(EngineRestartController::class.java)

    @PostMapping("/engine/restart")
    fun restart(request: HttpServletRequest?, @RequestParam(required = false) argoApp: String? = null): EngineRestartResponse {
        SmithRefusal.refuseSmith(sandbox, request, log, "restart the engine")
        if (!inCluster.inPod()) throw ResponseStatusException(HttpStatus.CONFLICT,
            "the engine is not in a cluster: a Compose engine is updated with docker compose (the desktop's Update engine)")
        argoApp?.takeIf { it.isNotBlank() }?.let { ref ->
            val parts = ref.split("/")
            if (parts.size != 2 || parts.any { it.isBlank() }) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "argoApp is namespace/name, got '$ref'")
            if (!argo.awaitSynced(parts[0], parts[1], ARGO_TIMEOUT)) throw ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                "Argo CD Application $ref did not sync within ${ARGO_TIMEOUT.toMinutes()} min; the engine was not restarted")
            log.info("engine restart: Argo CD Application {} is synced", ref)
        }
        val ns = namespace()
        restarter.restart(ns)
        log.info("engine restart: rolled deployment {}/{} (asked from {})", ns, DEPLOYMENT, request?.remoteAddr)
        return EngineRestartResponse(ns, DEPLOYMENT)
    }

    companion object {
        /** The engine's Deployment as deploy/k8s/base/engine.yaml names it. */
        const val DEPLOYMENT = "engine"
        const val NAMESPACE_FILE = "/var/run/secrets/kubernetes.io/serviceaccount/namespace"
        val ARGO_TIMEOUT: Duration = Duration.ofMinutes(5)
    }
}

package com.codefrak.engine.smith

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.File

/**
 * Whether the engine runs in a pod: the ServiceAccount token the kubelet mounts at [TOKEN] is there. In a pod
 * the Kubernetes client is built from that ServiceAccount, and there is no Docker socket, so `docker` hosting
 * is refused (engine/README.md, "Hosting models"). No property: the pod itself is the signal.
 */
@Component
class InCluster(private val token: File) {
    @Autowired constructor() : this(File(TOKEN))

    fun inPod(): Boolean = token.isFile

    companion object {
        const val TOKEN = "/var/run/secrets/kubernetes.io/serviceaccount/token"
        const val NO_DOCKER = "docker hosting is not available in a cluster: the engine runs in a pod, with no Docker socket; " +
            "set hosting to kubernetes"
    }
}

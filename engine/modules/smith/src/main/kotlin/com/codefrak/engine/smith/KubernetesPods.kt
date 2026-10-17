package com.codefrak.engine.smith

import io.fabric8.kubernetes.api.model.Pod


/** The `DisruptionTarget` condition reasons that mean the cluster took the pod away, not that the Smith failed. */
private val EVICTION_REASONS = setOf("EvictionByEvictionAPI", "PreemptionByScheduler", "PreemptionByKubeScheduler", "TerminationByKubelet")

/**
 * Whether the cluster evicted the pod: status reason `Evicted` (kubelet node pressure) or `Preempting`, or a true
 * `DisruptionTarget` condition with an eviction or preemption reason. An OOM kill of the `smith` container is never one.
 */
fun podEvicted(pod: Pod): Boolean {
    if (smithStatus(pod)?.state?.terminated?.reason == "OOMKilled") return false
    val reason = pod.status?.reason
    if (reason == "Evicted" || reason == "Preempting") return true
    return pod.status?.conditions.orEmpty().any { it.type == "DisruptionTarget" && it.status == "True" && it.reason in EVICTION_REASONS }
}

/** A finished pod's `smith` exit code: -1 when the pod failed (deadline, eviction) without one; null while it runs. */
fun podExit(pod: Pod): Long? {
    smithStatus(pod)?.state?.terminated?.let { return it.exitCode?.toLong() ?: -1L }
    return if (pod.status?.phase == "Failed" || pod.status?.phase == "Succeeded") -1L else null
}

internal fun smithStatus(pod: Pod) = pod.status?.containerStatuses?.firstOrNull { it.name == KubernetesSandbox.SMITH_CONTAINER }

internal fun smithStarted(pod: Pod) = smithStatus(pod)?.state?.let { it.running != null || it.terminated != null } ?: (podExit(pod) != null)

internal fun smithRunning(pod: Pod) = smithStatus(pod)?.state?.running != null


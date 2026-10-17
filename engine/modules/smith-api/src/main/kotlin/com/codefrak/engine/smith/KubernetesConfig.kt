package com.codefrak.engine.smith

import io.fabric8.kubernetes.client.Config
import java.io.File

/**
 * A Kubernetes client's config: fabric8's in-cluster config (the pod's ServiceAccount token and CA, the API server from
 * `KUBERNETES_SERVICE_HOST`) in a pod, else the kubeconfig at [kubeconfig], read only then. Shared by the Smith
 * sandbox (`KubernetesSandbox`) and the first-mate session pods (`FirstMatePods`).
 */
fun kubernetesConfig(inPod: Boolean, kubeconfig: File): Config =
    if (inPod) Config.autoConfigure(null) else Config.fromKubeconfig(kubeconfig.readText())

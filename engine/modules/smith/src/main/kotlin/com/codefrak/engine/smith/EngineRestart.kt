package com.codefrak.engine.smith

import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Restarts the engine's own Deployment (engine/README.md, "Deploying on Kubernetes"): the same patch as
 * `kubectl rollout restart`, so the new pod pulls `:main` (`imagePullPolicy: Always`). The base's Role lets the
 * engine's ServiceAccount get and patch only the Deployment named [DEPLOYMENT] in its own namespace.
 */
fun interface EngineRestarter {
    fun restart(namespace: String)
}

@Component
class KubernetesEngineRestarter : EngineRestarter {
    override fun restart(namespace: String) {
        KubernetesClientBuilder().withConfig(Config.autoConfigure(null)).build().use { k ->
            k.apps().deployments().inNamespace(namespace).withName(EngineRestartController.DEPLOYMENT).rolling().restart()
        }
    }
}

/**
 * Waits for an Argo CD Application to have applied what is on its branch now, before the engine restarts onto new
 * code: a commit that changes the engine and `deploy/k8s/base` together (a PriorityClass the new engine names, a Role
 * it needs) must be in the cluster before the new engine runs. Argo syncs the branch's head, which contains the
 * commit being deployed, so a reconcile that ended after [awaitSynced] was called, Synced and with no sync operation
 * running, means the manifests are applied. The site's overlay grants the get and patch on that one Application.
 */
fun interface ArgoSync {
    /** True once `namespace/name` has reconciled since now and is Synced; false when [timeout] passes first. */
    fun awaitSynced(namespace: String, name: String, timeout: Duration): Boolean
}

@Component
class KubernetesArgoSync : ArgoSync {
    private val application = ResourceDefinitionContext.Builder()
        .withGroup("argoproj.io").withVersion("v1alpha1").withKind("Application").withPlural("applications").withNamespaced(true).build()

    override fun awaitSynced(namespace: String, name: String, timeout: Duration): Boolean {
        val asked = Instant.now()
        KubernetesClientBuilder().withConfig(Config.autoConfigure(null)).build().use { k ->
            val app = k.genericKubernetesResources(application).inNamespace(namespace).withName(name)
            // A fresh reconcile now, not at Argo's next poll (3 min by default).
            app.edit { r -> r.also { it.metadata.annotations = (it.metadata.annotations ?: mutableMapOf()).apply { put(REFRESH, "hard") } } }
            val deadline = asked.plus(timeout)
            while (Instant.now().isBefore(deadline)) {
                if (synced(app.get(), asked)) return true
                Thread.sleep(3_000)
            }
            return false
        }
    }

    companion object {
        const val REFRESH = "argocd.argoproj.io/refresh"

        /** Reconciled at or after [asked] (whole seconds), Synced, and no sync operation running. */
        @Suppress("UNCHECKED_CAST")
        fun synced(app: GenericKubernetesResource?, asked: Instant): Boolean {
            val status = app?.additionalProperties?.get("status") as? Map<String, Any?> ?: return false
            val reconciled = (status["reconciledAt"] as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return false
            val sync = (status["sync"] as? Map<String, Any?>)?.get("status")
            val phase = (status["operationState"] as? Map<String, Any?>)?.get("phase")
            return !reconciled.isBefore(asked.truncatedTo(ChronoUnit.SECONDS)) && sync == "Synced" && phase != "Running"
        }
    }
}

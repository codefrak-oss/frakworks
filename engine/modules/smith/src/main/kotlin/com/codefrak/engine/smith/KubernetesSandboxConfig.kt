package com.codefrak.engine.smith

import com.codefrak.engine.accounts.AiAccounts
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.File
import java.time.Duration

/**
 * The [KubernetesSandbox] bean: in a pod, its ServiceAccount; outside one, the kubeconfig at
 * `engine.smith.k8s.kubeconfig` (mounted read-only, never in git); the hosting rows of `engine.engine_setting`, and the same env and caps as [DockerSandbox].
 */
@Configuration
@ConditionalOnProperty("engine.smith.sandbox", havingValue = "docker", matchIfMissing = true)
class KubernetesSandboxConfig {
    @Bean
    fun kubernetesSandbox(
        @Value("\${engine.smith.k8s.kubeconfig:/etc/frakworks/kube/config}") kubeconfig: String,
        @Value("\${engine.smith.memory-bytes:4294967296}") memoryBytes: Long,
        @Value("\${engine.smith.cpus:2}") cpus: Double,
        @Value("\${engine.smith.k8s.active-deadline:PT24H}") activeDeadline: Duration,
        @Value("\${engine.smith.image-pull-timeout:PT5M}") startTimeout: Duration,
        tokens: com.codefrak.engine.github.SmithTokens,
        identity: com.codefrak.engine.github.TokenIdentity,
        accounts: AiAccounts,
        hosting: SmithHosting,
        inCluster: InCluster,
        mcpServers: SmithMcpServers,
    ): KubernetesSandbox = KubernetesSandbox(
        client = lazy { KubernetesClientBuilder().withConfig(kubernetesConfig(inCluster.inPod(), File(kubeconfig))).build() },
        kubeconfigPresent = { File(kubeconfig).isFile },
        settings = hosting::get,
        environment = { spec, engineUrl ->
            val (credential, committer) = smithCredentialAndCommitter(spec, accounts, identity)
            // The repo's Smith-audience MCP servers (#2449) ride in the run's env Secret, never the Job spec.
            sandboxEnv(spec, tokens.forRun(spec.repo), credential, engineUrl, committer, mcpServers.forRun(spec))
        },
        memoryBytes = memoryBytes, cpus = cpus, activeDeadline = activeDeadline, startTimeout = startTimeout,
        inPod = inCluster::inPod,
    )
}

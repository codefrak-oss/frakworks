package com.codefrak.engine.firstmate

import com.codefrak.engine.accounts.AiAccounts
import com.codefrak.engine.accounts.credentialEnv
import com.codefrak.engine.accounts.providerOf
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.repos.EngineTokens
import com.codefrak.engine.smith.InCluster
import com.codefrak.engine.smith.SmithHosting
import com.codefrak.engine.smith.SmithHostingSettings
import com.codefrak.engine.smith.kubernetesConfig
import com.codefrak.engine.tier.TierTable
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** What a session pod runs on: the AI account's env var and token, the model and effort of the First mate tier. */
data class FirstMateAccount(val env: String?, val token: String?, val model: String, val effort: String)

/**
 * The session pods (engine/README.md, "First-mate sessions"): [start] begins a
 * new pod lifetime (`pod_epoch` + 1, so the new relay's `out_seq` 1 never collides), mints the pod's `first-mate-pod`
 * token for the session ([mintToken]) and creates the session's Secret and pod ([firstMateSecret], [firstMatePod]) in
 * [FIRST_MATE_NAMESPACE]. The pod's `fm-relay` connects out to the engine's `/first-mate/pod/{id}/` endpoints
 * (`FirstMatePodController`): the engine never attaches and keeps nothing about the pod in memory, so any replica serves
 * it. [stop] deletes pod and Secret and revokes the pod's token ([revokeTokens]). With no cluster to ask (no
 * ServiceAccount, no kubeconfig, no image) it starts nothing and logs.
 *
 * A [start] with a frozen session's snapshot (`FirstMateFreezer`) sets `FM_RESUME`: the relay fetches the snapshot,
 * restores it and runs `claude --resume <id>` itself. Until the new pod's first outbox line a `first-mate` frame with
 * line `{"type":"resuming"}` (seq 0) goes out every [resumingEvery], for at most [startTimeout].
 */
class KubernetesFirstMateSessionPods(
    private val client: Lazy<KubernetesClient>,
    /** Whether the cluster can be asked: in a pod, or a kubeconfig present. */
    private val clusterConfigured: () -> Boolean,
    private val settings: () -> SmithHostingSettings,
    /** The account, model and effort for a pod of the session's tier (null: the engine's First mate tier), picked at every pod start. */
    private val account: (Int?) -> FirstMateAccount,
    private val sessions: FirstMateSessions,
    private val events: EngineEvents,
    private val memoryBytes: Long,
    private val cpus: Double,
    private val startTimeout: Duration,
    /** Mints a `first-mate-pod` token scoped to the session; the token. */
    private val mintToken: (FirstMateSession) -> String,
    /** Revokes every `first-mate-pod` token of the session id. */
    private val revokeTokens: (String) -> Unit,
    private val prompt: FirstMatePrompt = FirstMatePrompt.load(),
    private val resumingEvery: Duration = Duration.ofSeconds(2),
) : FirstMateSessionPods {
    private val log = LoggerFactory.getLogger(KubernetesFirstMateSessionPods::class.java)
    private val ticker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "first-mate-resuming").apply { isDaemon = true } }

    companion object {
        private val JSON = jacksonObjectMapper()

        /**
         * The stream-json user line `claude -p --input-format stream-json` reads for a plain [text] (fm-relay writes the
         * same for a row without `images`; for one with them it builds the block-list `content` itself, nothing here does).
         */
        fun userLine(text: String): String =
            JSON.writeValueAsString(mapOf("type" to "user", "message" to mapOf("role" to "user", "content" to text))) + "\n"

        /** The line of the frame sent while a frozen session's pod starts. */
        val RESUMING: Map<String, String> = mapOf("type" to "resuming")

        /** The Claude id in `claude -p`'s `{"type":"system","subtype":"init","session_id":…}` line, or null for any other. */
        fun claudeId(body: Any?): String? =
            (body as? Map<*, *>)?.takeIf { it["type"] == "system" && it["subtype"] == "init" }?.get("session_id")?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun usable(): Boolean = clusterConfigured() && settings().image.isNotBlank()

    override fun start(session: FirstMateSession, mcp: FirstMateMcp, restore: FirstMateSnapshot?) {
        if (!usable()) {
            log.info("first-mate session {} on {} for @{}: no cluster to start a pod in ({} MCP servers, {} dropped)",
                session.id, session.repo, session.login, mcp.servers.size, mcp.dropped.size)
            return
        }
        val s = settings()
        val a = account(session.tier)
        val name = firstMatePodName(session)
        val k = client.value
        val epoch = sessions.nextEpoch(session.id)
        val token = mintToken(session)
        // The repo's LAN servers, opened for this pod alone before it starts (FirstMateEgress).
        val holes = FirstMateEgress.holes(mcp)
        val policies = k.network().networkPolicies().inNamespace(FIRST_MATE_NAMESPACE)
        policies.withName(name).delete()
        if (holes.isNotEmpty()) {
            policies.resource(FirstMateEgress.policy(name, session.id, holes)).create()
            log.info("first-mate session {} egress opened: {}", session.id, holes.joinToString { "${it.name} ${it.ip}:${it.port}" })
        }
        k.secrets().inNamespace(FIRST_MATE_NAMESPACE).resource(firstMateSecret(name, session, mcp.rebasedOn(s.engineUrl), a.env, a.token, token)).create()
        k.pods().inNamespace(FIRST_MATE_NAMESPACE).resource(firstMatePod(name, session, s.image,
            firstMateCommand(firstMateArgv(a.model, a.effort, prompt, lineage = session.lineage != null)),
            name, a.env, memoryBytes, cpus, s.memoryRequestBytes, s.cpuRequest, engineUrl = s.engineUrl, resume = restore != null)).create()
        log.info("first-mate session {} pod {}/{} (epoch {}) created on {} {} (tier {}) for @{}{}", session.id, FIRST_MATE_NAMESPACE, name, epoch, a.model,
            a.effort, session.tier ?: "default", session.login, if (restore != null) " (resuming)" else "")
        if (restore != null) resuming(session, epoch)
    }

    /**
     * Sends the `resuming` frame now and every [resumingEvery] until the pod of [epoch] stores its first outbox line,
     * the session is no longer open, or [startTimeout] passes.
     */
    private fun resuming(session: FirstMateSession, epoch: Int) {
        val until = System.nanoTime() + startTimeout.toNanos()
        // A throw is how a fixed-rate task ends itself.
        ticker.scheduleAtFixedRate({
            val now = sessions.get(session.id)
            if (now?.open != true || now.podEpoch != epoch || sessions.hasOut(session.id, epoch, 1) || System.nanoTime() > until)
                throw java.util.concurrent.CancellationException("answered")
            events.publish(EngineEvent.FirstMate(session.repo, session.id, 0, RESUMING))
        }, 0, resumingEvery.toMillis(), TimeUnit.MILLISECONDS)
    }

    override fun stop(session: FirstMateSession) {
        if (!usable()) return log.info("first-mate session {} ended", session.id)
        val name = firstMatePodName(session)
        val k = client.value
        k.pods().inNamespace(FIRST_MATE_NAMESPACE).withName(name).delete()
        k.secrets().inNamespace(FIRST_MATE_NAMESPACE).withName(name).delete()
        k.network().networkPolicies().inNamespace(FIRST_MATE_NAMESPACE).withName(name).delete()
        revokeTokens(session.id)
        log.info("first-mate session {} pod {}/{}, its Secret, egress policy and pod token deleted", session.id, FIRST_MATE_NAMESPACE, name)
    }

    override fun exec(session: FirstMateSession, script: String, stdin: ByteArray?): ByteArray? {
        if (!usable()) return null
        val pod = client.value.pods().inNamespace(FIRST_MATE_NAMESPACE).withName(firstMatePodName(session))
        if (pod.get() == null) return null
        val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
        val c = pod.inContainer(FIRST_MATE_CONTAINER)
        val watch = (if (stdin != null) c.readingInput(ByteArrayInputStream(stdin)) else c).writingOutput(out).writingError(err).exec("sh", "-c", script)
        watch.use {
            val code = it.exitCode().get(startTimeout.toMillis(), TimeUnit.MILLISECONDS)
            if (code != 0) error("`$script` in ${firstMatePodName(session)} exited $code: ${err.toString().take(500)}")
        }
        return out.toByteArray()
    }
}

/**
 * The [KubernetesFirstMateSessionPods] bean: the cluster as [com.codefrak.engine.smith.KubernetesSandbox] reaches it (the
 * pod's ServiceAccount, else the kubeconfig), the Smith image and requests from the hosting settings, the Smith limits
 * (`engine.smith.memory-bytes`, `engine.smith.cpus`), the account a Smith at the session's tier (else
 * `engine.firstmate.tier`) gets, and [EngineTokens] for the pods' `first-mate-pod` tokens.
 */
@Component
class FirstMateSessionPodsBean @Autowired constructor(
    @Value("\${engine.smith.k8s.kubeconfig:/etc/frakworks/kube/config}") kubeconfig: String,
    @Value("\${engine.smith.memory-bytes:4294967296}") memoryBytes: Long,
    @Value("\${engine.smith.cpus:2}") cpus: Double,
    @Value("\${engine.smith.image-pull-timeout:PT5M}") startTimeout: Duration,
    @Value("\${engine.firstmate.tier:8}") tier: Int,
    hosting: SmithHosting, inCluster: InCluster, tiers: TierTable, accounts: AiAccounts,
    sessions: FirstMateSessions, events: EngineEvents, tokens: EngineTokens,
) : FirstMateSessionPods by KubernetesFirstMateSessionPods(
    client = lazy { KubernetesClientBuilder().withConfig(kubernetesConfig(inCluster.inPod(), File(kubeconfig))).build() },
    clusterConfigured = { inCluster.inPod() || File(kubeconfig).isFile },
    settings = hosting::get,
    account = { sessionTier ->
        val t = tiers.resolve(sessionTier ?: tier)
        val provider = providerOf(t.model)
        val name = accounts.pick(provider, t.model) ?: error("no usable $provider account for ${t.model}")
        FirstMateAccount(credentialEnv(provider), accounts.credential(name) ?: error("account $name has no credential"), t.model, t.effort)
    },
    sessions = sessions, events = events, memoryBytes = memoryBytes, cpus = cpus, startTimeout = startTimeout,
    mintToken = { s -> tokens.mint(s.login, "first-mate pod ${s.id}", null, "engine", EngineTokens.FIRST_MATE_POD, s.id).token },
    revokeTokens = { id -> tokens.revokeForSession(id) },
)

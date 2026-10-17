package com.codefrak.engine.firstmate

import com.codefrak.engine.smith.SMITH_PRIORITY_CLASS
import com.codefrak.engine.smith.SMITH_UID
import com.codefrak.engine.smith.refreshable
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.api.model.SecurityContextBuilder
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import tools.jackson.module.kotlin.jacksonObjectMapper

/** The namespace every session pod runs in (deploy/k8s/base): never the Smiths' `frakworks-smith`. */
const val FIRST_MATE_NAMESPACE = "frakworks-firstmate"
/** The session pods' ServiceAccount (deploy/k8s/base). */
const val FIRST_MATE_SA = "first-mate"
/** The pod's one container: `fm-relay` with `claude` as its child. */
const val FIRST_MATE_CONTAINER = "first-mate"
/** The session label on pod and Secret. */
const val FIRST_MATE_SESSION_LABEL = "frakworks.codefrak.com/first-mate-session"
/** Where the Secret's `mcp.json` is mounted. */
const val FIRST_MATE_MCP_DIR = "/etc/firstmate"
/**
 * Every session pod's working folder, the same in every pod a session gets, so the Claude session file a freeze saves
 * is where `claude --resume` looks for it in the next pod ([claudeSessionPath]). An emptyDir.
 */
const val FIRST_MATE_CWD = "/work/repo"
/** Where `fm-relay` spools claude's stdout lines the engine has not yet accepted: an emptyDir, kept across a relay restart in the pod. */
const val FIRST_MATE_SPOOL = "/var/spool/fm-relay"
/** The Secret key of the pod's `first-mate-pod` token, the relay's `FM_TOKEN`. */
const val FIRST_MATE_TOKEN_KEY = "fm-token"
/** The Secret key, and the file in [FIRST_MATE_MCP_DIR], of the session's lineage (engine/README.md, "Lineage"); `FM_LINEAGE_FILE` names it. */
const val FIRST_MATE_LINEAGE_KEY = "lineage.json"

/** Claude Code's projects folder name for [cwd]: every character but a letter or digit becomes `-` (`/work/repo` is `-work-repo`). */
fun claudeProjectDir(cwd: String): String = cwd.replace(Regex("[^A-Za-z0-9]"), "-")

/** Where Claude Code keeps conversation [claudeId] run in [cwd], relative to `$HOME`: `.claude/projects/<encoded cwd>/<id>.jsonl`. */
fun claudeSessionPath(cwd: String, claudeId: String): String = ".claude/projects/${claudeProjectDir(cwd)}/$claudeId.jsonl"

/** The shared prompt and tool lists (shared/first-mate-prompt.json, on the classpath as `first-mate-prompt.json`). */
data class FirstMatePrompt(val prompt: String, val allowedTools: List<String>, val deniedTools: List<String>) {
    companion object {
        /** Read from the classpath; empty lists and prompt when the file is not there (a module test). */
        fun load(): FirstMatePrompt {
            val text = FirstMatePrompt::class.java.getResourceAsStream("/first-mate-prompt.json")?.use { String(it.readAllBytes()) }
                ?: return FirstMatePrompt("", emptyList(), emptyList())
            @Suppress("UNCHECKED_CAST")
            val m = jacksonObjectMapper().readValue(text, Map::class.java) as Map<String, Any?>
            fun list(k: String) = (m[k] as? List<*>).orEmpty().map { it.toString() }
            return FirstMatePrompt(list("prompt").joinToString(""), list("allowedTools"), list("deniedTools"))
        }
    }
}

/** A session's pod and Secret name: `first-mate-<session id>`, a DNS label. */
fun firstMatePodName(session: FirstMateSession): String = "first-mate-" + session.id.lowercase().replace(Regex("[^a-z0-9-]"), "-").take(52)

/**
 * The `claude -p` argv of a session pod, as the desktop's First mate child runs (claude-session.js). A resumed pod's
 * `fm-relay` adds `--resume <claude id>` from the snapshot. With [lineage], the appended prompt ends with the "Lineage
 * file:" line giving the mounted file's literal path for the Read tool (#2496: a shell read of `FM_LINEAGE_FILE` is
 * refused by the permission check), worded as the desktop's `lineageFileLine`.
 */
fun firstMateArgv(model: String, effort: String, prompt: FirstMatePrompt, lineage: Boolean = false): List<String> = buildList {
    addAll(listOf("claude", "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
        "--model", model, "--effort", effort))
    if (prompt.allowedTools.isNotEmpty()) addAll(listOf("--allowedTools", prompt.allowedTools.joinToString(",")))
    if (prompt.deniedTools.isNotEmpty()) addAll(listOf("--disallowedTools", prompt.deniedTools.joinToString(",")))
    addAll(listOf("--strict-mcp-config", "--mcp-config", "$FIRST_MATE_MCP_DIR/mcp.json"))
    val appended = if (lineage) "${prompt.prompt}\n\n${firstMateLineageLine()}" else prompt.prompt
    if (appended.isNotEmpty()) addAll(listOf("--append-system-prompt", appended))
}

/** The appended prompt's "Lineage file:" line (shared/first-mate-prompt.json, "Lineage:"). */
fun firstMateLineageLine(): String =
    "Lineage file: $FIRST_MATE_MCP_DIR/$FIRST_MATE_LINEAGE_KEY (open it with the Read tool, never Bash)."

/**
 * The container's command: `fm-relay` (engine/smith/fm-relay.py) running [argv] as its child. The relay connects out
 * to the engine's `/first-mate/pod/{id}/` endpoints; a resumed pod's relay restores the snapshot itself and adds
 * `--resume <claude id>` before `claude` starts.
 */
fun firstMateCommand(argv: List<String>): List<String> = listOf("fm-relay", "--") + argv

/**
 * The session's Secret: `mcp.json` (the `--mcp-config`, with the session's token in it), under [credentialEnv] the AI
 * account's token, and under [FIRST_MATE_TOKEN_KEY] the pod's own `first-mate-pod` [podToken]. None is ever in the pod's spec.
 */
fun firstMateSecret(name: String, session: FirstMateSession, mcp: FirstMateMcp, credentialEnv: String?, credential: String?, podToken: String): Secret =
    SecretBuilder().withNewMetadata().withName(name).withNamespace(FIRST_MATE_NAMESPACE)
        .withLabels<String, String>(mapOf(FIRST_MATE_SESSION_LABEL to session.id)).endMetadata()
        .withType("Opaque")
        .withStringData<String, String>(buildMap {
            put("mcp.json", jacksonObjectMapper().writeValueAsString(mapOf("mcpServers" to mcp.servers)))
            if (credentialEnv != null && credential != null) put(credentialEnv, credential)
            put(FIRST_MATE_TOKEN_KEY, podToken)
            session.lineage?.let { put(FIRST_MATE_LINEAGE_KEY, it) }
        })
        .build()

/**
 * A session's long-lived pod (engine/README.md, "First-mate sessions"): in [FIRST_MATE_NAMESPACE] under [FIRST_MATE_SA], from
 * the Smith image, sandboxed as a Smith's (`smithJob`): non-root as [SMITH_UID], every capability dropped, no privilege
 * escalation, the runtime's seccomp profile, no service links, PriorityClass [SMITH_PRIORITY_CLASS], and the same memory
 * and CPU limits and requests. Its container runs [command] (`fm-relay`, [firstMateCommand]) with the relay's env:
 * [engineUrl], the session id, the pod token from the Secret, the spool [FIRST_MATE_SPOOL] (an emptyDir) and, for a
 * [resume], `FM_RESUME`, and with a lineage `FM_LINEAGE_FILE`, its read-only file beside `mcp.json`. The engine never attaches, so stdin stays closed. Restart `Never`, so a crashed first mate ends
 * rather than loops. Its working folder is [FIRST_MATE_CWD], an emptyDir. A pure function so a test asserts on it.
 */
fun firstMatePod(
    name: String, session: FirstMateSession, image: String, command: List<String>, secret: String, credentialEnv: String?,
    memoryBytes: Long, cpus: Double, memoryRequestBytes: Long = memoryBytes, cpuRequest: Double = cpus,
    engineUrl: String = "", resume: Boolean = false,
): Pod {
    val labels = mapOf(FIRST_MATE_SESSION_LABEL to session.id)
    val locked = SecurityContextBuilder().withAllowPrivilegeEscalation(false).withPrivileged(false)
        .withNewCapabilities().withDrop("ALL").endCapabilities().build()
    val resources = ResourceRequirementsBuilder()
        .withLimits<String, Quantity>(mapOf("memory" to Quantity(memoryBytes.toString()), "cpu" to Quantity(cpus.toString())))
        .withRequests<String, Quantity>(mapOf("memory" to Quantity(memoryRequestBytes.toString()), "cpu" to Quantity(cpuRequest.toString())))
        .build()
    fun fromSecret(env: String, key: String) =
        EnvVarBuilder().withName(env).withNewValueFrom().withNewSecretKeyRef().withName(secret).withKey(key).endSecretKeyRef().endValueFrom().build()
    fun plain(env: String, value: String) = EnvVarBuilder().withName(env).withValue(value).build()
    val env = listOfNotNull(credentialEnv?.let { fromSecret(it, it) },
        plain("FM_ENGINE_URL", engineUrl), plain("FM_SESSION_ID", session.id), fromSecret("FM_TOKEN", FIRST_MATE_TOKEN_KEY),
        plain("FM_SPOOL", FIRST_MATE_SPOOL), if (resume) plain("FM_RESUME", "1") else null,
        session.lineage?.let { plain("FM_LINEAGE_FILE", "$FIRST_MATE_MCP_DIR/$FIRST_MATE_LINEAGE_KEY") })
    return PodBuilder()
        .withNewMetadata().withName(name).withNamespace(FIRST_MATE_NAMESPACE).withLabels<String, String>(labels)
            .withAnnotations<String, String>(mapOf("frakworks.codefrak.com/repo" to session.repo, "frakworks.codefrak.com/login" to session.login))
        .endMetadata()
        .withNewSpec()
            .withServiceAccountName(FIRST_MATE_SA)
            .withRestartPolicy("Never")
            .withPriorityClassName(SMITH_PRIORITY_CLASS)
            .withEnableServiceLinks(false)
            .withNewSecurityContext()
                .withRunAsNonRoot(true).withRunAsUser(SMITH_UID).withRunAsGroup(SMITH_UID).withFsGroup(SMITH_UID)
                .withNewSeccompProfile().withType("RuntimeDefault").endSeccompProfile()
            .endSecurityContext()
            .addNewContainer()
                .withName(FIRST_MATE_CONTAINER).withImage(image).withImagePullPolicy(if (refreshable(image)) "Always" else "IfNotPresent")
                .withCommand(command)
                .withWorkingDir(FIRST_MATE_CWD)
                .withStdin(false).withTty(false)
                .withEnv(env)
                .withSecurityContext(locked)
                .withResources(resources)
                .withVolumeMounts(VolumeMountBuilder().withName("mcp").withMountPath(FIRST_MATE_MCP_DIR).withReadOnly(true).build(),
                    VolumeMountBuilder().withName("work").withMountPath(FIRST_MATE_CWD).build(),
                    VolumeMountBuilder().withName("spool").withMountPath(FIRST_MATE_SPOOL).build())
            .endContainer()
            .withVolumes(VolumeBuilder().withName("mcp").withNewSecret().withSecretName(secret)
                .addNewItem().withKey("mcp.json").withPath("mcp.json").endItem()
                .apply { if (session.lineage != null) addNewItem().withKey(FIRST_MATE_LINEAGE_KEY).withPath(FIRST_MATE_LINEAGE_KEY).endItem() }
                .endSecret().build(),
                VolumeBuilder().withName("work").withNewEmptyDir().endEmptyDir().build(),
                VolumeBuilder().withName("spool").withNewEmptyDir().endEmptyDir().build())
        .endSpec()
        .build()
}

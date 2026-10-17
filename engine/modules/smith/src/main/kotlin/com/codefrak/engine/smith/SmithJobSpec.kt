package com.codefrak.engine.smith

import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.EnvFromSourceBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.api.model.SecurityContextBuilder
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import java.time.Duration

/** The Smith image's user, as [tarOf] owns the inputs. */
const val SMITH_UID = 65533L

/**
 * A run's Job name: `smith-<issue>-<run8>` as Docker names its container, so the sweep's [runIdPrefix] holds,
 * or `smith-job-<jobId>-<run8>` with the job id cut to a DNS label's characters and the whole to 52 (its pods
 * add a suffix to it).
 */
fun jobName(spec: SmithSpec): String {
    val run8 = spec.runId.take(8).lowercase()
    if (spec.issue != null) return "smith-${spec.issue}-$run8"
    val job = spec.jobId!!.lowercase().replace(Regex("[^a-z0-9-]"), "-").trim('-').take(52 - "smith-job--".length - run8.length)
    return "smith-job-$job-$run8"
}

/**
 * The run's environment as a Secret of its own, so the tokens in it are never in the Job's spec: every
 * `KEY=value` of [env] as a key, and the run label for the sweep. [delete] removes it with the Job.
 */
fun envSecret(job: String, env: List<String>, spec: SmithSpec): Secret =
    SecretBuilder().withNewMetadata().withName("$job-env").withLabels<String, String>(mapOf(RUN_LABEL to spec.runId)).endMetadata()
        .withType("Opaque")
        .withStringData<String, String>(env.associate { it.substringBefore('=') to it.substringAfter('=') })
        .build()

/** The PriorityClass of every Smith pod (deploy/k8s/base/smith.yaml): the lowest, so Smiths are evicted before the engine and Postgres. */
const val SMITH_PRIORITY_CLASS = "frakworks-smith"

/**
 * The Job for a Smith run: one pod, never retried (`backoffLimit 0`, restart `Never`), killed past
 * [activeDeadline]. The sandbox as Docker's ([sandboxHostConfig]): non-root as [SMITH_UID], every
 * capability dropped, no privilege escalation, the runtime's default seccomp profile, no service-account
 * token, no service links, memory and CPU capped at [memoryBytes] and [cpus] (limits) and requested at
 * [memoryRequestBytes] and [cpuRequest] (Burstable), in PriorityClass [SMITH_PRIORITY_CLASS]. The env is [envSecret]'s.
 * The run label on Job and pod; the run, repo, issue and job id as annotations (a repo has a `/`, which a
 * label value cannot), what [KubernetesSandbox.runAt] reads. A pure function so a test asserts on it.
 */
fun smithJob(
    name: String, spec: SmithSpec, image: String, envSecret: String, memoryBytes: Long, cpus: Double,
    activeDeadline: Duration, withInputs: Boolean,
    memoryRequestBytes: Long = memoryBytes, cpuRequest: Double = cpus,
): Job {
    val labels = mapOf(RUN_LABEL to spec.runId)
    val annotations = sandboxLabels(spec)
    val pull = if (refreshable(image)) "Always" else "IfNotPresent"
    val locked = SecurityContextBuilder().withAllowPrivilegeEscalation(false).withPrivileged(false)
        .withNewCapabilities().withDrop("ALL").endCapabilities().build()
    fun mount(volume: String, path: String, readOnly: Boolean = false) =
        VolumeMountBuilder().withName(volume).withMountPath(path).withReadOnly(readOnly).build()
    fun resources(memory: String, cpu: String, requestMemory: String = memory, requestCpu: String = cpu) =
        io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder()
            .withLimits<String, Quantity>(mapOf("memory" to Quantity(memory), "cpu" to Quantity(cpu)))
            .withRequests<String, Quantity>(mapOf("memory" to Quantity(requestMemory), "cpu" to Quantity(requestCpu))).build()
    val smith = ContainerBuilder().withName(KubernetesSandbox.SMITH_CONTAINER).withImage(image).withImagePullPolicy(pull)
        .withEnvFrom(EnvFromSourceBuilder().withNewSecretRef().withName(envSecret).endSecretRef().build())
        .withSecurityContext(locked)
        .withResources(resources(memoryBytes.toString(), cpus.toString(), memoryRequestBytes.toString(), cpuRequest.toString()))
        .withVolumeMounts(listOfNotNull(mount("out", "/work/out"), if (withInputs) mount("in", INPUT_DIR) else null))
        .build()
    val keeper = helper(KubernetesSandbox.KEEPER_CONTAINER, image, pull, locked,
        "trap 'exit 0' TERM; while :; do sleep 5; done", mount("out", "/work/out", readOnly = true), resources("32Mi", "50m"))
    val inputs = helper(KubernetesSandbox.INPUTS_CONTAINER, image, pull, locked,
        "until [ -e $INPUT_DIR/${KubernetesSandbox.READY} ]; do sleep 1; done; rm -f $INPUT_DIR/${KubernetesSandbox.READY}",
        mount("in", INPUT_DIR), resources("64Mi", "100m"))
    val volumes = listOfNotNull(
        VolumeBuilder().withName("out").withNewEmptyDir().endEmptyDir().build(),
        if (withInputs) VolumeBuilder().withName("in").withNewEmptyDir().endEmptyDir().build() else null,
    )
    return JobBuilder()
        .withNewMetadata().withName(name).withLabels<String, String>(labels).withAnnotations<String, String>(annotations).endMetadata()
        .withNewSpec()
            .withBackoffLimit(0)
            .withActiveDeadlineSeconds(activeDeadline.seconds)
            .withTtlSecondsAfterFinished(600)
            .withNewTemplate()
                .withNewMetadata().withLabels<String, String>(labels).withAnnotations<String, String>(annotations).endMetadata()
                .withNewSpec()
                    .withRestartPolicy("Never")
                    .withPriorityClassName(SMITH_PRIORITY_CLASS)
                    .withAutomountServiceAccountToken(false)
                    .withEnableServiceLinks(false)
                    .withNewSecurityContext()
                        .withRunAsNonRoot(true).withRunAsUser(SMITH_UID).withRunAsGroup(SMITH_UID).withFsGroup(SMITH_UID)
                        .withNewSeccompProfile().withType("RuntimeDefault").endSeccompProfile()
                    .endSecurityContext()
                    .withInitContainers(if (withInputs) listOf(inputs) else emptyList())
                    .withContainers(smith, keeper)
                    .withVolumes(volumes)
                .endSpec()
            .endTemplate()
        .endSpec()
        .build()
}

private fun helper(
    name: String, image: String, pull: String, security: io.fabric8.kubernetes.api.model.SecurityContext, script: String,
    mount: io.fabric8.kubernetes.api.model.VolumeMount, resources: io.fabric8.kubernetes.api.model.ResourceRequirements,
): Container = ContainerBuilder().withName(name).withImage(image).withImagePullPolicy(pull)
    .withCommand("sh", "-c", script).withSecurityContext(security).withResources(resources).withVolumeMounts(mount).build()

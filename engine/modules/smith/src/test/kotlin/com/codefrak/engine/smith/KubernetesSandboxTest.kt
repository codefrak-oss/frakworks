package com.codefrak.engine.smith

import io.fabric8.kubernetes.api.model.ContainerStateBuilder
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `KubernetesSandbox` against fabric8's mock API server in CRUD mode: the Job's sandbox, start, status, the
 * exit watch, OOM, stop and `runAt`. The mock runs no Job controller, so a test makes the Job's pod itself,
 * labelled `job-name` as the cluster does. Inputs, outputs and logs go over `exec` and the log API, which the
 * CRUD mock does not serve; the live k3s run covers those.
 */
@EnableKubernetesMockClient(crud = true)
class KubernetesSandboxTest {
    lateinit var client: KubernetesClient

    private var settings = SmithHostingSettings("kubernetes", "smiths", "registry.example:5000/frakworks-smith:main", "http://192.0.2.10:8090")
    private var kubeconfig = true
    private var inPod = false

    /** The run's repo MCP servers as [sandboxEnv] carries them (#2449): one server, one secret header, the run header. */
    private val mcpServers = """{"ops-server":{"type":"http","url":"http://ops-server.ops.svc.cluster.local:8737/mcp","headers":{"Authorization":"Bearer s3cr3t-header","X-Frakworks-Run":"0a1b2c3d-run"}}}"""

    private fun sandbox() = KubernetesSandbox(
        client = lazy { client }, kubeconfigPresent = { kubeconfig }, settings = { settings },
        environment = { spec, url -> listOf("SMITH_ID=${spec.runId}", "GH_TOKEN=ghp_secret", "ENGINE_URL=$url", "SMITH_MCP_SERVERS=$mcpServers") },
        memoryBytes = 3221225472, cpus = 2.0, activeDeadline = Duration.ofHours(24), startTimeout = Duration.ofSeconds(2),
        poll = Duration.ofMillis(50), inPod = { inPod },
    )

    private val spec = SmithSpec("0a1b2c3d-run", "codefrak-oss/frakworks", 1320, "claude-opus-5-5", "high", dryRun = false)

    /** The Job's pod as the cluster would make it, with [smith] as the smith container's state. */
    private fun pod(job: String, ip: String = "10.42.0.7", smith: ContainerStateBuilder.() -> Unit = { withNewRunning().endRunning() }): Pod {
        val pod = PodBuilder().withNewMetadata().withName("$job-abcde").withNamespace("smiths")
            .withLabels<String, String>(mapOf(KubernetesSandbox.JOB_NAME_LABEL to job, RUN_LABEL to spec.runId))
            .withAnnotations<String, String>(sandboxLabels(spec)).endMetadata()
            .withNewSpec().addNewContainer().withName("smith").withImage("x").endContainer().endSpec()
            .withNewStatus().withPhase("Running").withPodIP(ip)
            .withContainerStatuses(ContainerStatusBuilder().withName("smith").withState(ContainerStateBuilder().apply(smith).build()).build())
            .endStatus().build()
        // A pod's status is a subresource: written by its own call, as the kubelet does.
        val created = client.pods().inNamespace("smiths").resource(pod).create()
        created.status = pod.status
        client.pods().inNamespace("smiths").resource(created).updateStatus()
        return pod
    }

    private fun finished(reason: String? = null, terminated: String? = null, condition: String? = null): Pod = PodBuilder()
        .withNewMetadata().withName("p").endMetadata()
        .withNewStatus().withPhase("Failed").withReason(reason)
            .withConditions(listOfNotNull(condition?.let {
                io.fabric8.kubernetes.api.model.PodConditionBuilder().withType("DisruptionTarget").withStatus("True").withReason(it).build()
            }))
            .withContainerStatuses(listOfNotNull(terminated?.let {
                ContainerStatusBuilder().withName("smith").withState(ContainerStateBuilder().withNewTerminated()
                    .withExitCode(137).withReason(it).endTerminated().build()).build()
            }))
        .endStatus().build()

    @Test
    fun `an evicted or preempted pod is evicted, an OOM kill or a plain failure is not`() {
        assertTrue(podEvicted(finished(reason = "Evicted")))
        assertTrue(podEvicted(finished(condition = "TerminationByKubelet")))
        assertTrue(podEvicted(finished(condition = "PreemptionByScheduler")))
        assertTrue(podEvicted(finished(condition = "EvictionByEvictionAPI")))
        assertFalse(podEvicted(finished(terminated = "OOMKilled")))
        assertFalse(podEvicted(finished(terminated = "Error")))
        assertFalse(podEvicted(finished(condition = "DeletionByPodGC")))
    }

    @Test
    fun `the job runs the smith locked down, capped, labelled, its env in a secret`() {
        val job = smithJob("smith-1320-0a1b2c3d", spec, "registry.example:5000/frakworks-smith:main", "smith-1320-0a1b2c3d-env",
            3221225472, 2.0, Duration.ofHours(24), withInputs = true,
            memoryRequestBytes = SmithHostingSettings.DEFAULT_MEMORY_REQUEST, cpuRequest = SmithHostingSettings.DEFAULT_CPU_REQUEST)
        assertEquals(0, job.spec.backoffLimit)
        assertEquals(86400L, job.spec.activeDeadlineSeconds)
        val pod = job.spec.template.spec
        assertEquals("Never", pod.restartPolicy)
        assertEquals(false, pod.automountServiceAccountToken)
        assertEquals(false, pod.enableServiceLinks)
        assertEquals(true, pod.securityContext.runAsNonRoot)
        assertEquals(SMITH_UID, pod.securityContext.runAsUser)
        assertEquals("RuntimeDefault", pod.securityContext.seccompProfile.type)
        assertEquals(listOf("smith", "keeper"), pod.containers.map { it.name })
        assertEquals(listOf("smith-inputs"), pod.initContainers.map { it.name })
        (pod.containers + pod.initContainers).forEach { c ->
            assertEquals(false, c.securityContext.allowPrivilegeEscalation, c.name)
            assertEquals(false, c.securityContext.privileged, c.name)
            assertEquals(listOf("ALL"), c.securityContext.capabilities.drop, c.name)
        }
        val smith = pod.containers.first()
        assertEquals("3221225472", smith.resources.limits["memory"].toString())
        assertEquals("2.0", smith.resources.limits["cpu"].toString())
        assertEquals("1073741824", smith.resources.requests["memory"].toString(), "Burstable: the request is below the limit")
        assertEquals("0.5", smith.resources.requests["cpu"].toString())
        assertEquals(SMITH_PRIORITY_CLASS, pod.priorityClassName)
        assertEquals("Always", smith.imagePullPolicy)
        assertTrue(smith.env.isNullOrEmpty(), "no env value in the spec")
        assertEquals("smith-1320-0a1b2c3d-env", smith.envFrom.single().secretRef.name)
        assertEquals(setOf("/work/out", INPUT_DIR), smith.volumeMounts.map { it.mountPath }.toSet())
        assertEquals(mapOf(RUN_LABEL to spec.runId), job.spec.template.metadata.labels)
        assertEquals("codefrak-oss/frakworks", job.spec.template.metadata.annotations[REPO_LABEL])
        assertEquals("1320", job.spec.template.metadata.annotations[ISSUE_LABEL])

        val bare = smithJob("j", spec, "frakworks-smith:sha-abc1234", "s", 1, 1.0, Duration.ofHours(1), withInputs = false)
        assertTrue(bare.spec.template.spec.initContainers.isNullOrEmpty())
        assertEquals("IfNotPresent", bare.spec.template.spec.containers.first().imagePullPolicy)
    }

    @Test
    fun `job names are the container's, or a DNS label for a job run`() {
        assertEquals("smith-1320-0a1b2c3d", jobName(spec))
        val name = jobName(SmithSpec("ABCDEF12-x", "o/r", null, "m", "e", dryRun = true, jobId = "Nightly_Report.v2-with-a-very-long-name-indeed"))
        assertTrue(Regex("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$").matches(name), name)
        assertTrue(name.length <= 52, name)
        assertTrue(name.startsWith("smith-job-nightly-report-v2") && name.endsWith("-abcdef12"), name)
    }

    @Test
    fun `start creates the job and its env secret, and refuses while a setting is missing`() {
        val handle = sandbox().start(spec)
        assertEquals("k8s:smiths/smith-1320-0a1b2c3d", handle)
        assertNotNull(client.batch().v1().jobs().inNamespace("smiths").withName("smith-1320-0a1b2c3d").get())
        val secret = client.secrets().inNamespace("smiths").withName("smith-1320-0a1b2c3d-env").get()
        val env = secret.stringData ?: secret.data.mapValues { String(java.util.Base64.getDecoder().decode(it.value)) }
        assertEquals("ghp_secret", env["GH_TOKEN"])
        assertEquals("http://192.0.2.10:8090", env["ENGINE_URL"])
        // The repo's MCP servers (#2449) ride in the Secret alone: the Job spec names no server and holds no header value.
        assertEquals(mcpServers, env["SMITH_MCP_SERVERS"])
        val job = client.kubernetesSerialization.asJson(client.batch().v1().jobs().inNamespace("smiths").withName("smith-1320-0a1b2c3d").get())
        assertFalse("ops-server" in job, "no server name in the Job spec")
        assertFalse("s3cr3t-header" in job, "no header value in the Job spec")
        assertFalse("X-Frakworks-Run" in job, "no header in the Job spec")

        settings = settings.copy(engineUrl = "")
        kubeconfig = false
        val e = assertFailsWith<IllegalStateException> { sandbox().start(spec) }
        assertTrue("engineUrl, kubeconfig" in e.message!!, e.message)
    }

    @Test
    fun `status, exit and OOM come from the smith container`() {
        val s = sandbox()
        val handle = s.start(spec)
        assertEquals(SandboxStatus(true, null), s.status(handle))
        pod("smith-1320-0a1b2c3d") { withNewTerminated().withExitCode(137).withReason("OOMKilled").endTerminated() }
        assertEquals(SandboxStatus(false, 137), s.status(handle))
        assertEquals(true, s.oomKilled(handle))
        assertEquals(SandboxStatus(false, null), s.status("k8s:smiths/smith-1-gone0000"))
    }

    @Test
    fun `the watch reports a terminated smith once, with its run id`() {
        val exits = mutableListOf<SandboxExit>()
        val got = CountDownLatch(1)
        val watch = sandbox().watchExits({ exits += it; got.countDown() })
        try {
            Thread.sleep(1000)
            pod("smith-1320-0a1b2c3d") { withNewTerminated().withExitCode(0).endTerminated() }
            assertTrue(got.await(10, TimeUnit.SECONDS), "no exit reported")
            assertEquals(SandboxExit("k8s:smiths/smith-1320-0a1b2c3d", spec.runId, 0), exits.single())
        } finally { watch.close() }
    }

    @Test
    fun `stop deletes the job, its pod and its secret`() {
        val s = sandbox()
        val handle = s.start(spec)
        pod("smith-1320-0a1b2c3d")
        s.stop(handle)
        assertNull(client.batch().v1().jobs().inNamespace("smiths").withName("smith-1320-0a1b2c3d").get())
        assertNull(client.secrets().inNamespace("smiths").withName("smith-1320-0a1b2c3d-env").get())
        assertTrue(client.pods().inNamespace("smiths").list().items.isEmpty())
        s.stop(handle) // a gone one is not an error
    }

    @Test
    fun `runAt finds the running pod at the address, from its annotations`() {
        val s = sandbox()
        pod("smith-1320-0a1b2c3d", ip = "10.42.0.7")
        assertEquals(SmithCaller(spec.runId, 1320, "codefrak-oss/frakworks", null), s.runAt("::ffff:10.42.0.7"))
        assertNull(s.runAt("10.42.0.8"))
        kubeconfig = false
        assertNull(s.runAt("10.42.0.7"))
    }

    @Test
    fun `jobs lists the run's job for the sweep, running while its smith runs`() {
        val s = sandbox()
        s.start(spec)
        pod("smith-1320-0a1b2c3d")
        val job = s.jobs().single()
        assertEquals("smith-1320-0a1b2c3d", job.name)
        assertEquals("k8s:smiths/smith-1320-0a1b2c3d", job.id)
        assertTrue(job.running)
        assertEquals("0a1b2c3d", runIdPrefix(job.name))
        settings = settings.copy(namespace = "")
        assertFalse(s.jobs().isNotEmpty())
    }

    @Test
    fun `in a pod the serviceaccount stands in for the kubeconfig, outside one the kubeconfig is missing`() {
        kubeconfig = false
        inPod = true
        assertEquals(emptyList(), sandbox().missing())
        inPod = false
        assertEquals(listOf("kubeconfig"), sandbox().missing())
    }

    @Test
    fun `the client config is the kubeconfig outside a pod and never reads it in one`() {
        val file = java.io.File.createTempFile("kubeconfig", "")
        try {
            file.writeText("""
                apiVersion: v1
                kind: Config
                clusters: [{name: c, cluster: {server: "https://k3s.example:6443"}}]
                users: [{name: u, user: {token: t}}]
                contexts: [{name: x, context: {cluster: c, user: u}}]
                current-context: x
            """.trimIndent())
            assertTrue(kubernetesConfig(inPod = false, kubeconfig = file).masterUrl.startsWith("https://k3s.example:6443"))
            file.delete()
            assertFailsWith<java.io.IOException> { kubernetesConfig(inPod = false, kubeconfig = file) }
            // In a pod the file is not read at all: a missing one is no failure.
            kubernetesConfig(inPod = true, kubeconfig = file)
        } finally { file.delete() }
    }

    @Test
    fun `the engine is in a pod exactly when the serviceaccount token is there`() {
        val token = java.io.File.createTempFile("token", "")
        try {
            assertTrue(InCluster(token).inPod())
            token.delete()
            assertFalse(InCluster(token).inPod())
        } finally { token.delete() }
    }
}

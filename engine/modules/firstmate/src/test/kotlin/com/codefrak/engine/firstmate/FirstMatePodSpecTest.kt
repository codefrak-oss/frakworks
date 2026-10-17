package com.codefrak.engine.firstmate

import com.codefrak.engine.smith.SMITH_UID
import com.codefrak.engine.smith.SmithSpec
import com.codefrak.engine.smith.smithJob
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.OffsetDateTime
import io.fabric8.kubernetes.api.model.Quantity
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [firstMatePod], [firstMateSecret] and [firstMateArgv]: where a session pod runs, as what, and on how much. */
class FirstMatePodSpecTest {
    private val session = FirstMateSession("4F2a-9c", "lundmatt1", "tok-1", "acme/tickets", "open", OffsetDateTime.now(), null)
    private val prompt = FirstMatePrompt("You are the first mate.", listOf("mcp__frakworks__submit_ask", "Read"), listOf("Bash"))
    private val argv = firstMateArgv("claude-opus-5-5", "high", prompt)
    private val name = firstMatePodName(session)
    private val pod = firstMatePod(name, session, "registry.example:5000/frakworks-smith:main", firstMateCommand(argv), name, "ANTHROPIC_API_KEY",
        3221225472, 2.0, 1073741824, 0.5, engineUrl = "http://engine.frakworks:8080")

    @Test
    fun `the pod runs in frakworks-firstmate under the first-mate service account, never frakworks-smith`() {
        assertEquals("frakworks-firstmate", pod.metadata.namespace)
        assertEquals("first-mate", pod.spec.serviceAccountName)
        assertFalse(pod.metadata.namespace == "frakworks-smith")
        assertEquals("first-mate-4f2a-9c", name)
        assertEquals(session.id, pod.metadata.labels[FIRST_MATE_SESSION_LABEL])
        assertEquals("Never", pod.spec.restartPolicy)
    }

    @Test
    fun `its limits and requests equal a Smith job's and it is sandboxed as one`() {
        val spec = SmithSpec("0a1b2c3d-run", "acme/tickets", 7, "claude-opus-5-5", "high", dryRun = false)
        val job = smithJob("smith-7-0a1b2c3d", spec, "img", "env", 3221225472, 2.0, Duration.ofHours(24), false,
            memoryRequestBytes = 1073741824, cpuRequest = 0.5)
        val smith = job.spec.template.spec.containers.first { it.name == "smith" }
        val mate = pod.spec.containers.single()
        assertEquals(smith.resources.limits, mate.resources.limits)
        assertEquals(smith.resources.requests, mate.resources.requests)
        assertEquals(smith.securityContext, mate.securityContext)
        assertEquals(job.spec.template.spec.securityContext, pod.spec.securityContext)
        assertEquals(job.spec.template.spec.priorityClassName, pod.spec.priorityClassName)
        assertEquals(SMITH_UID, pod.spec.securityContext.runAsUser)
    }

    @Test
    fun `the container runs fm-relay with claude -p on stream-json, the shared prompt, the allow-list and the session's mcp config`() {
        val mate = pod.spec.containers.single()
        assertEquals(listOf("fm-relay", "--"), mate.command.take(2))
        assertEquals(listOf("claude", "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
            "--model", "claude-opus-5-5", "--effort", "high",
            "--allowedTools", "mcp__frakworks__submit_ask,Read", "--disallowedTools", "Bash",
            "--strict-mcp-config", "--mcp-config", "/etc/firstmate/mcp.json",
            "--append-system-prompt", "You are the first mate."), mate.command.drop(2))
        assertFalse(mate.stdin, "the engine never attaches")
        assertEquals("/etc/firstmate", mate.volumeMounts.single { it.name == "mcp" }.mountPath)
        assertEquals(name, pod.spec.volumes.single { it.name == "mcp" }.secret.secretName)
        assertEquals(name, mate.env.single { it.name == "ANTHROPIC_API_KEY" }.valueFrom.secretKeyRef.name)
        assertNull(mate.env.single { it.name == "ANTHROPIC_API_KEY" }.value)
    }

    @Test
    fun `the relay gets the engine url, the session id, the pod token from the secret and an emptyDir spool`() {
        val mate = pod.spec.containers.single()
        fun env(n: String) = mate.env.single { it.name == n }
        assertEquals("http://engine.frakworks:8080", env("FM_ENGINE_URL").value)
        assertEquals(session.id, env("FM_SESSION_ID").value)
        assertEquals(name, env("FM_TOKEN").valueFrom.secretKeyRef.name)
        assertEquals(FIRST_MATE_TOKEN_KEY, env("FM_TOKEN").valueFrom.secretKeyRef.key)
        assertNull(env("FM_TOKEN").value)
        assertEquals(FIRST_MATE_SPOOL, env("FM_SPOOL").value)
        assertEquals(FIRST_MATE_SPOOL, mate.volumeMounts.single { it.name == "spool" }.mountPath)
        assertTrue(pod.spec.volumes.single { it.name == "spool" }.emptyDir != null)
        assertTrue(mate.env.none { it.name == "FM_RESUME" })
    }

    @Test
    fun `the secret holds the mcp config and the account token, named and labelled for the session`() {
        val mcp = FirstMateMcp(mapOf("frakworks" to mapOf("type" to "http", "url" to "https://engine/mcp/frakworks")), emptyList())
        val secret = firstMateSecret(name, session, mcp, "ANTHROPIC_API_KEY", "sk-secret", "fw_pod-token")
        assertEquals(name, secret.metadata.name)
        assertEquals("frakworks-firstmate", secret.metadata.namespace)
        assertEquals(session.id, secret.metadata.labels[FIRST_MATE_SESSION_LABEL])
        assertEquals("sk-secret", secret.stringData["ANTHROPIC_API_KEY"])
        assertEquals("fw_pod-token", secret.stringData[FIRST_MATE_TOKEN_KEY])
        assertTrue(secret.stringData["mcp.json"]!!.contains("\"mcpServers\""))
        assertFalse(pod.toString().contains("sk-secret"))
        assertFalse(pod.toString().contains("fw_pod-token"))
    }

    @Test
    fun `every pod works in the same emptyDir folder, so the Claude session file path is the same in every pod`() {
        val mate = pod.spec.containers.single()
        assertEquals(FIRST_MATE_CWD, mate.workingDir)
        assertEquals("/work/repo", FIRST_MATE_CWD)
        assertEquals(FIRST_MATE_CWD, mate.volumeMounts.single { it.name == "work" }.mountPath)
        assertTrue(pod.spec.volumes.single { it.name == "work" }.emptyDir != null)
        assertEquals("-work-repo", claudeProjectDir(FIRST_MATE_CWD))
        assertEquals(".claude/projects/-work-repo/0b5e-77.jsonl", claudeSessionPath(FIRST_MATE_CWD, "0b5e-77"))
    }

    @Test
    fun `a resumed pod only sets FM_RESUME, and the relay restores the snapshot and adds --resume, with no restore marker or wait shell`() {
        assertFalse("--resume" in argv)
        val resumed = firstMatePod(name, session, "img", firstMateCommand(argv), name, null, 1, 1.0, resume = true)
        val mate = resumed.spec.containers.single()
        assertEquals("1", mate.env.single { it.name == "FM_RESUME" }.value)
        assertEquals(listOf("fm-relay", "--") + argv, mate.command)
        assertFalse(resumed.toString().contains("first-mate-restored"))
    }

    @Test
    fun `a native sidecar alone gets the repo's git token from the Secret and clones in the background, and the main container never sees it`() {
        val mate = pod.spec.containers.single()
        assertEquals("acme/tickets", mate.env.single { it.name == "FM_REPO" }.value)
        assertEquals(FIRST_MATE_CLONE, mate.env.single { it.name == "FM_CLONE" }.value)
        assertEquals(FIRST_MATE_CWD, mate.workingDir)
        assertNull(pod.spec.initContainers.single().env.singleOrNull { it.name == "FM_GIT_TOKEN" }, "no token, no env")
        val spec = firstMatePod(name, session, "img", firstMateCommand(argv), name, null, 1, 1.0, gitToken = true).spec
        assertNull(spec.containers.single().env.singleOrNull { it.name == "FM_GIT_TOKEN" }, "the main container never has the token")
        assertTrue(spec.containers.single().volumeMounts.any { it.mountPath == FIRST_MATE_CLONE })
        val withToken = spec.initContainers.single()
        assertEquals(listOf("fm-relay", "--clone-sidecar", FIRST_MATE_CLONE), withToken.command)
        assertEquals("Always", withToken.restartPolicy, "a native sidecar: claude does not wait for it")
        val big = firstMatePod(name, session, "img", firstMateCommand(argv), name, null, 4L shl 30, 2.0).spec.initContainers.single().resources
        assertEquals(Quantity(CLONE_MEMORY_REQUEST.toString()) to Quantity((4L shl 30).toString()), big.requests["memory"] to big.limits["memory"])
        assertEquals("acme/tickets", withToken.env.single { it.name == "FM_REPO" }.value)
        val ref = withToken.env.single { it.name == "FM_GIT_TOKEN" }.valueFrom.secretKeyRef
        assertEquals(name to FIRST_MATE_GIT_TOKEN_KEY, ref.name to ref.key)
        val secret = firstMateSecret(name, session, FirstMateMcp(emptyMap(), emptyList()), null, null, "fw_pod", "github_pat_smith")
        assertEquals("github_pat_smith", secret.stringData[FIRST_MATE_GIT_TOKEN_KEY])
        assertNull(firstMateSecret(name, session, FirstMateMcp(emptyMap(), emptyList()), null, null, "fw_pod").stringData[FIRST_MATE_GIT_TOKEN_KEY])
        assertFalse(spec.toString().contains("github_pat_smith"), "the token is never in the pod's spec")
    }
}

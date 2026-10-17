package com.codefrak.engine.smith

import com.codefrak.engine.accounts.AiAccount
import com.codefrak.engine.accounts.AiAccounts
import com.codefrak.engine.github.CommitIdentity
import com.codefrak.engine.github.TokenIdentity
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the frakworks server in a Smith adds to the sandbox (engine/README.md,
 * "The frakworks server in a Smith"): one plain URL in the env, no mount, no
 * privilege; and how the engine finds the calling Smith by address.
 */
class SandboxIsolationTest {
    private val spec = SmithSpec("run-1", "acme/widgets", 7, "claude-opus-5-5", "medium", dryRun = false, account = "acct")
    private val credential = "CLAUDE_CODE_OAUTH_TOKEN" to "tok"
    private fun keys(env: List<String>) = env.map { it.substringBefore('=') }.toSet()

    /** The env a sandbox got before the server: the run's inputs, GH_TOKEN and the one AI credential. */
    private val before = setOf("SMITH_REPO", "SMITH_ISSUE", "SMITH_ID", "SMITH_MODEL", "SMITH_EFFORT", "SMITH_DRY_RUN", "SMITH_MODE",
        "GH_TOKEN", "CLAUDE_CODE_OAUTH_TOKEN", "SMITH_ACCOUNT")

    @Test
    fun `a spec with a turn budget puts it in the env as SMITH_TURNS, and one without adds no key`() {
        val env = sandboxEnv(spec.copy(mode = "parent", turns = 1000), "gh", credential, "")
        assertEquals(before + "SMITH_TURNS", keys(env))
        assertTrue("SMITH_TURNS=1000" in env)
        assertEquals(before, keys(sandboxEnv(spec.copy(mode = "parent"), "gh", credential, "")))
    }

    @Test
    fun `the env gains ENGINE_URL only, and only when an engine url is configured`() {
        assertEquals(before, keys(sandboxEnv(spec, "gh", credential, "")))
        // A job run (no issue): SMITH_JOB in place of SMITH_ISSUE, and the job label in place of the issue label.
        val job = spec.copy(issue = null, jobId = "job-7", mode = "job")
        assertEquals(before - "SMITH_ISSUE" + "SMITH_JOB", keys(sandboxEnv(job, "gh", credential, "")))
        assertEquals(mapOf(RUN_LABEL to "run-1", REPO_LABEL to "acme/widgets", JOB_LABEL to "job-7"), sandboxLabels(job))
        assertEquals(mapOf(RUN_LABEL to "run-1", REPO_LABEL to "acme/widgets", ISSUE_LABEL to "7"), sandboxLabels(spec))
        val env = sandboxEnv(spec, "gh", credential, "http://engine:8080")
        assertEquals(before + "ENGINE_URL", keys(env))
        assertTrue("ENGINE_URL=http://engine:8080" in env)
    }

    @Test
    fun `a run of a repo with Smith-audience MCP servers gets them as SMITH_MCP_SERVERS, and no other key`() {
        val env = sandboxEnv(spec, "gh", credential, "http://engine:8080", mcpServers = """{"ops-server":{"type":"http","url":"http://ops/mcp","headers":{"X-Frakworks-Run":"run-1"}}}""")
        assertEquals(before + setOf("ENGINE_URL", "SMITH_MCP_SERVERS"), keys(env))
        assertTrue(env.any { it.startsWith("""SMITH_MCP_SERVERS={"ops-server":""") })
    }

    @Test
    fun `a refine run gets the token's scopes, never the token, as SMITH_GH_SCOPES`() {
        val env = sandboxEnv(spec.copy(mode = "refine", ghScopes = "repo"), "gh", credential, "")
        assertEquals(before + "SMITH_GH_SCOPES", keys(env))
        assertTrue("SMITH_GH_SCOPES=repo" in env)
    }

    @Test
    fun `only a refine run is told the token's scopes`() {
        assertEquals("repo,workflow", ghScopes("refine") { setOf("workflow", "repo") })
        assertEquals("unknown", ghScopes("refine") { null })
        assertEquals("none", ghScopes("refine") { emptySet() })
        assertNull(ghScopes("work") { setOf("repo") })
        assertNull(ghScopes("review") { setOf("repo") })
    }

    @Test
    fun `a run that commits gets the token's account as its committer, and a dry run gets none`() {
        val env = sandboxEnv(spec, "gh", credential, "", com.codefrak.engine.github.CommitIdentity("Fleet Bot", "7+fleet-bot@users.noreply.github.com"))
        assertEquals(before + setOf("SMITH_GIT_NAME", "SMITH_GIT_EMAIL"), keys(env))
        assertTrue("SMITH_GIT_NAME=Fleet Bot" in env && "SMITH_GIT_EMAIL=7+fleet-bot@users.noreply.github.com" in env)
        assertEquals(before, keys(sandboxEnv(spec.copy(dryRun = true), "gh", credential, "")))
    }

    @Test
    fun `no credential-looking key beyond GH_TOKEN and the account's own`() {
        val secretish = Regex("TOKEN|KEY|SECRET|PASSWORD|CREDENTIAL")
        val found = keys(sandboxEnv(spec, "gh", credential, "http://engine:8080")).filter { secretish.containsMatchIn(it) }.toSet()
        assertEquals(setOf("GH_TOKEN", "CLAUDE_CODE_OAUTH_TOKEN"), found)
    }

    @Test
    fun `the host config has no mount, no volume and no privilege`() {
        val host = sandboxHostConfig(2147483648L, 2.0)
        assertTrue(host.binds.isNullOrEmpty(), "no binds")
        assertTrue(host.mounts.isNullOrEmpty(), "no mounts")
        assertTrue(host.volumesFrom.isNullOrEmpty(), "no volumes-from")
        assertTrue(host.privileged != true, "not privileged")
        assertTrue(host.capAdd.isNullOrEmpty(), "no capability added")
    }

    @Test
    fun `smithCredentialAndCommitter looks up the account's credential, and the committer unless it's a dry run`() {
        val accounts = mock(AiAccounts::class.java)
        val identity = mock(TokenIdentity::class.java)
        val committer = CommitIdentity("Fleet Bot", "7+fleet-bot@users.noreply.github.com")
        `when`(identity.commitIdentity("acme/widgets")).thenReturn(committer)

        // No account, dry run: neither credential nor committer.
        assertEquals(null to null, smithCredentialAndCommitter(spec.copy(account = null, dryRun = true), accounts, identity))

        // No account, non-dry run: committer only.
        assertEquals(null to committer, smithCredentialAndCommitter(spec.copy(account = null), accounts, identity))

        // A known account with a credential: both, alongside the committer.
        val account = AiAccount("acct", "anthropic", null, true, true, OffsetDateTime.now(), OffsetDateTime.now())
        `when`(accounts.get("acct")).thenReturn(account)
        `when`(accounts.credential("acct")).thenReturn("tok")
        assertEquals(credential to committer, smithCredentialAndCommitter(spec, accounts, identity))

        // An unknown account: IllegalArgumentException naming it.
        `when`(accounts.get("missing")).thenReturn(null)
        val unknown = assertFailsWith<IllegalArgumentException> {
            smithCredentialAndCommitter(spec.copy(account = "missing"), accounts, identity)
        }
        assertTrue("missing" in unknown.message!!)

        // A known account with no credential: IllegalStateException naming it.
        `when`(accounts.get("nocred")).thenReturn(account.copy(name = "nocred"))
        `when`(accounts.credential("nocred")).thenReturn(null)
        val noCred = assertFailsWith<IllegalStateException> {
            smithCredentialAndCommitter(spec.copy(account = "nocred"), accounts, identity)
        }
        assertTrue("nocred" in noCred.message!!)
    }

    private fun container(labels: Map<String, String>, networks: Map<String, String>) = SandboxAddresses(labels, networks)

    @Test
    fun `callerAt finds the run by its address on the Smith network, and nothing else`() {
        val smiths = listOf(
            container(mapOf(RUN_LABEL to "run-1", ISSUE_LABEL to "7", REPO_LABEL to "acme/widgets"), mapOf("frakworks_default" to "172.18.0.5")),
            container(mapOf(RUN_LABEL to "run-2", ISSUE_LABEL to "8"), mapOf("other" to "10.0.0.9")),
        )
        assertEquals(SmithCaller("run-1", 7, "acme/widgets"), callerAt(smiths, "frakworks_default", "172.18.0.5"))
        assertEquals(SmithCaller("run-1", 7, "acme/widgets"), callerAt(smiths, "frakworks_default", "::ffff:172.18.0.5"))
        assertNull(callerAt(smiths, "frakworks_default", "10.0.0.9"), "an address on another network is not a Smith there")
        assertNull(callerAt(smiths, "frakworks_default", "172.18.0.1"), "the gateway is no Smith")
        assertEquals(SmithCaller("run-2", 8, null), callerAt(smiths, "", "10.0.0.9"))
        assertNull(callerAt(smiths, "frakworks_default", ""))
    }
}

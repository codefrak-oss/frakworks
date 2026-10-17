package com.codefrak.engine.deploy

import com.codefrak.engine.ProcessFakes
import com.codefrak.engine.github.GitHubCompare
import com.codefrak.engine.repos.DeployState
import com.codefrak.engine.repos.RepoMcpServers
import com.codefrak.engine.repos.StubMcpServer
import com.codefrak.engine.repos.TargetRepos
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `DeployedChecks.isDeployed` (#100008): the engine's own repo from its build, any other through its ops server. */
@SpringBootTest
@Import(ProcessFakes::class, DeployedChecksTest.Compare::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake", "engine.build.repo=acme/engine", "engine.build.commit=" + DeployedChecksTest.COMMIT])
class DeployedChecksTest {
    companion object { const val COMMIT = "0123456789abcdef0123456789abcdef01234567" }

    /** GitHub's compare, scripted: `status` by `base...head`. */
    class FakeCompare : GitHubCompare {
        val statuses = mutableMapOf<String, String>()
        val asked = mutableListOf<Triple<String, String, String>>()
        override fun status(repo: String, base: String, head: String): String { asked += Triple(repo, base, head); return statuses["$base...$head"] ?: "diverged" }
    }
    @TestConfiguration
    class Compare { @Bean @Primary fun compare() = FakeCompare() }

    @Autowired lateinit var checks: DeployedChecks
    @Autowired lateinit var compare: FakeCompare
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var store: RepoMcpServers

    private val own = "acme/engine"; private val other = "acme/app"

    @BeforeEach fun setUp() { for (r in listOf(own, other)) { repos.remove(r); repos.add(r) }; compare.statuses.clear(); compare.asked.clear() }
    @AfterEach fun tearDown() { for (r in listOf(own, other)) repos.remove(r) }

    @Test
    fun `the engine's own repo answers from its build commit and GitHub's compare`() {
        assertEquals(DeployState.DEPLOYED, checks.isDeployed(own, COMMIT), "the running commit")
        assertEquals(DeployState.DEPLOYED, checks.isDeployed(own, "0123456"), "its short form")
        assertEquals(emptyList(), compare.asked, "no compare for the commit itself")
        val ancestor = "a".repeat(40); val later = "b".repeat(40)
        compare.statuses["$ancestor...$COMMIT"] = "ahead"
        assertEquals(DeployState.DEPLOYED, checks.isDeployed(own, ancestor), "an ancestor: head is ahead of it")
        assertEquals(DeployState.NOT_YET, checks.isDeployed(own, later), "not merged before the build")
        compare.statuses["$later...$COMMIT"] = "behind"
        assertEquals(DeployState.NOT_YET, checks.isDeployed(own, later))
        assertEquals(Triple(own, ancestor, COMMIT), compare.asked.first(), "base is the asked sha, head the running commit")
    }

    @Test
    fun `another repo asks its ops server, and without one NoOpsServer names what the repo needs`() {
        val e = assertThrows<NoOpsServer> { checks.isDeployed(other, "abcdef0") }
        assertTrue("add_repo_mcp_server(repo: \"acme/app\", role: \"ops\"" in e.message!!, e.message)
        assertTrue("ops/1" in e.message!! && "is_deployed" in e.message!!, e.message)
        StubMcpServer(answer = { args -> mapOf("structuredContent" to mapOf("state" to if (args["sha"] == "abcdef0") "deployed" else "not_yet")) }).use { stub ->
            store.put(other, "ops", mapOf("kind" to "http", "audience" to "smith", "role" to "ops", "url" to stub.url), "alice")
            assertEquals(DeployState.DEPLOYED, checks.isDeployed(other, "ABCDEF0"))
            assertEquals(DeployState.NOT_YET, checks.isDeployed(other, "1234567"))
            assertEquals<List<Map<*, *>>>(listOf(mapOf("sha" to "abcdef0"), mapOf("sha" to "1234567")), stub.calls)
        }
        assertEquals(emptyList(), compare.asked, "never GitHub for another repo")
    }

    @Test
    fun `an empty or malformed sha is refused, never a silent deployed`() {
        for (sha in listOf("", "  ", "abc", "xyzxyzx", "a".repeat(41))) {
            val e = assertThrows<IllegalArgumentException>("'$sha' on the own repo") { checks.isDeployed(own, sha) }
            assertTrue(own in e.message!! && DeployEvents.SHA_400 in e.message!!, e.message)
            assertThrows<IllegalArgumentException>("'$sha' on another repo") { checks.isDeployed(other, sha) }
        }
        assertEquals(emptyList(), compare.asked, "nothing reaches GitHub")
    }
}

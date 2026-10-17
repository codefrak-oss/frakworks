package com.codefrak.engine.ci

import com.codefrak.engine.ProcessFakes
import com.codefrak.engine.repos.TargetRepos
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** GET /repos/{owner}/{name}/ci/runs (engine/README.md, "CI runs of a repo"): the answer and its refusals. */
@SpringBootTest
@Import(ProcessFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class CiRunsControllerTest {
    @Autowired lateinit var controller: CiRunsController
    @Autowired lateinit var runs: FakeCheckRuns
    @Autowired lateinit var repos: TargetRepos

    private val repo = "acme/ci-runs"

    @BeforeEach fun setUp() { runs.reset(); repos.add(repo) }
    @AfterEach fun tearDown() { runs.reset(); repos.remove(repo) }

    private fun refused(limit: String?, owner: String = "acme", name: String = "ci-runs") =
        assertFailsWith<ResponseStatusException> { controller.runs(owner, name, limit, null, null) }

    @Test
    fun `a target repo's runs, newest first, with their kinds, read once from GitHub`() {
        runs.defaultBranchName = "trunk"
        runs.workflowRunRows = listOf(
            WorkflowRun(3, "Engine", "smith/issue-1", "pull_request", "in_progress", null, "2026-10-06T10:00:00Z", "2026-10-06T10:01:00Z", null, "u3", ".github/workflows/engine.yml", "sha3", 103),
            WorkflowRun(2, "Images", "trunk", "push", "completed", "success", "2026-10-06T09:00:00Z", "2026-10-06T09:00:10Z", "2026-10-06T09:05:10Z", "u2", ".github/workflows/images.yml", "sha2", 42),
            WorkflowRun(1, "Release", "trunk", "release", "completed", "failure", null, null, null, "u1", ".github/workflows/desktop-release.yml", "sha1", 7))
        val body = controller.runs("ACME", "CI-Runs", "20", "engine.yml", "main")
        assertEquals(listOf<Any?>(repo, "engine.yml", "main", 20), runs.workflowRunsAsked.single())
        assertEquals(repo, body["repo"])
        val rows = (body["runs"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf(3L, 2L, 1L), rows.map { it["id"] })
        assertEquals(setOf("id", "workflow", "branch", "event", "status", "conclusion", "createdAt", "startedAt", "completedAt",
            "durationSeconds", "queuedSeconds", "url", "area", "trigger", "headSha", "runNumber"), rows[0].keys)
        assertEquals(listOf("engine", "images", "desktop-release"), rows.map { it["area"] })
        assertEquals(listOf("pr", "main", "release"), rows.map { it["trigger"] })
        assertEquals(listOf("sha3", "sha2", "sha1"), rows.map { it["headSha"] })
        assertEquals(listOf(103L, 42L, 7L), rows.map { it["runNumber"] })
        assertEquals(3, (body["summary"] as Map<*, *>)["count"])
    }

    @Test
    fun `a limit of 0, 101 or none is refused with 400`() {
        for (limit in listOf("0", "101", null, "x")) {
            val e = refused(limit)
            assertEquals(HttpStatus.BAD_REQUEST, e.statusCode)
            assertEquals("not a limit: ${limit ?: "none"} (an integer from 1 to 100)", e.reason)
        }
        assertTrue(runs.workflowRunsAsked.isEmpty())
    }

    @Test
    fun `a repo that is not a target repo is refused with 400 naming the target repos`() {
        val e = refused("20", "acme", "nowhere")
        assertEquals(HttpStatus.BAD_REQUEST, e.statusCode)
        assertTrue(e.reason!!.startsWith("acme/nowhere is not a target repo; the target repos are "), e.reason)
        assertTrue(e.reason!!.contains(repo), e.reason)
    }

    @Test
    fun `a GitHub error answers 502 with its message`() {
        runs.workflowRunsFailure = "403 Forbidden"
        val e = refused("20")
        assertEquals(HttpStatus.BAD_GATEWAY, e.statusCode)
        assertEquals("could not read the workflow runs of $repo: 403 Forbidden", e.reason)
    }
}

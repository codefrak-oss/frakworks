package com.codefrak.engine.ci

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A workflow run's kind (#1935): its area from the workflow file, its trigger from the event and branch. Plain JUnit. */
class CiRunsTest {
    @Test
    fun `area is the workflow file's name without its extension, null when unknown`() {
        assertEquals("engine", CiRuns.area(".github/workflows/engine.yml"))
        assertEquals("desktop-release", CiRuns.area(".github/workflows/desktop-release.yml"))
        assertEquals("images", CiRuns.area("images.yaml"))
        assertNull(CiRuns.area(null))
        assertNull(CiRuns.area(""))
    }

    @Test
    fun `trigger is pr, main, branch or the event name`() {
        assertEquals("pr", CiRuns.trigger("pull_request", "smith/issue-1", "main"))
        assertEquals("pr", CiRuns.trigger("pull_request_target", "x", "main"))
        assertEquals("main", CiRuns.trigger("push", "trunk", "trunk"))
        assertEquals("branch", CiRuns.trigger("push", "main", "trunk"))
        assertEquals("workflow_dispatch", CiRuns.trigger("workflow_dispatch", "main", "main"))
        assertEquals("schedule", CiRuns.trigger("schedule", null, "main"))
        assertNull(CiRuns.trigger(null, "main", "main"))
    }

    @Test
    fun `read answers the rows with their kinds, and main as the default branch when that read fails`() {
        val runs = object : CheckRuns {
            override fun latest(repo: String, sha: String, name: String): CheckRun? = null
            override fun log(repo: String, checkRunId: Long) = ""
            override fun rerun(repo: String, checkRunId: Long) {}
            override fun workflowRuns(repo: String, workflow: String?, branch: String?, limit: Int) = listOf(
                WorkflowRun(2, "Engine", "main", "push", "queued", null, null, null, null, "u2", ".github/workflows/engine.yml",
                    "sha2", 42),
                WorkflowRun(1, "Images", "dev", "push", "completed", "success", null, null, null, "u1", ".github/workflows/images.yml",
                    "sha1", 7))
            override fun defaultBranch(repo: String): String = error("403 Forbidden")
        }
        val rows = CiRuns.read(runs, "acme/widgets", null, null, 1)["runs"] as List<*>
        assertEquals(1, rows.size)
        val row = rows.single() as Map<*, *>
        assertEquals("engine", row["area"])
        assertEquals("main", row["trigger"])
        assertEquals("sha2", row["headSha"])
        assertEquals(42L, row["runNumber"])
    }
}

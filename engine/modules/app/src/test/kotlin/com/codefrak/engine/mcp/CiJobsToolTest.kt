package com.codefrak.engine.mcp

import com.codefrak.engine.ci.CheckRun
import com.codefrak.engine.ci.CheckRuns
import com.codefrak.engine.ci.CiLogCache
import com.codefrak.engine.ci.JobStep
import com.codefrak.engine.ci.LogFetch
import com.codefrak.engine.ci.RestCheckRuns
import com.codefrak.engine.ci.WorkflowJob
import com.codefrak.engine.ci.WorkflowRun
import com.codefrak.engine.engineJson
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.tier.SeededTiers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** read_ci_jobs, read_ci_job and search_ci_log (#2188): First mate's bounded reads of a target repo's CI jobs and logs. */
class CiJobsToolTest {
    private class Runs : CheckRuns {
        val jobsByRun = mutableMapOf<Long, List<WorkflowJob>>()
        val jobsById = mutableMapOf<Long, WorkflowJob>()
        var logText: String? = "2026-10-10T10:00:00.1Z line one\n2026-10-10T10:00:01.2Z #5 3.1 e: file.kt:3:1 Unresolved reference: x\n" +
            "2026-10-10T10:00:02Z FooTest > bar() FAILED\n2026-10-10T10:00:03Z BUILD FAILED\n"
        var logFetches = 0
        override fun latest(repo: String, sha: String, name: String): CheckRun? = null
        override fun log(repo: String, checkRunId: Long): String { logFetches++; return logText ?: error("403 Forbidden") }
        override fun rerun(repo: String, checkRunId: Long) = Unit
        override fun jobs(repo: String, runId: Long) = jobsByRun[runId] ?: error("404 Not Found")
        override fun job(repo: String, jobId: Long) = jobsById[jobId] ?: error("404 Not Found")
        val heads = mutableMapOf<Long, String>()
        val runsOn = mutableMapOf<String, List<WorkflowRun>>()
        val reruns = mutableListOf<Long>()
        override fun pullHead(repo: String, pr: Long) = heads[pr] ?: error("404 Not Found")
        override fun workflowRunsOn(repo: String, sha: String, limit: Int) = runsOn[sha].orEmpty()
        override fun rerunFailed(repo: String, runId: Long) { reruns += runId }
    }

    private val now = Instant.parse("2026-10-10T11:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val runs = Runs()
    private val engine = FakeEngine()
    private val tool = CiJobsTool(engine, runs, CiLogCache(clock), clock)
    private val mcp = FrakworksMcp(engine, SeededTiers.table(), FakeFrictionLog(), checkRuns = runs)
    private val mate = McpCaller.Nobody("127.0.0.1", "someone")
    private val smith = McpCaller.Smith(SmithCaller("run-1", 7, "acme/widgets"))

    private fun body(r: McpToolResult): Map<*, *> { assertFalse(r.isError, r.text); return engineJson.readValue(r.text, Map::class.java) }
    private fun error(r: McpToolResult): String { assertTrue(r.isError, r.text); return engineJson.readValue(r.text, Map::class.java)["error"] as String }

    private fun step(n: Int, status: String, conclusion: String?, from: String?, to: String?) = JobStep(n, "step $n", status, conclusion, from, to)
    private val failedJob = WorkflowJob(77, 5, "build", "completed", "failure", "2026-10-10T10:00:00Z", "2026-10-10T10:06:00Z", "u77", listOf(
        step(1, "completed", "success", "2026-10-10T10:00:00Z", "2026-10-10T10:00:10Z"),
        step(2, "completed", "failure", "2026-10-10T10:00:10Z", "2026-10-10T10:05:10Z"),
        step(3, "completed", "success", "2026-10-10T10:05:10Z", "2026-10-10T10:06:00Z")))
    private val runningJob = WorkflowJob(78, 5, "test", "in_progress", null, "2026-10-10T10:00:00Z", null, "u78", listOf(
        step(1, "completed", "success", "2026-10-10T10:00:00Z", "2026-10-10T10:00:20Z"),
        step(2, "completed", "success", "2026-10-10T10:00:20Z", "2026-10-10T10:00:50Z"),
        step(3, "in_progress", null, "2026-10-10T10:00:50Z", null),
        step(4, "queued", null, null, null)))

    init {
        runs.jobsByRun[5] = listOf(failedJob, runningJob)
        runs.jobsById[77] = failedJob
        runs.jobsById[78] = runningJob
    }

    @Test
    fun `First mate and a Smith both get the four tools, and a Smith calls each on its run's repo only`() {
        assertTrue(mcp.tools(mate).map { it.name }.containsAll(CiJobsTool.NAMES))
        assertTrue(mcp.tools(smith).map { it.name }.containsAll(CiJobsTool.NAMES))
        runs.jobsByRun[60] = listOf(job(601, 60, "completed", "failure"))
        assertEquals(5, body(mcp.call("read_ci_jobs", mapOf("repo" to "acme/widgets", "run_id" to 5), smith))["runId"])
        assertEquals(77, (body(mcp.call("read_ci_job", mapOf("job_id" to 77), smith))["job"] as Map<*, *>)["id"])
        assertEquals(77, body(mcp.call("search_ci_log", mapOf("job_id" to 77, "pattern" to "FAILED"), smith))["jobId"])
        assertEquals(1, (body(mcp.call(CiJobsTool.RERUN, mapOf("repo" to "acme/widgets", "run_id" to 60), smith))["rerun"] as List<*>).size)
        assertEquals(listOf(60L), runs.reruns)
        for (name in CiJobsTool.NAMES) {
            val id = if (name == "read_ci_jobs" || name == CiJobsTool.RERUN) "run_id" to 60 else "job_id" to 77
            assertEquals("a Smith may call $name on its run's repo only: acme/widgets, not acme/gadgets",
                error(mcp.call(name, mapOf("repo" to "acme/gadgets", id), smith)))
        }
        assertEquals(listOf(60L), runs.reruns)
        assertEquals(5, body(mcp.call("read_ci_jobs", mapOf("repo" to "acme/widgets", "run_id" to 5), mate))["runId"])
    }

    @Test
    fun `each tool refuses no repo and a non-target repo naming the target repos, other arguments and bad ids`() {
        for ((name, id) in listOf("read_ci_jobs" to "run_id", "read_ci_job" to "job_id", "search_ci_log" to "job_id")) {
            val missing = error(tool.call(name, mapOf(id to 5)))
            assertTrue("no default repo" in missing && "the target repos are acme/widgets" in missing, missing)
            val other = error(tool.call(name, mapOf("repo" to "acme/nowhere", id to 5)))
            assertTrue(other.startsWith("acme/nowhere is not a target repo; the target repos are acme/widgets"), other)
            assertTrue(error(tool.call(name, mapOf("repo" to "acme/widgets", id to 5, "bogus" to 1))).startsWith("$name takes only"))
            for (bad in listOf(0, -3, "5", null, 1.5))
                assertTrue(error(tool.call(name, mapOf("repo" to "acme/widgets", id to bad))).startsWith("not a "), "$name $bad")
        }
        assertEquals("not a tail: 201 (an integer from 1 to 200)", error(tool.call("read_ci_job", mapOf("repo" to "acme/widgets", "job_id" to 77, "tail" to 201))))
        assertEquals("not a context: 6 (an integer from 0 to 5)", error(tool.call("search_ci_log", mapOf("repo" to "acme/widgets", "job_id" to 77, "context" to 6))))
        assertEquals("not a max: 51 (an integer from 1 to 50)", error(tool.call("search_ci_log", mapOf("repo" to "acme/widgets", "job_id" to 77, "max" to 51))))
        assertTrue(error(tool.call("search_ci_log", mapOf("repo" to "acme/widgets", "job_id" to 77, "pattern" to "(unclosed"))).startsWith("not a pattern: not a regex"))
        assertEquals("could not read job 99 in acme/widgets: 404 Not Found", error(tool.call("read_ci_job", mapOf("repo" to "acme/widgets", "job_id" to 99))))
        assertEquals(0, runs.logFetches)
    }

    @Test
    fun `read_ci_jobs answers each job's steps with elapsed seconds, the slowest step, and a running step possibly stuck`() {
        val b = body(tool.call("read_ci_jobs", mapOf("repo" to "ACME/Widgets", "run_id" to 5)))
        assertEquals("acme/widgets", b["repo"])
        val jobs = b["jobs"] as List<*>
        val failed = jobs[0] as Map<*, *>
        assertEquals(360, failed["elapsedSeconds"])
        assertEquals("step 2", failed["slowestStep"])
        val steps = failed["steps"] as List<*>
        assertEquals(listOf(10, 300, 50), steps.map { (it as Map<*, *>)["elapsedSeconds"] })
        assertEquals(true, (steps[1] as Map<*, *>)["slowest"])
        val running = (jobs[1] as Map<*, *>)["steps"] as List<*>
        // Running since 10:00:50, now 11:00: 3550 s, past 600 s and 3x the 25 s median finished step.
        assertEquals(3550, (running[2] as Map<*, *>)["elapsedSeconds"])
        assertEquals(true, (running[2] as Map<*, *>)["possiblyStuck"])
        assertNull((running[3] as Map<*, *>)["elapsedSeconds"])
        assertEquals(0, runs.logFetches)
    }

    @Test
    fun `GitHub's jobs response parses into jobs and steps`() {
        val fixture = """{"jobs":[{"id":77,"run_id":5,"name":"build","status":"completed","conclusion":"failure",
            "started_at":"2026-10-10T10:00:00Z","completed_at":"2026-10-10T10:06:00Z","html_url":"https://github.com/acme/widgets/actions/runs/5/job/77",
            "steps":[{"name":"Set up job","status":"completed","conclusion":"success","number":1,
            "started_at":"2026-10-10T10:00:00Z","completed_at":"2026-10-10T10:00:10Z"}]}]}"""
        val jobs = engineJson.readValue(fixture, RestCheckRuns.ActionsJobs::class.java).jobs.map { it.toJob() }
        assertEquals(listOf(WorkflowJob(77, 5, "build", "completed", "failure", "2026-10-10T10:00:00Z", "2026-10-10T10:06:00Z",
            "https://github.com/acme/widgets/actions/runs/5/job/77",
            listOf(JobStep(1, "Set up job", "completed", "success", "2026-10-10T10:00:00Z", "2026-10-10T10:00:10Z")))), jobs)
    }

    @Test
    fun `read_ci_job answers a failed job's failures and log end through CiLog, and a passed job without its log`() {
        val b = body(tool.call("read_ci_job", mapOf("repo" to "acme/widgets", "job_id" to 77)))
        assertEquals(listOf("e: file.kt:3:1 Unresolved reference: x", "FooTest > bar() FAILED"), b["failures"])
        assertEquals("BUILD FAILED", (b["tail"] as List<*>).last())
        assertEquals("line one", (b["tail"] as List<*>).first())
        runs.jobsById[79] = failedJob.copy(id = 79, conclusion = "success")
        val passed = body(tool.call("read_ci_job", mapOf("repo" to "acme/widgets", "job_id" to 79)))
        assertNull(passed["tail"])
        assertEquals(1, runs.logFetches)
    }

    @Test
    fun `read_ci_job's tail is capped, and a running job's says how long its log has been quiet`() {
        runs.logText = (1..500).joinToString("\n") { "2026-10-10T10:30:00Z line $it" }
        val b = body(tool.call("read_ci_job", mapOf("repo" to "acme/widgets", "job_id" to 78, "tail" to 200)))
        val tail = b["tail"] as List<*>
        assertEquals(200, tail.size)
        assertEquals("line 500", tail.last())
        assertEquals("2026-10-10T10:30:00Z", b["lastOutputAt"])
        assertEquals(1800, b["silentSeconds"])
    }

    @Test
    fun `a completed job's log is fetched once across reads and searches, and a running job's every time`() {
        tool.call("read_ci_job", mapOf("repo" to "acme/widgets", "job_id" to 77))
        tool.call("search_ci_log", mapOf("repo" to "acme/widgets", "job_id" to 77, "pattern" to "FAILED"))
        tool.call("read_ci_job", mapOf("repo" to "Acme/Widgets", "job_id" to 77, "tail" to 3))
        assertEquals(1, runs.logFetches)
        tool.call("search_ci_log", mapOf("repo" to "acme/widgets", "job_id" to 78, "pattern" to "x"))
        tool.call("search_ci_log", mapOf("repo" to "acme/widgets", "job_id" to 78, "pattern" to "x"))
        assertEquals(3, runs.logFetches)
    }

    @Test
    fun `search_ci_log answers matches with line numbers and context, none, and the total past max`() {
        val one = body(tool.call("search_ci_log", mapOf("repo" to "acme/widgets", "job_id" to 77, "pattern" to "FAILED", "fixed" to true, "context" to 1, "max" to 1)))
        assertEquals(2, one["total"])
        val m = (one["matches"] as List<*>).single() as Map<*, *>
        assertEquals(3, m["line"])
        assertEquals("FooTest > bar() FAILED", m["text"])
        assertEquals(listOf("e: file.kt:3:1 Unresolved reference: x"), m["before"])
        assertEquals(listOf("BUILD FAILED"), m["after"])
        val none = body(tool.call("search_ci_log", mapOf("repo" to "acme/widgets", "job_id" to 77, "pattern" to "nothing here")))
        assertEquals(0, none["total"])
        assertEquals(emptyList<Any>(), none["matches"])
        runs.logText = (1..1000).joinToString("\n") { "warning: thing $it" }
        runs.jobsById[80] = failedJob.copy(id = 80)
        val many = body(tool.call("search_ci_log", mapOf("repo" to "acme/widgets", "job_id" to 80)))
        assertEquals(1000, many["total"])
        assertEquals(20, (many["matches"] as List<*>).size)
    }

    @Test
    fun `an answer longer than the budget is cut and says so`() {
        runs.logText = (1..1000).joinToString("\n") { "warning " + "y".repeat(390) + " $it" }
        runs.jobsById[81] = failedJob.copy(id = 81)
        val r = tool.call("search_ci_log", mapOf("repo" to "acme/widgets", "job_id" to 81, "context" to 5, "max" to 50))
        assertTrue(r.text.length <= FrakworksMcp.PR_CHAR_BUDGET, "${r.text.length}")
        val b = body(r)
        assertTrue((b["cut"] as String).endsWith("of 50 matches dropped to stay within 24000 characters"), b["cut"] as String)
        assertEquals(1000, b["total"])
    }

    @Test
    fun `a log above the fetch bound answers the steps and its size, and is not searched`() {
        val big = object : CheckRuns by runs {
            override fun boundedLog(repo: String, checkRunId: Long, maxBytes: Long) = LogFetch(null, 9_000_000)
        }
        val t = CiJobsTool(engine, big, CiLogCache(clock), clock)
        val b = body(t.call("read_ci_job", mapOf("repo" to "acme/widgets", "job_id" to 77)))
        assertTrue((b["logTooLarge"] as String).startsWith("too large to read: 9,000,000 bytes"), b["logTooLarge"] as String)
        assertEquals(3, ((b["job"] as Map<*, *>)["steps"] as List<*>).size)
        assertTrue(error(t.call("search_ci_log", mapOf("repo" to "acme/widgets", "job_id" to 77))).startsWith("the log of job 77 in acme/widgets is too large to read"))
    }

    private fun job(id: Long, run: Long, status: String, conclusion: String?) =
        WorkflowJob(id, run, "job $id", status, conclusion, null, null, "u$id")
    private fun run(id: Long, workflow: String) =
        WorkflowRun(id, workflow, "smith/issue-9", "push", "completed", "failure", null, null, null, "r$id", ".github/workflows/$workflow.yml")

    @Test
    fun `rerun_ci_jobs re-runs a finished run's failed jobs, by run id and by a PR's head, and says why it leaves a run alone`() {
        runs.jobsByRun[40] = listOf(job(401, 40, "completed", "success"), job(402, 40, "completed", "failure"))
        val byRun = body(tool.call(CiJobsTool.RERUN, mapOf("repo" to "acme/widgets", "run_id" to 40)))
        assertEquals(listOf(40L), runs.reruns)
        val one = (byRun["rerun"] as List<*>).single() as Map<*, *>
        assertEquals(40, one["runId"]); assertEquals(listOf("job 402"), one["jobs"])

        // The head's latest run of each workflow: engine's 42 (an older 41 is superseded), docs' 43 still running, lint's 44 passed.
        runs.reruns.clear()
        runs.heads[9] = "sha-9"
        runs.runsOn["sha-9"] = listOf(run(42, "engine"), run(43, "docs"), run(41, "engine"), run(44, "lint"))
        runs.jobsByRun[41] = listOf(job(411, 41, "completed", "failure"))
        runs.jobsByRun[42] = listOf(job(421, 42, "completed", "timed_out"))
        runs.jobsByRun[43] = listOf(job(431, 43, "in_progress", null), job(432, 43, "completed", "failure"))
        runs.jobsByRun[44] = listOf(job(441, 44, "completed", "success"))
        val byPr = body(mcp.call(CiJobsTool.RERUN, mapOf("repo" to "acme/widgets", "pr" to 9), mate))
        assertEquals(listOf(42L), runs.reruns, "only the finished, failed latest run")
        assertEquals("sha-9", byPr["headSha"])
        assertEquals(listOf(mapOf("runId" to 42, "workflow" to "engine", "jobs" to listOf("job 421"))), byPr["rerun"])
        @Suppress("UNCHECKED_CAST")
        val skipped = byPr["skipped"] as List<Map<String, Any?>>
        assertEquals(listOf(43, 44), skipped.map { it["runId"] })
        assertTrue((skipped[0]["why"] as String).startsWith("still in progress"), skipped.toString())
        assertEquals("no failed job", skipped[1]["why"])
    }

    @Test
    fun `rerun_ci_jobs with nothing to re-run re-runs nothing, and refuses no repo, a non-target repo and a bad id`() {
        runs.jobsByRun[50] = listOf(job(501, 50, "completed", "success"))
        val passed = body(tool.call(CiJobsTool.RERUN, mapOf("repo" to "acme/widgets", "run_id" to 50)))
        assertEquals(emptyList<Any>(), passed["rerun"])
        runs.heads[10] = "sha-10"
        val none = body(tool.call(CiJobsTool.RERUN, mapOf("repo" to "acme/widgets", "pr" to 10)))
        assertEquals("no workflow run on the pull request's head commit", none["why"])
        assertEquals(emptyList<Long>(), runs.reruns)

        val missing = error(tool.call(CiJobsTool.RERUN, mapOf("run_id" to 50)))
        assertTrue("no default repo" in missing && "the target repos are acme/widgets" in missing, missing)
        assertTrue("is not a target repo" in error(tool.call(CiJobsTool.RERUN, mapOf("repo" to "acme/nowhere", "run_id" to 50))))
        assertTrue("exactly one of" in error(tool.call(CiJobsTool.RERUN, mapOf("repo" to "acme/widgets"))))
        assertTrue("exactly one of" in error(tool.call(CiJobsTool.RERUN, mapOf("repo" to "acme/widgets", "run_id" to 50, "pr" to 10))))
        assertTrue("not a pull request number" in error(tool.call(CiJobsTool.RERUN, mapOf("repo" to "acme/widgets", "pr" to 0))))
        assertEquals(emptyList<Long>(), runs.reruns)
    }
}

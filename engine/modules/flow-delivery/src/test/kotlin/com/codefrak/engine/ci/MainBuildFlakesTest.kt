package com.codefrak.engine.ci

import com.codefrak.engine.chore.FlowStarter
import com.codefrak.engine.github.IssueSnapshot
import com.codefrak.engine.intake.AuthorizedStart
import com.codefrak.engine.repos.HandledIssues
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.workitems.FakeTickets
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #100068: `main`'s `images` builds are read once each for flaky tests, and file or comment the tickets. */
class MainBuildFlakesTest {
    private val repo = "codefrak-oss/frakworks"
    private val flakyLog = """
        2026-10-15T12:00:00.0000000Z #11 88.4 > Task :engine-app:test
        2026-10-15T12:00:01.0000000Z #11 90.1 FLAKY-TEST: com.x.FooTest > does a thing() passed on retry
        2026-10-15T12:00:02.0000000Z #11 99.0 BUILD SUCCESSFUL in 5m
    """.trimIndent()

    /** The `images` runs, the engine job of each (id = run id * 10) and the logs by job id. */
    private class Runs : CheckRuns {
        var rows = listOf<WorkflowRun>()
        val logs = mutableMapOf<Long, String>()
        val asked = mutableListOf<List<Any?>>()
        val logsRead = mutableListOf<Long>()
        var failLog = false
        override fun latest(repo: String, sha: String, name: String): CheckRun? = null
        override fun log(repo: String, checkRunId: Long): String {
            if (failLog) error("502 Bad Gateway")
            logsRead += checkRunId
            return logs[checkRunId] ?: ""
        }
        override fun rerun(repo: String, checkRunId: Long) {}
        override fun workflowRuns(repo: String, workflow: String?, branch: String?, limit: Int): List<WorkflowRun> {
            asked += listOf(repo, workflow, branch); return rows
        }
        override fun jobs(repo: String, runId: Long) = listOf(
            WorkflowJob(runId * 10, runId, "build (frakworks-engine)", "completed", "success", null, null, "https://github.com/x/actions/runs/$runId/job/${runId * 10}"),
            WorkflowJob(runId * 10 + 1, runId, "build (frakworks-smith)", "completed", "success", null, null, null))
    }

    private fun run(id: Long, status: String = "completed", conclusion: String? = "success") =
        WorkflowRun(id, "images", "main", "push", status, conclusion, null, null, null, "https://github.com/x/actions/runs/$id", headSha = "merge$id-0123456789", runNumber = id)

    /** The watermark in memory: no database here; shared across readers as the table is across restarts. */
    private val watermark = object : MainBuildWatermark(JdbcClient.create(SimpleDriverDataSource())) {
        val rows = mutableMapOf<String, Long>()
        override fun last(repo: String) = rows[repo.lowercase()]
        override fun set(repo: String, runId: Long) { rows.merge(repo.lowercase(), runId, ::maxOf) }
    }
    private val starts = mutableListOf<List<Any?>>()
    private val starter = object : FlowStarter {
        override fun start(flow: String, repo: String, ticket: IssueSnapshot, authorizedBy: String, model: String?, effort: String?,
                           tier: Int?, extra: Map<String, Any>): String? =
            "pi-${ticket.number}".also { starts += listOf(flow, ticket.number, authorizedBy, tier) }
        override fun counts(instance: String?) = true
        override fun mergedClose(instance: String?) = false
        override fun steer(instance: String, by: String, message: String) = false
    }
    private val handled = object : HandledIssues(JdbcClient.create(SimpleDriverDataSource())) {
        override fun claim(repo: String, issue: Long, labelEvent: Long, flow: String, outcome: String, detail: String?, askedBy: String?,
                           points: Int?) = true
        override fun started(repo: String, issue: Long, labelEvent: Long, processInstance: String) {}
        override fun release(repo: String, issue: Long, labelEvent: Long) {}
    }
    private val tickets = FakeTickets()
    private val runs = Runs()
    private val repos = mock(TargetRepos::class.java).also {
        `when`(it.engineDefectsRepo()).thenReturn(repo)
        `when`(it.list()).thenReturn(emptyList())
    }

    /** A reader as the engine makes one; a second call is a restart. */
    private fun reader(): MainBuildFlakes {
        val start = AuthorizedStart(handled, starter, tickets)
        val flaky = object : FlakyTests(tickets, repos, start) {}
        return MainBuildFlakes(runs, flaky, repos, watermark)
    }

    @Test
    fun `a successful images run on main with a retried test files one started bug naming main's build`() {
        runs.rows = listOf(run(100)); runs.logs[1000] = flakyLog
        val ids = reader().poll()
        assertEquals(listOf(repo, MainBuildFlakes.WORKFLOW, "main"), runs.asked.single())
        assertEquals(listOf(1000L), runs.logsRead, "the engine job's log alone")
        val item = tickets.details(ids.single())!!
        assertEquals("Flaky test: com.x.FooTest.does a thing", item.title)
        assertEquals("bug", item.kind)
        assertTrue(item.body.contains("on `main`'s build (images run 100, https://github.com/x/actions/runs/100)") &&
            item.body.contains("commit merge100-012") && item.body.contains("https://github.com/x/actions/runs/100/job/1000"), item.body)
        assertTrue(item.flags.authorized)
        assertEquals(listOf(listOf<Any?>("bug", item.ticket, FlakyTests.BY, FlakyTests.TIER)), starts)
        assertEquals(100L, watermark.rows[repo])
    }

    @Test
    fun `the same test on a later images run comments on the open ticket and files no second one`() {
        runs.rows = listOf(run(100)); runs.logs[1000] = flakyLog
        val first = reader().poll().single()
        runs.rows = listOf(run(101), run(100)); runs.logs[1010] = flakyLog
        assertEquals(listOf(first), reader().poll())
        assertEquals(1, tickets.open(repo).size)
        assertEquals(1, starts.size)
        assertTrue(tickets.conversation(first).any { it.body.startsWith("Seen again: ") && it.body.contains("images run 101") })
    }

    @Test
    fun `each run is read once, across a second pass and a restart`() {
        runs.rows = listOf(run(100)); runs.logs[1000] = flakyLog
        val first = reader().also { it.poll() }
        val comments = tickets.conversation(tickets.open(repo).single().id).size
        assertEquals(emptyList(), first.poll(), "a second pass")
        assertEquals(emptyList(), reader().poll(), "after a restart")
        assertEquals(listOf(1000L), runs.logsRead)
        assertEquals(comments, tickets.conversation(tickets.open(repo).single().id).size, "nothing commented")
        assertEquals(1, starts.size)
    }

    @Test
    fun `a failed or cancelled run files nothing, and a run still going waits for the next pass`() {
        runs.rows = listOf(run(103, "in_progress", null), run(102, conclusion = "cancelled"), run(101, conclusion = "failure"))
        runs.logs[1010] = flakyLog; runs.logs[1020] = flakyLog; runs.logs[1030] = flakyLog
        assertEquals(emptyList(), reader().poll())
        assertTrue(runs.logsRead.isEmpty())
        assertEquals(102L, watermark.rows[repo], "read up to the run still going")
        runs.rows = listOf(run(103))
        assertEquals(1, reader().poll().size, "read once it completed")
    }

    @Test
    fun `a log that cannot be read is read again on the next pass`() {
        runs.rows = listOf(run(100)); runs.logs[1000] = flakyLog; runs.failLog = true
        assertEquals(emptyList(), reader().poll())
        assertEquals(null, watermark.rows[repo])
        runs.failLog = false
        assertEquals(1, reader().poll().size)
    }

    @Test
    fun `a pass for other repos, or with no engineDefects repo, reads nothing`() {
        runs.rows = listOf(run(100)); runs.logs[1000] = flakyLog
        assertEquals(emptyList(), reader().poll(setOf("acme/widgets")))
        `when`(repos.engineDefectsRepo()).thenReturn(null)
        assertEquals(emptyList(), reader().poll())
        assertTrue(runs.asked.isEmpty())
    }
}

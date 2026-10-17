package com.codefrak.engine.mcp

import com.codefrak.engine.ci.CheckRun
import com.codefrak.engine.ci.CheckRuns
import com.codefrak.engine.ci.WorkflowRun
import com.codefrak.engine.engineJson
import com.codefrak.engine.github.GitHubCompare
import com.codefrak.engine.github.TicketPull
import com.codefrak.engine.repos.ComparedCommit
import com.codefrak.engine.repos.CompareCommits
import com.codefrak.engine.repos.TicketTitles
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.tier.SeededTiers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** read_desktop_release (#100117): desktop versions from the desktop-release.yml runs, with GitHub stubbed. */
class DesktopReleaseToolTest {
    private class Runs(val list: List<WorkflowRun>) : CheckRuns {
        val asked = mutableListOf<Triple<String?, String?, Int>>()
        override fun latest(repo: String, sha: String, name: String): CheckRun? = null
        override fun log(repo: String, checkRunId: Long) = error("unused")
        override fun rerun(repo: String, checkRunId: Long) = Unit
        override fun workflowRuns(repo: String, workflow: String?, branch: String?, limit: Int): List<WorkflowRun> {
            asked += Triple(workflow, branch, limit); return list
        }
    }

    private fun run(n: Long, sha: String, conclusion: String = "success") =
        WorkflowRun(n * 10, "desktop-release", "main", "push", "completed", conclusion, null, null, null, null, headSha = sha, runNumber = n)

    // Main's line: a1 (#1 ships 0.2.6) → b2 (0.2.7) → c3 (#2, #3) → d4 (0.2.8, failed run 9 at e5) → f6 (#4, unreleased).
    private val order = listOf("aaaa111", "bbbb222", "cccc333", "dddd444", "eeee555", "ffff666")
    private val runs = Runs(listOf(run(9, "eeee555", "failure"), run(8, "dddd444"), run(7, "bbbb222"), run(6, "aaaa111")))
    private val commits = mapOf(
        "aaaa111" to ComparedCommit("aaaa111", null, "Fix one (#1) (#11)"),
        "bbbb222" to ComparedCommit("bbbb222", "aaaa111", "Chore without ticket"),
        "cccc333" to ComparedCommit("cccc333", "bbbb222", "Fix two (#2) (#12)"),
        "dddd444" to ComparedCommit("dddd444", "cccc333", "Fix three (#3) (#13)"),
    )
    private val compare = CompareCommits { _, from, to ->
        order.subList(order.indexOf(from) + 1, order.indexOf(to) + 1).mapNotNull { commits[it] }
    }
    private val titles = TicketTitles { _, n -> "Ticket $n" }
    private val statusCalls = mutableListOf<String>()
    private val gitCompare = object : GitHubCompare {
        override fun status(repo: String, base: String, head: String): String {
            statusCalls += head
            val b = order.indexOf(base); val h = order.indexOf(head)
            return when { b == h -> "identical"; h > b -> "ahead"; else -> "behind" }
        }
    }
    private val pulls = mapOf(
        2L to listOf(TicketPull(12, "Fix two", "u", "MERGED", false, null, "cccc333")),
        4L to listOf(TicketPull(14, "Fix four", "u", "MERGED", false, null, "ffff666")),
        5L to listOf(TicketPull(15, "Fix five", "u", "OPEN", false, null, null)),
        6L to emptyList(),
    )
    private val engine = FakeEngine()
    private val mcp = FrakworksMcp(engine, SeededTiers.table(), FakeFrictionLog(), checkRuns = runs, compare = compare,
        titles = titles, gitCompare = gitCompare, merges = TicketMerges { _, n -> pulls[n] })
    private val mate = McpCaller.Nobody("127.0.0.1", "someone")
    private val smith = McpCaller.Smith(SmithCaller("run-1", 7, "acme/widgets"))

    private fun call(args: Map<String, Any?>, caller: McpCaller = mate) = mcp.call(DesktopReleaseTool.NAME, args, caller)
    private fun body(r: McpToolResult): Map<*, *> { assertFalse(r.isError, r.text); return engineJson.readValue(r.text, Map::class.java) }
    private fun error(r: McpToolResult): String { assertTrue(r.isError, r.text); return r.text }

    @Test
    fun `listed to First mate and a Smith`() {
        assertTrue(mcp.tools(mate).any { it.name == DesktopReleaseTool.NAME })
        assertTrue(mcp.tools(smith).any { it.name == DesktopReleaseTool.NAME })
    }

    @Test
    fun `a version answers its commit and the tickets since the previous release`() {
        val b = body(call(mapOf("repo" to "acme/widgets", "version" to "0.2.8")))
        assertEquals("0.2.8", b["version"])
        assertEquals("dddd444", b["commit"])
        assertEquals("0.2.7", b["since"])
        assertEquals("bbbb222", b["sinceCommit"])
        assertEquals(listOf(mapOf("number" to 3, "title" to "Ticket 3", "pr" to 13), mapOf("number" to 2, "title" to "Ticket 2", "pr" to 12)), b["tickets"])
        assertEquals(Triple("desktop-release.yml", "main", 100), runs.asked.single())
    }

    @Test
    fun `a version answers the tickets since a given version`() {
        val b = body(call(mapOf("repo" to "acme/widgets", "version" to "8", "since" to "0.2.6")))
        assertEquals("0.2.6", b["since"])
        assertEquals(listOf(3, 2), (b["tickets"] as List<*>).map { (it as Map<*, *>)["number"] })
    }

    @Test
    fun `the first release has no previous one and lists no tickets`() {
        val b = body(call(mapOf("repo" to "acme/widgets", "version" to "0.2.6")))
        assertEquals("aaaa111", b["commit"])
        assertNull(b["since"])
        assertEquals(emptyList<Any>(), b["tickets"])
    }

    @Test
    fun `an unknown or failed version is an error naming the newest releases`() {
        assertEquals("no desktop release 0.2.9 in acme/widgets; the newest are 0.2.8, 0.2.7, 0.2.6",
            error(call(mapOf("repo" to "acme/widgets", "version" to "0.2.9"))))
        assertTrue(error(call(mapOf("repo" to "acme/widgets", "version" to "1.x"))).startsWith("not a desktop version"))
    }

    @Test
    fun `a ticket answers the first release containing its merge`() {
        val b = body(call(mapOf("repo" to "acme/widgets", "ticket" to 2), smith))
        assertEquals(true, b["merged"])
        assertEquals("cccc333", b["mergeCommit"])
        assertEquals("0.2.8", b["released"])
        assertEquals("0.2.8", b["newest"])
    }

    @Test
    fun `a merged ticket no release contains yet answers released null`() {
        val b = body(call(mapOf("repo" to "acme/widgets", "ticket" to 4)))
        assertEquals(true, b["merged"])
        assertNull(b["released"])
        assertEquals("0.2.8", b["newest"])
    }

    @Test
    fun `an unmerged ticket says so`() {
        assertEquals(false, body(call(mapOf("repo" to "acme/widgets", "ticket" to 5)))["merged"])
        assertEquals(false, body(call(mapOf("repo" to "acme/widgets", "ticket" to 6)))["merged"])
        assertTrue(statusCalls.isEmpty())
        assertEquals("acme/widgets has no issue #99", error(call(mapOf("repo" to "acme/widgets", "ticket" to 99))))
    }

    @Test
    fun `repo is required, with no default, and one of version or ticket`() {
        assertTrue(error(call(mapOf("version" to "0.2.8"))).contains("there is no default repo; the target repos are acme/widgets"))
        assertTrue(error(call(mapOf("repo" to "nope/nope", "version" to "0.2.8"))).startsWith("nope/nope is not a target repo"))
        assertEquals("pass exactly one of \"version\" or \"ticket\"", error(call(mapOf("repo" to "acme/widgets"))))
        assertEquals("pass exactly one of \"version\" or \"ticket\"",
            error(call(mapOf("repo" to "acme/widgets", "version" to "0.2.8", "ticket" to 2))))
        assertTrue(runs.asked.isEmpty())
    }
}

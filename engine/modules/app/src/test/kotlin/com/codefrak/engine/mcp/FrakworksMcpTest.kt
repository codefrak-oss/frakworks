package com.codefrak.engine.mcp

import com.codefrak.engine.board.Need
import com.codefrak.engine.ci.FakeCheckRuns
import com.codefrak.engine.ci.WorkflowRun
import com.codefrak.engine.camunda.TicketRun
import com.codefrak.engine.camunda.TimelineStep
import com.codefrak.engine.smith.Finding
import com.codefrak.engine.smith.SmithRunStat
import com.codefrak.engine.friction.FrictionFilter
import com.codefrak.engine.friction.FrictionLog
import com.codefrak.engine.friction.FrictionNote
import com.codefrak.engine.feature.PlanQuestion
import com.codefrak.engine.github.BundleChanges
import com.codefrak.engine.github.PullCheck
import com.codefrak.engine.github.PullFiles
import com.codefrak.engine.github.TicketPulls
import com.codefrak.engine.intake.Amended
import com.codefrak.engine.intake.TicketAmended
import com.codefrak.engine.intake.AskRequest
import com.codefrak.engine.intake.IntakeStarted
import com.codefrak.engine.intake.IntakeQuestion
import com.codefrak.engine.proposal.ProposeResult
import com.codefrak.engine.repos.AccessChange
import com.codefrak.engine.repos.AccessView
import com.codefrak.engine.repos.EngineToken
import com.codefrak.engine.repos.MintedToken
import com.codefrak.engine.repos.AddRepo
import com.codefrak.engine.repos.TargetRepo
import com.codefrak.engine.repos.TrustedLogin
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.tier.SeededTiers
import com.codefrak.engine.trigger.IssueApprovals
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.OffsetDateTime

internal class FakeEngine : FrakworksEngine {
    /** run id → root flow, for runRoot (answer attachments). */
    val runRoots = mutableMapOf<String, String>()
    override fun runRoot(runId: String) = runRoots[runId]
    val asks = mutableListOf<AskRequest>()
    var needRows = listOf<Need>()
    var transcriptDoc: Transcript? = null
    /** The target repos, as their rows spell them. */
    val targets = mutableListOf("acme/widgets", "acme/other")
    /** Closing PRs by (repo, ticket), file texts by (repo, path, ref), diffs by (repo, PR number). */
    val pulls = mutableMapOf<Pair<String, Long>, TicketPulls>()
    val files = mutableMapOf<Triple<String, String, String>, String>()
    val diffs = mutableMapOf<Pair<String, Long>, String>()

    override fun submit(ask: AskRequest, request: HttpServletRequest?): IntakeStarted { asks += ask; return IntakeStarted("intake-1", ask.repo ?: "acme/widgets", 100001, "https://github.com/acme/widgets/issues/100001") }
    override fun needs() = needRows
    /** cancel_intake's and amend_intake's calls as (id, by, reason or text); a refusal each throws instead; what amend answers. */
    val cancels = mutableListOf<Triple<String, String, String>>()
    val amends = mutableListOf<Triple<String, String, String>>()
    var cancelFails: ResponseStatusException? = null
    var amendFails: ResponseStatusException? = null
    var refining = "restarted"
    override fun cancelIntake(intakeId: String, by: String, reason: String, request: HttpServletRequest?): List<String> {
        cancelFails?.let { throw it }
        cancels += Triple(intakeId, by, reason)
        return emptyList()
    }
    override fun amendIntake(intakeId: String, text: String, by: String): Amended {
        amendFails?.let { throw it }
        amends += Triple(intakeId, by, text)
        return Amended(intakeId, refining)
    }
    /** amend_ticket's calls as (repo, ticket, by, text); a refusal throws instead. */
    val ticketAmends = mutableListOf<List<Any?>>()
    var ticketAmendFails: ResponseStatusException? = null
    override fun amendTicket(repo: String?, ticket: Long, text: String, by: String): TicketAmended {
        ticketAmendFails?.let { throw it }
        ticketAmends += listOf(repo, ticket, by, text)
        return TicketAmended(repo!!, ticket, "restarted", "the stopped work run's unpushed commits", emptyList())
    }
    override fun transcript(ticket: Long) = transcriptDoc
    /** The named runs read_transcript was asked for, and the Smith runs by id; ticket runs by (repo, ticket). */
    val namedReads = mutableListOf<NamedRun>()
    val smithRuns = mutableMapOf<String, SmithRunRef>()
    val ticketRuns = mutableMapOf<Pair<String, Long>, List<TicketRunRow>>()
    override fun transcript(ticket: Long, run: NamedRun): Transcript? { namedReads += run; return transcriptDoc }
    override fun smithRun(runId: String) = smithRuns[runId]
    /** The stuck tickets a doctor-filed intake names, by the refining run's id. */
    val stuckTickets = mutableMapOf<String, List<Pair<String, Long>>>()
    override fun doctorStuckTickets(runId: String) = stuckTickets[runId].orEmpty()
    override fun ticketRuns(repo: String, ticket: Long) = ticketRuns[repo to ticket]
    override fun targetRepo(repo: String) = targets.firstOrNull { it.equals(repo, ignoreCase = true) }
    override fun ticketPulls(repo: String, ticket: Long) = pulls[repo to ticket]
    override fun fileText(repo: String, path: String, ref: String) = files[Triple(repo, path, ref)]
    override fun pullDiff(repo: String, number: Long) = diffs[repo to number]
    /** Proposal flows by (repo, ticket), and each one's draft files at its bundle's head by path. */
    val proposals = mutableMapOf<Pair<String, Long>, ProposalFlow>()
    val drafts = mutableMapOf<Pair<String, Long>, Map<String, String>>()
    override fun proposalFlow(repo: String, ticket: Long) = proposals[repo to ticket]
    override fun draftChanges(repo: String, ticket: Long, default: String, path: String) =
        drafts[repo to ticket]?.let { BundleChanges(it.keys.toList(), null, it[path]) }
    /** The trusted logins; the intake issues waiting on an authorization; and the approvals made. */
    val trustedLogins = mutableSetOf<String>()
    val waiting = mutableSetOf<Pair<String, Long>>()
    val approvals = mutableListOf<Triple<String, Long, String>>()
    override fun trusted(login: String) = login in trustedLogins
    override fun approve(repo: String, ticket: Long, by: String): IssueApprovals.Approved? {
        if (!waiting.remove(repo to ticket)) return null
        approvals += Triple(repo, ticket, by)
        return IssueApprovals.Approved("intake-$ticket", emptyList())
    }
    /** What approveTicket answers per (repo, ticket), taken once; any other ticket is refused. And the calls made. */
    val ticketApprovable = mutableMapOf<Pair<String, Long>, IssueApprovals.TicketApproved>()
    val ticketApprovals = mutableListOf<Triple<String, Long, String>>()
    override fun approveTicket(repo: String, ticket: Long, by: String): IssueApprovals.TicketApproved {
        ticketApprovals += Triple(repo, ticket, by)
        return ticketApprovable.remove(repo to ticket) ?: IssueApprovals.TicketApproved.Refused("@$by is not a trusted login")
    }
    /** What reauthorize answers per (repo, ticket), taken once; any other ticket is refused. And the calls made. */
    val reauthorizable = mutableMapOf<Pair<String, Long>, IssueApprovals.Reauthorized>()
    val reauthorizations = mutableListOf<Triple<String, Long, String>>()
    override fun reauthorize(repo: String, ticket: Long, by: String): IssueApprovals.Reauthorized {
        reauthorizations += Triple(repo, ticket, by)
        return reauthorizable.remove(repo to ticket)
            ?: IssueApprovals.Reauthorized.Refused("$repo#$ticket is not waiting on a blocker (no deferred approval on it): nothing to re-authorize")
    }
    /** close_ticket's running flows; deferred starts; the writes in order. */
    val running = mutableMapOf<Pair<String, Long>, List<String>>()
    val deferredStarts = mutableSetOf<Pair<String, Long>>()
    val writes = mutableListOf<String>()
    var closeFails: Exception? = null
    /** Runs a close for real (the store's [com.codefrak.engine.workitems.WorkItemTickets.close]) after recording it, when set. */
    var closes: ((String, Long, String) -> Unit)? = null
    override fun runningFlows(repo: String, ticket: Long) = running[repo to ticket].orEmpty()
    override fun deferred(repo: String, ticket: Long) = (repo to ticket) in deferredStarts
    override fun dropDeferred(repo: String, ticket: Long) { deferredStarts -= repo to ticket; writes += "drop $repo#$ticket" }
    override fun close(repo: String, ticket: Long, reason: String) { closeFails?.let { throw it }; writes += "close $repo#$ticket $reason"; closes?.invoke(repo, ticket, reason) }
    /** GET /repos' rows; the POST, PATCH and DELETE /repos calls made; and the refusal the next one throws, as the API would. */
    val repoRows = mutableListOf(TargetRepo("acme/widgets", OffsetDateTime.parse("2026-09-01T08:00:00Z")))
    val repoCalls = mutableListOf<Any>()
    var repoFails: ResponseStatusException? = null
    override fun repos(login: String?) = repoRows.toList()
    override fun addRepo(body: AddRepo, request: HttpServletRequest?): Boolean {
        repoFails?.let { throw it }
        repoCalls += body
        if (repoRows.any { it.fullName == body.repo }) return false
        repoRows += TargetRepo(body.repo, OffsetDateTime.parse("2026-10-05T08:00:00Z"), body.ciCheck, body.ciPaths.orEmpty())
        return true
    }
    override fun editRepo(repo: String, fields: Map<String, Any?>, request: HttpServletRequest?): TargetRepo {
        repoFails?.let { throw it }
        repoCalls += repo to fields
        return repoRows.firstOrNull { it.fullName == repo } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "$repo is not a target repo")
    }
    override fun removeRepo(repo: String) {
        repoFails?.let { throw it }
        repoCalls += repo
        if (!repoRows.removeIf { it.fullName == repo }) throw ResponseStatusException(HttpStatus.NOT_FOUND, "$repo is not a target repo")
    }
    /** GET /access' logins and history; the POST and DELETE /access calls made, as (change, login, by). */
    val accessLogins = mutableListOf("someone", "octocat")
    val accessHistory = mutableListOf(AccessChange(OffsetDateTime.parse("2026-09-01T08:00:00Z"), "grant", "octocat", "someone"))
    val accessCalls = mutableListOf<Triple<String, String, String>>()
    override fun access() = AccessView(accessLogins.map(::TrustedLogin), accessHistory.toList())
    override fun addAccess(login: String, by: String): Boolean {
        accessCalls += Triple("grant", login, by)
        if (accessLogins.any { it.equals(login, ignoreCase = true) }) return false
        accessLogins += login
        return true
    }
    override fun removeAccess(login: String, by: String): Boolean {
        accessCalls += Triple("revoke", login, by)
        return accessLogins.removeIf { it.equals(login, ignoreCase = true) }
    }
    /** GET /tokens' rows; the mints and revokes made. A minted token is built here and nowhere else. */
    val tokenRows = mutableListOf<EngineToken>()
    val tokenCalls = mutableListOf<String>()
    override fun tokens() = tokenRows.toList()
    override fun mintToken(login: String, name: String, expiresInDays: Int?, by: String): MintedToken {
        tokenCalls += "mint $login $name $expiresInDays by $by"
        val row = EngineToken("tok-${tokenRows.size + 1}", login, name, "fakepref", OffsetDateTime.parse("2026-10-10T08:00:00Z"), by,
            expiresInDays?.let { OffsetDateTime.parse("2026-10-10T08:00:00Z").plusDays(it.toLong()) }, null, null)
        tokenRows += row
        return MintedToken(row, "frk_fake-${row.id}-" + "x".repeat(32))
    }
    override fun revokeToken(id: String): Boolean? {
        tokenCalls += "revoke $id"
        val i = tokenRows.indexOfFirst { it.id == id }
        if (i < 0) return null
        if (tokenRows[i].revokedAt != null) return false
        tokenRows[i] = tokenRows[i].copy(revokedAt = OffsetDateTime.parse("2026-10-10T09:00:00Z"))
        return true
    }
    override fun anyActiveToken() = tokenRows.any { it.revokedAt == null }
}

/** `engine.friction_note` in memory: what was inserted, listed newest first, and each run's root by a fixed map. */
internal class FakeFrictionLog : FrictionLog {
    val rows = mutableListOf<FrictionNote>()
    val roots = mutableMapOf("run-1" to "root-1")
    var insertFails: Exception? = null
    var asked: FrictionFilter? = null
    override fun insert(note: FrictionNote) { insertFails?.let { throw it }; rows += note }
    override fun list(filter: FrictionFilter): List<FrictionNote> {
        asked = filter
        return rows.filter {
            (filter.repo == null || it.repo == filter.repo) && (filter.issue == null || it.issue == filter.issue) &&
                (filter.kind == null || it.kind == filter.kind) && (filter.source == null || it.source == filter.source) &&
                (filter.since == null || !it.notedAt.isBefore(filter.since))
        }.sortedWith(compareByDescending<FrictionNote> { it.notedAt }.thenByDescending { it.id }).take(filter.limit)
    }
    override fun rootOf(runId: String) = roots[runId]
}

class FrakworksMcpTest {
    private val engine = FakeEngine()
    private val friction = FakeFrictionLog()
    private val mcp = FrakworksMcp(engine, SeededTiers.table(), friction)
    private val mate = McpCaller.Nobody("127.0.0.1", "someone")
    private val smith = McpCaller.Smith(SmithCaller("run-1", 7, "acme/widgets"))

    private val question = "0a1b2c3d-0000-0000-0000-000000000001"
    private val incident = "0a1b2c3d-0000-0000-0000-00000000000f"

    private fun need(number: Long?, kind: String, actionId: String, repo: String? = "acme/widgets", question: String? = null, forLogin: String? = null) =
        Need(repo, number, "title $number", kind, "wanted", OffsetDateTime.parse("2026-09-20T10:00:00Z"), actionId, question, forLogin)

    private fun call(tool: String, args: Map<String, Any?>, caller: McpCaller = mate) = mcp.call(tool, args, caller)

    private fun text(result: McpToolResult) = result.text
    private fun body(result: McpToolResult): Map<*, *> = mcpJson.readValue(text(result), Map::class.java)
    private fun error(result: McpToolResult): String {
        assertEquals(true, result.isError, text(result))
        return body(result)["error"] as String
    }

    @Test
    fun `First mate lists thirty-nine tools, a Smith or a caller Docker cannot place only its own twenty-one`() {
        // act_on_incident (#539) and read_doctor_history (#658) are frakworks-engine-diagnostics', not First mate's.
        assertEquals(listOf("submit_ask", "read_transcript", "read_needs", "read_ticket_pr", "approve", "reauthorize", "note_friction",
            "read_repos", "add_repo", "edit_repo", "remove_repo", "read_ci_runs", "cancel_intake", "amend_intake", "amend_ticket", "close_ticket", "comment_ticket", "read_ticket_runs", "search_tickets",
            "read_access", "add_access", "remove_access", "read_tokens", "mint_token", "revoke_token", "read_ticket", "list_private_docs", "read_private_doc",
            "read_repo_mcp_servers", "add_repo_mcp_server", "edit_repo_mcp_server", "remove_repo_mcp_server", "test_repo_mcp_server",
            "read_ci_jobs", "read_ci_job", "search_ci_log", "rerun_ci_jobs", "read_version", "screenshot_desktop"), mcp.tools(mate).map { it.name })
        for (name in listOf("approve", "reauthorize")) {
            val tool = mcp.tools(mate).single { it.name == name }
            assertTrue(tool.description.isNotBlank())
            val props = tool.inputSchema["properties"] as Map<*, *>
            assertEquals(setOf("ticket", "repo"), props.keys)
            assertEquals("integer", (props["ticket"] as Map<*, *>)["type"])
            assertEquals("string", (props["repo"] as Map<*, *>)["type"])
            assertEquals(listOf("ticket", "repo"), tool.inputSchema["required"])
        }
        assertEquals("unknown tool: \"read_doctor_history\"", error(call("read_doctor_history", emptyMap())))
        val smithTools = mcp.tools(smith)
        assertEquals(listOf("submit_ask", "note_friction", "read_ci_log", "read_run_log", "list_private_docs", "read_private_doc", "list_attachments", "read_attachment",
            "steer_child", "await_children", "file_children", "answer_child", "note_for_next_parent", "read_ticket_runs", "read_transcript", "read_ticket",
            "read_ci_jobs", "read_ci_job", "search_ci_log", "rerun_ci_jobs", "read_version"), smithTools.map { it.name })
        assertEquals(setOf("ask"), (smithTools.first().inputSchema["properties"] as Map<*, *>).keys)
        val note = smithTools[1].inputSchema
        assertEquals(listOf("kind", "summary", "evidence", "severity"), (note["properties"] as Map<*, *>).keys.toList())
        assertEquals(listOf("kind", "summary"), note["required"])
        assertEquals(false, note["additionalProperties"])
        assertEquals(listOf("bug", "design", "docs", "tooling", "other"), ((note["properties"] as Map<*, *>)["kind"] as Map<*, *>)["enum"])
        val unknown = McpCaller.Unknown("172.18.0.9", "docker is down")
        assertEquals(listOf("submit_ask", "note_friction", "read_ci_log", "read_run_log", "list_private_docs", "read_private_doc", "list_attachments", "read_attachment",
            "steer_child", "await_children", "file_children", "answer_child", "note_for_next_parent", "read_ticket_runs", "read_transcript", "read_ticket",
            "read_ci_jobs", "read_ci_job", "search_ci_log", "rerun_ci_jobs", "read_version"), mcp.tools(unknown).map { it.name })
        assertEquals("cannot tell whether the caller is a Smith sandbox: docker is down", error(call("submit_ask", mapOf("ask" to "x"), unknown)))
        assertTrue(engine.asks.isEmpty())
    }

    @Test
    fun `First mate's MCP server tools take a required repo, refused naming the target repos, and a Smith may not call them`() {
        for (name in listOf("read_repo_mcp_servers", "add_repo_mcp_server", "edit_repo_mcp_server", "remove_repo_mcp_server", "test_repo_mcp_server")) {
            val tool = mcp.tools(mate).single { it.name == name }
            assertTrue("repo" in (tool.inputSchema["required"] as List<*>), name)
            assertTrue("\"repo\" is required (owner/name): there is no default repo (the target repos: acme/widgets)" in error(call(name, mapOf("name" to "x"))), name)
            assertTrue("acme/elsewhere is not a target repo (the target repos: acme/widgets)" in error(call(name, mapOf("repo" to "acme/elsewhere", "name" to "x"))), name)
            assertTrue("may not call $name" in error(call(name, mapOf("repo" to "acme/widgets"), smith)), name)
        }
    }

    @Test
    fun `First mate's private-doc tools take a required repo, a Smith's take none`() {
        for (name in listOf("list_private_docs", "read_private_doc")) {
            val mine = mcp.tools(mate).single { it.name == name }.inputSchema
            assertTrue("repo" in (mine["required"] as List<*>), name)
            val smiths = mcp.tools(smith).single { it.name == name }.inputSchema
            assertTrue("repo" !in (smiths["properties"] as Map<*, *>).keys, name)
        }
    }

    // ---- note_friction (#876) ----

    @Test
    fun `First mate sees note_friction with a Smith's four arguments and an optional repo`() {
        val note = mcp.tools(mate).single { it.name == "note_friction" }.inputSchema
        val props = note["properties"] as Map<*, *>
        assertEquals(listOf("kind", "summary", "evidence", "severity", "repo"), props.keys.toList())
        assertEquals(listOf("kind", "summary"), note["required"])
        assertEquals(false, note["additionalProperties"])
        assertEquals((mcp.tools(smith).single { it.name == "note_friction" }.inputSchema["properties"] as Map<*, *>).let { s -> listOf("kind", "severity").map { (s[it] as Map<*, *>)["enum"] } },
            listOf("kind", "severity").map { (props[it] as Map<*, *>)["enum"] })
        assertTrue(error(call("note_friction", mapOf("kind" to "docs", "summary" to "x"), McpCaller.Unknown("172.18.0.9", "down"))).startsWith("cannot tell"))
        assertTrue(friction.rows.isEmpty())
    }

    @Test
    fun `First mate's note_friction inserts one first-mate row with its login and repo, and no ticket or run`() {
        val before = OffsetDateTime.now()
        val r = body(call("note_friction", mapOf("kind" to "tooling", "summary" to " no tool reads a workflow log ",
            "evidence" to "gh run view: not allowed", "severity" to "medium", "repo" to "ACME/Widgets")))
        val row = friction.rows.single()
        assertEquals(mapOf("ok" to true, "id" to row.id), r)
        // The repo as its target-repo row spells it.
        assertEquals(listOf("acme/widgets", null, null, null, "first-mate", "someone", "tooling", "no tool reads a workflow log", "gh run view: not allowed", "medium"),
            listOf(row.repo, row.issue, row.runId, row.processInstanceId, row.source, row.login, row.kind, row.summary, row.evidence, row.severity))
        assertTrue(!row.notedAt.isBefore(before))
        // No repo selected: noted with none.
        body(call("note_friction", mapOf("kind" to "docs", "summary" to "s")))
        assertEquals(listOf(null, "someone", "first-mate"), friction.rows.last().let { listOf(it.repo, it.login, it.source) })
        assertTrue(engine.asks.isEmpty() && engine.approvals.isEmpty() && engine.reauthorizations.isEmpty())
    }

    @Test
    fun `First mate's note_friction is refused, recording nothing, without a login, with a non-target repo or a bad argument`() {
        val args = mapOf("kind" to "docs", "summary" to "x")
        assertTrue(error(call("note_friction", args, McpCaller.Nobody("127.0.0.1"))).startsWith("no GitHub login to note as"))
        assertTrue(error(call("note_friction", args, McpCaller.Nobody("127.0.0.1", "not a login!"))).startsWith("no GitHub login to note as"))
        assertEquals("acme/elsewhere is not a target repo", error(call("note_friction", args + ("repo" to "acme/elsewhere"))))
        assertEquals("not an owner/name repo: \"nope\"", error(call("note_friction", args + ("repo" to "nope"))))
        assertEquals("note_friction takes only \"kind\", \"summary\", \"evidence\", \"severity\", \"repo\", not \"ticket\"",
            error(call("note_friction", args + ("ticket" to 5))))
        assertTrue(error(call("note_friction", mapOf("kind" to "rant", "summary" to "s"))).startsWith("kind is one of"))
        assertEquals("a note needs a summary", error(call("note_friction", mapOf("kind" to "docs", "summary" to "  "))))
        assertTrue(error(call("note_friction", args + ("severity" to "critical"))).startsWith("severity is one of"))
        assertTrue(friction.rows.isEmpty())
        friction.insertFails = IllegalStateException("db down")
        assertTrue(error(call("note_friction", args)).startsWith("the note was not recorded: db down"))
    }

    @Test
    fun `a Smith's note_friction inserts one as-you-go row stamped from its run, and returns its id`() {
        val before = OffsetDateTime.now()
        val r = body(call("note_friction", mapOf("kind" to "docs", "summary" to "  README names a gone flag  "), smith))
        val row = friction.rows.single()
        assertEquals(mapOf("ok" to true, "id" to row.id), r)
        assertEquals(listOf("acme/widgets", 7L, "run-1", "root-1", "as-you-go", "docs", "README names a gone flag", null, null, null),
            listOf(row.repo, row.issue, row.runId, row.processInstanceId, row.source, row.kind, row.summary, row.evidence, row.severity, row.login))
        assertTrue(!row.notedAt.isBefore(before))

        body(call("note_friction", mapOf("kind" to "tooling", "summary" to "s", "evidence" to "gradle: OOM", "severity" to "high"), smith))
        assertEquals("gradle: OOM" to "high", friction.rows.last().evidence to friction.rows.last().severity)
        // A run the engine's history does not know is still noted, with no root.
        mcp.call("note_friction", mapOf("kind" to "other", "summary" to "s"), McpCaller.Smith(SmithCaller("run-9", 9, "acme/other")))
        assertEquals(listOf("acme/other", 9L, "run-9", null), friction.rows.last().let { listOf(it.repo, it.issue, it.runId, it.processInstanceId) })
    }

    @Test
    fun `note_friction refuses an extra argument, a bad kind, a blank summary or a bad severity, and inserts nothing`() {
        // A Smith's note is stamped from its run: First mate's `repo` is not its.
        assertEquals("note_friction takes only \"kind\", \"summary\", \"evidence\", \"severity\", not \"repo\"",
            error(call("note_friction", mapOf("kind" to "docs", "summary" to "s", "repo" to "acme/other"), smith)))
        assertTrue(error(call("note_friction", mapOf("kind" to "rant", "summary" to "s"), smith)).startsWith("kind is one of"))
        assertTrue(error(call("note_friction", mapOf("summary" to "s"), smith)).startsWith("kind is one of"))
        assertEquals("a note needs a summary", error(call("note_friction", mapOf("kind" to "docs", "summary" to "  "), smith)))
        assertEquals("a note needs a summary", error(call("note_friction", mapOf("kind" to "docs"), smith)))
        assertTrue(error(call("note_friction", mapOf("kind" to "docs", "summary" to "s", "severity" to "critical"), smith)).startsWith("severity is one of"))
        assertTrue(error(call("note_friction", mapOf("kind" to "docs", "summary" to "x".repeat(2001)), smith)).contains("at most 2000"))
        assertTrue(friction.rows.isEmpty())
    }

    @Test
    fun `note_friction writes nothing to GitHub and puts nothing in front of the user`() {
        engine.needRows = listOf(need(5, "question", question))
        val needs = engine.needs()
        call("note_friction", mapOf("kind" to "bug", "summary" to "s"), smith)
        assertEquals(1, friction.rows.size)
        assertTrue(engine.asks.isEmpty() && engine.approvals.isEmpty() && engine.reauthorizations.isEmpty())
        assertEquals(needs, engine.needs())
    }

    @Test
    fun `a failed insert is a tool error, not a throw`() {
        friction.insertFails = IllegalStateException("db down")
        assertTrue(error(call("note_friction", mapOf("kind" to "bug", "summary" to "s"), smith)).startsWith("the note was not recorded: db down"))
    }

    @Test
    fun `a Smith's call to First mate's tools is refused`() {
        for (tool in listOf("read_needs", "read_ticket_pr", "approve", "reauthorize", "close_ticket", "comment_ticket"))
            assertEquals("unknown tool: \"$tool\"", error(call(tool, mapOf("ticket" to 5), smith)))
        // read_transcript and read_ticket_runs are a parent run's (#2528): refused naming the run for any other Smith
        for (tool in listOf("read_transcript", "read_ticket_runs"))
            assertTrue(error(call(tool, mapOf("ticket" to 5, "run" to "r"), smith)).startsWith("run run-1 is not a parent run"), tool)
        assertTrue(engine.writes.isEmpty())
        assertTrue(engine.approvals.isEmpty())
        assertTrue(engine.reauthorizations.isEmpty())
    }

    // ---- cancel_intake, amend_intake (#1173) ----

    @Test
    fun `cancel_intake and amend_intake are First mate's, each schema closed with its two arguments required`() {
        for ((name, second) in listOf("cancel_intake" to "reason", "amend_intake" to "text")) {
            val schema = mcp.tools(mate).single { it.name == name }.inputSchema
            assertEquals(false, schema["additionalProperties"], name)
            assertEquals(listOf("intake_id", second), schema["required"], name)
            assertEquals(listOf("intake_id", second), (schema["properties"] as Map<*, *>).keys.toList(), name)
        }
        for (caller in listOf(smith, McpCaller.Unknown("172.18.0.9", "down")))
            assertTrue(mcp.tools(caller).none { it.name in FrakworksMcp.INTAKE_TOOLS })
        for (tool in FrakworksMcp.INTAKE_TOOLS)
            assertEquals("a Smith sandbox (run run-1) may not call $tool: intakes are the user's to cancel or amend",
                error(call(tool, mapOf("intake_id" to question, "reason" to "x", "text" to "x"), smith)))
        assertTrue(engine.cancels.isEmpty() && engine.amends.isEmpty())
    }

    @Test
    fun `cancel_intake cancels as the login, and passes the endpoint's refusals on`() {
        val r = body(call("cancel_intake", mapOf("intake_id" to question, "reason" to " not needed ")))
        assertEquals(mapOf("ok" to true, "intakeId" to question, "failedWrites" to emptyList<String>()), r)
        assertEquals(listOf(Triple(question, "someone", "not needed")), engine.cancels)
        assertEquals("a cancel needs a reason", error(call("cancel_intake", mapOf("intake_id" to question, "reason" to " "))))
        assertEquals("not an intake id: \"x\"", error(call("cancel_intake", mapOf("intake_id" to "x", "reason" to "r"))))
        assertTrue(error(call("cancel_intake", mapOf("intake_id" to question, "reason" to "r"), McpCaller.Nobody("127.0.0.1"))).startsWith("no GitHub login to cancel as"))
        assertEquals("cancel_intake takes only \"intake_id\", \"reason\", not \"text\"",
            error(call("cancel_intake", mapOf("intake_id" to question, "reason" to "r", "text" to "x"))))
        engine.cancelFails = ResponseStatusException(HttpStatus.NOT_FOUND, "$question is not running")
        assertEquals("$question is not running", error(call("cancel_intake", mapOf("intake_id" to question, "reason" to "r"))))
        engine.cancelFails = ResponseStatusException(HttpStatus.FORBIDDEN, "someone may not cancel a intake on acme/widgets")
        assertEquals("someone may not cancel a intake on acme/widgets", error(call("cancel_intake", mapOf("intake_id" to question, "reason" to "r"))))
        assertEquals(1, engine.cancels.size)
    }

    @Test
    fun `amend_intake adds the follow-up as the login, says what the refine does, and passes the engine's refusals on`() {
        val r = body(call("amend_intake", mapOf("intake_id" to question, "text" to " and make it green ")))
        assertEquals(mapOf("ok" to true, "intakeId" to question, "refining" to "restarted"), r)
        assertEquals(listOf(Triple(question, "someone", "and make it green")), engine.amends)
        engine.refining = "waits on a question"
        assertEquals("waits on a question", body(call("amend_intake", mapOf("intake_id" to question, "text" to "x")))["refining"])
        assertEquals("a follow-up needs some text", error(call("amend_intake", mapOf("intake_id" to question, "text" to "  "))))
        assertEquals("a follow-up is at most ${FrakworksMcp.MAX_ANSWER_CHARS} characters",
            error(call("amend_intake", mapOf("intake_id" to question, "text" to "x".repeat(FrakworksMcp.MAX_ANSWER_CHARS + 1)))))
        assertTrue(error(call("amend_intake", mapOf("intake_id" to question, "text" to "x"), McpCaller.Nobody("127.0.0.1"))).startsWith("no GitHub login to amend as"))
        engine.amendFails = ResponseStatusException(HttpStatus.CONFLICT, "intake $question has already filed #12; use amend_ticket on #12")
        assertEquals("intake $question has already filed #12; use amend_ticket on #12", error(call("amend_intake", mapOf("intake_id" to question, "text" to "x"))))
        engine.amendFails = ResponseStatusException(HttpStatus.NOT_FOUND, "intake $question is not running")
        assertEquals("intake $question is not running", error(call("amend_intake", mapOf("intake_id" to question, "text" to "x"))))
        assertEquals(2, engine.amends.size)
    }

    // ---- amend_ticket (#1362) ----

    @Test
    fun `amend_ticket is First mate's, refused to a Smith beside amend_intake, with ticket, repo and text required`() {
        val schema = mcp.tools(mate).single { it.name == "amend_ticket" }.inputSchema
        assertEquals(false, schema["additionalProperties"])
        assertEquals(listOf("ticket", "repo", "text"), schema["required"])
        assertTrue(mcp.tools(smith).none { it.name == "amend_ticket" })
        assertEquals("a Smith sandbox (run run-1) may not call amend_ticket: tickets are the user's to amend",
            error(call("amend_ticket", mapOf("ticket" to 5, "repo" to "acme/widgets", "text" to "x"), smith)))
        assertEquals("a Smith sandbox (run run-1) may not call amend_intake: intakes are the user's to cancel or amend",
            error(call("amend_intake", mapOf("intake_id" to question, "text" to "x"), smith)))
        assertTrue(engine.ticketAmends.isEmpty())
    }

    @Test
    fun `amend_ticket amends as the login, answers what the engine did, and passes its refusals on`() {
        val r = body(call("amend_ticket", mapOf("ticket" to 5, "repo" to "acme/widgets", "text" to " and dark mode ")))
        assertEquals(mapOf("ok" to true, "repo" to "acme/widgets", "ticket" to 5, "work" to "restarted",
            "lost" to "the stopped work run's unpushed commits", "failedWrites" to emptyList<String>()), r)
        assertEquals(listOf(listOf<Any?>("acme/widgets", 5L, "someone", "and dark mode")), engine.ticketAmends)
        assertEquals("an amendment needs some text", error(call("amend_ticket", mapOf("ticket" to 5, "repo" to "acme/widgets", "text" to " "))))
        assertEquals("an amendment is at most ${FrakworksMcp.MAX_ANSWER_CHARS} characters",
            error(call("amend_ticket", mapOf("ticket" to 5, "repo" to "acme/widgets", "text" to "x".repeat(FrakworksMcp.MAX_ANSWER_CHARS + 1)))))
        engine.ticketAmendFails = ResponseStatusException(HttpStatus.CONFLICT, "ticket acme/widgets#5 is between steps; try again in a moment")
        assertEquals("ticket acme/widgets#5 is between steps; try again in a moment",
            error(call("amend_ticket", mapOf("ticket" to 5, "repo" to "acme/widgets", "text" to "x"))))
        assertEquals(1, engine.ticketAmends.size)
    }

    // ---- read_access, add_access, remove_access (#1636) ----

    @Test
    fun `read_access lists GET's logins and history, and takes no arguments`() {
        val r = body(call("read_access", emptyMap()))
        assertEquals(listOf("someone", "octocat"), r["logins"])
        assertEquals(listOf(mapOf("changedAt" to "2026-09-01T08:00:00Z", "change" to "grant", "login" to "octocat", "changedBy" to "someone")), r["history"])
        assertEquals("read_access takes no arguments, not \"login\"", error(call("read_access", mapOf("login" to "x"))))
    }

    @Test
    fun `add_access adds by the caller's login, or says it was already there`() {
        assertEquals(mapOf("ok" to true, "login" to "newbie", "outcome" to "added"), body(call("add_access", mapOf("login" to "newbie"))))
        assertEquals(Triple("grant", "newbie", "someone"), engine.accessCalls.single())
        assertEquals("already there", body(call("add_access", mapOf("login" to "NEWBIE")))["outcome"])
        engine.accessCalls.clear()
        assertEquals("add_access takes only \"login\", not \"by\"", error(call("add_access", mapOf("login" to "x", "by" to "y"))))
        assertTrue(error(call("add_access", mapOf("login" to "not a login"))).startsWith("login is a GitHub login"))
        assertTrue(error(call("add_access", emptyMap())).startsWith("login is a GitHub login"))
        assertTrue(error(call("add_access", mapOf("login" to "x"), McpCaller.Nobody("127.0.0.1"))).startsWith("a GitHub login is needed"))
        assertTrue(engine.accessCalls.isEmpty())
    }

    @Test
    fun `remove_access removes by the caller's login, and refuses the caller's own login, changing nothing`() {
        assertEquals(mapOf("ok" to true, "login" to "octocat", "outcome" to "removed"), body(call("remove_access", mapOf("login" to "octocat"))))
        assertEquals(Triple("revoke", "octocat", "someone"), engine.accessCalls.single())
        assertEquals("octocat is not a trusted login; nothing was changed", error(call("remove_access", mapOf("login" to "octocat"))))
        engine.accessCalls.clear()
        assertEquals("remove_access refuses your own login (someone): that would lock you out; nothing was changed",
            error(call("remove_access", mapOf("login" to "SomeOne"))))
        assertEquals("remove_access takes only \"login\", not \"repo\"", error(call("remove_access", mapOf("login" to "x", "repo" to "a/b"))))
        assertTrue(engine.accessCalls.isEmpty())
        assertEquals(listOf("someone"), engine.accessLogins)
    }

    @Test
    fun `a Smith's call to an access tool is refused naming its run, and the tools are not listed to it`() {
        for (tool in FrakworksMcp.ACCESS_TOOLS) {
            assertEquals("a Smith sandbox (run run-1) may not call $tool: the trusted logins are First mate's to manage",
                error(call(tool, mapOf("login" to "octocat"), smith)))
            assertTrue(mcp.tools(smith).none { it.name == tool })
            assertEquals(false, mcp.tools(mate).single { it.name == tool }.inputSchema["additionalProperties"])
        }
        assertTrue(engine.accessCalls.isEmpty())
    }

    // ---- read_repos, add_repo, edit_repo, remove_repo (#1021) ----

    @Test
    fun `the repo tools are First mate's, each schema closed with repo as owner-name, required but on read_repos`() {
        for (name in FrakworksMcp.REPO_TOOLS) {
            val schema = mcp.tools(mate).single { it.name == name }.inputSchema
            assertEquals(false, schema["additionalProperties"], name)
            val repo = (schema["properties"] as Map<*, *>)["repo"] as Map<*, *>
            assertEquals("string", repo["type"])
            assertTrue((repo["description"] as String).contains("owner/name"), name)
            assertEquals(if (name == "read_repos") null else listOf("repo"), schema["required"], name)
        }
        val props = { name: String -> (mcp.tools(mate).single { it.name == name }.inputSchema["properties"] as Map<*, *>).keys.toList() }
        assertEquals(listOf("repo", "ciCheck", "ciPaths", "securityReview", "scanIntervalMinutes"), props("add_repo"))
        assertEquals(listOf("repo", "ciCheck", "ciPaths", "securityReview", "scanIntervalMinutes", "doctorBugs", "engineDefects", "frictionBugs", "docsFirst", "childMode"), props("edit_repo"))
        assertEquals(listOf("repo"), props("remove_repo"))
        for (caller in listOf(smith, McpCaller.Unknown("172.18.0.9", "down")))
            assertTrue(mcp.tools(caller).none { it.name in FrakworksMcp.REPO_TOOLS })
    }

    @Test
    fun `a Smith's call to a repo tool is refused naming its run, and a caller Docker cannot place is refused too`() {
        val unknown = McpCaller.Unknown("172.18.0.9", "docker is down")
        for (tool in FrakworksMcp.REPO_TOOLS) {
            assertEquals("a Smith sandbox (run run-1) may not call $tool: target repos are First mate's to manage",
                error(call(tool, mapOf("repo" to "acme/widgets"), smith)))
            assertEquals("cannot tell whether the caller is a Smith sandbox: docker is down", error(call(tool, mapOf("repo" to "acme/widgets"), unknown)))
        }
        assertTrue(engine.repoCalls.isEmpty())
    }

    @Test
    fun `the repo writes need a login, read_repos does not`() {
        val nobody = McpCaller.Nobody("127.0.0.1")
        for ((tool, args) in listOf("add_repo" to mapOf("repo" to "acme/new"), "edit_repo" to mapOf("repo" to "acme/widgets", "ciCheck" to "build"),
                "remove_repo" to mapOf("repo" to "acme/widgets"))) {
            val why = error(call(tool, args, nobody))
            assertTrue(why.startsWith("a GitHub login is needed to ") && why.contains("Authorization: Bearer"), why)
            assertTrue(error(call(tool, args, McpCaller.Nobody("127.0.0.1", "not a login!"))).startsWith("a GitHub login is needed"))
        }
        assertTrue(engine.repoCalls.isEmpty())
        assertEquals(listOf("acme/widgets"), (body(call("read_repos", emptyMap(), nobody))["repos"] as List<*>).map { (it as Map<*, *>)["fullName"] })
    }

    @Test
    fun `read_repos lists GET's fields, one repo when given, and the token and webhook secret only as set`() {
        engine.repoRows[0] = engine.repoRows[0].copy(hasToken = true, tokenSetAt = OffsetDateTime.parse("2026-09-02T08:00:00Z"), hasWebhookSecret = true)
        engine.repoRows += TargetRepo("acme/other", OffsetDateTime.parse("2026-09-03T08:00:00Z"))
        val all = body(call("read_repos", emptyMap()))
        assertEquals(2, all["count"])
        val one = (body(call("read_repos", mapOf("repo" to "ACME/Widgets")))["repos"] as List<*>).single() as Map<*, *>
        assertEquals(listOf("fullName", "addedAt", "ciCheck", "ciPaths", "securityReview", "scanIntervalMinutes", "doctorBugs", "engineDefects",
            "frictionBugs", "docsFirst", "childMode", "publishWorkItems", "hasToken", "tokenPermission", "callerPermission", "tokenSetAt", "hasWebhookSecret", "webhookSecretSetAt", "hasSmithToken", "smithTokenSetAt", "scanCalls", "scanCallsPerScan"), one.keys.toList())
        assertEquals(listOf("acme/widgets", true, "2026-09-02T08:00:00Z", true, null),
            listOf(one["fullName"], one["hasToken"], one["tokenSetAt"], one["hasWebhookSecret"], one["webhookSecretSetAt"]))
        assertEquals("acme/nope is not a target repo", error(call("read_repos", mapOf("repo" to "acme/nope"))))
        assertEquals("repo is owner/name", error(call("read_repos", mapOf("repo" to "nope"))))
        assertEquals("read_repos takes only \"repo\", not \"ticket\"", error(call("read_repos", mapOf("ticket" to 5))))
    }

    @Test
    fun `add_repo says added or already there, and refuses what POST does not take, a token and a webhook secret among them`() {
        assertEquals(mapOf("ok" to true, "repo" to "acme/new", "outcome" to "added"),
            body(call("add_repo", mapOf("repo" to "acme/new", "ciCheck" to "build", "ciPaths" to listOf("src/"), "securityReview" to false, "scanIntervalMinutes" to 30))))
        assertEquals(AddRepo("acme/new", "build", listOf("src/"), false, 30), engine.repoCalls.single())
        assertEquals(mapOf("ok" to true, "repo" to "acme/new", "outcome" to "already there", "note" to "acme/new was already a target repo; nothing was changed"),
            body(call("add_repo", mapOf("repo" to "acme/new"))))
        assertEquals(AddRepo("acme/new"), engine.repoCalls.last(), "absent keys are POST's defaults")
        engine.repoCalls.clear()
        val only = "\"repo\", \"ciCheck\", \"ciPaths\", \"securityReview\", \"scanIntervalMinutes\""
        for (key in listOf("token", "webhookSecret", "doctorBugs", "engineDefects", "frictionBugs"))
            assertEquals("add_repo takes only $only, not \"$key\"", error(call("add_repo", mapOf("repo" to "acme/x", key to "v"))))
        assertEquals("\"repo\" is required (owner/name): the target repo this is about; there is no default repo", error(call("add_repo", emptyMap())))
        assertEquals("repo is owner/name", error(call("add_repo", mapOf("repo" to 5))))
        assertEquals("ciCheck is a string or null", error(call("add_repo", mapOf("repo" to "acme/x", "ciCheck" to 5))))
        assertEquals("ciPaths is an array of strings or null", error(call("add_repo", mapOf("repo" to "acme/x", "ciPaths" to "src/"))))
        assertEquals("securityReview is true or false", error(call("add_repo", mapOf("repo" to "acme/x", "securityReview" to null))))
        for (bad in listOf(0, 1441, 2.5, 2.0, "5", null))
            assertEquals("scanIntervalMinutes is a whole number of minutes from 1 to 1440 (24 hours)",
                error(call("add_repo", mapOf("repo" to "acme/x", "scanIntervalMinutes" to bad))))
        assertTrue(engine.repoCalls.isEmpty(), "none of these reached the engine")
    }

    @Test
    fun `edit_repo hands every key but repo to PATCH, in the row's spelling, and returns the row as read_repos shows it`() {
        val r = body(call("edit_repo", mapOf("repo" to "ACME/widgets", "ciCheck" to null, "doctorBugs" to true)))
        assertEquals("acme/widgets", r["fullName"])
        assertEquals("acme/widgets" to mapOf("ciCheck" to null, "doctorBugs" to true), engine.repoCalls.single())
        assertEquals("edit_repo needs at least one of \"ciCheck\", \"ciPaths\", \"securityReview\", \"scanIntervalMinutes\", \"doctorBugs\", " +
            "\"engineDefects\", \"frictionBugs\", \"docsFirst\", \"childMode\" besides \"repo\"", error(call("edit_repo", mapOf("repo" to "acme/widgets"))))
        // Not a target repo: the name goes to PATCH as given, and its 404 is the error.
        assertEquals("acme/nope is not a target repo", error(call("edit_repo", mapOf("repo" to "acme/nope", "ciCheck" to "x"))))
        assertEquals("acme/nope" to mapOf("ciCheck" to "x"), engine.repoCalls.last())
    }

    @Test
    fun `remove_repo removes the row's spelling, and the API's refusals are the tool's errors`() {
        engine.repoFails = ResponseStatusException(HttpStatus.CONFLICT, "acme/widgets has running flows: smith-run #42; cancel or finish them first")
        assertEquals("acme/widgets has running flows: smith-run #42; cancel or finish them first", error(call("remove_repo", mapOf("repo" to "acme/widgets"))))
        engine.repoFails = ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown field(s): token; only ciCheck, ciPaths, securityReview, scanIntervalMinutes, doctorBugs and engineDefects")
        assertTrue(error(call("edit_repo", mapOf("repo" to "acme/widgets", "token" to "t0k"))).startsWith("unknown field(s): token"))
        engine.repoFails = null
        assertEquals(mapOf("ok" to true, "repo" to "acme/widgets", "outcome" to "removed"), body(call("remove_repo", mapOf("repo" to "Acme/Widgets"))))
        assertEquals(listOf<Any>("acme/widgets"), engine.repoCalls)
        assertEquals("acme/widgets is not a target repo", error(call("remove_repo", mapOf("repo" to "acme/widgets"))))
        assertEquals("remove_repo takes only \"repo\", not \"force\"", error(call("remove_repo", mapOf("repo" to "acme/widgets", "force" to true))))
    }

    @Test
    fun `reauthorize resumes or starts a ticket as the login, in the repo given`() {
        engine.reauthorizable["acme/widgets" to 724L] = IssueApprovals.Reauthorized.Done("resumed", "parent-724", emptyList())
        assertEquals(mapOf("ok" to true, "ticket" to 724, "repo" to "acme/widgets", "instance" to "parent-724", "authorized_by" to "someone",
            "outcome" to "resumed"), body(call("reauthorize", mapOf("ticket" to 724, "repo" to "acme/widgets"))))
        engine.reauthorizable["acme/other" to 9L] = IssueApprovals.Reauthorized.Done("started", "instance-9", listOf("the comment was not posted: 502"))
        assertEquals(mapOf("ok" to true, "ticket" to 9, "repo" to "acme/other", "instance" to "instance-9", "authorized_by" to "someone",
            "outcome" to "started", "notes" to listOf("the comment was not posted: 502")), body(call("reauthorize", mapOf("ticket" to 9, "repo" to "ACME/other"))))
        assertEquals(listOf(Triple("acme/widgets", 724L, "someone"), Triple("acme/other", 9L, "someone")), engine.reauthorizations)
    }

    @Test
    fun `reauthorize says why it did nothing, and is refused without a login, a target repo, or with other arguments`() {
        engine.reauthorizable["acme/widgets" to 724L] = IssueApprovals.Reauthorized.Refused("@someone may not authorize a feature in acme/widgets")
        assertEquals("@someone may not authorize a feature in acme/widgets; nothing was changed", error(call("reauthorize", mapOf("ticket" to 724, "repo" to "acme/widgets"))))
        assertEquals("acme/widgets#725 is not waiting on a blocker (no deferred approval on it): nothing to re-authorize; nothing was changed",
            error(call("reauthorize", mapOf("ticket" to 725, "repo" to "acme/widgets"))))
        assertEquals(2, engine.reauthorizations.size)
        assertTrue(error(call("reauthorize", mapOf("ticket" to 724, "repo" to "acme/widgets"), McpCaller.Nobody("127.0.0.1"))).startsWith("no GitHub login to reauthorize as"))
        assertEquals("acme/secret is not a target repo", error(call("reauthorize", mapOf("ticket" to 724, "repo" to "acme/secret"))))
        assertEquals("reauthorize takes only \"ticket\", \"repo\", not \"answer\"", error(call("reauthorize", mapOf("ticket" to 724, "answer" to "x", "repo" to "acme/widgets"))))
        assertEquals("not a ticket number: \"x\" (a positive integer)", error(call("reauthorize", mapOf("ticket" to "x", "repo" to "acme/widgets"))))
        assertEquals(2, engine.reauthorizations.size, "none of these reached the engine")
    }

    @Test
    fun `approve authorizes a waiting intake as the login`() {
        engine.trustedLogins += "someone"
        engine.waiting += "acme/widgets" to 780L
        val r = call("approve", mapOf("ticket" to 780, "repo" to "acme/widgets"))
        assertEquals(mapOf("ok" to true, "ticket" to 780, "repo" to "acme/widgets", "intake_id" to "intake-780", "authorized_by" to "someone"), body(r))
        assertEquals(listOf(Triple("acme/widgets", 780L, "someone")), engine.approvals)
        // Nothing waits any more: a second approve changes nothing.
        assertEquals("no intake on acme/widgets#780 waits for an authorization (already authorized, refined or closed, or not an intake's ask); nothing was changed",
            error(call("approve", mapOf("ticket" to 780, "repo" to "acme/widgets"))))
        assertEquals(1, engine.approvals.size)
    }

    @Test
    fun `approve is refused without intake access, a login, a target repo, or with other arguments`() {
        engine.waiting += "acme/widgets" to 780L
        assertEquals("@someone is not a trusted login", error(call("approve", mapOf("ticket" to 780, "repo" to "acme/widgets"))))
        assertTrue(error(call("approve", mapOf("ticket" to 780, "repo" to "acme/widgets"), McpCaller.Nobody("127.0.0.1"))).startsWith("no GitHub login to approve as"))
        engine.trustedLogins += "someone"
        assertEquals("acme/secret is not a target repo", error(call("approve", mapOf("ticket" to 780, "repo" to "acme/secret"))))
        assertEquals("approve takes only \"ticket\", \"repo\", not \"answer\"", error(call("approve", mapOf("ticket" to 780, "answer" to "x", "repo" to "acme/widgets"))))
        assertEquals("not a ticket number: \"x\" (a positive integer)", error(call("approve", mapOf("ticket" to "x", "repo" to "acme/widgets"))))
        assertTrue(engine.approvals.isEmpty())
        assertEquals(setOf("acme/widgets" to 780L), engine.waiting, "nothing was authorized")
    }

    private val close = mapOf("ticket" to 1027, "repo" to "acme/widgets", "reason" to "no longer wanted", "outcome" to "not_planned")

    @Test
    fun `close_ticket's schema takes ticket, repo, reason and outcome, all required`() {
        val schema = mcp.tools(mate).single { it.name == "close_ticket" }.inputSchema
        val props = schema["properties"] as Map<*, *>
        assertEquals(setOf("ticket", "repo", "reason", "outcome"), props.keys)
        assertEquals(1, (props["ticket"] as Map<*, *>)["minimum"])
        assertEquals("string", (props["outcome"] as Map<*, *>)["type"])
        assertEquals(listOf("completed", "not_planned"), (props["outcome"] as Map<*, *>)["enum"])
        assertEquals(listOf("ticket", "repo", "reason", "outcome"), schema["required"])
        assertTrue(mcp.tools(smith).none { it.name == "close_ticket" })
    }

    @Test
    fun `close_ticket refuses and writes nothing`() {
        engine.trustedLogins += "someone"
        assertTrue(error(call("close_ticket", close, McpCaller.Nobody("127.0.0.1"))).startsWith("no GitHub login to close as"))
        assertTrue(error(call("close_ticket", close, McpCaller.Nobody("127.0.0.1", " "))).startsWith("no GitHub login to close as"))
        assertEquals("not a ticket number: null (a positive integer)", error(call("close_ticket", close - "ticket")))
        assertEquals("not a ticket number: \"x\" (a positive integer)", error(call("close_ticket", close + ("ticket" to "x"))))
        assertEquals("\"repo\" is required (owner/name): the target repo this is about; there is no default repo", error(call("close_ticket", close - "repo")))
        assertEquals("not an owner/name repo: 5", error(call("close_ticket", close + ("repo" to 5))))
        for (bad in listOf(close - "reason", close + ("reason" to "  "), close + ("reason" to 3)))
            assertTrue(error(call("close_ticket", bad)).startsWith("\"reason\" is required"))
        for (bad in listOf(close - "outcome", close + ("outcome" to " "), close + ("outcome" to "duplicate"), close + ("outcome" to "Completed"), close + ("outcome" to 1)))
            error(call("close_ticket", bad)).let { assertTrue(it.startsWith("\"outcome\" is required: \"completed\" or \"not_planned\""), it) }
        assertEquals("close_ticket takes only \"ticket\", \"repo\", \"reason\", \"outcome\", not \"x\"", error(call("close_ticket", close + ("x" to 1))))
        assertEquals("acme/secret is not a target repo", error(call("close_ticket", close + ("repo" to "acme/secret"))))
        assertTrue(engine.writes.isEmpty())
    }

    @Test
    fun `the engine's own refusal comes back as the tool's error`() {
        engine.cancelFails = ResponseStatusException(HttpStatus.FORBIDDEN, "someone may not cancel in acme/widgets")
        assertEquals("someone may not cancel in acme/widgets", error(call("cancel_intake", mapOf("intake_id" to question, "reason" to "x"))))
    }

    @Test
    fun `submit_ask files as the login, with the prescribed tier's line only when a tier is given`() {
        val r = call("submit_ask", mapOf("ask" to "  do the thing ", "tier" to 5, "authorize" to true, "repo" to "acme/widgets"))
        assertEquals(mapOf("intakeId" to "intake-1", "status" to 202, "tier" to 5, "ask" to "do the thing", "repo" to "acme/widgets",
            "ticket" to 100001, "ticketUrl" to "https://github.com/acme/widgets/issues/100001"), body(r))
        val filed = engine.asks.single()
        assertEquals("Tier: 5 (opus medium) — prescribed by the user; do not re-judge\n\ndo the thing", filed.ask)
        assertEquals("someone", filed.submittedBy)
        assertEquals(true, filed.authorize)
        assertEquals("acme/widgets", filed.repo)

        call("submit_ask", mapOf("ask" to "plain", "repo" to "acme/widgets"))
        assertEquals("plain", engine.asks.last().ask)
        assertNull(engine.asks.last().authorize)
        assertNull(engine.asks.last().context, "no context given, none passed")
        call("submit_ask", mapOf("ask" to "with context", "repo" to "acme/widgets", "context" to "we had just read the intake skill"))
        assertEquals("we had just read the intake skill", engine.asks.last().context)

        assertEquals("tier 11 is not a tier of the engine's map (1-10)", error(call("submit_ask", mapOf("ask" to "x", "tier" to 11))))
        assertEquals("not an owner/name repo: \"../x\"", error(call("submit_ask", mapOf("ask" to "x", "repo" to "../x"))))
        assertEquals("an ask needs some text", error(call("submit_ask", mapOf("ask" to "  "))))
        val noRepo = "\"repo\" is required (owner/name): the target repo this is about; there is no default repo"
        assertEquals(noRepo, error(call("submit_ask", mapOf("ask" to "x"))))
        for (tool in listOf("approve", "reauthorize", "read_ticket_pr")) assertEquals(noRepo, error(call(tool, mapOf("ticket" to 5))))
        assertEquals(3, engine.asks.size)
    }

    @Test
    fun `a Smith's submit_ask takes the ask alone and files unauthorized, as no one`() {
        assertEquals("a Smith's submit_ask takes only \"ask\", not \"tier\"", error(call("submit_ask", mapOf("ask" to "x", "tier" to 3), smith)))
        val r = call("submit_ask", mapOf("ask" to " found work "), smith)
        assertEquals(mapOf("intakeId" to "intake-1", "status" to 202, "ask" to "found work", "repo" to "acme/widgets",
            "ticket" to 100001, "ticketUrl" to "https://github.com/acme/widgets/issues/100001"), body(r))
        val filed = engine.asks.single()
        assertEquals(AskRequest("found work", authorize = false), filed)
    }

    @Test
    fun `read_needs keeps the rows of the ticket and repo asked for`() {
        engine.needRows = listOf(need(5, "question", question, forLogin = "alice"), need(5, "incident", incident, repo = "acme/other"), need(6, "authorize", "u"))
        val all = body(call("read_needs", emptyMap()))
        assertEquals(3, all["count"])
        val rows = body(call("read_needs", mapOf("ticket" to 5, "repo" to "acme/widgets")))["rows"] as List<*>
        assertEquals(listOf(mapOf("repo" to "acme/widgets", "number" to 5, "title" to "title 5", "kind" to "question", "wanted" to "wanted",
            "since" to "2026-09-20T10:00:00Z", "actionId" to question, "question" to null, "for" to "alice")), rows, "a row says whom it is for")
        assertEquals(null, ((body(call("read_needs", mapOf("ticket" to 6)))["rows"] as List<*>).single() as Map<*, *>)["for"], "an authorize row is everyone's")
        assertEquals("read_needs takes only \"ticket\", \"repo\", not \"kind\"", error(call("read_needs", mapOf("kind" to "x"))))
        assertEquals("not a ticket number: 0 (a positive integer)", error(call("read_needs", mapOf("ticket" to 0))))
    }

    @Test
    fun `read_needs serves a recorded question whole, and null on a row without one`() {
        val full = "Before I go on, the 9 checks:\n\n" + (1..9).joinToString("\n") { "$it. Keep check number $it as it is today?" } +
            "\n\n```ask\n" + (1..9).joinToString("\n") { "- $it: keep | drop" } + "\n```"
        assertTrue(full.length > 200)
        engine.needRows = listOf(need(5, "question", question, question = full), need(6, "incident", incident))
        val rows = body(call("read_needs", emptyMap()))["rows"] as List<*>
        assertEquals(full, (rows[0] as Map<*, *>)["question"])
        assertTrue((rows[1] as Map<*, *>).containsKey("question"))
        assertNull((rows[1] as Map<*, *>)["question"])
    }

    @Test
    fun `read_transcript says when there is no store, no session, or renders the newest events`() {
        assertEquals("read_transcript takes only \"ticket\", \"run\", not \"host\"", error(call("read_transcript", mapOf("ticket" to 5, "host" to "x"))))
        assertTrue(text(call("read_transcript", mapOf("ticket" to 5))).contains("transcript store is not configured"))

        engine.transcriptDoc = Transcript(5, live = true, liveKnown = true)
        assertEquals("No Smith transcript found for #5 yet, though a Smith is running on it now.", text(call("read_transcript", mapOf("ticket" to 5))))

        engine.transcriptDoc = Transcript(5, liveKnown = true, host = "engine", model = "claude-opus-5-5", session = "issue-5-work-r.log",
            events = listOf(TranscriptEvent(null, listOf("a")), TranscriptEvent(null, listOf("b", "c"))))
        assertEquals("Ticket #5: run ended (no Smith is running now).\nhost engine · model claude-opus-5-5 · session issue-5-work-r.log\n2 events.\n\na\n\nb\n\nc",
            text(call("read_transcript", mapOf("ticket" to 5))))
    }

    @Test
    fun `read_transcript with a run reads that run's session, and names a run the ticket has not got`() {
        val started = Instant.parse("2026-10-03T10:00:00Z")
        engine.smithRuns["run-a"] = SmithRunRef("run-a", "acme/widgets", 5, NamedRun("run-a", "review", started))
        engine.smithRuns["run-b"] = SmithRunRef("run-b", "acme/widgets", 6, NamedRun("run-b", "work", started))
        assertEquals("Run run-b is not one of #5's Smith runs (read_ticket_runs lists them).", text(call("read_transcript", mapOf("ticket" to 5, "run" to "run-b"))))
        assertEquals("Run nope is not one of #5's Smith runs (read_ticket_runs lists them).", text(call("read_transcript", mapOf("ticket" to 5, "run" to "nope"))))
        assertEquals("not a run id: 7", error(call("read_transcript", mapOf("ticket" to 5, "run" to 7))))
        assertTrue(engine.namedReads.isEmpty())

        engine.transcriptDoc = Transcript(5, liveKnown = true, kind = "review")
        assertEquals("No transcript of run run-a on #5 was shipped.", text(call("read_transcript", mapOf("ticket" to 5, "run" to "run-a"))))
        assertEquals(listOf("run-a"), engine.namedReads.map { it.runId })

        engine.transcriptDoc = Transcript(5, liveKnown = true, kind = "review", session = "issue-5-review-run-a.log",
            events = listOf(TranscriptEvent(null, listOf("a"))))
        assertEquals("Ticket #5: run ended (no Smith is running now).\nkind review · session issue-5-review-run-a.log\n1 events.\n\na",
            text(call("read_transcript", mapOf("ticket" to 5, "run" to "run-a"))))
        // Without `run`, the newest session as before, and no named read.
        call("read_transcript", mapOf("ticket" to 5))
        assertEquals(2, engine.namedReads.size)
    }

    private fun step(role: String, round: Int, start: Long, end: Long?, outcome: String?, cost: Double? = null) =
        TimelineStep(role, round, start, end, ((end ?: start) - start) * 1000, "claude-opus-5-5", "high", cost, 10, outcome, end == null)

    private fun stat(runId: String, mode: String, started: String, ended: String) = SmithRunStat(runId, "proposal", "acme/widgets", 9, 5,
        "claude-opus-5-5", "high", "acct-1", OffsetDateTime.parse(started), OffsetDateTime.parse(ended), 0, null, null, mode)

    @Test
    fun `read_ticket_runs refuses bad arguments, a missing repo and one that is no target repo`() {
        assertEquals("\"repo\" is required (owner/name): the target repo this is about; there is no default repo", error(call("read_ticket_runs", mapOf("ticket" to 9))))
        assertEquals("read_ticket_runs takes only \"ticket\", \"repo\", not \"run\"", error(call("read_ticket_runs", mapOf("ticket" to 9, "repo" to "acme/widgets", "run" to "x"))))
        assertEquals("not a ticket number: 0 (a positive integer)", error(call("read_ticket_runs", mapOf("ticket" to 0, "repo" to "acme/widgets"))))
        assertEquals("acme/nope is not a target repo", error(call("read_ticket_runs", mapOf("ticket" to 9, "repo" to "acme/nope"))))
        assertEquals("No process instance is tied to acme/widgets#9: the engine has run nothing on it.",
            text(call("read_ticket_runs", mapOf("ticket" to 9, "repo" to "acme/widgets"))))
    }

    @Test
    fun `read_ticket_runs lists the runs oldest first, a review's findings between propose rows`() {
        val f1 = Finding("Say why", file = "docs/p.md", id = "review-1-1", review = "correctness")
        val f2 = Finding("Add a test", id = "review-1-2", review = "correctness")
        engine.ticketRuns["acme/widgets" to 9L] = listOf(
            TicketRunRow(TicketRun("p1", "propose", 1, step("propose", 1, 1_791_000_000, 1_791_000_600, "done", 1.5), null),
                stat("p1", "propose", "2026-10-03T10:00:00Z", "2026-10-03T10:10:00Z")),
            TicketRunRow(TicketRun("r1", "review", 1, step("correctness", 1, 1_791_001_000, 1_791_001_300, "changes", 0.5), listOf(f1, f2)),
                stat("r1", "review", "2026-10-03T10:16:40Z", "2026-10-03T10:21:40Z")),
            TicketRunRow(TicketRun("p2", "propose", 2, step("propose", 2, 1_791_002_000, 1_791_002_600, "done"), null),
                stat("p2", "propose", "2026-10-03T10:33:20Z", "2026-10-03T10:43:20Z")),
            TicketRunRow(TicketRun("r2", "review", 2, step("correctness", 2, 1_791_003_000, 1_791_003_100, "approved"), emptyList()),
                stat("r2", "review", "2026-10-03T10:50:00Z", "2026-10-03T10:51:40Z")),
            TicketRunRow(TicketRun("p3", "propose", 3, step("propose", 3, 1_791_004_000, null, "in progress"), null), null),
        )
        val out = body(call("read_ticket_runs", mapOf("ticket" to 9, "repo" to "ACME/widgets")))
        assertEquals("acme/widgets", out["repo"])
        val runs = out["runs"] as List<*>
        assertEquals(listOf("p1", "r1", "p2", "r2", "p3"), runs.map { (it as Map<*, *>)["runId"] })
        assertEquals(listOf(1, 1, 2, 2, 3), runs.map { ((it as Map<*, *>)["round"] as Number).toInt() })
        val r1 = runs[1] as Map<*, *>
        assertEquals("review", r1["mode"]); assertEquals("acct-1", r1["account"]); assertEquals(5, (r1["tier"] as Number).toInt())
        assertEquals("2026-10-03T10:16:40Z", r1["started"]); assertEquals("2026-10-03T10:21:40Z", r1["ended"])
        assertEquals(0, (r1["exitCode"] as Number).toInt()); assertEquals("changes", r1["outcome"]); assertEquals(0.5, r1["cost"])
        assertEquals(listOf(
            mapOf("id" to "review-1-1", "review" to "correctness", "file" to "docs/p.md", "ask" to "Say why"),
            mapOf("id" to "review-1-2", "review" to "correctness", "ask" to "Add a test"),
        ), r1["findings"])
        assertEquals(emptyList<Any>(), (runs[3] as Map<*, *>)["findings"])
        assertTrue("findings" !in (runs[0] as Map<*, *>))
        val running = runs[4] as Map<*, *>
        assertNull(running["ended"]); assertNull(running["exitCode"]); assertNull(running["account"]); assertNull(running["cost"])
        assertEquals(Instant.ofEpochSecond(1_791_004_000).toString(), running["started"])
        assertEquals("in progress", running["outcome"])
    }

    @Test
    fun `boundEvents keeps the newest that fit, and always the newest one`() {
        val events = listOf("aaaa", "bbbb", "cccc").map { TranscriptEvent(null, listOf(it)) }
        assertEquals(listOf("bbbb", "cccc") to 1, TranscriptTool.boundEvents(events, budget = 12))
        assertEquals(listOf("cc …") to 2, TranscriptTool.boundEvents(events, budget = 2))
        val doc = Transcript(5, session = "s", events = events, error = "live-run lookup failed: down")
        assertEquals("Ticket #5: run unknown (the live-run lookup failed).\nsession s\nNote: live-run lookup failed: down\n" +
            "Showing the newest 2 of 3 events: 1 earlier events left out.\n\nbbbb\n\ncccc", TranscriptTool.renderTranscript(doc, budget = 12))
    }

    private fun withPr() {
        engine.pulls["acme/widgets" to 731L] = TicketPulls("main", listOf(PullFiles(740, "https://github.com/acme/widgets/pull/740", "OPEN",
            "smith/issue-731", linkedMapOf("docs/proposals/x.md" to "ADDED", "README.md" to "MODIFIED"))))
    }

    @Test
    fun `read_ticket_pr lists the ticket's PR and its files, in the repo given`() {
        withPr()
        assertEquals(mapOf("repo" to "acme/widgets", "ticket" to 731, "pulls" to listOf(mapOf("number" to 740,
            "url" to "https://github.com/acme/widgets/pull/740", "state" to "OPEN", "headRefName" to "smith/issue-731",
            "files" to listOf(mapOf("path" to "docs/proposals/x.md", "changeType" to "ADDED"), mapOf("path" to "README.md", "changeType" to "MODIFIED")),
            "mergeable" to null, "mergeStateStatus" to null, "checks" to emptyList<Any>()))),
            body(call("read_ticket_pr", mapOf("ticket" to 731, "repo" to "acme/widgets"))))
        assertEquals(body(call("read_ticket_pr", mapOf("ticket" to 731, "repo" to "acme/widgets"))), body(call("read_ticket_pr", mapOf("ticket" to 731, "repo" to "acme/widgets", "diff" to false))))
        assertEquals("read_ticket_pr takes only \"ticket\", \"repo\", \"path\", \"diff\", \"offset\", not \"pr\"",
            error(call("read_ticket_pr", mapOf("ticket" to 731, "pr" to 740, "repo" to "acme/widgets"))))
        assertEquals("read_ticket_pr takes \"path\" or \"diff\", not both",
            error(call("read_ticket_pr", mapOf("ticket" to 731, "path" to "README.md", "diff" to true, "repo" to "acme/widgets"))))
    }

    @Test
    fun `read_ticket_pr reports the PR's checks, running and passed, and its merge state`() {
        engine.pulls["acme/widgets" to 731L] = TicketPulls("main", listOf(PullFiles(740, "https://github.com/acme/widgets/pull/740", "OPEN",
            "smith/issue-731", mapOf("README.md" to "MODIFIED"), listOf(
                PullCheck("engine", "in_progress", null, "2026-10-07T10:00:00Z", null, "https://github.com/acme/widgets/actions/runs/1"),
                PullCheck("lint", "completed", "success", "2026-10-07T09:58:00Z", "2026-10-07T09:59:00Z", "https://github.com/acme/widgets/actions/runs/2")),
            "MERGEABLE", "BLOCKED")))
        val pull = (body(call("read_ticket_pr", mapOf("ticket" to 731, "repo" to "acme/widgets")))["pulls"] as List<*>).single() as Map<*, *>
        assertEquals("MERGEABLE", pull["mergeable"])
        assertEquals("BLOCKED", pull["mergeStateStatus"])
        assertEquals(listOf(
            mapOf("name" to "engine", "status" to "in_progress", "conclusion" to null, "startedAt" to "2026-10-07T10:00:00Z",
                "completedAt" to null, "url" to "https://github.com/acme/widgets/actions/runs/1"),
            mapOf("name" to "lint", "status" to "completed", "conclusion" to "success", "startedAt" to "2026-10-07T09:58:00Z",
                "completedAt" to "2026-10-07T09:59:00Z", "url" to "https://github.com/acme/widgets/actions/runs/2")), pull["checks"])
    }

    @Test
    fun `read_ticket_pr returns a changed file's text at the PR's head branch`() {
        withPr()
        engine.files[Triple("acme/widgets", "docs/proposals/x.md", "smith/issue-731")] = "# The proposal"
        assertEquals("docs/proposals/x.md at smith/issue-731 (PR #740, OPEN, https://github.com/acme/widgets/pull/740):\n\n# The proposal",
            text(call("read_ticket_pr", mapOf("ticket" to 731, "path" to "docs/proposals/x.md", "repo" to "acme/widgets"))))
    }

    @Test
    fun `read_ticket_pr returns the PR's diff`() {
        withPr()
        engine.diffs["acme/widgets" to 740L] = "diff --git a/README.md b/README.md"
        assertEquals("PR #740 (OPEN, smith/issue-731, https://github.com/acme/widgets/pull/740) diff:\n\ndiff --git a/README.md b/README.md",
            text(call("read_ticket_pr", mapOf("ticket" to 731, "diff" to true, "repo" to "acme/widgets"))))
    }

    @Test
    fun `read_ticket_pr returns a text under the budget whole, with no cut line`() {
        withPr()
        val doc = "y".repeat(FrakworksMcp.PR_CHAR_BUDGET)
        engine.files[Triple("acme/widgets", "docs/proposals/x.md", "smith/issue-731")] = doc
        assertEquals("docs/proposals/x.md at smith/issue-731 (PR #740, OPEN, https://github.com/acme/widgets/pull/740):\n\n$doc",
            text(call("read_ticket_pr", mapOf("ticket" to 731, "path" to "docs/proposals/x.md", "repo" to "acme/widgets"))))
    }

    @Test
    fun `read_ticket_pr pages a file's text over the budget with offset`() {
        withPr()
        val b = FrakworksMcp.PR_CHAR_BUDGET
        val doc = (0 until b + 9412).joinToString("") { ('a' + it % 26).toString() }
        engine.files[Triple("acme/widgets", "docs/proposals/x.md", "smith/issue-731")] = doc
        val head = "docs/proposals/x.md at smith/issue-731 (PR #740, OPEN, https://github.com/acme/widgets/pull/740):\n\n"
        val first = text(call("read_ticket_pr", mapOf("ticket" to 731, "path" to "docs/proposals/x.md", "repo" to "acme/widgets")))
        val cut = "\n… 9,412 more characters; pass offset: $b for the next page."
        assertEquals(head + doc.take(b) + cut, first)
        val second = text(call("read_ticket_pr", mapOf("ticket" to 731, "path" to "docs/proposals/x.md", "offset" to b, "repo" to "acme/widgets")))
        val start = "(characters $b–${doc.length} of ${doc.length})\n"
        assertEquals(head + start + doc.substring(b), second)
        assertEquals(doc, first.removePrefix(head).removeSuffix(cut) + second.removePrefix(head + start))
        assertEquals("offset ${doc.length} is past the end: the text is ${doc.length} characters long",
            error(call("read_ticket_pr", mapOf("ticket" to 731, "path" to "docs/proposals/x.md", "offset" to doc.length, "repo" to "acme/widgets"))))
    }

    @Test
    fun `read_ticket_pr pages the diff the same way`() {
        withPr()
        val b = FrakworksMcp.PR_CHAR_BUDGET
        val prefix = "PR #740 (OPEN, smith/issue-731, https://github.com/acme/widgets/pull/740) diff:\n\n"
        engine.diffs["acme/widgets" to 740L] = "x".repeat(b + 50)
        val whole = prefix + "x".repeat(b + 50)
        val first = text(call("read_ticket_pr", mapOf("ticket" to 731, "diff" to true, "repo" to "acme/widgets")))
        val rest = whole.length - b
        val cut = "\n… $rest more characters; pass offset: $b for the next page."
        assertEquals(whole.take(b) + cut, first)
        val second = text(call("read_ticket_pr", mapOf("ticket" to 731, "diff" to true, "offset" to b.toDouble(), "repo" to "acme/widgets")))
        val start = "(characters $b–${whole.length} of ${whole.length})\n"
        assertEquals(start + whole.substring(b), second)
        assertEquals(whole, first.removeSuffix(cut) + second.removePrefix(start))
        assertEquals("offset 999999 is past the end: the text is ${whole.length} characters long",
            error(call("read_ticket_pr", mapOf("ticket" to 731, "diff" to true, "offset" to 999999, "repo" to "acme/widgets"))))
    }

    @Test
    fun `read_ticket_pr refuses an offset without path or diff, and a bad offset`() {
        withPr()
        val only = "read_ticket_pr takes \"offset\" only with \"path\" or \"diff\": it pages a file's text or a diff"
        assertEquals(only, error(call("read_ticket_pr", mapOf("ticket" to 731, "offset" to 0, "repo" to "acme/widgets"))))
        assertEquals(only, error(call("read_ticket_pr", mapOf("ticket" to 731, "diff" to false, "offset" to 5, "repo" to "acme/widgets"))))
        for (bad in listOf(-1, 1.5, "10"))
            assertEquals("not an offset: ${if (bad is String) "\"$bad\"" else bad} (an integer, 0 or more)",
                error(call("read_ticket_pr", mapOf("ticket" to 731, "diff" to true, "offset" to bad, "repo" to "acme/widgets"))))
        assertEquals("ab\n… 2 more characters; pass offset: 2 for the next page.", FrakworksMcp.pageText("abcd", budget = 2))
        assertEquals("(characters 2–4 of 4)\ncd", FrakworksMcp.pageText("abcd", 2, budget = 2))
        assertEquals("", FrakworksMcp.pageText(""))
        assertEquals(null, FrakworksMcp.pageText("abcd", 4))
    }

    @Test
    fun `read_ticket_pr says when there is no PR, no such file, or no such issue`() {
        engine.pulls["acme/widgets" to 5L] = TicketPulls("main", emptyList())
        assertEquals("ticket acme/widgets#5 has no PR (none closes it on GitHub)", error(call("read_ticket_pr", mapOf("ticket" to 5, "repo" to "acme/widgets"))))
        withPr()
        assertEquals("docs/nope.md is not among the files acme/widgets#731's PR changes: \"docs/proposals/x.md\", \"README.md\"",
            error(call("read_ticket_pr", mapOf("ticket" to 731, "path" to "docs/nope.md", "repo" to "acme/widgets"))))
        assertEquals("no issue acme/other#731 on GitHub (no such repo, or no such issue in it)",
            error(call("read_ticket_pr", mapOf("ticket" to 731, "repo" to "acme/other"))))
        assertEquals("not an owner/name repo: \"../x\"", error(call("read_ticket_pr", mapOf("ticket" to 731, "repo" to "../x"))))
    }

    private val drafted = ProposeResult("drafted", "docs/proposals/x.md", "docs/proposals/x.html", "A draft of x.",
        listOf(PlanQuestion("Keep y?", "yes", 0.6, "high")))

    private fun withDraft(result: ProposeResult? = drafted, running: Boolean = true) {
        engine.pulls["acme/widgets" to 9L] = TicketPulls("main", emptyList())
        engine.proposals["acme/widgets" to 9L] = ProposalFlow("p-9", running, listOf("post the smith's question"), result,
            listOf(IntakeQuestion(3, "p-9", "acme/widgets", 9, "Which y?", OffsetDateTime.parse("2026-10-06T08:00:00Z"), null, null, null)))
    }

    @Test
    fun `read_ticket_pr serves a drafting proposal's draft and open questions from the engine`() {
        withDraft()
        assertEquals(mapOf("repo" to "acme/widgets", "ticket" to 9, "pulls" to emptyList<Any>(), "draft" to mapOf(
            "instance" to "p-9", "waitsAt" to listOf("post the smith's question"), "path" to "docs/proposals/x.md",
            "pitch" to "docs/proposals/x.html", "summary" to "A draft of x.",
            "questions" to listOf(mapOf("question" to "Keep y?", "assumption" to "yes", "confidence" to 0.6, "impact" to "high")),
            "openQuestions" to listOf(mapOf("question" to "Which y?", "askedAt" to "2026-10-06T08:00Z")))),
            body(call("read_ticket_pr", mapOf("ticket" to 9, "repo" to "acme/widgets"))))
    }

    @Test
    fun `read_ticket_pr pages a draft's doc and refuses a path the draft does not change`() {
        withDraft()
        val b = FrakworksMcp.PR_CHAR_BUDGET
        val doc = (0 until b + 100).joinToString("") { ('a' + it % 26).toString() }
        engine.drafts["acme/widgets" to 9L] = mapOf("docs/proposals/x.md" to doc, "docs/proposals/x.html" to "<p>x</p>")
        val head = "docs/proposals/x.md in the draft of acme/widgets#9 (proposal flow p-9, no PR yet):\n\n"
        assertEquals(head + doc.take(b) + "\n… 100 more characters; pass offset: $b for the next page.",
            text(call("read_ticket_pr", mapOf("ticket" to 9, "path" to "docs/proposals/x.md", "repo" to "acme/widgets"))))
        assertEquals(head + "(characters $b–${doc.length} of ${doc.length})\n" + doc.substring(b),
            text(call("read_ticket_pr", mapOf("ticket" to 9, "path" to "docs/proposals/x.md", "offset" to b, "repo" to "acme/widgets"))))
        assertEquals("README.md is not among the files acme/widgets#9's draft changes: \"docs/proposals/x.md\", \"docs/proposals/x.html\"",
            error(call("read_ticket_pr", mapOf("ticket" to 9, "path" to "README.md", "repo" to "acme/widgets"))))
    }

    @Test
    fun `read_ticket_pr refuses a diff of a draft with no PR`() {
        withDraft()
        assertEquals("ticket acme/widgets#9 is a proposal with no PR yet, so it has no diff: pass \"path\" " +
            "(\"docs/proposals/x.md\", \"docs/proposals/x.html\") to read its draft",
            error(call("read_ticket_pr", mapOf("ticket" to 9, "diff" to true, "repo" to "acme/widgets"))))
    }

    @Test
    fun `read_ticket_pr says a proposal's draft is not there yet`() {
        withDraft(result = null)
        assertEquals("ticket acme/widgets#9 has no PR, and its proposal flow p-9 has stored no draft yet (the first draft is still running)",
            error(call("read_ticket_pr", mapOf("ticket" to 9, "repo" to "acme/widgets"))))
        withDraft(running = false)
        assertEquals("ticket acme/widgets#9 has no PR, and its proposal flow p-9 has ended: no draft is there to read",
            error(call("read_ticket_pr", mapOf("ticket" to 9, "repo" to "acme/widgets"))))
    }

    @Test
    fun `read_ticket_pr reads only a target repo, by the name its row spells`() {
        engine.pulls["acme/secret" to 731L] = TicketPulls("main", listOf(PullFiles(9, "https://github.com/acme/secret/pull/9", "OPEN",
            "smith/issue-731", mapOf("x.md" to "ADDED"))))
        engine.diffs["acme/secret" to 9L] = "secret diff"
        for (args in listOf(mapOf("ticket" to 731, "repo" to "acme/secret"), mapOf("ticket" to 731, "repo" to "acme/secret", "diff" to true)))
            assertEquals("acme/secret is not a target repo", error(call("read_ticket_pr", args)))
        engine.targets.clear()
        assertEquals("acme/widgets is not a target repo", error(call("read_ticket_pr", mapOf("ticket" to 731, "repo" to "acme/widgets"))))
        engine.targets += "acme/widgets"
        withPr()
        assertEquals("acme/widgets", body(call("read_ticket_pr", mapOf("ticket" to 731, "repo" to "ACME/Widgets")))["repo"])
    }

    // ---- read_ci_log ----

    private val runs = FakeCheckRuns()
    private val mcpWithRuns = FrakworksMcp(engine, SeededTiers.table(), friction, checkRuns = runs)

    @Test
    fun `a Smith's read_ci_log reads its own repo's job log, timestamps and BuildKit prefixes stripped`() {
        runs.logText = "2026-09-26T10:03:06.0000000Z #11 190.1 ChoreProcessTest > a chore is worked() FAILED\r\n2026-09-26T10:03:07.0000000Z #11 195.0 FAILURE: Build failed with an exception.\n\n"
        val r = mcpWithRuns.call("read_ci_log", mapOf("check_run" to 11), smith)
        assertFalse(r.isError, r.text)
        assertEquals("The job log of check run 11 in acme/widgets:\n\nChoreProcessTest > a chore is worked() FAILED\nFAILURE: Build failed with an exception.", r.text)
        // A whole float is an id too, as JavaScript sends integers.
        assertFalse(mcpWithRuns.call("read_ci_log", mapOf("check_run" to 11.0), smith).isError)
    }

    @Test
    fun `read_ci_log pages a long log at the PR budget, and refuses an offset past its end`() {
        runs.logText = (1..2000).joinToString("\n") { "line $it " + "x".repeat(40) }
        val first = mcpWithRuns.call("read_ci_log", mapOf("check_run" to 11), smith).text
        assertTrue("more characters; pass offset: ${FrakworksMcp.PR_CHAR_BUDGET} for the next page." in first, first.takeLast(200))
        val next = mcpWithRuns.call("read_ci_log", mapOf("check_run" to 11, "offset" to FrakworksMcp.PR_CHAR_BUDGET), smith).text
        assertTrue(next.startsWith("The job log of check run 11 in acme/widgets:\n\n(characters ${FrakworksMcp.PR_CHAR_BUDGET}–"), next.take(120))
        val total = runs.logText!!.length
        assertEquals("offset ${total + 5} is past the end: the log is $total characters long",
            error(mcpWithRuns.call("read_ci_log", mapOf("check_run" to 11, "offset" to total + 5), smith)))
    }

    @Test
    fun `read_ci_log refuses a bad id, a bad offset, other arguments, a run with no repo, and reports GitHub's refusal`() {
        assertEquals("not a check run id: 0 (a positive integer)", error(mcpWithRuns.call("read_ci_log", mapOf("check_run" to 0), smith)))
        assertEquals("not a check run id: \"11\" (a positive integer)", error(mcpWithRuns.call("read_ci_log", mapOf("check_run" to "11"), smith)))
        assertEquals("not a check run id: null (a positive integer)", error(mcpWithRuns.call("read_ci_log", emptyMap(), smith)))
        assertEquals("not an offset: -1 (a non-negative integer)", error(mcpWithRuns.call("read_ci_log", mapOf("check_run" to 11, "offset" to -1), smith)))
        assertEquals("read_ci_log takes only \"check_run\" and \"offset\", not \"repo\"",
            error(mcpWithRuns.call("read_ci_log", mapOf("check_run" to 11, "repo" to "acme/other"), smith)))
        assertEquals("Smith run run-2 works in no target repo, so it has no check runs to read",
            error(mcpWithRuns.call("read_ci_log", mapOf("check_run" to 11), McpCaller.Smith(SmithCaller("run-2", null, null)))))
        runs.logText = null
        assertEquals("could not read the log of check run 11 in acme/widgets: 403 Forbidden", error(mcpWithRuns.call("read_ci_log", mapOf("check_run" to 11), smith)))
        runs.logText = "   \n"
        assertEquals("the log of check run 11 in acme/widgets is empty (the job may still be running, or its log expired)",
            error(mcpWithRuns.call("read_ci_log", mapOf("check_run" to 11), smith)))
    }

    @Test
    fun `read_ci_log is a Smith's, not First mate's, and an engine with no check-run reader says so`() {
        assertEquals("unknown tool: \"read_ci_log\"", error(call("read_ci_log", mapOf("check_run" to 11), mate)))
        assertEquals("the engine has no GitHub check-run reader; open the check's page for its log", error(call("read_ci_log", mapOf("check_run" to 11), smith)))
    }

    // ---- read_ci_runs ----

    @Test
    fun `read_ci_runs answers each run's duration and queue time, and their medians, for a target repo`() {
        runs.workflowRunRows = listOf(
            WorkflowRun(3, "engine", "main", "push", "completed", "success", "2026-10-06T10:00:00Z", "2026-10-06T10:01:00Z", "2026-10-06T10:11:00Z", "u3", ".github/workflows/engine.yml"),
            WorkflowRun(2, "engine", "main", "pull_request", "in_progress", null, "2026-10-06T09:00:00Z", "2026-10-06T09:00:30Z", null, "u2", ".github/workflows/engine.yml"),
            WorkflowRun(1, "engine", "main", "push", "completed", "failure", "2026-10-06T08:00:00Z", "2026-10-06T08:00:10Z", "2026-10-06T08:05:10Z", "u1"))
        val b = body(mcpWithRuns.call("read_ci_runs", mapOf("repo" to "ACME/Widgets", "workflow" to "engine.yml", "branch" to "main", "limit" to 3), mate))
        assertEquals(listOf<Any?>("acme/widgets", "engine.yml", "main", 3), runs.workflowRunsAsked.single())
        assertEquals("acme/widgets", b["repo"])
        val rows = b["runs"] as List<*>
        assertEquals(listOf(600, null, 300), rows.map { (it as Map<*, *>)["durationSeconds"] })
        assertEquals(listOf(60, 30, 10), rows.map { (it as Map<*, *>)["queuedSeconds"] })
        assertEquals(mapOf("count" to 3, "medianDurationSeconds" to 450.0, "medianQueuedSeconds" to 30.0), b["summary"])
        assertEquals(listOf("engine", "engine", null), rows.map { (it as Map<*, *>)["area"] })
        assertEquals(listOf("main", "pr", "main"), rows.map { (it as Map<*, *>)["trigger"] })
    }

    @Test
    fun `read_ci_runs' median takes the middle of an odd count, averages an even one, skips nulls and is null with none`() {
        assertEquals(20.0, CiRunsTool.median(listOf(30L, null, 10L, 20L)))
        assertEquals(25.0, CiRunsTool.median(listOf(40L, 10L, 20L, 30L)))
        assertEquals(7.0, CiRunsTool.median(listOf(7L)))
        assertNull(CiRunsTool.median(listOf<Long?>(null, null)))
        assertNull(CiRunsTool.median(emptyList()))
        assertEquals(mapOf("count" to 0, "medianDurationSeconds" to null, "medianQueuedSeconds" to null),
            CiRunsTool.answer("acme/widgets", emptyList())["summary"])
    }

    @Test
    fun `read_ci_runs refuses a missing or non-target repo naming the target repos, a limit above 100, and a Smith`() {
        val missing = error(mcpWithRuns.call("read_ci_runs", mapOf("limit" to 5), mate))
        assertTrue("no default repo" in missing && "the target repos are acme/widgets" in missing, missing)
        assertEquals("acme/nowhere is not a target repo; the target repos are acme/widgets",
            error(mcpWithRuns.call("read_ci_runs", mapOf("repo" to "acme/nowhere", "limit" to 5), mate)))
        assertEquals("not a limit: 101 (an integer from 1 to 100)", error(mcpWithRuns.call("read_ci_runs", mapOf("repo" to "acme/widgets", "limit" to 101), mate)))
        assertEquals("not a limit: null (an integer from 1 to 100)", error(mcpWithRuns.call("read_ci_runs", mapOf("repo" to "acme/widgets"), mate)))
        assertTrue(runs.workflowRunsAsked.isEmpty())
        assertTrue(mcpWithRuns.tools(smith).none { it.name == "read_ci_runs" })
        assertEquals("a Smith sandbox (run run-1) may not call read_ci_runs: CI run history is First mate's to read",
            error(mcpWithRuns.call("read_ci_runs", mapOf("repo" to "acme/widgets", "limit" to 5), smith)))
    }
}

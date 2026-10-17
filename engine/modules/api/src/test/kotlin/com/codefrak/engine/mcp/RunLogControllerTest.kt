package com.codefrak.engine.mcp

import com.codefrak.engine.board.Need
import com.codefrak.engine.repos.AccessView
import com.codefrak.engine.github.BundleChanges
import com.codefrak.engine.github.TicketPulls
import com.codefrak.engine.intake.Amended
import com.codefrak.engine.intake.AskRequest
import com.codefrak.engine.intake.IntakeStarted
import com.codefrak.engine.intake.TicketAmended
import com.codefrak.engine.repos.AddRepo
import com.codefrak.engine.repos.EngineToken
import com.codefrak.engine.repos.MintedToken
import com.codefrak.engine.repos.TargetRepo
import com.codefrak.engine.smith.SandboxStatus
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.smith.SmithSandbox
import com.codefrak.engine.smith.SmithSpec
import com.codefrak.engine.trigger.IssueApprovals
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant

/** read_run_log and its controller call only `smithRun`, `doctorStuckTickets` and the named-run `transcript`; every other member is unused here. */
private class FakeEngine : FrakworksEngine {
    val smithRuns = mutableMapOf<String, SmithRunRef>()
    val stuckTickets = mutableMapOf<String, List<Pair<String, Long>>>()
    var transcriptDoc: Transcript? = null
    override fun smithRun(runId: String) = smithRuns[runId]
    override fun doctorStuckTickets(runId: String) = stuckTickets[runId].orEmpty()
    override fun transcript(ticket: Long, run: NamedRun): Transcript? = transcriptDoc
    override fun submit(ask: AskRequest, request: HttpServletRequest?): IntakeStarted = error("unused")
    override fun needs(): List<Need> = error("unused")
    override fun cancelIntake(intakeId: String, by: String, reason: String, request: HttpServletRequest?): List<String> = error("unused")
    override fun amendIntake(intakeId: String, text: String, by: String): Amended = error("unused")
    override fun amendTicket(repo: String?, ticket: Long, text: String, by: String): TicketAmended = error("unused")
    override fun transcript(ticket: Long): Transcript? = error("unused")
    override fun ticketRuns(repo: String, ticket: Long): List<TicketRunRow>? = error("unused")
    override fun targetRepo(repo: String): String? = error("unused")
    override fun ticketPulls(repo: String, ticket: Long): TicketPulls? = error("unused")
    override fun fileText(repo: String, path: String, ref: String): String? = error("unused")
    override fun proposalFlow(repo: String, ticket: Long): ProposalFlow? = error("unused")
    override fun draftChanges(repo: String, ticket: Long, default: String, path: String): BundleChanges? = error("unused")
    override fun pullDiff(repo: String, number: Long): String? = error("unused")
    override fun approve(repo: String, ticket: Long, by: String): IssueApprovals.Approved? = error("unused")
    override fun approveTicket(repo: String, ticket: Long, by: String): IssueApprovals.TicketApproved = error("unused")
    override fun reauthorize(repo: String, ticket: Long, by: String): IssueApprovals.Reauthorized = error("unused")
    override fun trusted(login: String): Boolean = error("unused")
    override fun runningFlows(repo: String, ticket: Long): List<String> = error("unused")
    override fun deferred(repo: String, ticket: Long): Boolean = error("unused")
    override fun dropDeferred(repo: String, ticket: Long): Unit = error("unused")
    override fun close(repo: String, ticket: Long, reason: String): Unit = error("unused")
    override fun repos(login: String?): List<TargetRepo> = error("unused")
    override fun addRepo(body: AddRepo, request: HttpServletRequest?): Boolean = error("unused")
    override fun editRepo(repo: String, fields: Map<String, Any?>, request: HttpServletRequest?): TargetRepo = error("unused")
    override fun removeRepo(repo: String): Unit = error("unused")
    override fun access(): AccessView = error("unused")
    override fun addAccess(login: String, by: String): Boolean = error("unused")
    override fun removeAccess(login: String, by: String): Boolean = error("unused")
    override fun tokens(): List<EngineToken> = error("unused")
    override fun mintToken(login: String, name: String, expiresInDays: Int?, by: String): MintedToken = error("unused")
    override fun revokeToken(id: String): Boolean? = error("unused")
    override fun anyActiveToken(): Boolean = error("unused")
}

/** `GET /runs/{id}/log` and read_run_log (#1634): a run on the caller's own ticket or a doctor-named stuck ticket, and no other. */
class RunLogControllerTest {
    private class Sandbox(val runs: Map<String, SmithCaller>) : SmithSandbox {
        override fun start(spec: SmithSpec): String = error("unused")
        override fun status(handle: String): SandboxStatus = error("unused")
        override fun logs(handle: String, maxLines: Int): String = ""
        override fun output(handle: String, path: String): ByteArray? = null
        override fun remove(handle: String) {}
        override fun runAt(address: String): SmithCaller? = runs[address]
    }

    private val refiner = "172.18.0.9"
    private val engine = FakeEngine().apply {
        val started = Instant.parse("2026-10-06T21:00:00Z")
        smithRuns["stuck-run"] = SmithRunRef("stuck-run", "acme/widgets", 1352, NamedRun("stuck-run", "work", started))
        smithRuns["other-run"] = SmithRunRef("other-run", "acme/widgets", 99, NamedRun("other-run", "work", started))
        stuckTickets["refine-run"] = listOf("acme/widgets" to 1352L)
        transcriptDoc = Transcript(1352, liveKnown = true, session = "issue-1352-work-stuck-run.log",
            events = listOf(TranscriptEvent(null, listOf("fatal: could not read Username"))))
    }
    private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(
        RunLogController(engine, Sandbox(mapOf(refiner to SmithCaller("refine-run", 1367, "acme/widgets"))))).build()

    private fun get(id: String, from: String = refiner) =
        mvc.get("/runs/$id/log") { with(RequestPostProcessor { it.remoteAddr = from; it }) }.andReturn().response

    @Test
    fun `a refiner reads a run on the stuck ticket its doctor-filed ask names`() {
        val r = get("stuck-run")
        assertEquals(200, r.status)
        assertTrue(r.contentAsString.contains("fatal: could not read Username"), r.contentAsString)
    }

    @Test
    fun `a run on any other ticket is refused`() {
        val r = get("other-run")
        assertEquals(403, r.status)
        assertTrue(r.contentAsString.contains("acme/widgets#99"), r.contentAsString)
    }

    @Test
    fun `a caller that is no Smith is refused`() {
        assertEquals(403, get("stuck-run", from = "127.0.0.1").status)
    }

    @Test
    fun `read_run_log answers the tail and refuses the same`() {
        val mcp = RunLogTool(engine)
        val smith = McpCaller.Smith(SmithCaller("refine-run", 1367, "acme/widgets"), null)
        engine.transcriptDoc = engine.transcriptDoc!!.copy(events = listOf(TranscriptEvent(null, listOf("x".repeat(30000) + "END"))))
        val ok = mcp.call(mapOf("runId" to "stuck-run"), smith)
        assertEquals(false, ok.isError)
        assertTrue(ok.text.endsWith("END") && ok.text.contains("last ${RunLogTool.LOG_CHARS} of 30003 characters"), ok.text.take(200))
        assertEquals(true, mcp.call(mapOf("runId" to "other-run"), smith).isError)
        val own = McpCaller.Smith(SmithCaller("work-run", 99, "acme/widgets"), null)
        assertEquals(false, mcp.call(mapOf("runId" to "other-run"), own).isError)
    }

    @Test
    fun `a run whose transcript shipped no events answers its sandbox output as plain text`() {
        engine.smithRuns["dead-run"] = SmithRunRef("dead-run", "acme/widgets", 1352,
            NamedRun("dead-run", "refine", Instant.parse("2026-10-06T21:00:00Z")), logTail = "bash: id: command not found")
        engine.transcriptDoc = engine.transcriptDoc!!.copy(events = emptyList())
        val r = get("dead-run")
        assertEquals(200, r.status)
        assertTrue(r.contentAsString.contains("shipped no events") && r.contentAsString.endsWith("bash: id: command not found"), r.contentAsString)
    }

    @Test
    fun `an ended run with no session answers its sandbox output, and with no tail or still running the 404`() {
        val started = Instant.parse("2026-10-06T21:00:00Z")
        engine.transcriptDoc = Transcript(1352, liveKnown = true)
        engine.smithRuns["dead-run"] = SmithRunRef("dead-run", "acme/widgets", 1352, NamedRun("dead-run", "refine", started), logTail = "exit 2")
        val ok = get("dead-run")
        assertEquals(200, ok.status)
        assertTrue(ok.contentAsString.contains("sandbox's own output") && ok.contentAsString.endsWith("exit 2"), ok.contentAsString)
        engine.smithRuns["dead-run"] = SmithRunRef("dead-run", "acme/widgets", 1352, NamedRun("dead-run", "refine", started), logTail = " ")
        assertEquals(404, get("dead-run").status)
        engine.smithRuns["dead-run"] = SmithRunRef("dead-run", "acme/widgets", 1352,
            NamedRun("dead-run", "refine", started, running = true), logTail = "exit 2")
        val live = get("dead-run")
        assertEquals(404, live.status)
        assertTrue(live.contentAsString.contains("no log of run dead-run was shipped"), live.contentAsString)
    }

    @Test
    fun `a run whose transcript has events answers the transcript, never the sandbox output`() {
        engine.smithRuns["stuck-run"] = engine.smithRuns["stuck-run"]!!.copy(logTail = "SANDBOX")
        val r = get("stuck-run")
        assertEquals(200, r.status)
        assertTrue(r.contentAsString.contains("fatal: could not read Username") && !r.contentAsString.contains("SANDBOX"), r.contentAsString)
    }
}

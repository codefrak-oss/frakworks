package com.codefrak.engine.refactor

import com.codefrak.engine.intake.tickets
import com.codefrak.engine.TestTokens
import com.codefrak.engine.chore.FakeGitHubPulls
import com.codefrak.engine.chore.FakeGitPush
import com.codefrak.engine.chore.ProcessFlowStarter
import com.codefrak.engine.chore.ScriptedSandbox
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.events.EventRecorder
import com.codefrak.engine.github.COMPLETED
import com.codefrak.engine.github.NOT_PLANNED
import com.codefrak.engine.intake.TicketRecorder
import com.codefrak.engine.intake.RecordingFlowStarter
import com.codefrak.engine.intake.TicketFiler
import com.codefrak.engine.jobs.JobStarter
import com.codefrak.engine.jobs.JobStore
import com.codefrak.engine.mcp.EngineMcpController
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.runAllJobs
import com.codefrak.engine.smith.BRIEF_FILE
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.SmithExitNotifier
import com.codefrak.engine.smith.ensureTestAccount
import com.codefrak.engine.workitems.FakeTickets
import com.codefrak.engine.workitems.NewWorkItem
import com.codefrak.engine.workitems.WorkItemFlags
import com.codefrak.engine.workitems.WorkItemSourceRef
import com.codefrak.sdk.CallableSmith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RuntimeService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A refactoring job end to end (bpmn/job.bpmn's refactor branch, then bpmn/refactor-scan.bpmn; engine/README.md,
 * "Refactoring jobs"): enabled through `PUT /repos/{o}/{n}/refactor`, executed through `POST /jobs/{id}/execute`, which
 * files and starts the refactor-scan ticket, whose scripted sandbox answers mode `job` with proposals, and the tickets
 * filed on the fake GitHub. The feature starts approval `none` makes are recorded, not run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class RefactorJobProcessTest {
    @TestConfiguration
    class Fakes {
        @Bean @Primary fun github() = TicketRecorder()
        @Bean @Primary fun tickets(github: com.codefrak.engine.intake.TicketRecorder) = github.tickets()
        @Bean @Primary fun pulls() = FakeGitHubPulls()
        @Bean @Primary fun git() = FakeGitPush()
        @Bean @Primary fun sandbox() = ScriptedSandbox()
        @Bean @Primary fun starter(real: ProcessFlowStarter) = RecordingFlowStarter(real)
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var history: HistoryService
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var exits: SmithExitNotifier
    @Autowired lateinit var sandbox: ScriptedSandbox
    @Autowired lateinit var github: TicketRecorder
    @Autowired lateinit var tickets: FakeTickets
    @Autowired lateinit var starter: RecordingFlowStarter
    @Autowired lateinit var accounts: com.codefrak.engine.accounts.AiAccounts
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var access: Access
    @Autowired lateinit var store: JobStore
    @Autowired lateinit var runs: com.codefrak.engine.jobs.JobRuns
    @Autowired lateinit var configs: RefactorConfigStore
    @Autowired lateinit var bus: EngineEvents
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var tiers: com.codefrak.engine.tier.TierTable
    @Autowired lateinit var scanner: com.codefrak.engine.trigger.IssueScanner
    @Autowired lateinit var dataSource: javax.sql.DataSource

    private val json = jacksonObjectMapper()
    private val repo = "acme/refjob"
    private val login = "alice"

    @BeforeEach fun setUp() {
        runtime.createProcessInstanceQuery().processDefinitionKey(JobStarter.PROCESS_KEY).list().forEach { runtime.deleteProcessInstance(it.id, "test reset") }
        clearScans()
        history.createHistoricProcessInstanceQuery().processDefinitionKey(REFACTOR_SCAN).list().forEach { history.deleteHistoricProcessInstance(it.id) }
        repos.remove(repo); repos.add(repo)
        access.add(login, "test")
        sandbox.reset(); accounts.ensureTestAccount()
        github.created.clear(); github.comments.clear(); github.labelled.clear(); github.closed.clear(); github.closedAs.clear(); github.onLabel = null
        tickets.reset(); starter.features.clear()
        jdbc.sql("DELETE FROM engine.handled_issue WHERE full_name = :r").param("r", repo).update()
        jdbc.sql("DELETE FROM engine.job_run WHERE repo = :r").param("r", repo).update()
    }
    @AfterEach fun tearDown() { accounts.remove("test-anthropic"); repos.remove(repo) }

    private fun runAllJobs() = management.runAllJobs(exits)

    private fun variable(instanceId: String, name: String) =
        history.createHistoricVariableInstanceQuery().processInstanceId(instanceId).variableName(name).singleResult()?.value

    private fun enable(extra: String = "", tier: String = "9"): String {
        val result = mvc.put("/repos/$repo/refactor") { contentType = MediaType.APPLICATION_JSON; content = """{"enabled":true,"tier":$tier$extra}"""; with(TestTokens.bearer(login)) }.andReturn()
        assertEquals(200, result.response.status, result.response.errorMessage ?: "")
        return json.readValue<Map<String, Any?>>(result.response.contentAsString).getValue("jobId") as String
    }

    private fun execute(id: String): String {
        val result = mvc.post("/jobs/$id/execute") { with(TestTokens.bearer(login)) }.andReturn()
        assertEquals(200, result.response.status, result.response.errorMessage ?: "")
        return json.readValue<Map<String, String>>(result.response.contentAsString).getValue("instance")
    }

    /** A proposal with a body the size of a real one (~1.5 k characters), so a run's `result` outgrows a String variable as a real run's does. */
    private fun proposal(key: String, lines: Int = 120) =
        """{"key":"$key","title":"Split $key","body":"## Goal\n\nSplit it.\n\n## Acceptance criteria\n\n- at most 300 changed lines\n\n## Allowed scope\n\n$key/**\n\n## Context\n\nOne module, one job: smaller modules. $SEEN","lines":$lines}"""

    private fun script(vararg proposals: String) {
        val inspected = if (proposals.isEmpty()) ""","inspected":["engine/modules"]""" else ""
        sandbox.script.getOrPut("job") { ArrayDeque() }.addLast("""{"outcome":"done","summary":{"proposals":[${proposals.joinToString(",")}]$inspected}}""")
    }

    // TicketRecorder numbers the issues it creates from 100 up (99 + the count after adding).
    private fun finished(root: String) = history.createHistoricProcessInstanceQuery().processInstanceId(root).finished().count() == 1L

    /** The refactor-scan flow's instance on scan ticket [issue], the one a job run filed and started. */
    private fun scan(issue: Long = 100L): String = history.createHistoricProcessInstanceQuery().processDefinitionKey(REFACTOR_SCAN)
        .variableValueEquals("issue", issue).list().single().id

    private fun clearScans() = runtime.createProcessInstanceQuery().processDefinitionKey(REFACTOR_SCAN).list().forEach { runtime.deleteProcessInstance(it.id, "test reset") }

    private companion object {
        /** What the Smith saw, as the prompt asks it to write: padding that takes three proposals past 4000 characters. */
        val SEEN = "What I saw: " + (1..30).joinToString(" ") { "the file engine/src/main/kotlin/Part$it.kt holds two jobs" } + "."
    }

    @Test
    fun `an open ticket of kind refactor-scan stops the job from filing another, found by its kind`() {
        val jobId = enable()
        val open = tickets.create(NewWorkItem(repo, "# Refactoring scan of $repo\n\nscanning", REFACTOR_SCAN,
            WorkItemSourceRef(WorkItemSourceRef.RAISED), ticket = 77, author = "engine"))
        val root = execute(jobId)
        runAllJobs()

        assertTrue(finished(root), "the job instance ended")
        assertEquals("filed nothing: refactor-scan #${open.ticket} is still open", variable(root, FileRefactorTicketsDelegate.SUMMARY))
        assertEquals(emptyList(), github.created.filter { it.first == repo }, "no second scan filed")
        assertTrue(sandbox.started.isEmpty(), "no scan started")
    }

    @Test
    fun `the job files a refactor-scan ticket at the config's tier and starts it, and the scan files at most max tickets, then closes`() = EventRecorder(bus, repo).use { rec ->
        val jobId = enable()
        script(proposal("engine/jobs"), proposal("engine/intake"), proposal("bots/readme"))
        val root = execute(jobId)
        runAllJobs()

        assertTrue(finished(root), "the job instance ended")
        assertEquals(0L, runtime.createIncidentQuery().processInstanceId(root).count())
        assertEquals("filed refactor-scan #100", variable(root, FileRefactorTicketsDelegate.SUMMARY))
        val (_, scanIssue) = github.created.first { it.first == repo }
        assertEquals("Refactoring scan of $repo", scanIssue.title)
        assertEquals(listOf(REFACTOR_SCAN), scanIssue.labels)
        assertContains(scanIssue.body, RefactorValues.DEFAULT.trimEnd()); assertContains(scanIssue.body, "At most 2 proposals, each `lines` at most 300")
        assertContains(scanIssue.body, "<!-- engine: refactor job $jobId · tier 9 · p3 · by $login -->")
        assertTrue(Triple(repo, 100L, listOf("authorized")) in github.labelled, "authorized as the visible record")
        val claim = jdbc.sql("SELECT flow, detail FROM engine.handled_issue WHERE full_name = :r AND issue = 100 AND label_event = 0")
            .param("r", repo).query { rs, _ -> rs.getString(1) to rs.getString(2) }.single()
        assertEquals(REFACTOR_SCAN to "refactor job $jobId", claim)

        // The scan: a no-code job-mode run on the ticket's instructions, at tier 9.
        val spec = sandbox.started.single()
        assertEquals("job", spec.mode); assertEquals(repo, spec.repo); assertNull(spec.issue)
        val expected = tiers.resolve(9)
        assertEquals(expected.model to expected.effort, spec.model to spec.effort, "tier 9's model and effort from the tier map")
        val prompt = json.readValue<Brief>(spec.inputs.getValue(BRIEF_FILE)).prompt!!
        assertContains(prompt, RefactorValues.DEFAULT.trimEnd()); assertFalse("<!-- engine:" in prompt, "the trailer is not part of the instructions")

        val scan = scan()
        assertTrue(finished(scan), "the scan ended")
        assertEquals(0L, runtime.createIncidentQuery().processInstanceId(scan).count())
        assertEquals("done", variable(scan, "outcome"))
        // Three full bodies run past the 4000 characters a String variable holds: the result is stored as bytes and read all the same.
        assertTrue(variable(scan, CallableSmith.RESULT) is ByteArray, "a long result is a bytes variable")
        val created = github.created.filter { it.first == repo }.drop(1)
        assertEquals(listOf("Split engine/jobs", "Split engine/intake"), created.map { it.second.title }, "the first maxTickets, in the Smith's order")
        created.forEach { (_, issue) ->
            assertEquals(listOf("feature", "feature:confirm", "refactor"), issue.labels)
            assertContains(issue.body, "**at most 300 changed lines**")
            assertContains(issue.body, FileRefactorTicketsDelegate.VALUES_HEADING)
            assertTrue(RefactorValues.DEFAULT.trimEnd().lines().all { "> $it\n" in issue.body }, "every line of the values quoted")
            assertContains(issue.body, "## Context\n\nOne module, one job")
            assertEquals(login, TicketFiler.askedByOf(issue.body))
        }
        assertEquals("engine/jobs", TicketFiler.keyOf(created[0].second.body)); assertEquals("engine/intake", TicketFiler.keyOf(created[1].second.body))
        assertContains(created[0].second.body, "<!-- engine: refactor-scan #100 · p3 · by $login · key engine/jobs -->")
        assertTrue(github.labelled.none { it.first == repo && it.second != 100L && "authorized" in it.third }, "approval human: nothing is authorized")
        assertTrue(starter.features.isEmpty(), "no feature starts")
        assertEquals("filed #101, #102; skipped 1 over the cap", variable(scan, FileRefactorTicketsDelegate.SUMMARY))
        assertEquals(COMPLETED, github.closedAs[repo to 100L])
        assertTrue(github.comments.any { it.first == repo && it.second == 100L && "Refactoring scan done: filed #101, #102" in it.third })

        val frames = rec.of("job", Duration.ofSeconds(2)).filter { it.str("jobId") == jobId }
        assertEquals(listOf("started", "ended"), frames.map { it.state }, frames.toString())
        frames.forEach { assertEquals("system", it.str("owner")); assertEquals("refactor", it.str("kind")); assertEquals("refactor $repo", it.str("name")) }
        assertEquals("done", frames[1].str("outcome"))

        // The run's history row: the scan it filed, and what the scan filed and skipped.
        val run = runs.get(root)!!
        assertEquals(jobId, run.jobId); assertEquals(repo, run.repo); assertEquals("refactor", run.kind); assertNotNull(run.endedAt)
        assertEquals("done", run.status); assertEquals("filed refactor-scan #100", run.summary); assertEquals(100L, run.scanIssue)
        assertEquals(listOf(101L, 102L), run.filed); assertEquals(mapOf("overCap" to 1), run.skipped); assertEquals(false, run.foundNothing)
        assertEquals("done", run.scanOutcome); assertEquals("filed #101, #102; skipped 1 over the cap", run.scanSummary); assertNotNull(run.scanEndedAt)
        val listed = mvc.get("/jobs/$jobId/runs").andReturn()
        assertEquals(200, listed.response.status)
        assertEquals(listOf(root), json.readValue<List<Map<String, Any?>>>(listed.response.contentAsString).map { it["instance"] })
    }

    @Test
    fun `a scan that proposes nothing is recorded as found nothing on the job run`() {
        val jobId = enable()
        script()
        val root = execute(jobId)
        runAllJobs()

        assertEquals("found nothing", variable(scan(), FileRefactorTicketsDelegate.SUMMARY))
        val run = runs.get(root)!!
        assertEquals("done", run.status); assertEquals(100L, run.scanIssue)
        assertEquals(true, run.foundNothing); assertEquals(emptyList(), run.filed); assertEquals(emptyMap(), run.skipped)
        assertEquals("found nothing", run.scanSummary)
        assertEquals(1, github.created.count { it.first == repo }, "only the scan ticket")
    }

    @Test
    fun `with no tier the job files no scan and ends failed saying so`() {
        val jobId = enable(tier = "null")
        val root = execute(jobId)
        runAllJobs()

        assertTrue(finished(root))
        assertEquals("failed: refactoring on $repo has no tier: set one with PUT /repos/$repo/refactor", variable(root, "outcome"))
        assertTrue(github.created.none { it.first == repo }); assertTrue(sandbox.started.isEmpty())
        val run = runs.get(root)!!
        assertEquals("failed", run.status); assertEquals("failed: refactoring on $repo has no tier: set one with PUT /repos/$repo/refactor", run.outcome)
        assertNotNull(run.endedAt)
    }

    @Test
    fun `a key open on a refactor issue is not filed again, nor a duplicate within the scan, nor one over the line cap`() {
        val jobId = enable()
        val openBody = "## Goal\n\n<!-- engine: refactor-scan #7 · p3 · by alice · key engine/jobs -->\n"
        tickets.create(NewWorkItem(repo, "# Split engine/jobs\n\n$openBody", "feature", WorkItemSourceRef(WorkItemSourceRef.RAISED),
            mode = "confirm", ticket = 42, author = "fleet", flags = WorkItemFlags(refactor = true)))
        tickets.create(NewWorkItem(repo, "# Unrelated\n\n<!-- engine: intake x · p3 · key engine/intake -->", "chore",
            WorkItemSourceRef(WorkItemSourceRef.RAISED), ticket = 43, author = "alice"))
        script(proposal("engine/jobs"), proposal("engine/intake"), proposal("engine/intake"), proposal("engine/huge", lines = 301))
        execute(jobId)
        runAllJobs()

        val prompt = json.readValue<Brief>(sandbox.started.single().inputs.getValue(BRIEF_FILE)).prompt!!
        assertContains(prompt, "- `engine/jobs`"); assertFalse("engine/intake" in prompt.substringAfter(RefactorPrompt.OPEN_HEADING).substringBefore(RefactorPrompt.SHAPE_HEADING),
            "a key on an issue without the refactor label does not count as open")
        val scan = scan()
        assertTrue(finished(scan))
        assertEquals(listOf("Split engine/intake"), github.created.filter { it.first == repo }.drop(1).map { it.second.title })
        assertEquals("filed #101; skipped 2 open, 1 oversize", variable(scan, FileRefactorTicketsDelegate.SUMMARY), "the open key, and the duplicate as open once filed")
        assertEquals("done", variable(scan, "outcome"))
    }

    @Test
    fun `approval none files feature auto, starts the feature at once as label event 0 and labels it authorized`() {
        val jobId = enable(""","approval":"none","maxTickets":1""")
        script(proposal("engine/jobs"))
        execute(jobId)
        runAllJobs()

        val scan = scan()
        assertTrue(finished(scan))
        val (_, issue) = github.created.filter { it.first == repo }[1]
        assertEquals(listOf("feature", "feature:auto", "refactor"), issue.labels)
        val (by, snapshot, tier, extra) = starter.features.single()
        assertEquals(login, by); assertEquals(101L, snapshot.number); assertNull(tier)
        assertEquals(mapOf<String, Any>("featureMode" to "auto"), extra)
        assertTrue(Triple(repo, 101L, listOf("authorized")) in github.labelled)
        val claim = jdbc.sql("SELECT flow, outcome, detail, process_instance FROM engine.handled_issue WHERE full_name = :r AND issue = 101 AND label_event = 0")
            .param("r", repo).query { rs, _ -> listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) }.single()
        assertEquals(listOf("feature", "started", "refactor-scan #100", "feature-1"), claim)
        assertTrue(github.comments.any { it.first == repo && it.second == 101L && "Feature started in auto mode: refactor-scan #100" in it.third })
        assertEquals("filed #101", variable(scan, FileRefactorTicketsDelegate.SUMMARY))
    }

    @Test
    fun `the authorized label's webhook scan, on another connection the moment the label is written, finds the claim and skips it`() {
        // #1802: the fleet's `authorized` label wakes a scan at once (WebhookWake). Here that scan runs inside the label call,
        // on a thread and connection of its own, as the webhook's does. The fleet login is not a trusted one.
        val jobId = enable(""","approval":"none","maxTickets":1""")
        script(proposal("engine/jobs"))
        val claimed = mutableListOf<Pair<Long, Boolean>>()
        val actions = mutableListOf<com.codefrak.engine.trigger.ScanAction>()
        github.onLabel = { r, n, _ ->
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT COUNT(*) FROM engine.handled_issue WHERE full_name = ? AND issue = ? AND label_event = 0").use { st ->
                    st.setString(1, r); st.setLong(2, n)
                    st.executeQuery().use { rs -> rs.next(); claimed += n to (rs.getLong(1) == 1L) }
                }
            }
            Thread.ofPlatform().start { synchronized(actions) { actions += scanner.scan(setOf(r)).actions } }.join()
        }
        execute(jobId)
        runAllJobs()

        assertEquals(listOf(100L to true, 101L to true), claimed, "the claim is committed, seen from another connection, before each label")
        assertEquals(emptyList(), actions, "nothing refused, nothing started by the scan")
        assertTrue(github.comments.none { it.first == repo && "Not starting" in it.third }, github.comments.toString())
        assertEquals(listOf(0L), jdbc.sql("SELECT label_event FROM engine.handled_issue WHERE full_name = :r AND issue = 101").param("r", repo)
            .query(Long::class.java).list())
        assertEquals(1, starter.features.size, "the delegate's start is the only one")
    }

    @Test
    fun `a failed smith result closes the scan not planned with nothing filed, and a summary without proposals is its own failure`() {
        val jobId = enable()
        sandbox.script.getOrPut("job") { ArrayDeque() }.addLast("""{"outcome":"failed","summary":"could not read the repo"}""")
        execute(jobId)
        runAllJobs()
        val failed = scan()
        assertTrue(finished(failed))
        assertEquals("failed", variable(failed, "outcome"))
        val failedRun = runs.list(jobId, 1).single()
        assertEquals(100L, failedRun.scanIssue); assertEquals("failed", failedRun.scanOutcome); assertNull(failedRun.filed); assertNull(failedRun.scanSummary)
        assertEquals(1, github.created.count { it.first == repo }, "only the scan ticket")
        assertEquals(NOT_PLANNED, github.closedAs[repo to 100L])

        sandbox.script.getOrPut("job") { ArrayDeque() }.addLast("""{"outcome":"done","summary":"a few ideas"}""")
        execute(jobId)
        runAllJobs()
        val shapeless = scan(101L)
        assertTrue(finished(shapeless))
        assertEquals("failed: the run's summary has no proposals array", variable(shapeless, "outcome"))
        assertEquals(2, github.created.count { it.first == repo }, "only the two scan tickets")
        assertEquals(0L, runtime.createIncidentQuery().processInstanceId(shapeless).count())
        assertEquals(NOT_PLANNED, github.closedAs[repo to 101L])
    }

    @Test
    fun `a scan that proposes nothing and names nothing it inspected is failed, not found nothing`() {
        val jobId = enable()
        sandbox.script.getOrPut("job") { ArrayDeque() }.addLast("""{"outcome":"done","summary":{"proposals":[]}}""")
        execute(jobId)
        runAllJobs()
        val unread = scan()
        assertTrue(finished(unread))
        assertEquals("failed: the run proposed nothing and named nothing it inspected", variable(unread, "outcome"))
        val run = runs.list(jobId, 1).single()
        assertEquals("failed: the run proposed nothing and named nothing it inspected", run.scanOutcome); assertNull(run.scanSummary)
        assertNotEquals(true, run.foundNothing); assertNull(run.filed)
        assertEquals(NOT_PLANNED, github.closedAs[repo to 100L])
    }

    @Test
    fun `a job run whose repo was disabled meanwhile ends failed with refactoring off and files nothing`() = EventRecorder(bus, repo).use { rec ->
        val jobId = enable()
        val job = store.get(jobId)!!
        val root = execute(jobId)
        // The instance waits at its first async step; the config is switched off before it runs.
        mvc.put("/repos/$repo/refactor") { contentType = MediaType.APPLICATION_JSON; content = """{"enabled":false}"""; with(TestTokens.bearer(login)) }.andExpect { status { isOk() } }
        assertNull(store.get(jobId), "the job row is gone")
        runAllJobs()

        assertTrue(finished(root))
        assertTrue(sandbox.started.isEmpty(), "no sandbox")
        assertEquals("failed: refactoring is off for $repo", variable(root, "outcome"))
        assertTrue(github.created.none { it.first == repo })
        val ended = rec.of("job", Duration.ofSeconds(2)).last { it.str("jobId") == jobId }
        assertEquals("ended", ended.state); assertEquals("failed: refactoring is off for $repo", ended.str("outcome")); assertEquals(job.name, ended.str("name"))
    }

    @Test
    fun `a scan whose repo was disabled after filing closes not planned and starts no sandbox`() {
        val jobId = enable()
        val root = execute(jobId)
        // Run only the job's step: the scan is started and waits at its first async step.
        repeat(3) { management.createJobQuery().processInstanceId(root).list().forEach { management.executeJob(it.id) } }
        assertTrue(finished(root), "the job filed the scan and ended")
        mvc.put("/repos/$repo/refactor") { contentType = MediaType.APPLICATION_JSON; content = """{"enabled":false}"""; with(TestTokens.bearer(login)) }.andExpect { status { isOk() } }
        runAllJobs()

        val scan = scan()
        assertTrue(finished(scan))
        assertTrue(sandbox.started.isEmpty(), "no sandbox")
        assertEquals("failed: refactoring is off for $repo", variable(scan, "outcome"))
        assertEquals(true, variable(scan, PrepareRefactorScanDelegate.SKIPPED))
        assertNull(variable(scan, CallableSmith.PROMPT))
        assertEquals(NOT_PLANNED, github.closedAs[repo to 100L])
    }
}

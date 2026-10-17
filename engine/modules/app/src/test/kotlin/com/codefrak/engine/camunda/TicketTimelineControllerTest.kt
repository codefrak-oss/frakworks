package com.codefrak.engine.camunda

import com.codefrak.engine.TestTokens
import com.codefrak.engine.ProcessFakes
import com.codefrak.engine.accounts.AiAccounts
import com.codefrak.engine.chore.FakeGitPush
import com.codefrak.engine.chore.FlowStarter
import com.codefrak.engine.chore.ScriptedSandbox
import com.codefrak.engine.github.IssueSnapshot
import com.codefrak.engine.github.issueUrl
import com.codefrak.engine.intake.AUTHORIZED_BY
import com.codefrak.engine.intake.IntakeQuestions
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.RunLabels
import com.codefrak.engine.smith.SmithSessions
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.ensureTestAccount
import com.codefrak.engine.smith.eventually
import com.codefrak.engine.intake.IntakeTickets
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.impl.util.ClockUtil
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A real chore run on scripted Smiths, with the engine's clock stepped a minute per job so
 * history has real gaps; jobs are run by hand. Its own repo: history outlives the reset.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ProcessFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class TicketTimelineControllerTest {
    @Autowired lateinit var timelines: TicketTimelineController
    @Autowired lateinit var links: IntakeTickets
    @Autowired lateinit var incidents: IncidentController
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var exits: com.codefrak.engine.smith.SmithExitNotifier
    @Autowired lateinit var starter: FlowStarter
    @Autowired lateinit var sandbox: ScriptedSandbox
    @Autowired lateinit var accounts: AiAccounts
    @Autowired lateinit var access: Access
    @Autowired lateinit var targetRepos: TargetRepos
    @Autowired lateinit var questions: IntakeQuestions
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var git: FakeGitPush
    @Autowired lateinit var pulls: com.codefrak.engine.chore.FakeGitHubPulls
    @Autowired lateinit var sessions: SmithSessions
    @Autowired lateinit var history: org.operaton.bpm.engine.HistoryService

    private val repo = "acme/timeline"
    private var now = 1_789_900_000_000L

    @BeforeEach
    fun reset() {
        runtime.createProcessInstanceQuery().rootProcessInstances().list().forEach { runtime.deleteProcessInstance(it.id, "test reset") }
        accounts.ensureTestAccount()
        now = 1_789_900_000_000L
        ClockUtil.setCurrentTime(Date(now))
    }

    @AfterEach
    fun restore() { ClockUtil.reset(); accounts.remove("test-anthropic") }

    private fun tick(seconds: Long) { now += seconds * 1000; ClockUtil.setCurrentTime(Date(now)) }

    /** One job at a time, a minute apart, until none is left or `until` holds. */
    private fun runJobs(until: () -> Boolean = { false }) {
        repeat(300) {
            if (until()) return
            // Exited sandboxes reach their waiting runs through the backstop scan: no event stream in tests.
            exits.scanExits()
            val job = management.createJobQuery().withRetriesLeft().list().firstOrNull() ?: return
            tick(60)
            runCatching { management.executeJob(job.id) }
        }
        error("no quiescence in 300 jobs")
    }

    private fun json(owner: String, name: String, n: Long): JsonNode =
        mapper.valueToTree(timelines.timeline(owner, name, n).body)

    private fun timeline(n: Long) = json(repo.substringBefore('/'), repo.substringAfter('/'), n)

    private fun result(cost: Double, turns: Int) = """{"type":"result","total_cost_usd":$cost,"num_turns":$turns,"duration_ms":1000}"""
    private val done = """{"outcome":"done","title":"Tidy the readme","summary":"Tidied it.","addressed":[]}"""

    /**
     * A chore whose first work run fails, and its automatic rerun too, and sits as an incident for
     * ten minutes until retried, then works, is pushed, passes security, gets changes from the reviewer, and
     * is left while its round-2 work run is still running.
     */
    private fun reworkedChore(issue: Long): String {
        targetRepos.add(repo)
        access.list().forEach { access.remove(it.login, null) }
        access.add("bob", "test"); access.add("alice", "test")
        sandbox.reset("work" to done,
            "security" to """{"outcome":"passed","summary":"fine","findings":[]}""",
            "review" to """{"outcome":"changes","findings":[{"ask":"fix the typo","file":"README.md","line":3}]}""",
            "work" to done)
        sandbox.failNext = 2
        sandbox.logByMode["security"] = result(0.31, 9); sandbox.logByMode["review"] = result(0.62, 15)
        val chore = starter.start("chore", repo, IssueSnapshot(issue, "Tidy the readme", "## Goal\n\nTidy.\n\nTier: 5\n", "author"), "test")!!
        runJobs()
        val incident = runtime.createIncidentQuery().list().first { it.processInstanceId == chore }
        tick(600)
        incidents.retry(incident.id, RetryRequest(), TestTokens.asLogin("alice"))
        runJobs(until = { sandbox.started.size == 6 })
        tick(300)
        return chore
    }

    @Test
    fun `each smith run is a step, rework adds a round, and a running run is open`() {
        reworkedChore(31)
        val t = timeline(31)
        val steps = t["steps"].toList()
        assertEquals(listOf("coding", "coding", "coding", "security", "correctness", "coding"), steps.map { it["role"].asString() })
        assertEquals(listOf(1, 1, 1, 1, 1, 2), steps.map { it["round"].asInt() }, "the rework is round 2, the round-1 steps stay")
        assertEquals(listOf("passed", "changes", "in progress"), steps.drop(3).map { it["outcome"].asString() })
        assertEquals(listOf(0.31, 0.62), steps.slice(3..4).map { it["cost"].asDouble() })
        assertEquals(listOf(9, 15), steps.slice(3..4).map { it["turns"].asInt() })
        assertTrue(steps.all { it["model"].isString && it["effort"].isString }, "runModel/runEffort")
        assertTrue(steps.none { it["catchUp"].asBoolean() }, "a round opened by a work push is not a catch-up")

        val running = steps.last()
        assertTrue(running["running"].asBoolean())
        assertTrue(running["end"].isNull)
        assertTrue(running["cost"].isNull, "no cost known yet")
        val sinceStart = now - running["start"].asLong() * 1000
        assertTrue(running["durationMs"].asLong() in (sinceStart - 1000)..sinceStart, "measured to now")
        steps.dropLast(1).forEach { s ->
            assertTrue(!s["running"].asBoolean() && s["end"].isNumber)
            assertEquals((s["end"].asLong() - s["start"].asLong()) * 1000, s["durationMs"].asLong(), "wall-clock, not the Smith's own durationMs")
        }

        val ticket = t["ticket"]
        assertEquals(31, ticket["number"].asInt())
        assertEquals("Tidy the readme", ticket["title"].asString())
        assertEquals("OPEN", ticket["state"].asString())
        assertEquals(2, ticket["round"].asInt())
        assertEquals("chore", ticket["kind"].asString())
        assertEquals(5, ticket["tier"].asInt())
    }

    private fun assistant(id: String, output: Int) =
        """{"type":"assistant","message":{"id":"$id","model":"claude-opus-5-5","usage":{"input_tokens":3,"output_tokens":$output,""" +
            """"cache_read_input_tokens":1000,"cache_creation_input_tokens":50}},"parent_tool_use_id":null}"""

    @Test
    fun `a running step has its live turns and tokens and no cost, and once collect result has run only the result's own`() {
        reworkedChore(42)
        val runId = runtime.createProcessInstanceQuery().processDefinitionKey(TicketTimelineController.SMITH_RUN).list().single().id
        var running = timeline(42)["steps"].last()
        assertTrue(running["running"].asBoolean())
        assertTrue(running["cost"].isNull && running["turns"].isNull && running["tokens"].isNull, "no usable event yet: $running")

        sessions.open(RunLabels(runId, 42, repo, "claude-opus-5-5", "work", "engine"))
        sessions.offer(runId, """{"type":"system","subtype":"init"}""")
        sessions.offer(runId, assistant("msg_1", 100))
        sessions.offer(runId, assistant("msg_2", 20))
        running = timeline(42)["steps"].last()
        assertTrue(running["running"].asBoolean())
        assertTrue(running["cost"].isNull, "no live cost: cost is the result's, at the end")
        assertEquals(2, running["turns"].asInt())
        assertEquals(listOf(6L, 120L, 2000L, 100L), listOf("input", "output", "cacheRead", "cacheWrite").map { running["tokens"][it].asLong() })
        timeline(42)["steps"].toList().dropLast(1).forEach { assertTrue(it["tokens"].isNull, "a finished step has no tokens: $it") }
        sessions.close(runId)
        eventually("the lines stored") { sessions.lines(runId).size == 3 }

        // Whatever runs after the collect in the same job finds a script for the next Smith.
        sandbox.script.getOrPut("security") { ArrayDeque() }.addLast("""{"outcome":"passed","summary":"fine","findings":[]}""")
        sandbox.script.getOrPut("review") { ArrayDeque() }.addLast("""{"outcome":"approved","findings":[]}""")
        val collected = { history.createHistoricActivityInstanceQuery().processInstanceId(runId).activityId("task-collect-result").finished().count() > 0 }
        runJobs(until = collected)
        assertTrue(collected())
        assertEquals(2L, sessions.live(runId)?.turns, "the stored lines would still give a tally")
        val step = timeline(42)["steps"].toList().single { it["round"].asInt() == 2 && it["role"].asString() == "coding" }
        assertTrue(step["turns"].isNull && step["cost"].isNull, "the result's own: this run's log had no result event: $step")
        assertTrue(step["tokens"].isNull, "never a live figure once collect result has run: $step")
        sessions.forget(runId)
    }

    @Test
    fun `a round opened by merging main into the branch marks its security and review runs as catch-up, the work round's not`() {
        targetRepos.add(repo)
        val passed = """{"outcome":"passed","summary":"fine","findings":[]}"""
        val approved = """{"outcome":"approved","findings":[]}"""
        sandbox.reset("work" to done, "security" to passed, "review" to approved, "security" to passed, "review" to approved)
        git.merges.clear()
        // Behind and clean merges as is; GitHub's 405 then brings the catch-up round on the same clean merge.
        pulls.conflictNext = 1
        repeat(2) { git.merges.addLast(com.codefrak.engine.github.MergeResult.Clean("head-1", "merge-1", "merge bundle".toByteArray())) }
        starter.start("chore", repo, IssueSnapshot(35, "Tidy the readme", "## Goal\n\nTidy.\n\nTier: 5\n", "author"), "test")!!
        runJobs()

        val steps = timeline(35)["steps"].toList()
        assertEquals(listOf("coding", "security", "correctness", "security", "correctness"), steps.map { it["role"].asString() })
        assertEquals(listOf(1, 1, 1, 2, 2), steps.map { it["round"].asInt() }, "a merge push still starts a round")
        assertEquals(listOf(false, false, false, true, true), steps.map { it["catchUp"].asBoolean() },
            "round 2's security and review follow a merge from main, not a work push")
    }

    @Test
    fun `the gaps are waits, an incident is stuck with who can retry it, and totals add up`() {
        reworkedChore(32)
        val t = timeline(32)
        val waits = t["waits"].toList()
        val steps = t["steps"].toList()

        val stuck = waits.single { it["cause"].asString() == "stuck" }
        assertTrue(stuck["durationMs"].asLong() >= 600_000, "stuck from the incident until the retry")
        assertEquals("alice, bob", stuck["payload"]["retrier"].asString())
        assertTrue(":" in stuck["payload"]["incident"].asString(), stuck["payload"]["incident"].asString())
        assertTrue(waits.filter { it["cause"].asString() == "engine" }.any { it["payload"]["steps"].size() > 0 })
        assertEquals(setOf("stuck", "engine"), waits.map { it["cause"].asString() }.toSet())

        // Steps and waits tile the span: each wait starts where the step or wait before it ended.
        val pieces = (steps.map { it["start"].asLong() to (it["end"].takeIf { e -> e.isNumber }?.asLong() ?: (now / 1000)) } +
            waits.map { it["start"].asLong() to it["end"].asLong() }).sortedBy { it.first }
        pieces.zipWithNext().forEach { (a, b) -> assertEquals(a.second, b.first, "no hole or overlap between $a and $b") }

        val totals = t["totals"]
        val long = { k: String -> totals[k].asLong() }
        assertEquals(long("elapsedMs"), long("workingMs") + long("waitingMs"))
        assertEquals(long("waitingMs"), long("waitingPersonMs") + long("stuckMs") + long("ciMs") + long("engineMs"))
        assertEquals(0L, long("ciMs"), "no engine check configured: no CI wait")
        assertEquals(waits.sumOf { it["durationMs"].asLong() }, long("waitingMs"))
        assertEquals(stuck["durationMs"].asLong(), long("stuckMs"))
        assertEquals(0.93, totals["costUsd"].asDouble(), 1e-9)
        assertEquals(steps.sumOf { if (it["cost"].isNull) 0.0 else it["cost"].asDouble() }, totals["costUsd"].asDouble(), 1e-9)
    }

    /** An intake waiting for `authorized` ten minutes, then on the user's answer to a question. */
    private fun waitingIntake(issue: Long): String {
        val intake = runtime.createProcessInstanceByKey("intake").startBeforeActivity("event-intake-authorized")
            .setVariable("issueUrl", issueUrl(repo, issue)).execute().id
        links.opened(intake, repo, issue, issueUrl(repo, issue))
        tick(600)
        runtime.createProcessInstanceModification(intake).startBeforeActivity("user-task-answer-question")
            .cancelAllForActivity("event-intake-authorized").execute()
        questions.ask(intake, repo, issue, "Which readme?")
        tick(300)
        return intake
    }

    private val draft = """"kind":"chore","ticket":{"title":"Tidy the readme","body":"## Goal\n\nTidy.\n","tier":5,"priority":"p3","points":3}"""

    /** An intake auto-authorized at `gateway-authorized` (no wait), whose refine ask runs to a filed chore (or `refined`). */
    private fun autoAuthorizedIntake(issue: Long, refined: String = """{"outcome":"file",$draft}"""): String {
        targetRepos.add(repo)
        sandbox.reset("refine" to refined)
        val intake = runtime.createProcessInstanceByKey("intake").startBeforeActivity("gateway-authorized")
            .setVariable("issueUrl", issueUrl(repo, issue))
            .setVariable(Vars.REPO, repo).setVariable(Vars.ISSUE, issue)
            .setVariable(AUTHORIZED_BY, "lundmatt1")
            .execute().id
        runJobs()
        return intake
    }

    @Test
    fun `an auto-authorized intake's refine run is a step, not hidden behind its wrapper activities, with no authorization wait`() {
        val intake = autoAuthorizedIntake(36)
        val t = timeline(36)
        val steps = t["steps"].toList()
        assertEquals(listOf("refine"), steps.map { it["role"].asString() }, "the refine run keeps its own mode as role")
        val refine = steps.single()
        assertTrue(refine["durationMs"].asLong() > 0)
        assertEquals((refine["end"].asLong() - refine["start"].asLong()) * 1000, refine["durationMs"].asLong())

        val waits = t["waits"].toList()
        assertTrue(waits.none { it["cause"].asString() == "person-authorization" },
            "gateway-authorized is a routing decision, not a wait: auto-authorized skipped event-intake-authorized entirely")
        assertTrue(waits.none { it["start"].asLong() < refine["end"].asLong() },
            "the whole call-refine-ask span is one step: no 0-3s engine waits from its wrapper activities before or inside it")
        // After it, the asyncBefore boundaries on task-start-work and task-close-intake (#1802) are real job hand-offs.
        assertTrue(waits.all { it["cause"].asString() == "engine" }, "only engine hand-offs follow the refine run: $waits")
    }

    @Test
    fun `an intake that filed its ticket unlabeled on the insufficient-permissions outcome is blocked, with the refiner's blocker as its summary`() {
        val reason = "The CI signed-release feature needs a mac code-signing secret. It also needs hosts outside the fleet's scope."
        val intake = autoAuthorizedIntake(37, """{"outcome":"insufficient-permissions",$draft,"reason":"$reason","blocker":"Needs a mac code-signing secret and hosts outside the fleet's scope"}""")
        assertNull(runtime.createIncidentQuery().processInstanceId(intake).singleResult(), "no incident: the ticket was filed unlabeled")
        assertEquals(0L, runtime.createProcessInstanceQuery().processInstanceId(intake).count(),
            "the intake closed; the banner reads its stored refine result")
        val blocked = timeline(37)["ticket"]["blocked"]
        assertEquals("insufficient-permissions", blocked["kind"].asString())
        assertEquals("Needs a mac code-signing secret and hosts outside the fleet's scope", blocked["summary"].asString(), "verbatim")
        assertEquals(reason, blocked["reason"].asString())
        val fixture = mapper.readTree(javaClass.getResourceAsStream("/fixture-ticket-timeline.json")!!.readAllBytes())
        sameShape(fixture["_blockedIntake"]["ticket"], timeline(37)["ticket"], "blocked intake ticket")
    }

    @Test
    fun `an insufficient-permissions result with no blocker (from before it existed) gets a summary of at most 20 words from its reason`() {
        val reason = "The CI signed-release feature needs a mac code-signing secret the fleet does not hold, and it needs the build-runner " +
            "VM and a container on the hypervisor, hosts outside the fleet's scope. A human must grant both."
        autoAuthorizedIntake(38, """{"outcome":"insufficient-permissions",$draft,"reason":"$reason"}""")
        val blocked = timeline(38)["ticket"]["blocked"]
        val summary = blocked["summary"].asString()
        assertEquals("The CI signed-release feature needs a mac code-signing secret the fleet does not hold, and it needs the build-runner VM…", summary)
        assertTrue(summary.removeSuffix("…").split(" ").size <= 20, summary)
        assertEquals(reason, blocked["reason"].asString())
    }

    @Test
    fun `an intake that filed its ticket, or is waiting on the user, has no blocked key`() {
        autoAuthorizedIntake(39)
        assertTrue(!timeline(39)["ticket"].has("blocked"), "file")
        waitingIntake(40)
        assertTrue(!timeline(40)["ticket"].has("blocked"), "no refine result yet")
        autoAuthorizedIntake(41, """{"outcome":"ask","question":"Which readme?"}""")
        assertTrue(!timeline(41)["ticket"].has("blocked"), "ask")
    }

    @Test
    fun `a user task waits on the user's question, the authorized catch on their authorization`() {
        val intake = waitingIntake(33)
        var t = timeline(33)
        val waits = t["waits"].toList()
        assertEquals(listOf("person-authorization", "person-question"), waits.map { it["cause"].asString() })
        assertEquals(listOf(600_000L, 300_000L), waits.map { it["durationMs"].asLong() }, "the open wait ends now, as a number")
        assertEquals("event-intake-authorized", waits[0]["payload"]["steps"][0]["name"].asString())
        assertEquals("Which readme?", waits[1]["payload"]["question"].asString())
        assertTrue(waits[1]["payload"]["answer"].isNull)
        assertEquals(listOf("intake", "OPEN", "1"), listOf("kind", "state", "round").map { t["ticket"][it].asString() })
        assertTrue(t["steps"].isEmpty)
        assertEquals(900_000L, t["totals"]["waitingPersonMs"].asLong())
        assertEquals(0L, t["totals"]["workingMs"].asLong())

        questions.answer(questions.pending(intake)!!.id, "The engine's.", "lundmatt1")
        t = timeline(33)
        assertEquals("The engine's.", t["waits"][1]["payload"]["answer"].asString())
    }

    /**
     * #355: a feature ticket whose only Smith run so far is its design run, parked on the
     * user's question. The design run is a smith-run called under the feature root, which
     * carries the ticket's repo/issue, so the timeline has it as a step. Asked under another
     * owner (a repo's pre-move name) it is a 404: the engine keys history by the recorded name.
     */
    @Test
    fun `a feature ticket whose only run is its design run has that run as a step`() {
        targetRepos.add(repo)
        access.add("bob", "test")
        val plan = """"plan":{"summary":"s","files":["a"],"stages":[{"title":"t","detail":"d"}],"tests":["t"],"acceptance":["a"]}"""
        sandbox.reset("design" to """{"outcome":"planned",$plan,"questions":[{"question":"Which?","assumption":"a","confidence":0.4,"impact":"high"}]}""")
        val feature = starter.start("feature", repo, IssueSnapshot(353, "A strip", "## Goal\n\nA strip.\n\nTier: 5\n", "author"), "bob",
            extra = mapOf("featureMode" to "confirm"))!!
        runJobs()
        assertEquals(listOf("user-task-answer-question"), runtime.getActiveActivityIds(feature))
        assertEquals(listOf("design"), sandbox.started.map { it.mode })

        val response = timelines.timeline(repo.substringBefore('/'), repo.substringAfter('/'), 353)
        assertEquals(HttpStatus.OK, response.statusCode, response.body.toString())
        val t = timeline(353)
        assertEquals("feature", t["ticket"]["kind"].asString())
        assertEquals(listOf("design"), t["steps"].toList().map { it["role"].asString() })

        assertEquals(HttpStatus.NOT_FOUND, timelines.timeline("someone-else", repo.substringAfter('/'), 353).statusCode)
    }

    @Test
    fun `an issue with no process instances is a 404 with a JSON error`() {
        val response = timelines.timeline("acme", "timeline", 404)
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        val body = mapper.valueToTree<JsonNode>(response.body)
        assertTrue(body["error"].isString, body.toString())
    }

    /** Fields whose value may be null where the fixture happens to show a value. */
    private val nullable = setOf("title", "tier", "end", "model", "effort", "cost", "turns", "tokens", "outcome", "answer", "retrier", "incident", "question",
        "conclusion", "check", "account")

    @Test
    fun `the response has exactly the fixture's field names and JSON types at every level`() {
        val fixture = mapper.readTree(javaClass.getResourceAsStream("/fixture-ticket-timeline.json")!!.readAllBytes())
        reworkedChore(34)
        waitingIntake(35)
        val chore = timeline(34); val intake = timeline(35)

        assertEquals(fixture.propertyNames().asSequence().filter { !it.startsWith("_") }.toSet(), chore.propertyNames().asSequence().toSet())
        sameShape(fixture["ticket"], chore["ticket"], "ticket")
        sameShape(fixture["ticket"], intake["ticket"], "ticket")
        sameShape(fixture["totals"], chore["totals"], "totals")
        val fixtureStep = fixture["steps"].first { !it["running"].asBoolean() }
        val fixtureRunning = fixture["steps"].first { it["running"].asBoolean() }
        chore["steps"].forEach { sameShape(if (it["running"].asBoolean()) fixtureRunning else fixtureStep, it, "step") }
        val waits = chore["waits"].toList() + intake["waits"].toList()
        assertEquals(setOf("person-authorization", "person-question", "stuck", "engine"), waits.map { it["cause"].asString() }.toSet())
        waits.forEach { w -> sameShape(fixture["waits"].first { it["cause"] == w["cause"] }, w, "wait ${w["cause"]}") }
    }

    private fun sameShape(expected: JsonNode, actual: JsonNode, path: String) {
        assertEquals(expected.propertyNames().asSequence().toSet(), actual.propertyNames().asSequence().toSet(), "$path: field names")
        for (name in expected.propertyNames()) {
            val e = expected[name]; val a = actual[name]
            assertNotNull(a, "$path.$name")
            if (a.isNull && (e.isNull || name in nullable)) continue
            assertEquals(e.nodeType, a.nodeType, "$path.$name: ${a} for ${e}")
            if (e.isObject) sameShape(e, a, "$path.$name")
            if (e.isArray && e.size() > 0) a.forEach { sameShape(e[0], it, "$path.$name[]") }
        }
    }
}

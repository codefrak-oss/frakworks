package com.codefrak.engine.bug

import com.codefrak.engine.TestTokens
import com.codefrak.engine.ProcessFakes
import com.codefrak.engine.finished
import com.codefrak.engine.brief
import com.codefrak.engine.ci.CheckRun
import com.codefrak.engine.ci.CI_CHECK_MESSAGE
import com.codefrak.engine.ci.CiCheckWaits
import com.codefrak.engine.ci.FakeCheckRuns
import com.codefrak.engine.smith.ensureTestAccount
import com.codefrak.engine.smith.dropTestAccount
import com.codefrak.engine.accounts.AiAccounts
import com.codefrak.engine.chore.FakeGitHubPulls
import com.codefrak.engine.chore.FakeGitPush
import com.codefrak.engine.ci.ConflictRounds
import com.codefrak.engine.github.MergeResult
import com.codefrak.engine.chore.ScriptedSandbox
import com.codefrak.engine.intake.CancelRequest
import com.codefrak.engine.intake.TicketRecorder
import com.codefrak.engine.intake.IntakeController
import com.codefrak.engine.intake.IntakeQuestions
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.TargetRepos
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RuntimeService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import kotlin.test.assertEquals
import kotlin.test.assertNull
import com.codefrak.engine.smith.Vars
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(ProcessFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class BugProcessTest {
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var hitlpRequests: com.codefrak.engine.hitlp.HitlpRequests
    @Autowired lateinit var hitlpPoller: com.codefrak.engine.hitlp.HitlpPoller

    /** Decides the HITLP wait [id] is parked on, as [by], through the shared stub and the poller. */
    private fun decide(id: String, answer: String, by: String = "lundmatt1") =
        com.codefrak.engine.hitlp.decideWait(com.codefrak.engine.hitlp.SharedHitlpStub.STUB, hitlpRequests, hitlpPoller, id, answer, by)

    @Autowired lateinit var accounts: AiAccounts
    @Autowired lateinit var history: HistoryService
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var exits: com.codefrak.engine.smith.SmithExitNotifier
    @Autowired lateinit var github: TicketRecorder
    @Autowired lateinit var pulls: FakeGitHubPulls
    @Autowired lateinit var git: FakeGitPush
    @Autowired lateinit var sandbox: ScriptedSandbox
    @Autowired lateinit var runs: FakeCheckRuns
    @Autowired lateinit var waits: CiCheckWaits
    @Autowired lateinit var intakes: IntakeController
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var access: Access
    @Autowired lateinit var questions: IntakeQuestions

    private val json = jacksonObjectMapper()

    @AfterEach
    fun dropTestAccount() { accounts.dropTestAccount() }

    @BeforeEach
    fun clear() { pulls.opened.clear(); pulls.merged.clear(); pulls.conflictNext = 0; git.reset(); runs.reset(); accounts.ensureTestAccount()
        repos.add("acme/tickets"); repos.update("acme/tickets", "engine", listOf("engine/"), true) }

    private fun runAllJobs() {
        repeat(100) {
            // Exited sandboxes reach their waiting runs through the backstop scan: no event stream in tests.
            exits.scanExits()
            // The engine check's 3-hour timeout and its re-trigger cycle fire only when a test says so.
            val timeouts = listOf("event-ci-check-timeout", "event-ci-check-retrigger")
                .flatMap { management.createJobQuery().activityId(it).list() }.map { it.id }.toSet() +
                // So do the post-deployment check's: a flow that reaches call-post-deploy-check waits for a deploy.
                management.createJobQuery().timers().processDefinitionKey("post-deploy-check").list().map { it.id }
            val jobs = management.createJobQuery().withRetriesLeft().list().filterNot { it.id in timeouts }
            if (jobs.isEmpty()) return
            jobs.forEach { runCatching { management.executeJob(it.id) } }
        }
        error("no quiescence in 100 job rounds")
    }

    private fun start(issue: Long, extra: Map<String, Any> = emptyMap()) = runtime.createMessageCorrelation("bug filed")
        .setVariables(mapOf("repo" to "acme/tickets", "issue" to issue) + extra).correlateStartMessage().also { runAllJobs() }

    private fun ended(id: String) = history.createHistoricActivityInstanceQuery().processInstanceId(id).activityType("noneEndEvent")
        .list().single().activityId

    private val reproduced = """{"outcome":"reproduced","rootCause":"IssueBoard never joins an intake's instance","repro":"curl /issues | jq '.[] | select(.number==46) | .flow'  # null","testable":true}"""
    private val done = """{"outcome":"done","title":"Join intakes to their flow","summary":"Joined them."}"""
    private fun done(addressed: String) =
        """{"outcome":"done","title":"Join intakes to their flow","summary":"Joined them.","addressed":[{"finding":"$addressed","how":"fixed"}]}"""
    private val passed = """{"outcome":"passed","summary":"fine","findings":[]}"""
    private val approved = """{"outcome":"approved","summary":"fine","findings":[]}"""

    @Test
    fun `a bug is reproduced, fixed through the chore's reviews, merged, verified on main and closed`() {
        sandbox.reset("reproduce" to reproduced, "work" to done, "security" to passed, "review" to approved,
            "verify" to """{"outcome":"fixed","summary":"The repro now prints the instance."}""")
        val bug = start(21)

        assertTrue(history.finished(bug.id))
        assertEquals("end-bug-fixed", ended(bug.id))
        assertEquals(listOf("reproduce", "work", "security", "review", "verify"), sandbox.started.map { it.mode })
        assertEquals(emptyMap(), sandbox.started[0].inputs, "the reproduce run has nothing to be told yet")
        sandbox.started.drop(1).forEach {
            assertEquals("IssueBoard never joins an intake's instance", brief(it)!!.reproduction!!["rootCause"], "${it.mode} is told the reproduction")
        }
        assertTrue("engine: bug " in pulls.opened.single()[4], "the PR names the bug flow")
        assertEquals(7L, brief(sandbox.started[2])!!.pr?.number, "security reviews the round after its push")
        assertEquals(listOf(7L to "Join intakes to their flow"), pulls.merged)
        assertEquals("acme/tickets" to 21L, github.closed.last())
        val closing = github.commentsOn(21).last()
        assertTrue("Merged #7" in closing && "The repro now prints the instance." in closing, closing)
    }

    /** A bug without the flaky-test variable still reproduces and verifies: the test above, `a bug is reproduced, ...`. */
    @Test
    fun `a flaky-test bug runs no reproduce or verify run and closes as an optimistic fix naming the suspected cause`() {
        sandbox.reset("work" to """{"outcome":"done","title":"Clear the shared map","summary":"Cleared the map between tests.\nSuspected cause: a static map shared across test classes."}""",
            "security" to passed, "review" to approved)
        val bug = start(140, mapOf(Vars.FLAKY_TEST to true))

        assertTrue(history.finished(bug.id))
        assertEquals("end-bug-fixed", ended(bug.id))
        assertEquals(listOf("work", "security", "review"), sandbox.started.map { it.mode })
        assertTrue(brief(sandbox.started[0])!!.flakyTest, "the work run is told")
        assertNull(brief(sandbox.started[0])!!.reproduction, "no reproduction")
        assertEquals("acme/tickets" to 140L, github.closed.last())
        val closing = github.commentsOn(140).last()
        assertTrue("Optimistic flaky-test fix, not verified. Suspected cause: a static map shared across test classes." in closing, closing)
        assertTrue("Verified on the default branch" !in closing, closing)
    }

    @Test
    fun `a flaky-test bug whose work summary names no cause closes saying so`() {
        sandbox.reset("work" to done, "security" to passed, "review" to approved)
        val bug = start(141, mapOf(Vars.FLAKY_TEST to true))
        assertEquals("end-bug-fixed", ended(bug.id))
        assertTrue("Optimistic flaky-test fix, not verified. Suspected cause: not named" in github.commentsOn(141).last())
    }

    @Autowired lateinit var mcpServers: com.codefrak.engine.repos.RepoMcpServers

    /** Flows left waiting at call-post-deploy-check, deleted after each test: their waits would outlive it. */
    private val checking = mutableListOf<String>()

    @AfterEach
    fun deleteChecking() { checking.forEach { runCatching { runtime.deleteProcessInstance(it, "test done") } }; checking.clear() }

    private fun addOpsServer() { mcpServers.put("acme/tickets", "ops", mapOf("kind" to "http", "audience" to "smith", "role" to "ops", "url" to "https://ops.example/mcp"), "test") }

    /** Runs [block] with an ops-role MCP server on acme/tickets, removed after: the repo row is shared by every flow test. */
    private fun <T> withOpsServer(block: () -> T): T {
        addOpsServer()
        try { return block() } finally { mcpServers.delete("acme/tickets", "ops") }
    }

    /** The PR body's post-deployment check (bots/smith.md, "The post-deployment check"), as a JSON string's escapes. */
    private val postDeploySection = """\n\n## POST-DEPLOYMENT-CHECK\n\nThe next answer lands in the new table.\nRe-check: 4h"""

    @Test
    fun `a bug whose pr has a post-deployment check is merged, verified, and waits at the check with the ticket open`() = withOpsServer {
        sandbox.reset("reproduce" to reproduced, "work" to """{"outcome":"done","title":"Join intakes to their flow","summary":"Joined them.$postDeploySection"}""",
            "security" to passed, "review" to approved, "verify" to """{"outcome":"fixed","summary":"The repro now prints the instance."}""")
        val bug = start(128).also { checking += it.id }

        assertEquals(listOf("reproduce", "work", "security", "review", "verify"), sandbox.started.map { it.mode })
        assertEquals(listOf(7L to "Join intakes to their flow"), pulls.merged)
        assertEquals(listOf("call-post-deploy-check"), runtime.getActiveActivityIds(bug.id).filter { it.startsWith("call-") })
        assertTrue(("acme/tickets" to 128L) !in github.closed, "the post-deployment check closes the ticket")
    }

    @Test
    fun `the reproduce run's files under work-out-repro reach the work and verify runs at work-in-repro, modes kept`() {
        val script = "#!/bin/sh\ncurl -s /issues | jq '.[0].flow'\n".toByteArray()
        val lib = ByteArray(3000) { (it % 251).toByte() }
        sandbox.reset("reproduce" to """{"outcome":"reproduced","rootCause":"c","repro":"/work/in/repro/x.sh  # prints null","testable":true}""",
            "work" to done, "security" to passed, "review" to approved, "verify" to """{"outcome":"fixed","summary":"ok"}""")
        sandbox.reproFiles["x.sh"] = script to 0b111_101_101
        sandbox.reproFiles["lib/libnss3.so"] = lib to 0b110_100_100
        val bug = start(31)

        assertEquals("end-bug-fixed", ended(bug.id))
        assertEquals(emptyMap(), sandbox.started[0].inputs.filterKeys { it.startsWith("repro/") })
        listOf("work", "verify").forEach { mode ->
            val spec = sandbox.started.single { it.mode == mode }
            assertEquals(script.toList(), spec.inputs["repro/x.sh"]?.toList(), "$mode gets the script")
            assertEquals(lib.toList(), spec.inputs["repro/lib/libnss3.so"]?.toList(), "$mode gets the nested file")
            assertEquals(0b111_101_101, spec.inputModes["repro/x.sh"], "$mode: the script stays executable")
            assertEquals(0b110_100_100, spec.inputModes["repro/lib/libnss3.so"])
        }
        val verify = sandbox.started.single { it.mode == "verify" }
        val cited = (brief(verify)!!.reproduction!!["repro"] as String).substringBefore(' ')
        assertEquals(script.toList(), sandbox.fileIn(verify, cited)?.toList(), "the path the brief cites holds the file in the verify run")
        listOf("security", "review").forEach { mode ->
            assertTrue(sandbox.started.single { it.mode == mode }.inputs.keys.none { it.startsWith("repro/") }, "$mode gets no repro files")
        }
    }

    @Test
    fun `a reproduce run with no repro files leaves no variable and starts the later runs without them`() {
        sandbox.reset("reproduce" to reproduced, "work" to done, "security" to passed, "review" to approved,
            "verify" to """{"outcome":"fixed","summary":"ok"}""")
        val bug = start(32)

        assertEquals("end-bug-fixed", ended(bug.id))
        assertEquals(0L, history.createHistoricVariableInstanceQuery().variableName("reproFiles").list()
            .count { it.rootProcessInstanceId == bug.id || it.processInstanceId == bug.id }.toLong(), "no reproFiles variable")
        sandbox.started.forEach { spec -> assertTrue(spec.inputs.keys.none { it.startsWith("repro/") }) }
        assertNull(brief(sandbox.started[1])!!.reproduction!!["filesDropped"])
    }

    @Test
    fun `a reproduce run past the repro cap is collected without its files, and the reproduction says so`() {
        sandbox.reset("reproduce" to reproduced, "work" to done, "security" to passed, "review" to approved,
            "verify" to """{"outcome":"fixed","summary":"ok"}""")
        sandbox.reproFiles["electron"] = ByteArray(10) to 0b111_101_101
        sandbox.reproTooLarge = true
        val bug = start(33)

        assertEquals("end-bug-fixed", ended(bug.id))
        sandbox.started.forEach { spec -> assertTrue(spec.inputs.keys.none { it.startsWith("repro/") }, "${spec.mode} gets no repro files") }
        listOf("work", "verify").forEach { mode ->
            val dropped = brief(sandbox.started.single { it.mode == mode })!!.reproduction!!["filesDropped"] as? String
            assertTrue(dropped != null && "32 MiB" in dropped, "$mode is told the files were dropped: $dropped")
        }
    }

    @Test
    fun `a bug's done with no commits reruns the work once with a rules finding, and the flow goes on`() {
        sandbox.reset("reproduce" to reproduced, "work" to done, "work" to done, "security" to passed, "review" to approved,
            "verify" to """{"outcome":"fixed","summary":"The repro now prints the instance."}""")
        sandbox.unbundledWork = 1
        val bug = start(77)

        assertTrue(history.finished(bug.id))
        assertEquals(listOf("reproduce", "work", "work", "security", "review", "verify"), sandbox.started.map { it.mode })
        assertEquals(listOf("rules-0-1"), brief(sandbox.started[2])!!.findings.map { it.id })
        assertTrue(github.commentsOn(77).any { "rejected the work result" in it })
    }

    @Test
    fun `a bug with neither a repro nor a cause is labelled needs-repro and left open, untouched`() {
        sandbox.reset("reproduce" to """{"outcome":"not-reproduced","summary":"Ran the repro; the flow was there."}""")
        val bug = start(22)

        assertTrue(history.finished(bug.id))
        assertEquals("end-not-reproduced", ended(bug.id))
        assertEquals(listOf("reproduce"), sandbox.started.map { it.mode })
        assertEquals(emptyList(), git.pushes)
        assertTrue(github.closed.none { it.second == 22L }, "not reproducing it here is no proof it is gone")
        assertEquals(Triple("acme/tickets", 22L, listOf("needs-repro")), github.labelled.last())
        assertTrue("Ran the repro; the flow was there." in github.commentsOn(22).last())
    }

    @Test
    fun `a fix that merged but still reproduces leaves the ticket open`() {
        sandbox.reset("reproduce" to reproduced, "work" to done, "security" to passed, "review" to approved,
            "verify" to """{"outcome":"still-broken","summary":"Still null."}""")
        val bug = start(23)

        assertTrue(history.finished(bug.id))
        assertEquals("end-still-broken", ended(bug.id))
        assertTrue(github.closed.none { it.second == 23L }, "left open for a human")
        assertTrue("Still null." in github.commentsOn(23).last())
    }

    @Test
    fun `a bug understood from the report and logs is fixed without a test and closed as fixed but not verified`() {
        sandbox.reset(
            "reproduce" to """{"outcome":"understood","rootCause":"CarPlay takes the audio session and the app never reactivates it",""" +
                """"evidence":"the log shows AVAudioSessionInterruption began with no ended","testable":false,"untestableWhy":"needs a car"}""",
            "work" to done, "security" to passed, "review" to approved,
            "verify" to """{"outcome":"unverified","summary":"Needs CarPlay; nothing here can show it."}""")
        val bug = start(25)

        assertTrue(history.finished(bug.id))
        assertEquals("end-bug-fixed", ended(bug.id))
        assertEquals(false, brief(sandbox.started[1])!!.reproduction!!["testable"], "the work run is told no test is expected")
        assertEquals("acme/tickets" to 25L, github.closed.last())
        val closing = github.commentsOn(25).last()
        assertTrue("Fixed, but not verified" in closing && "Needs CarPlay" in closing, closing)
    }

    @Test
    fun `an understood bug whose evidence is an object goes on to the fix, the evidence reaching the work run as JSON (#235)`() {
        val evidence = """{"logsOrCode":["ReadResultDelegate.kt: the reproduce branch parses before problems()"],"ticketMatch":"the instance sits at remove sandbox","confidence":"high"}"""
        sandbox.reset(
            "reproduce" to """{"outcome":"understood","rootCause":"evidence typed String","evidence":$evidence,"testable":true}""",
            "work" to done, "security" to passed, "review" to approved,
            "verify" to """{"outcome":"fixed","summary":"Parses now."}""")
        val bug = start(235)

        assertTrue(history.finished(bug.id), "no incident at read result")
        assertEquals("end-bug-fixed", ended(bug.id))
        assertEquals(listOf("reproduce", "work", "security", "review", "verify"), sandbox.started.map { it.mode })
        val told = brief(sandbox.started[1])!!.reproduction!!["evidence"]
        assertEquals(json.readValue<Map<String, Any?>>(evidence), told, "the work run gets the evidence as the object it was, not an escaped string")
    }

    @Test
    fun `a not-reproduced summary given as a list is commented as a readable block, not a one-line blob`() {
        sandbox.reset("reproduce" to """{"outcome":"not-reproduced","summary":["ran the repro","the flow was there"]}""")
        val bug = start(236)

        assertEquals("end-not-reproduced", ended(bug.id))
        val comment = github.commentsOn(236).last()
        assertTrue("```json\n[" in comment && "\"ran the repro\"" in comment && "\\\"" !in comment, comment)
    }

    @Test
    fun `a branch field of the wrong type stops at read result naming the field`() {
        sandbox.reset("reproduce" to """{"outcome":"reproduced","rootCause":"x","repro":"run it","testable":"yes"}""")
        val bug = start(237)
        val incident = runtime.createIncidentQuery().incidentMessageLike("the smith's result breaks the rules: smith/schemas/reproduce.schema.json: /testable%").list().single()
        assertTrue("boolean" in incident.incidentMessage, incident.incidentMessage)
        assertEquals(listOf("reproduce"), sandbox.started.map { it.mode })
        runtime.deleteProcessInstance(bug.id, "test done")
    }

    @Test
    fun `understood without evidence, or reproduced without saying whether it is testable, stops before any work`() {
        sandbox.reset("reproduce" to """{"outcome":"understood","rootCause":"a hunch","testable":false,"untestableWhy":"a car"}""")
        val hunch = start(26)
        assertEquals(1L, runtime.createIncidentQuery().incidentMessageLike("%needs the evidence%").count())
        runtime.deleteProcessInstance(hunch.id, "test done")

        sandbox.reset("reproduce" to """{"outcome":"reproduced","rootCause":"x","repro":"run it"}""")
        val unsaid = start(27)
        assertEquals(1L, runtime.createIncidentQuery().incidentMessageLike("%needs testable%").count())
        assertEquals(listOf("reproduce"), sandbox.started.map { it.mode })
        runtime.deleteProcessInstance(unsaid.id, "test done")
    }

    @Test
    fun `a reproduction without a repro stops before any work`() {
        sandbox.reset("reproduce" to """{"outcome":"reproduced","rootCause":"somewhere","testable":true}""")
        val bug = start(24)

        assertEquals(1L, runtime.createIncidentQuery().incidentMessageLike("%needs a repro%").count())
        assertEquals(listOf("reproduce"), sandbox.started.map { it.mode })
        runtime.deleteProcessInstance(bug.id, "test done")
    }

    @Test
    fun `a reproduce smith's question and a work smith's question are each recorded, answered, and run again told every answer`() {
        sandbox.reset("reproduce" to """{"outcome":"ask","question":"Which board, desktop or web?"}""", "reproduce" to reproduced,
            "work" to """{"outcome":"ask","question":"Backfill old rows?"}""", "work" to done, "security" to passed, "review" to approved,
            "verify" to """{"outcome":"fixed","summary":"The repro now prints the instance."}""")
        val bug = start(29)
        repos.add("acme/tickets"); access.add("lundmatt1", "test")

        // The reproduce run asked: a wait on the user, not an incident, with the question recorded under the bug.
        assertEquals(listOf("user-task-answer-reproduce"), runtime.getActiveActivityIds(bug.id))
        assertEquals(0L, runtime.createIncidentQuery().processInstanceId(bug.id).count())
        val first = questions.pending(bug.id)!!
        assertEquals(Triple("acme/tickets", 29L, "Which board, desktop or web?"), Triple(first.repo, first.issue, first.question))
        assertTrue(questions.open().any { it.id == first.id }, "GET /questions and the board's pendingQuestion show it")
        assertTrue("The reproduce Smith has a question (1)" in github.commentsOn(29).last())
        decide(bug.id, "desktop")
        runAllJobs()

        // The reproduce run ran again told the answer, reproduced it, and the work run asked in turn.
        assertEquals(listOf("reproduce", "reproduce", "work"), sandbox.started.map { it.mode })
        assertEquals("desktop", brief(sandbox.started[1])!!.answers.single().answer)
        assertEquals(listOf("user-task-answer-work"), runtime.getActiveActivityIds(bug.id))
        assertEquals("Backfill old rows?", questions.pending(bug.id)!!.question)
        assertTrue("The work Smith has a question (2)" in github.commentsOn(29).last())
        decide(bug.id, "no")
        runAllJobs()

        assertTrue(history.finished(bug.id))
        assertEquals("end-bug-fixed", ended(bug.id))
        assertEquals(listOf("reproduce", "reproduce", "work", "work", "security", "review", "verify"), sandbox.started.map { it.mode })
        assertEquals(listOf("desktop", "no"), brief(sandbox.started[3])!!.answers.map { it.answer })
        assertNull(questions.pending(bug.id))
    }

    @Test
    fun `a bug parked on an incident is cancelled by someone with access, and says so on the ticket`() {
        sandbox.reset("reproduce" to """{"outcome":"reproduced","rootCause":"x","testable":true}""")
        val bug = start(28)
        repos.add("acme/tickets"); access.add("lundmatt1", "test")

        val refused = assertFailsWith<ResponseStatusException> { intakes.cancel(bug.id, CancelRequest(reason = "no"), TestTokens.asLogin("stranger")) }
        assertEquals(403, refused.statusCode.value())

        val state = intakes.cancel(bug.id, CancelRequest(reason = "the smith image was stale"), TestTokens.asLogin("lundmatt1"))
        assertTrue(state.ended)
        assertEquals(0L, runtime.createIncidentQuery().processInstanceId(bug.id).count())
        assertTrue("cancelled this bug" in github.commentsOn(28).last() && "stale" in github.commentsOn(28).last())
        assertEquals(404, assertFailsWith<ResponseStatusException> { intakes.cancel(bug.id, CancelRequest(reason = "again"), TestTokens.asLogin("lundmatt1")) }.statusCode.value())
    }

    // ---- bringing the PR up to date: the chore's step, shared (engine/README.md, "Bringing the PR up to date") ----

    private fun clean(n: Int) = MergeResult.Clean("head-$n", "merge-$n", "merge bundle $n".toByteArray())
    /** Behind and clean merges as is; GitHub's 405 then brings the catch-up round on the same clean merge. */
    private fun cleanAfter405(n: Int) { pulls.conflictNext = 1; git.merges.addLast(clean(n)); git.merges.addLast(clean(n)) }
    private val fixed = """{"outcome":"fixed","summary":"The repro now prints the instance."}"""

    @Test
    fun `after github's 405 a bug branch that merges cleanly is pushed, security-reviewed with the merge entry, reviewed again, then merged and verified`() {
        sandbox.reset("reproduce" to reproduced, "work" to done, "security" to passed, "review" to approved,
            "security" to passed, "review" to approved, "verify" to fixed)
        cleanAfter405(1)
        val bug = start(31)

        assertTrue(history.finished(bug.id))
        assertEquals("end-bug-fixed", ended(bug.id))
        assertEquals(listOf("reproduce", "work", "security", "review", "security", "review", "verify"), sandbox.started.map { it.mode })
        val security = sandbox.started[4]
        assertEquals("merge bundle 1", String(security.inputs.getValue("commits.bundle")), "security reviews the pushed merge")
        assertEquals(2, brief(security)!!.round, "the catch-up was pushed as round 2 before its security review")
        assertEquals(mapOf("default" to "main", "oldHead" to "head-1", "merge" to "merge-1"), brief(security)!!.merge)
        assertNull(brief(sandbox.started[5])!!.merge, "only a security run is told of the merge")
        assertEquals(listOf("bundle of container-2", "merge bundle 1"), git.pushes.map { it.third })
        assertEquals(3, git.mergeCalls.size, "rebase, then again after the 405, then after the merge's review")
        assertEquals(listOf(7L to "Join intakes to their flow"), pulls.merged)
        assertEquals("acme/tickets" to 31L, github.closed.last())
    }

    @Test
    fun `a bug in a repo with security reviews off goes from the push to review pr, merges and is verified`() {
        repos.add("acme/tickets"); repos.update("acme/tickets", null, emptyList(), false)
        try {
            sandbox.reset("reproduce" to reproduced, "work" to done, "review" to approved, "review" to approved, "verify" to fixed)
            cleanAfter405(1)
            val bug = start(38)

            assertTrue(history.finished(bug.id))
            assertEquals("end-bug-fixed", ended(bug.id))
            assertEquals(listOf("reproduce", "work", "review", "review", "verify"), sandbox.started.map { it.mode },
                "no security run, the catch-up's included")
            assertEquals(listOf("bundle of container-2", "merge bundle 1"), git.pushes.map { it.third })
            assertEquals(listOf(7L to "Join intakes to their flow"), pulls.merged)
            assertEquals("acme/tickets" to 38L, github.closed.last())
        } finally {
            repos.update("acme/tickets", null, emptyList(), true)
        }
    }

    @Test
    fun `a conflicted bug merge goes back to a fix round with a finding naming the files`() {
        sandbox.reset("reproduce" to reproduced, "work" to done, "security" to passed, "review" to approved,
            "work" to done("rebase-1-1"), "security" to passed, "review" to approved, "verify" to fixed)
        git.merges.addLast(MergeResult.Conflict(listOf("a.txt", "b.txt")))
        val bug = start(32)

        assertTrue(history.finished(bug.id))
        assertEquals("end-bug-fixed", ended(bug.id))
        assertEquals(listOf("reproduce", "work", "security", "review", "work", "security", "review", "verify"), sandbox.started.map { it.mode })
        val finding = brief(sandbox.started[4])!!.findings.single()
        assertEquals("rebase-1-1", finding.id)
        assertEquals("rebase", finding.review)
        assertEquals(ConflictRounds.conflictAsk("main", listOf("a.txt", "b.txt")), finding.ask)
        assertTrue(finding.ask.startsWith("merge conflict with the default branch in a.txt, b.txt"), finding.ask)
        assertEquals(listOf("bundle of container-2", "bundle of container-5"), git.pushes.map { it.third })
        assertEquals(listOf(7L to "Join intakes to their flow"), pulls.merged)
    }

    @Test
    fun `the third conflict parks the bug on an incident, and nothing is merged or verified`() {
        sandbox.reset("reproduce" to reproduced, "work" to done, "security" to passed, "review" to approved,
            "work" to done("rebase-1-1"), "security" to passed, "review" to approved,
            "work" to done("rebase-2-1"), "security" to passed, "review" to approved)
        repeat(10) { git.merges.addLast(MergeResult.Conflict(listOf("a.txt"))) } // the conflict stays for the failed job's retries
        val bug = start(33)

        assertEquals(3, sandbox.started.count { it.mode == "work" }, "the first round and two conflict rounds")
        val incident = runtime.createIncidentQuery().processInstanceId(bug.id).singleResult()
        assertEquals("task-rebase-pr", incident.activityId)
        assertTrue("after 2 conflict rounds" in incident.incidentMessage, incident.incidentMessage)
        assertEquals(emptyList(), pulls.merged)
        assertTrue(sandbox.started.none { it.mode == "verify" })
        assertTrue(github.closed.none { it.second == 33L })
        runtime.deleteProcessInstance(bug.id, "test done")
    }

    @Test
    fun `github's merge conflict at the bug's merge pr goes back through the rebase step`() {
        sandbox.reset("reproduce" to reproduced, "work" to done, "security" to passed, "review" to approved,
            "security" to passed, "review" to approved, "verify" to fixed)
        pulls.conflictNext = 1
        git.merges.addLast(MergeResult.Current) // git says current, GitHub then refuses: main moved in between
        git.merges.addLast(clean(2))
        val bug = start(34)

        assertTrue(history.finished(bug.id))
        assertEquals("end-bug-fixed", ended(bug.id))
        assertEquals(3, git.mergeCalls.size, "rebase, then again after the 405, then after the merge's review")
        assertEquals(listOf("bundle of container-2", "merge bundle 2"), git.pushes.map { it.third })
        assertEquals(listOf(7L to "Join intakes to their flow"), pulls.merged)
    }

    // ---- the engine check after the push (bpmn/reusable-ci-check.bpmn; engine/README.md, "Chore") ----

    private fun waitingForCheck(id: String) = runtime.createExecutionQuery().messageEventSubscriptionName(CI_CHECK_MESSAGE).list()
        .any { history.createHistoricProcessInstanceQuery().processInstanceId(it.processInstanceId).singleResult().rootProcessInstanceId == id }
    private fun completed(sha: String, conclusion: String, id: Long) {
        runs.bySha[sha] = CheckRun(id, "completed", conclusion, "https://github.com/acme/tickets/actions/runs/1/job/$id")
    }

    @Test
    fun `a bug branch touching engine parks at the check after the push, before any review, and a pass goes on to security and review`() {
        git.paths = listOf("engine/src/main/kotlin/X.kt")
        sandbox.reset("reproduce" to reproduced, "work" to done, "security" to passed, "review" to approved, "verify" to fixed)
        val bug = start(41)

        assertTrue(waitingForCheck(bug.id), "parked at the check with no Smith running")
        assertEquals(listOf("reproduce", "work"), sandbox.started.map { it.mode }, "no security run before the check")
        assertEquals(emptyList(), waits.poll(), "no check run yet")
        completed("sha-1", "success", 11)
        assertEquals(41L, waits.poll().single().issue)
        runAllJobs()

        assertTrue(history.finished(bug.id))
        assertEquals(listOf("reproduce", "work", "security", "review", "verify"), sandbox.started.map { it.mode })
        assertEquals(listOf(7L to "Join intakes to their flow"), pulls.merged)
    }

    @Test
    fun `a failed check on a bug branch goes back to the work with the ci finding, and the fix is checked again`() {
        git.paths = listOf("engine/src/main/kotlin/X.kt")
        sandbox.reset("reproduce" to reproduced, "work" to done, "work" to done("ci-1-1"), "security" to passed,
            "review" to approved, "verify" to fixed)
        val bug = start(42)
        completed("sha-1", "failure", 11)
        waits.poll(); runAllJobs()

        assertEquals(listOf("reproduce", "work", "work"), sandbox.started.map { it.mode }, "no security or correctness review of a failing branch")
        val finding = brief(sandbox.started.last { it.mode == "work" })!!.findings.single()
        assertEquals("ci-1-1", finding.id)
        assertEquals("ci", finding.review)
        assertTrue("ChoreProcessTest > a chore is worked() FAILED" in finding.ask, finding.ask)
        assertTrue(waitingForCheck(bug.id), "the fix waits for its own check")

        completed("sha-2", "success", 12)
        waits.poll(); runAllJobs()
        assertTrue(history.finished(bug.id))
        assertEquals(listOf("reproduce", "work", "work", "security", "review", "verify"), sandbox.started.map { it.mode })
    }

    @Test
    fun `a bug branch touching nothing under engine skips the check`() {
        sandbox.reset("reproduce" to reproduced, "work" to done, "security" to passed, "review" to approved, "verify" to fixed)
        val bug = start(43)

        assertTrue(history.finished(bug.id))
        assertEquals(listOf("reproduce", "work", "security", "review", "verify"), sandbox.started.map { it.mode })
        assertEquals(emptyList(), runs.asked, "no check was read")
    }

    // ---- amend_ticket (#1362) ----

    @Autowired lateinit var ticketAmender: com.codefrak.engine.intake.TicketAmender

    @Test
    fun `an amendment at the reproduce stops it and reproduces again, and one at the work runs the fix again`() {
        access.add("lundmatt1", "test")
        sandbox.reset("reproduce" to reproduced, "reproduce" to reproduced, "work" to done, "work" to done, "security" to passed,
            "review" to approved, "verify" to fixed)
        sandbox.holdMode = "reproduce"
        val bug = start(61)
        val amended = ticketAmender.amend("acme/tickets", 61, "it also fails on Windows", "lundmatt1")
        assertEquals("restarted", amended.work)
        assertEquals("the stopped reproduce run's unpushed commits", amended.lost)
        assertEquals(listOf("container-1"), sandbox.stopped)
        sandbox.holdMode = "work"
        runAllJobs()
        assertEquals(listOf("reproduce", "reproduce", "work"), sandbox.started.map { it.mode })
        assertEquals(listOf("it also fails on Windows"), brief(sandbox.started[1])!!.amendments.map { it.text })

        val again = ticketAmender.amend("acme/tickets", 61, "and on macOS", "lundmatt1")
        assertEquals("restarted", again.work)
        assertEquals("the stopped work run's unpushed commits", again.lost)
        runAllJobs()
        assertTrue(history.finished(bug.id))
        assertEquals(listOf("reproduce", "reproduce", "work", "work", "security", "review", "verify"), sandbox.started.map { it.mode })
        assertEquals(listOf("it also fails on Windows", "and on macOS"), brief(sandbox.started[3])!!.amendments.map { it.text })
    }

    @Test
    fun `an amendment while the engine check runs cancels the check and runs the work again on the same PR`() {
        access.add("lundmatt1", "test")
        git.paths = listOf("engine/src/main/kotlin/X.kt")
        sandbox.reset("reproduce" to reproduced, "work" to done, "work" to done)
        val bug = start(62)
        assertTrue(waitingForCheck(bug.id))
        val amended = ticketAmender.amend("acme/tickets", 62, "keep the old flag", "lundmatt1")
        assertEquals("restarted", amended.work)
        assertNull(amended.lost, "no Smith runs while the check waits")
        assertTrue(sandbox.stopped.isEmpty())
        runAllJobs()
        assertEquals(listOf("reproduce", "work", "work"), sandbox.started.map { it.mode })
        assertEquals(7L, brief(sandbox.started[2])!!.pr?.number)
        assertTrue(waitingForCheck(bug.id), "the new round's push waits on its own check")
        assertEquals(emptyList(), pulls.closed)
    }

}

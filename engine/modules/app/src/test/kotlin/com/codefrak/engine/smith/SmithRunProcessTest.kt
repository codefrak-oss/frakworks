package com.codefrak.engine.smith

import com.codefrak.engine.intake.tickets
import com.codefrak.engine.TestTokens
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.events.EventRecorder
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RuntimeService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class SmithRunProcessTest {
    @TestConfiguration
    class Fakes {
        @Bean @Primary fun sandbox() = FakeSandbox()
        @Bean @Primary fun github() = com.codefrak.engine.intake.TicketRecorder()
        @Bean @Primary fun tickets(github: com.codefrak.engine.intake.TicketRecorder) = github.tickets()
    }

    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var history: HistoryService
    @Autowired lateinit var sandbox: FakeSandbox
    @Autowired lateinit var exits: SandboxExitNotifier
    @Autowired lateinit var accounts: com.codefrak.engine.accounts.AiAccounts
    @Autowired lateinit var usages: com.codefrak.engine.accounts.AccountUsages
    @Autowired lateinit var intakes: com.codefrak.engine.intake.IntakeController
    @Autowired lateinit var repos: com.codefrak.engine.repos.TargetRepos
    @Autowired lateinit var access: com.codefrak.engine.repos.Access
    @Autowired lateinit var stats: SmithRunStats
    @Autowired lateinit var repository: org.operaton.bpm.engine.RepositoryService
    @Autowired lateinit var tasks: org.operaton.bpm.engine.TaskService
    @Autowired lateinit var bus: EngineEvents
    @Autowired lateinit var tierTable: com.codefrak.engine.tier.TierTable

    @org.junit.jupiter.api.BeforeEach
    fun freshSandboxes() = sandbox.reset()

    /** A run that exits 0 is `success` only with a result.json; without one it is `no-result`. */
    private fun wroteResult(vararg issues: Long) = issues.forEach {
        sandbox.files["container-$it" to OUTPUT_PATH] = """{"outcome":"done","summary":"s"}""".toByteArray()
    }

    /** Cancel deletes the activities that would remove the sandbox, so it stops it first (#134). */
    @Test
    fun `cancelling a run stops its sandbox, and one not started yet calls none`() {
        repos.add("acme/widgets"); access.add("lundmatt1", "test")
        val vars = mapOf(Vars.REPO to "acme/widgets", Vars.ISSUE to 46L, Vars.MODEL to "claude-sonnet-5",
            Vars.EFFORT to "low", Vars.DRY_RUN to true)

        val running = runtime.startProcessInstanceByKey("smith-run", vars)
        management.executeJob(management.createJobQuery().processInstanceId(running.id).singleResult().id)
        val handle = runtime.getVariable(running.id, Vars.HANDLE) as String
        intakes.cancel(running.id, com.codefrak.engine.intake.CancelRequest(reason = "test"), TestTokens.asLogin("lundmatt1"))
        assertTrue(handle in sandbox.stopped)

        val stoppedBefore = sandbox.stopped.size
        val removedBefore = sandbox.removed.size
        val idle = runtime.startProcessInstanceByKey("smith-run", vars)
        intakes.cancel(idle.id, com.codefrak.engine.intake.CancelRequest(reason = "test"), TestTokens.asLogin("lundmatt1"))
        assertEquals(stoppedBefore, sandbox.stopped.size)
        assertEquals(removedBefore, sandbox.removed.size)
    }

    /** Drives the async continuations by hand: no job executor in tests. Returns when the instance has no job left. */
    private fun runJobs(instanceId: String) {
        repeat(50) {
            val jobs = management.createJobQuery().processInstanceId(instanceId).list()
            if (jobs.isEmpty()) return
            jobs.forEach { management.executeJob(it.id) }
        }
        error("process did not quiesce in 50 job rounds")
    }

    /**
     * Drives a run to its end the way a flow test does: once the run has a sandbox, the sandbox is marked exited
     * with `exit` (no die event: the backstop scan finds it) before each job round. For the tests that only
     * care about what `collect result` records, not how the exit arrived.
     */
    private fun runAllJobs(instanceId: String, exit: Long = 0) {
        repeat(50) {
            (runtime.createProcessInstanceQuery().processInstanceId(instanceId).singleResult()
                ?.let { runtime.getVariable(instanceId, Vars.HANDLE) as? String })
                ?.takeIf { it !in sandbox.states }?.let { sandbox.states[it] = SandboxStatus(false, exit) }
            exits.scanExits("test")
            val jobs = management.createJobQuery().processInstanceId(instanceId).list()
            if (jobs.isEmpty()) return
            jobs.forEach { management.executeJob(it.id) }
        }
        error("process did not finish in 50 job rounds")
    }

    /** The notifier is off in this context (`exit-scan.enabled=false`); each test subscribes the way startup would. */
    private fun <T> watching(block: () -> T): T =
        sandbox.watchExits({ exits.deliver(it).let { d -> if (d == SmithExitNotifier.Delivery.NOT_WAITING) error("die before the run waited: $it") } }).use { block() }

    private fun start(issue: Long, vararg more: Pair<String, Any>) = runtime.startProcessInstanceByKey("smith-run", mapOf(
        Vars.REPO to "acme/widgets", Vars.ISSUE to issue, Vars.MODEL to "claude-sonnet-5",
        Vars.EFFORT to "low", Vars.DRY_RUN to true, *more,
    ))

    private fun vars(instanceId: String) = history.createHistoricVariableInstanceQuery().processInstanceId(instanceId).list()
        .associate { it.name to it.value }

    private fun activityCount(instanceId: String, activity: String) =
        history.createHistoricActivityInstanceQuery().processInstanceId(instanceId).activityId(activity).count()

    private fun finished(instanceId: String) =
        history.createHistoricProcessInstanceQuery().processInstanceId(instanceId).finished().count() == 1L

    private fun waitingOnExit(instanceId: String) =
        runtime.createExecutionQuery().processInstanceId(instanceId).messageEventSubscriptionName(SMITH_EXITED_MESSAGE).count() == 1L

    /** (a) The event path: the die event alone takes the run to the end, no backstop scan involved. */
    @Test
    fun `a run starts a sandbox, waits for its exit event, collects and removes it`() = watching {
        val before = sandbox.started.size   // the fake is one bean for the class: the other tests' runs are in it
        val instance = start(42L)
        runJobs(instance.id)                // the start job; the run now waits on the message
        assertEquals(before + 1, sandbox.started.size)
        // A work run is not told the repo's token's scopes (SMITH_GH_SCOPES): only a refine run is.
        assertEquals(SmithSpec(instance.id, "acme/widgets", 42, "claude-sonnet-5", "low", true), sandbox.started[before])
        assertTrue(waitingOnExit(instance.id), "the run waits at event-smith-exited")
        assertTrue("container-42" !in sandbox.removed)

        wroteResult(42L)
        sandbox.exit("container-42", 0)     // Docker's die event
        runJobs(instance.id)                // the asyncBefore continuation into remove sandbox

        assertTrue("container-42" in sandbox.removed)
        val vars = vars(instance.id)
        assertEquals("success", vars[Vars.RESULT])
        assertEquals(0L, vars[Vars.EXIT_CODE])
        val tail = vars[Vars.LOG_TAIL] as String
        assertTrue(tail.endsWith("smith: done (container-42)") && tail.length == Vars.TAIL_CHARS)
        assertEquals(50_000 + "smith: done (container-42)".length, (vars[Vars.LOG] as ByteArray).size)
        assertTrue(finished(instance.id))
    }

    /** The stream (engine/README.md, "Events"): `session` `started` once the start commits, `ended` once per run at collect. */
    @Test
    fun `the stream says a session started with its labels and ended with what collect recorded`() = watching {
        EventRecorder(bus).use { rec ->
            sandbox.files["container-77" to MEMORY_PEAK_PATH] = "${3L * 1024 * 1024}\n".toByteArray()
            val instance = start(77L)
            runJobs(instance.id)
            val started = rec.of("session").single()
            assertEquals(listOf("started", "acme/widgets", 77L, instance.id, instance.id, "work", "claude-sonnet-5"),
                listOf(started.state, started.str("repo"), started.long("number"), started.str("runId"), started.str("instance"), started.str("mode"), started.str("model")))
            assertEquals("issue-77-work-${instance.id}.log" to "engine", started.str("filename") to started.str("host"))
            assertNull(started.str("account"), "a dry run bills no account")
            assertNull(started.long("exitCode"))

            wroteResult(77L)
            sandbox.exit("container-77", 0)
            runJobs(instance.id)
            val frames = rec.of("session")
            assertEquals(2, frames.size, "one started, one ended")
            val ended = frames.last()
            assertEquals(listOf("ended", instance.id, 0L, 3L, "issue-77-work-${instance.id}.log"),
                listOf(ended.state, ended.str("runId"), ended.long("exitCode"), ended.long("peakMiB"), ended.str("filename")))
            assertNotNull(ended.long("seconds"))
        }
    }

    /** A mode `job` run (engine/README.md, "Custom jobs and the SDK"): no issue, a job id, the prompt in its brief, and nothing written to GitHub. */
    @Test
    fun `a job run with no issue starts a smith-job sandbox, posts no comment and collects`() = watching {
        EventRecorder(bus).use { rec ->
            val before = sandbox.started.size
            val comments = github.comments.size
            val instance = runtime.startProcessInstanceByKey("smith-run", mapOf(
                Vars.REPO to "acme/widgets", Vars.JOB_ID to "job-1", Vars.PROMPT to "Say hello.", Vars.MODE to "job",
                Vars.MODEL to "claude-sonnet-5", Vars.EFFORT to "low", Vars.DRY_RUN to true))
            runJobs(instance.id)
            val spec = sandbox.started[before]
            assertEquals(listOf("job", null, "job-1", "acme/widgets"), listOf(spec.mode, spec.issue, spec.jobId, spec.repo))
            assertEquals("Say hello.", tools.jackson.module.kotlin.jacksonObjectMapper().readValue(spec.inputs.getValue(BRIEF_FILE), Brief::class.java).prompt)
            val started = rec.of("session").single()
            assertNull(started.long("number"), "no ticket")
            assertEquals("job-1" to "job-job-1-job-${instance.id}.log", started.str("jobId") to started.str("filename"))

            sandbox.files["container-null" to OUTPUT_PATH] = """{"outcome":"done","summary":"hello"}""".toByteArray()
            sandbox.exit("container-null", 0)
            runJobs(instance.id)
            assertTrue(finished(instance.id))
            assertEquals("success", vars(instance.id)[Vars.RESULT])
            assertEquals(comments, github.comments.size, "no GitHub write for a run with no issue")
            val ended = rec.of("session").last()
            assertEquals("ended" to "job-1", ended.state to ended.str("jobId"))
            assertNull(stats.get(instance.id)?.issue, "the stats row has no issue")
        }
    }

    /** (b) The backstop: no event ever fires, the scan sees the sandbox exited and collects it; a scan of a running sandbox does nothing. */
    @Test
    fun `the backstop scan collects an exited sandbox whose event was missed`() {
        val instance = start(46L)
        runJobs(instance.id)
        val idle = exits.scanExits("test")
        assertTrue(idle.checked >= 1 && idle.collected == 0, "still running: the scan leaves it waiting, got $idle")
        assertTrue(waitingOnExit(instance.id))

        sandbox.states["container-46"] = SandboxStatus(false, 3)   // exited, but the die event never came
        val report = exits.scanExits("test")
        assertEquals(1, report.collected)
        runJobs(instance.id)

        assertTrue(finished(instance.id))
        assertEquals("failed", vars(instance.id)[Vars.RESULT])
        assertEquals(3L, vars(instance.id)[Vars.EXIT_CODE])
        assertTrue("container-46" in sandbox.removed)
    }

    /** A container Docker no longer knows reads as exited with code -1, never as a run that waits forever. */
    @Test
    fun `the backstop treats a gone container as exited with code -1`() {
        val instance = start(47L)
        runJobs(instance.id)
        sandbox.gone += "container-47"
        assertEquals(1, exits.scanExits("test").collected)
        runJobs(instance.id)
        assertTrue(finished(instance.id))
        assertEquals(-1L, vars(instance.id)[Vars.EXIT_CODE])
        assertEquals("failed", vars(instance.id)[Vars.RESULT])
    }

    /** A status check that fails (the daemon unreachable) is not an exit: the run keeps waiting, then collects normally. */
    @Test
    fun `the backstop leaves a run waiting when its status cannot be checked`() {
        val instance = start(149L)
        runJobs(instance.id)
        sandbox.unreachable += "container-149"
        assertEquals(0, exits.scanExits("test").collected)
        runJobs(instance.id)
        assertFalse(finished(instance.id))
        sandbox.unreachable.clear()
        sandbox.states["container-149"] = SandboxStatus(false, 0)
        assertEquals(1, exits.scanExits("test").collected)
        runJobs(instance.id)
        assertTrue(finished(instance.id))
        assertEquals(0L, vars(instance.id)[Vars.EXIT_CODE])
    }

    /** (c) Event then backstop, and backstop then event, on one exit: one collect, one remove, one log read. */
    @Test
    fun `the event and the backstop racing on one exit collect exactly once`() = watching {
        // Event first, then the backstop sees the same exit.
        val first = start(48L)
        runJobs(first.id)
        sandbox.exit("container-48", 0)
        assertEquals(0, exits.scanExits("test").collected, "the backstop finds nothing left to collect")
        assertEquals(SmithExitNotifier.Delivery.ALREADY, exits.deliver(SandboxExit("container-48", first.id, 0)), "a second event is a no-op")
        runJobs(first.id)
        assertOnce(first.id, "container-48")

        // Backstop first, then the (late) event arrives.
        val second = start(49L)
        runJobs(second.id)
        sandbox.states["container-49"] = SandboxStatus(false, 1)
        assertEquals(1, exits.scanExits("test").collected)
        assertEquals(SmithExitNotifier.Delivery.ALREADY, exits.deliver(SandboxExit("container-49", second.id, 1)))
        runJobs(second.id)
        assertOnce(second.id, "container-49")
        assertEquals(1L, vars(second.id)[Vars.EXIT_CODE])
    }

    private fun assertOnce(instanceId: String, handle: String) {
        assertTrue(finished(instanceId))
        assertEquals(1L, activityCount(instanceId, "task-collect-result"), "one collect result")
        assertEquals(1L, activityCount(instanceId, "task-remove-sandbox"), "one remove sandbox")
        assertEquals(1, sandbox.logged.count { it == handle }, "one logs() call")
        assertEquals(1, sandbox.removed.count { it == handle }, "one remove() call")
    }

    /** A die that lands before the start job committed its subscription is reported as such, and the backstop collects later. */
    @Test
    fun `an exit event before the run waits is left to the backstop`() {
        val instance = start(50L)
        // The start job has not run: no sandbox, no subscription yet.
        assertEquals(SmithExitNotifier.Delivery.NOT_WAITING, exits.deliver(SandboxExit("container-50", instance.id, 0)))
        runJobs(instance.id)
        assertTrue(waitingOnExit(instance.id))
        sandbox.states["container-50"] = SandboxStatus(false, 0)
        assertEquals(1, exits.scanExits("test").collected)
        runJobs(instance.id)
        assertTrue(finished(instance.id))
    }

    /** An exit with no run id (a provider that cannot say) is correlated by the handle variable. */
    @Test
    fun `an exit without a run id is matched by its sandbox handle`() {
        val instance = start(51L)
        runJobs(instance.id)
        assertEquals(SmithExitNotifier.Delivery.COLLECTED, exits.deliver(SandboxExit("container-51", null, 0)))
        runJobs(instance.id)
        assertTrue(finished(instance.id))
        assertNotNull(vars(instance.id)[Vars.EXIT_CODE])
    }

    /**
     * A collect that throws (here a REPO variable that is not a String) used to run inside the exit's correlation:
     * the exit rolled back, nothing made an incident, and the run waited at `event-smith-exited` forever (#1668).
     * Now the exit commits on its own and the failing collect is a job that becomes a retryable incident.
     */
    @Test
    fun `an exit-0 sandbox whose collect throws leaves the wait and becomes an incident`() {
        val instance = start(169L)
        runJobs(instance.id)
        wroteResult(169L)
        runtime.setVariable(instance.id, Vars.REPO, 169L)
        sandbox.states["container-169"] = SandboxStatus(false, 0)

        assertEquals(SmithExitNotifier.Delivery.COLLECTED, exits.deliver(SandboxExit("container-169", instance.id, 0)))
        assertFalse(waitingOnExit(instance.id), "the exit is committed, whatever collect does")
        assertEquals(0L, runtime.getVariable(instance.id, Vars.EXIT_CODE))
        repeat(5) {
            management.createJobQuery().processInstanceId(instance.id).withRetriesLeft().list()
                .forEach { runCatching { management.executeJob(it.id) } }
        }
        assertEquals(1L, runtime.createIncidentQuery().processInstanceId(instance.id).count(), "a failing collect is an incident")

        runtime.setVariable(instance.id, Vars.REPO, "acme/widgets")
        val job = management.createJobQuery().processInstanceId(instance.id).singleResult()
        management.setJobRetries(job.id, 1)
        runJobs(instance.id)
        assertTrue(finished(instance.id))
        assertEquals("success", vars(instance.id)[Vars.RESULT])
    }

    /** With a usable account of the model's provider stored, the run bills to it and says so; a pinned one is kept. */
    @Test
    fun `a run picks an account for its model's provider unless the caller pinned one`() = watching {
        accounts.add("run-anthropic", "anthropic"); accounts.setCredential("run-anthropic", "sk-ant-oat01-x")
        accounts.add("run-openai", "openai"); accounts.setCredential("run-openai", "sk-x")
        try {
            val before = sandbox.started.size
            val picked = start(43L, Vars.MODEL to "gpt-6-astra")
            runJobs(picked.id); sandbox.exit("container-43", 0); runJobs(picked.id)
            assertEquals("run-openai", sandbox.started[before].account)
            assertEquals("run-openai", history.createHistoricVariableInstanceQuery()
                .processInstanceId(picked.id).variableName(Vars.BILLED_ACCOUNT).singleResult().value)
            assertEquals(0L, history.createHistoricVariableInstanceQuery()
                .processInstanceId(picked.id).variableName(Vars.ACCOUNT).count(), "the pick is not written as a pin")

            val pinned = start(44L, Vars.ACCOUNT to "run-anthropic")
            runJobs(pinned.id); sandbox.exit("container-44", 0); runJobs(pinned.id)
            assertEquals("run-anthropic", sandbox.started[before + 1].account)
        } finally {
            accounts.remove("run-anthropic"); accounts.remove("run-openai")
        }
    }

    /** The engine has no token of its own: a real run with no usable account never starts a sandbox. */
    @Test
    fun `a real run with no usable account fails before a sandbox starts`() {
        val before = sandbox.started.size
        val instance = start(45L, Vars.DRY_RUN to false)
        val job = management.createJobQuery().processInstanceId(instance.id).singleResult()
        val failure = runCatching { management.executeJob(job.id) }.exceptionOrNull()
        assertTrue(failure?.message?.contains("no usable ai account") == true, "got: $failure")
        assertEquals(before, sandbox.started.size)
    }

    /** meter.json as engine/smith/meter-proxy.py writes it. */
    private fun meterJson(status: Int, verdict: String) = """
        {"observedAt": "2026-09-25T10:00:00Z", "status": $status,
         "anthropic-ratelimit-unified-5h-utilization": "0.42", "anthropic-ratelimit-unified-5h-status": "$verdict",
         "anthropic-ratelimit-unified-5h-reset": "1790230200", "anthropic-ratelimit-unified-7d-utilization": "0.17",
         "anthropic-ratelimit-unified-7d-status": "allowed", "anthropic-ratelimit-unified-7d-reset": null,
         "anthropic-ratelimit-unified-status": "$verdict"}
    """.trimIndent().toByteArray()

    private fun runPinned(issue: Long, account: String, model: String = "claude-sonnet-5", mode: String? = null): String {
        val instance = runtime.startProcessInstanceByKey("smith-run", mapOf(
            Vars.REPO to "acme/widgets", Vars.ISSUE to issue, Vars.MODEL to model, Vars.ACCOUNT to account,
            Vars.EFFORT to "low", Vars.DRY_RUN to true,
        ) + listOfNotNull(mode?.let { Vars.MODE to it }))
        runAllJobs(instance.id)
        return instance.id
    }

    /** The sandbox's meter.json is copied out and recorded once, under the run's account (engine/README.md, "The usage meter"). */
    @Test
    fun `a run's meter json is collected and recorded under its account`() {
        sandbox.files["container-46" to METER_PATH] = meterJson(200, "allowed")
        val id = runPinned(46L, "meter-a")
        val u = usages.get("meter-a")!!
        assertEquals(0.42, u.fiveHourUtilization); assertEquals("allowed", u.status); assertEquals(200, u.httpStatus)
        assertEquals(0.17, u.sevenDayUtilization); assertNull(u.sevenDayReset)
        assertEquals(1, u.requests)
        assertEquals(java.time.Instant.parse("2026-09-25T10:00:00Z"), u.observedAt.toInstant())
        val meter = history.createHistoricVariableInstanceQuery().processInstanceId(id).variableName(Vars.METER).singleResult().value
        assertTrue((meter as ByteArray).contentEquals(sandbox.files["container-46" to METER_PATH]!!))

        sandbox.files["container-47" to METER_PATH] = meterJson(429, "rejected")
        runPinned(47L, "meter-a")
        assertEquals(2, usages.get("meter-a")!!.requests)
        assertEquals(429, usages.get("meter-a")!!.httpStatus); assertEquals("rejected", usages.get("meter-a")!!.status)
    }

    /** A dry run, or a Smith that never called Anthropic, leaves no meter.json: nothing is recorded. */
    @Test
    fun `a run with no meter json records nothing`() {
        val id = runPinned(48L, "meter-none")
        assertNull(usages.get("meter-none"))
        assertNull(history.createHistoricVariableInstanceQuery().processInstanceId(id).variableName(Vars.METER).singleResult().value)
    }

    @Test
    fun `an unreadable meter json records nothing and does not fail the run`() {
        sandbox.files["container-49" to METER_PATH] = "not json".toByteArray()
        wroteResult(49L)
        val id = runPinned(49L, "meter-junk")
        assertNull(usages.get("meter-junk"))
        assertEquals("success", history.createHistoricVariableInstanceQuery().processInstanceId(id).variableName(Vars.RESULT).singleResult().value)
    }

    // ---- peak memory (engine/README.md, "Smith runs") ----

    private fun peakVar(id: String) =
        history.createHistoricVariableInstanceQuery().processInstanceId(id).variableName(Vars.PEAK_MEMORY).singleResult()?.value

    @Test
    fun `collect records the sandbox's peak memory as a stats row and a variable`() {
        sandbox.files["container-70" to MEMORY_PEAK_PATH] = "123456789\n".toByteArray()
        val id = runPinned(70L, "peak-a")
        val row = stats.get(id)!!
        assertEquals(123456789L, row.peakBytes); assertEquals(false, row.oomKilled)
        assertEquals("smith-run", row.rootFlowKind); assertEquals("acme/widgets", row.repo); assertEquals(70L, row.issue)
        assertEquals("claude-sonnet-5", row.model); assertEquals("low", row.effort); assertEquals("peak-a", row.account)
        assertEquals(0L, row.exitCode); assertTrue(row.started != null && !row.ended!!.isBefore(row.started))
        assertEquals(123456789L, peakVar(id))
        assertEquals("work", row.mode, "no mode given: a work run, as the start delegate reads it")
    }

    /** The role goes on the stats row, for the issues board's stage estimates (engine/README.md, "The issues board"). */
    @Test
    fun `collect records the run's mode on its stats row`() {
        val id = runPinned(75L, "peak-d", mode = "review")
        assertEquals("review", stats.get(id)!!.mode)
    }

    @Test
    fun `an OOM-killed run records oom_killed true`() {
        sandbox.oom["container-71"] = true
        val id = runPinned(71L, "peak-b")
        assertEquals(true, stats.get(id)!!.oomKilled)
    }

    @Test
    fun `a missing or garbled peak records a null peak and leaves the result`() {
        sandbox.files["container-72" to MEMORY_PEAK_PATH] = "max".toByteArray()
        wroteResult(72L)
        val garbled = runPinned(72L, "peak-c")
        assertNull(stats.get(garbled)!!.peakBytes); assertNull(peakVar(garbled))
        assertEquals("success", history.createHistoricVariableInstanceQuery().processInstanceId(garbled).variableName(Vars.RESULT).singleResult().value)
        val missing = runPinned(73L, "peak-c")
        assertNull(stats.get(missing)!!.peakBytes)
    }

    @Autowired lateinit var jdbc: org.springframework.jdbc.core.simple.JdbcClient

    @Test
    fun `a failed stats insert leaves the collect and its result standing`() {
        jdbc.sql("ALTER TABLE engine.smith_run_stats ADD CONSTRAINT no_74 CHECK (issue <> 74)").update()
        wroteResult(74L)
        try {
            val id = runPinned(74L, "peak-d")
            assertNull(stats.get(id))
            assertEquals("success", history.createHistoricVariableInstanceQuery().processInstanceId(id).variableName(Vars.RESULT).singleResult().value)
        } finally {
            jdbc.sql("ALTER TABLE engine.smith_run_stats DROP CONSTRAINT no_74").update()
        }
    }

    // ---- cost, turns and duration (engine/README.md, "Smith runs") ----

    /** A closing `result` event as Claude's stream-json writes it, trimmed to the fields read. */
    private val resultLine = """{"type":"result","subtype":"success","is_error":false,"duration_ms":81234,""" +
        """"duration_api_ms":80000,"num_turns":17,"result":"done","total_cost_usd":1.2345}"""

    private fun runVar(id: String, name: String) =
        history.createHistoricVariableInstanceQuery().processInstanceId(id).variableName(name).singleResult()?.value

    /** The variables the run rows replaced (#1976): none of them is set by any delegate any more. */
    private val formerVariables = listOf("runModel", "runEffort", "runTier", "cost", "turns", "durationMs", "reviews")

    @Test
    fun `collect records cost, turns and duration from the closing result event on the run's rows, not as variables`() {
        sandbox.logText["container-80"] = """{"type":"system","subtype":"init"}""" + "\n" + resultLine + "\nsmith: done"
        val id = runPinned(80L, "cost-a")
        val row = stats.get(id)!!
        assertEquals(1.2345, row.cost); assertEquals(17L, row.turns); assertEquals(81234L, row.durationMs)
        formerVariables.forEach { assertNull(runVar(id, it), "$it is not a process variable") }
    }

    /** `start smith` writes the model, effort and tier the run was started with to its row (engine/README.md, "Smith runs"), nowhere else. */
    @Test
    fun `start smith records the run's model and effort on its row and sets no variable for them`() {
        val id = runPinned(85L, "model-a")
        val row = stats.get(id)!!
        assertEquals("claude-sonnet-5", row.model); assertEquals("low", row.effort)
        listOf("runModel", "runEffort", "runTier").forEach { assertNull(runVar(id, it), "$it is not a process variable") }
    }

    @Autowired lateinit var ledger: com.codefrak.engine.accounts.ClaudeUsageLedger
    @Autowired lateinit var consumption: com.codefrak.engine.accounts.ConsumptionController

    /** engine/README.md, "Consumption ledger": the row is read back from the table, not from the tally collect forgot. */
    @Test
    fun `collect writes the run's cost, duration and tokens to the ledger under its role, in its window only`() {
        val assistant = { id: String, input: Int ->
            """{"type":"assistant","parent_tool_use_id":null,"message":{"id":"$id","usage":{"input_tokens":$input,"output_tokens":20,""" +
                """"cache_read_input_tokens":300,"cache_creation_input_tokens":40}}}""" }
        sandbox.logText["container-84"] = listOf(assistant("m1", 1), assistant("m1", 1), assistant("m2", 2), resultLine, "smith: done").joinToString("\n")
        val before = java.time.OffsetDateTime.now().minusSeconds(1)
        val id = runPinned(84L, "ledger-a", mode = "review")
        val row = ledger.between(before, java.time.OffsetDateTime.now().plusSeconds(1)).single { it.runId == id }
        assertEquals("smith-run", row.caller); assertEquals("correctness", row.role); assertEquals("ledger-a", row.account)
        assertEquals("claude-sonnet-5", row.model); assertEquals(1.2345, row.costUsd); assertEquals(81234L, row.durationMs)
        assertEquals(com.codefrak.engine.accounts.ClaudeTokens(3, 40, 600, 80), row.tokens)

        val view = consumption.consumption("1h", null, null)
        val role = view.rows.single { it.key == "correctness" }
        assertEquals("smith-run", role.caller); assertTrue(role.calls >= 1); assertTrue(role.costUsd!! >= 1.2345)
        assertTrue(role.inputTokens!! >= 3); assertTrue(role.durationMs!! >= 81234L)
        assertEquals("total", view.rows.last().key)
        val earlier = consumption.consumption(null, before.minusHours(2).toString(), before.minusHours(1).toString())
        assertTrue(earlier.rows.none { it.key == "correctness" }, "a run is in the window it ended in only")
    }

    @Test
    fun `a missing or garbled result event records null cost, turns and duration and leaves the result`() {
        sandbox.logText["container-81"] = """{"type":"result","num_turns":17,"total_cost_usd":1.2""" + "\nsmith: done"
        wroteResult(81L, 82L)
        val garbled = runPinned(81L, "cost-b")
        val missing = runPinned(82L, "cost-b")   // the default log: no stream-json at all
        for (id in listOf(garbled, missing)) {
            val row = stats.get(id)!!
            assertNull(row.cost, "cost on $id"); assertNull(row.turns, "turns on $id"); assertNull(row.durationMs, "durationMs on $id")
            assertEquals("success", runVar(id, Vars.RESULT)); assertEquals(0L, runVar(id, Vars.EXIT_CODE))
        }
    }

    // ---- the Fable limit (engine/README.md, "The usage meter") ----

    /** Claude's closing stream-json event for the #87/#157 refusals of 2026-09-25. */
    private val fableRefusal = """{"type":"result","subtype":"success","is_error":true,"terminal_reason":"api_error",""" +
        """"result":"You've reached your Fable limit. Switch to another model to continue."}"""

    /** meter.json whose 7-day window resets at `sevenDayReset` (epoch seconds). */
    private fun weekMeter(status: Int, verdict: String, sevenDayReset: Long) = """
        {"observedAt": "${java.time.OffsetDateTime.now()}", "status": $status,
         "anthropic-ratelimit-unified-5h-utilization": "0.61", "anthropic-ratelimit-unified-5h-status": "allowed",
         "anthropic-ratelimit-unified-7d-utilization": "0.40", "anthropic-ratelimit-unified-7d-status": "allowed",
         "anthropic-ratelimit-unified-7d-reset": "$sevenDayReset", "anthropic-ratelimit-unified-status": "$verdict"}
    """.trimIndent().toByteArray()

    private val inThreeDays get() = java.time.Instant.now().plus(java.time.Duration.ofDays(3)).epochSecond

    @Test
    fun `collect result marks the account at its Fable limit from the run's result event`() {
        val reset = inThreeDays
        sandbox.files["container-60" to METER_PATH] = weekMeter(200, "allowed", reset)
        sandbox.logText["container-60"] = """{"type":"system","subtype":"init","model":"claude-fable-5-1"}""" + "\n" + fableRefusal + "\nsmith: done"
        val before = java.time.OffsetDateTime.now()
        runPinned(60L, "fable-run-a", "claude-fable-5-1")
        val u = usages.get("fable-run-a")!!
        assertEquals("reached", u.fableLimit)
        assertEquals(java.time.Instant.ofEpochSecond(reset), u.fableLimitReset!!.toInstant(), "the account's 7-day reset when recorded")
        assertTrue(!u.fableLimitSince!!.isBefore(before.minusSeconds(1)))

        // A Smith quoting the phrase (say, working this very ticket) is not refused.
        sandbox.files["container-61" to METER_PATH] = weekMeter(200, "allowed", reset)
        sandbox.logText["container-61"] = listOf(
            """{"type":"assistant","message":{"content":[{"type":"text","text":"the result: You've reached your Fable limit."}]}}""",
            """{"type":"result","subtype":"success","is_error":false,"result":"added a check for 'reached your Fable limit'"}""",
        ).joinToString("\n")
        sandbox.files["container-61" to OUTPUT_PATH] = """{"outcome":"done","summary":"detects reached your Fable limit"}""".toByteArray()
        runPinned(61L, "fable-run-b", "claude-fable-5-1")
        assertEquals("unknown", usages.get("fable-run-b")!!.fableLimit)
    }

    @Test
    fun `a 429 on a Fable run the headers allowed marks the limit, the same on Opus does not`() {
        sandbox.files["container-62" to METER_PATH] = weekMeter(429, "allowed", inThreeDays)
        runPinned(62L, "fable-429", "claude-fable-5-1")
        assertEquals("reached", usages.get("fable-429")!!.fableLimit)

        sandbox.files["container-63" to METER_PATH] = weekMeter(429, "allowed", inThreeDays)
        runPinned(63L, "opus-429", "claude-opus-5-5")
        assertEquals("unknown", usages.get("opus-429")!!.fableLimit)
    }

    /** The reading observed on 2026-10-05: 429, both windows allowed, the overall status rejected (only 7d_oi out). */
    private fun fableOutMeter(fiveHour: String = "allowed", sevenDay: String = "allowed") = """
        {"observedAt": "${java.time.OffsetDateTime.now()}", "status": 429,
         "anthropic-ratelimit-unified-5h-utilization": "0.20", "anthropic-ratelimit-unified-5h-status": "$fiveHour",
         "anthropic-ratelimit-unified-7d-utilization": "0.40", "anthropic-ratelimit-unified-7d-status": "$sevenDay",
         "anthropic-ratelimit-unified-7d-reset": "$inThreeDays", "anthropic-ratelimit-unified-status": "rejected"}
    """.trimIndent().toByteArray()

    private val outOfCredits = "You're out of usage credits. Switch to another model to continue."

    @Test
    fun `a Fable run refused out of usage credits records the Fable limit, not a rejection`() {
        sandbox.logText["container-1123"] = """{"type":"result","subtype":"success","is_error":true,"result":"$outOfCredits"}"""
        sandbox.files["container-1123" to METER_PATH] = fableOutMeter()
        runPinned(1123L, "fable-credits", "claude-fable-5-1")
        val usage = usages.get("fable-credits")!!
        assertEquals("reached", usage.fableLimit)
        assertNotNull(usage.fableLimitReset)
        assertNull(usage.limitRejectedSince)
        assertFalse(usage.rejected())
    }

    /** Starts a real (not dry) run and returns why its first job failed, and whether it started a sandbox. */
    private fun startReal(issue: Long, model: String, account: String? = null): Pair<Throwable?, Boolean> {
        val before = sandbox.started.size
        val instance = runtime.startProcessInstanceByKey("smith-run", buildMap {
            put(Vars.REPO, "acme/widgets"); put(Vars.ISSUE, issue); put(Vars.MODEL, model); put(Vars.EFFORT, "low")
            put(Vars.DRY_RUN, false); account?.let { put(Vars.ACCOUNT, it) }
        })
        val job = management.createJobQuery().processInstanceId(instance.id).singleResult()
        val failure = runCatching { management.executeJob(job.id) }.exceptionOrNull()
        runCatching { runtime.deleteProcessInstance(instance.id, "test") }
        return failure to (sandbox.started.size > before)
    }

    @Test
    fun `with every anthropic account at its Fable limit start smith fails naming the earliest reset`() {
        val others = accounts.list().filter { it.provider == "anthropic" && it.enabled }.map { it.name }
        others.forEach { accounts.setEnabled(it, false) }
        val names = listOf("fable-all-a", "fable-all-b")
        names.forEach { accounts.add(it, "anthropic"); accounts.setCredential(it, "sk-ant-oat01-$it") }
        try {
            val soon = java.time.OffsetDateTime.now().plusDays(2).withNano(0)
            val later = soon.plusDays(3)
            usages.record("fable-all-a", com.codefrak.engine.accounts.MeterReading(0.1, "allowed", null, 0.5, "allowed", later, "allowed"), 200)
            usages.record("fable-all-b", com.codefrak.engine.accounts.MeterReading(0.1, "allowed", null, 0.5, "allowed", soon, "allowed"), 200)
            names.forEach { usages.markFableReached(it) }

            val (failure, started) = startReal(64L, "claude-fable-5-1")
            assertEquals("no account can run Fable until ${soon.toInstant()}", failure?.message, "got: $failure")
            assertTrue(!started)

            // A pinned account at the limit fails naming the pin for Fable, and starts for another model.
            val (pinned, pinnedStarted) = startReal(65L, "claude-fable-5-1", "fable-all-a")
            assertEquals("account fable-all-a is pinned and at its Fable limit until ${later.toInstant()}", pinned?.message, "got: $pinned")
            assertTrue(!pinnedStarted)
            val (opus, opusStarted) = startReal(66L, "claude-opus-5-5", "fable-all-a")
            assertNull(opus); assertTrue(opusStarted)
            val (sonnet, sonnetStarted) = startReal(67L, "claude-sonnet-5")
            assertNull(sonnet); assertTrue(sonnetStarted, "non-Fable picks ignore the limit")
        } finally {
            names.forEach { accounts.remove(it) }
            others.forEach { accounts.setEnabled(it, true) }
        }
    }

    @Test
    fun `a pinned account whose Fable meter is out is refused for Fable and starts for Opus`() {
        accounts.add("fable-meter-pin", "anthropic"); accounts.setCredential("fable-meter-pin", "sk-ant-oat01-fmp")
        try {
            val reset = java.time.OffsetDateTime.now().plusHours(20).withNano(0)
            usages.record("fable-meter-pin", com.codefrak.engine.accounts.MeterReading(0.1, "allowed", null, 0.5, "allowed", null, "allowed", 1.0, reset), 200)
            val (fable, fableStarted) = startReal(1124L, "claude-fable-5-1", "fable-meter-pin")
            assertEquals("account fable-meter-pin is pinned and at its Fable limit until ${reset.toInstant()}", fable?.message, "got: $fable")
            assertTrue(!fableStarted)
            val (opus, opusStarted) = startReal(1125L, "claude-opus-5-5", "fable-meter-pin")
            assertNull(opus); assertTrue(opusStarted)
        } finally { accounts.remove("fable-meter-pin") }
    }

    // ---- the 5-hour / 7-day gate (engine/README.md, "AI accounts") ----

    @Test
    fun `with every anthropic account rejected start smith fails naming the earliest reset`() {
        val others = accounts.list().filter { it.provider == "anthropic" && it.enabled }.map { it.name }
        others.forEach { accounts.setEnabled(it, false) }
        val names = listOf("rej-all-a", "rej-all-b")
        names.forEach { accounts.add(it, "anthropic"); accounts.setCredential(it, "sk-ant-oat01-$it") }
        try {
            val soon = java.time.OffsetDateTime.now().plusHours(2).withNano(0)
            val later = soon.plusDays(3)
            usages.record("rej-all-a", com.codefrak.engine.accounts.MeterReading(1.0, "rejected", soon, 0.5, "allowed", later, "rejected"), 429)
            usages.record("rej-all-b", com.codefrak.engine.accounts.MeterReading(0.4, "allowed", soon, 1.0, "rejected", later, "rejected"), 429)

            val (failure, started) = startReal(170L, "claude-sonnet-5")
            assertEquals("no account available until ${soon.toInstant()}", failure?.message, "got: $failure")
            assertTrue(!started)
            val (fable, _) = startReal(171L, "claude-fable-5-1")
            assertEquals("no account available until ${soon.toInstant()}", fable?.message, "got: $fable")

            val (pinned, pinnedStarted) = startReal(172L, "claude-sonnet-5", "rej-all-b")
            assertEquals("account rej-all-b is pinned and rejected until ${later.toInstant()}", pinned?.message, "got: $pinned")
            assertTrue(!pinnedStarted)
        } finally {
            names.forEach { accounts.remove(it) }
            others.forEach { accounts.setEnabled(it, true) }
        }
    }

    @Test
    fun `a Fable run blocked only on Fable names Fable even when another account is rejected`() {
        val others = accounts.list().filter { it.provider == "anthropic" && it.enabled }.map { it.name }
        others.forEach { accounts.setEnabled(it, false) }
        val names = listOf("fable-only-a", "fable-only-rej")
        names.forEach { accounts.add(it, "anthropic"); accounts.setCredential(it, "sk-ant-oat01-$it") }
        try {
            val reset = java.time.OffsetDateTime.now().plusDays(2).withNano(0)
            val soon = java.time.OffsetDateTime.now().plusHours(2).withNano(0)
            usages.record("fable-only-a", com.codefrak.engine.accounts.MeterReading(0.1, "allowed", null, 0.5, "allowed", reset, "allowed"), 200)
            usages.markFableReached("fable-only-a")
            usages.record("fable-only-rej", com.codefrak.engine.accounts.MeterReading(1.0, "rejected", soon, 0.5, "allowed", soon.plusDays(3), "rejected"), 429)

            val (fable, started) = startReal(173L, "claude-fable-5-1")
            assertEquals("no account can run Fable until ${reset.toInstant()}", fable?.message, "got: $fable")
            assertTrue(!started)
            val (opus, opusStarted) = startReal(174L, "claude-opus-5-5")
            assertNull(opus); assertTrue(opusStarted)
        } finally {
            names.forEach { accounts.remove(it) }
            others.forEach { accounts.setEnabled(it, true) }
        }
    }

    @Test
    fun `a run resolves its flow's tier against the map as it is now, not as the flow started`() {
        val was = tierTable.all().first { it.tier == 8 }
        try {
            tierTable.set(8, "opus", "low")
            val now = tierTable.resolve(8)
            // The flow started while tier 8 was Fable and still carries that model.
            val before = sandbox.started.size
            val instance = start(175L, Vars.TIER to 8, Vars.MODEL to "claude-fable-5-1", Vars.EFFORT to "high")
            runCatching { management.createJobQuery().processInstanceId(instance.id).list().forEach { management.executeJob(it.id) } }
            val spec = sandbox.started.drop(before).single { it.issue == 175L }
            assertEquals(now.model, spec.model); assertEquals(now.effort, spec.effort)
            runCatching { runtime.deleteProcessInstance(instance.id, "test") }
        } finally { tierTable.set(8, was.model, was.effort) }
    }

    @Test
    fun `a jev account never runs a Smith, picked or pinned`() {
        val others = accounts.list().filter { it.enabled }.map { it.name }
        others.forEach { accounts.setEnabled(it, false) }
        accounts.add("jev-run", "jev"); accounts.setCredential("jev-run", "jev-key")
        try {
            val (failure, started) = startReal(180L, "claude-sonnet-5")
            assertTrue(failure?.message?.contains("no usable ai account") == true, "got: $failure")
            assertTrue(!started, "the only usable account is jev's: nothing starts")
            val (codex, codexStarted) = startReal(181L, "gpt-6-astra")
            assertTrue(codex?.message?.contains("no usable ai account") == true, "got: $codex")
            assertTrue(!codexStarted)

            val (pinned, pinnedStarted) = startReal(182L, "claude-sonnet-5", "jev-run")
            assertEquals("account jev-run is a jev account; no Smith runs on it", pinned?.message, "got: $pinned")
            assertTrue(!pinnedStarted)
            assertNull(usages.get("jev-run"), "no meter, Fable or limit record")
        } finally {
            accounts.remove("jev-run")
            others.forEach { accounts.setEnabled(it, true) }
        }
    }

    private val spendRefusal = """{"type":"result","subtype":"success","is_error":true,"terminal_reason":"api_error",""" +
        """"result":"You've hit your org's monthly spend limit · your session limit resets 3:10pm (UTC)"}"""

    @Test
    fun `collect result records a session or spend limit from the result event until the reset it names`() {
        sandbox.logText["container-173"] = spendRefusal + "\nsmith: done"
        val before = java.time.OffsetDateTime.now()
        runPinned(173L, "limit-run-a")
        val reset = usages.get("limit-run-a")!!.limitRejectedReset!!.toInstant()
        assertEquals(parseLimitReset("resets 3:10pm (UTC)", before)!!.toInstant(), reset)
        assertEquals("15:10:00Z", reset.toString().substringAfter('T'))

        // Quoted by an assistant line, or in a finished run's result.json: not a refusal.
        sandbox.logText["container-174"] = listOf(
            """{"type":"assistant","message":{"content":[{"type":"text","text":"You've hit your org's monthly spend limit"}]}}""",
            """{"type":"result","subtype":"success","is_error":false,"result":"parses 'your session limit resets 3:10pm (UTC)'"}""",
        ).joinToString("\n")
        sandbox.files["container-174" to OUTPUT_PATH] = """{"outcome":"done","summary":"handles monthly spend limit"}""".toByteArray()
        runPinned(174L, "limit-run-b")
        assertNull(usages.get("limit-run-b"))
    }

    @Test
    fun `a session limit with no reset in the text falls back to the meter's`() {
        val fiveHourReset = java.time.Instant.now().plusSeconds(7200).epochSecond
        sandbox.files["container-175" to METER_PATH] = """
            {"observedAt": "${java.time.OffsetDateTime.now()}", "status": 429,
             "anthropic-ratelimit-unified-5h-utilization": "1.0", "anthropic-ratelimit-unified-5h-status": "rejected",
             "anthropic-ratelimit-unified-5h-reset": "$fiveHourReset", "anthropic-ratelimit-unified-status": "rejected"}
        """.trimIndent().toByteArray()
        sandbox.logText["container-175"] = """{"type":"result","is_error":true,"result":"You've hit your session limit"}"""
        runPinned(175L, "limit-run-c")
        assertEquals(java.time.Instant.ofEpochSecond(fiveHourReset), usages.get("limit-run-c")!!.limitRejectedReset!!.toInstant())

        // Neither a reset in the text nor a meter: nothing recorded, no window invented.
        sandbox.logText["container-176"] = """{"type":"result","is_error":true,"result":"You've hit your session limit"}"""
        runPinned(176L, "limit-run-d")
        assertNull(usages.get("limit-run-d"))
    }

    // ---- a flow's later run re-picks (#178) ----

    /** Two smith-run calls with a wait between, mapped like engine/bpmn/feature.bpmn: every variable in and out. */
    private fun deployTwoRunFlow() {
        val call = { id: String -> """<bpmn:callActivity id="$id" calledElement="smith-run">
            <bpmn:extensionElements><camunda:in variables="all" /><camunda:out variables="all" /></bpmn:extensionElements></bpmn:callActivity>""" }
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" targetNamespace="t">
  <bpmn:process id="two-runs" isExecutable="true" camunda:historyTimeToLive="1">
    <bpmn:startEvent id="s" />
    ${call("first")}
    <bpmn:userTask id="between" />
    ${call("second")}
    <bpmn:endEvent id="e" />
    <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="first" />
    <bpmn:sequenceFlow id="f2" sourceRef="first" targetRef="between" />
    <bpmn:sequenceFlow id="f3" sourceRef="between" targetRef="second" />
    <bpmn:sequenceFlow id="f4" sourceRef="second" targetRef="e" />
  </bpmn:process>
</bpmn:definitions>"""
        repository.createDeployment().addString("two-runs.bpmn", xml).deploy()
    }

    /** Runs every job of the flow and of the smith-runs it called; each round every started sandbox has exited (the backstop collects it). */
    private fun runFlowJobs() = repeat(50) {
        sandbox.started.map { "container-${it.issue}" }.filter { it !in sandbox.states }.forEach { sandbox.states[it] = SandboxStatus(false, 0) }
        exits.scanExits("test")
        val jobs = management.createJobQuery().list()
        if (jobs.isEmpty()) return
        jobs.forEach { management.executeJob(it.id) }
    }

    private fun smithRuns(flow: String) = history.createHistoricProcessInstanceQuery().superProcessInstanceId(flow)
        .orderByProcessInstanceStartTime().asc().list().map { it.id }

    private fun billed(run: String) = history.createHistoricVariableInstanceQuery()
        .processInstanceId(run).variableName(Vars.BILLED_ACCOUNT).singleResult()?.value

    private fun withOnly(names: List<String>, body: () -> Unit) {
        val others = accounts.list().filter { it.provider == "anthropic" && it.enabled }.map { it.name }
        others.forEach { accounts.setEnabled(it, false) }
        names.forEach { accounts.add(it, "anthropic"); accounts.setCredential(it, "sk-ant-oat01-$it") }
        try { body() } finally {
            names.forEach { accounts.remove(it) }
            others.forEach { accounts.setEnabled(it, true) }
        }
    }

    @Test
    fun `a later run in a flow re-picks after the first run's account hits its Fable limit, and each run records its own`() {
        deployTwoRunFlow()
        withOnly(listOf("repick-a", "repick-b")) {
            sandbox.files["container-70" to METER_PATH] = weekMeter(200, "allowed", inThreeDays)
            val before = sandbox.started.size
            val flow = runtime.startProcessInstanceByKey("two-runs", mapOf(
                Vars.REPO to "acme/widgets", Vars.ISSUE to 70L, Vars.MODEL to "claude-fable-5-1",
                Vars.EFFORT to "low", Vars.DRY_RUN to false))
            runFlowJobs()
            assertEquals("repick-a", sandbox.started[before].account)
            assertNull(runtime.getVariable(flow.id, Vars.ACCOUNT), "the pick does not come back as a pin")

            usages.markFableReached("repick-a")
            tasks.complete(tasks.createTaskQuery().processInstanceId(flow.id).singleResult().id)
            runFlowJobs()
            assertEquals(before + 2, sandbox.started.size, "the second run started")
            assertEquals("repick-b", sandbox.started[before + 1].account)

            val (first, second) = smithRuns(flow.id)
            assertEquals("repick-a", billed(first)); assertEquals("repick-b", billed(second))
            assertEquals(1, usages.get("repick-a")!!.requests); assertEquals(1, usages.get("repick-b")!!.requests)
            assertEquals("reached", usages.get("repick-a")!!.fableLimit)
            assertEquals("unknown", usages.get("repick-b")!!.fableLimit)
        }
    }

    @Test
    fun `a flow that pins an account keeps it for every run`() {
        deployTwoRunFlow()
        withOnly(listOf("pin-a", "pin-b")) {
            val before = sandbox.started.size
            val flow = runtime.startProcessInstanceByKey("two-runs", mapOf(
                Vars.REPO to "acme/widgets", Vars.ISSUE to 71L, Vars.MODEL to "claude-sonnet-5", Vars.ACCOUNT to "pin-b",
                Vars.EFFORT to "low", Vars.DRY_RUN to false))
            runFlowJobs()
            tasks.complete(tasks.createTaskQuery().processInstanceId(flow.id).singleResult().id)
            runFlowJobs()
            assertEquals(listOf("pin-b", "pin-b"), sandbox.started.drop(before).map { it.account })
            assertEquals(listOf("pin-b", "pin-b"), smithRuns(flow.id).map { billed(it) })
        }
    }

    // ---- the retro's friction notes (engine/README.md, "Smith runs"; #876) ----

    @Autowired lateinit var friction: com.codefrak.engine.friction.FrictionNotes

    private fun retroNotes(issue: Long) = friction.list(com.codefrak.engine.friction.FrictionFilter(repo = "acme/widgets", issue = issue))

    private fun result(id: String) = history.createHistoricVariableInstanceQuery().processInstanceId(id).variableName(Vars.RESULT).singleResult().value

    @Test
    fun `each retro item of a work run becomes a retro friction note stamped from the run`() {
        wroteResult(90L)
        sandbox.files["container-90" to RETRO_PATH] = """[{"kind":"docs","summary":"README names a gone flag","evidence":"engine/README.md"},
            {"kind":"tooling","summary":"gradle OOM","severity":"high"}]""".toByteArray()
        val id = runPinned(90L, "retro-a")
        val rows = retroNotes(90L).sortedBy { it.kind }
        assertEquals(listOf("docs", "tooling"), rows.map { it.kind })
        for (row in rows) {
            assertEquals(listOf("acme/widgets", 90L, id, id, "retro"), listOf(row.repo, row.issue, row.runId, row.processInstanceId, row.source))
        }
        assertEquals("engine/README.md" to null, rows[0].evidence to rows[0].severity)
        assertEquals("high", rows[1].severity)
        assertEquals("success", result(id))
    }

    @Test
    fun `an unparsable retro stores nothing and leaves the run's result, and an invalid item is skipped`() {
        wroteResult(91L)
        sandbox.files["container-91" to RETRO_PATH] = "not json".toByteArray()
        val garbled = runPinned(91L, "retro-b")
        assertTrue(retroNotes(91L).isEmpty())
        assertEquals("success", result(garbled))

        wroteResult(92L)
        sandbox.files["container-92" to RETRO_PATH] = """{"notes":[]}""".toByteArray()
        assertEquals("success", result(runPinned(92L, "retro-b")))
        assertTrue(retroNotes(92L).isEmpty())

        sandbox.files["container-93" to RETRO_PATH] = """[{"kind":"rant","summary":"x"},"text",{"kind":"bug","summary":"real","extra":1},
            {"kind":"bug","summary":"kept"}]""".toByteArray()
        val failed = runPinned(93L, "retro-b")
        assertEquals(listOf("kept"), retroNotes(93L).map { it.summary })
        assertEquals("no-result", result(failed), "no result.json: the retro does not make one")
    }

    @Test
    fun `a review run's retro is read, a design run's is not`() {
        sandbox.files["container-94" to RETRO_PATH] = """[{"kind":"design","summary":"r"}]""".toByteArray()
        runPinned(94L, "retro-c", mode = "review")
        assertEquals(listOf("r"), retroNotes(94L).map { it.summary })
        sandbox.files["container-95" to RETRO_PATH] = """[{"kind":"design","summary":"d"}]""".toByteArray()
        runPinned(95L, "retro-c", mode = "design")
        assertTrue(retroNotes(95L).isEmpty())
    }

    // ---- a session or spend limit reruns on another account (engine/README.md, "Incidents"; #1035) ----

    @Autowired lateinit var github: com.codefrak.engine.intake.TicketRecorder
    @Autowired lateinit var retrier: com.codefrak.engine.camunda.IncidentRetrier

    private val limitLog = spendRefusal + "\nsmith: done"
    private val asked = """{"outcome":"ask","question":"Which widget?"}"""

    /** A `reusable-smith-run` as a flow calls it: the run, then `read result`. A real (not dry) run, so the picker picks. */
    private fun startWrapped(issue: Long, mode: String = "work", vararg more: Pair<String, Any>) =
        runtime.startProcessInstanceByKey(REUSABLE_SMITH_RUN, mapOf(
            Vars.REPO to "acme/widgets", Vars.ISSUE to issue, Vars.MODEL to "claude-sonnet-5", Vars.EFFORT to "low",
            Vars.DRY_RUN to false, Vars.MODE to mode, *more))

    /**
     * Every executable job of the flow and its runs, until none is left; a failing one is left to fail (its retries run
     * down to an incident). Only the flow's: another test's leftover start job would start a Smith on this test's accounts.
     */
    private fun drainJobs(flow: String) = repeat(50) {
        val jobs = (listOf(flow) + smithRuns(flow)).flatMap { management.createJobQuery().processInstanceId(it).executable().list() }
        if (jobs.isEmpty()) return
        jobs.forEach { runCatching { management.executeJob(it.id) } }
    }

    /** Deletes the flow when a test leaves it on an incident or a retry, so no later test drives its jobs. */
    private fun discard(flow: String) { runCatching { runtime.deleteProcessInstance(flow, "test") } }

    /** The waiting run's sandbox exits `code` with `log` (and `output` as its result.json, if any); its collect and every job after it run. */
    private fun exitRun(flow: String, issue: Long, code: Long, log: String, output: String? = null) {
        val handle = "container-$issue"
        sandbox.logText[handle] = log
        if (output != null) sandbox.files[handle to OUTPUT_PATH] = output.toByteArray() else sandbox.files.remove(handle to OUTPUT_PATH)
        sandbox.states[handle] = SandboxStatus(false, code)
        exits.scanExits("test")
        sandbox.states.remove(handle)
        drainJobs(flow)
    }

    private fun incidents(flow: String) = (listOf(flow) + smithRuns(flow))
        .flatMap { history.createHistoricIncidentQuery().processInstanceId(it).list() }

    @Test
    fun `a run that hit its account's session limit is rerun on another usable account, with no incident and one comment`() {
        withOnly(listOf("rerun-a", "rerun-b")) {
            val before = sandbox.started.size
            val comments = github.comments.size
            val flow = startWrapped(190L)
            drainJobs(flow.id)
            exitRun(flow.id, 190L, 1, limitLog)
            assertEquals(before + 2, sandbox.started.size, "a second Smith started")
            exitRun(flow.id, 190L, 0, "smith: done", asked)

            val (first, second) = smithRuns(flow.id)
            assertEquals("rerun-a", billed(first)); assertEquals("rerun-b", billed(second))
            assertNotNull(usages.get("rerun-a")!!.limitRejectedReset, "the limited account is rejected")
            assertEquals("failed", result(first)); assertEquals("success", result(second))
            assertTrue(finished(flow.id), "the flow read the second run's result and ended")
            assertEquals("ask", vars(flow.id)["outcome"])
            assertEquals(emptyList(), incidents(flow.id).map { it.incidentMessage }, "no incident at any step")
            val posted = github.comments.drop(comments)
            assertEquals(1, posted.size, "one comment: $posted")
            val (repo, issue, body) = posted.single()
            assertEquals("acme/widgets" to 190L, repo to issue)
            assertTrue(body.startsWith("The engine started its Smith run again on this reusable-smith-run (`${flow.id}`)"), body)
            assertTrue("on account rerun-a's session or spend limit, rejected until" in body && body.endsWith("— engine"), body)
            assertNull(vars(flow.id)[Vars.LIMIT_RERUNS], "a run that ended any other way clears the bound")
            assertEquals(0L, activityCount(flow.id, "event-wait-reset"), "an immediate rerun does not wait")
            assertNull(vars(flow.id)[Vars.RERUN_AT])
        }
    }

    @Autowired lateinit var camunda: com.codefrak.engine.camunda.CamundaController

    /** The account's window reopens: its recorded limit rejection is gone, as a reading past its reset clears it. */
    private fun reopen(vararg names: String) = names.forEach {
        jdbc.sql("UPDATE engine.ai_account_usage SET limit_rejected_since = NULL, limit_rejected_reset = NULL WHERE account = :a")
            .param("a", it).update()
    }

    /** The wrapper's timer job (`event-wait-reset`), or null when it waits on none. */
    private fun timer(flow: String) = management.createJobQuery().processInstanceId(flow).timers().singleResult()

    @Test
    fun `a session-limited run with every other account rejected waits for the earliest reset, then reruns on a new pick`() {
        withOnly(listOf("timed-a", "timed-b")) {
            val bUntil = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(30).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            usages.markLimitRejected("timed-b", bUntil)
            val before = sandbox.started.size
            val comments = github.comments.size
            val flow = startWrapped(201L)
            drainJobs(flow.id)
            exitRun(flow.id, 201L, 1, limitLog)

            assertEquals(before + 1, sandbox.started.size, "no second run before the reset")
            assertEquals("timed-a", billed(smithRuns(flow.id).single()))
            val aUntil = usages.get("timed-a")!!.rejectedUntil()!!.toInstant()
            val first = if (bUntil.toInstant() < aUntil) "timed-b" else "timed-a"
            val at = minOf(bUntil.toInstant(), aUntil)
            val job = assertNotNull(timer(flow.id), "the wrapper waits on its timer")
            assertEquals(at, job.duedate.toInstant(), "the timer is due at the provider's earliest reset")
            assertEquals(listOf(com.codefrak.engine.camunda.Timer("event-wait-reset", at.atOffset(java.time.ZoneOffset.UTC))),
                camunda.instance(flow.id).nodes.first().timers, "the process view shows the timer with its due time")
            assertEquals(emptyList(), incidents(flow.id).map { it.incidentMessage }, "no incident while it waits")
            assertEquals("timed-a", runtime.getVariable(flow.id, Vars.LIMIT_RERUNS), "the timed rerun counts against the bound")
            val body = rerunComments(201L, comments).single()
            assertTrue(body.startsWith("The engine starts its Smith run again on this reusable-smith-run (`${flow.id}`) at $at: " +
                "the last one ended on account timed-a's session or spend limit, rejected until $aUntil; " +
                "no other anthropic account is usable until $at."), body)

            reopen(first)
            management.executeJob(job.id)
            drainJobs(flow.id)
            assertEquals(before + 2, sandbox.started.size, "the reset reran the Smith")
            assertEquals(first, billed(smithRuns(flow.id).last()), "the rerun picked again: the account whose window reopened")
            exitRun(flow.id, 201L, 0, "smith: done", asked)

            assertTrue(finished(flow.id), "the wrapper read the second run's result and ended")
            assertEquals("ask", vars(flow.id)["outcome"])
            assertEquals(emptyList(), incidents(flow.id).map { it.incidentMessage }, "no incident at any step")
            assertEquals(1, rerunComments(201L, comments).size, "one comment, before the wait")
            assertEquals(1L, activityCount(flow.id, "event-wait-reset"))
        }
    }

    @Test
    fun `a timed rerun is bounded, the same account limited again in the step parks on its incident`() {
        withOnly(listOf("solo-a")) {
            val before = sandbox.started.size
            val comments = github.comments.size
            val flow = startWrapped(191L)
            drainJobs(flow.id)
            exitRun(flow.id, 191L, 1, limitLog)
            val until = usages.get("solo-a")!!.rejectedUntil()!!.toInstant()
            val job = assertNotNull(timer(flow.id))
            assertEquals(until, job.duedate.toInstant(), "its own reset is the earliest")

            reopen("solo-a")
            management.executeJob(job.id)
            drainJobs(flow.id)
            exitRun(flow.id, 191L, 1, limitLog)
            assertEquals(before + 2, sandbox.started.size, "one timed rerun, then none")
            assertNull(timer(flow.id), "no second wait")
            val incident = runtime.createIncidentQuery().processInstanceId(smithRuns(flow.id).last()).singleResult()
            assertNotNull(incident, "the failed job's incident")
            assertEquals("task-remove-sandbox", incident.activityId)
            val (line, more) = incident.incidentMessage.split("\n", limit = 2)
            assertTrue(com.codefrak.engine.doctor.Signature.generic(line), "the first line is the generic one: $line")
            assertEquals("It ended on account solo-a's session or spend limit, rejected until $until; " +
                "this step was already rerun after a limit on solo-a.", more)
            assertEquals(1, rerunComments(191L, comments).size, "one rerun comment")

            assertEquals("smith", retrier.retryAsSystem(incident.id, "test").action, "a retry starts the Smith run over")
            discard(flow.id)
        }
    }

    @Test
    fun `a session-limited run with no reset known parks on its incident as before`() {
        // The result names no reset and there is no meter: the account is not rejected.
        withOnly(listOf("noreset-a")) {
            val comments = github.comments.size
            val flow = startWrapped(202L)
            drainJobs(flow.id)
            exitRun(flow.id, 202L, 1, """{"type":"result","subtype":"success","is_error":true,"terminal_reason":"api_error",""" +
                """"result":"You've hit your session limit"}""" + "\nsmith: done")
            assertNull(timer(flow.id), "no wait")
            val incident = runtime.createIncidentQuery().processInstanceId(smithRuns(flow.id).single()).singleResult()
            assertEquals("It ended on account noreset-a's session or spend limit; no reset is known, so noreset-a is not rejected.",
                incident.incidentMessage.split("\n", limit = 2)[1])
            assertEquals(emptyList(), rerunComments(202L, comments))
            discard(flow.id)
        }
        // No enabled account of the provider is left to come back (AiAccounts.blockedUntil is null).
        withOnly(listOf("gone-a")) {
            val comments = github.comments.size
            val flow = startWrapped(203L)
            drainJobs(flow.id)
            accounts.setEnabled("gone-a", false)
            exitRun(flow.id, 203L, 1, limitLog)
            assertNull(timer(flow.id), "no wait")
            val incident = runtime.createIncidentQuery().processInstanceId(smithRuns(flow.id).single()).singleResult()
            val until = usages.get("gone-a")!!.rejectedUntil()!!.toInstant()
            assertEquals("It ended on account gone-a's session or spend limit, rejected until $until; no other anthropic account is usable.",
                incident.incidentMessage.split("\n", limit = 2)[1])
            assertEquals(emptyList(), rerunComments(203L, comments))
            discard(flow.id)
        }
    }

    @Test
    fun `reruns are bounded, with every account limited in turn the flow ends on the incident once each has rerun`() {
        withOnly(listOf("bound-a", "bound-b")) {
            val before = sandbox.started.size
            val flow = startWrapped(192L)
            drainJobs(flow.id)
            exitRun(flow.id, 192L, 1, limitLog)
            exitRun(flow.id, 192L, 1, limitLog)
            assertEquals(before + 2, sandbox.started.size, "one rerun at once, then a wait")
            assertEquals(listOf("bound-a", "bound-b"), smithRuns(flow.id).map { billed(it) })
            val job = assertNotNull(timer(flow.id), "both accounts limited: the second rerun waits for a reset")
            assertEquals("bound-a,bound-b", runtime.getVariable(flow.id, Vars.LIMIT_RERUNS), "the bound is kept on the caller")

            reopen("bound-a", "bound-b")
            management.executeJob(job.id)
            drainJobs(flow.id)
            val third = billed(smithRuns(flow.id).last()) as String
            exitRun(flow.id, 192L, 1, limitLog)
            assertEquals(before + 3, sandbox.started.size, "each account reran the step once, then none")
            val incident = runtime.createIncidentQuery().processInstanceId(smithRuns(flow.id).last()).singleResult()
            assertNotNull(incident)
            assertTrue("this step was already rerun after a limit on $third" in incident.incidentMessage, incident.incidentMessage)
            discard(flow.id)
        }
        // An account the step already reran after is not rerun again, even with another account usable.
        withOnly(listOf("again-a", "again-b")) {
            val before = sandbox.started.size
            val flow = startWrapped(193L, "work", Vars.LIMIT_RERUNS to "again-a")
            drainJobs(flow.id)
            exitRun(flow.id, 193L, 1, limitLog)
            assertEquals(before + 1, sandbox.started.size)
            val incident = runtime.createIncidentQuery().processInstanceId(smithRuns(flow.id).single()).singleResult()
            assertTrue("this step was already rerun after a limit on again-a" in incident.incidentMessage, incident.incidentMessage)
            discard(flow.id)
        }
    }

    // ---- a failed run is rerun once by the engine (engine/README.md, "Incidents"; #1047) ----

    private fun rerunComments(issue: Long, since: Int) = github.comments.drop(since).filter { it.second == issue }.map { it.third }

    @Test
    fun `a run that ends failed is rerun once with a new pick, and the second run's result moves the caller on with no incident`() {
        withOnly(listOf("plain-a", "plain-b")) {
            val before = sandbox.started.size
            val comments = github.comments.size
            val flow = startWrapped(194L)
            drainJobs(flow.id)
            exitRun(flow.id, 194L, 1, "smith: crashed")
            assertEquals(before + 2, sandbox.started.size, "a second Smith started")
            exitRun(flow.id, 194L, 0, "smith: done", asked)

            val (first, second) = smithRuns(flow.id)
            assertNotNull(billed(first)); assertNotNull(billed(second), "the rerun picked its account again")
            assertEquals("failed", result(first)); assertEquals("success", result(second))
            assertTrue(finished(flow.id), "the wrapper read the second run's result and ended")
            assertEquals("ask", vars(flow.id)["outcome"])
            assertEquals(emptyList(), incidents(flow.id).map { it.incidentMessage }, "no incident at any step")
            assertEquals(flow.id, vars(flow.id)[Vars.FAIL_RERUN_OF], "the cap names the wrapper")
            assertEquals(0L, activityCount(flow.id, "event-wait-reset"), "a failure rerun does not wait")
            assertNull(usages.get("plain-a")?.limitRejectedReset, "no limit recorded")
            val body = rerunComments(194L, comments).single()
            assertTrue(body.startsWith("The engine started its Smith run again on this reusable-smith-run (`${flow.id}`): the last one ended failed (exit 1);"), body)
            assertTrue("this is its one automatic rerun, a second failure parks on an incident" in body && body.endsWith("— engine"), body)
        }
    }

    @Test
    fun `a run that exits 0 with no result json is rerun once the same way`() {
        withOnly(listOf("nores-a")) {
            val before = sandbox.started.size
            val comments = github.comments.size
            val flow = startWrapped(196L)
            drainJobs(flow.id)
            exitRun(flow.id, 196L, 0, "smith: done")
            assertEquals(before + 2, sandbox.started.size, "a second Smith started")
            exitRun(flow.id, 196L, 0, "smith: done", asked)

            val (first, second) = smithRuns(flow.id)
            assertEquals(NO_RESULT, result(first)); assertEquals("success", result(second))
            assertTrue(finished(flow.id))
            assertEquals(emptyList(), incidents(flow.id).map { it.incidentMessage })
            assertTrue("the last one ended with no result.json (exit 0)" in rerunComments(196L, comments).single())
        }
    }

    @Test
    fun `two failures in a row leave one incident and no third run, and a retry of it still runs the Smith again`() {
        withOnly(listOf("twice-a", "twice-b")) {
            val before = sandbox.started.size
            val comments = github.comments.size
            val flow = startWrapped(197L)
            drainJobs(flow.id)
            exitRun(flow.id, 197L, 1, "smith: crashed")
            exitRun(flow.id, 197L, 1, "smith: crashed again")
            assertEquals(before + 2, sandbox.started.size, "one rerun, then none")
            val open = runtime.createIncidentQuery().processInstanceId(smithRuns(flow.id).last()).list()
            val incident = open.single()
            assertEquals("smith run container-197 ended failed; see its log", incident.incidentMessage)
            assertEquals("task-remove-sandbox", incident.activityId)
            assertEquals(1L, history.createHistoricJobLogQuery().jobId(incident.configuration).failureLog().count(),
                "the stored failed result is read once, not three identical retries (#2376)")
            assertEquals(0L, runtime.createIncidentQuery().processInstanceId(smithRuns(flow.id).first()).count(), "the first run left none")
            assertEquals(1, rerunComments(197L, comments).size, "one rerun comment")

            assertEquals("smith", retrier.retryAsSystem(incident.id, "test").action, "a retry starts the Smith run over")
            drainJobs(flow.id)
            assertEquals(before + 3, sandbox.started.size, "the cap does not stop a retry")
            discard(flow.id)
        }
    }

    // ---- an evicted run is rerun, up to MAX_EVICTION_RERUNS times (engine/README.md, "Incidents"; #1428) ----

    @Test
    fun `an evicted run is rerun without spending the failure rerun, and its stats row says evicted`() {
        withOnly(listOf("evict-a")) {
            val before = sandbox.started.size
            val comments = github.comments.size
            val flow = startWrapped(206L)
            drainJobs(flow.id)
            sandbox.evictions["container-206"] = true
            exitRun(flow.id, 206L, 1, "smith: killed")
            sandbox.evictions.remove("container-206")
            assertEquals(before + 2, sandbox.started.size, "a second Smith started")
            val first = smithRuns(flow.id).first()
            assertEquals(true, stats.get(first)!!.evicted)
            assertNull(vars(flow.id)[Vars.FAIL_RERUN_OF], "the failure rerun is not spent")
            assertEquals("${flow.id}:1", vars(flow.id)[Vars.EVICTION_RERUNS])
            assertTrue("evicted by the cluster" in rerunComments(206L, comments).single())
            exitRun(flow.id, 206L, 1, "smith: crashed")
            assertEquals(before + 3, sandbox.started.size, "a plain failure still gets its one rerun")
            exitRun(flow.id, 206L, 0, "smith: done", asked)
            assertTrue(finished(flow.id))
            assertEquals(emptyList(), incidents(flow.id).map { it.incidentMessage })
        }
    }

    @Test
    fun `a run evicted past the bound parks on an incident naming eviction`() {
        withOnly(listOf("evict-b")) {
            val before = sandbox.started.size
            val flow = startWrapped(207L)
            drainJobs(flow.id)
            sandbox.evictions["container-207"] = true
            repeat(MAX_EVICTION_RERUNS + 1) { exitRun(flow.id, 207L, 1, "smith: killed") }
            sandbox.evictions.remove("container-207")
            assertEquals(before + MAX_EVICTION_RERUNS + 1, sandbox.started.size, "rerun $MAX_EVICTION_RERUNS times, then none")
            val incident = runtime.createIncidentQuery().processInstanceId(smithRuns(flow.id).last()).singleResult()
            assertNotNull(incident)
            assertTrue("evicted by the cluster ${MAX_EVICTION_RERUNS + 1} times" in incident.incidentMessage, incident.incidentMessage)
            discard(flow.id)
        }
    }

    @Test
    fun `a result enforce rejects is not rerun and parks on its incident`() {
        withOnly(listOf("rules-a")) {
            val before = sandbox.started.size
            val comments = github.comments.size
            val flow = startWrapped(198L)
            drainJobs(flow.id)
            exitRun(flow.id, 198L, 0, "smith: done", """{"outcome":"bogus","summary":"s"}""")
            assertEquals(before + 1, sandbox.started.size, "no rerun")
            val incident = runtime.createIncidentQuery().processInstanceId(smithRuns(flow.id).single()).singleResult()
            assertTrue(incident.incidentMessage.startsWith("the smith's result breaks the rules"), incident.incidentMessage)
            assertEquals(emptyList(), rerunComments(198L, comments))
            discard(flow.id)
        }
    }

    @Test
    fun `a diagnose run that ends failed is not rerun, its failure is its outcome`() {
        withOnly(listOf("diagfail-a")) {
            val before = sandbox.started.size
            val flow = startWrapped(199L, "diagnose")
            drainJobs(flow.id)
            exitRun(flow.id, 199L, 1, "smith: crashed")
            assertEquals(before + 1, sandbox.started.size, "no rerun")
            assertTrue(finished(flow.id))
            assertEquals("failed", vars(flow.id)["outcome"])
            assertEquals(emptyList(), incidents(flow.id).map { it.incidentMessage })
        }
    }

    @Test
    fun `a later step's wrapper carrying an earlier wrapper's cap is still rerun on its first failure`() {
        withOnly(listOf("later-a")) {
            val before = sandbox.started.size
            val flow = startWrapped(200L, "work", Vars.FAIL_RERUN_OF to "an-earlier-wrapper")
            drainJobs(flow.id)
            exitRun(flow.id, 200L, 1, "smith: crashed")
            assertEquals(before + 2, sandbox.started.size, "rerun: the cap is this wrapper's only")
            exitRun(flow.id, 200L, 0, "smith: done", asked)
            assertTrue(finished(flow.id))
            assertEquals(flow.id, vars(flow.id)[Vars.FAIL_RERUN_OF])
        }
    }

    @Test
    fun `a diagnose run that hits a session limit is not rerun, its failure is its outcome`() {
        withOnly(listOf("diag-a", "diag-b")) {
            val before = sandbox.started.size
            val flow = startWrapped(195L, "diagnose")
            drainJobs(flow.id)
            exitRun(flow.id, 195L, 1, limitLog)
            assertEquals(before + 1, sandbox.started.size, "no rerun")
            assertTrue(finished(flow.id))
            assertEquals("failed", vars(flow.id)["outcome"])
            assertEquals(emptyList(), incidents(flow.id).map { it.incidentMessage })
            assertNotNull(usages.get("diag-a")!!.limitRejectedReset, "the account is still marked rejected")
        }
    }
}

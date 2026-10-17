package com.codefrak.engine.doctor

import com.codefrak.engine.intake.tickets
import com.codefrak.engine.accounts.AccountUsages
import com.codefrak.engine.accounts.AiAccounts
import com.codefrak.engine.accounts.MeterReading
import com.codefrak.engine.camunda.Actor
import com.codefrak.engine.camunda.IncidentRetrier
import com.codefrak.engine.chore.FakeGitHubPulls
import com.codefrak.engine.chore.FakeGitPush
import com.codefrak.engine.chore.ScriptedSandbox
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.events.EventRecorder
import com.codefrak.engine.intake.TicketRecorder
import com.codefrak.engine.intake.FlowCanceller
import com.codefrak.engine.jev.FakeJevDecider
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.mcp.DoctorHistoryTool
import com.codefrak.engine.mcp.DoctorReads
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.runAllJobs
import com.codefrak.engine.smith.SmithExitNotifier
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.ensureTestAccount
import com.codefrak.engine.smith.dropTestAccount
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.runtime.ProcessInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.test.context.TestPropertySource
import org.springframework.web.server.ResponseStatusException
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The doctor's tick (engine/README.md, "Doctor"), called by hand with its clock stepped an interval (an hour here)
 * per tick, on chores placed by scripted Smiths or started at an activity. Every running flow is removed before
 * each test, so a tick sees only that test's tickets.
 */
@SpringBootTest
@TestPropertySource(properties = ["engine.smith.sandbox=fake", "engine.doctor.enabled=true", "engine.doctor.interval=PT1H"])
class DoctorTickTest {
    @TestConfiguration
    class Fakes {
        @Bean @Primary fun github() = TicketRecorder()
        @Bean @Primary fun tickets(github: com.codefrak.engine.intake.TicketRecorder) = github.tickets()
        @Bean @Primary fun pulls() = FakeGitHubPulls()
        @Bean @Primary fun git() = FakeGitPush()
        @Bean @Primary fun sandbox() = ScriptedSandbox()
        @Bean @Primary fun jev(calls: JevCalls) = FakeJevDecider(calls)
    }

    @Autowired lateinit var doctor: Doctor
    @Autowired lateinit var stored: DoctorPositions
    @Autowired lateinit var runs: DoctorHistory
    @Autowired lateinit var positions: TicketPositions
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var accounts: AiAccounts
    @Autowired lateinit var exits: SmithExitNotifier
    @Autowired lateinit var sandbox: ScriptedSandbox
    @Autowired lateinit var retrier: IncidentRetrier
    @Autowired lateinit var access: Access
    @Autowired lateinit var targetRepos: TargetRepos
    @Autowired lateinit var jev: FakeJevDecider
    @Autowired lateinit var context: ApplicationContext
    @Autowired lateinit var bus: EngineEvents
    @Autowired lateinit var tick: DoctorTick
    @Autowired lateinit var sightings: DoctorSightings
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var github: TicketRecorder
    @Autowired lateinit var canceller: FlowCanceller
    @Autowired lateinit var usages: AccountUsages
    @Autowired lateinit var reads: DoctorReads

    private val repo = "acme/tickets"
    private val t0 = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS)
    private fun at(hours: Long) = t0.plusHours(hours)

    private val done = """{"outcome":"done","title":"Tidy the readme","summary":"Tidied it.","addressed":[]}"""
    private val unfixable = """{"outcome":"unfixable","diagnosis":"The ticket asks for a file that does not exist.",
        "defect":{"where":"none","summary":"A file that does not exist"}}"""
    private val badTarget = """{"outcome":"retry","target":"no-such-incident","diagnosis":"A blip.","defect":{"where":"engine","summary":"A blip"}}"""

    @BeforeEach
    fun setUp() {
        runtime.createProcessInstanceQuery().rootProcessInstances().list()
            .forEach { runtime.deleteProcessInstance(it.id, "doctor test reset", true) }
        accounts.ensureTestAccount()
        targetRepos.add(repo)
        access.add("lundmatt1", "test")
        jev.reset()
        sandbox.reset()
    }

    @AfterEach
    fun dropTestAccount() { accounts.dropTestAccount() }

    private fun runAllJobs() = management.runAllJobs(exits)

    private fun script(mode: String, result: String) = sandbox.script.getOrPut(mode) { ArrayDeque() }.addLast(result)

    /** A chore whose first work run exits 1, and its automatic rerun too: it stops on an incident. */
    private fun stuckChore(issue: Long): ProcessInstance {
        script("work", done)
        sandbox.failNext = 2
        val chore = runtime.createMessageCorrelation("chore filed")
            .setVariables(mapOf("repo" to repo, "issue" to issue)).correlateStartMessage()
        runAllJobs()
        assertTrue(runtime.createIncidentQuery().count() > 0, "the failed work run left an incident")
        return chore
    }

    /** A ticket flow started straight at [activity], as if it had got there. */
    private fun placed(flow: String, activity: String, issue: Long): ProcessInstance =
        runtime.createProcessInstanceByKey(flow).startBeforeActivity(activity)
            .setVariables(mapOf("repo" to repo, "issue" to issue)).execute(true, true)

    @Test
    fun `with no ticket running a tick does nothing and stores nothing`() {
        assertEquals(DoctorTickReport.IDLE, doctor.tick(at(0)))
        assertEquals(emptyList(), stored.all())
    }

    @Test
    fun `a new ticket on an incident is stuck on the first tick and gets one doctor run`() {
        val chore = stuckChore(501)

        val first = doctor.tick(at(0))
        assertEquals(1, first.tickets)
        assertEquals(listOf(chore.id), first.stuck, "an open incident needs no second tick")
        assertEquals(1, first.started.size)
        val row = assertNotNull(stored.get(chore.id))
        assertEquals(at(0).toInstant(), row.since.toInstant())
        assertTrue(TicketPosition.parseKey(row.position).any { it.startsWith("incident:") }, row.position)
        val run = runs.of(repo, 501).single()
        assertEquals(first.started.single(), run.id)
        assertEquals(chore.id, run.rootInstanceId)
        assertEquals("running", run.outcome)
        assertEquals(TicketPosition.parseKey(row.position), run.position)
        assertEquals(at(0).toInstant(), run.stuckSince.toInstant(), "stuck since the tick that first saw the position")
        val doctorRun = runtime.createProcessInstanceQuery().processInstanceId(run.id).singleResult()
        assertEquals(chore.id, runtime.getVariable(doctorRun.id, DoctorVars.ROOT))
        assertEquals(501L, runtime.getVariable(doctorRun.id, DoctorVars.ISSUE))
        assertEquals(null, runtime.getVariable(doctorRun.id, "issue"), "a doctor run is not taken for the ticket's own flow")

        val second = doctor.tick(at(1))
        assertEquals(listOf(chore.id), second.stuck)
        assertEquals(emptyList(), second.started, "one doctor run per stuck episode")
        assertEquals(1, runs.of(repo, 501).size)
    }

    @Test
    fun `a ticket whose last position is older than two intervals starts again from a baseline`() {
        val chore = stuckChore(502)
        val run = doctor.tick(at(0)).started.single()
        runs.finish(run, "A blip.", "retry", null, "failed", null)

        doctor.tick(at(3))
        assertEquals(at(3).toInstant(), stored.get(chore.id)!!.since.toInstant(), "the engine was down: the old row is only a baseline")
    }

    @Test
    fun `back after a gap at the same position a doctor run judged none, the episode goes on and no second run starts`() {
        val chore = stuckChore(530)
        assertEquals(1, doctor.tick(at(0)).started.size)
        script("doctor", unfixable)
        runAllJobs()
        assertEquals("none", runs.of(repo, 530).single().outcome)

        val back = doctor.tick(at(3))
        assertEquals(listOf(chore.id), back.stuck)
        assertEquals(emptyList(), back.started, "the gap alone is no new episode")
        assertEquals(at(0).toInstant(), stored.get(chore.id)!!.since.toInstant(), "the episode keeps its since")
        assertEquals(emptyList(), doctor.tick(at(4)).started)
        assertEquals(1, runs.of(repo, 530).size)
    }

    @Test
    fun `back after a gap at the same position a doctor run still runs on, no second run starts`() {
        val chore = stuckChore(534)
        assertEquals(1, doctor.tick(at(0)).started.size)

        // A slow tick (or a lease handover) is a gap too: the run started at(0) has not ended, so the episode goes on.
        val back = doctor.tick(at(3))
        assertEquals(listOf(chore.id), back.stuck)
        assertEquals(emptyList(), back.started)
        assertEquals(at(0).toInstant(), stored.get(chore.id)!!.since.toInstant())
        assertEquals(emptyList(), doctor.tick(at(6)).started)
        assertEquals(1, runs.of(repo, 534).size)
    }

    @Test
    fun `a ticket parked on no Smith token for its repo has one incident, however many ticks and gaps pass`() {
        val message = "no Smith token for $repo: PUT /repos/$repo/smith-token"
        script("work", done)
        sandbox.startFails = message
        val chore = runtime.createMessageCorrelation("chore filed")
            .setVariables(mapOf("repo" to repo, "issue" to 535L)).correlateStartMessage()
        runAllJobs()
        // Every open root-cause incident the ticket's row in GET /needs would list: its own tree's, and its doctor runs'.
        fun ticketIncidents() = runtime.createIncidentQuery().list()
            .filter { it.rootCauseIncidentId == null || it.rootCauseIncidentId == it.id }
            .filter { inc -> val root = retrier.rootOf(inc.processInstanceId)
                root.id == chore.id || runtime.getVariable(root.id, DoctorVars.ROOT) == chore.id }
        assertEquals(listOf(message), ticketIncidents().map { it.incidentMessage })

        for (hour in listOf(0L, 1L, 3L, 4L, 7L, 10L)) {
            val report = doctor.tick(at(hour))
            assertEquals(emptyList(), report.started, "no doctor run: its own Smith could not start either (at $hour)")
            assertTrue(chore.id !in report.stuck, "it waits on the user's Smith token")
            runAllJobs()
        }
        assertEquals(1, ticketIncidents().size, "one failure, one incident")
        assertEquals(0L, runtime.createProcessInstanceQuery().processDefinitionKey(Doctor.PROCESS)
            .variableValueEquals(DoctorVars.ROOT, chore.id).count())
        assertTrue(Doctor.noSmithToken("the Smith token can read $repo but not push to it; no Smith runs on $repo until PUT /repos/$repo/smith-token"))
        assertTrue(!Doctor.noSmithToken("smith run 5 ended failed; see its log"))
    }

    @Test
    fun `back after a gap at the same position a doctor run applied a remedy on, no second run starts`() {
        val chore = stuckChore(531)
        val key = positions.of(chore.id).keyText
        stored.save(chore.id, repo, 531, key, at(0), at(0))
        runs.start("applied-531", repo, 531, chore.id, key, at(0))
        runs.finish("applied-531", "A blip.", "retry", null, "applied", null)

        assertEquals(emptyList(), doctor.tick(at(3)).started)
        assertEquals(at(0).toInstant(), stored.get(chore.id)!!.since.toInstant())
        assertEquals(1, runs.of(repo, 531).size)
    }

    @Test
    fun `back after a gap at the same position whose only doctor run failed, a new run starts`() {
        val chore = stuckChore(532)
        assertEquals(1, doctor.tick(at(0)).started.size)
        script("doctor", badTarget)
        runAllJobs()
        assertEquals("failed", runs.of(repo, 532).single().outcome)
        assertEquals(emptyList(), doctor.tick(at(1)).started, "within the episode a failed run is not repeated")

        val back = doctor.tick(at(4))
        assertEquals(listOf(chore.id), back.stuck)
        assertEquals(1, back.started.size, "a failed Smith reached no verdict: the doctor tries again")
        assertEquals(at(4).toInstant(), stored.get(chore.id)!!.since.toInstant())
        assertEquals(at(4).toInstant(), runs.get(back.started.single())!!.stuckSince.toInstant())
        assertEquals(2, runs.of(repo, 532).size)
    }

    @Test
    fun `back after a gap at a new incident, the old row is a baseline and the new incident gets its run`() {
        val chore = stuckChore(533)
        assertEquals(1, doctor.tick(at(0)).started.size)
        script("doctor", unfixable)
        runAllJobs()
        assertEquals("none", runs.of(repo, 533).single().outcome)
        val before = stored.get(chore.id)!!.position

        val incident = runtime.createIncidentQuery().list().first { it.rootCauseIncidentId == null || it.rootCauseIncidentId == it.id }
        script("work", done)
        sandbox.failNext = 1
        retrier.retryAsSystem(incident.id, "test")
        runAllJobs()
        assertTrue(runtime.createIncidentQuery().count() > 0, "the retried work run failed again")
        assertTrue(positions.of(chore.id).keyText != before, "a new incident is a new position")

        val back = doctor.tick(at(3))
        assertEquals(listOf(chore.id), back.stuck, "an open incident is stuck at once, as on any baseline")
        assertEquals(1, back.started.size)
        assertEquals(at(3).toInstant(), stored.get(chore.id)!!.since.toInstant())
        assertEquals(2, runs.of(repo, 533).size)
    }

    @Test
    fun `a ticket that moved is not stuck, a running Smith is a legitimate wait, and its exited sandbox is not`() {
        val chore = stuckChore(503)
        val incident = runtime.createIncidentQuery().list().first { it.rootCauseIncidentId == null || it.rootCauseIncidentId == it.id }

        script("work", done)
        sandbox.holdMode = "work"
        retrier.retryAsSystem(incident.id, "test")
        runAllJobs()
        val moved = doctor.tick(at(1))
        assertEquals(emptyList(), moved.stuck, "a first sight with no incident is only a baseline")
        assertEquals(at(1).toInstant(), stored.get(chore.id)!!.since.toInstant())
        assertTrue("smith-run:event-smith-exited@smith" in TicketPosition.parseKey(stored.get(chore.id)!!.position))

        assertEquals(emptyList(), doctor.tick(at(2)).stuck, "a Smith whose sandbox runs is waited on")

        val handle = "container-${sandbox.started.size}"
        sandbox.stop(handle)
        assertEquals(emptyList(), doctor.tick(at(3)).stuck, "the sandbox just exited: that is a move")
        assertTrue("smith-run:event-smith-exited@none" in TicketPosition.parseKey(stored.get(chore.id)!!.position))
        val stuck = doctor.tick(at(4))
        assertEquals(listOf(chore.id), stuck.stuck, "an exit nothing collected is stuck on the tick after")
        assertEquals(at(3).toInstant(), runs.of(repo, 503).single().stuckSince.toInstant())
    }

    @Test
    fun `a user task, the feature's design question and the intake's authorized label are waits on the user, never stuck`() {
        val waits = listOf(
            placed("chore", "user-task-answer-question", 511),
            placed("feature", "user-task-answer-question", 512),
            placed("intake", "event-intake-authorized", 513),
        )
        doctor.tick(at(0))
        val report = doctor.tick(at(1))
        assertEquals(3, report.tickets)
        assertEquals(emptyList(), report.stuck)
        assertEquals(emptyList(), report.started)
        waits.forEach { assertTrue("@user" in stored.get(it.id)!!.position, stored.get(it.id)!!.position) }
    }

    @Test
    fun `no second doctor run while one is going, and none again for the same stuck episode after it ended`() {
        val chore = stuckChore(521)
        assertEquals(1, doctor.tick(at(0)).started.size)

        val going = doctor.tick(at(2))
        assertEquals(listOf(chore.id), going.stuck)
        assertEquals(emptyList(), going.started, "a doctor run on it is going")

        script("doctor", unfixable)
        runAllJobs()
        assertEquals("none", runs.of(repo, 521).single().outcome)
        assertEquals(emptyList(), doctor.tick(at(3)).started, "this episode had its run")
        assertEquals(1, runs.of(repo, 521).size)
    }

    /** The stream (engine/README.md, "Events"): `setting` `doctor` when a fix starts, `heartbeat` with the counts on every tick. */
    @Test
    fun `a doctor run started is a doctor setting, and the tick publishes a heartbeat with its counts`() = EventRecorder(bus).use { rec ->
        val chore = stuckChore(590)
        val report = doctor.tick(at(0))
        val setting = rec.of("setting").single()
        assertEquals(listOf("*", "doctor", "started", report.started.single(), chore.id, repo, 590L),
            listOf(setting.str("repo"), setting.str("name"), setting.data["value"]["state"].asString(), setting.data["value"]["run"].asString(),
                setting.data["value"]["root"].asString(), setting.data["value"]["repo"].asString(), setting.data["value"]["number"].asLong()))
        assertEquals(emptyList(), rec.of("heartbeat"), "a tick called by hand is no heartbeat")

        tick.tick()
        val beat = rec.of("heartbeat").single()
        assertEquals(listOf("*", 1L, 0L), listOf(beat.str("repo"), beat.long("running"), beat.long("waiting")), "one chore, parked on nothing")
        assertTrue(beat.long("incidents")!! >= 1, beat.toString())
        assertEquals(1, rec.of("setting").size, "no second doctor run, so no second setting")
    }

    /** Runs at the chore's place, as earlier episodes of it left them: stuck since [since], each ended [outcome]. */
    private fun earlierRuns(chore: ProcessInstance, issue: Long, outcome: String, vararg since: OffsetDateTime): String {
        val key = positions.of(chore.id).keyText
        since.forEachIndexed { i, s ->
            runs.start("$outcome-$issue-$i", repo, issue, chore.id, key, s)
            runs.finish("$outcome-$issue-$i", "x", null, null, outcome, "x")
        }
        return key
    }

    private fun stuckBrief(run: String): Map<String, Any?> =
        jacksonObjectMapper().readValue(runtime.getVariable(run, Vars.STUCK) as ByteArray)

    @Test
    fun `a place whose last 3 runs failed is backed off until engine_doctor_backoff after the last, with one backoff frame`() = EventRecorder(bus).use { rec ->
        val chore = stuckChore(540)
        earlierRuns(chore, 540, "failed", at(-3), at(-2), at(-1))

        val first = doctor.tick(at(0))
        assertEquals(listOf(chore.id), first.stuck, "a backed-off place is still stuck")
        assertEquals(emptyList(), first.started, "the 3rd failed run in a row backs the place off")
        assertEquals(at(0).toInstant(), stored.get(chore.id)!!.since.toInstant(), "the position is still recorded")
        val frame = rec.of("setting").single()
        assertEquals(listOf("doctor", "backoff", "failed", chore.id, 540L), listOf(frame.str("name"), frame.data["value"]["state"].asString(),
            frame.data["value"]["reason"].asString(), frame.data["value"]["root"].asString(), frame.data["value"]["number"].asLong()))
        assertEquals(emptyList(), doctor.tick(at(1)).started)
        assertEquals(1, rec.of("setting").size, "one backoff frame per episode")

        val expired = doctor.tick(at(7))
        assertEquals(1, expired.started.size, "engine.doctor.backoff (PT6H) after the last run, the doctor runs again")
        @Suppress("UNCHECKED_CAST")
        val earlier = stuckBrief(expired.started.single())["earlier"] as List<Map<String, Any?>>
        assertEquals(3, earlier.size, "the brief names the earlier runs at this place")
        assertTrue(earlier.all { it["outcome"] == "failed" && it["remedy"] == null && it["startedAt"] != null }, "$earlier")
        assertEquals(listOf("failed-540-2", "failed-540-1", "failed-540-0").toSet(), earlier.map { it["id"] }.toSet())
    }

    @Test
    fun `the first brief at a place has no earlier runs`() {
        stuckChore(541)
        val report = doctor.tick(at(0))
        assertEquals(1, report.started.size)
        assertEquals(emptyList<Any>(), stuckBrief(report.started.single())["earlier"])
    }

    @Test
    fun `two failed runs back nothing off`() {
        val chore = stuckChore(542)
        earlierRuns(chore, 542, "failed", at(-2), at(-1))
        val report = doctor.tick(at(0))
        assertEquals(1, report.started.size, "only the 3rd failed run in a row backs a place off")
        assertEquals(2, (stuckBrief(report.started.single())["earlier"] as List<*>).size)
    }

    @Test
    fun `a place whose last sighting is lumped into a bug not yet fixed is backed off, and a fixed bug lifts it`() = EventRecorder(bus).use { rec ->
        val chore = stuckChore(543)
        val key = earlierRuns(chore, 543, "none", at(-1))
        val position = TicketPosition.parseKey(key)
        val seen = sightings.record(NewSighting("none-543-0", null, repo, 543, chore.id, "unfixable", null, "engine", "A blip",
            Signature.of("engine", position, listOf(IncidentShape("task-read-result", "failedJob", "smith run 543 ended failed; see its log", "exit:1")))))
        assertNull(seen.bug, "a new signature is stored with no bug")
        jdbc.sql("INSERT INTO engine.doctor_bug (repo, issue, state) VALUES (:r, 9543, 'open')").param("r", repo).update()
        val bug = jdbc.sql("SELECT MAX(id) FROM engine.doctor_bug").query(Long::class.java).single()
        jdbc.sql("UPDATE engine.doctor_sighting SET bug_id = :b, lumped_by = 'exact' WHERE id = :id").param("b", bug).param("id", seen.id).update()
        assertEquals("open", runs.of(repo, 543).single().sighting?.bug?.state)

        assertEquals(emptyList(), doctor.tick(at(0)).started, "lumped into an open bug: backed off")
        assertEquals("bug", rec.of("setting").single().data["value"]["reason"].asString())

        jdbc.sql("UPDATE engine.doctor_bug SET state = 'fixed' WHERE id = :b").param("b", bug).update()
        assertEquals(1, doctor.tick(at(1)).started.size, "a fixed bug lifts the back-off")
    }

    @Test
    fun `a bug closed as fixed after the last run at a place lifts its failed back-off, and gives a run episode a fresh look`() {
        val chore = stuckChore(544)
        val key = earlierRuns(chore, 544, "failed", at(-3), at(-2), at(-1))
        jdbc.sql("UPDATE engine.doctor_run SET started_at = stuck_since WHERE id LIKE 'failed-544-%'").update()
        val seen = sightings.record(NewSighting("failed-544-2", null, repo, 544, chore.id, "unfixable", null, "engine", "A blip",
            Signature.of("engine", TicketPosition.parseKey(key), emptyList())))
        jdbc.sql("INSERT INTO engine.doctor_bug (repo, issue, state) VALUES (:r, 9544, 'open')").param("r", repo).update()
        val bug = jdbc.sql("SELECT MAX(id) FROM engine.doctor_bug").query(Long::class.java).single()
        jdbc.sql("UPDATE engine.doctor_sighting SET bug_id = :b, lumped_by = 'exact' WHERE id = :id").param("b", bug).param("id", seen.id).update()
        fun fixedAt(at: OffsetDateTime) =
            jdbc.sql("UPDATE engine.doctor_bug SET state = 'fixed', closed_at = :at WHERE id = :b").param("at", at).param("b", bug).update()
        assertEquals(emptyList(), doctor.tick(at(0)).started, "3 failed runs: backed off")

        fixedAt(at(-24))
        assertEquals(emptyList(), doctor.tick(at(1)).started, "a fix older than the runs lifts nothing")

        fixedAt(at(-1).plusMinutes(30))
        val run = doctor.tick(at(2)).started.single()
        runtime.deleteProcessInstance(run, "test: the run ended")
        runs.finish(run, "x", null, null, "failed", "x")
        assertEquals(emptyList(), doctor.tick(at(3)).started, "the episode had its look since the fix")

        // Fixed after that run started: the same episode, still stuck, gets another.
        fixedAt(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1))
        assertEquals(1, doctor.tick(at(4)).started.size, "a fresh look past the episode's run")
    }

    @Test
    fun `the tick is a bean when enabled, and its interval is the property`() {
        assertEquals(1, context.getBeansOfType(DoctorTick::class.java).size)
        val scheduled = DoctorTick::class.java.getMethod("tick").getAnnotation(Scheduled::class.java)
        assertEquals("\${engine.doctor.interval:PT1M}", scheduled.fixedDelayString)
        assertEquals("\${engine.doctor.interval:PT1M}", scheduled.initialDelayString)
    }

    // ---- a doctor run ends with its ticket's place, is cancelled alone, and resumes when its account window reopens (#1084) ----

    /** The doctor run on [chore], started by a tick at [hours]. */
    private fun doctorOn(chore: ProcessInstance, hours: Long = 0): String = doctor.tick(at(hours)).started.single().also {
        assertEquals(chore.id, runtime.getVariable(it, DoctorVars.ROOT))
    }

    /** The doctor run's health check job failed for good: it is parked on an incident, as the stranded runs were. */
    private fun parkOnIncident(run: String) {
        management.setJobRetries(management.createJobQuery().processInstanceId(run).singleResult().id, 0)
        assertNotNull(incidentOf(run), "the doctor run is parked on an incident")
    }

    private fun running(id: String) = runtime.createProcessInstanceQuery().processInstanceId(id).count() == 1L

    private fun sightingCount(run: String) =
        jdbc.sql("SELECT COUNT(*) FROM engine.doctor_sighting WHERE doctor_run_id = :id").param("id", run).query(Long::class.java).single()

    @Test
    fun `a doctor run parked on an incident whose ticket closed is ended on the next tick, with no sighting`() {
        val chore = stuckChore(560)
        val run = doctorOn(chore)
        parkOnIncident(run)
        assertEquals("running", runs.get(run)!!.outcome)

        runtime.deleteProcessInstance(chore.id, "test: the ticket closed")
        val report = doctor.tick(at(1))
        assertEquals(listOf(run), report.ended)
        assertTrue(!running(run), "the doctor run is gone")
        val row = runs.get(run)!!
        assertNotNull(row.endedAt)
        assertEquals("ended", row.outcome)
        assertTrue("closed" in row.detail!!, row.detail)
        assertEquals(0L, sightingCount(run), "an ended run records no sighting")
        assertEquals(emptyList(), doctor.tick(at(2)).ended, "ended once")
    }

    @Test
    fun `a doctor run whose ticket moved off its position is ended on the next tick, and read_doctor_history shows it`() {
        val chore = stuckChore(561)
        val run = doctorOn(chore)
        parkOnIncident(run)
        assertEquals(emptyList(), doctor.tick(at(1)).ended, "the ticket is still where the run was started for")

        val incident = runtime.createIncidentQuery().list().first { it.rootCauseIncidentId == null || it.rootCauseIncidentId == it.id }
        script("work", done)
        sandbox.holdMode = "work"
        retrier.retryAsSystem(incident.id, "test")
        runAllJobs()
        val report = doctor.tick(at(2))
        assertEquals(listOf(run), report.ended)
        assertTrue(!running(run))
        assertTrue(running(chore.id), "the ticket's own flow goes on")
        val row = runs.get(run)!!
        assertEquals("ended", row.outcome)
        assertTrue("moved" in row.detail!!, row.detail)
        assertEquals(0L, sightingCount(run))

        val history: Map<String, Any?> = jacksonObjectMapper().readValue(DoctorHistoryTool(reads).call(mapOf("ticket" to 561, "repo" to repo)).text)
        val shown = (history["runs"] as List<*>).map { it as Map<*, *> }.single { it["id"] == run }
        assertEquals("ended", shown["outcome"])
        assertTrue("moved" in shown["detail"] as String, shown.toString())
        assertNotNull(shown["endedAt"])
        assertNull(shown["sighting"])
    }

    @Test
    fun `cancelling a doctor run ends it alone - its Smith stopped, its row finished, the ticket left open, unlabelled and running`() {
        val chore = stuckChore(562)
        val run = doctorOn(chore)
        script("doctor", unfixable)
        sandbox.holdMode = "doctor"
        runAllJobs()
        val handle = "container-${sandbox.started.size}"
        assertEquals("doctor", sandbox.started.last().mode)
        val labels = github.labelled.size

        val failed = canceller.cancel(run, Actor.Login("lundmatt1"), "not needed")
        assertEquals(emptyList(), failed)
        assertTrue(!running(run), "the doctor run is deleted")
        assertTrue(handle in sandbox.stopped, "its running Smith is stopped")
        val row = runs.get(run)!!
        assertNotNull(row.endedAt)
        assertEquals("cancelled", row.outcome)
        assertTrue("not needed" in row.detail!!, row.detail)
        assertEquals(0L, sightingCount(run))
        assertTrue((repo to 562L) !in github.closed, "the ticket is not closed")
        assertEquals(labels, github.labelled.size, "no label is written")
        assertTrue(running(chore.id), "the ticket's flow still runs")
        val comment = github.comments.last { it.first == repo && it.second == 562L }.third
        assertTrue(comment.startsWith("@lundmatt1 cancelled the doctor run (`$run`) on this chore: not needed"), comment)
    }

    @Test
    fun `an untrusted login may not cancel or retry a doctor run`() {
        val chore = stuckChore(563)
        val run = doctorOn(chore)
        parkOnIncident(run)
        val e = assertFailsWith<ResponseStatusException> { canceller.cancel(run, Actor.Login("mallory"), "x") }
        assertEquals(403, e.statusCode.value())
        assertEquals("@mallory is not a trusted login", e.reason)
        val r = assertFailsWith<ResponseStatusException> { retrier.retry(assertNotNull(incidentOf(run)).id, "mallory") }
        assertEquals("@mallory is not a trusted login", r.reason)
        assertTrue(running(run))
        assertEquals("running", runs.get(run)!!.outcome)
    }

    /** Every anthropic account but [blocked] is disabled, and it is rejected until two hours from now: answers that reset and an undo. */
    private fun blockAccounts(blocked: String): Pair<java.time.Instant, () -> Unit> {
        val others = accounts.list().filter { it.provider == "anthropic" && it.enabled }.map { it.name }
        others.forEach { accounts.setEnabled(it, false) }
        accounts.add(blocked, "anthropic"); accounts.setCredential(blocked, "sk-ant-oat01-$blocked")
        val soon = OffsetDateTime.now(ZoneOffset.UTC).plusHours(2).withNano(0)
        usages.record(blocked, MeterReading(1.0, "rejected", soon, 0.5, "allowed", soon.plusDays(3), "rejected"), 429)
        return soon.toInstant() to { accounts.remove(blocked); others.forEach { accounts.setEnabled(it, true) } }
    }

    /** The open root-cause incident under the doctor run [run], or null. */
    private fun incidentOf(run: String) = runtime.createIncidentQuery().list()
        .filter { it.rootCauseIncidentId == null || it.rootCauseIncidentId == it.id }
        .firstOrNull { runCatching { retrier.rootOf(it.processInstanceId).id }.getOrNull() == run }

    @Test
    fun `a doctor run parked on no account available until t is retried by the engine once t has passed`() {
        val chore = stuckChore(564)
        val (until, unblock) = blockAccounts("resume-a")
        val (run, incident) = try {
            val run = doctorOn(chore)
            script("doctor", unfixable)
            runAllJobs()
            val parked = assertNotNull(incidentOf(run), "start smith found no account")
            assertEquals("no account available until $until", parked.incidentMessage)
            assertEquals(until, Doctor.reopens(parked.incidentMessage))

            val early = doctor.tick(at(1))
            assertEquals(emptyList(), early.resumed, "t has not passed")
            assertEquals(emptyList(), early.ended, "the ticket is where it was")
            assertNotNull(incidentOf(run))
            run to parked.id
        } finally { unblock() }

        val comments = github.comments.size
        val report = doctor.tick(at(3))
        assertEquals(listOf(incident), report.resumed)
        assertTrue(runtime.createIncidentQuery().incidentId(incident).count() == 0L, "the incident is resolved")
        val comment = github.comments.drop(comments).single { it.first == repo && it.second == 564L }.third
        assertTrue(comment.startsWith("The engine's doctor retried") && "the doctor run (`$run`) on this chore" in comment, comment)
        runAllJobs()
        assertEquals("none", runs.get(run)!!.outcome, "the resumed run went on to its verdict")
    }

    @Test
    fun `a doctor run whose t is already past when the engine starts is resumed on the first tick`() {
        val chore = stuckChore(565)
        val (_, unblock) = blockAccounts("resume-b")
        val run = try {
            doctorOn(chore).also { script("doctor", unfixable); runAllJobs() }
        } finally { unblock() }
        val incident = assertNotNull(incidentOf(run)).id

        // The engine was down past t: the doctor keeps nothing of it in memory, so the first tick reads t off the incident.
        val report = doctor.tick(at(5))
        assertEquals(listOf(incident), report.resumed)
        runAllJobs()
        assertEquals("none", runs.get(run)!!.outcome)
    }

    @Test
    fun `a ticket flow's own start smith parked on no account available until t is retried by the engine once t has passed`() {
        val (until, unblock) = blockAccounts("resume-c")
        val (chore, incident) = try {
            script("work", done)
            val chore = runtime.createMessageCorrelation("chore filed")
                .setVariables(mapOf("repo" to repo, "issue" to 566L)).correlateStartMessage()
            runAllJobs()
            val parked = runtime.createIncidentQuery().list()
                .filter { it.rootCauseIncidentId == null || it.rootCauseIncidentId == it.id }
                .single { retrier.rootOf(it.processInstanceId).id == chore.id }
            assertEquals("no account available until $until", parked.incidentMessage)

            val early = doctor.tick(at(1))
            assertTrue(parked.id !in early.resumed, "t has not passed")
            assertTrue(chore.id !in early.stuck, "an account wait is waiting, not stuck")
            assertEquals(emptyList(), early.started, "no doctor run before t")
            assertEquals(0L, runtime.createProcessInstanceQuery().processDefinitionKey(Doctor.PROCESS)
                .variableValueEquals(DoctorVars.ROOT, chore.id).count(), "no doctor run, so no sighting or ask")
            assertEquals(1L, runtime.createIncidentQuery().incidentId(parked.id).count(), "the incident is still open")
            chore to parked.id
        } finally { unblock() }

        val comments = github.comments.size
        val smiths = sandbox.started.size
        val report = doctor.tick(at(3))
        assertTrue(incident in report.resumed, report.resumed.toString())
        assertEquals(0L, runtime.createIncidentQuery().incidentId(incident).count(), "the incident is resolved")
        assertTrue(github.comments.drop(comments).any { it.first == repo && it.second == 566L && it.third.startsWith("The engine's doctor retried") })
        assertEquals(emptyList(), report.started, "the resumed start is not doctored")
        runAllJobs()
        assertTrue(sandbox.started.drop(smiths).any { it.mode == "work" }, "the Smith started once t passed")
    }

    @Test
    fun `an incident with any other message is not resumed`() {
        assertNull(Doctor.reopens("smith run 5 ended failed; see its log"))
        assertNull(Doctor.reopens("no account available until soon"))
        assertEquals(java.time.Instant.parse("2026-10-05T12:00:00Z"), Doctor.reopens("no account can run Fable until 2026-10-05T12:00:00Z"))
    }
}

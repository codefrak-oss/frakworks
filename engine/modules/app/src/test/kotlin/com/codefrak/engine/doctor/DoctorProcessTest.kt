package com.codefrak.engine.doctor

import com.codefrak.engine.intake.tickets
import com.codefrak.engine.finished
import com.codefrak.engine.accounts.AiAccounts
import com.codefrak.engine.chore.FakeGitHubPulls
import com.codefrak.engine.chore.FakeGitPush
import com.codefrak.engine.chore.ScriptedSandbox
import com.codefrak.engine.intake.TicketRecorder
import com.codefrak.engine.jev.FakeJevDecider
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.SMITH_EXITED_MESSAGE
import com.codefrak.engine.smith.SandboxExit
import com.codefrak.engine.smith.SandboxExitNotifier
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.ensureTestAccount
import com.codefrak.engine.smith.dropTestAccount
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.runtime.Incident
import org.operaton.bpm.engine.runtime.ProcessInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The doctor process (bpmn/doctor.bpmn) on stuck chores, with scripted doctor Smiths: each remedy the engine applies,
 * the ones it refuses, and the engine.doctor_run row and sighting each run leaves. Every running flow is removed before each test.
 */
@SpringBootTest
@TestPropertySource(properties = ["engine.smith.sandbox=fake", "engine.doctor.interval=PT1H"])
class DoctorProcessTest {
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
    @Autowired lateinit var runs: DoctorHistory
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var history: HistoryService
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var repository: org.operaton.bpm.engine.RepositoryService
    @Autowired lateinit var accounts: AiAccounts
    @Autowired lateinit var exits: SandboxExitNotifier
    @Autowired lateinit var sandbox: ScriptedSandbox
    @Autowired lateinit var access: Access
    @Autowired lateinit var targetRepos: TargetRepos
    @Autowired lateinit var jev: FakeJevDecider
    @Autowired lateinit var sightings: DoctorSightings

    private val json = jacksonObjectMapper()
    private val repo = "acme/tickets"
    private val t0 = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS)

    private val done = """{"outcome":"done","title":"Tidy the readme","summary":"Tidied it.","addressed":[]}"""
    private val passed = """{"outcome":"passed","summary":"fine","findings":[]}"""
    private val approved = """{"outcome":"approved","summary":"fine","findings":[]}"""

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

    /** Runs every job, delivering every exited sandbox's exit except [keep]'s: the one the doctor is to find. */
    private fun runAllJobs(keep: String? = null) {
        repeat(100) {
            runtime.createExecutionQuery().messageEventSubscriptionName(SMITH_EXITED_MESSAGE).list().forEach { waiting ->
                val handle = runtime.getVariable(waiting.id, Vars.HANDLE) as? String ?: return@forEach
                val status = sandbox.status(handle)
                if (handle != keep && !status.running) exits.deliver(SandboxExit(handle, waiting.processInstanceId, status.exitCode ?: 0L))
            }
            val jobs = management.createJobQuery().withRetriesLeft().list()
            if (jobs.isEmpty()) return
            jobs.forEach { runCatching { management.executeJob(it.id) } }
        }
        error("no quiescence in 100 job rounds")
    }

    private fun script(mode: String, result: String) = sandbox.script.getOrPut(mode) { ArrayDeque() }.addLast(result)
    private fun chore(issue: Long): ProcessInstance = runtime.createMessageCorrelation("chore filed")
        .setVariables(mapOf("repo" to repo, "issue" to issue)).correlateStartMessage()
    private fun rootIncident(): Incident = runtime.createIncidentQuery().list().first { it.rootCauseIncidentId == null || it.rootCauseIncidentId == it.id }

    /** A chore whose first work run exits 1, and its automatic rerun too: it stops on an incident. */
    private fun stuckChore(issue: Long): ProcessInstance {
        script("work", done)
        sandbox.failNext = 2
        return chore(issue).also { runAllJobs() }
    }

    /** One tick: [chore] sits on an open incident, so it is stuck at once and gets its doctor run. */
    private fun doctorOn(chore: ProcessInstance): String {
        val started = doctor.tick(t0).started
        assertEquals(1, started.size, "one doctor run for ${chore.id}")
        return started.single()
    }

    private fun doctorBrief(): Map<String, Any?> =
        json.readValue<Brief>(sandbox.started.last { it.mode == "doctor" }.inputs.getValue("brief.json")).stuck!!

    @Test
    fun `retry — the engine retries the incident the Smith names, and the ticket goes on to the end`() {
        val chore = stuckChore(601)
        val incident = rootIncident()
        val run = doctorOn(chore)
        script("doctor", """{"outcome":"retry","target":"${incident.id}","diagnosis":"The sandbox lost its network.","defect":{"where":"engine","summary":"The sandbox lost its network"}}""")
        script("work", done); script("security", passed); script("review", approved)
        runAllJobs()

        val told = doctorBrief()
        assertEquals(chore.id, told["rootInstanceId"])
        assertEquals("chore", told["rootFlow"])
        @Suppress("UNCHECKED_CAST")
        assertTrue((told["incidents"] as List<Map<String, Any?>>).any { it["id"] == incident.id }, "the brief names the incident")
        assertTrue(history.finished(run))
        assertEquals("applied", history.createHistoricVariableInstanceQuery().processInstanceId(run).variableName(DoctorVars.OUTCOME).singleResult().value)
        assertTrue(history.finished(chore.id), "the retried chore went on to the end")

        val row = runs.of(repo, 601).single()
        assertEquals("applied" to "retry", row.outcome to row.remedy)
        assertEquals(incident.id, row.target)
        assertEquals("The sandbox lost its network.", row.diagnosis)
        assertNotNull(row.endedAt)
        assertEquals(sandbox.started.last { it.mode == "doctor" }.runId, row.smithRunId, "the smith-run whose log is the run's transcript")
        val seen = assertNotNull(row.sighting, "an applied retry is a sighting")
        assertEquals("retried" to "engine", seen.kind to seen.where)
        assertNull(seen.bug, "a new signature has no bug: nothing is filed")
        assertTrue(seen.tickets >= 1 && seen.runs >= seen.tickets, "counted over its signature, which other tests' retries may share: $seen")
        val signature = assertNotNull(sightings.signature(assertNotNull(sightings.signatureOf(seen.id))), "the signature is stored")
        assertEquals("engine", signature.first)
    }

    @Test
    fun `the brief's incident row for a twice-failed job carries both attempts, oldest first, beside its stacktrace`() {
        val (instance, job) = com.codefrak.engine.camunda.TwiceFailedJob.run(repository, runtime, management)
        val incident = runtime.createIncidentQuery().processInstanceId(instance).singleResult()
        val row = doctor.incidentRows(listOf(OpenIncident(incident.id, instance, "job-attempts-test", incident.activityId,
            incident.incidentType, incident.incidentMessage, job))).single()
        assertNotNull(row["stacktrace"])
        @Suppress("UNCHECKED_CAST")
        val attempts = row["attempts"] as List<Map<String, Any?>>
        assertEquals(listOf(com.codefrak.engine.camunda.TwiceFailedJob.FIRST, com.codefrak.engine.camunda.TwiceFailedJob.SECOND), attempts.map { it["message"] })
        assertTrue(attempts.all { it["at"] != null })
        assertEquals(incident.incidentMessage, attempts.last()["message"])
    }

    @Test
    fun `not-held — a later run at the same root and leaves after an applied retry names it`() {
        val chore = stuckChore(603)
        val first = doctorOn(chore)
        script("doctor", """{"outcome":"retry","target":"${rootIncident().id}","diagnosis":"A blip.","defect":{"where":"engine","summary":"A blip"}}""")
        script("work", done); sandbox.failMode = "work"
        runAllJobs()
        assertEquals("applied", runs.get(first)!!.outcome)
        assertTrue(!history.finished(chore.id), "the retried run failed again")

        val second = doctorOn(chore)
        script("doctor", """{"outcome":"unfixable","diagnosis":"The retry did not hold.","defect":{"where":"engine","summary":"A blip"}}""")
        runAllJobs()
        // Read after the jobs ran: the second run's sandbox (and its brief) starts in its first job.
        @Suppress("UNCHECKED_CAST")
        val earlier = doctorBrief()["earlier"] as List<Map<String, Any?>>
        assertEquals(listOf(first), earlier.map { it["id"] }, "the brief names the earlier run at this place")
        assertEquals("retry" to "applied", earlier.single()["remedy"] to earlier.single()["outcome"])

        val seen = assertNotNull(runs.get(second)!!.sighting)
        assertEquals("not-held", seen.kind)
        assertEquals(first, sightings.detail(seen.id)!!.second, "earlier_run_id is the retry that did not hold")
        assertEquals("retried", runs.get(first)!!.sighting!!.kind)
    }

    @Test
    fun `collect-exit — the engine collects the exit of a sandbox that exited unnoticed`() {
        script("work", done); sandbox.holdMode = "work"
        val chore = chore(602)
        runAllJobs()
        doctor.tick(t0)
        val handle = "container-1"
        sandbox.stop(handle)
        doctor.tick(t0.plusHours(1)).let { assertEquals(emptyList(), it.started, "the exit is a move") }
        val started = doctor.tick(t0.plusHours(2)).started
        val run = started.single()
        script("doctor", """{"outcome":"collect-exit","diagnosis":"The sandbox exited while the event stream was down.","defect":{"where":"engine","summary":"An exit missed"}}""")
        script("security", passed); script("review", approved)
        runAllJobs(keep = handle)

        @Suppress("UNCHECKED_CAST")
        val leaves = doctorBrief()["leaves"] as List<Map<String, Any?>>
        assertTrue(leaves.any { it["activityId"] == "event-smith-exited" && it["wait"] == "none" && it["sandboxHandle"] == handle }, "$leaves")
        val row = runs.of(repo, 602).single()
        assertEquals(run, row.id)
        assertEquals("applied" to "collect-exit", row.outcome to row.remedy)
        assertEquals("backstop" to "engine", row.sighting?.kind to row.sighting?.where)
        runAllJobs()
        assertTrue(history.finished(chore.id), "the collected run went on, and so did the chore")
    }

    @Test
    fun `healthy and unfixable change nothing, a target not in the position and a failed run are failed, one row each`() {
        val cases = listOf(
            Triple(611L, """{"outcome":"healthy","diagnosis":"It is fine."}""", "none"),
            Triple(612L, """{"outcome":"unfixable","diagnosis":"The ticket is wrong.","defect":{"where":"none","summary":"A wrong ticket"}}""", "none"),
            Triple(613L, """{"outcome":"retry","target":"no-such-incident","diagnosis":"A blip.","defect":{"where":"engine","summary":"A blip"}}""", "failed"),
            Triple(614L, """{"outcome":"run-job","target":"no-such-job","diagnosis":"A due job.","defect":{"where":"engine","summary":"A due job"}}""", "failed"),
            Triple(615L, """{"outcome":"healthy","diagnosis":"never read"}""", "failed"),
        )
        for ((issue, result, outcome) in cases) {
            runtime.createProcessInstanceQuery().rootProcessInstances().list()
                .forEach { runtime.deleteProcessInstance(it.id, "next case", true) }
            val chore = stuckChore(issue)
            val incident = rootIncident()
            val run = doctorOn(chore)
            script("doctor", result)
            if (issue == 615L) sandbox.failMode = "doctor"
            runAllJobs()

            val row = runs.of(repo, issue).single()
            assertEquals(outcome, row.outcome, "$issue: ${row.detail}")
            assertTrue(history.finished(run), "$issue: the doctor run ended")
            assertEquals(0L, runtime.createIncidentQuery().processInstanceId(run).count(), "$issue: a doctor run is never an incident")
            assertEquals(incident.id, rootIncident().id, "$issue: the ticket's incident is left alone")
            when (issue) {
                613L -> assertTrue("not open" in row.detail!!, row.detail)
                614L -> assertTrue("not a due job" in row.detail!!, row.detail)
                615L -> { assertNull(row.remedy); assertNotNull(row.detail) }
                else -> { assertEquals(result.substringAfter("\"outcome\":\"").substringBefore('"'), row.remedy); assertNull(row.detail) }
            }
            // A sighting for every run but a healthy one; a failed doctor run is one against the doctor itself.
            val seen = row.sighting
            when (issue) {
                611L -> assertNull(seen, "healthy is no sighting")
                612L -> assertEquals("unfixable" to "none", seen?.kind to seen?.where)
                613L, 614L -> assertEquals("remedy-failed" to "engine", seen?.kind to seen?.where)
                615L -> {
                    assertEquals("doctor-failed" to "engine", seen?.kind to seen?.where)
                    assertTrue(sightings.signature(sightings.signatureOf(seen!!.id)!!)!!.third!!.startsWith("doctor-failed "))
                }
            }
        }
    }
}

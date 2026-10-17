package com.codefrak.engine.sdk

import com.codefrak.engine.intake.tickets
import com.codefrak.engine.chore.FakeGitHubPulls
import com.codefrak.engine.chore.FakeGitPush
import com.codefrak.engine.chore.ScriptedSandbox
import com.codefrak.engine.doctor.Doctor
import com.codefrak.engine.doctor.DoctorHistory
import com.codefrak.engine.intake.TicketRecorder
import com.codefrak.engine.jev.FakeJevDecider
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.runAllJobs
import com.codefrak.engine.smith.SmithExitNotifier
import com.codefrak.engine.smith.ensureTestAccount
import com.codefrak.engine.smith.dropTestAccount
import com.codefrak.sdk.Diagnosis
import com.codefrak.sdk.Incident
import com.codefrak.sdk.Remedy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.runtime.ProcessInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A plugin doctor bean, as a plugin jar would contribute one: answers what the test sets, and remembers what it was asked. */
class FakePluginDoctor : com.codefrak.sdk.Doctor {
    var answer: Diagnosis? = null
    var fail = false
    val asked = mutableListOf<Incident>()
    override fun diagnose(incident: Incident): Diagnosis? {
        asked += incident
        if (fail) error("plugin broke")
        return answer
    }
}

/**
 * The doctor's tick asks the plugin doctors ([PluginDoctors]) about a stuck ticket's open incident before it starts
 * a Smith doctor run (engine/README.md, "Custom jobs and the SDK"), on a chore parked by two failed work runs.
 */
@SpringBootTest
@TestPropertySource(properties = ["engine.smith.sandbox=fake", "engine.doctor.enabled=true", "engine.doctor.interval=PT1H"])
class PluginDoctorsTest {
    @TestConfiguration
    class Fakes {
        @Bean @Primary fun github() = TicketRecorder()
        @Bean @Primary fun tickets(github: com.codefrak.engine.intake.TicketRecorder) = github.tickets()
        @Bean @Primary fun pulls() = FakeGitHubPulls()
        @Bean @Primary fun git() = FakeGitPush()
        @Bean @Primary fun sandbox() = ScriptedSandbox()
        @Bean @Primary fun jev(calls: JevCalls) = FakeJevDecider(calls)
        @Bean fun pluginDoctor() = FakePluginDoctor()
    }

    @Autowired lateinit var doctor: Doctor
    @Autowired lateinit var runs: DoctorHistory
    @Autowired lateinit var plugins: PluginDoctors
    @Autowired lateinit var pluginDoctor: FakePluginDoctor
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var accounts: com.codefrak.engine.accounts.AiAccounts
    @Autowired lateinit var exits: SmithExitNotifier
    @Autowired lateinit var sandbox: ScriptedSandbox
    @Autowired lateinit var access: Access
    @Autowired lateinit var targetRepos: TargetRepos

    private val repo = "acme/tickets"
    private val t0 = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS)
    private val done = """{"outcome":"done","title":"Tidy the readme","summary":"Tidied it.","addressed":[]}"""

    @BeforeEach
    fun setUp() {
        runtime.createProcessInstanceQuery().rootProcessInstances().list().forEach { runtime.deleteProcessInstance(it.id, "plugin doctor test reset", true) }
        accounts.ensureTestAccount()
        targetRepos.add(repo)
        access.add("lundmatt1", "test")
        sandbox.reset()
        pluginDoctor.answer = null; pluginDoctor.fail = false; pluginDoctor.asked.clear()
    }

    @AfterEach
    fun dropTestAccount() { accounts.dropTestAccount() }

    private fun runAllJobs() = management.runAllJobs(exits)

    /** A chore whose first work run exits 1, and its automatic rerun too: it stops on an incident. */
    private fun stuckChore(issue: Long): ProcessInstance {
        sandbox.script.getOrPut("work") { ArrayDeque() }.addLast(done)
        sandbox.failNext = 2
        val chore = runtime.createMessageCorrelation("chore filed").setVariables(mapOf("repo" to repo, "issue" to issue)).correlateStartMessage()
        runAllJobs()
        assertTrue(runtime.createIncidentQuery().count() > 0, "the failed work run left an incident")
        return chore
    }

    private fun doctorRuns() = runtime.createProcessInstanceQuery().processDefinitionKey(Doctor.PROCESS).count()

    /** The open root-cause incidents: a failed job's incident is copied onto each calling instance up to the root, and those copies are not asked about. */
    private fun rootIncidents() = runtime.createIncidentQuery().list().filter { it.rootCauseIncidentId == null || it.rootCauseIncidentId == it.id }

    @Test
    fun `the plugin doctor is a bean the registry sees`() {
        assertEquals(listOf("pluginDoctor"), plugins.doctors().keys.toList())
    }

    @Test
    fun `a RETRY diagnosis retries the incident, is recorded as plugin-bean applied, and starts no Smith doctor`() {
        val chore = stuckChore(701)
        val incident = rootIncidents().single()
        pluginDoctor.answer = Diagnosis("A blip: the sandbox exited 1 twice", Remedy.RETRY)

        val report = doctor.tick(t0)
        assertEquals(listOf(chore.id), report.stuck)
        assertEquals(emptyList(), report.started, "no Smith doctor: the plugin answered")
        assertEquals(0L, doctorRuns())
        val asked = pluginDoctor.asked.single()
        assertEquals(listOf(incident.id, repo, 701L, "failedJob"), listOf(asked.id, asked.repo, asked.issue, asked.type))
        val run = runs.of(repo, 701).single()
        assertEquals(listOf("plugin:pluginDoctor", "retry", "applied", "A blip: the sandbox exited 1 twice", chore.id),
            listOf(run.target, run.remedy, run.outcome, run.diagnosis, run.rootInstanceId))
        assertTrue(run.endedAt != null, "recorded as finished")
        // The retry started the work run over (IncidentRetrier: a failed Smith run is rerun): the incident is gone and a third run started.
        val before = sandbox.started.size
        runAllJobs()
        assertEquals(0L, runtime.createIncidentQuery().incidentId(incident.id).count(), "the incident was retried")
        assertTrue(sandbox.started.size > before, "the work run started over")
    }

    @Test
    fun `a NONE diagnosis is recorded and nothing else happens, and a plugin that throws or answers null leaves the Smith doctor to run`() {
        stuckChore(702)
        pluginDoctor.answer = Diagnosis("Known: waits for a credential", Remedy.NONE)
        val first = doctor.tick(t0)
        assertEquals(emptyList(), first.started)
        val run = runs.of(repo, 702).single()
        assertEquals(listOf("plugin:pluginDoctor", "none", "none"), listOf(run.target, run.remedy, run.outcome))
        assertNull(run.detail)
        assertEquals(1, rootIncidents().size, "nothing retried")
        assertEquals(0L, doctorRuns())

        val other = stuckChore(703)
        pluginDoctor.fail = true
        val second = doctor.tick(t0.plusHours(1))
        assertEquals(1, second.started.size, "a plugin that throws is no answer: the Smith doctor runs")
        assertEquals(other.id, runs.of(repo, 703).single().rootInstanceId)
        assertNull(runs.of(repo, 703).single().target?.takeIf { it.startsWith("plugin:") })
        assertEquals(1L, doctorRuns())
    }
}

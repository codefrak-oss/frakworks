package com.codefrak.engine.camunda

import com.codefrak.engine.doctor.DoctorVars
import com.codefrak.engine.intake.IntakeProcessTest
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.Vars
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RuntimeService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals

/**
 * A retried failedJob incident gets its activity's `failedJobRetryTimeCycle` back (engine/README.md, "Incidents"):
 * `task-open-pr`'s `R3/PT30S` gives it 3 tries, an activity without a cycle 1.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntakeProcessTest.Fakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class IncidentRetryCycleTest {
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var retrier: IncidentRetrier

    // Its own repo: the test classes share one engine database.
    private val repo = "acme/retry-cycle"

    @BeforeEach
    fun reset() {
        runtime.createProcessInstanceQuery().rootProcessInstances().list().forEach { runtime.deleteProcessInstance(it.id, "test reset") }
        repos.add(repo)
    }

    /** Fails the only job of `instance` into an incident and retries it as the doctor; the job's retries after. */
    private fun failAndRetry(instance: String): Int {
        val job = management.createJobQuery().processInstanceId(instance).singleResult()
        management.setJobRetries(job.id, 0)
        val incident = runtime.createIncidentQuery().processInstanceId(instance).singleResult()
        retrier.retryAsSystem(incident.id, "doctor")
        return management.createJobQuery().jobId(job.id).singleResult().retries
    }

    @Test
    fun `retrying a task-open-pr incident in the chore restores its R3 cycle`() {
        val chore = runtime.createProcessInstanceByKey("chore").startBeforeActivity("task-open-pr")
            .setVariables(mapOf(Vars.REPO to repo, Vars.ISSUE to 51L)).execute()
        assertEquals(3, failAndRetry(chore.id))
    }

    @Test
    fun `retrying an incident on an activity without a cycle gives one try`() {
        val chore = runtime.createProcessInstanceByKey("chore").startBeforeActivity("user-task-answer-question")
            .setVariables(mapOf(Vars.REPO to repo, Vars.ISSUE to 52L)).execute()
        val doctor = runtime.startProcessInstanceByKey("doctor", mapOf(DoctorVars.REPO to repo, DoctorVars.ISSUE to 52L,
            DoctorVars.ROOT to chore.id, DoctorVars.POSITION to "chore:user-task-answer-question@user"))
        assertEquals(1, failAndRetry(doctor.id))
    }
}

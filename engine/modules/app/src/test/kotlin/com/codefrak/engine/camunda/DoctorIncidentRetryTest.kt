package com.codefrak.engine.camunda

import com.codefrak.engine.TestTokens
import com.codefrak.engine.doctor.DoctorVars
import com.codefrak.engine.intake.TicketRecorder
import com.codefrak.engine.intake.IntakeProcessTest
import com.codefrak.engine.repos.Access
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
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `POST /camunda/incidents/{id}/retry` on a doctor run's incident (engine/README.md, "Incidents"): it is its ticket's,
 * so access is the ticket flow's kind on the ticket's repo, and the comment goes on the ticket's issue.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntakeProcessTest.Fakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class DoctorIncidentRetryTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var access: Access
    @Autowired lateinit var github: TicketRecorder

    // Its own repo: the test classes share one engine database.
    private val repo = "acme/doctored"

    @BeforeEach
    fun reset() {
        runtime.createProcessInstanceQuery().rootProcessInstances().list().forEach { runtime.deleteProcessInstance(it.id, "test reset") }
        repos.add(repo)
        access.add("lundmatt1", "test")
    }

    /** A chore waiting on a question, and a doctor run on it whose health check job failed: the doctor run's id and its incident's. */
    private fun doctorIncident(issue: Long): Pair<String, String> {
        val chore = runtime.createProcessInstanceByKey("chore").startBeforeActivity("user-task-answer-question")
            .setVariables(mapOf(Vars.REPO to repo, Vars.ISSUE to issue)).execute()
        val doctor = runtime.startProcessInstanceByKey("doctor", mapOf(DoctorVars.REPO to repo, DoctorVars.ISSUE to issue,
            DoctorVars.ROOT to chore.id, DoctorVars.POSITION to "chore:user-task-answer-question@user"))
        val job = management.createJobQuery().processInstanceId(doctor.id).singleResult()
        management.setJobRetries(job.id, 0)
        val incident = runtime.createIncidentQuery().processInstanceId(doctor.id).singleResult()
        return doctor.id to incident.id
    }

    private fun retry(incident: String, by: String, trust: Boolean = true) = mvc.post("/camunda/incidents/$incident/retry") {
        contentType = MediaType.APPLICATION_JSON; content = "{}"; with(TestTokens.bearer(by, trust))
    }

    @Test
    fun `a login granted the ticket's kind on its repo retries a doctor run's incident, and the comment is on the ticket`() {
        val (doctor, incident) = doctorIncident(41)
        retry(incident, "lundmatt1").andExpect {
            status { isOk() }
            jsonPath("$.incidentId") { value(incident) }
            jsonPath("$.rootInstanceId") { value(doctor) }
            jsonPath("$.action") { value("job") }
        }
        assertEquals(0L, runtime.createIncidentQuery().incidentId(incident).count(), "the job has a try again")
        val comment = github.comments.last { it.first == repo && it.second == 41L }.third
        assertTrue(comment.startsWith("@lundmatt1 retried `call-health-check` on the doctor run (`$doctor`) on this chore."), comment)
    }

    @Test
    fun `an untrusted login's token is refused at the door with a 401 naming it, and the incident stays open`() {
        val (_, incident) = doctorIncident(42)
        val body = retry(incident, "mallory", trust = false).andExpect { status { isUnauthorized() } }.andReturn().response.contentAsString
        assertEquals("""{"error":"@mallory is not a trusted login"}""", body)
        assertEquals(1L, runtime.createIncidentQuery().incidentId(incident).count())
    }
}

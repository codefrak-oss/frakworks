package com.codefrak.engine.board

import com.codefrak.engine.github.Blocker
import com.codefrak.engine.github.IssueSnapshot
import com.codefrak.engine.github.ReadFakes
import com.codefrak.engine.workitems.WorkItem
import com.codefrak.engine.intake.TicketFiler
import com.codefrak.engine.intake.IntakeQuestions
import com.codefrak.engine.repos.Deferred
import com.codefrak.engine.repos.DeferredIssues
import com.codefrak.engine.repos.HandledIssues
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.workitems.ListedTickets
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Jobs are not executed, so a started flow stays where it was put. One test per kind of need. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ReadFakes::class, IssueBoardTest.Fakes::class)
@ExtendWith(OutputCaptureExtension::class)
class NeedsTest {
    @Autowired lateinit var needs: Needs
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var tickets: ListedTickets
    @Autowired lateinit var handled: HandledIssues
    @Autowired lateinit var deferred: DeferredIssues
    @Autowired lateinit var questions: IntakeQuestions
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var repository: RepositoryService
    @Autowired lateinit var jdbc: JdbcClient

    private val repo = "acme/widgets"
    private val t0 = OffsetDateTime.parse("2026-09-24T10:00:00Z")

    @BeforeEach
    fun reset() {
        runtime.createProcessInstanceQuery().rootProcessInstances().list().forEach { runtime.deleteProcessInstance(it.id, "test reset") }
        listOf("engine.handled_issue", "engine.deferred_issue", "engine.intake_question", "engine.target_repo").forEach { jdbc.sql("DELETE FROM $it").update() }
        tickets.reset()
        repos.add(repo)
    }

    private fun issue(n: Long, vararg labels: String) {
        tickets.list(repo, n, "Issue $n", labels.toList(), t0.plusMinutes(n), t0)
    }

    /** A feature parked at `task`, as a real run leaves it. */
    private fun featureAt(task: String, issue: Long): String =
        runtime.createProcessInstanceByKey("feature").startBeforeActivity(task)
            .setVariable("repo", repo).setVariable("issue", issue).execute().id

    /** A pre-#583 feature definition parked at `user-task-answer-acceptance`, deployed once per call under [key]. */
    private fun legacyAcceptance(key: String, issue: Long): String {
        repository.createDeployment().addString("$key.bpmn", """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" targetNamespace="t">
  <bpmn:process id="$key" isExecutable="true" camunda:historyTimeToLive="1">
    <bpmn:startEvent id="s" />
    <bpmn:userTask id="user-task-answer-acceptance" />
    <bpmn:endEvent id="e" />
    <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="user-task-answer-acceptance" />
    <bpmn:sequenceFlow id="f2" sourceRef="user-task-answer-acceptance" targetRef="e" />
  </bpmn:process>
</bpmn:definitions>""").deploy()
        return runtime.startProcessInstanceByKey(key, mapOf("repo" to repo, "issue" to issue, "askedBy" to "alice")).id
    }

    @Test
    fun `a flow's question, acceptance or decision is no row - it is decided on the hitlp server`() {
        // A recorded question, a wait with none recorded, a proposal's and a research ticket's decision, a pre-#583 acceptance.
        questions.ask(featureAt("user-task-answer-question", 1), repo, 1, "Which readme?")
        featureAt("user-task-answer-work", 2)
        runtime.createProcessInstanceByKey("proposal").startBeforeActivity("user-task-answer-decision")
            .setVariable("repo", repo).setVariable("issue", 3L).setVariable("askedBy", "carol").execute()
            .let { questions.ask(it.id, repo, 3, "The proposal is up as PR #7. Your decision") }
        runtime.createProcessInstanceByKey("research").startBeforeActivity("user-task-answer-decision")
            .setVariable("repo", repo).setVariable("issue", 4L).execute()
        legacyAcceptance("feature-before-583", 5)

        assertEquals(emptyList(), needs.list().filter { it.number in 1L..5L })
        assertTrue(needs.list().none { it.kind in setOf("question", "acceptance", "decision") })
        mvc.get("/needs").andExpect {
            status { isOk() }
            jsonPath("$[?(@.kind == 'question')]") { isEmpty() }
            jsonPath("$[?(@.kind == 'acceptance')]") { isEmpty() }
            jsonPath("$[?(@.kind == 'decision')]") { isEmpty() }
        }
        assertEquals(2, questions.open().size, "GET /questions still lists the recorded questions")
    }

    @Test
    fun `the engine's own rows are everyone's, and for= keeps them`() {
        val flow = runtime.startProcessInstanceByKey("smith-run", mapOf("repo" to repo, "issue" to 8L, "askedBy" to "alice"))
        runtime.createIncident("failedJob", flow.id, "cfg", "docker went away")
        handled.claim(repo, 8, 1, "chore", "started", null, "alice")
        issue(9, "chore")
        val blockers = listOf(Blocker(repo, 30, "closed", "completed"))
        deferred.defer(Deferred(repo, 11, 211, "lundmatt1", "chore", null, IssueSnapshot(11, "Deferred 11", "", "alice"), blockers))
        deferred.checked(repo, 11, blockers, setOf("$repo#30"))
        repos.setDoctorBugs(repo, true, false)

        assertEquals(mapOf("incident" to null, "authorize" to null, "reauthorize" to null, "sighting" to null),
            needs.list().associate { it.kind to it.forLogin })
        assertEquals(needs.list(), needs.list(forLogin = "ALICE"), "everyone's rows stay")
        mvc.get("/needs?for=alice").andExpect {
            status { isOk() }
            jsonPath("$[?(@.number == 8)].for") { exists() }
            jsonPath("$[?(@.number == 8)].forLogin") { doesNotExist() }
        }
        repos.setDoctorBugs(repo, false, false)
    }

    @Test
    fun `an open incident, by the id to retry`() {
        val flow = runtime.startProcessInstanceByKey("smith-run", mapOf("repo" to repo, "issue" to 4L))
        val incident = runtime.createIncident("failedJob", flow.id, "cfg", "docker went away\nstack trace")
        val need = needs.list().single()
        assertEquals("incident", need.kind)
        assertEquals(incident.id, need.actionId)
        assertEquals(repo to 4L, need.repo to need.number)
        assertTrue(need.wanted.startsWith("retry or cancel: ") && need.wanted.endsWith("docker went away"), need.wanted)
        assertNotNull(need.since)
    }

    @Test
    fun `a doctor run's incident is its ticket's - repo, number and title - and repo= keeps it with that repo`() {
        issue(6)
        val doctor = runtime.startProcessInstanceByKey("doctor",
            mapOf("doctorRepo" to repo, "doctorIssue" to 6L, "doctorRoot" to "the-ticket-flow", "doctorPosition" to "chore:task-x@none"))
        val incident = runtime.createIncident("failedJob", doctor.id, "cfg", "no account available until 2026-10-05T12:00:00Z")
        val need = needs.list().single { it.actionId == incident.id }
        assertEquals("incident", need.kind)
        assertEquals(Triple(repo, 6L, "Issue 6"), Triple(need.repo, need.number, need.title))
        assertTrue(incident.id in needs.list(repo).map { it.actionId }, "repo= keeps the doctor run's incident with its ticket's repo")
    }

    @Test
    fun `repo= keeps only that target repo's needs`() {
        val other = "acme/gadgets"
        repos.add(other)
        issue(10, "chore")
        tickets.list(other, 20, "Gadget 20", listOf("chore"), t0, t0)
        val here = runtime.startProcessInstanceByKey("smith-run", mapOf("repo" to repo, "issue" to 4L))
        runtime.createIncident("failedJob", here.id, "cfg", "docker went away")
        val there = runtime.startProcessInstanceByKey("smith-run", mapOf("repo" to other, "issue" to 21L))
        runtime.createIncident("failedJob", there.id, "cfg", "docker went away")

        assertEquals(setOf(repo to 4L, repo to 10L, other to 20L, other to 21L), needs.list().map { it.repo to it.number }.toSet())
        mvc.get("/needs").andExpect { jsonPath("$.length()") { value(4) } }

        assertEquals(setOf(other to 20L, other to 21L), needs.list("Acme/Gadgets").map { it.repo to it.number }.toSet())
        mvc.get("/needs?repo=$other").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }
            jsonPath("$[0].repo") { value(other) }
            jsonPath("$[1].repo") { value(other) }
        }
        mvc.get("/needs?repo=$repo").andExpect { jsonPath("$.length()") { value(2) } }
        mvc.get("/needs?repo=acme/nowhere").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `an open ticket waiting for authorized, and an ask waiting for its start`() {
        issue(10, "chore"); issue(11, "intake")
        issue(12, "chore", "authorized"); issue(14, "chore"); issue(15, "chore")
        handled.claim(repo, 14, 100, "chore", "refused", "no access")
        deferred.defer(Deferred(repo, 15, 101, "lundmatt1", "chore", null, IssueSnapshot(15, "Issue 15", "", "someone"),
            listOf(Blocker(repo, 99, "open"))))
        val rows = needs.list()
        assertEquals(listOf(10L, 11L), rows.map { it.number })
        assertTrue(rows.all { it.kind == "authorize" })
        assertEquals(listOf("add the authorized label", "start the ask"), rows.map { it.wanted })
        assertEquals("https://github.com/$repo/issues/10", rows[0].actionId)
        assertEquals(t0.plusMinutes(10), rows[0].since)
    }

    @Test
    fun `a triage ticket is listed by its kind, waiting on its blocker, and no other kind with the triage trailer is`() {
        val body = "${TicketFiler.triageSection("Needs a mac code-signing secret", "no secret", "chore")}\n<!-- engine: intake i · p3 · triage -->"
        tickets.list(repo, 20, "Sign the app", listOf(WorkItem.TRIAGE), t0.plusMinutes(20), t0, body)
        tickets.list(repo, 21, "Already triaged", listOf("chore", "authorized"), t0.plusMinutes(21), t0, body)
        val rows = needs.list()
        assertEquals(listOf(20L), rows.map { it.number })
        assertEquals("insufficient-permissions", rows.single().kind)
        assertEquals("Needs a mac code-signing secret", rows.single().wanted)
    }

    /** An intake parked at `ask item started` for issue `n`, as `POST /intake` leaves it. */
    private fun intakeFor(n: Long, authorizedBy: String?) {
        runtime.createProcessInstanceByKey("intake").startBeforeActivity("event-intake-authorized")
            .setVariable("issueUrl", "https://github.com/$repo/issues/$n").setVariable("authorizedBy", authorizedBy).execute()
    }

    @Test
    fun `an intake issue whose intake is already authorized needs no one`() {
        issue(40, "intake")
        intakeFor(40, "lundmatt1")
        assertEquals(emptyList(), needs.list())
    }

    @Test
    fun `an intake issue whose intake waits for the authorized label is still authorize`() {
        issue(41, "intake")
        intakeFor(41, null)
        assertEquals(listOf(41L to "authorize"), needs.list().map { it.number to it.kind })
    }

    @Test
    fun `an intake issue rewritten into its ticket needs authorizing at once when the authorization did not carry over`() {
        issue(44, "chore")
        runtime.createProcessInstanceByKey("intake").startBeforeActivity("event-intake-authorized")
            .setVariable("issueUrl", "https://github.com/$repo/issues/44").setVariable("authorizedBy", "lundmatt1")
            .setVariable("ticket", 44L).execute()
        assertEquals(listOf(44L to "authorize"), needs.list().map { it.number to it.kind })
    }

    @Test
    fun `an intake for another issue leaves an issue's authorize row as before`() {
        issue(42, "intake")
        intakeFor(43, "lundmatt1")
        assertEquals(listOf(42L to "authorize"), needs.list().map { it.number to it.kind })
    }

    @Test
    fun `a deferred approval whose blocker closed without a merge`() {
        for ((n, blockers) in mapOf(20L to listOf(Blocker(repo, 30, "closed", "completed")),
                                    21L to listOf(Blocker(repo, 31, "closed", "completed"), Blocker(repo, 32, "open")))) {
            deferred.defer(Deferred(repo, n, 200 + n, "lundmatt1", "chore", null, IssueSnapshot(n, "Deferred $n", "", "someone"), blockers))
            deferred.checked(repo, n, blockers, setOf("$repo#${blockers.first().number}"))
        }
        val need = needs.list().single()
        assertEquals(Need(repo, 20, "Deferred 20", "reauthorize",
            "blocker $repo#30 closed without a merge: re-authorize to proceed", need.since, "https://github.com/$repo/issues/20"), need)
        assertNotNull(need.since)
    }

    @Test
    fun `a repo GitHub cannot list loses only its authorize rows`() {
        issue(10, "chore")
        val flow = runtime.startProcessInstanceByKey("smith-run", mapOf("repo" to repo, "issue" to 1L))
        runtime.createIncident("failedJob", flow.id, "cfg", "docker went away")
        tickets.unlistable += repo
        assertEquals(listOf("incident"), needs.list().map { it.kind })
    }

    @Test
    fun `a question whose flow ended unanswered closes on the next read of GET questions`(output: CapturedOutput) {
        // #358: the asking flow was superseded and the issue closed, so it is not in the listing.
        val id = featureAt("user-task-answer-question", 358)
        questions.ask(id, repo, 358, "Which readme?")
        val waiting = featureAt("user-task-answer-question", 359)
        questions.ask(waiting, repo, 359, "Which docs?")
        runtime.deleteProcessInstance(id, "superseded")

        assertEquals(listOf(waiting), questions.open().map { it.intake })
        val closed = questions.list(id).single()
        assertEquals("Which readme?", closed.question)
        assertEquals("closed: flow ended (superseded)", closed.answer)
        assertEquals("engine", closed.answeredBy)
        assertNotNull(closed.answeredAt)
        assertEquals(null, questions.list(waiting).single().answer)
        assertFalse("ENGINE-16004" in output.all, "read the variables of an ended instance")
    }

    @Test
    fun `a question left open on an ended flow is settled on the next read`() {
        val id = featureAt("user-task-answer-question", 360)
        runtime.deleteProcessInstance(id, "finished")
        // As a row from before this check: open, its instance already gone.
        jdbc.sql("INSERT INTO engine.intake_question (intake, full_name, issue, question) VALUES (:p, :r, 360, 'Old?')")
            .param("p", id).param("r", repo).update()
        mvc.get("/questions").andExpect { jsonPath("$.length()") { value(0) } }
        assertEquals("closed: flow ended (finished)", questions.list(id).single().answer)
        assertTrue(needs.list().none { it.actionId == id })
    }

    @Test
    fun `a sighting row while some repo has doctorBugs on and none takes engine defects`() {
        assertTrue(needs.list().none { it.kind == "sighting" }, "no repo has doctorBugs on")
        repos.setDoctorBugs(repo, true, false)
        val need = needs.list().single { it.kind == "sighting" }
        assertEquals(Need(null, null, "The doctor has no repo for engine defects", "sighting", Needs.NO_DEFECTS_REPO, null, "engineDefects"), need)
        assertTrue(needs.list(repo).none { it.kind == "sighting" }, "a need in no repo is left out of one repo's list")
        mvc.get("/needs").andExpect { jsonPath("$[?(@.kind == 'sighting')].actionId") { value("engineDefects") } }

        repos.setDoctorBugs(repo, true, true)
        assertTrue(needs.list().none { it.kind == "sighting" }, "a repo takes engine defects")
        repos.setDoctorBugs(repo, false, false)
    }
}

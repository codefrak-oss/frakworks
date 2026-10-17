package com.codefrak.engine.camunda

import com.codefrak.engine.repos.HandledIssues
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.intake.IntakeTickets
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Jobs are not executed, so a started process waits at its first async step and a job can be failed by hand. */
@SpringBootTest
class CamundaControllerTest {
    @Autowired lateinit var camunda: CamundaController
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var repository: RepositoryService
    @Autowired lateinit var history: org.operaton.bpm.engine.HistoryService
    @Autowired lateinit var handled: HandledIssues
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var links: IntakeTickets

    // Its own repo: the test classes share one engine database, and IssueBoardTest's intakes carry acme/widgets URLs.
    private val repo = "acme/camunda"
    private fun url(n: Long) = "https://github.com/$repo/issues/$n"

    @BeforeEach
    fun reset() {
        jdbc.sql("DELETE FROM engine.handled_issue").update()
        // Roots only: deleting one takes its called processes with it.
        runtime.createProcessInstanceQuery().rootProcessInstances().list().forEach { runtime.deleteProcessInstance(it.id, "test reset") }
    }

    @Test
    fun `an issue's instances come from handled_issue, the intake's issueUrl and the flow's repo and issue, newest first`() {
        val fromRow = runtime.startProcessInstanceByKey("smith-run")
        handled.claim(repo, 4, 100, "chore", "started", null); handled.started(repo, 4, 100, fromRow.id)
        val intake = runtime.createProcessInstanceByKey("intake").startBeforeActivity("event-intake-authorized")
            .setVariable("issueUrl", url(4)).execute()
        links.opened(intake.id, repo, 4, url(4))
        val chore = runtime.createProcessInstanceByKey("chore").startBeforeActivity("call-do-work")
            .setVariables(mapOf(Vars.REPO to repo, Vars.ISSUE to 4L)).execute()
        val other = runtime.startProcessInstanceByKey("hello", mapOf(Vars.REPO to repo, Vars.ISSUE to 5L))

        val listed = camunda.instances(url(4))
        assertEquals(setOf(fromRow.id, intake.id, chore.id), listed.map { it.id }.toSet())
        assertEquals(listed.sortedByDescending { it.startedAt }, listed)
        assertTrue(listed.none { it.id == other.id }, "another issue's flow is not listed")
        assertEquals(1, listed.count { it.processDefinitionKey == "smith-run" }, "only roots: the chore's called smith run is inside it, not beside it")
        val row = listed.first { it.id == fromRow.id }
        assertEquals("smith-run" to "running", row.processDefinitionKey to row.state)
        assertEquals("start smith", row.step)
        assertFalse(row.ended)

        assertEquals(listOf(other.id), camunda.instances(url(5)).map { it.id })
        assertTrue(camunda.instances(url(6)).isEmpty())
        assertEquals(HttpStatus.BAD_REQUEST, assertFailsWith<ResponseStatusException> { camunda.instances("acme/widgets#4") }.statusCode)
    }

    @Test
    fun `an instance's tree has every called process, what each did, waits at and holds`() {
        // `intake` calls `reusable-smith-run` synchronously (chore's call is asyncBefore: it would wait before calling).
        val intake = runtime.createProcessInstanceByKey("intake").startBeforeActivity("call-refine-ask")
            .setVariables(mapOf(Vars.REPO to repo, Vars.ISSUE to 7L, "issueUrl" to url(7))).execute()
        val tree = camunda.instance(intake.id)
        assertEquals(intake.id, tree.id)
        assertEquals(listOf("intake", "reusable-smith-run", "smith-run"), tree.nodes.map { it.processDefinitionKey })
        val (root, reusable, run) = tree.nodes
        assertNull(root.parentId); assertNull(root.calledFrom)
        assertEquals(root.id to "call-refine-ask", reusable.parentId to reusable.calledFrom)
        assertEquals(reusable.id to "call-smith-run", run.parentId to run.calledFrom)
        assertEquals(listOf("call-refine-ask"), root.active, "the root waits in its call activity")
        assertEquals(listOf("task-start-smith"), run.active, "the run waits on its first async step")
        assertTrue(run.completed.none { it == "task-start-smith" })
        assertTrue(tree.nodes.all { it.incidents.isEmpty() && !it.ended })
        val repoVar = root.variables.first { it.name == Vars.REPO }
        assertEquals(repo, repoVar.value); assertEquals("string", repoVar.type?.lowercase()); assertFalse(repoVar.truncated)
        assertEquals("refine", run.variables.first { it.name == Vars.MODE }.value, "variables passed down the call are on the called process")
        assertEquals(listOf(intake.id), camunda.instances(url(7)).map { it.id }, "the list has the root only")
    }

    @Test
    fun `a failed job is an incident on its activity, and the list says so`() {
        val intake = runtime.createProcessInstanceByKey("intake").startBeforeActivity("task-file-unlabeled")
            .setVariable("issueUrl", url(8)).execute()
        links.opened(intake.id, repo, 8, url(8))
        val job = management.createJobQuery().processInstanceId(intake.id).activityId("task-file-unlabeled").singleResult()
        management.setJobRetries(job.id, 1)
        runCatching { management.executeJob(job.id) }
        val node = camunda.instance(intake.id).nodes.single()
        val incident = node.incidents.single()
        assertEquals("task-file-unlabeled", incident.activityId)
        assertEquals("failedJob", incident.type)
        assertNotNull(incident.message)
        assertEquals(listOf("task-file-unlabeled"), node.active)
        assertEquals("incident", camunda.instances(url(8)).single().state)
    }

    @Test
    fun `a failed job's incident serves every failed attempt from the historic job log, oldest first, the message the last`() {
        val (instance, _) = TwiceFailedJob.run(repository, runtime, management)
        val incident = camunda.instance(instance).nodes.single().incidents.single()
        assertEquals(listOf(TwiceFailedJob.FIRST, TwiceFailedJob.SECOND), incident.attempts.map { it.message })
        assertTrue(incident.attempts.all { it.at != null })
        assertEquals(incident.attempts.last().message, incident.message)
        assertEquals(emptyList(), JobAttempts(history).of("no-such-job"), "a job that never existed has no attempts")
    }

    @Test
    fun `an ended instance is completed throughout, with nothing active and no incidents`() {
        // A string over 4000 characters is more than the engine's column holds; a blob is how a long value arrives.
        val done = runtime.startProcessInstanceByKey("hello", mapOf("big" to ByteArray(5000), Vars.REPO to repo, Vars.ISSUE to 9L))
        val node = camunda.instance(done.id).nodes.single()
        assertTrue(node.ended)
        assertTrue(node.active.isEmpty())
        assertContains(node.completed, "task-delegate-1")
        val big = node.variables.single { it.name == "big" }
        assertEquals("<5000 bytes>", big.value, "a blob is named, never sent"); assertFalse(big.truncated)
        val listed = camunda.instances(url(9)).single()
        assertEquals("ended", listed.state); assertTrue(listed.ended); assertNull(listed.step)
    }

    @Test
    fun `the definition's xml is what was deployed, and unknown ids are 404s`() {
        val definition = repository.createProcessDefinitionQuery().processDefinitionKey("intake").latestVersion().singleResult()
        val xml = camunda.xml(definition.id)
        assertContains(xml, "<bpmn:process id=\"intake\"")
        assertContains(xml, "call-refine-ask")
        assertEquals(HttpStatus.NOT_FOUND, assertFailsWith<ResponseStatusException> { camunda.xml("intake:99:nope") }.statusCode)
        assertEquals(HttpStatus.NOT_FOUND, assertFailsWith<ResponseStatusException> { camunda.instance("nope") }.statusCode)
    }
}

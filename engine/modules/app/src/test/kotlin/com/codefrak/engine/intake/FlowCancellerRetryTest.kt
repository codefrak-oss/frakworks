package com.codefrak.engine.intake

import com.codefrak.engine.accounts.AiAccounts
import com.codefrak.engine.camunda.Actor
import com.codefrak.engine.camunda.ActorAccess
import com.codefrak.engine.doctor.DoctorBugStore
import com.codefrak.engine.doctor.DoctorRunEnder
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.github.GitHubPulls
import com.codefrak.engine.github.NOT_PLANNED
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.repos.DeferredIssues
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.runAllJobs
import com.codefrak.engine.smith.SmithExitNotifier
import com.codefrak.engine.smith.SmithSandbox
import com.codefrak.engine.smith.ensureTestAccount
import com.codefrak.engine.smith.dropTestAccount
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.OptimisticLockingException
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.TaskService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A cancel that loses a row lock to the flow's own start-up (engine/README.md, "Cancel and the sweep"): [FlowCanceller]
 * retries its engine-side step, and writes GitHub once, only after it succeeds.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntakeProcessTest.Fakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class FlowCancellerRetryTest {
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var history: HistoryService
    @Autowired lateinit var tasks: TaskService
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var exits: SmithExitNotifier
    @Autowired lateinit var accounts: AiAccounts
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var access: Access
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var github: TicketRecorder
    @Autowired lateinit var questions: IntakeQuestions
    @Autowired lateinit var actors: ActorAccess
    @Autowired lateinit var sandbox: SmithSandbox
    @Autowired lateinit var pulls: GitHubPulls
    @Autowired lateinit var deferred: DeferredIssues
    @Autowired lateinit var jevCalls: JevCalls
    @Autowired lateinit var events: EngineEvents
    @Autowired lateinit var doctorBugs: DoctorBugStore
    @Autowired lateinit var doctorRuns: DoctorRunEnder
    @Autowired lateinit var hitlpAnswers: HitlpAnswers
    @Autowired lateinit var tickets: com.codefrak.engine.workitems.FakeTickets

    @BeforeEach
    fun reset() {
        runtime.createProcessInstanceQuery().rootProcessInstances().list().forEach { runtime.deleteProcessInstance(it.id, "test reset") }
        listOf("engine.handled_issue", "engine.deferred_issue", "engine.engine_access", "engine.engine_access_audit", "engine.target_repo",
            "engine.intake_question").forEach { jdbc.sql("DELETE FROM $it").update() }
        repos.add("acme/tickets")
        access.add("lundmatt1", "test")
        accounts.ensureTestAccount()
    }

    @AfterEach
    fun dropTestAccount() { accounts.dropTestAccount() }

    private fun runAllJobs() = management.runAllJobs(exits, rounds = 50)

    /** An intake parked on an incident (its refiner's result broke the rules): its id and its issue. */
    private fun parked(): Pair<String, Long> {
        val intake = runtime.createMessageCorrelation("ask submitted")
            .setVariables(mapOf("submittedBy" to "lundmatt1", "repo" to "acme/tickets", "ask" to "hello", "fakeOutcome" to "bad")).correlateStartMessage().id
        runAllJobs()
        return intake to (runtime.getVariable(intake, "issue") as Number).toLong()
    }

    /** The real [RuntimeService], whose first [conflicts] `deleteProcessInstance` calls throw; [deletes] counts them all. */
    private inner class Racing(val conflicts: Int) {
        var deletes = 0
        val service = Proxy.newProxyInstance(RuntimeService::class.java.classLoader, arrayOf(RuntimeService::class.java)) { _, method, args ->
            if (method.name == "deleteProcessInstance" && ++deletes <= conflicts)
                throw OptimisticLockingException("ExecutionEntity was updated by another transaction concurrently")
            try { method.invoke(runtime, *(args ?: emptyArray())) } catch (e: InvocationTargetException) { throw e.targetException }
        } as RuntimeService
        val canceller = FlowCanceller(service, history, tasks, questions, actors, sandbox, pulls, deferred, jevCalls, events,
            doctorBugs, doctorRuns, hitlpAnswers, tickets, stopPauseMillis = 10)
    }

    private fun comments(issue: Long) = github.comments.count { it.first == "acme/tickets" && it.second == issue }
    private fun closes(issue: Long) = github.closed.count { it == "acme/tickets" to issue }

    @Test
    fun `a cancel that conflicts once is retried, deletes the flow, and comments and closes once`() {
        val (intake, issue) = parked()
        val before = comments(issue); val closedBefore = closes(issue)
        val racing = Racing(conflicts = 1)

        assertEquals(emptyList(), racing.canceller.cancel(intake, Actor.Login("lundmatt1"), "not wanted"))

        assertEquals(2, racing.deletes)
        assertEquals(0L, runtime.createProcessInstanceQuery().processInstanceId(intake).count())
        assertEquals(before + 1, comments(issue))
        assertEquals(closedBefore + 1, closes(issue))
        assertEquals(NOT_PLANNED, github.closedAs["acme/tickets" to issue])
    }

    @Test
    fun `a cancel that conflicts on every attempt throws the conflict and writes nothing to GitHub`() {
        val (intake, issue) = parked()
        val before = comments(issue); val closedBefore = closes(issue)
        val racing = Racing(conflicts = Int.MAX_VALUE)

        assertThrows<OptimisticLockingException> { racing.canceller.cancel(intake, Actor.Login("lundmatt1"), "not wanted") }

        assertEquals(FlowCanceller.STOP_ATTEMPTS, racing.deletes)
        assertEquals(1L, runtime.createProcessInstanceQuery().processInstanceId(intake).count())
        assertEquals(before, comments(issue))
        assertEquals(closedBefore, closes(issue))
    }

    /** A chore started on [issue], not yet past its first wait. */
    private fun chore(issue: Long) = runtime.createMessageCorrelation("chore filed")
        .setVariables(mapOf("repo" to "acme/tickets", "issue" to issue)).correlateStartMessage().id

    @Test
    fun `cancelling a flow whose ticket is already closed ends it and leaves the issue's state alone (#2542)`() {
        tickets.ended += "acme/tickets" to 2532L
        val id = chore(2532)
        val closedBefore = closes(2532)
        try {
            assertEquals(emptyList(), Racing(conflicts = 0).canceller.cancel(id, Actor.Login("lundmatt1"), "stray flow on a merged ticket"))
        } finally { tickets.ended -= "acme/tickets" to 2532L }

        assertEquals(0L, runtime.createProcessInstanceQuery().processInstanceId(id).count())
        assertEquals(closedBefore, closes(2532), "an already-closed ticket is not closed again as not planned")
        val said = github.comments.last { it.second == 2532L }.third
        assertTrue("stray flow on a merged ticket" in said && "already closed and is left as it is" in said, said)
    }

    @Test
    fun `cancelling a flow whose ticket is open still closes it as not planned`() {
        val id = chore(2533)
        assertEquals(emptyList(), Racing(conflicts = 0).canceller.cancel(id, Actor.Login("lundmatt1"), "not wanted"))
        assertEquals(NOT_PLANNED, github.closedAs["acme/tickets" to 2533L])
    }
}

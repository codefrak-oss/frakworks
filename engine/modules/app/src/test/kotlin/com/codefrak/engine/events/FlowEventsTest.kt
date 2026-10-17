package com.codefrak.engine.events

import com.codefrak.engine.board.IssueBoard
import com.codefrak.engine.board.IssueBoardTest
import com.codefrak.engine.repos.HandledIssues
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.workitems.ListedTickets
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.ProcessEngineConfiguration
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The history handler, the after-commit deferral and the board row, on the in-memory engine: the test-only
 * `events-test` diagram (src/test/resources/bpmn) is driven job by job, each in a command of its own, and the
 * frames a subscriber of the bus sees are checked in order. Jobs are not executed by the engine (job execution off).
 */
@SpringBootTest
@Import(IssueBoardTest.Fakes::class)
class FlowEventsTest {
    @Autowired lateinit var bus: EngineEvents
    @Autowired lateinit var flows: FlowEvents
    @Autowired lateinit var board: IssueBoard
    @Autowired lateinit var tickets: ListedTickets
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var handled: HandledIssues
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var configuration: ProcessEngineConfiguration
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var json: ObjectMapper

    private val repo = "acme/events"
    private val t0 = OffsetDateTime.parse("2026-10-05T09:00:00Z")
    private lateinit var sub: EventSubscription

    @BeforeEach
    fun reset() {
        listOf("engine.handled_issue", "engine.target_repo").forEach { jdbc.sql("DELETE FROM $it").update() }
        tickets.reset()
        repos.add(repo)
        flows.window = Duration.ofMillis(100)
        sub = bus.subscribe(repo)
    }

    /** Ends what the test left running after unsubscribing, and lets those frames flush before the next test subscribes. */
    @AfterEach
    fun tearDown() {
        bus.unsubscribe(sub)
        val left = runtime.createProcessInstanceQuery().processDefinitionKey("events-test").list()
        left.forEach { runtime.deleteProcessInstance(it.id, "test done") }
        if (left.isNotEmpty()) Thread.sleep(flows.window.toMillis() * 3)
        flows.window = EngineEvents.FLOW_WINDOW
    }

    /** `value` as the frame carries it: through the same mapper, re-read, so node types match the frame's. */
    private fun tree(value: Any?): JsonNode = json.readTree(json.writeValueAsString(value))

    private fun listed(n: Long) {
        tickets.list(repo, n, "Issue $n", listOf("chore", "authorized"), t0, t0)
    }

    /**
     * The next `flow` or `incident` frame whose `state` is `state` within 5 s, as its `data`; fails naming what came
     * instead (as `<event>/<state>`). The `event:` line names the type (`flow`); the state is in the data.
     */
    private fun next(state: String): JsonNode {
        val seen = mutableListOf<String>()
        val until = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < until) {
            val frame = sub.next(Duration.ofMillis(100)) ?: continue
            val (name, data) = frame.trim().lines().let { it[0].removePrefix("event: ") to json.readTree(it[1].removePrefix("data: ")) }
            if (data["state"]?.asString() == state) return data
            seen += "$name/${data["state"]?.asString()}"
        }
        error("no $state frame within 5 s; saw $seen")
    }

    private fun nothing(within: Duration = Duration.ofMillis(250)) = assertNull(sub.next(within), "no frame expected")

    private fun runJob(instance: String) = management.executeJob(management.createJobQuery().processInstanceId(instance).singleResult().id)

    @Test
    fun `a flow's frames come in order with the board row, under the root, and a called process reports under it`() {
        listed(5)
        val root = runtime.startProcessInstanceByKey("events-test", mapOf("repo" to repo, "issue" to 5L))
        val started = next("started")
        assertEquals(repo, started["repo"].asString()); assertEquals(5, started["number"].asLong())
        assertEquals(root.id, started["instance"].asString()); assertEquals(root.id, started["root"].asString())
        assertEquals("events-test", started["kind"].asString())
        assertEquals(5, started["issue"]["number"].asLong(), "the board row rides on the frame")
        assertTrue(started["issue"]["flow"].isNull, "no approval row yet")
        Instant.parse(started["at"].asString())

        // The approval the board joins on, then the first step runs into the wait state in one command: one frame.
        handled.claim(repo, 5, 500, "chore", "started", null)
        handled.started(repo, 5, 500, root.id)
        runJob(root.id)
        val waiting = next("waiting")
        assertEquals("wait for go", waiting["step"].asString())
        assertEquals("receive-wait", waiting["stage"].asString())
        assertEquals(tree(board.board().issues.single()), waiting["issue"], "the row GET /issues lists, by the same mapper")
        assertEquals(tree(board.row(repo, 5)), waiting["issue"])
        assertEquals("wait for go", waiting["issue"]["flow"]["step"].asString())
        nothing()

        // The message moves it to the call activity's async job: nothing started yet.
        runtime.createMessageCorrelation("events-test-go").processInstanceId(root.id).correlate()
        nothing()

        // The called process starts: its step, under the root's call activity, with the called instance.
        runJob(root.id)
        val child = runtime.createProcessInstanceQuery().superProcessInstanceId(root.id).singleResult()
        val step = next("step")
        assertEquals(child.id, step["instance"].asString())
        assertEquals(root.id, step["root"].asString())
        assertEquals("call-child", step["stage"].asString())
        assertEquals("start-events-test-child", step["step"].asString())
        assertEquals(5, step["issue"]["number"].asLong())

        // The child's step runs it and the root to their ends: one ended frame, with no step.
        runJob(child.id)
        val ended = next("ended")
        assertEquals(root.id, ended["instance"].asString())
        assertTrue(ended["step"].isNull); assertTrue(ended["stage"].isNull)
        assertTrue(ended["issue"]["flow"]["ended"].asBoolean())
        // An ended flow takes its GET /needs answer row with it, recorded question or not (#1172): a `needs` `removed` after.
        val removed = next("removed")
        assertEquals("question" to root.id, removed["kind"].asString() to removed["actionId"].asString())
        assertEquals(5, removed["number"].asLong())
        nothing()
    }

    @Test
    fun `a deleted flow is cancelled, and the row is null when GitHub cannot be read`() {
        tickets.unlistable += repo
        val root = runtime.startProcessInstanceByKey("events-test", mapOf("repo" to repo, "issue" to 6L))
        val started = next("started")
        assertTrue(started["issue"].isNull, "issue: null, the other fields filled")
        assertEquals(6, started["number"].asLong())
        runtime.deleteProcessInstance(root.id, "cancelled by the test")
        val cancelled = next("cancelled")
        assertEquals(root.id, cancelled["root"].asString())
        assertTrue(cancelled["issue"].isNull)
        assertEquals(root.id, next("removed")["actionId"].asString(), "a deleted flow's need is announced gone (#1172)")
    }

    @Test
    fun `an intake's issue comes from its issueUrl, and a root with no issue publishes nothing`() {
        listed(7)
        runtime.startProcessInstanceByKey("events-test", mapOf("issueUrl" to "https://github.com/$repo/issues/7"))
        assertEquals(7, next("started")["number"].asLong())
        runtime.startProcessInstanceByKey("hello")
        nothing()
        // The root with no issue took no frame, and left no ordering lock behind either (only a burst in flight holds one).
        val until = System.currentTimeMillis() + 2000
        while (flows.locksHeld() > 0 && System.currentTimeMillis() < until) Thread.sleep(20)
        assertEquals(0, flows.locksHeld(), "no lock entry stays for a flushed root")
    }

    @Test
    fun `an incident is reported open and then resolved, with its ids`() {
        listed(8)
        val root = runtime.startProcessInstanceByKey("events-test", mapOf("repo" to repo, "issue" to 8L))
        next("started")
        val incident = runtime.createIncident("custom", root.id, "config", "something broke")
        val open = next("open")
        assertEquals(incident.id, open["incidentId"].asString())
        assertEquals(root.id, open["instance"].asString()); assertEquals(root.id, open["root"].asString())
        assertEquals("custom", open["type"].asString())
        assertEquals("something broke", open["message"].asString())
        assertEquals(8, open["number"].asLong())
        assertNull(open["issue"], "no board row on an incident frame")
        // A root-cause incident is a row of GET /needs: its open and its resolve are `needs` frames too.
        val added = next("added")
        assertEquals("incident" to incident.id, added["kind"].asString() to added["actionId"].asString())
        assertEquals(8, added["number"].asLong())
        runtime.resolveIncident(incident.id)
        assertEquals(incident.id, next("resolved")["incidentId"].asString())
        assertEquals("incident" to incident.id, next("removed").let { it["kind"].asString() to it["actionId"].asString() })
    }

    @Test
    fun `a propagated incident's resolve is a needs removed too, since GET needs lists it once its root cause is gone`() {
        listed(9)
        val root = runtime.startProcessInstanceByKey("events-test", mapOf("repo" to repo, "issue" to 9L))
        next("started")
        // A propagated incident whose root cause is no longer open is a row (board/Needs.kt, `incidents`); its resolve, as the
        // history handler reports it, was published as an `incident` frame only.
        flows.incident(IncidentChange(root.id, root.id, "propagated-1", "resolved", "config", "custom", "propagated", rootCause = "cause-1"))
        assertEquals("propagated-1", next("resolved")["incidentId"].asString())
        assertEquals("incident" to "propagated-1", next("removed").let { it["kind"].asString() to it["actionId"].asString() })
    }

    @Test
    fun `a publish inside an Operaton command waits for its commit, and a rolled-back command publishes nothing`() {
        val executor = (configuration as ProcessEngineConfigurationImpl).commandExecutorTxRequired
        executor.execute { _ ->
            bus.publishAfterCommit(EngineEvent.Repo(repo, "changed"))
            assertNull(sub.next(Duration.ZERO), "not before the commit")
            null
        }
        assertNotNull(sub.next(Duration.ofSeconds(1)), "after the commit")

        assertFailsWith<RuntimeException> {
            executor.execute { _ ->
                bus.publishAfterCommit(EngineEvent.Repo(repo, "removed"))
                throw IllegalStateException("rolled back")
            }
        }
        nothing()
    }
}

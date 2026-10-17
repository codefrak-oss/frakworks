package com.codefrak.engine.smith

import com.codefrak.engine.intake.tickets
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
import kotlin.test.assertTrue

/**
 * A whole `smith-run` with VictoriaLogs unreachable (nothing listens on port 1):
 * the run starts, its gauge is on `/metrics` while it is live, it completes and
 * is collected exactly as without telemetry, and the gauge is gone after.
 */
@SpringBootTest
@TestPropertySource(properties = ["engine.smith.sandbox=fake", "engine.telemetry.logs-url=http://127.0.0.1:1", "engine.telemetry.timeout=PT1S"])
class SmithTelemetryProcessTest {
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
    @Autowired lateinit var telemetry: SmithTelemetry
    @Autowired lateinit var metrics: SmithMetricsController
    @Autowired lateinit var host: com.codefrak.engine.lease.InstanceId
    @Autowired lateinit var resume: SmithTelemetryResume
    @Autowired lateinit var sessions: SmithSessions
    @Autowired lateinit var stats: SmithRunStats

    private fun runJobs(instanceId: String) {
        repeat(50) {
            val jobs = management.createJobQuery().processInstanceId(instanceId).list()
            if (jobs.isEmpty()) return
            jobs.forEach { management.executeJob(it.id) }
        }
        error("process did not quiesce in 50 job rounds")
    }

    @Test
    fun `an unreachable VictoriaLogs leaves the run to start, complete and be collected as before`() {
        val result = """{"type":"result","total_cost_usd":1.25,"num_turns":7,"duration_ms":4200}"""
        val instance = runtime.startProcessInstanceByKey("smith-run", mapOf(
            Vars.REPO to "acme/widgets", Vars.ISSUE to 91L, Vars.MODEL to "claude-sonnet-5",
            Vars.EFFORT to "low", Vars.DRY_RUN to true))
        runJobs(instance.id)
        assertTrue(sandbox.followers.containsKey("container-91"), "the run's stdout is followed")
        val body = metrics.metrics().body!!
        assertTrue("""smith_run_active{host="${host.name}",issue="91",kind="job",model="claude-sonnet-5",repo="acme/widgets"} 1""" in body, body)

        sandbox.print("container-91", """{"type":"system","subtype":"init"}""")
        sandbox.print("container-91", result)
        sandbox.logText["container-91"] = "smith: start\n$result\n"
        // Exit 0 is `success` only with a result.json (SmithRunProcessTest); without one it is `no-result`.
        sandbox.files["container-91" to OUTPUT_PATH] = """{"outcome":"done","summary":"s"}""".toByteArray()
        sandbox.exit("container-91", 0)
        exits.scanExits("test")
        runJobs(instance.id)

        assertEquals(1L, history.createHistoricProcessInstanceQuery().processInstanceId(instance.id).finished().count())
        val vars = history.createHistoricVariableInstanceQuery().processInstanceId(instance.id).list().associate { it.name to it.value }
        assertEquals("success", vars[Vars.RESULT])
        assertEquals("smith: start\n$result\n", vars[Vars.LOG_TAIL])
        assertEquals("smith: start\n$result\n", (vars[Vars.LOG] as ByteArray).toString(Charsets.UTF_8))
        // Cost, turns and duration live on the run's rows (engine/README.md, "Smith runs"), not among its variables.
        val row = stats.get(instance.id)!!
        assertEquals(1.25, row.cost); assertEquals(7L, row.turns); assertEquals(4200L, row.durationMs)
        listOf("cost", "turns", "durationMs", "runModel", "runEffort", "runTier").forEach { assertTrue(it !in vars, "$it is not a variable") }
        assertTrue("container-91" in sandbox.removed)
        eventually("the series to leave /metrics") { !telemetry.isActive(instance.id) }
        assertTrue("smith_run_active{" !in metrics.metrics().body!!)
    }

    /**
     * The lease moves while a run is live (engine/README.md, "Replicas and the lease"): the old holder stops following
     * it without ending its session ([SmithTelemetry.handOff], on `LeaseLost`); the new holder follows it again
     * ([SmithTelemetryResume], on `LeaseAcquired`), stores its further lines in the same session, and collects it.
     */
    @Test
    fun `a live run handed over with the lease is followed again and collected by the new holder`() {
        val result = """{"type":"result","total_cost_usd":0.5,"num_turns":2,"duration_ms":1000}"""
        val instance = runtime.startProcessInstanceByKey("smith-run", mapOf(
            Vars.REPO to "acme/widgets", Vars.ISSUE to 93L, Vars.MODEL to "claude-sonnet-5",
            Vars.EFFORT to "low", Vars.DRY_RUN to true))
        runJobs(instance.id)
        sandbox.print("container-93", "before")
        eventually("the first line stored") { sessions.lines(instance.id) == listOf("before") }

        telemetry.handOff()
        assertTrue(!sandbox.followers.containsKey("container-93"), "the old holder no longer follows it")
        assertTrue(!telemetry.isActive(instance.id))
        Thread.sleep(StoredSmithSessions.POLL_MILLIS * 3)
        assertEquals(null, sessions.session(instance.id)!!.endedAt, "the session stays open for the new holder")

        resume.atStartup()
        assertTrue(sandbox.followers.containsKey("container-93"), "the new holder follows it")
        assertTrue(telemetry.isActive(instance.id))
        sandbox.print("container-93", "after")
        eventually("the line after the handover, in the same session") { sessions.lines(instance.id) == listOf("before", "after") }

        sandbox.logText["container-93"] = "smith: start\n$result\n"
        sandbox.files["container-93" to OUTPUT_PATH] = """{"outcome":"done","summary":"s"}""".toByteArray()
        sandbox.exit("container-93", 0)
        exits.scanExits("test")
        runJobs(instance.id)
        assertEquals(1L, history.createHistoricProcessInstanceQuery().processInstanceId(instance.id).finished().count())
        val vars = history.createHistoricVariableInstanceQuery().processInstanceId(instance.id).list().associate { it.name to it.value }
        assertEquals("success", vars[Vars.RESULT])
    }
}

package com.codefrak.engine.intake

import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.SmithRunStat
import com.codefrak.engine.smith.SmithRunStats
import com.codefrak.engine.smith.Ticket
import com.codefrak.engine.smith.Vars
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.variable.Variables
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GET /intake/{id}`'s step estimates (engine/README.md, "Stage estimates"): the open root step of a running flow
 * carries the board's estimate, scope level included; nothing else does. Jobs are not executed unless a test runs one.
 */
@SpringBootTest
class IntakeStepEstimateTest {
    @Autowired lateinit var intakes: IntakeController
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var stats: SmithRunStats
    @Autowired lateinit var jdbc: JdbcClient

    private val t0 = OffsetDateTime.parse("2026-09-24T10:00:00Z")
    private val minute = 60_000L

    /** Two granted paths: the `small` scope bucket. */
    private val body = "## Goal\n\nx\n\n## Allowed scope\n\n- `engine/src/**`\n- `engine/README.md`\n- May not touch: `bots/**`\n"

    @BeforeEach
    fun reset() {
        jdbc.sql("DELETE FROM engine.smith_run_stats").update()
    }

    private fun run(minutes: Long, scope: Int?) =
        stats.record(SmithRunStat(UUID.randomUUID().toString(), "chore", "acme/widgets", 1, 6, null, null, null,
            t0, t0.plusMinutes(minutes), 0, null, null, "work", scope))

    /** A chore at tier 6 with [body] as its ticket, under way at `call-do-work`: its Smith run waits at its own first step. */
    private fun choreAtWork(): String {
        val chore = runtime.createProcessInstanceByKey("chore").startBeforeActivity("call-do-work")
            .setVariable(Vars.TIER, 6)
            .setVariable(Vars.TICKET, Variables.byteArrayValue(Brief.ticketBytes(Ticket(1, "t", body, "someone"))))
            .execute()
        management.executeJob(management.createJobQuery().processInstanceId(chore.id).singleResult().id)
        return chore.id
    }

    @Test
    fun `the open root step carries its estimate and its source, and no other step does`() {
        val id = choreAtWork()
        val steps = intakes.state(id).steps
        val work = steps.single { it.activity == "call-do-work" }
        assertNull(work.endedAt)
        assertEquals(30 * minute, work.estimateMs, "an empty smith_run_stats: the default for work")
        assertEquals("default", work.estimateBasis); assertEquals(0, work.estimateRuns)
        assertTrue(steps.filter { it !== work }.all { it.estimateMs == null && it.estimateBasis == null && it.estimateRuns == null },
            "the Smith run's own steps and ended steps carry none")
        runtime.deleteProcessInstance(id, "test cleanup")
    }

    @Test
    fun `a scope bucket with enough runs wins over the tier median, and a thin one falls back to it`() {
        listOf(40L, 40L, 40L, 40L).forEach { run(it, 2) }
        listOf(10L, 10L, 10L, 10L, 10L, 10L).forEach { run(it, 20) }
        val id = choreAtWork()
        val thin = intakes.state(id).steps.single { it.activity == "call-do-work" }
        assertEquals("tier", thin.estimateBasis, "4 small runs are not enough")
        assertEquals(10, thin.estimateRuns); assertEquals(10 * minute, thin.estimateMs)

        run(40, 2)
        val scoped = intakes.state(id).steps.single { it.activity == "call-do-work" }
        assertEquals("scope", scoped.estimateBasis); assertEquals(5, scoped.estimateRuns); assertEquals(40 * minute, scoped.estimateMs)
        runtime.deleteProcessInstance(id, "test cleanup")
    }

    @Test
    fun `a step that waits on someone and every step of an ended flow carry none`() {
        val chore = runtime.createProcessInstanceByKey("chore").startBeforeActivity("user-task-answer-question").execute()
        val waiting = intakes.state(chore.id).steps.single { it.activity == "user-task-answer-question" }
        assertNotNull(waiting.startedAt); assertNull(waiting.endedAt)
        assertNull(waiting.estimateMs); assertNull(waiting.estimateBasis)
        runtime.deleteProcessInstance(chore.id, "test cleanup")

        val done = runtime.startProcessInstanceByKey("hello")
        val state = intakes.state(done.id)
        assertTrue(state.ended)
        assertTrue(state.steps.all { it.estimateMs == null })
    }
}

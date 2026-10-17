package com.codefrak.engine.board

import com.codefrak.engine.smith.SmithRunStat
import com.codefrak.engine.smith.SmithRunStats
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The stage estimates' fallback chain (engine/README.md, "The issues board", `stageEstimateMs`). */
@SpringBootTest
class StageEstimatesTest {
    @Autowired lateinit var estimates: StageEstimates
    @Autowired lateinit var stats: SmithRunStats
    @Autowired lateinit var jdbc: JdbcClient

    private val t0 = OffsetDateTime.parse("2026-09-24T10:00:00Z")
    private val minute = 60_000L

    @BeforeEach
    fun reset() {
        jdbc.sql("DELETE FROM engine.smith_run_stats").update()
    }

    private fun run(mode: String?, kind: String?, tier: Int?, minutes: Long, exit: Long = 0, scope: Int? = null) =
        stats.record(SmithRunStat(UUID.randomUUID().toString(), kind, "acme/widgets", 1, tier, null, null, null,
            t0, t0.plusMinutes(minutes), exit, null, null, mode, scope))

    private fun work(kind: String? = "chore", tier: Int? = 6) = estimates.snapshot().estimateMs("call-do-work", kind, tier)

    /** `tierOf`: the approved `tier` variable, else the tier the root's newest run was started at, from its row; no `runTier` variable (#1976). */
    @Test
    fun `a flow's tier is its tier variable, else its newest run's row`() {
        val root = "root-${UUID.randomUUID()}"
        assertNull(estimates.tierOf(emptyMap(), root), "no run yet")
        stats.start(SmithRunStat("run-${UUID.randomUUID()}", null, "acme/widgets", 1, 3, "m", "low", null, t0, null, null, null, null, mode = "refine", root = root))
        stats.start(SmithRunStat("run-${UUID.randomUUID()}", null, "acme/widgets", 1, 7, "m", "low", null, t0.plusMinutes(5), null, null, null, null, mode = "refine", root = root))
        assertEquals(7, estimates.tierOf(emptyMap(), root), "the newest run's tier")
        assertEquals(5, estimates.tierOf(mapOf(com.codefrak.engine.smith.Vars.TIER to 5), root), "the approved tier wins")
        assertNull(estimates.tierOf(emptyMap(), "root-${UUID.randomUUID()}"), "another root's runs do not count")
    }

    @Test
    fun `an empty table gives every Smith stage its role's default`() {
        val snapshot = estimates.snapshot()
        StageEstimates.ROLES.forEach { (stage, role) ->
            assertEquals(StageEstimates.DEFAULTS.getValue(role), snapshot.estimateMs(stage, "chore", 6), stage)
        }
        assertEquals(30 * minute, work())
    }

    @Test
    fun `the chain narrows from role to role and kind to role, kind and tier as runs exist`() {
        run("work", "feature", 3, 10)
        assertEquals(10 * minute, work(), "role only: another kind's run still counts")
        run("work", "chore", 3, 20)
        assertEquals(20 * minute, work(), "role and kind win over role")
        run("work", "chore", 6, 40)
        assertEquals(40 * minute, work(), "role, kind and tier win over role and kind")
        assertEquals(30 * minute, work(tier = 5), "no run at tier 5: role and kind, median of 20 and 40")
        assertEquals(20 * minute, estimates.snapshot().estimateMs("call-design", "feature", 6), "no design run: its default")
    }

    @Test
    fun `the median of an odd and an even count, failed runs and roleless rows left out`() {
        listOf(5L, 9L, 30L).forEach { run("review", "chore", 6, it) }
        assertEquals(9 * minute, estimates.snapshot().estimateMs("call-review-pr", "chore", 6))
        run("review", "chore", 6, 11)
        assertEquals(10 * minute, estimates.snapshot().estimateMs("call-review-pr", "chore", 6), "(9 + 11) / 2")
        run("review", "chore", 6, 500, exit = 1)
        run(null, "chore", 6, 500)
        assertEquals(10 * minute, estimates.snapshot().estimateMs("call-review-pr", "chore", 6))
    }

    @Test
    fun `an intake's refiner and a proposal's drafter and reviewer take their role's default, then its median`() {
        val empty = estimates.snapshot()
        assertEquals(StageEstimates.Estimate(10 * minute, "default", 0), empty.estimate("call-refine-ask", "intake", 5))
        assertEquals(StageEstimates.Estimate(10 * minute, "default", 0), empty.estimate("call-draft", "proposal", 5))
        assertEquals(StageEstimates.Estimate(10 * minute, "default", 0), empty.estimate("call-review", "proposal", 5))
        assertNull(empty.estimate("event-intake-authorized", "intake", 5))
        assertNull(empty.estimate("user-task-answer-decision", "proposal", 5))
        listOf(4L, 6L, 20L).forEach { run("refine", "intake", 5, it) }
        listOf(12L, 18L).forEach { run("propose", "proposal", 5, it) }
        val runs = estimates.snapshot()
        assertEquals(StageEstimates.Estimate(6 * minute, "tier", 3), runs.estimate("call-refine-ask", "intake", 5))
        assertEquals(StageEstimates.Estimate(15 * minute, "tier", 2), runs.estimate("call-draft", "proposal", 5))
    }

    @Test
    fun `a non-Smith stage takes its default, a waiting stage none, and a rework round its own first round`() {
        run("work", "chore", 6, 40)
        val snapshot = estimates.snapshot()
        assertEquals(10 * minute, snapshot.estimateMs("call-ci-check", "chore", 6))
        assertEquals(minute, snapshot.estimateMs("task-open-pr", "chore", 6))
        assertEquals(minute, snapshot.estimateMs("task-merge-pr", "chore", 6))
        assertNull(snapshot.estimateMs("user-task-answer-question", "chore", 6))
        assertNull(snapshot.estimateMs("event-child-closed", "feature", 6))
        assertEquals(7 * minute, snapshot.estimateMs("call-do-work", "chore", 6, priorRound = 7 * minute))
        assertNull(snapshot.estimateMs("user-task-answer-question", "chore", 6, priorRound = 7 * minute))
    }

    @Test
    fun `a scope bucket with at least five runs wins over the tier median, and a thinner one falls back to it`() {
        listOf(20L, 20L, 20L).forEach { run("work", "chore", 6, it, scope = 12) }
        repeat(4) { run("work", "chore", 6, 60, scope = 2) }
        val thin = estimates.snapshot().estimate("call-do-work", "chore", 6, scopePaths = 3)!!
        assertEquals(StageEstimates.Estimate(60 * minute, "tier", 7), thin, "4 small runs: the tier's median of 7")

        run("work", "chore", 6, 60, scope = 1)
        val snapshot = estimates.snapshot()
        assertEquals(StageEstimates.Estimate(60 * minute, "scope", 5), snapshot.estimate("call-do-work", "chore", 6, scopePaths = 3))
        assertEquals(StageEstimates.Estimate(60 * minute, "tier", 8), snapshot.estimate("call-do-work", "chore", 6, scopePaths = 9),
            "3 large runs: the tier's median")
        assertEquals("tier", snapshot.estimate("call-do-work", "chore", 6)!!.basis, "no scope: the scope level is skipped")
        assertEquals(StageEstimates.Estimate(7 * minute, "prior-round", 0),
            snapshot.estimate("call-do-work", "chore", 6, priorRound = 7 * minute, scopePaths = 3), "a rework round's own first round still wins")
        assertEquals(StageEstimates.Estimate(60 * minute, "kind", 8), snapshot.estimate("call-do-work", "chore", 5, scopePaths = 3),
            "another tier: no scope level, the kind's median")
        assertEquals(StageEstimates.Estimate(60 * minute, "role", 8), snapshot.estimate("call-do-work", "bug", 6, scopePaths = 3))
    }

    @Test
    fun `the scope buckets' edges, and a default's or a non-Smith stage's source`() {
        assertNull(StageEstimates.bucket(null))
        assertEquals(listOf("small", "small", "medium", "medium", "large"), listOf(0, 3, 4, 8, 9).map { StageEstimates.bucket(it) })
        val snapshot = estimates.snapshot()
        assertEquals(StageEstimates.Estimate(30 * minute, "default", 0), snapshot.estimate("call-do-work", "chore", 6, scopePaths = 4))
        assertEquals(StageEstimates.Estimate(10 * minute, "default", 0), snapshot.estimate("call-ci-check", "chore", 6))
        assertEquals(StageEstimates.Estimate(3 * minute, "prior-round", 0), snapshot.estimate("call-ci-check", "chore", 6, priorRound = 3 * minute))
    }

    @Test
    fun `a run's scope size is stored and read back`() {
        val id = UUID.randomUUID().toString()
        stats.record(SmithRunStat(id, "chore", "acme/widgets", 1, 6, null, null, null, t0, t0.plusMinutes(5), 0, null, null, "work", 7))
        assertEquals(7, stats.get(id)!!.scopePaths)
        assertEquals(listOf(7), stats.durations().map { it.scopePaths })
        run("work", "chore", 6, 5)
        assertEquals(setOf(7, null), stats.durations().map { it.scopePaths }.toSet(), "no scope: null")
    }
}

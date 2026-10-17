package com.codefrak.engine.board

import com.codefrak.engine.smith.AllowedScope
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.RunDuration
import com.codefrak.engine.smith.SmithRunStats
import com.codefrak.engine.smith.Vars
import org.springframework.stereotype.Component

/**
 * How long a flow's current stage should take (engine/README.md, "The issues
 * board", `stageEstimateMs`). A Smith stage (`call-do-work`, …) is the median
 * duration of past successful runs of its role from `engine.smith_run_stats`,
 * narrowed to the same root flow kind and tier when there are such runs, and
 * falling back by dropping tier, then kind, then to [DEFAULTS]. Above the tier
 * level sits the scope level: the same role, kind and tier with a ticket of the
 * same size of Allowed scope ([bucket] of [com.codefrak.engine.smith.AllowedScope.grantCount]),
 * which counts only with at least [MIN_SCOPE_RUNS] runs (engine/README.md, "Stage estimates"). Any other
 * stage the flow moves through on its own (`task-open-pr`, `call-ci-check`,
 * …) takes only its [DEFAULTS] entry; a stage that waits on someone
 * (`user-task-*`, an intake's authorization, a child closing, a merge conflict) has no estimate.
 */
@Component
class StageEstimates(private val stats: SmithRunStats) {
    companion object {
        private const val MINUTE = 60_000L

        /** The Smith role (`smith_run_stats.mode`) each call activity runs, as the BPMN passes it. */
        val ROLES = mapOf(
            "call-design" to "design", "call-reproduce" to "reproduce", "call-do-work" to "work",
            "call-security-review" to "security", "call-review-pr" to "review", "call-accept" to "accept",
            "call-verify" to "verify", "call-replan" to "replan",
            // an intake's refiner, and a proposal's drafter and its reviewer
            "call-refine-ask" to "refine", "call-draft" to "propose", "call-review" to "review",
            // a research ticket's answerer
            "call-answer" to "research",
        )

        /** Per role, when no run of it is recorded; and the only estimate for the stages that are not Smith runs. */
        val DEFAULTS = mapOf(
            "design" to 20 * MINUTE, "reproduce" to 15 * MINUTE, "work" to 30 * MINUTE, "security" to 10 * MINUTE,
            "review" to 10 * MINUTE, "accept" to 10 * MINUTE, "verify" to 10 * MINUTE, "replan" to 10 * MINUTE,
            "refine" to 10 * MINUTE, "propose" to 10 * MINUTE, "research" to 15 * MINUTE,
            "call-ci-check" to 10 * MINUTE, "task-rebase-pr" to 2 * MINUTE,
        )

        /**
         * The fewest runs a scope bucket needs before its median is used. A median of fewer is mostly the one odd
         * run in it, so a thin bucket falls back to its tier's median, which today's board already shows. The
         * coarser levels keep their one-run rule, so the board's numbers change only where a bucket has history.
         */
        const val MIN_SCOPE_RUNS = 5

        /**
         * A ticket's Allowed-scope size as a bucket: `small` (up to 3 paths or globs: a fix in a file or two),
         * `medium` (4 to 8: a change across one component, its tests and docs) or `large` (9 and up: a change
         * across components). Cut here, not stored, so the edges can be retuned without a migration; null for an
         * unknown size, which no bucket takes.
         */
        fun bucket(scopePaths: Int?): String? = when {
            scopePaths == null -> null
            scopePaths <= 3 -> "small"
            scopePaths <= 8 -> "medium"
            else -> "large"
        }

        /** Any other `task-*` or `start-*` step: a GitHub or git call, over in about a minute. */
        private const val STEP_DEFAULT = MINUTE

        /** A root flow's ticket's Allowed-scope size, from its `approvedTicket` variable; null without one (an intake). */
        fun scopeOf(variables: Map<String, Any?>): Int? = runCatching {
            (variables[Vars.TICKET] as? ByteArray)?.let { AllowedScope.grantCount(Brief.ticket(it).body) }
        }.getOrNull()

        fun median(values: List<Long>): Long? {
            if (values.isEmpty()) return null
            val sorted = values.sorted(); val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        }
    }

    /**
     * A root flow's tier: the approved `tier` among its variables, else the tier its newest Smith run was started at
     * (`engine.smith_run_stats.tier`, the one home of a run's tier: a flow with no `tier` of its own, an intake say,
     * still runs its Smiths at one); null before any run.
     */
    fun tierOf(variables: Map<String, Any?>, root: String): Int? =
        (variables[Vars.TIER] as? Number)?.toInt() ?: stats.latestTier(root)

    /** One read of the table, for one `GET /issues`: every flow on the board is estimated from it. */
    fun snapshot(): Snapshot = Snapshot(stats.durations())

    /**
     * An estimate and where it came from: `basis` is `scope` (the role, kind, tier and scope bucket's median),
     * `tier`, `kind` or `role` (the coarser medians), `default` ([DEFAULTS]) or `prior-round` (this ticket's
     * own first round of the stage); `runs` is how many runs the median rests on, 0 for the last two.
     */
    data class Estimate(val ms: Long, val basis: String, val runs: Int)

    class Snapshot(runs: List<RunDuration>) {
        private val byRole = runs.groupBy { it.mode }

        /**
         * The estimate for [stage] in a flow of [rootKind] at [tier] whose ticket grants [scopePaths] paths; null
         * for a stage that waits on someone. [priorRound] is how long this ticket's own first round of the stage
         * took, when it is a rework round and that is known: it wins over the table for a stage that has an
         * estimate at all. A null [scopePaths] skips the scope level.
         */
        fun estimate(stage: String, rootKind: String?, tier: Int?, priorRound: Long? = null, scopePaths: Int? = null): Estimate? {
            val role = ROLES[stage] ?: return (DEFAULTS[stage] ?: STEP_DEFAULT.takeIf { stage.startsWith("task-") || stage.startsWith("start-") })
                ?.let { priorRound?.let { p -> Estimate(p, "prior-round", 0) } ?: Estimate(it, "default", 0) }
            if (priorRound != null) return Estimate(priorRound, "prior-round", 0)
            val all = byRole[role].orEmpty()
            val kind = all.filter { it.rootFlowKind == rootKind }
            val atTier = kind.filter { tier != null && it.tier == tier }
            val scoped = bucket(scopePaths)?.let { b -> atTier.filter { bucket(it.scopePaths) == b } }.orEmpty()
            val levels = listOf("scope" to scoped.takeIf { it.size >= MIN_SCOPE_RUNS }.orEmpty(), "tier" to atTier, "kind" to kind, "role" to all)
            return levels.firstNotNullOfOrNull { (basis, level) -> median(level.map { it.millis })?.let { Estimate(it, basis, level.size) } }
                ?: Estimate(DEFAULTS.getValue(role), "default", 0)
        }

        /** [estimate]'s duration alone. */
        fun estimateMs(stage: String, rootKind: String?, tier: Int?, priorRound: Long? = null, scopePaths: Int? = null): Long? =
            estimate(stage, rootKind, tier, priorRound, scopePaths)?.ms
    }
}

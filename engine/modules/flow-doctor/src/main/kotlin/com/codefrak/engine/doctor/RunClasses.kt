package com.codefrak.engine.doctor

import com.codefrak.engine.accounts.AccountUsage
import com.codefrak.engine.accounts.AccountUsage.Companion.FABLE_REACHED
import com.codefrak.engine.accounts.AccountUsages
import com.codefrak.engine.smith.SmithRunStat
import com.codefrak.engine.smith.SmithRunStats
import org.operaton.bpm.engine.HistoryService
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * A failed Smith run's class (engine/README.md, "Doctor"), from its `engine.smith_run_stats` row and its
 * account's `engine.ai_account_usage`: `oom` when the sandbox was OOM-killed, `rate-limited` when the account's
 * 5-hour or 7-day utilisation was at 1 in a window the run ended in (its reset was still ahead of the run's end), or,
 * for a `claude-fable-*` run, its Fable limit was reached or its Fable meter at 1 with the reset ahead of the run's end,
 * `sandbox-failed` when run-smith.sh exited 69 (a step before `claude` failed), else `exit:<code>`; null with no stats row.
 */
@Component
class RunClasses(private val stats: SmithRunStats, private val usages: AccountUsages, private val history: HistoryService) {
    /** The class of the `smith-run` instance [smithRunId]. */
    fun ofRun(smithRunId: String?): String? {
        val run = smithRunId?.let { stats.get(it) } ?: return null
        return of(run, run.account?.let { usages.get(it) })
    }

    companion object {
        /** run-smith.sh's exit when a step before `claude` (the clone) failed: no Smith ran. */
        const val SANDBOX_FAILED_EXIT = 69L

        /** The class of [run], judged by its account's [usage] at the run's end. */
        fun of(run: SmithRunStat, usage: AccountUsage?): String {
            if (run.oomKilled == true) return "oom"
            val ended = run.ended
            fun ahead(reset: OffsetDateTime?) = reset != null && ended != null && reset.isAfter(ended)
            fun at1(utilization: Double?, reset: OffsetDateTime?) = utilization != null && utilization >= 1.0 && ahead(reset)
            if (usage != null && (at1(usage.fiveHourUtilization, usage.fiveHourReset) || at1(usage.sevenDayUtilization, usage.sevenDayReset))) return "rate-limited"
            // The Fable limit, as AccountUsage.fableOut has it but at the run's end, only for a run on a Fable model.
            val fable = run.model?.startsWith("claude-fable-") == true
            if (fable && usage != null && ((usage.fableLimit == FABLE_REACHED && ahead(usage.fableLimitReset)) ||
                    at1(usage.fableUtilization, usage.fableReset))) return "rate-limited"
            if (run.exitCode == SANDBOX_FAILED_EXIT) return "sandbox-failed"
            return "exit:${run.exitCode}"
        }
    }

    /**
     * The class of the run an incident names: the incident sits on `read result` in a `reusable-smith-run` instance
     * [processInstanceId], whose newest called `smith-run` is the run; or on that `smith-run` itself.
     */
    fun ofIncident(processInstanceId: String?): String? {
        if (processInstanceId == null) return null
        val self = history.createHistoricProcessInstanceQuery().processInstanceId(processInstanceId).singleResult()
        val run = if (self?.processDefinitionKey == TicketPositions.SMITH_RUN) self.id
        else history.createHistoricProcessInstanceQuery().superProcessInstanceId(processInstanceId)
            .processDefinitionKey(TicketPositions.SMITH_RUN).list().maxByOrNull { it.startTime }?.id
        return ofRun(run)
    }
}

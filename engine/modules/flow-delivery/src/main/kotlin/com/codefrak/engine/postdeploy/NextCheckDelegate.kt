package com.codefrak.engine.postdeploy

import com.codefrak.engine.smith.Vars
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Date

/**
 * `${nextCheck}`: after a `not_yet` check run, when the next one runs, as `checkWakeAt` (a Date for the flow's
 * timer): the Smith's hint when it gave `at` (already set by [CheckResultHandler]; left as it is), else now plus the
 * section's `Re-check:` for the first wait, else now plus the ladder's rung for the runs so far (`checkHistory`):
 * 1h after the first, 4h after the second, 1d after the third, then daily. An `event` hint sets no time of its own:
 * the ladder's time is the fallback the flow wakes on when the event never comes.
 */
@Component("nextCheck")
class NextCheckDelegate(private val clock: Clock = Clock.systemUTC()) : JavaDelegate {
    private val log = LoggerFactory.getLogger(NextCheckDelegate::class.java)

    override fun execute(execution: DelegateExecution) {
        val hinted = execution.getVariable(Vars.CHECK_WAKE_AT) as? Date
        val runs = CheckRun.list(execution.getVariable(Vars.CHECK_HISTORY) as? ByteArray).size
        val section = PostDeployCheck.of(execution.getVariable(Vars.POST_DEPLOY_CHECK) as? ByteArray)
        val at = hinted?.toInstant() ?: next(Instant.now(clock), runs, section?.recheck)
        execution.setVariable(Vars.CHECK_WAKE_AT, Date.from(at))
        log.info("check of {}: {} runs so far, next at {}{}", execution.processInstanceId, runs, at, if (hinted != null) " (the Smith's hint)" else "")
    }

    companion object {
        /** The waits between runs when neither the Smith nor the section says: after the 1st, 2nd, 3rd run; the last repeats. */
        val LADDER = listOf(Duration.ofHours(1), Duration.ofHours(4), Duration.ofDays(1))

        /** The next run's time from [now]: [recheck] after the first run when the section gave one, else the ladder's rung for [runs] so far. */
        fun next(now: Instant, runs: Int, recheck: Duration?): Instant {
            if (runs <= 1 && recheck != null) return now.plus(recheck)
            return now.plus(LADDER[(runs - 1).coerceIn(0, LADDER.size - 1)])
        }
    }
}

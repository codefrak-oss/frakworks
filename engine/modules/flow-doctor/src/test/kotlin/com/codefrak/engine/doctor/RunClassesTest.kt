package com.codefrak.engine.doctor

import com.codefrak.engine.accounts.AccountUsage
import com.codefrak.engine.smith.SmithRunStat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals

/** [RunClasses.of]: the 5-hour and 7-day windows for any run, the Fable limit only for a `claude-fable-*` run. */
class RunClassesTest {
    private val ended = OffsetDateTime.of(2026, 10, 5, 23, 48, 0, 0, ZoneOffset.UTC)
    private val ahead = ended.plusDays(2)

    private fun run(model: String) = SmithRunStat("run", "ticket", "acme/x", 1, 9, model, "high", "a", ended.minusSeconds(6), ended, 1, null, false)
    private fun usage(sevenDay: Double = 0.6) = AccountUsage("a", 0.42, "allowed", ahead, sevenDay, "allowed", ahead, "allowed", 429, 1, ended)

    @Test
    fun `the 7-day window at 1 is rate-limited`() =
        assertEquals("rate-limited", RunClasses.of(run("claude-opus-5-5"), usage(sevenDay = 1.0)))

    @Test
    fun `a reached Fable limit resetting after the run's end is rate-limited on a Fable run`() =
        assertEquals("rate-limited", RunClasses.of(run("claude-fable-5-1"), usage().copy(fableLimit = "reached", fableLimitReset = ahead)))

    @Test
    fun `the Fable meter at 1 resetting after the run's end is rate-limited on a Fable run`() =
        assertEquals("rate-limited", RunClasses.of(run("claude-fable-5-1"), usage().copy(fableUtilization = 1.0, fableReset = ahead)))

    @Test
    fun `the Fable meter does not class a non-Fable run`() =
        assertEquals("exit:1", RunClasses.of(run("claude-opus-5-5"),
            usage().copy(fableUtilization = 1.0, fableReset = ahead, fableLimit = "reached", fableLimitReset = ahead)))

    @Test
    fun `a Fable limit reset before the run's end is not rate-limited`() =
        assertEquals("exit:1", RunClasses.of(run("claude-fable-5-1"), usage().copy(fableLimit = "reached", fableLimitReset = ended.minusHours(1))))

    @Test
    fun `exit 69 is sandbox-failed, exit 1 stays exit 1`() {
        assertEquals("sandbox-failed", RunClasses.of(run("claude-opus-5-5").copy(exitCode = 69L), usage()))
        assertEquals("exit:1", RunClasses.of(run("claude-opus-5-5").copy(exitCode = 1L), usage()))
    }
}

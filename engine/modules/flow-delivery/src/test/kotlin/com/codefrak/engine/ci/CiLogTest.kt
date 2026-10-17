package com.codefrak.engine.ci

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #2258: a failed check whose log shows the engine workflow's BuildKit connection loss (`rpc error ...
 * EOF`), and no failing test, is reported as infrastructure rather than presented as an unexplained test
 * failure (friction g90/g91, PR #1641 / ticket #1630).
 */
class CiLogTest {
    private val retryThenLoseAgain = """
        2026-10-09T14:02:10.0000000Z #8 1.2 FROM docker.io/library/gradle:8.10-jdk21
        2026-10-09T14:03:40.0000000Z #11 88.4 > Task :engine-app:test
        2026-10-09T14:04:55.0000000Z #11 163.9 CiChecksTest > the setting names a check() PASSED
        2026-10-09T14:05:10.6000000Z rpc error: code = Unavailable desc = error reading from server: EOF
        2026-10-09T14:05:10.8000000Z ::warning::infrastructure: the BuildKit connection was lost (no task failed); retrying the build once
        2026-10-09T14:05:12.0000000Z #1 1.0 [internal] booting buildkit
    """.trimIndent() + "\n" + (0 until 90).joinToString("\n") { "2026-10-09T14:06:${"%02d".format(it % 60)}.0000000Z #11 ${30 + it}.0 SomeOtherTest > case $it() PASSED" } +
        "\n2026-10-09T14:09:00.1000000Z rpc error: code = Unavailable desc = error reading from server: EOF" +
        "\n2026-10-09T14:09:00.2000000Z ##[error]buildx failed with: ERROR: failed to solve: rpc error: code = Unavailable desc = error reading from server: EOF" +
        "\n2026-10-09T14:09:00.3000000Z ##[error]Process completed with exit code 1."

    @Test
    fun `a BuildKit connection loss with no failed test is reported as infrastructure, even once the warning line has scrolled out of the tail`() {
        val lines = CiLog.clean(retryThenLoseAgain)
        assertTrue(CiLog.failures(lines).isEmpty())
        assertFalse(CiLog.tail(lines).contains("::warning::infrastructure:"), "the warning line should have scrolled out of the tail")

        val finding = CiLog.ask("engine (build and test)", "failure", "abc123def4567890", "https://example/run/1", retryThenLoseAgain, 42)

        assertTrue(finding.contains("Infrastructure, not a test failure"))
        assertTrue(finding.contains("BuildKit"))
        assertFalse(finding.contains("Failing:"))
    }

    @Test
    fun `either BuildKit connection-loss signature alone, on its own line, is reported as infrastructure`() {
        val onlyUnavailable = """
            #11 88.4 > Task :engine-app:test
            SomeOtherTest > case 0() PASSED
            ##[error]buildx failed with: ERROR: failed to solve: rpc error: code = Unavailable
        """.trimIndent()
        val onlyEofLine = """
            #11 88.4 > Task :engine-app:test
            SomeOtherTest > case 0() PASSED
            ##[error]error reading from server: EOF
        """.trimIndent()

        for (log in listOf(onlyUnavailable, onlyEofLine)) {
            val finding = CiLog.ask("engine (build and test)", "failure", "abc123def4567890", "https://example/run/1", log)
            assertTrue(finding.contains("Infrastructure, not a test failure"), "expected infrastructure label for:\n$log")
            assertFalse(finding.contains("Failing:"))
        }
    }

    @Test
    fun `a real test failure is not relabeled as infrastructure even if the log also shows an rpc error`() {
        val log = """
            #11 88.4 > Task :engine-app:test
            SomeTest > a real bug() FAILED
            rpc error: code = Unavailable desc = error reading from server: EOF
        """.trimIndent()

        val finding = CiLog.ask("engine (build and test)", "failure", "abc123def4567890", "https://example/run/1", log)

        assertFalse(finding.contains("Infrastructure, not a test failure"))
        assertTrue(finding.contains("Failing:"))
        assertTrue(finding.contains("SomeTest > a real bug() FAILED"))
    }

    @Test
    fun `a FLAKY-TEST line is read as a retried test and never as a failure`() {
        val lines = CiLog.clean(
            "#11 90.1 FLAKY-TEST: com.x.FooTest > does a thing() passed on retry\n" +
                "#11 90.2 FLAKY-TEST: com.x.FooTest > does a thing() passed on retry\n" +
                "#11 91.0 com.x.BarTest > breaks() FAILED"
        )
        assertTrue(CiLog.flaky(lines) == listOf(CiLog.FlakyTest("com.x.FooTest", "does a thing")))
        assertTrue(CiLog.failures(lines) == listOf("com.x.BarTest > breaks() FAILED"))
    }

    @Test
    fun `flaky caps the tests one log files and skips unsafe names`() {
        val many = (1..50).map { "FLAKY-TEST: com.x.T$it > m() passed on retry" }
        assertTrue(CiLog.flaky(many).size == CiLog.MAX_FLAKY)
        val unsafe = listOf("FLAKY-TEST: com.x.T > a `b`() passed on retry", "FLAKY-TEST: com.x.T > <img>() passed on retry",
            "FLAKY-TEST: com.x.T> > m() passed on retry")
        assertTrue(CiLog.flaky(unsafe).isEmpty())
    }
}

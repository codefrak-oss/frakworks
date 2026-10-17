package com.codefrak.engine.ci

/**
 * A failed check's log, cut down to what a work Smith needs (engine/README.md, "The brief"): the
 * failing tests and compile errors, then the end of the log. Each line loses the Actions timestamp
 * and BuildKit's `#12 34.5 ` step prefix.
 */
object CiLog {
    private val log = org.slf4j.LoggerFactory.getLogger(CiLog::class.java)
    const val TAIL_LINES = 80
    const val TAIL_CHARS = 6000
    const val MAX_FAILURES = 30
    const val MAX_FLAKY = 10

    private val timestamp = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z ?""")
    private val buildkit = Regex("""^#\d+ (\d+\.\d+ )?""")
    private val failedTest = Regex("""^\S.* > .+ FAILED$""")
    private val flakyTest = Regex("""^FLAKY-TEST: ([A-Za-z0-9_.$]{1,200}) > ([^`#<>\r\n]{1,200})\(\) passed on retry$""")
    private val testTask = Regex("""^> Task :([A-Za-z0-9_-]{1,100}):test\b.*""")
    private val compileError = Regex("""^e: .+""")
    private val buildKitLost = Regex("""rpc error: code = Unavailable|error reading from server: EOF""")

    fun clean(log: String): List<String> = log.lines().map { it.trimEnd('\r').replace(timestamp, "").replace(buildkit, "") }

    /** Gradle's `Class > test name() FAILED` lines and Kotlin's `e: ` compile errors, in order, each once. */
    fun failures(lines: List<String>): List<String> =
        lines.filter { failedTest.matches(it) || compileError.matches(it) }.distinct().take(MAX_FAILURES)

    /** A test that failed and then passed on a retry, from the build's `FLAKY-TEST:` line (engine/README.md, "Flaky tests"). */
    /** [module]: the Gradle project of the `> Task :<module>:test` line before it, or null when the log has none. */
    data class FlakyTest(val cls: String, val method: String, val module: String? = null)

    /**
     * The `FLAKY-TEST: <Class> > <method>() passed on retry` lines of a passing check's log, each test once, at most
     * [MAX_FLAKY] (the build's own `maxFailures` is 10). A class outside `[A-Za-z0-9_.$]`, a method with a backtick,
     * `#`, `<` or `>`, or either over 200 characters is not matched: the text becomes a ticket's title and body.
     */
    fun flaky(lines: List<String>): List<FlakyTest> {
        var module: String? = null
        val all = lines.mapNotNull { line ->
            testTask.matchEntire(line)?.let { module = it.groupValues[1] }
            flakyTest.matchEntire(line)?.let { FlakyTest(it.groupValues[1], it.groupValues[2], module) }
        }.distinct()
        if (all.size > MAX_FLAKY) log.warn("{} flaky tests in one log; filing the first {}, dropping {}", all.size, MAX_FLAKY, all.size - MAX_FLAKY)
        return all.take(MAX_FLAKY)
    }

    /**
     * The engine workflow's build step (`.github/workflows/engine.yml`, "build and test") lost the remote
     * BuildKit connection (`rpc error: code = Unavailable` or `error reading from server: EOF`, the same two
     * independent signatures the workflow's own `grep -qE` checks for retry) and no test or compile error
     * failed: the job failed on infrastructure, not the code, whether or not the workflow's own
     * `::warning::infrastructure:` line (printed once, before its single retry) is still in [tail]'s window.
     * Read over the whole log, not the tail, so a loss that scrolls out of the excerpt is still caught.
     */
    fun buildKitConnectionLost(lines: List<String>, failures: List<String>): Boolean =
        failures.isEmpty() && lines.any { buildKitLost.containsMatchIn(it) }

    /** The last [TAIL_LINES] lines, no more than [TAIL_CHARS] characters of them. */
    fun tail(lines: List<String>): String =
        lines.dropLastWhile { it.isBlank() }.takeLast(TAIL_LINES).joinToString("\n").takeLast(TAIL_CHARS)

    /** The finding's ask for a failed check: what failed where, the failures, the log's end, and what to do. */
    fun ask(check: String, conclusion: String, sha: String, url: String, log: String?, checkRunId: Long? = null): String = buildString {
        append("The engine check `$check` (built and tested on the build runners) ended `$conclusion` on the pushed head ${sha.take(12)}")
        if (url.isNotBlank()) append(": $url")
        appendLine(".")
        if (checkRunId != null) {
            appendLine("The whole job log, when the excerpt below does not show the cause: read_ci_log({check_run: $checkRunId}) on the frakworks MCP server.")
        }
        if (log == null) {
            appendLine().appendLine("The job log could not be read; open the check's page above for it.")
        } else {
            val lines = clean(log)
            val failures = failures(lines)
            if (buildKitConnectionLost(lines, failures)) {
                appendLine().appendLine(
                    "Infrastructure, not a test failure: the job log shows the build step lost the remote BuildKit " +
                        "connection (`rpc error: code = Unavailable` or `error reading from server: EOF`), and no test or compile error failed."
                )
            }
            if (failures.isNotEmpty()) {
                appendLine().appendLine("Failing:")
                failures.forEach { appendLine("- $it") }
            }
            appendLine().appendLine("The end of the job log:").appendLine("```").appendLine(tail(lines)).appendLine("```")
        }
        append("Fix the cause and commit; the engine pushes the fix and runs the check again. ")
        append("Run the failing test class in the sandbox before you end the round; never the full build.")
    }
}

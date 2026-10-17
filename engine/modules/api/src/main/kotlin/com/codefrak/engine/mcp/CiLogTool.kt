package com.codefrak.engine.mcp

import com.codefrak.engine.ci.CheckRuns
import com.codefrak.engine.ci.CiLog

/**
 * read_ci_log, on [FrakworksMcp] for a Smith only: the whole job log behind a failed engine check, paged. The
 * finding `ci-<round>-1` carries only the failing tests and the log's last 80 lines ([CiLog.ask]); a Smith whose
 * failure is earlier in the log, or whose test runner prints failures in a shape [CiLog.failures] does not know
 * (node's `✖`), reads the rest here. The repo is the Smith's own, from its container's labels: it names none.
 */
class CiLogTool(private val runs: CheckRuns?) {
    companion object {
        const val NAME = "read_ci_log"

        val TOOL = McpTool(NAME,
            "Read-only: the whole job log of an engine check run in your repo, for when a `ci` finding's excerpt " +
                "(the failing tests and the log's last 80 lines) does not show the cause. `check_run` is the id the " +
                "finding names (also the number after /job/ in the check's link). Actions timestamps and BuildKit's " +
                "`#12 34.5 ` step prefixes are stripped from each line. The log comes in pages of ${FrakworksMcp.PR_CHAR_BUDGET} " +
                "characters: when more remains, the page ends with a line naming the `offset` to pass for the next page; " +
                "call again with it until a page ends without that line.",
            obj(
                "check_run" to prop("integer", "The check run (Actions job) id the finding names.", "minimum" to 1),
                "offset" to prop("integer", "The character position the returned page starts at (default 0); a cut page's last line names the next one.", "minimum" to 0),
                required = listOf("check_run"),
            ))

    }

    /** A Smith's call: its own repo, the check run it names, the page at `offset`. */
    fun call(args: Map<String, Any?>, caller: McpCaller.Smith): McpToolResult {
        val extra = args.keys.filter { it != "check_run" && it != "offset" }
        if (extra.isNotEmpty()) return McpToolResult.error("$NAME takes only \"check_run\" and \"offset\", not ${quoted(extra)}")
        val id = wholeNumberOf(args["check_run"])?.takeIf { it > 0 }
            ?: return McpToolResult.error("not a check run id: ${json(args["check_run"])} (a positive integer)")
        val offset = when (val o = args["offset"]) {
            null -> 0
            else -> wholeNumberOf(o)?.takeIf { it >= 0 && it <= Int.MAX_VALUE }?.toInt()
                ?: return McpToolResult.error("not an offset: ${json(o)} (a non-negative integer)")
        }
        val run = caller.caller
        val repo = run.repo ?: return McpToolResult.error("Smith run ${run.runId} works in no target repo, so it has no check runs to read")
        val reader = runs ?: return McpToolResult.error("the engine has no GitHub check-run reader; open the check's page for its log")
        val log = try { reader.log(repo, id) } catch (e: Exception) {
            return McpToolResult.error("could not read the log of check run $id in $repo: ${e.message}")
        }
        val text = CiLog.clean(log).joinToString("\n").trimEnd()
        if (text.isEmpty()) return McpToolResult.error("the log of check run $id in $repo is empty (the job may still be running, or its log expired)")
        val page = FrakworksMcp.pageText(text, offset)
            ?: return McpToolResult.error("offset $offset is past the end: the log is ${text.length} characters long")
        return McpToolResult("The job log of check run $id in $repo:\n\n$page")
    }
}

package com.codefrak.engine.mcp

/**
 * read_run_log, on [FrakworksMcp] for a Smith only, and `GET /runs/{id}/log` ([RunLogController]) behind it (#1634):
 * the last [LOG_CHARS] characters of a Smith run's transcript, read where read_transcript reads it ([TranscriptReader]).
 * A Smith may read a run on its own ticket, or, refining a doctor-filed ask, a run on a stuck ticket that ask names
 * ([FrakworksEngine.doctorStuckTickets]); any other run is refused.
 */
class RunLogTool(private val engine: FrakworksEngine) {
    companion object {
        const val NAME = "read_run_log"
        /** The tail of a run's transcript one read answers. */
        const val LOG_CHARS = 20000

        val TOOL = McpTool(NAME,
            "Read-only: the last $LOG_CHARS characters of a Smith run's transcript, for a run on your own ticket or, " +
                "when you refine an ask the engine's doctor filed, a run on the stuck ticket it names (its \"see its log\"). " +
                "`runId` is the Smith run's id, as the ask or a doctor run names it. Any other run is refused.",
            mapOf("type" to "object",
                "properties" to mapOf("runId" to mapOf("type" to "string", "description" to "The Smith run's id.")),
                "required" to listOf("runId"), "additionalProperties" to false))
    }

    /** What a read came to: the text, or why it was refused (`status` the HTTP status `GET /runs/{id}/log` answers). */
    sealed interface Read {
        data class Log(val text: String) : Read
        data class Refused(val status: Int, val why: String) : Read
    }

    fun call(args: Map<String, Any?>, caller: McpCaller.Smith): McpToolResult {
        val extra = args.keys.filter { it != "runId" }
        if (extra.isNotEmpty()) return McpToolResult.error("$NAME takes only \"runId\", not ${quoted(extra)}")
        val id = args["runId"] as? String
        if (id.isNullOrBlank()) return McpToolResult.error("not a run id: ${json(args["runId"])}")
        return when (val r = read(id, caller.caller.runId, caller.caller.repo, caller.caller.issue)) {
            is Read.Log -> McpToolResult(r.text)
            is Read.Refused -> McpToolResult.error(r.why)
        }
    }

    /** The run [runId]'s log tail for the Smith run [callerRun] on [repo]#[issue], or why not. */
    fun read(runId: String, callerRun: String, repo: String?, issue: Long?): Read {
        val run = engine.smithRun(runId) ?: return Read.Refused(404, "no Smith run $runId")
        val ticket = run.issue ?: return Read.Refused(403, "Smith run $runId works no ticket, so no Smith may read its log")
        val own = repo != null && run.repo.equals(repo, ignoreCase = true) && ticket == issue
        val stuck = !own && engine.doctorStuckTickets(callerRun).any { (r, n) -> r.equals(run.repo, ignoreCase = true) && n == ticket }
        if (!own && !stuck) return Read.Refused(403,
            "Smith run $runId is on ${run.repo}#$ticket: a Smith reads only runs on its own ticket, or on the stuck ticket the doctor-filed ask it refines names")
        val doc = engine.transcript(ticket, run.run)
            ?: return Read.Refused(503, "the transcript store is not configured on this engine (engine.telemetry.logs-url is empty)")
        val sandboxTail = run.logTail?.takeIf { it.isNotBlank() && !run.run.running }
        val sandbox = { tail: String, why: String -> Read.Log(
            "The log of Smith run $runId (${run.run.phase}) on ${run.repo}#$ticket: $why, so this is the sandbox's own " +
                "output (its stdout/stderr tail, logTail), not a transcript:\n\n$tail") }
        if (doc.session == null) {
            doc.error?.let { return Read.Refused(502, "could not read the log of run $runId: $it") }
            sandboxTail?.let { return sandbox(it, "no transcript was shipped") }
            return Read.Refused(404, "no log of run $runId was shipped${if (doc.live) " yet, though it is running now" else ""}")
        }
        if (doc.events.isEmpty()) run.logTail?.takeIf { it.isNotBlank() }?.let { return sandbox(it, "its transcript shipped no events") }
        val text = doc.events.flatMap { it.lines }.joinToString("\n")
        val tail = if (text.length > LOG_CHARS) text.takeLast(LOG_CHARS) else text
        val head = "The log of Smith run $runId (${run.run.phase}) on ${run.repo}#$ticket" +
            (if (text.length > LOG_CHARS) ", its last $LOG_CHARS of ${text.length} characters" else "") + ":\n\n"
        return Read.Log(head + tail)
    }
}

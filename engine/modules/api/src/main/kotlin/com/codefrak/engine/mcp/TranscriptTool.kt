package com.codefrak.engine.mcp

/**
 * read_transcript (#352), on [FrakworksMcp] for First mate only: the newest Smith session for a ticket, live or
 * ended, from the Smith's Claude Code transcript (the same source as the desktop's transcript pane); `run` reads
 * one of read_ticket_runs' rows instead of the newest (#934's blank-line rendering, #1162's `run`).
 */
class TranscriptTool(private val engine: FrakworksEngine) {
    companion object {
        const val NAME = "read_transcript"

        /**
         * Most characters of rendered events read_transcript returns: the result lands in First mate's context on
         * every call, and about 6k tokens of the newest events answers "what is it doing now?".
         */
        const val TRANSCRIPT_CHAR_BUDGET = 24000

        val TOOL = McpTool(NAME,
            "Read-only: the newest Smith session for a ticket, live or ended, from the Smith's Claude Code transcript " +
                "(the same source as the desktop's transcript pane). Use it when the user asks what a ticket's Smith is " +
                "doing or did. Returns the run's state, host, model and session, then its newest events in order. " +
                "Pass `run` (a run id from read_ticket_runs) to read that run's session instead of the newest.",
            obj(
                "ticket" to prop("integer", "The ticket (issue) number, e.g. 350.", "minimum" to 1),
                "run" to prop("string", "A Smith run id of the ticket's, as read_ticket_runs lists it: read that run's session."),
                required = listOf("ticket"),
            ))

        /** The newest events that fit [budget], oldest first, and how many earlier ones were left out; the newest is always kept, cut to the budget if it alone is over. */
        fun boundEvents(events: List<TranscriptEvent>, budget: Int = TRANSCRIPT_CHAR_BUDGET): Pair<List<String>, Int> {
            val kept = ArrayDeque<String>()
            var used = 0
            for (e in events.asReversed()) {
                // A blank line between entries (#934), as the desktop's pane draws them; it counts against the budget.
                val text = e.lines.joinToString("\n\n")
                if (kept.isNotEmpty() && used + text.length + 2 > budget) break
                kept.addFirst(if (text.length > budget) "${text.take(budget)} …" else text)
                used += text.length + 2
            }
            return kept.toList() to events.size - kept.size
        }

        /** Plain text, not JSON: First mate reads it, it does not parse it. */
        fun renderTranscript(doc: Transcript, budget: Int = TRANSCRIPT_CHAR_BUDGET): String {
            val state = when {
                doc.live -> "live (a Smith is running now)"
                doc.liveKnown -> "ended (no Smith is running now)"
                else -> "unknown (the live-run lookup failed)"
            }
            val head = mutableListOf("Ticket #${doc.ticket}: run $state.")
            val facts = listOf("host" to doc.host, "model" to doc.model, "kind" to doc.kind, "session" to doc.session)
                .filter { !it.second.isNullOrEmpty() }.map { "${it.first} ${it.second}" }
            if (facts.isNotEmpty()) head += facts.joinToString(" · ")
            doc.error?.let { head += "Note: $it" }
            val (texts, omitted) = boundEvents(doc.events, budget)
            val n = doc.events.size
            // The reader itself returns at most the newest TranscriptReader.LIMIT events.
            val capped = if (n >= TranscriptReader.LIMIT) "; the read stops at the newest ${TranscriptReader.LIMIT}, so older ones may exist too" else ""
            head += if (omitted > 0) "Showing the newest ${texts.size} of $n events: $omitted earlier events left out$capped." else "$n events$capped."
            return "${head.joinToString("\n")}\n\n${texts.joinToString("\n\n")}"
        }
    }

    /**
     * With [cursors] (a live parent's, #2571), `cursor` is taken and the answer ends with the cursor to pass next, so the
     * parent reads only the events after its last look ([afterCursor]).
     */
    fun call(args: Map<String, Any?>, cursors: Boolean = false): McpToolResult {
        refuseOthers(NAME, args, if (cursors) listOf("ticket", "run", "cursor") else listOf("ticket", "run"))?.let { return it }
        val cursor = args["cursor"]
        if (cursor != null && (cursor !is String || cursor.isBlank())) return McpToolResult.error("not a cursor: ${json(cursor)} (a string an earlier read_transcript answered)")
        val n = FrakworksMcp.ticketOf(args["ticket"]) ?: return noTicket(args)
        val runId = args["run"]
        if (runId != null && (runId !is String || runId.isBlank())) return McpToolResult.error("not a run id: ${json(runId)}")
        val named = (runId as String?)?.let { id ->
            engine.smithRun(id)?.takeIf { it.issue == n }
                ?: return McpToolResult("Run $id is not one of #$n's Smith runs (read_ticket_runs lists them).")
        }
        val doc = (if (named == null) engine.transcript(n) else engine.transcript(n, named.run))
            ?: return McpToolResult("Could not read the Smith transcript for #$n: the transcript store is not configured on this engine (engine.telemetry.logs-url is empty).")
        if (doc.session == null) {
            doc.error?.let { return McpToolResult("Could not read the Smith transcript for #$n: $it") }
            if (named != null) return McpToolResult("No transcript of run ${named.runId} on #$n was shipped${if (doc.live) " yet, though it is running now" else ""}.")
            return McpToolResult("No Smith transcript found for #$n${if (doc.live) " yet, though a Smith is running on it now" else ""}.")
        }
        if (!cursors) return McpToolResult(renderTranscript(doc))
        val (events, note) = afterCursor(doc, cursor as String?)
        val next = cursorOf(doc)
        if (cursor != null && events.isEmpty()) return McpToolResult("Nothing new on #$n since the cursor${note?.let { ": $it" } ?: ""}.\nCursor: $next")
        val head = note?.let { "Note: $it\n" } ?: ""
        return McpToolResult("$head${renderTranscript(doc.copy(events = events))}\n\nCursor: $next")
    }
}

/** A read_transcript cursor: the session, the newest event's time, and how many events at that time were read. */
internal fun cursorOf(doc: Transcript): String {
    val last = doc.events.lastOrNull()?.time
    val same = if (last == null) doc.events.size else doc.events.takeLastWhile { it.time == last }.size
    return "${doc.session}|${last ?: ""}|$same"
}

/**
 * [doc]'s events after [cursor] ([cursorOf]): every event when there is none, or when it names another session (with a
 * note that it does); else those newer than its time, and those at its time past the count it read.
 */
internal fun afterCursor(doc: Transcript, cursor: String?): Pair<List<TranscriptEvent>, String?> {
    if (cursor == null) return doc.events to null
    val parts = cursor.split("|")
    if (parts.size != 3 || parts[2].toIntOrNull() == null) return doc.events to "the cursor was not one read_transcript answered; all events follow"
    val (session, time, seen) = Triple(parts[0], parts[1], parts[2].toInt())
    if (session != doc.session) return doc.events to "a new session since the cursor's; all its events follow"
    if (time.isEmpty()) return doc.events.drop(seen) to null
    var atTime = 0
    return doc.events.filter { e ->
        val t = e.time ?: return@filter true
        when {
            t > time -> true
            t < time -> false
            else -> ++atTime > seen
        }
    } to null
}

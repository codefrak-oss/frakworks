package com.codefrak.engine.mcp

import com.codefrak.engine.engineJson
import com.codefrak.engine.feature.ChildDraft
import com.codefrak.engine.feature.ReplanResult
import org.springframework.web.server.ResponseStatusException
import tools.jackson.module.kotlin.readValue

/**
 * A `parent`-mode Smith's tools on its own split's children (#2528, child-work mode `live`): steer_child delivers a
 * steer as the replan's does ([FrakworksEngine.steerChild]), and read_ticket_runs, read_transcript and read_ticket read
 * a child as First mate's [TicketRunsTool], [TranscriptTool] and [TicketTool] do, in the run's own repo. Listed to every
 * Smith; each call first finds the caller's split ([FrakworksEngine.parentSplit]), and refuses a non-parent run, or a
 * ticket not filed in its split, naming the run. await_children (#2571) blocks on [ChildWatch] until a child changes,
 * in place of the parent's old `sleep 300`; [EngineMcpController] streams it ([STREAMED]), and each answer carries the
 * run's `deadline` and `remainingSeconds` (#2596). note_for_next_parent (#2596) writes a note into [ParentNoteLog] at once,
 * for the next wave's parent brief. file_children (#100042) applies the parent's replan to its split and files the
 * ready drafts through the flow's own filing ([FrakworksEngine.fileChildren]), so one parent session spans the whole
 * split: the next await_children reads the split again and watches the new children too. answer_child (#100049) answers
 * a child's question that `post the smith's question` routed to the running parent ([FrakworksEngine.answerChild]), or
 * escalates it to the user ([FrakworksEngine.escalateChild]), so the user sees today's HITLP row.
 *
 * A work-item split's children (engine/docs/work-items.md, "Live parent on work items") are addressed by their work item
 * id, a string `item` in place of the integer `ticket` (and of `child` for steer_child), refused unless it is one of
 * [ParentSplit.items]: read_ticket answers the store's item through [UntrustedFence][com.codefrak.engine.workitems.UntrustedFence],
 * steer_child writes a `conversation` entry rather than a comment, and await_children's changes name the item.
 */
class ParentTools(private val engine: FrakworksEngine, private val watch: ChildWatch? = null,
                  private val notes: ParentNoteLog? = null, private val clock: () -> java.time.Instant = java.time.Instant::now,
                  workItems: com.codefrak.engine.workitems.WorkItemStore? = null) {
    companion object {
        /** A parent Smith run's mode. */
        const val PARENT_MODE = "parent"
        const val STEER_CHILD = "steer_child"
        const val AWAIT_CHILDREN = "await_children"
        const val NOTE_FOR_NEXT_PARENT = "note_for_next_parent"
        const val FILE_CHILDREN = "file_children"
        const val ANSWER_CHILD = "answer_child"
        /** The longest note note_for_next_parent keeps. */
        const val NOTE_MAX = 8000
        /** The tools [EngineMcpController] answers as an SSE stream with progress: they block for minutes. */
        val STREAMED = setOf(AWAIT_CHILDREN)

        private val CHILD = "ticket" to prop("integer", "A child ticket (issue) number of your split, e.g. 976.", "minimum" to 1)
        private val ITEM = "item" to prop("string", "A child work item id of your split (a work-item split's children have no number); instead of the number.")

        val STEER_TOOL = McpTool(STEER_CHILD,
            "Parent runs only: steer one of your split's children. The message is appended to the child flow's steer, " +
                "which every later brief of that child carries (its current run goes on untouched), and posted on the " +
                "child as `Steer from parent #<n>: <message>`. Refused for a ticket not in your split.",
            obj(
                "child" to prop("integer", "The child ticket (issue) number, e.g. 976.", "minimum" to 1),
                ITEM,
                "message" to prop("string", "The steer: what the child's Smith should do differently, and why."),
                required = listOf("child", "message"),
            ))
        val READ_TICKET_RUNS_TOOL = McpTool(TicketRunsTool.READ_TICKET_RUNS,
            "Parent runs only, read-only: one of your split's children's Smith runs, oldest first (runId, mode, round, " +
                "model, started, ended, outcome, cost, a review's findings). Pass a row's runId to read_transcript's `run`.",
            obj(CHILD, ITEM, required = listOf("ticket")))
        val READ_TRANSCRIPT_TOOL = McpTool(TranscriptTool.NAME,
            "Parent runs only, read-only: the newest Smith session on one of your split's children, live or ended, its " +
                "newest events in order; `run` (a runId from read_ticket_runs) reads that run's session instead.",
            obj(CHILD, ITEM, "run" to prop("string", "A Smith run id of the child's, as read_ticket_runs lists it."),
                "cursor" to prop("string", "The cursor the previous read_transcript of this child answered: only the events after it."),
                required = listOf("ticket")))
        val AWAIT_TOOL = McpTool(AWAIT_CHILDREN,
            "Parent runs only: wait until one of your split's children has a significant change (a run ends with its outcome: " +
                "work done or a question, review or security findings; a push; a question or incident; a merge or close), " +
                "then about 30 s more to gather what follows, and answer {changed, changes, cursor}: every change since the " +
                "cursor, run starts and steps included, which never wake you on their own; {changed: false} after the " +
                "engine's cap (15 minutes by default). Pass the previous answer's `cursor` to get only what is new since then. Loop on it " +
                "instead of sleeping. Every answer also carries `deadline` (when your run is cut, ISO-8601) and " +
                "`remainingSeconds`: write your result before it.",
            obj("cursor" to prop("string", "The cursor the previous await_children answered; omit on the first call.")))
        val NOTE_TOOL = McpTool(NOTE_FOR_NEXT_PARENT,
            "Parent runs only: leave a note for the next wave's parent session of your split. The engine stores it at once, " +
                "so it survives your run ending without a result; every note of the split's parent sessions reaches the next " +
                "parent's brief (`parentNotes`), oldest first. Note what the next wave should know as soon as you learn it.",
            obj("text" to prop("string", "The note, at most $NOTE_MAX characters."), required = listOf("text")))
        val FILE_CHILDREN_TOOL = McpTool(FILE_CHILDREN,
            "Parent runs only: replan your split and file its next children, mid-run, as the flow's `file the next child` " +
                "does (the same ticket body with its `Part N of M of #<parent>.` line, the parent blocked by each, each " +
                "authorized and started via the parent, `Filed child #n` commented on the parent). `outcome` is a replan's: " +
                "`continue` keeps the drafts left as they are, `revise` replaces them with `children` (the replan's shape: " +
                "title, body, kind, dependsOn over the whole split with filed children first, points), `done` drops them all; " +
                "`drop`, with `continue`, drops the named drafts first. The engine then records every child its own close " +
                "has closed and files every draft whose dependencies are done; the rest wait for the next call. Answers " +
                "{filed: [{number, title, part}], draftsLeft, dropped, open} and refuses, changing nothing, a replan that " +
                "breaks a replan's rules. Your next await_children watches the new children too. Call it after a close " +
                "readies a draft, or whenever the plan changes; end your run with `done` only once every draft is filed " +
                "and every child closed.",
            obj(
                "outcome" to prop("string", "continue, revise or done.", "enum" to ReplanResult.OUTCOMES.toList()),
                "children" to prop("array", "For revise: the drafts left, rewritten; each {title, body, kind, dependsOn, points}, a replan's child shape.",
                    "items" to mapOf("type" to "object")),
                "drop" to prop("array", "For continue: titles of drafts left to drop.", "items" to mapOf("type" to "string")),
                "summary" to prop("string", "Why, in a paragraph: commented on the parent with the replan."),
                required = listOf("outcome"),
            ))
        val ANSWER_CHILD_TOOL = McpTool(ANSWER_CHILD,
            "Parent runs only: answer a question one of your split's children asked you (an await_children `question` " +
                "change with its text; the child's run ended with `ask` and its flow waits on you, not on the user). " +
                "{child, answer} lands your answer as the child's next brief's `answers` entry (`by` `#<parent>`), " +
                "comments `Parent #<n> answered:` on the child and its flow goes on; {child, escalate: true, note?} " +
                "hands the question to the user instead, filing the HITLP request and the `/needs` row the child would " +
                "have had without you, with your note on the child's comment. Refused for a child with no question " +
                "waiting on you, and for a ticket not in your split.",
            obj(
                "child" to prop("integer", "The child ticket (issue) number, e.g. 976.", "minimum" to 1),
                "answer" to prop("string", "Your answer, as the user would write it (an option's value answers a choice block)."),
                "escalate" to prop("boolean", "True to hand the question to the user instead of answering it."),
                "note" to prop("string", "With escalate: why you hand it over, for the child's comment."),
                required = listOf("child"),
            ))
        val READ_TICKET_TOOL = McpTool(TicketTool.NAME,
            "Parent runs only, read-only: one of your split's children's title, state, labels and body, the body in pages " +
                "of ${FrakworksMcp.PR_CHAR_BUDGET} characters (a cut page names the `offset` for the next).",
            obj(CHILD, ITEM, "offset" to prop("integer", "The character position of the body the page starts at (default 0).", "minimum" to 0),
                required = listOf("ticket")))

        val TOOLS = listOf(STEER_TOOL, AWAIT_TOOL, FILE_CHILDREN_TOOL, ANSWER_CHILD_TOOL, NOTE_TOOL, READ_TICKET_RUNS_TOOL, READ_TRANSCRIPT_TOOL, READ_TICKET_TOOL)
        val NAMES = TOOLS.map { it.name }.toSet()
    }

    private val runsTool = TicketRunsTool(engine)
    private val transcriptTool = TranscriptTool(engine)
    private val itemTool = TicketTool(engine, workItems)

    fun call(tool: String, args: Map<String, Any?>, caller: McpCaller.Smith): McpToolResult {
        val runId = caller.caller.runId
        val split = engine.parentSplit(runId)
            ?: return McpToolResult.error("run $runId is not a parent run: $tool reads and steers a live parent's own split's children only")
        if (tool == AWAIT_CHILDREN) return await(split, args)
        if (tool == NOTE_FOR_NEXT_PARENT) return note(split, args)
        if (tool == FILE_CHILDREN) return fileChildren(split, args)
        val key = if (tool == STEER_CHILD || tool == ANSWER_CHILD) "child" else "ticket"
        if (tool == ANSWER_CHILD && args.containsKey("item")) return McpToolResult.error("$ANSWER_CHILD answers a split's numbered children; a work-item child's question is the user's")
        if (args.containsKey("item")) return callItem(tool, split, key, args)
        val n = FrakworksMcp.ticketOf(args[key])
            ?: return McpToolResult.error("not a ticket number: ${json(args[key])} (\"$key\", an integer above 0)")
        if (n !in split.children) return McpToolResult.error("#$n is not a child of run $runId's split " +
            "(${split.children.keys.joinToString(", ") { "#$it" }.ifEmpty { "none filed yet" }})")
        return when (tool) {
            STEER_CHILD -> steer(split, n, args)
            ANSWER_CHILD -> answerChild(split, n, args)
            TicketRunsTool.READ_TICKET_RUNS -> refuseOthers(tool, args, listOf("ticket"))
                ?: runsTool.call(tool, mapOf("ticket" to n, "repo" to split.repo))
            TranscriptTool.NAME -> transcriptTool.call(args, cursors = true)
            TicketTool.NAME -> refuseOthers(tool, args, listOf("ticket", "offset"))
                ?: itemTool.call(args + ("repo" to split.repo))
            else -> McpToolResult.error("unknown tool: ${json(tool)}")
        }
    }

    /** A call naming a child work item ([ParentSplit.items]) by `item` instead of a number. */
    private fun callItem(tool: String, split: ParentSplit, key: String, args: Map<String, Any?>): McpToolResult {
        val runId = split.runId
        if (args[key] != null) return McpToolResult.error("pass \"$key\" or \"item\", not both")
        val item = (args["item"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return McpToolResult.error("not a work item id: ${json(args["item"])} (\"item\", a non-empty string)")
        if (item !in split.items) return McpToolResult.error("$item is not a child of run $runId's split " +
            "(${split.items.keys.joinToString(", ").ifEmpty { "no work item filed yet" }})")
        val rest = args - key
        return when (tool) {
            STEER_CHILD -> steerItem(split, item, rest)
            TicketRunsTool.READ_TICKET_RUNS -> refuseOthers(tool, rest, listOf("item")) ?: engine.itemRuns(split.repo, item)
                ?.let { runs -> McpToolResult.of(linkedMapOf("repo" to split.repo, "item" to item, "runs" to runs.map(runsTool::runRow))) }
                ?: McpToolResult("No flow is tied to work item $item: the engine has run nothing on it.")
            TranscriptTool.NAME -> transcriptItem(split, item, rest)
            TicketTool.NAME -> refuseOthers(tool, rest, listOf("item", "offset")) ?: run {
                val offset = if (rest["offset"] == null) 0 else FrakworksMcp.offsetOf(rest["offset"])
                    ?: return McpToolResult.error("not an offset: ${json(rest["offset"])} (an integer, 0 or more)")
                itemTool.readItem(item, offset)
            }
            else -> McpToolResult.error("unknown tool: ${json(tool)}")
        }
    }

    private fun transcriptItem(split: ParentSplit, item: String, args: Map<String, Any?>): McpToolResult {
        refuseOthers(TranscriptTool.NAME, args, listOf("item", "run", "cursor"))?.let { return it }
        val run = args["run"]
        if (run != null && (run !is String || run.isBlank())) return McpToolResult.error("not a run id: ${json(run)}")
        val cursor = args["cursor"]
        if (cursor != null && (cursor !is String || cursor.isBlank())) return McpToolResult.error("not a cursor: ${json(cursor)} (a string an earlier read_transcript answered)")
        val doc = engine.itemTranscript(split.repo, item, run as String?)?.takeIf { it.session != null }
            ?: return McpToolResult("No Smith transcript found for work item $item.")
        val (events, note) = afterCursor(doc, cursor as String?)
        val head = note?.let { "Note: $it\n" } ?: ""
        return McpToolResult("$head${TranscriptTool.renderTranscript(doc.copy(events = events))}\n\nCursor: ${cursorOf(doc)}")
    }

    private fun steerItem(split: ParentSplit, item: String, args: Map<String, Any?>): McpToolResult {
        refuseOthers(STEER_CHILD, args, listOf("item", "message"))?.let { return it }
        val message = (args["message"] as? String)?.takeIf { it.isNotBlank() }
            ?: return McpToolResult.error("\"message\" is required: the steer to deliver, a non-empty string")
        return try {
            if (engine.steerItem(split, item, message)) McpToolResult("Steered $item: appended to its flow's steer and written as a conversation entry on it.")
            else McpToolResult("Written as a conversation entry on $item; no flow of it runs to take the steer.")
        } catch (e: Exception) {
            McpToolResult.error("could not steer $item: ${(e as? ResponseStatusException)?.reason ?: e.message}")
        }
    }

    /** await_children: the split's children's changes after `cursor`, waiting up to the cap for one. */
    private fun await(split: ParentSplit, args: Map<String, Any?>): McpToolResult {
        refuseOthers(AWAIT_CHILDREN, args, listOf("cursor"))?.let { return it }
        val cursor = args["cursor"]
        if (cursor != null && (cursor !is String || cursor.isBlank())) return McpToolResult.error("not a cursor: ${json(cursor)} (a string an earlier await_children answered)")
        val w = watch ?: return McpToolResult.error("$AWAIT_CHILDREN is not available on this engine: it has no event watch")
        val got = w.await(split.repo, split.children.keys, split.items.keys, cursor as String?)
        return McpToolResult.of(buildMap {
            put("changed", got.changes.isNotEmpty())
            put("changes", got.changes.map { c -> mapOf("child" to (c.item ?: c.number), "event" to c.event) + c.data })
            put("cursor", got.cursor)
            if (got.changes.isEmpty()) put("waited", "${w.cap().seconds} s")
            if (got.cursorReset) put("cursorReset", "the cursor was another engine process's: changes are from now on")
            if (got.truncated) put("truncated", "older changes after the cursor were dropped from the engine's journal; read the children")
            split.deadline?.let {
                put("deadline", it.toString())
                put("remainingSeconds", java.time.Duration.between(clock(), it).seconds.coerceAtLeast(0))
            }
        })
    }

    /** file_children: [split]'s replan applied and its ready drafts filed, through the flow's own filing. */
    private fun fileChildren(split: ParentSplit, args: Map<String, Any?>): McpToolResult {
        refuseOthers(FILE_CHILDREN, args, listOf("outcome", "children", "drop", "summary"))?.let { return it }
        if (split.parentItem != null) return McpToolResult.error("$FILE_CHILDREN files a split's numbered children; a work-item split's drafts are the flow's to file")
        val outcome = args["outcome"] as? String
        if (outcome == null || outcome !in ReplanResult.OUTCOMES) return McpToolResult.error("not an outcome: ${json(args["outcome"])} (\"outcome\", one of ${ReplanResult.OUTCOMES})")
        val rawChildren = args["children"]
        if (rawChildren != null && rawChildren !is List<*>) return McpToolResult.error("not a list of children: ${json(rawChildren)} (\"children\", an array of {title, body, kind, dependsOn, points})")
        val children = try { (rawChildren as? List<*>)?.let { engineJson.readValue<List<ChildDraft>>(engineJson.writeValueAsBytes(it)) }.orEmpty() } catch (e: Exception) {
            return McpToolResult.error("\"children\" is not a list of drafts {title, body, kind, dependsOn, points}: ${e.message?.lineSequence()?.firstOrNull()}")
        }
        val rawDrop = args["drop"]
        if (rawDrop != null && (rawDrop !is List<*> || rawDrop.any { it !is String || it.isBlank() })) return McpToolResult.error("not a list of draft titles: ${json(rawDrop)} (\"drop\", an array of non-empty strings)")
        @Suppress("UNCHECKED_CAST")
        val drop = (rawDrop as? List<String>).orEmpty()
        val summary = args["summary"]
        if (summary != null && summary !is String) return McpToolResult.error("not a summary: ${json(summary)} (\"summary\", a string)")
        return try {
            val filed = engine.fileChildren(split, outcome, children, drop, summary as String?)
            McpToolResult.of(buildMap {
                put("filed", filed.filed.map { mapOf("number" to it.number, "title" to it.title, "part" to it.index + 1) })
                put("draftsLeft", filed.state.drafts.size)
                put("dropped", filed.state.dropped)
                put("open", filed.state.open().map { it.number })
                filed.stopped?.let { put("stopped", it) }
                if (filed.filed.isEmpty() && filed.state.drafts.isNotEmpty()) put("note", "no draft is ready: each draft left waits on a child not yet closed by the engine")
            })
        } catch (e: IllegalArgumentException) {
            McpToolResult.error("the replan breaks the rules, nothing changed: ${e.message}")
        } catch (e: Exception) {
            McpToolResult.error("could not file ${split.repo}#${split.parent}'s children: ${(e as? ResponseStatusException)?.reason ?: e.message}")
        }
    }

    /** note_for_next_parent: one note of [split]'s, stored now. */
    private fun note(split: ParentSplit, args: Map<String, Any?>): McpToolResult {
        refuseOthers(NOTE_FOR_NEXT_PARENT, args, listOf("text"))?.let { return it }
        val text = (args["text"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return McpToolResult.error("\"text\" is required: the note, a non-empty string")
        if (text.length > NOTE_MAX) return McpToolResult.error("the note is ${text.length} characters; at most $NOTE_MAX")
        val log = notes ?: return McpToolResult.error("$NOTE_FOR_NEXT_PARENT is not available on this engine: it has no note store")
        return try {
            log.add(split, text)
            McpToolResult("Noted for the next parent of ${split.repo}#${split.parent}.")
        } catch (e: Exception) {
            McpToolResult.error("could not store the note: ${e.message}")
        }
    }

    /** answer_child: [child]'s question waiting on the parent answered with `answer`, or escalated with `escalate` (and `note`). */
    private fun answerChild(split: ParentSplit, child: Long, args: Map<String, Any?>): McpToolResult {
        refuseOthers(ANSWER_CHILD, args, listOf("child", "answer", "escalate", "note"))?.let { return it }
        val escalate = args["escalate"]
        if (escalate != null && escalate !is Boolean) return McpToolResult.error("not a boolean: ${json(escalate)} (\"escalate\", true to hand the question to the user)")
        val answer = args["answer"]
        if (answer != null && (answer !is String || answer.isBlank())) return McpToolResult.error("not an answer: ${json(answer)} (\"answer\", a non-empty string)")
        val note = args["note"]
        if (note != null && note !is String) return McpToolResult.error("not a note: ${json(note)} (\"note\", a string)")
        return try {
            if (escalate == true) {
                if (answer != null) return McpToolResult.error("pass \"answer\" or \"escalate\": true, not both")
                val question = engine.escalateChild(split, child, note as String?)
                    ?: return McpToolResult.error("no question of #$child waits on parent #${split.parent}: nothing to hand over")
                McpToolResult("Handed #$child's question to the user: the HITLP request is filed and the child waits for the answer.\n\n${question.trimEnd()}")
            } else {
                if (note != null) return McpToolResult.error("\"note\" goes with \"escalate\": true; to answer, pass \"answer\"")
                if (answer == null) return McpToolResult.error("\"answer\" is required: your answer, a non-empty string (or \"escalate\": true to hand the question to the user)")
                val question = engine.answerChild(split, child, answer as String)
                    ?: return McpToolResult.error("no question of #$child waits on parent #${split.parent}: nothing to answer")
                McpToolResult("Answered #$child's question as parent #${split.parent}: posted on the child, and its flow goes on with your answer in its next brief.\n\n${question.trimEnd()}")
            }
        } catch (e: Exception) {
            McpToolResult.error("could not answer ${split.repo}#$child: ${(e as? ResponseStatusException)?.reason ?: e.message}")
        }
    }

    private fun steer(split: ParentSplit, child: Long, args: Map<String, Any?>): McpToolResult {
        refuseOthers(STEER_CHILD, args, listOf("child", "message"))?.let { return it }
        val message = (args["message"] as? String)?.takeIf { it.isNotBlank() }
            ?: return McpToolResult.error("\"message\" is required: the steer to deliver, a non-empty string")
        return try {
            if (engine.steerChild(split, child, message)) McpToolResult("Steered #$child: appended to its flow's steer and posted on the child.")
            else McpToolResult("Not steerable: #$child's flow has ended (nothing was posted).")
        } catch (e: Exception) {
            McpToolResult.error("could not steer ${split.repo}#$child: ${(e as? ResponseStatusException)?.reason ?: e.message}")
        }
    }
}

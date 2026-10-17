package com.codefrak.engine.mcp

import com.codefrak.engine.engineJson
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.tier.SeededTiers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.OffsetDateTime

/** [FakeEngine] with a parent run's split (run-p parents acme/widgets#20: children #21 open, #22 ended) and the steers made. */
private class ParentEngine(val base: FakeEngine = FakeEngine()) : FrakworksEngine by base {
    val split = ParentSplit("run-p", "acme/widgets", 20, "feature-1", mapOf(21L to "chore", 22L to "feature"),
        java.time.Instant.now().plus(Duration.ofHours(1)))
    val steers = mutableListOf<Triple<Long, String, String>>()
    /** Each file_children call reaching the engine: outcome, children, drop, summary. */
    val filings = mutableListOf<List<Any?>>()
    override fun parentSplit(runId: String) = split.takeIf { runId == it.runId }
    override fun fileChildren(split: ParentSplit, outcome: String, children: List<com.codefrak.engine.feature.ChildDraft>, drop: List<String>, summary: String?): com.codefrak.engine.feature.ChildrenFiled {
        filings += listOf(outcome, children, drop, summary)
        if (outcome == "revise" && children.isEmpty()) throw IllegalArgumentException("outcome 'revise' needs the remaining children, rewritten (or say 'done' to drop them)")
        val filed = com.codefrak.engine.feature.FiledChild(2, "Part three", 23, "https://github.com/acme/widgets/issues/23", "chore", instance = "flow-23")
        val state = com.codefrak.engine.feature.SplitState(filed = listOf(
            com.codefrak.engine.feature.FiledChild(0, "Part one", 21, "", "chore", state = "merged", prNumber = 30),
            com.codefrak.engine.feature.FiledChild(1, "Part two", 22, "", "feature"), filed), dropped = drop)
        return com.codefrak.engine.feature.ChildrenFiled(state, listOf(filed))
    }
    val answers = mutableListOf<Triple<Long, String?, String?>>()
    override fun answerChild(split: ParentSplit, child: Long, answer: String): String? {
        if (child == 22L) return null
        answers += Triple(child, answer, null); return "Which one?"
    }
    override fun escalateChild(split: ParentSplit, child: Long, note: String?): String? {
        if (child == 22L) return null
        answers += Triple(child, null, note); return "Which one?"
    }
    override fun steerChild(split: ParentSplit, child: Long, message: String): Boolean {
        if (child == 22L) return false
        steers += Triple(child, "#${split.parent}", message)
        return true
    }
}

/** [ParentNoteLog] in memory: the notes in the order they were written, keyed by the split's instance. */
private class MemoryNotes : ParentNoteLog {
    val rows = mutableListOf<Triple<String, String, String>>()
    override fun add(split: ParentSplit, note: String) { rows += Triple(split.instance, split.runId, note) }
    override fun notes(instance: String) = rows.filter { it.first == instance }.map { mapOf("run" to it.second, "note" to it.third) }
}

class ParentToolsTest {
    private val notes = MemoryNotes()
    private val engine = ParentEngine()
    private val bus = EngineEvents(engineJson)
    private var cap: Duration = Duration.ofMillis(200)
    private val watch = ChildWatch(bus, { cap }, Duration.ofMillis(50))
    private val mcp = FrakworksMcp(engine, SeededTiers.table(), FakeFrictionLog(), childWatch = watch, parentNotes = notes)
    private val parent = McpCaller.Smith(SmithCaller("run-p", 20, "acme/widgets"))
    private val worker = McpCaller.Smith(SmithCaller("run-w", 21, "acme/widgets"))

    private fun error(result: McpToolResult): String {
        assertEquals(true, result.isError, result.text)
        return mcpJson.readValue(result.text, Map::class.java)["error"] as String
    }

    private fun ok(result: McpToolResult): String {
        assertEquals(false, result.isError, result.text)
        return result.text
    }

    @Test
    fun `every Smith lists the parent tools, and First mate keeps its own read tools without steer_child`() {
        assertTrue(mcp.tools(worker).map { it.name }.containsAll(listOf("steer_child", "read_ticket_runs", "read_transcript", "read_ticket")))
        assertEquals(listOf("child", "message"), mcp.tools(worker).single { it.name == "steer_child" }.inputSchema["required"])
        assertTrue(mcp.tools(McpCaller.Nobody("127.0.0.1", "someone")).none { it.name == "steer_child" })
    }

    @Test
    fun `steer_child steers a child of the parent's split, and says when the child's flow has ended`() {
        assertTrue("Steered #21" in ok(mcp.call("steer_child", mapOf("child" to 21, "message" to "use the new seam"), parent)))
        assertEquals(listOf(Triple(21L, "#20", "use the new seam")), engine.steers)
        assertTrue("Not steerable: #22's flow has ended" in ok(mcp.call("steer_child", mapOf("child" to 22, "message" to "x"), parent)))
    }

    @Test
    fun `answer_child answers or escalates a child's question waiting on the parent, and is refused where none waits or the call is malformed`() {
        assertTrue("Answered #21's question as parent #20" in ok(mcp.call("answer_child", mapOf("child" to 21, "answer" to "The first."), parent)))
        assertTrue("Handed #21's question to the user" in ok(mcp.call("answer_child", mapOf("child" to 21, "escalate" to true, "note" to "only the user knows"), parent)))
        assertEquals(listOf(Triple(21L, "The first.", null), Triple(21L, null, "only the user knows")), engine.answers)
        assertEquals("no question of #22 waits on parent #20: nothing to answer", error(mcp.call("answer_child", mapOf("child" to 22, "answer" to "x"), parent)))
        assertEquals("no question of #22 waits on parent #20: nothing to hand over", error(mcp.call("answer_child", mapOf("child" to 22, "escalate" to true), parent)))
        assertTrue("\"answer\" is required" in error(mcp.call("answer_child", mapOf("child" to 21), parent)))
        assertTrue("not both" in error(mcp.call("answer_child", mapOf("child" to 21, "answer" to "x", "escalate" to true), parent)))
        assertTrue("goes with" in error(mcp.call("answer_child", mapOf("child" to 21, "answer" to "x", "note" to "n"), parent)))
        assertTrue("not a child of run run-p's split" in error(mcp.call("answer_child", mapOf("child" to 23, "answer" to "x"), parent)))
        assertTrue("not a parent run" in error(mcp.call("answer_child", mapOf("child" to 21, "answer" to "x"), worker)))
        assertTrue("numbered children" in error(mcp.call("answer_child", mapOf("item" to "wi-a", "answer" to "x"), parent)))
        assertEquals(2, engine.answers.size, "a refused call changes nothing")
    }

    @Test
    fun `steer_child is refused for a ticket outside the split, a non-parent run and an empty message`() {
        assertEquals("#99 is not a child of run run-p's split (#21, #22)",
            error(mcp.call("steer_child", mapOf("child" to 99, "message" to "x"), parent)))
        assertTrue("run run-w is not a parent run" in error(mcp.call("steer_child", mapOf("child" to 21, "message" to "x"), worker)))
        assertTrue("\"message\" is required" in error(mcp.call("steer_child", mapOf("child" to 21, "message" to " "), parent)))
        assertTrue(engine.steers.isEmpty())
    }

    @Test
    fun `read_ticket_runs reads a child in the run's repo, and is refused off the split or from a non-parent run`() {
        assertEquals("No process instance is tied to acme/widgets#21: the engine has run nothing on it.",
            ok(mcp.call("read_ticket_runs", mapOf("ticket" to 21), parent)))
        assertTrue("#5 is not a child of run run-p's split" in error(mcp.call("read_ticket_runs", mapOf("ticket" to 5), parent)))
        assertTrue("run run-w is not a parent run" in error(mcp.call("read_ticket_runs", mapOf("ticket" to 21), worker)))
        assertTrue("takes only" in error(mcp.call("read_ticket_runs", mapOf("ticket" to 21, "repo" to "acme/other"), parent)))
    }

    @Test
    fun `read_transcript reads a child, and is refused off the split or from a non-parent run`() {
        assertTrue("Could not read the Smith transcript for #22" in ok(mcp.call("read_transcript", mapOf("ticket" to 22), parent)))
        assertTrue("#5 is not a child of run run-p's split" in error(mcp.call("read_transcript", mapOf("ticket" to 5), parent)))
        assertTrue("run run-w is not a parent run" in error(mcp.call("read_transcript", mapOf("ticket" to 21), worker)))
    }

    @Test
    fun `read_ticket reads a child in the run's repo, never GitHub, and is refused off the split or from a non-parent run`() {
        assertTrue("acme/widgets#21 is not a ticket: the engine holds no work item" in error(mcp.call("read_ticket", mapOf("ticket" to 21), parent)))
        assertTrue("#20 is not a child of run run-p's split" in error(mcp.call("read_ticket", mapOf("ticket" to 20), parent)))
        assertTrue("run run-w is not a parent run" in error(mcp.call("read_ticket", mapOf("ticket" to 21), worker)))
    }

    @Suppress("UNCHECKED_CAST")
    private fun awaited(args: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        mcpJson.readValue(ok(mcp.call("await_children", args, parent)), Map::class.java) as Map<String, Any?>

    @Test
    fun `await_children is refused for a non-parent run and for a bad cursor`() {
        assertTrue("run run-w is not a parent run" in error(mcp.call("await_children", emptyMap(), worker)))
        assertTrue("not a cursor" in error(mcp.call("await_children", mapOf("cursor" to 5), parent)))
        assertTrue("takes only" in error(mcp.call("await_children", mapOf("ticket" to 21), parent)))
    }

    @Test
    fun `await_children answers changed false at the cap, and the cursor then returns only what is new`() {
        val first = awaited()
        assertEquals(false, first["changed"])
        assertEquals("0 s", first["waited"])
        bus.publish(EngineEvent.Session("acme/widgets", 21, "run-c", "i-1", "work", "opus", 7, null, "started", null, null))
        bus.publish(EngineEvent.Session("acme/widgets", 99, "run-x", "i-2", "work", "opus", 7, null, "started", null, null))
        bus.publish(EngineEvent.Flow("acme/other", 21, "i-3", "i-3", "feature", "moved", step = "review"))
        bus.publish(EngineEvent.Ticket("acme/widgets", 21, "closed"))
        val second = awaited(mapOf("cursor" to first["cursor"]))
        assertEquals(true, second["changed"])
        val changes = second["changes"] as List<Map<String, Any?>>
        assertEquals(listOf(21L, 21L), changes.map { (it["child"] as Number).toLong() }, "another ticket and another repo are not the split's")
        assertEquals(listOf("session" to "started", "ticket" to null), changes.map { it["event"] to it["state"] },
            "the session frame does not wake, but comes with the close that does")
        assertEquals(false, awaited(mapOf("cursor" to second["cursor"]))["changed"], "the cursor holds nothing new")
    }

    @Test
    fun `await_children wakes on each kind of child change`() {
        val start = awaited()["cursor"]
        bus.publish(EngineEvent.Flow("acme/widgets", 21, "i-1", "i-1", "feature", "moved", step = "design", stage = "design"))
        bus.publish(EngineEvent.Ticket("acme/widgets", 22, "closed"))
        bus.publish(EngineEvent.Ticket("acme/widgets", 21, "created", prNumber = 40))
        bus.publish(EngineEvent.Needs("acme/widgets", "question", "added", 22))
        bus.publish(EngineEvent.Incident("acme/widgets", 21, "inc", "i-1", "i-1", "open", "ci", "failedJob", "check failed"))
        bus.publish(EngineEvent.Push("acme/widgets", "refs/heads/smith/issue-22", "0000", "abcd", false))
        bus.publish(EngineEvent.Created("acme/widgets", 21, "https://github.com/acme/widgets/issues/21"))
        val changes = awaited(mapOf("cursor" to start))["changes"] as List<Map<String, Any?>>
        assertEquals(listOf("flow" to 21, "ticket" to 22, "ticket" to 21, "needs" to 22, "incident" to 21, "push" to 22),
            changes.map { it["event"] to it["child"] })
    }

    @Test
    fun `await_children blocks until a child changes, not until the cap`() {
        cap = Duration.ofSeconds(30)
        val cursor = watch.await("acme/widgets", setOf(21L), null, Duration.ZERO).cursor
        Thread.ofVirtual().start {
            Thread.sleep(200)
            bus.publish(EngineEvent.Push("acme/widgets", "refs/heads/smith/issue-21", "0000", "abcd", false))
        }
        val started = System.nanoTime()
        val got = awaited(mapOf("cursor" to cursor))
        assertEquals(true, got["changed"])
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(10), "woken by the event")
    }

    @Test
    fun `a cursor from another engine process is reset`() {
        val got = awaited(mapOf("cursor" to "elsewhere:3"))
        assertEquals(false, got["changed"])
        assertTrue((got["cursorReset"] as String).startsWith("the cursor was another engine process's"))
    }

    @Test
    fun `file_children hands the replan to the engine and answers the children filed, the drafts left and the open ones`() {
        val draft = mapOf("title" to "Part three", "body" to "## Goal\n\nThree.\n\n## Acceptance criteria\n\n- [ ] Three is in.\n", "kind" to "chore", "dependsOn" to listOf(0), "points" to 3)
        @Suppress("UNCHECKED_CAST")
        val got = mcpJson.readValue(ok(mcp.call("file_children", mapOf("outcome" to "revise", "children" to listOf(draft), "summary" to "One moved the seam."), parent)), Map::class.java) as Map<String, Any?>
        assertEquals(listOf(mapOf("number" to 23, "title" to "Part three", "part" to 3)), got["filed"])
        assertEquals(0, got["draftsLeft"])
        assertEquals(listOf(22, 23), got["open"], "the open children after the filing, the new one included")
        val (outcome, children, drop, summary) = engine.filings.single()
        assertEquals("revise", outcome)
        assertEquals(listOf(com.codefrak.engine.feature.ChildDraft("Part three", draft["body"] as String, "chore", null, listOf(0), 3)), children)
        assertEquals(emptyList<String>(), drop)
        assertEquals("One moved the seam.", summary)

        @Suppress("UNCHECKED_CAST")
        val dropped = mcpJson.readValue(ok(mcp.call("file_children", mapOf("outcome" to "continue", "drop" to listOf("Part four")), parent)), Map::class.java) as Map<String, Any?>
        assertEquals(listOf("Part four"), dropped["dropped"])
        assertEquals(listOf("continue", emptyList<Any>(), listOf("Part four"), null), engine.filings.last())
    }

    @Test
    fun `file_children is refused for a non-parent run, a bad outcome, malformed children and a replan the rules refuse`() {
        assertTrue("run run-w is not a parent run" in error(mcp.call("file_children", mapOf("outcome" to "continue"), worker)))
        assertTrue("not an outcome" in error(mcp.call("file_children", mapOf("outcome" to "ended"), parent)))
        assertTrue("not a list of children" in error(mcp.call("file_children", mapOf("outcome" to "revise", "children" to "x"), parent)))
        assertTrue("not a list of drafts" in error(mcp.call("file_children", mapOf("outcome" to "revise", "children" to listOf(mapOf("title" to "no body"))), parent)))
        assertTrue("not a list of draft titles" in error(mcp.call("file_children", mapOf("outcome" to "continue", "drop" to listOf(" ")), parent)))
        assertTrue("takes only" in error(mcp.call("file_children", mapOf("outcome" to "continue", "steer" to emptyList<Any>()), parent)))
        assertTrue(engine.filings.isEmpty(), engine.filings.toString())
        val refused = error(mcp.call("file_children", mapOf("outcome" to "revise", "children" to emptyList<Any>()), parent))
        assertTrue("breaks the rules, nothing changed: outcome 'revise' needs the remaining children" in refused, refused)
        assertTrue(mcp.tools(worker).any { it.name == "file_children" })
    }

    @Test
    fun `note_for_next_parent stores the note at once for the split, and the next parent reads it`() {
        assertTrue("Noted for the next parent of acme/widgets#20" in ok(mcp.call("note_for_next_parent", mapOf("text" to " #21 needs the new seam "), parent)))
        ok(mcp.call("note_for_next_parent", mapOf("text" to "steer on #22 worked"), parent))
        assertEquals(listOf(mapOf("run" to "run-p", "note" to "#21 needs the new seam"), mapOf("run" to "run-p", "note" to "steer on #22 worked")),
            notes.notes("feature-1"), "oldest first, stored as each call returned")
        assertEquals(emptyList<Map<String, Any?>>(), notes.notes("feature-2"), "a split with none has an empty list")
    }

    @Test
    fun `note_for_next_parent is refused for a non-parent run, an empty or too long note and other arguments`() {
        assertTrue("run run-w is not a parent run" in error(mcp.call("note_for_next_parent", mapOf("text" to "x"), worker)))
        assertTrue("\"text\" is required" in error(mcp.call("note_for_next_parent", mapOf("text" to "  "), parent)))
        assertTrue("at most 8000" in error(mcp.call("note_for_next_parent", mapOf("text" to "x".repeat(8001)), parent)))
        assertTrue("takes only" in error(mcp.call("note_for_next_parent", mapOf("text" to "x", "ticket" to 21), parent)))
        assertTrue(notes.rows.isEmpty())
    }

    @Test
    fun `every await_children answer carries the run's deadline and the seconds left to it`() {
        val got = awaited()
        assertEquals(engine.split.deadline.toString(), got["deadline"])
        val left = (got["remainingSeconds"] as Number).toLong()
        assertTrue(left in 3500..3600, "about an hour left: $left")
    }

    @Test
    fun `a work-item split's child is addressed by item, and one outside the split, or with a number too, is refused`() {
        val items = object : FrakworksEngine by engine.base {
            override fun parentSplit(runId: String) = engine.split.copy(items = mapOf("wi-a" to "chore"), parentItem = "wi-p")
                .takeIf { runId == it.runId }
            override fun steerItem(split: ParentSplit, item: String, message: String) = true.also { engine.steers += Triple(0L, item, message) }
        }
        val itemWatch = ChildWatch(bus, { Duration.ofMillis(200) }, Duration.ofMillis(50))
        val tools = FrakworksMcp(items, SeededTiers.table(), FakeFrictionLog(), childWatch = itemWatch, parentNotes = notes)
        assertEquals("wi-b is not a child of run run-p's split (wi-a)",
            error(tools.call("steer_child", mapOf("item" to "wi-b", "message" to "x"), parent)))
        assertTrue("not both" in error(tools.call("read_ticket", mapOf("ticket" to 21, "item" to "wi-a"), parent)))
        assertTrue("Steered wi-a" in ok(tools.call("steer_child", mapOf("item" to "wi-a", "message" to "use the seam"), parent)))
        assertEquals(listOf(Triple(0L, "wi-a", "use the seam")), engine.steers)
        assertTrue("No flow is tied to work item wi-a" in ok(tools.call("read_ticket_runs", mapOf("item" to "wi-a"), parent)))

        val cursor = itemWatch.await("acme/widgets", emptySet(), setOf("wi-a"), null, Duration.ZERO).cursor
        itemWatch.changed(com.codefrak.engine.workitems.WorkItem("wi-a", "acme/widgets", "d", "chore", null, null, "done",
            com.codefrak.engine.workitems.WorkItemSourceRef("split-child", "wi-p#1"), true, null, OffsetDateTime.now(), OffsetDateTime.now()))
        @Suppress("UNCHECKED_CAST")
        val got = mcpJson.readValue(ok(tools.call("await_children", mapOf("cursor" to cursor), parent)), Map::class.java) as Map<String, Any?>
        assertEquals(listOf(mapOf("child" to "wi-a", "event" to "item", "state" to "done")), got["changes"])
    }
}

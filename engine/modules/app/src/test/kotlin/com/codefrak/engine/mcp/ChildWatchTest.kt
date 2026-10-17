package com.codefrak.engine.mcp

import com.codefrak.engine.engineJson
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/** Which child frames end a live parent's wait, and how they are coalesced (#2591). */
class ChildWatchTest {
    private val bus = EngineEvents(engineJson)
    private val watch = ChildWatch(bus, { Duration.ofSeconds(5) }, Duration.ofMillis(300))
    private val repo = "acme/widgets"
    private val kids = setOf(21L, 22L)

    private fun session(state: String, n: Long = 21) =
        bus.publish(EngineEvent.Session(repo, n, "run-$n", "i-$n", "work", "opus", 5, null, state, null, null))
    private fun flow(stage: String, step: String = "start smith", n: Long = 21, state: String = "step") =
        bus.publish(EngineEvent.Flow(repo, n, "sub-$n", "i-$n", "chore", state, step, stage))
    private fun push(n: Long = 21) = bus.publish(EngineEvent.Push(repo, "refs/heads/smith/issue-$n", "0000", "abcd", false))

    /** A run under [stage]: the session and flow bookkeeping a Smith run makes. */
    private fun run(stage: String, n: Long = 21) {
        session("started", n); flow(stage, "start smith", n); flow(stage, "smith exited", n); session("ended", n)
    }

    private fun now() = watch.await(repo, kids, null, Duration.ZERO).cursor

    @Test
    fun `bookkeeping frames alone do not wake, and the cursor stays for the next wake`() {
        val cursor = now()
        run("call-do-work")
        flow("gateway-work-outcome", "outcome?")
        run("call-do-work")
        val got = watch.await(repo, kids, cursor, Duration.ofMillis(200))
        assertTrue(got.changes.isEmpty(), "${got.changes}")
        assertEquals(cursor, got.cursor, "the bookkeeping frames are still after the cursor")
    }

    @Test
    fun `each significant kind wakes`() {
        val kinds: List<Pair<String, () -> Unit>> = listOf(
            "a run ending, the flow moving to the next stage" to { flow("call-do-work"); flow("call-security-review") },
            "a flow ending (merged, closed)" to { flow("call-do-work", state = "ended") },
            "a flow cancelled" to { flow("call-do-work", state = "cancelled") },
            "a push" to { push() },
            "a question" to { bus.publish(EngineEvent.Needs(repo, "question", "added", 22)) },
            "a question asked of the parent (#100049), with its text" to {
                bus.publish(EngineEvent.Question(repo, 22, "i-22", "asked", "Which one?", askedOf = "#20"))
                val got = watch.await(repo, kids, null, Duration.ZERO)
                assertTrue(got.changes.isEmpty() || got.changes.last().data["text"] == "Which one?")
            },
            "an incident" to { bus.publish(EngineEvent.Incident(repo, 21, "inc", "i-21", "i-21", "open", "ci", "failedJob", "check failed")) },
            "a close" to { bus.publish(EngineEvent.Ticket(repo, 22, "closed")) },
        )
        for ((what, publish) in kinds) {
            val cursor = now()
            publish()
            assertTrue(watch.await(repo, kids, cursor, Duration.ZERO).changes.any { it.wakes }, what)
        }
        val cursor = now()
        bus.publish(EngineEvent.Needs(repo, "question", "removed", 22))
        bus.publish(EngineEvent.Question(repo, 22, "i-22", "answered", "Which one?", askedOf = "#20", by = "#20"))
        bus.publish(EngineEvent.Question(repo, 22, "i-22", "escalated", "Which one?", askedOf = "#20", by = "#20"))
        bus.publish(EngineEvent.Incident(repo, 21, "inc", "i-21", "i-21", "resolved", "ci", "failedJob", null))
        bus.publish(EngineEvent.Ticket(repo, 21, "commented"))
        flow("call-security-review", "smith exited")
        assertTrue(watch.await(repo, kids, cursor, Duration.ZERO).changes.none { it.wakes }, "an answered question, a retry, a comment, a step")
    }

    @Test
    fun `a question asked of the parent is reported with its text and who it was asked of`() {
        val cursor = now()
        bus.publish(EngineEvent.Question(repo, 21, "i-21", "asked", "Which one?\n\n```ask\nkind: choice\n```", askedOf = "#20"))
        val got = watch.await(repo, kids, cursor, Duration.ZERO).changes.single()
        assertEquals("question", got.event)
        assertTrue(got.wakes)
        assertEquals("asked", got.data["state"])
        assertEquals("#20", got.data["askedOf"])
        assertTrue((got.data["text"] as String).startsWith("Which one?"))
    }

    @Test
    fun `frames arriving within the window after the first wake come in the same answer`() {
        val cursor = now()
        Thread.ofVirtual().start {
            push(21)
            Thread.sleep(50); session("started", 22)
            Thread.sleep(50); bus.publish(EngineEvent.Ticket(repo, 22, "closed"))
            Thread.sleep(800); push(22)
        }
        val started = System.nanoTime()
        val got = watch.await(repo, kids, cursor)
        assertEquals(listOf("push" to 21L, "session" to 22L, "ticket" to 22L), got.changes.map { it.event to it.number })
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(3), "answered at the window, not the cap")
        Thread.sleep(1000)
        assertEquals(listOf("push" to 22L), watch.await(repo, kids, got.cursor, Duration.ZERO).changes.map { it.event to it.number })
    }

    @Test
    fun `the cursor returns the non-waking frames with the next wake`() {
        val cursor = now()
        run("call-do-work")
        assertTrue(watch.await(repo, kids, cursor, Duration.ofMillis(100)).changes.isEmpty())
        push()
        val got = watch.await(repo, kids, cursor, Duration.ofSeconds(1))
        assertEquals(listOf("session", "flow", "flow", "session", "push"), got.changes.map { it.event })
        assertEquals(listOf(false, false, false, false, true), got.changes.map { it.wakes })
    }

    @Test
    fun `a work, security, review, work, push, security cycle wakes the parent a handful of times, not once a frame`() {
        var cursor = now()
        var frames = 0
        var answers = 0
        val phases: List<() -> Unit> = listOf(
            { run("call-do-work") },
            { flow("gateway-work-outcome", "outcome?"); run("call-security-review") },
            { flow("gateway-security-passed", "passed?"); flow("task-open-pr", "open/update pr"); run("call-review-pr") },
            { flow("gateway-review-outcome", "approve or changes?"); run("call-do-work") },
            { flow("gateway-work-outcome", "outcome?"); push(); run("call-security-review") },
        )
        for (phase in phases) {
            phase()
            val got = watch.await(repo, kids, cursor, Duration.ofMillis(500))
            cursor = got.cursor
            frames += got.changes.size
            if (got.changes.isNotEmpty()) answers++
        }
        assertTrue(answers in 3..4, "answers: $answers")
        assertEquals(20 + 6, frames, "every frame was answered once, none lost")
    }

    private fun item(id: String, state: String) = com.codefrak.engine.workitems.WorkItem(id, repo, "d", "chore", null, null, state,
        com.codefrak.engine.workitems.WorkItemSourceRef("split-child", "wi-p#1"), true, null,
        java.time.OffsetDateTime.now(), java.time.OffsetDateTime.now())

    @Test
    fun `a child work item's changes are keyed by its id, and its done wakes`() {
        val cursor = watch.await(repo, kids, setOf("wi-a"), null, Duration.ZERO).cursor
        watch.changed(item("wi-a", "running"))
        watch.changed(item("wi-other", "done"))
        watch.take(repo, "event: flow\ndata: {\"item\":\"wi-a\",\"state\":\"step\",\"stage\":\"call-do-work\"}")
        assertTrue(watch.await(repo, kids, setOf("wi-a"), cursor, Duration.ofMillis(200)).changes.isEmpty(), "running is bookkeeping")
        watch.changed(item("wi-a", "done"))
        val got = watch.await(repo, kids, setOf("wi-a"), cursor, Duration.ofSeconds(2))
        assertEquals(listOf("item", "flow", "item"), got.changes.map { it.event })
        assertTrue(got.changes.all { it.item == "wi-a" }, "${got.changes}")
        assertTrue(watch.await(repo, kids, cursor, Duration.ofMillis(200)).changes.isEmpty(), "a numbered await sees no item")
    }
}

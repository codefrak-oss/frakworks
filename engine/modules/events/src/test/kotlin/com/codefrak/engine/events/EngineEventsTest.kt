package com.codefrak.engine.events

import com.codefrak.engine.repos.SetupReport
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The bus alone: the frame grammar and shape, the per-repo filter, and the queue bound. The after-commit deferral is in [FlowEventsTest]. */
class EngineEventsTest {
    private val json = jacksonObjectMapper()
    private val bus = EngineEvents(json)
    private val at = Instant.parse("2026-10-05T10:00:00Z")
    private val none = Duration.ZERO

    @Test
    fun `a frame is event, data as one JSON object of the event's fields, and a blank line`() {
        val sub = bus.subscribe("acme/widgets")
        bus.publish(EngineEvent.Ticket("acme/widgets", 7, "labeled", label = "authorized", at = at))
        val frame = sub.next(none)!!
        assertTrue(frame.startsWith("event: ticket\ndata: "), frame)
        assertTrue(frame.endsWith("\n\n"), frame)
        val data = json.readValue<Map<String, Any?>>(frame.removePrefix("event: ticket\ndata: ").trim())
        assertEquals(mapOf("repo" to "acme/widgets", "number" to 7, "action" to "labeled", "label" to "authorized", "prNumber" to null,
            "issue" to null, "at" to "2026-10-05T10:00:00Z"), data)
        assertNull(sub.next(none), "nothing else")
    }

    @Test
    fun `every type serializes its table's fields and never the event name`() {
        val events = listOf(
            EngineEvent.Intake("r", "i1", "filed", ticket = 9, ticketUrl = "u", kind = "chore", tier = 5, at = at),
            EngineEvent.Flow("r", 1, "inst", "root", "chore", "step", step = "start smith", stage = "call-do-work", at = at),
            EngineEvent.Incident("r", 1, "inc", "inst", "root", "open", "task-x", "failedJob", "boom", at = at),
            EngineEvent.Needs("r", "question", "added", number = 1, actionId = "a", at = at, forLogin = "alice"),
            EngineEvent.Session("r", 1, "run", "inst", "work", "opus", 5, "acct", "started", "f", "h", at = at),
            EngineEvent.Repo("r", "changed", listOf("ciCheck"), at = at),
            EngineEvent.Setting("intake.tier", 6, at = at),
            EngineEvent.Heartbeat(1, 2, 3, at = at),
            EngineEvent.Push("r", "refs/heads/main", "a", "b", default = true, at = at),
            EngineEvent.Setup("r", "done", at, SetupReport("r", hasToken = true, readable = false), at = at),
            EngineEvent.Created("r", 9, "https://github.com/r/issues/9", at = at),
            EngineEvent.Deploy("r", "abc1234", "github-actions", at = at),
        )
        val names = listOf("intake", "flow", "incident", "needs", "session", "repo", "setting", "heartbeat", "push", "setup", "created", "deploy")
        events.zip(names).forEach { (event, name) ->
            assertEquals(name, event.event)
            val data = json.readValue<Map<String, Any?>>(json.writeValueAsString(event))
            assertFalse("event" in data, "$name carries no event name")
            assertEquals("2026-10-05T10:00:00Z", data["at"], "$name: at")
            assertTrue("repo" in data, "$name: repo")
        }
        val incident = json.readValue<Map<String, Any?>>(json.writeValueAsString(events[2]))
        assertEquals("failedJob", incident["type"], "an incident's type is Operaton's, under the table's name")
        val needs = json.readValue<Map<String, Any?>>(json.writeValueAsString(events[3]))
        assertEquals("alice", needs["for"], "a needs frame says whom the row is for, as `for`")
        assertFalse("forLogin" in needs)
        assertEquals(null, json.readValue<Map<String, Any?>>(json.writeValueAsString(EngineEvent.Needs("r", "incident", "added", at = at)))["for"])
        assertEquals("*", json.readValue<Map<String, Any?>>(json.writeValueAsString(events[6]))["repo"], "a setting is fleet-wide")
        val setup = json.readValue<Map<String, Any?>>(json.writeValueAsString(events[9]))
        assertEquals("2026-10-05T10:00:00Z", setup["checkedAt"], "a setup frame carries checkedAt")
        assertEquals(mapOf("repo" to "r", "hasToken" to true, "readable" to false, "push" to null, "missingLabels" to null,
            "defaultBranch" to null, "ciCheck" to null, "webhook" to null), setup["report"], "and the report as GET /repos/{o}/{n}/setup has it")
        assertEquals(mapOf("repo" to "r", "number" to 9, "url" to "https://github.com/r/issues/9", "issue" to null, "at" to "2026-10-05T10:00:00Z"),
            json.readValue<Map<String, Any?>>(json.writeValueAsString(events[10])), "a created frame: the issue, and its row (null here)")
    }

    @Test
    fun `a repo subscription gets its repo's events, ignoring case, and a star subscription gets every event`() {
        val widgets = bus.subscribe("acme/widgets")
        val gadgets = bus.subscribe("acme/gadgets")
        val fleet = bus.subscribe("*")
        bus.publish(EngineEvent.Ticket("Acme/Widgets", 1, "opened", at = at))
        bus.publish(EngineEvent.Setting("intake.tier", 6, at = at))
        assertTrue(widgets.next(none)!!.startsWith("event: ticket"))
        assertNull(widgets.next(none), "a fleet-wide event reaches only * subscriptions")
        assertNull(gadgets.next(none), "another repo's event")
        assertTrue(fleet.next(none)!!.startsWith("event: ticket"))
        assertTrue(fleet.next(none)!!.startsWith("event: setting"))
        assertEquals(3, bus.subscriberCount)
        bus.unsubscribe(gadgets)
        assertEquals(2, bus.subscriberCount)
        assertEquals(EventSubscription.CLOSE, gadgets.next(none), "an unsubscribed one is closed")
    }

    @Test
    fun `the 1001st frame a subscriber has not drained closes it, and the publisher never blocks`() {
        val slow = bus.subscribe("*")
        val fine = bus.subscribe("*")
        repeat(EngineEvents.QUEUE_CAPACITY) { bus.publish(EngineEvent.Heartbeat(it, 0, 0, at = at)) }
        assertFalse(slow.closed, "1000 frames fit")
        assertEquals(2, bus.subscriberCount)
        // The fine subscriber keeps up: it drains before the next frame, as a live client would.
        repeat(EngineEvents.QUEUE_CAPACITY) { assertTrue(fine.next(none)!!.startsWith("event: heartbeat")) }
        bus.publish(EngineEvent.Heartbeat(1001, 0, 0, at = at))
        assertTrue(slow.closed)
        assertTrue(slow.overflowed)
        assertEquals(1, bus.subscriberCount, "the closed one is dropped from the bus")
        // The 1000 queued frames are still drained, then the close.
        repeat(EngineEvents.QUEUE_CAPACITY) { assertTrue(slow.next(none)!!.startsWith("event: heartbeat")) }
        assertEquals(EventSubscription.CLOSE, slow.next(none))
        // The other subscriber took the 1001st frame too, and stays open.
        assertTrue(fine.next(none)!!.startsWith("event: heartbeat"))
        assertNull(fine.next(none))
        assertFalse(fine.closed)
        assertFalse(fine.overflowed)
    }

    @Test
    fun `hello carries the subscription and an instant, and the ping is a comment frame`() {
        val hello = bus.hello("acme/widgets")
        assertTrue(hello.startsWith("event: hello\ndata: "), hello)
        val data = json.readValue<Map<String, Any?>>(hello.removePrefix("event: hello\ndata: ").trim())
        assertEquals("acme/widgets", data["repo"])
        Instant.parse(data["at"] as String)
        assertEquals(": ping\n\n", bus.ping)
    }

    @Test
    fun `with no command context bound, publishAfterCommit publishes at once`() {
        val sub = bus.subscribe("r")
        bus.publishAfterCommit(EngineEvent.Repo("r", "added", at = at))
        assertTrue(sub.next(none)!!.startsWith("event: repo"))
    }
}

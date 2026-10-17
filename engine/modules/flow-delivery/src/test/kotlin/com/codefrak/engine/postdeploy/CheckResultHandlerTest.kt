package com.codefrak.engine.postdeploy

import com.codefrak.engine.intake.TicketRecorder
import com.codefrak.engine.intake.tickets
import com.codefrak.engine.smith.Vars
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.variable.value.TypedValue
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [CheckResultHandler]: the three outcomes, each wake shape, the history and the conversation entry, on a mocked execution. Plain JUnit. */
class CheckResultHandlerTest {
    private val vars = mutableMapOf<String, Any?>(Vars.REPO to "acme/widgets", Vars.ISSUE to 41L, Vars.MODE to "check")
    private val execution = mock(DelegateExecution::class.java).also { e ->
        `when`(e.processInstanceId).thenReturn("run-1")
        `when`(e.getVariable(anyString())).thenAnswer { vars[it.getArgument(0)] }
        doAnswer {
            val v = it.getArgument<Any?>(1)
            vars[it.getArgument(0)] = if (v is TypedValue) v.value else v
            null
        }.`when`(e).setVariable(anyString(), any())
    }
    private val github = TicketRecorder()
    private val now = Instant.parse("2026-10-13T12:00:00Z")
    private val handler = CheckResultHandler(github.tickets(), Clock.fixed(now, ZoneOffset.UTC))
    private val refused = mutableListOf<String>()
    private fun handle(json: String): String = handler.handle(execution, "check", json.toByteArray()) { refused += it }

    @Test
    fun `passed sets the outcome and summary, no wake, one history entry and one conversation entry`() {
        assertEquals("passed", handle("""{"outcome":"passed","summary":"The tile shows."}"""))
        assertEquals(emptyList(), refused)
        assertEquals("passed", vars[Vars.CHECK_OUTCOME])
        assertEquals("The tile shows.", vars[Vars.CHECK_SUMMARY])
        assertNull(vars[Vars.CHECK_WAKE_AT]); assertNull(vars[Vars.CHECK_WAKE_EVENT])
        val history = CheckRun.list(vars[Vars.CHECK_HISTORY] as ByteArray)
        assertEquals(listOf(CheckRun("run-1", now.toString(), "passed", "The tile shows.")), history)
        val (repo, issue, body) = github.comments.single()
        assertEquals("acme/widgets" to 41L, repo to issue)
        assertTrue(body.startsWith("Post-deployment check, run 1: **passed**."), body)
        assertTrue("The tile shows." in body && "run-1" in body, body)
    }

    @Test
    fun `failed needs a bug, and with one is the outcome`() {
        handle("""{"outcome":"failed","summary":"Wrong."}""")
        assertEquals(listOf("outcome 'failed' needs a bug {title, body}"), refused)
        refused.clear()
        assertEquals("failed", handle("""{"outcome":"failed","summary":"Wrong.","bug":{"title":"Tile is blank","body":"It shows nothing."}}"""))
        assertEquals(emptyList(), refused)
        assertEquals("failed", vars[Vars.CHECK_OUTCOME])
        assertTrue("a bug is filed" in github.comments.last().third)
    }

    @Test
    fun `not_yet with an at hint sets checkWakeAt as a Date, appends to the history and says when in the entry`() {
        vars[Vars.CHECK_HISTORY] = CheckRun.bytes(listOf(CheckRun("run-0", "2026-10-13T08:00:00Z", "not_yet", "Not deployed.")))
        assertEquals("not_yet", handle("""{"outcome":"not_yet","summary":"Still rolling out.","wake":{"at":"2026-10-14T09:00:00Z"}}"""))
        assertEquals(emptyList(), refused)
        assertEquals(Date.from(Instant.parse("2026-10-14T09:00:00Z")), vars[Vars.CHECK_WAKE_AT])
        assertNull(vars[Vars.CHECK_WAKE_EVENT])
        val history = CheckRun.list(vars[Vars.CHECK_HISTORY] as ByteArray)
        assertEquals(listOf("run-0", "run-1"), history.map { it.run })
        assertEquals("2026-10-14T09:00:00Z", history.last().wakeAt)
        assertTrue(github.comments.single().third.startsWith("Post-deployment check, run 2: **not_yet** — look again at 2026-10-14T09:00:00Z."))
    }

    @Test
    fun `not_yet with an event hint sets checkWakeEvent and no time`() {
        assertEquals("not_yet", handle("""{"outcome":"not_yet","summary":"Waits for the next question.","wake":{"event":"intake:name=question"}}"""))
        assertEquals(emptyList(), refused)
        assertNull(vars[Vars.CHECK_WAKE_AT])
        assertEquals("intake:name=question", vars[Vars.CHECK_WAKE_EVENT])
        assertEquals("intake:name=question", CheckRun.list(vars[Vars.CHECK_HISTORY] as ByteArray).single().wakeEvent)
        assertTrue("look again on `intake:name=question`" in github.comments.single().third)
    }

    @Test
    fun `not_yet without a hint leaves both wake variables unset, clearing an earlier run's`() {
        vars[Vars.CHECK_WAKE_AT] = Date(); vars[Vars.CHECK_WAKE_EVENT] = "push"
        assertEquals("not_yet", handle("""{"outcome":"not_yet","summary":"Not deployed yet."}"""))
        assertNull(vars[Vars.CHECK_WAKE_AT]); assertNull(vars[Vars.CHECK_WAKE_EVENT])
    }

    @Test
    fun `a wake with both keys, neither, an unparsable at or a malformed event is refused`() {
        for ((wake, why) in listOf(
            """{"at":"2026-10-14T09:00:00Z","event":"push"}""" to "one of the two",
            """{}""" to "one of the two",
            """{"at":"tomorrow"}""" to "not an ISO-8601 instant",
            """{"event":"push default=true"}""" to "not <type>[:<field>=<value>]",
        )) {
            refused.clear()
            handle("""{"outcome":"not_yet","summary":"x","wake":$wake}""")
            assertTrue(refused.any { why in it }, "$wake: $refused")
        }
        refused.clear()
        handle("""{"outcome":"passed","summary":"x","wake":{"at":"2026-10-14T09:00:00Z"}}""")
        assertEquals(listOf("only outcome 'not_yet' takes a wake hint"), refused)
        refused.clear()
        handle("""{"outcome":"passed","summary":"x","bug":{"title":"t","body":"b"}}""")
        assertEquals(listOf("only outcome 'failed' files a bug"), refused)
    }

    @Test
    fun `without a ticket writer the entry is skipped, and a failing one does not fail the run`() {
        assertEquals("passed", CheckResultHandler(null).handle(execution, "check", """{"outcome":"passed","summary":"ok"}""".toByteArray()) {})
        github.onComment = { _, _, _ -> error("GitHub 502") }
        assertEquals("passed", handle("""{"outcome":"passed","summary":"ok"}"""))
        assertEquals(2, CheckRun.list(vars[Vars.CHECK_HISTORY] as ByteArray).size)
    }
}

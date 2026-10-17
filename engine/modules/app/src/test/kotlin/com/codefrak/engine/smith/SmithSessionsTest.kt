package com.codefrak.engine.smith

import com.codefrak.engine.summarize.SummarizeFakes
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A live run's stdout in `engine.smith_session_line`, fed by SmithTelemetry's follow of a fake sandbox. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SummarizeFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class SmithSessionsTest {
    @Autowired lateinit var sandbox: FakeSandbox
    @Autowired lateinit var telemetry: SmithTelemetry
    @Autowired lateinit var sessions: SmithSessions
    @Autowired lateinit var jdbc: JdbcClient

    private fun spec(runId: String, issue: Long) = SmithSpec(runId, "acme/widgets", issue, "claude-opus-5-5", "medium", dryRun = false, mode = "work")

    /** What Docker does when the sandbox exits: it is no longer running, and its followed stream ends. */
    private fun exit(handle: String) {
        sandbox.states[handle] = SandboxStatus(false, 0)
        sandbox.followers.remove(handle)?.second?.invoke(null)
    }

    @Test
    fun `a fake sandbox's lines land in the table while the run is live, and ended_at is set at its exit`() {
        val lines = listOf("smith: run=run-sess-1 repo=acme/widgets issue=#4231 mode=work",
            """{"type":"system","subtype":"init"}""",
            """{"type":"assistant","message":{"content":[{"type":"text","text":"Reading the telemetry code."}]}}""")
        telemetry.started(spec("run-sess-1", 4231), "container-sess-1")
        lines.forEach { sandbox.print("container-sess-1", it) }

        eventually("three stored lines") { sessions.lines("run-sess-1") == lines }
        assertTrue(telemetry.isActive("run-sess-1"), "the lines are stored before the run exits")
        val session = assertNotNull(sessions.session("run-sess-1"))
        assertEquals("acme/widgets", session.repo); assertEquals(4231L, session.issue)
        assertEquals("work", session.mode); assertEquals("claude-opus-5-5", session.model)
        assertNull(session.endedAt)
        assertEquals(listOf(0L, 1L, 2L), jdbc.sql("SELECT seq FROM engine.smith_session_line WHERE run_id = 'run-sess-1' ORDER BY seq")
            .query(Long::class.java).list())

        exit("container-sess-1")
        eventually("ended_at") { sessions.session("run-sess-1")?.endedAt != null }
        eventually("the run to leave the live set") { !telemetry.isActive("run-sess-1") }
    }

    @Test
    fun `a run resumed after a restart numbers its lines on after the stored ones`() {
        telemetry.started(spec("run-sess-2", 4232), "container-sess-2")
        sandbox.print("container-sess-2", "before")
        eventually("the first line") { sessions.lines("run-sess-2") == listOf("before") }
        telemetry.ended("run-sess-2")
        eventually("the first follow to end") { !telemetry.isActive("run-sess-2") && sessions.session("run-sess-2")?.endedAt != null }

        telemetry.resumed(RunLabels.of(spec("run-sess-2", 4232), "engine"), "container-sess-2")
        sandbox.print("container-sess-2", "after")
        eventually("the second line") { sessions.lines("run-sess-2") == listOf("before", "after") }
        assertNull(sessions.session("run-sess-2")!!.endedAt, "reopened")
        telemetry.ended("run-sess-2")
    }

    @Test
    fun `with shipping and metrics off a session store still follows the sandbox`() {
        val t = SmithTelemetry(sandbox, "", metricsEnabled = false, instance = com.codefrak.engine.lease.InstanceId("engine"), timeout = Duration.ofSeconds(1), sessions = sessions)
        t.started(spec("run-sess-3", 4233), "container-sess-3")
        assertTrue(sandbox.followers.containsKey("container-sess-3"))
        sandbox.print("container-sess-3", "only line")
        eventually("the line") { sessions.lines("run-sess-3") == listOf("only line") }
        t.ended("run-sess-3")
        eventually("ended_at") { sessions.session("run-sess-3")?.endedAt != null }
        assertFalse(sandbox.followers.containsKey("container-sess-3"))
    }

    @Test
    fun `offered lines feed the run's live tally, close keeps it, forget drops it, and one missing from memory is rebuilt from the stored lines`() {
        val assistant = { id: String, out: Int ->
            """{"type":"assistant","message":{"id":"$id","usage":{"input_tokens":1,"output_tokens":$out,"cache_read_input_tokens":10,"cache_creation_input_tokens":2}},"parent_tool_use_id":null}"""
        }
        assertNull(sessions.live("run-sess-4"), "no session, no figures")
        telemetry.started(spec("run-sess-4", 4234), "container-sess-4")
        sandbox.print("container-sess-4", """{"type":"system","subtype":"init"}""")
        eventually("the init line") { sessions.lines("run-sess-4").size == 1 }
        assertEquals(LiveFigures(null, null, null), sessions.live("run-sess-4"), "nothing usable yet")

        sandbox.print("container-sess-4", assistant("msg_1", 5))
        sandbox.print("container-sess-4", assistant("msg_2", 7))
        eventually("two turns") { sessions.live("run-sess-4")?.turns == 2L }
        assertEquals(LiveTokens(2, 12, 20, 4), sessions.live("run-sess-4")!!.tokens)

        exit("container-sess-4")
        eventually("ended_at") { sessions.session("run-sess-4")?.endedAt != null }
        assertEquals(2L, sessions.live("run-sess-4")?.turns, "an exited sandbox keeps its figures until collect result")

        val collected = telemetry.collected("run-sess-4")
        assertEquals(2L, collected?.countedTurns, "collect result reads the tally once, for its log")
        assertEquals(LiveFigures(2, LiveTokens(2, 12, 20, 4), 2), sessions.live("run-sess-4"),
            "a tally not in memory (an engine restart) is rebuilt from the stored lines")
        sessions.forget("run-sess-4")
    }

    @Test
    fun `an over-long line is stored cut, with a marker`() {
        val long = "x".repeat(StoredSmithSessions.MAX_LINE + 10)
        assertEquals(StoredSmithSessions.MAX_LINE + " …[cut: 10 more chars]".length, StoredSmithSessions.cut(long).length)
        assertTrue(StoredSmithSessions.cut(long).endsWith("[cut: 10 more chars]"))
        assertEquals("short", StoredSmithSessions.cut("short"))
    }
}

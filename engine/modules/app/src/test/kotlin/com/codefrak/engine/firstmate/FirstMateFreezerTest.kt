package com.codefrak.engine.firstmate

import com.codefrak.engine.ProcessFakes
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [FirstMateFreezer] (engine/README.md, "First-mate sessions"): the 15-minute idle timer, the 200 MB folder cap and the
 * optional expiry of frozen sessions, on the real store with a fixed clock and a fake pod.
 */
@SpringBootTest
@Import(ProcessFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class FirstMateFreezerTest {
    @Autowired lateinit var sessions: FirstMateSessions
    @Autowired lateinit var snapshots: FirstMateSnapshots
    @Autowired lateinit var expiry: FirstMateExpiry
    @Autowired lateinit var jdbc: JdbcClient

    private val now = OffsetDateTime.of(2026, 10, 11, 12, 0, 0, 0, ZoneOffset.UTC)
    private val sessionFile = """{"type":"user"}""".toByteArray()
    private val tar = byteArrayOf(31, -117, 8, 0)

    /** A pod that answers `du` with [size], `cat` with the session file and `tar` with [tar]; what it was asked and stopped. */
    private inner class FakePods(var size: Long = 1234) : FirstMateSessionPods {
        val scripts = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        /** The session's state and whether its relay had answered, at each exec. */
        val seen = mutableListOf<Pair<String, Boolean>>()
        override fun start(session: FirstMateSession, mcp: FirstMateMcp, restore: FirstMateSnapshot?) {}
        override fun stop(session: FirstMateSession) { stopped += session.id }
        override fun exec(session: FirstMateSession, script: String, stdin: ByteArray?): ByteArray? {
            scripts += script
            seen += sessions.get(session.id)!!.state to sessions.podExitedAfter(session.id, 0)
            return when {
                script.startsWith("du ") -> "$size\n".toByteArray()
                script.startsWith("cat ") -> sessionFile
                script.startsWith("tar ") -> tar
                else -> ByteArray(0)
            }
        }
    }

    private fun freezer(pods: FirstMateSessionPods) =
        FirstMateFreezer(sessions, snapshots, pods, expiry).apply { clock = Clock.fixed(now.toInstant(), ZoneOffset.UTC); handshake = Duration.ofMillis(100) }

    private fun clean() {
        listOf("engine.first_mate_snapshot", "engine.first_mate_message", "engine.first_mate_session").forEach { jdbc.sql("DELETE FROM $it").update() }
        expiry.set(null)
    }
    @BeforeEach fun setUp() = clean()
    @AfterEach fun tearDown() = clean()

    /** An open session whose last message was [idle] ago, with Claude id `c-<n>`. */
    private fun session(idle: Duration): FirstMateSession {
        val s = sessions.start("alice", "tok-1", "acme/firstmate")
        sessions.setClaudeSessionId(s.id, "c-${s.id.take(8)}")
        jdbc.sql("UPDATE engine.first_mate_session SET last_message_at = :t WHERE id = :id").param("t", now.minus(idle)).param("id", s.id).update()
        return sessions.get(s.id)!!
    }

    @Test
    fun `a session idle 14m59s is left open, one idle 15m is frozen, its pod read and stopped, its snapshot saved`() {
        val fresh = session(Duration.ofMinutes(15).minusSeconds(1))
        val idle = session(Duration.ofMinutes(15))
        val pods = FakePods()
        assertEquals(listOf(idle.id), freezer(pods).freezeIdle())

        assertEquals("open", sessions.get(fresh.id)!!.state)
        val frozen = sessions.get(idle.id)!!
        assertEquals("frozen", frozen.state)
        assertEquals(now.toInstant(), frozen.frozenAt!!.toInstant())
        assertEquals(listOf(idle.id), pods.stopped)
        assertEquals(FIRST_MATE_CWD, frozen.cwd)
        assertTrue(pods.scripts.any { it.contains(claudeSessionPath(FIRST_MATE_CWD, idle.claudeSessionId!!)) })
        val snap = assertNotNull(snapshots.get(idle.id))
        assertContentEquals(sessionFile, snap.sessionFile)
        assertContentEquals(tar, snap.workdirTar)
        assertEquals(1234, snap.size)
        assertNull(snapshots.get(fresh.id))
    }

    @Test
    fun `a folder over 200 MB is dropped and flagged, and one of exactly 200 MB is kept`() {
        val big = session(Duration.ofMinutes(20))
        val pods = FakePods(FirstMateSnapshot.CAP_BYTES + 1)
        freezer(pods).freezeIdle()
        assertTrue(pods.scripts.none { it.startsWith("tar ") })
        assertTrue(sessions.get(big.id)!!.workdirDropped)
        val snap = snapshots.get(big.id)!!
        assertNull(snap.workdirTar)
        assertContentEquals(sessionFile, snap.sessionFile)

        val edge = session(Duration.ofMinutes(20))
        freezer(FakePods(FirstMateSnapshot.CAP_BYTES)).freezeIdle()
        assertEquals(false, sessions.get(edge.id)!!.workdirDropped)
        assertContentEquals(tar, snapshots.get(edge.id)!!.workdirTar)
    }

    @Test
    fun `a message after the freeze thaws the session, and an ended session's snapshot is gone`() {
        val s = session(Duration.ofMinutes(30))
        freezer(FakePods()).freezeIdle()
        assertTrue(sessions.thaw(s.id))
        assertEquals("open", sessions.get(s.id)!!.state)
        assertEquals(false, sessions.thaw(s.id))
        assertTrue(sessions.end(s.id))
        assertNull(snapshots.get(s.id))
    }

    @Test
    fun `with no expiry frozen sessions are kept, with one only sessions frozen longer ago are deleted`() {
        val old = session(Duration.ofDays(9)); val recent = session(Duration.ofDays(2)); val open = session(Duration.ofMinutes(1))
        freezer(FakePods()).freezeIdle()
        fun frozenAt(id: String, ago: Duration) = jdbc.sql("UPDATE engine.first_mate_session SET frozen_at = :t WHERE id = :id")
            .param("t", now.minus(ago)).param("id", id).update()
        frozenAt(old.id, Duration.ofDays(8)); frozenAt(recent.id, Duration.ofDays(6))

        assertNull(expiry.get())
        assertEquals(0, freezer(FakePods()).expire())
        assertNotNull(sessions.get(old.id))

        expiry.set(7)
        assertEquals(7, expiry.get())
        assertEquals(1, freezer(FakePods()).expire())
        assertNull(sessions.get(old.id))
        assertNull(snapshots.get(old.id))
        assertEquals("frozen", sessions.get(recent.id)!!.state)
        assertEquals("open", sessions.get(open.id)!!.state)
    }

    @Test
    fun `the session is freezing before the pod is read, and the snapshot waits for the relay's status exited`() {
        val s = session(Duration.ofMinutes(20))
        val pods = FakePods()
        val f = freezer(pods).apply { handshake = Duration.ofSeconds(10) }
        // The relay: sees the inbox answer freeze, flushes a last line, posts status exited.
        val relay = Thread {
            while (sessions.get(s.id)?.freezing != true) Thread.sleep(20)
            Thread.sleep(300)
            assertTrue(pods.scripts.isEmpty(), "no exec before the relay answers")
            sessions.append(s.id, "assistant", mapOf("type" to "result"))
            sessions.append(s.id, "system", mapOf("type" to "pod-status", "state" to "exited", "code" to 0))
        }.apply { start() }
        assertEquals(listOf(s.id), f.freezeIdle())
        relay.join()
        assertTrue(pods.seen.isNotEmpty())
        assertTrue(pods.seen.all { it == ("freezing" to true) }, "every exec ran on a freezing session after the relay's answer: ${pods.seen}")
        assertEquals("frozen", sessions.get(s.id)!!.state, "the relay's own lines do not count as a message")
        assertEquals(listOf(s.id), pods.stopped)
    }
}

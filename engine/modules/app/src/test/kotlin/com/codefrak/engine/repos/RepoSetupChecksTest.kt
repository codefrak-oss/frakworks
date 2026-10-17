package com.codefrak.engine.repos

import com.codefrak.engine.ci.FakeCheckRuns
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.events.EventRecorder
import com.codefrak.engine.github.FakeRepoSetupGitHub
import com.codefrak.engine.github.RepoSettings
import com.codefrak.engine.github.RepoSetupGitHub
import com.codefrak.engine.github.RepoTokens
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [RepoSetupChecks] over the fake GitHub [RepoSetupCheckTest] uses: one round of reads is one `repo` read. Plain JUnit, no Spring. */
class RepoSetupChecksTest {
    /** The fake, counting rounds, and holding a round at its first read while [hold] is set, until [release]. */
    private class Counting(val fake: FakeRepoSetupGitHub) : RepoSetupGitHub by fake {
        val rounds = AtomicInteger()
        @Volatile var hold: CountDownLatch? = null
        @Volatile var fail: RuntimeException? = null
        val entered = CountDownLatch(1)
        override fun repo(repo: String): RepoSettings? {
            rounds.incrementAndGet()
            entered.countDown()
            hold?.await(5, TimeUnit.SECONDS)
            fail?.let { throw it }
            return fake.repo(repo)
        }
    }

    private class Now(var instant: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
        override fun instant() = instant
    }

    private val repo = "acme/widgets"
    private val row = TargetRepo(repo, OffsetDateTime.parse("2026-09-26T10:00:00Z"))
    private val github = Counting(FakeRepoSetupGitHub().apply { repos[repo] = RepoSettings("main", push = true, allowSquashMerge = true) })
    private val bus = EngineEvents(jacksonObjectMapper())
    private val clock = Now(Instant.parse("2026-10-05T10:00:00Z"))
    private val setup = RepoSetupCheck(github, FakeCheckRuns(), RepoTokens { "t0k" })

    /** Runs each check on the calling thread, so a test reads the frames as soon as [RepoSetupChecks.request] returns. */
    private val inline = RepoSetupChecks(setup, bus, clock) { it() }

    @Test
    fun `two requests inside 5 minutes make one round, the second re-publishes the kept report`() {
        EventRecorder(bus, repo).use { rec ->
            assertEquals(SetupCheckQueued(listOf(repo), emptyList()), inline.request(listOf(row), force = false))
            clock.instant = clock.instant.plus(Duration.ofMinutes(4))
            assertEquals(SetupCheckQueued(emptyList(), listOf(repo)), inline.request(listOf(row), force = false))
            assertEquals(1, github.rounds.get())
            val frames = rec.of("setup")
            assertEquals(listOf("started", "done", "done"), frames.map { it.state })
            assertEquals("2026-10-05T10:00:00Z", frames[1].str("checkedAt"))
            assertEquals("2026-10-05T10:00:00Z", frames[2].str("checkedAt"), "the fresh frame keeps its original checkedAt")
            assertEquals("main", frames[2].data["report"]["defaultBranch"]["name"].asString())
        }
    }

    @Test
    fun `a report older than 5 minutes is read again`() {
        inline.request(listOf(row), force = false)
        clock.instant = clock.instant.plus(RepoSetupChecks.FRESH_FOR)
        assertEquals(SetupCheckQueued(listOf(repo), emptyList()), inline.request(listOf(row), force = false))
        assertEquals(2, github.rounds.get())
    }

    @Test
    fun `force inside the window makes a second round`() {
        inline.request(listOf(row), force = false)
        assertEquals(SetupCheckQueued(listOf(repo), emptyList()), inline.request(listOf(row), force = true))
        assertEquals(2, github.rounds.get())
    }

    @Test
    fun `two concurrent requests while a check runs make one round, and the request never waits on GitHub`() {
        val checks = RepoSetupChecks(setup, bus, clock) { task -> Thread.ofVirtual().start { task() } }
        github.hold = CountDownLatch(1)
        EventRecorder(bus, repo).use { rec ->
            assertEquals(SetupCheckQueued(listOf(repo), emptyList()), checks.request(listOf(row), force = false))
            assertTrue(github.entered.await(5, TimeUnit.SECONDS), "the check started")
            assertEquals(SetupCheckQueued(listOf(repo), emptyList()), checks.request(listOf(row), force = false), "returned while GitHub is held")
            assertEquals(SetupCheckQueued(listOf(repo), emptyList()), checks.request(listOf(row), force = true), "a force joins the running check too")
            github.hold!!.countDown()
            rec.await("setup") { it.state == "done" }
            assertEquals(1, github.rounds.get())
            assertEquals(listOf("started", "done"), rec.of("setup").map { it.state })
        }
    }

    @Test
    fun `a check that throws publishes failed, keeps nothing, and the next request runs again`() {
        github.fail = IllegalStateException("502 Bad Gateway")
        EventRecorder(bus, repo).use { rec ->
            inline.request(listOf(row), force = false)
            val failed = rec.of("setup").last()
            assertEquals("failed", failed.state)
            assertEquals("GitHub: 502 Bad Gateway", failed.str("error"))
            assertNull(failed.str("report"))
            github.fail = null
            assertEquals(SetupCheckQueued(listOf(repo), emptyList()), inline.request(listOf(row), force = false))
            assertEquals(2, github.rounds.get())
            assertEquals("done", rec.of("setup").last().state)
        }
    }

    @Test
    fun `a star request starts each repo it is given, and a repo's frames go on its stream and on star`() {
        val other = TargetRepo("acme/gadgets", OffsetDateTime.parse("2026-09-26T10:00:00Z"))
        EventRecorder(bus).use { all ->
            EventRecorder(bus, repo).use { one ->
                assertEquals(SetupCheckQueued(listOf(repo, "acme/gadgets"), emptyList()), inline.request(listOf(row, other), force = false))
                assertEquals(4, all.of("setup").size)
                assertEquals(setOf(repo), one.of("setup").map { it.str("repo") }.toSet())
            }
        }
    }
}

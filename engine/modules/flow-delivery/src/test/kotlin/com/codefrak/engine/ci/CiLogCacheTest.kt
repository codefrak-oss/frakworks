package com.codefrak.engine.ci

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A completed job's cleaned log, kept per (repo, job) within a size bound and a TTL (#2188). Plain JUnit. */
class CiLogCacheTest {
    private class Logs : CheckRuns {
        var fetched = 0
        override fun latest(repo: String, sha: String, name: String): CheckRun? = null
        override fun log(repo: String, checkRunId: Long): String { fetched++; return "2026-10-10T10:00:00Z #3 1.0 job $checkRunId\n" }
        override fun rerun(repo: String, checkRunId: Long) = Unit
    }

    private class Ticking(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }

    @Test
    fun `a completed job's log is fetched once and cleaned, a running job's every time`() {
        val logs = Logs()
        val cache = CiLogCache()
        assertEquals(listOf("job 1", ""), cache.get(logs, "acme/widgets", 1, completed = true).lines)
        cache.get(logs, "ACME/widgets", 1, completed = true)
        assertEquals(1, logs.fetched)
        cache.get(logs, "acme/widgets", 2, completed = false)
        cache.get(logs, "acme/widgets", 2, completed = false)
        assertEquals(3, logs.fetched)
        assertEquals(1, cache.size())
        assertEquals("2026-10-10T10:00:00Z", cache.get(logs, "acme/widgets", 1, completed = true).lastOutputAt)
    }

    @Test
    fun `an entry past its TTL is fetched again, and the size bound evicts the least recently used`() {
        val logs = Logs()
        val clock = Ticking(Instant.parse("2026-10-10T10:00:00Z"))
        val cache = CiLogCache(clock, maxChars = 16)
        cache.get(logs, "acme/widgets", 1, true)
        clock.now = clock.now.plus(CiLogCache.TTL)
        cache.get(logs, "acme/widgets", 1, true)
        assertEquals(2, logs.fetched)
        cache.get(logs, "acme/widgets", 2, true) // 8 chars each ("job N" and the empty last line): two fit, a third evicts job 1
        cache.get(logs, "acme/widgets", 3, true)
        assertEquals(2, cache.size())
        cache.get(logs, "acme/widgets", 1, true)
        assertEquals(5, logs.fetched)
    }

    @Test
    fun `a log above the bound has no lines, only its size, and lastOutputAt finds the last stamped line`() {
        val big = object : CheckRuns by Logs() {
            override fun boundedLog(repo: String, checkRunId: Long, maxBytes: Long) = LogFetch(null, maxBytes + 1)
        }
        val log = CiLogCache().get(big, "acme/widgets", 9, true)
        assertNull(log.lines)
        assertEquals(CiLogCache.MAX_LOG_BYTES + 1, log.size)
        assertEquals("2026-10-10T10:00:02.5Z", CiLogCache.lastOutputAt("2026-10-10T10:00:01Z a\n2026-10-10T10:00:02.5Z b\nno stamp\n"))
        assertNull(CiLogCache.lastOutputAt("none\n"))
    }
}

package com.codefrak.engine.sdk

import com.codefrak.engine.friction.FrictionNote
import com.codefrak.engine.mcp.FakeFrictionLog
import com.codefrak.sdk.FrictionFilter
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The SDK's `Friction`, called as a plugin would: the note lands in the engine's log stamped by the engine, never by the plugin. */
class EngineFrictionTest {
    private val log = FakeFrictionLog()

    @Test
    fun `a note is as-you-go, stamped with the job id, repo and run, and has no ticket`() {
        val friction = EngineFrictions(log).forJob("job-7", "acme/widgets", "run-1")
        val id = friction.note("tooling", "The build is slow", "gradle took 9 minutes", "low")
        val row = log.rows.single()
        assertEquals(id, row.id)
        assertEquals(listOf(FrictionNote.AS_YOU_GO, "job-7", "acme/widgets", "run-1", "root-1", "tooling", "The build is slow", "gradle took 9 minutes", "low"),
            listOf(row.source, row.jobId, row.repo, row.runId, row.processInstanceId, row.kind, row.summary, row.evidence, row.severity))
        assertNull(row.issue, "a job has no ticket")

        val listed = friction.list(FrictionFilter(repo = "acme/widgets", kind = "tooling"))
        assertEquals(listOf(id to "job-7"), listed.map { it.id to it.jobId })
        assertEquals("acme/widgets" to "tooling", log.asked?.repo to log.asked?.kind)
    }

    @Test
    fun `a plugin outside any run has no run id, and a bad kind is refused before anything is stored`() {
        val friction = EngineFrictions(log).forJob("job-8", "acme/widgets")
        friction.note("docs", "A doc is stale")
        assertNull(log.rows.single().runId)
        assertFailsWith<IllegalArgumentException> { friction.note("mood", "Not a kind") }
        assertEquals(1, log.rows.size)
    }
}

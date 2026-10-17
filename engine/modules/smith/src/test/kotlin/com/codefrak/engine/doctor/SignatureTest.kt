package com.codefrak.engine.doctor

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A sighting's [Signature] (engine/README.md, "Doctor"): the same defect at the same place is one key, whatever its ids. */
class SignatureTest {
    private val failedJob = "failedJob"

    @Test
    fun `two messages that differ only in ids give one signature`() {
        val a = Signature.of("engine", listOf("smith-run:task-remove-sandbox@none", "incident:inc-1"), listOf(IncidentShape(
            "task-remove-sandbox", failedJob,
            "Could not remove sandbox 3f85b96a-c079-11f1-b874-c2538ad383a1 (container 9c2e41f0ab77): exit 137\n  at line 12")))
        val b = Signature.of("engine", listOf("incident:inc-2", "smith-run:task-remove-sandbox@none"), listOf(IncidentShape(
            "task-remove-sandbox", failedJob,
            "Could not remove sandbox 0a1b2c3d-0000-11f1-aaaa-c2538ad383a1 (container 4d77e0c1b2aa): exit 1\n  at line 40")))
        assertEquals(a, b)
        assertEquals(a.hash, b.hash)
        assertEquals(64, a.hash.length)
        assertEquals(listOf("smith-run:task-remove-sandbox@none"), a.leaves)
        assertEquals(listOf("task-remove-sandbox failedJob Could not remove sandbox # (container #): exit #"), a.shape)
    }

    @Test
    fun `two different messages at smith-run task-remove-sandbox@none give two signatures`() {
        val position = listOf("smith-run:task-remove-sandbox@none")
        val a = Signature.of("engine", position, listOf(IncidentShape("task-remove-sandbox", failedJob, "No such container: abc1234def")))
        val b = Signature.of("engine", position, listOf(IncidentShape("task-remove-sandbox", failedJob, "Docker daemon is not reachable")))
        assertNotEquals(a.hash, b.hash)
    }

    @Test
    fun `where, the leaves and the generic run message's class are part of the key`() {
        val position = listOf("reusable-smith-run:task-read-result@none")
        fun generic(where: String, runClass: String?) = Signature.of(where, position,
            listOf(IncidentShape("task-read-result", failedJob, "smith run 9c2e41f0ab77 ended failed; see its log", runClass)))
        assertTrue(Signature.generic("smith run 9c2e41f0ab77 ended failed; see its log"))
        assertFalse(Signature.generic("Could not remove sandbox"))
        assertEquals(generic("engine", "oom").hash, generic("engine", "oom").hash)
        assertNotEquals(generic("engine", "oom").hash, generic("engine", "exit:1").hash)
        assertNotEquals(generic("engine", "oom").hash, generic("target", "oom").hash)
        assertEquals(listOf("task-read-result failedJob smith run # ended failed; see its log [unknown]"), generic("engine", null).shape)
        assertNotEquals(Signature.doctorFailed(position, "oom").hash, Signature.doctorFailed(listOf("chore:task-open-pr@none"), "oom").hash)
        assertEquals(listOf("doctor-failed oom"), Signature.doctorFailed(position, "oom").shape)
        assertEquals("an effaced word stays", Signature.normalise("an effaced word stays"))
    }
}

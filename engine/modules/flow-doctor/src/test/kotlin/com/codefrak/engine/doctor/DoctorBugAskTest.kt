package com.codefrak.engine.doctor

import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [DoctorBugAsk.render]: the ask a doctor bug's intake is filed with. */
class DoctorBugAskTest {
    private val stuckSince = OffsetDateTime.parse("2026-10-06T21:23:00Z")
    private val signature = Signature("engine", listOf("chore:task-open-pr@none"), listOf("task-open-pr failedJob git clone failed"))

    private fun sighting(where: String = "engine", repo: String = "acme/widgets", summary: String? = "Stuck cloning the repo") = NewSighting(
        doctorRunId = "run-1", earlierRunId = null, repo = repo, issue = 42, rootInstanceId = "inst-1",
        kind = "stuck", runClass = null, where = where, summary = summary, signature = signature,
    )

    @Test
    fun `the title is Engine defect for where engine`() {
        val ask = DoctorBugAsk.render(sighting(where = "engine"), null, "acme/engine", null, stuckSince)
        assertTrue(ask.lines().first().startsWith("Engine defect: Stuck cloning the repo"), ask)
    }

    @Test
    fun `the title is Defect in owner-name for where target`() {
        val ask = DoctorBugAsk.render(sighting(where = "target", repo = "acme/widgets"), null, "acme/widgets", null, stuckSince)
        assertTrue(ask.lines().first().startsWith("Defect in acme/widgets: Stuck cloning the repo"), ask)
    }

    @Test
    fun `the title is Regression of the old bug's name, with a regressionOf`() {
        val old = DoctorBugRef(1, "acme/engine", 7, "fixed")
        val ask = DoctorBugAsk.render(sighting(), null, "acme/engine", old, stuckSince)
        assertTrue(ask.lines().first().startsWith("Regression of #7: Stuck cloning the repo"), ask)
    }

    @Test
    fun `line two is Kind bug`() {
        val ask = DoctorBugAsk.render(sighting(), null, "acme/engine", null, stuckSince)
        assertEquals("Kind: bug", ask.lines()[1])
    }

    @Test
    fun `the Stuck ticket line names stuckSince when given, and omits it when null`() {
        val withSince = DoctorBugAsk.render(sighting(), null, "acme/engine", null, stuckSince)
        assertTrue(withSince.contains(", stuck since $stuckSince"), withSince)

        val withoutSince = DoctorBugAsk.render(sighting(), null, "acme/engine", null, null)
        assertTrue(withoutSince.lines().any { it.startsWith("- Stuck ticket:") && !it.contains("stuck since") }, withoutSince)
    }

    @Test
    fun `the diagnosis is cut at MAX_DIAGNOSIS`() {
        val long = "x".repeat(DoctorBugAsk.MAX_DIAGNOSIS + 500)
        val ask = DoctorBugAsk.render(sighting(), long, "acme/engine", null, stuckSince)
        val diagnosis = ask.substringAfter("Diagnosis (the doctor's Smith):\n\n").trim()
        assertEquals(DoctorBugAsk.MAX_DIAGNOSIS, diagnosis.length)
    }

    @Test
    fun `a sighting with no summary falls back to 'a stuck ticket the doctor could not fix'`() {
        val ask = DoctorBugAsk.render(sighting(summary = null), null, "acme/engine", null, stuckSince)
        assertTrue(ask.lines().first().startsWith("Engine defect: a stuck ticket the doctor could not fix"), ask)
    }
}

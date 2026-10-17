package com.codefrak.engine.camunda

import com.codefrak.engine.smith.ResultSchemas
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What fix and diagnose Smiths write (smith/schemas/fix.schema.json, diagnose.schema.json), and the proposal cut. */
class FixResultTest {
    @Test
    fun `a fix result picks retry or unfixable, with a diagnosis`() {
        val retry = """{"outcome":"retry","diagnosis":"a Docker blip"}""".toByteArray()
        assertEquals(emptyList(), ResultSchemas.problems("fix", retry))
        assertEquals(emptyList(), FixResult.parse(retry).problems())
        assertEquals("retry", FixResult.parse(retry).outcome)

        val restart = """{"outcome":"restart the engine","diagnosis":"x"}""".toByteArray()
        assertTrue(ResultSchemas.problems("fix", restart).isNotEmpty(), "a remedy off the menu")
        assertTrue(FixResult.parse(restart).problems().isNotEmpty())
        assertTrue(ResultSchemas.problems("fix", """{"outcome":"retry"}""".toByteArray()).isNotEmpty(), "no diagnosis")
    }

    @Test
    fun `a diagnose result proposes a summary of any length, which the engine cuts to 40`() {
        val long = """{"outcome":"proposed","summary":"${"x".repeat(80)}","diagnosis":"why"}""".toByteArray()
        assertEquals(emptyList(), ResultSchemas.problems("diagnose", long), "a long summary is truncated, not refused")
        assertEquals(emptyList(), DiagnoseResult.parse(long).problems())
        assertTrue(ResultSchemas.problems("diagnose", """{"outcome":"proposed","summary":" "}""".toByteArray()).isNotEmpty())
        assertTrue(DiagnoseResult.parse("""{"outcome":"retry","summary":"s"}""".toByteArray()).problems().isNotEmpty())
    }

    @Test
    fun `a proposal is one line of at most 40 characters`() {
        assertEquals("Retry: Docker blip", proposal("  Retry:\n Docker   blip "))
        val exact = "a".repeat(MAX_PROPOSAL)
        assertEquals(exact, proposal(exact))
        val cut = proposal("Retry it: the work run's sandbox lost its network mid-run")
        assertEquals(MAX_PROPOSAL, cut.length)
        assertEquals("Retry it: the work run's sandbox lost i…", cut)
        assertTrue(proposal("${"b".repeat(38)} and more").let { it.length <= MAX_PROPOSAL && it.endsWith("…") })
    }
}

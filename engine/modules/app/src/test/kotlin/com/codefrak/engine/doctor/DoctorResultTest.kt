package com.codefrak.engine.doctor

import com.codefrak.engine.smith.ResultSchemas
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a doctor Smith writes (smith/schemas/doctor.schema.json), checked by the schema and by [DoctorResult.problems]. */
class DoctorResultTest {
    private fun bytes(text: String) = text.toByteArray()

    @Test
    fun `every remedy on the menu, with a diagnosis, and a target where it acts on one`() {
        listOf(
            """{"outcome":"healthy","diagnosis":"The engine check is running."}""",
            """{"outcome":"unfixable","diagnosis":"The ticket names a repo the fleet cannot read.","defect":{"where":"none","summary":"A wrong repo"}}""",
            """{"outcome":"retry","target":"inc-1","diagnosis":"A Docker blip.","defect":{"where":"engine","summary":"Docker failed"}}""",
            """{"outcome":"collect-exit","diagnosis":"The sandbox exited while the event stream was down.","defect":{"where":"engine","summary":"An exit missed"}}""",
            """{"outcome":"collect-exit","target":"run-1","diagnosis":"That run's sandbox exited.","defect":{"where":"engine","summary":"An exit missed"}}""",
            """{"outcome":"run-job","target":"job-1","diagnosis":"The job is due and nothing took it.","summary":"Run the job","defect":{"where":"target","summary":"The repo's job"}}""",
        ).forEach {
            assertEquals(emptyList(), ResultSchemas.problems("doctor", bytes(it)), it)
            assertEquals(emptyList(), DoctorResult.parse(bytes(it)).problems(), it)
        }
        val run = DoctorResult.parse(bytes("""{"outcome":"run-job","target":"job-1","diagnosis":"due","defect":{"where":"engine","summary":"x"}}"""))
        assertEquals("run-job" to "job-1", run.outcome to run.target)
    }

    @Test
    fun `a remedy off the menu, no diagnosis, or retry and run-job without a target are refused`() {
        listOf(
            """{"outcome":"restart the engine","diagnosis":"x"}""",
            """{"outcome":"healthy"}""",
            """{"outcome":"retry","diagnosis":"x"}""",
            """{"outcome":"run-job","diagnosis":"x"}""",
        ).forEach {
            assertTrue(ResultSchemas.problems("doctor", bytes(it)).isNotEmpty(), "the schema refuses $it")
            assertTrue(DoctorResult.parse(bytes(it)).problems().isNotEmpty(), "problems() refuses $it")
        }
        assertTrue(ResultSchemas.problems("doctor", bytes("""{"outcome":"retry","target":" ","diagnosis":"x"}""")).isNotEmpty(), "a blank target")
    }

    @Test
    fun `a diagnosis may be any non-empty JSON, passed on as text`() {
        val result = bytes("""{"outcome":"unfixable","diagnosis":{"cause":"a credential expired"},"defect":{"where":"engine","summary":"x"}}""")
        assertEquals(emptyList(), ResultSchemas.problems("doctor", result))
        assertTrue("a credential expired" in DoctorResult.parse(result).diagnosis!!)
    }

    @Test
    fun `every outcome but healthy needs a defect, and an applied remedy's is never where none`() {
        val defect = DoctorResult.parse(bytes("""{"outcome":"retry","target":"inc-1","diagnosis":"x","defect":{"where":"target","summary":"A flaky test"}}""")).defect
        assertEquals(DoctorDefect("target", "A flaky test"), defect)
        listOf(
            """{"outcome":"unfixable","diagnosis":"x"}""" to "needs a defect",
            """{"outcome":"retry","target":"inc-1","diagnosis":"x"}""" to "needs a defect",
            """{"outcome":"collect-exit","diagnosis":"x"}""" to "needs a defect",
            """{"outcome":"run-job","target":"job-1","diagnosis":"x"}""" to "needs a defect",
            """{"outcome":"retry","target":"inc-1","diagnosis":"x","defect":{"where":"none","summary":"y"}}""" to "never none",
            """{"outcome":"collect-exit","diagnosis":"x","defect":{"where":"none","summary":"y"}}""" to "never none",
            """{"outcome":"run-job","target":"job-1","diagnosis":"x","defect":{"where":"none","summary":"y"}}""" to "never none",
            """{"outcome":"unfixable","diagnosis":"x","defect":{"where":"elsewhere","summary":"y"}}""" to "is not one of",
            """{"outcome":"unfixable","diagnosis":"x","defect":{"where":"engine","summary":" "}}""" to "needs a summary",
        ).forEach { (it, why) ->
            assertTrue(ResultSchemas.problems("doctor", bytes(it)).isNotEmpty(), "the schema refuses $it")
            val problems = DoctorResult.parse(bytes(it)).problems()
            assertTrue(problems.any { p -> why in p }, "problems() refuses $it with '$why': $problems")
        }
        listOf(
            """{"outcome":"healthy","diagnosis":"x"}""",
            """{"outcome":"healthy","diagnosis":"x","defect":{"where":"none","summary":"nothing"}}""",
            """{"outcome":"unfixable","diagnosis":"x","defect":{"where":"none","summary":"The ticket is wrong"}}""",
            """{"outcome":"unfixable","diagnosis":"x","defect":{"where":"target","summary":"The repo's CI is broken"}}""",
        ).forEach {
            assertEquals(emptyList(), ResultSchemas.problems("doctor", bytes(it)), it)
            assertEquals(emptyList(), DoctorResult.parse(bytes(it)).problems(), it)
        }
    }
}

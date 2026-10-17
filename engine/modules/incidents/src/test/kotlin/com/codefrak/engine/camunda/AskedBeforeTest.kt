package com.codefrak.engine.camunda

import com.codefrak.engine.camunda.AskedBefore.askedBefore
import com.codefrak.engine.camunda.AskedBefore.questionOf
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The preceding-run question lookup the timeline and [com.codefrak.engine.board.Needs] share. */
class AskedBeforeTest {
    private val full = "Which of the 9 checks?\n\n1. lint\n2. tests\n\n```ask\n- 1: keep | drop\n```"

    @Test
    fun `the newest run that ended before the wait asks the question`() {
        val runs = listOf(AskedRun(100, false, "old"), AskedRun(200, false, full), AskedRun(400, false, "after the wait"))
        assertEquals(full, askedBefore(runs, 300))
    }

    @Test
    fun `a running run, or none that asked, gives null`() {
        assertNull(askedBefore(listOf(AskedRun(100, true, "still running")), 300))
        assertNull(askedBefore(listOf(AskedRun(100, false, null)), 300))
        assertNull(askedBefore(emptyList(), 300))
    }

    @Test
    fun `a run's question comes whole from its result json`() {
        val json = tools.jackson.module.kotlin.jacksonObjectMapper().writeValueAsBytes(mapOf("outcome" to "ask", "question" to full))
        assertEquals(full, questionOf(json))
        assertNull(questionOf("""{"outcome":"done"}""".toByteArray()))
        assertNull(questionOf(null))
    }
}

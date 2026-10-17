package com.codefrak.engine.postdeploy

import com.codefrak.engine.intake.AskWaits
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [CheckDeadline]: when the check stops, the question's choice block, and reading the answer. Plain JUnit. */
class CheckDeadlineTest {
    private val now = Instant.parse("2026-10-13T12:00:00Z")

    @Test
    fun `the deadline is the section's instant, else its duration from now, else 7 days`() {
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), CheckDeadline.at(PostDeployCheck("x", deadlineAt = Instant.parse("2026-11-01T00:00:00Z")), now))
        assertEquals(now.plus(Duration.ofDays(2)), CheckDeadline.at(PostDeployCheck("x", deadlineAfter = Duration.ofDays(2)), now))
        assertEquals(now.plus(Duration.ofDays(7)), CheckDeadline.at(PostDeployCheck("x"), now))
        assertEquals(now.plus(Duration.ofDays(7)), CheckDeadline.extended(PostDeployCheck("x", deadlineAt = Instant.parse("2026-11-01T00:00:00Z")), now))
    }

    @Test
    fun `the question ends in a choice block whose options are wait, drop and bug`() {
        val runs = listOf(CheckRun("r1", now.toString(), "not_yet", "No board load yet."))
        val question = CheckDeadline.question(42, PostDeployCheck("The board shows the column."), runs, Duration.ofDays(7))
        assertTrue("after 1 run(s)" in question && "No board load yet." in question && "The board shows the column." in question, question)
        val options = AskWaits.askBlock(question)!!["options"] as List<*>
        assertEquals(CheckDeadline.ANSWERS, options.map { (it as Map<*, *>)["value"] })
    }

    @Test
    fun `an answer is read from the recorded choice or a bare id, and anything else is none`() {
        assertEquals("wait", CheckDeadline.answer("option wait: keep checking for another 7 day(s)"))
        assertEquals("drop", CheckDeadline.answer(" Drop "))
        assertNull(CheckDeadline.answer("maybe later"))
        assertNull(CheckDeadline.answer(null))
    }
}

package com.codefrak.engine.postdeploy

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [PostDeploySection.parse]: the `## POST-DEPLOYMENT-CHECK` grammar on PR bodies (bots/smith.md, "The post-deployment check"). Plain JUnit. */
class PostDeploySectionTest {
    private val body = """
        ## Summary

        Adds the widget.

        ## POST-DEPLOYMENT-CHECK

        Re-check: 4h
        Deadline: 2d
        The next time a feature asks the user a question, the answer is in `engine.hitlp_record`.
        Read it with GET /hitlp/records.

        ## Testing

        Unit tests.
    """.trimIndent()

    @Test
    fun `the section is read to the next heading of the same level, keys out, prose as what`() {
        val check = PostDeploySection.parse(body, "PR #12")!!
        assertEquals("The next time a feature asks the user a question, the answer is in `engine.hitlp_record`.\nRead it with GET /hitlp/records.", check.what)
        assertEquals(Duration.ofHours(4), check.recheck)
        assertEquals(Duration.ofDays(2), check.deadlineAfter)
        assertNull(check.deadlineAt)
    }

    @Test
    fun `a level-3 heading in any case ends at the next level-3 or higher heading, and the keys are optional`() {
        val check = PostDeploySection.parse("# Title\n\n### Post-Deployment-Check\n\nThe tile shows.\n\n#### Detail\n\nmore of it\n\n### Next\n\nnot this")!!
        assertEquals("The tile shows.\n#### Detail\nmore of it", check.what)
        assertNull(check.recheck); assertNull(check.deadlineAfter); assertNull(check.deadlineAt)
    }

    @Test
    fun `the section may be the last thing in the body`() {
        assertEquals("Watch the queue.", PostDeploySection.parse("## post-deployment-check\nWatch the queue.")!!.what)
    }

    @Test
    fun `a body without the section answers null, as does an empty one`() {
        assertNull(PostDeploySection.parse("## Summary\n\nNo check here, though POST-DEPLOYMENT-CHECK is mentioned in prose."))
        assertNull(PostDeploySection.parse(null))
        assertNull(PostDeploySection.parse("  "))
    }

    @Test
    fun `a section with no prose is an error naming the PR`() {
        val e = assertFailsWith<PostDeploySectionError> { PostDeploySection.parse("## POST-DEPLOYMENT-CHECK\n\nRe-check: 1h\n\n## Next\n\ntext", "PR #7") }
        assertTrue(e.message!!.startsWith("PR #7:"), e.message)
        assertTrue("prose" in e.message!!, e.message)
    }

    @Test
    fun `every duration form, and an ISO date or instant as the deadline`() {
        assertEquals(Duration.ofMinutes(30), PostDeploySection.duration("30m"))
        assertEquals(Duration.ofHours(4), PostDeploySection.duration("4h"))
        assertEquals(Duration.ofDays(2), PostDeploySection.duration("2d"))
        assertEquals(Duration.ofDays(7), PostDeploySection.duration("1w"))
        assertNull(PostDeploySection.duration("soon"))
        assertNull(PostDeploySection.duration("4 hours"))
        val dated = PostDeploySection.parse("## POST-DEPLOYMENT-CHECK\nDeadline: 2026-11-01\nIt works.")!!
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), dated.deadlineAt)
        assertNull(dated.deadlineAfter)
        val timed = PostDeploySection.parse("## POST-DEPLOYMENT-CHECK\ndeadline: 2026-11-01T09:30:00Z\nre-check: 1w\nIt works.")!!
        assertEquals(Instant.parse("2026-11-01T09:30:00Z"), timed.deadlineAt)
        assertEquals(Duration.ofDays(7), timed.recheck)
    }

    @Test
    fun `an unreadable value or a key given twice is an error`() {
        assertTrue("Re-check" in assertFailsWith<PostDeploySectionError> { PostDeploySection.parse("## POST-DEPLOYMENT-CHECK\nRe-check: later\nIt works.") }.message!!)
        assertTrue("Deadline" in assertFailsWith<PostDeploySectionError> { PostDeploySection.parse("## POST-DEPLOYMENT-CHECK\nDeadline: next week\nIt works.") }.message!!)
        assertTrue("two Re-check" in assertFailsWith<PostDeploySectionError> { PostDeploySection.parse("## POST-DEPLOYMENT-CHECK\nRe-check: 1h\nRe-check: 2h\nIt works.") }.message!!)
    }

    @Test
    fun `the section round-trips through its bytes, durations and the instant included`() {
        val check = PostDeployCheck("Watch it.", Duration.ofMinutes(30), null, Instant.parse("2026-11-01T00:00:00Z"))
        assertEquals(check, PostDeployCheck.of(check.toBytes()))
        assertEquals(PostDeployCheck("Watch it."), PostDeployCheck.of(PostDeployCheck("Watch it.").toBytes()))
        assertNull(PostDeployCheck.of(null))
    }
}

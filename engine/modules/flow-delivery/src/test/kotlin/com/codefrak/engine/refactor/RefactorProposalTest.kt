package com.codefrak.engine.refactor

import com.codefrak.engine.engineJson
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [RefactorProposal.of]: what a Smith's proposal node parses to; [ticketBody]: the filed ticket's body shape. */
class RefactorProposalTest {
    private fun node(json: String) = engineJson.readTree(json)
    private fun proposal(key: String = "engine/refactor/Foo", title: String = "Title", body: String = "Body", lines: Any = 42) =
        node("""{"key": ${engineJson.writeValueAsString(key)}, "title": "$title", "body": "$body", "lines": $lines}""")

    @Test
    fun `a well-formed proposal parses with every field`() {
        val result = RefactorProposal.of(proposal(lines = 0))
        assertEquals(RefactorProposal("engine/refactor/Foo", "Title", "Body", 0), result)
        assertEquals(RefactorProposal("engine/refactor/Foo", "Title", "Body", 42), RefactorProposal.of(proposal(lines = 42)))
    }

    @Test
    fun `a non-object node is null`() {
        assertNull(RefactorProposal.of(node("[1, 2, 3]")))
        assertNull(RefactorProposal.of(node(""""a string"""")))
    }

    @Test
    fun `a key with a space, or over 120 characters, is null`() {
        assertNull(RefactorProposal.of(proposal(key = "engine refactor Foo")))
        assertNull(RefactorProposal.of(proposal(key = "a".repeat(121))))
        assertNotNull(RefactorProposal.of(proposal(key = "a".repeat(120))))
    }

    @Test
    fun `a blank title, or one over 200 characters, is null`() {
        assertNull(RefactorProposal.of(proposal(title = "")))
        assertNull(RefactorProposal.of(proposal(title = "a".repeat(201))))
        assertNotNull(RefactorProposal.of(proposal(title = "a".repeat(200))))
    }

    @Test
    fun `a blank body is null`() {
        assertNull(RefactorProposal.of(proposal(body = "")))
        assertNull(RefactorProposal.of(proposal(body = "   ")))
    }

    @Test
    fun `lines negative, fractional, or missing is null`() {
        assertNull(RefactorProposal.of(proposal(lines = -1)))
        assertNull(RefactorProposal.of(proposal(lines = 1.5)))
        assertNull(RefactorProposal.of(node("""{"key": "k", "title": "t", "body": "b"}""")))
    }

    @Test
    fun `ticketBody is the proposal's body, then the values heading, then the values quoted line by line`() {
        val config = RefactorConfig("acme/repo", enabled = true, schedule = 604800L, values = "line one\nline two", valuesDefault = false,
            maxTickets = 2, maxLines = 300, approval = RefactorApproval.HUMAN, jobId = null, updatedAt = null, updatedBy = null)
        val result = ticketBody(RefactorProposal("k", "t", "the Smith's body", 120), config, "refactor-scan #42")

        val lines = result.lines()
        assertEquals("the Smith's body", lines[0])
        assertEquals("", lines[1])
        assertEquals(FileRefactorTicketsDelegate.VALUES_HEADING, lines[2])
        val quoted = lines.drop(3).filter { it.isNotEmpty() }
        assertEquals(listOf("> line one", "> line two"), quoted.takeLast(2))
        assertTrue(result.contains("refactor-scan #42"), "names the scan")
        assertTrue(result.contains("acme/repo"), "names the repo")
        assertTrue(result.contains("at most 2"), "names the ticket cap")
        assertTrue(result.contains("at most 300 changed lines"), "names the line cap")
        assertTrue(result.contains("counts its moved lines once, not as both added and removed, plus every other line of the new file: its KDoc, package line and imports"), "states how a verbatim move counts")
        assertTrue(result.contains("120"), "names the proposal's estimated lines")
    }
}

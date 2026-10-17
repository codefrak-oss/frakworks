package com.codefrak.engine.board

import com.codefrak.engine.github.FakeIssueDetails
import com.codefrak.engine.github.TreeEntry
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The proposal doc parser on this repo's real doc shapes, and the listing's oid cache. */
class ProposalDocsTest {
    private val workTree = """
        # A tree of related work in the desktop Explorer

        Status: accepted
        Decision log:
        - 2026-09-27 accepted (#569)
        Ticket: #569

        ## The question
    """.trimIndent()

    private val multiRepo = """
        # Multiple target repos in the desktop, engine and Smiths

        Status: built
        Decision log:
        - 2026-09-26 accepted (#497); build filed as #509
        - 2026-09-26 built in #512 (part 1 of 6)
        - 2026-09-27 revised: a token per target repo (§5, "A token that reaches the
          repo"), sealed on its `engine.target_repo` row
        Ticket: #497

        ## The question
    """.trimIndent()

    // Two Ticket: lines and a template Status line, both in the body's code fence.
    private val proposalFlow = """
        # Proposal flow

        Status: built
        Decision log:
        - 2026-09-25 accepted, with the user, in an interactive session; build filed through intake
        - 2026-09-26 built in #410
        Ticket: (this doc predates the flow; the build ticket is filed from the intake)

        ## The question

        ```
        # <title>
        Status: accepted | shelved | rejected | built
        Decision log:
        - 2026-11-02 accepted (#340); built in #352
        Ticket: #340
        ```
    """.trimIndent()

    @Test
    fun `reads the status, the log in order, the ticket and the build ticket`() {
        val p = ProposalDocs.parse(workTree, "explorer-work-tree")
        assertEquals("A tree of related work in the desktop Explorer", p.title)
        assertEquals("accepted", p.status)
        assertEquals(listOf("2026-09-27 accepted (#569)"), p.decisionLog)
        assertEquals(569L, p.ticket)
        assertNull(p.buildTicket)
    }

    @Test
    fun `a log item runs on over its indented lines, and the build ticket is the one the log filed`() {
        val p = ProposalDocs.parse(multiRepo, "multi-repo")
        assertEquals("built", p.status)
        assertEquals(3, p.decisionLog.size)
        assertEquals("2026-09-27 revised: a token per target repo (§5, \"A token that reaches the repo\"), sealed on its `engine.target_repo` row",
            p.decisionLog[2])
        assertEquals(497L, p.ticket)
        assertEquals(509L, p.buildTicket)
    }

    @Test
    fun `a template in the body is not the doc's own`() {
        val p = ProposalDocs.parse(proposalFlow, "proposal-flow")
        assertEquals("built", p.status)
        assertEquals(2, p.decisionLog.size)
        assertNull(p.ticket)
        assertNull(p.buildTicket)
    }

    @Test
    fun `bold labels read as plain ones, an unknown status is null, and no log is an empty one`() {
        val feed = "# Release feed\n\n**Status:** built, end to end.\n**Builds:** #441 (feature)\n\n**Decision log:**\n- 2026-09-26 filed\n"
        val p = ProposalDocs.parse(feed, "feed")
        assertEquals("built", p.status)
        assertEquals(441L, p.buildTicket)
        assertEquals(listOf("2026-09-26 filed"), p.decisionLog)
        val bare = ProposalDocs.parse("**Decided:** 2026-09-24. **Status:** not built.\n\nText.", "stack")
        assertEquals("stack", bare.title)
        assertNull(bare.status)
        assertEquals(emptyList<String>(), bare.decisionLog)
    }

    @Test
    fun `a second listing reads no blob that did not change, and a changed one only`() {
        val gh = FakeIssueDetails()
        gh.trees["o/r" to "docs/proposals"] = listOf(TreeEntry("a.md", "1"), TreeEntry("a.html", "2"), TreeEntry("b.md", "3"), TreeEntry("notes.txt", "4"))
        gh.blobs["1"] = workTree; gh.blobs["3"] = multiRepo
        val docs = ProposalDocs(gh)
        val first = docs.list("o/r")
        assertEquals(listOf("a", "b"), first.map { it.slug })
        assertEquals("docs/proposals/a.html", first[0].pitch)
        assertNull(first[1].pitch)
        assertEquals("docs/proposals/b.md", first[1].path)
        assertEquals(listOf(listOf("1", "3")), gh.blobReads.map { it.toList() })
        docs.list("o/r")
        assertEquals(1, gh.blobReads.size)
        gh.trees["o/r" to "docs/proposals"] = listOf(TreeEntry("a.md", "5"), TreeEntry("b.md", "3"))
        gh.blobs["5"] = proposalFlow
        val third = docs.list("o/r")
        assertEquals(listOf("5"), gh.blobReads.last().toList())
        assertEquals("Proposal flow", third[0].title)
        assertNull(third[0].pitch)
    }
}

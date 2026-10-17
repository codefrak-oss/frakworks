package com.codefrak.engine.repos

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class RepoChangesControllerTest {
    private val repo = "codefrak-oss/frakworks"
    private val from = "a".repeat(40)
    private val to = "d".repeat(40)

    // GitHub's order, oldest first: b on main, x on a merged side branch, c a direct push, d the head
    private val commits = listOf(
        ComparedCommit("b".repeat(40), from, "Choice questions offer a text box (#2712) (#2720)"),
        ComparedCommit("e".repeat(40), "f".repeat(40), "side branch commit (#1)"),
        ComparedCommit("c".repeat(40), "b".repeat(40), "direct push to main"),
        ComparedCommit(to, "c".repeat(40), "Bump esbuild to 0.28 (#2713)"),
    )
    private val calls = mutableListOf<String>()
    private val compare = CompareCommits { r, f, t -> calls += "$r $f...$t"; commits }
    private val titles = TicketTitles { _, n -> if (n == 2712L) "Choice questions offer “Something else”" else null }

    @Test
    fun `the compare's first-parent PRs come newest first with ticket titles from the store`() {
        val r = RepoChanges(compare, titles).answer(repo, from, to)
        assertEquals(listOf(2713L, 2720L), r.changes.map { it.pr })
        assertEquals("Bump esbuild to 0.28", r.changes[0].title)
        assertTrue(r.changes[0].tickets.isEmpty())
        val t = r.changes[1].tickets.single()
        assertEquals(2712L, t.n)
        assertEquals("frakworks://ticket/$repo/2712", t.url)
        assertEquals("Choice questions offer “Something else”", t.title)
        assertEquals("https://github.com/$repo/pull/2720", r.changes[1].prUrl)
        assertEquals(listOf("$repo $from...$to"), calls, "the compare is the one GitHub call")
    }

    @Test
    fun `a ticket the store does not know has a null title`() {
        val r = RepoChanges({ _, _, _ -> listOf(ComparedCommit(to, from, "Thing (#9) (#10)")) }, titles).answer(repo, from, to)
        assertNull(r.changes.single().tickets.single().title)
    }

    @Test
    fun `the same commit is no changes and no GitHub call`() {
        assertTrue(RepoChanges(compare, titles).answer(repo, from, from).changes.isEmpty())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `refusals are 400 for a bad sha, 404 for no target repo, 502 for a failed compare`() {
        val c = RepoChanges(compare, titles)
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows<ResponseStatusException> { c.answer(repo, "nope", to) }.statusCode)
        assertEquals(HttpStatus.NOT_FOUND, assertThrows<ResponseStatusException> { c.answer(null, from, to) }.statusCode)
        val failing = RepoChanges({ _, _, _ -> error("boom") }, titles)
        assertEquals(HttpStatus.BAD_GATEWAY, assertThrows<ResponseStatusException> { failing.answer(repo, from, to) }.statusCode)
    }

    @Test
    fun `parseSubject splits the trailing numbers`() {
        assertEquals("T" to listOf(1L, 2L, 3L), RepoChanges.parseSubject("T (#1) (#2) (#3)"))
        assertEquals("plain" to emptyList<Long>(), RepoChanges.parseSubject("plain"))
    }
}

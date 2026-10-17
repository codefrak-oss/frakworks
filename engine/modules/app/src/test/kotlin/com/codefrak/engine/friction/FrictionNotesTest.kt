package com.codefrak.engine.friction

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `engine.friction_note` against H2 with Flyway's migrations applied, the same scripts Postgres runs. */
@SpringBootTest
class FrictionNotesTest(@Autowired val notes: FrictionNotes, @Autowired val jdbc: JdbcClient) {
    @BeforeEach
    fun clear() { jdbc.sql("DELETE FROM engine.friction_note").update() }

    private fun note(id: String, at: String, repo: String = "acme/widgets", issue: Long = 7, kind: String = "docs",
                     source: String = FrictionNote.AS_YOU_GO, evidence: String? = null, severity: String? = null) =
        FrictionNote(id, repo, issue, "run-$id", "root-$id", OffsetDateTime.parse(at), source, kind, "summary $id", evidence, severity)

    @Test
    fun `a note round-trips every column`() {
        val n = note("a", "2026-10-01T10:00:00Z", evidence = "gradle test: exit 137", severity = "high")
        notes.insert(n)
        val back = notes.list(FrictionFilter()).single()
        assertEquals(n.copy(notedAt = back.notedAt, signature = FrictionKey.of(n.summary, n.evidence)), back, "the key is written at insert; nothing groups it here")
        assertTrue(back.notedAt.isEqual(n.notedAt))
        assertNull(back.groupId); assertNull(back.groupedBy); assertNull(back.jevCallId)
    }

    @Test
    fun `the key is the SHA-256 of the field and the normalised first line of the evidence, or of the summary with none`() {
        fun sha(s: String) = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(sha("evidence\n\$ bots/check /work/out/body.md -> exit #"), FrictionKey.of("not executable", "\n  \$ bots/check   /work/out/body.md -> exit 126\nPermission denied"))
        assertEquals(sha("summary\nno Gradle in the sandbox"), FrictionKey.of("no Gradle in the sandbox\nmore", null))
        assertEquals(FrictionKey.of("x", null), FrictionKey.of("x", "  \n "), "blank evidence is none")
        assertEquals(FrictionKey.of("a", "same line"), FrictionKey.of("b", "same line"), "the summary is not in an evidence key")
        assertTrue(FrictionKey.of("same line", null) != FrictionKey.of("other", "same line"), "a summary key never collides with an evidence key")
        assertEquals("evidence", FrictionKey.field("e")); assertEquals("summary", FrictionKey.field(null))
        assertEquals("python# bots/check #", FrictionKey.line("s", "python3 bots/check 0a1b2c3d-0000-0000-0000-000000000001"))
    }

    @Test
    fun `list filters by repo, issue, kind, source and since, newest first, up to the limit`() {
        notes.insert(note("a", "2026-10-01T10:00:00Z"))
        notes.insert(note("b", "2026-10-02T10:00:00Z", repo = "acme/other"))
        notes.insert(note("c", "2026-10-03T10:00:00Z", issue = 9, kind = "bug"))
        notes.insert(note("d", "2026-10-04T10:00:00Z", source = FrictionNote.RETRO))
        fun ids(f: FrictionFilter) = notes.list(f).map { it.id }
        assertEquals(listOf("d", "c", "b", "a"), ids(FrictionFilter()))
        assertEquals(listOf("b"), ids(FrictionFilter(repo = "acme/other")))
        assertEquals(listOf("c"), ids(FrictionFilter(issue = 9)))
        assertEquals(listOf("c"), ids(FrictionFilter(kind = "bug")))
        assertEquals(listOf("d"), ids(FrictionFilter(source = FrictionNote.RETRO)))
        assertEquals(listOf("d", "c"), ids(FrictionFilter(since = OffsetDateTime.parse("2026-10-03T00:00:00Z"))))
        assertEquals(listOf("d", "c"), ids(FrictionFilter(limit = 2)))
        assertEquals(listOf("a"), ids(FrictionFilter(repo = "acme/widgets", issue = 7, kind = "docs", source = FrictionNote.AS_YOU_GO)))
    }

    @Test
    fun `record stamps the note, and a run history does not know has no root`() {
        val n = notes.record("acme/widgets", 7, "no-such-run", FrictionInput("tooling", "s"), FrictionNote.RETRO)
        val back = notes.list(FrictionFilter()).single()
        assertEquals(listOf(n.id, "acme/widgets", 7L, "no-such-run", "retro", "tooling", "s"),
            listOf(back.id, back.repo, back.issue, back.runId, back.source, back.kind, back.summary))
        assertNull(back.processInstanceId)
    }

    @Test
    fun `recordFirstMate stamps first-mate and the login, with no ticket, run or flow, and a Smith's note has no login`() {
        val n = notes.recordFirstMate("someone", "acme/widgets", FrictionInput("docs", "s", "e"))
        notes.insert(note("a", "2026-10-01T10:00:00Z"))
        val back = notes.list(FrictionFilter(source = FrictionNote.FIRST_MATE)).single()
        assertEquals(n.copy(notedAt = back.notedAt), back, "record stamps the key too")
        assertEquals(FrictionKey.of("s", "e"), back.signature)
        assertEquals(listOf("acme/widgets", null, null, null, "first-mate", "someone"),
            listOf(back.repo, back.issue, back.runId, back.processInstanceId, back.source, back.login))
        assertNull(notes.list(FrictionFilter(source = FrictionNote.AS_YOU_GO)).single().login)
        notes.recordFirstMate("someone", null, FrictionInput("bug", "s"))
        assertEquals(listOf(null, "acme/widgets"), notes.list(FrictionFilter(source = FrictionNote.FIRST_MATE)).map { it.repo })
    }

    @Test
    fun `parse takes the four arguments and refuses the rest`() {
        assertEquals(FrictionInput.Parsed.Ok(FrictionInput("docs", "s", "e", "low")),
            FrictionInput.parse(mapOf("kind" to "docs", "summary" to " s ", "evidence" to " e ", "severity" to "low")))
        assertEquals(FrictionInput.Parsed.Ok(FrictionInput("bug", "s")), FrictionInput.parse(mapOf("kind" to "bug", "summary" to "s", "evidence" to null)))
        for (bad in listOf(mapOf("kind" to "docs", "summary" to "s", "x" to 1), mapOf("kind" to "nope", "summary" to "s"),
                mapOf("kind" to "docs", "summary" to ""), mapOf("kind" to "docs", "summary" to "s", "severity" to "huge"),
                mapOf("kind" to "docs", "summary" to "s", "evidence" to 5)))
            assertTrue(FrictionInput.parse(bad) is FrictionInput.Parsed.Bad, bad.toString())
    }
}

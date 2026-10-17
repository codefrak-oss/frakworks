package com.codefrak.engine.intake

import com.codefrak.engine.github.CreatedIssue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [FileOnce.findOrFile]'s three cases (#1802): nothing filed yet, filed and recorded, filed on GitHub but not recorded. */
class FileOnceTest {
    private val repo = "acme/tickets"
    private val calls = mutableListOf<String>()
    private val begin = { calls += "begin" }
    private val create = { calls += "create"; CreatedIssue(7, "https://github.com/$repo/issues/7", 1007) }
    private val record = { c: CreatedIssue -> calls += "record #${c.number}" }

    @Test
    fun `nothing filed yet, it begins, creates and records, and looks for nothing`() {
        val created = FileOnce.findOrFile("i-1", null, false, { calls += "find"; null }, begin, create, record, repo)
        assertEquals(7, created.number)
        assertEquals(listOf("begin", "create", "record #7"), calls)
    }

    @Test
    fun `filed and recorded, the recorded ticket, with no GitHub call and no write`() {
        val created = FileOnce.findOrFile("i-1", 5, true, { calls += "find"; null }, begin, create, record, repo)
        assertEquals(CreatedIssue(5, "https://github.com/$repo/issues/5"), created)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `filed on GitHub but not recorded, the ticket found by its trailer, recorded, and no second create`() {
        val found = CreatedIssue(6, "https://github.com/$repo/issues/6")
        val created = FileOnce.findOrFile("i-1", null, true, { calls += "find"; found }, begin, create, record, repo)
        assertEquals(found, created)
        assertEquals(listOf("find", "record #6"), calls)
    }

    @Test
    fun `a create begun that GitHub never made files it then`() {
        val created = FileOnce.findOrFile("i-1", null, true, { calls += "find"; null }, begin, create, record, repo)
        assertEquals(7, created.number)
        assertEquals(listOf("find", "begin", "create", "record #7"), calls)
    }

    @Test
    fun `the trailer names the intake as its origin, and no other`() {
        val body = "Tidy it.\n\n<!-- engine: intake bec00063-c32b · tier 5 · p2 · by alice -->\n"
        assertTrue(FileOnce.ofIntake(body, "bec00063-c32b"))
        assertFalse(FileOnce.ofIntake(body, "bec00063"))
        assertFalse(FileOnce.ofIntake("Tidy it.\n\n<!-- engine: split of #4 · p2 -->\n", "bec00063-c32b"))
        assertTrue(FileOnce.ofIntake("x\n\n<!-- engine: intake i-9 -->\n", "i-9"))
    }
}

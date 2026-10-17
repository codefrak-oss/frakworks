package com.codefrak.engine.intake

import com.codefrak.engine.board.BoardIssue
import com.codefrak.engine.board.BoardRows
import com.codefrak.engine.events.CreatedEvents
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.events.EventRecorder
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.OffsetDateTime
import com.codefrak.engine.workitems.WorkItem
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The `created` frame every ticket the engine files puts on the bus (engine/README.md, "Events"): published by
 * [TicketFiler] itself, on the caller's thread, so it is on the bus the moment `file` returns, before the delegate that
 * filed it returns and its command commits. That covers `FileTicketDelegate`, `FileUnlabeledTicketDelegate`,
 * `FileChildDelegate` and `FileProposalBuildDelegate`, which all file through it.
 */
class TicketFilerTest {
    private val bus = EngineEvents(jacksonObjectMapper())
    private val github = TicketRecorder()
    private val t0 = OffsetDateTime.parse("2026-10-05T12:50:57Z")
    /** What the row read saw: the tickets created when it ran, and the numbers it was asked for. */
    private val asked = mutableListOf<Pair<Long, Int>>()
    private var failing = false
    private val rows = BoardRows { repo, number ->
        asked += number to github.created.size
        check(!failing) { "GitHub 502" }
        BoardIssue(repo, number, "Make the widget blue", "lundmatt1", listOf("chore"), "https://github.com/$repo/issues/$number", t0, t0,
            kind = "chore", authorized = false, flow = null, pendingQuestion = null)
    }
    private val tickets = github.tickets()
    private val filer = TicketFiler(tickets, CreatedEvents(bus, rows))

    @Test
    fun `a filed ticket's board row is on the bus the moment file returns, read by number after the create`() = EventRecorder(bus).use { rec ->
        val created = filer.file("acme/widgets", "Make the widget blue", "body", "chore", null)
        val frame = rec.of("created").single()
        assertEquals(listOf("acme/widgets", created.number, created.url), listOf(frame.str("repo"), frame.long("number"), frame.str("url")))
        val issue = frame.data["issue"]
        assertEquals(listOf(created.number, "Make the widget blue", "chore"),
            listOf(issue["number"].asLong(), issue["title"].asString(), issue["kind"].asString()), "the row as a flow frame's issue has it")
        assertEquals(listOf(created.number to 1), asked, "one read, by the new issue's number, once GitHub had created it")
    }

    @Test
    fun `an unlabeled ticket is a triage work item, announced the same way`() = EventRecorder(bus).use { rec ->
        val created = filer.fileUnlabeled("acme/widgets", "Needs a secret", "body")
        assertEquals(created.number, rec.of("created").single().long("number"))
        val item = tickets.forTicket("acme/widgets", created.number)!!
        assertEquals(listOf(WorkItem.TRIAGE, "Needs a secret", "body"), listOf(item.kind, item.title, item.body))
        assertEquals(listOf(WorkItem.TRIAGE), item.labelSet, "no work kind, no authorized: nothing starts from it")
    }

    @Test
    fun `when the row cannot be read the frame still goes out, with issue null`() = EventRecorder(bus).use { rec ->
        failing = true
        val created = filer.file("acme/widgets", "Make the widget blue", "body", "chore", null)
        val frame = rec.of("created").single()
        assertEquals(created.number, frame.long("number"))
        assertTrue(frame.data["issue"].isNull, "issue: null, not left out")
    }

    @Test
    fun `the trailer says who asked, after the priority and before jev or triage, and askedByOf reads it`() {
        val plain = filer.body("## Goal\n\nBlue.", "intake i", 7, "p3", by = "alice")
        assertTrue(plain.endsWith("<!-- engine: intake i · tier 7 · p3 · by alice -->\n"), plain)
        assertEquals("alice", TicketFiler.askedByOf(plain))
        val jev = filer.body("## Goal", "intake i", 7, "p3", by = "alice", jev = "0f8fad5b-d9cb-469f-a165-70867728950e")
        assertTrue(jev.endsWith("<!-- engine: intake i · tier 7 · p3 · by alice · jev 0f8fad5b-d9cb-469f-a165-70867728950e -->\n"), jev)
        assertEquals("alice", TicketFiler.askedByOf(jev))
        val triage = filer.body("## Goal", "intake i", null, "p3", by = "a-b1", triage = true)
        assertTrue(triage.endsWith("<!-- engine: intake i · p3 · by a-b1 · triage -->\n"), triage)
        assertEquals("a-b1", TicketFiler.askedByOf(triage))
        assertTrue(TicketFiler.isTriage(triage), "`by` before `triage` does not hide the mark")
        val none = filer.body("## Goal", "intake i", 7, "p3")
        assertTrue(none.endsWith("<!-- engine: intake i · tier 7 · p3 -->\n"), none)
        assertEquals(null, TicketFiler.askedByOf(none), "a trailer from before `by`")
        assertEquals(none, filer.body("## Goal", "intake i", 7, "p3", by = " "), "a blank `by` is no `by`")
        assertEquals(null, TicketFiler.askedByOf("no trailer at all"))
        assertEquals(null, TicketFiler.askedByOf(null))
    }

    @Test
    fun `the trailer carries the story points after the tier, and pointsOf reads them`() {
        val pointed = filer.body("## Goal", "intake i", 7, "p3", by = "alice", points = 5)
        assertTrue(pointed.endsWith("<!-- engine: intake i · tier 7 · points 5 · p3 · by alice -->\n"), pointed)
        assertEquals(5, TicketFiler.pointsOf(pointed))
        assertEquals("alice", TicketFiler.askedByOf(pointed))
        assertEquals(null, TicketFiler.pointsOf(filer.body("## Goal", "intake i", 7, "p3")), "a trailer without points")
        assertEquals(null, TicketFiler.pointsOf(null))
    }

    @Test
    fun `the trailer carries a refactoring proposal's key after by, keyOf reads it, and extra labels follow the kind and mode`() {
        val keyed = filer.body("## Goal", "refactor job j1", null, "p3", by = "alice", key = "engine/jobs")
        assertTrue(keyed.endsWith("<!-- engine: refactor job j1 · p3 · by alice · key engine/jobs -->\n"), keyed)
        assertEquals("engine/jobs", TicketFiler.keyOf(keyed))
        assertEquals("alice", TicketFiler.askedByOf(keyed))
        assertEquals(null, TicketFiler.keyOf(filer.body("## Goal", "intake i", 7, "p3", by = "alice")), "a trailer without a key")
        assertEquals(null, TicketFiler.keyOf(null))
        assertEquals(listOf("feature", "feature:confirm", "refactor"), filer.labels("feature", "confirm", listOf("refactor")))
        assertEquals(listOf("feature", "feature:auto", "refactor"), filer.labels("feature", "auto", listOf("refactor", "feature")), "none twice")
        assertEquals(listOf("chore"), filer.labels("chore", "confirm"), "unchanged without extras")
    }

    @Test
    fun `a create the store refuses publishes nothing`() = EventRecorder(bus).use { rec ->
        val refusing = TicketRecorder().apply { onCreate = { error("store refused") } }
        runCatching { TicketFiler(refusing.tickets(), CreatedEvents(bus, rows)).file("acme/widgets", "t", "b", "chore", null) }
        assertEquals(emptyList(), rec.of("created"))
        assertEquals(emptyList(), asked)
    }

    @Test
    fun `a filed feature is a work item with its kind and mode as fields, and extra labels as its flags`() {
        val created = filer.file("acme/widgets", "Make the widget blue", "## Goal\n\nBlue.", "feature", "confirm", extraLabels = listOf("refactor"))
        val item = tickets.forTicket("acme/widgets", created.number)!!
        assertEquals(listOf("feature", "confirm", "Make the widget blue", "## Goal\n\nBlue."), listOf(item.kind, item.mode, item.title, item.body))
        assertTrue(item.flags.refactor); assertEquals("engine", item.author)
        assertEquals(listOf("feature", "feature:confirm", "refactor"), item.labelSet, "the labels it carried before")
        assertEquals("https://github.com/acme/widgets/issues/${created.number}", created.url)
    }

    @Test
    fun `a filed chore or bug has no mode, even when one is passed`() {
        val chore = tickets.forTicket("acme/widgets", filer.file("acme/widgets", "t", "b", "chore", "auto").number)!!
        assertEquals("chore" to null, chore.kind to chore.mode)
        val bug = tickets.forTicket("acme/widgets", filer.file("acme/widgets", "t", "b", "bug", null).number)!!
        assertEquals("bug" to null, bug.kind to bug.mode)
    }

    @Test
    fun `a refactor-scan ticket is a work item of that kind, and an unknown kind is refused`() {
        val created = filer.file("acme/widgets", "Scan", "b", WorkItem.REFACTOR_SCAN, null)
        val scan = tickets.open("acme/widgets").single()
        assertEquals(listOf(WorkItem.REFACTOR_SCAN, created.number), listOf(scan.kind, scan.ticket))
        assertEquals("https://github.com/acme/widgets/issues/${created.number}", created.url)
        assertFailsWith<IllegalArgumentException> { filer.file("acme/widgets", "t", "b", "epic", null) }
        assertEquals(1, tickets.open("acme/widgets").size, "nothing filed for the refused kind")
    }

    @Test
    fun `the filer takes no GitHub client, so a refactor-scan or triage ticket makes no GitHub create`() {
        val params = TicketFiler::class.java.constructors.flatMap { it.parameterTypes.toList() }.map { it.name }
        assertTrue(params.none { "github" in it.lowercase() }, params.toString())
        filer.file("acme/widgets", "Scan", "b", WorkItem.REFACTOR_SCAN, null)
        filer.fileUnlabeled("acme/widgets", "Needs a secret", "body")
        assertEquals(setOf(WorkItem.REFACTOR_SCAN, WorkItem.TRIAGE), tickets.open("acme/widgets").map { it.kind }.toSet())
    }
}

package com.codefrak.engine.friction

import com.codefrak.engine.github.ClosedIssue
import com.codefrak.engine.github.ClosingPull
import com.codefrak.engine.github.FakeIssueDetails
import com.codefrak.engine.github.IssueDetails
import com.codefrak.engine.github.IssueOrPull
import com.codefrak.engine.github.NamedIssue
import com.codefrak.engine.github.ReadFakes
import com.codefrak.engine.repos.TargetRepos
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [FrictionFixes] against H2 with GitHub faked: the link at filing, the sync and backfill, the state it follows, recurrence. */
@SpringBootTest
@Import(ReadFakes::class)
class FrictionFixesTest {
    @Autowired lateinit var fixes: FrictionFixes
    @Autowired lateinit var groups: FrictionGroups
    @Autowired lateinit var groupFixes: FrictionGroupFixes
    @Autowired lateinit var notes: FrictionNotes
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var details: FakeIssueDetails
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var intakes: FrictionIntakes

    /** Intake fates by id for [byIntake]; an id not here is unknown. */
    private val fates = mutableMapOf<String, IntakeFate>()
    private val byIntake by lazy { FrictionFixes(groups, groupFixes, repos, details) { fates[it] ?: IntakeFate.Unknown } }

    private val t0 = OffsetDateTime.parse("2026-10-01T10:00:00Z")
    private val merged = OffsetDateTime.parse("2026-10-06T12:00:00Z")

    private companion object {
        const val REPO = "acme/fixes"
    }

    @BeforeEach
    fun clear() {
        jdbc.sql("DELETE FROM engine.friction_note").update()
        jdbc.sql("DELETE FROM engine.friction_signature").update()
        jdbc.sql("DELETE FROM engine.friction_group").update()
        repos.remove(REPO); repos.add(REPO)
        details.named.clear(); details.closed.clear(); details.failing.clear(); details.byNumber.clear()
        fates.clear()
    }

    /** A group founded by a note `id` at [at]; answers its id. */
    private fun group(id: String, at: OffsetDateTime = t0): Long {
        val note = FrictionNote(id, REPO, 7, "run-$id", "root-$id", at, FrictionNote.AS_YOU_GO, "docs", "summary $id", "evidence $id unique $id", null)
        notes.insert(note)
        return groups.found(note, GroupedBy.NEW, null, at)
    }

    private fun join(groupId: Long, id: String, at: OffsetDateTime) {
        notes.insert(FrictionNote(id, REPO, 7, "run-$id", "root-$id", at, FrictionNote.AS_YOU_GO, "docs", "summary $id", "other $id", null))
        groups.join(id, groupId, GroupedBy.JEV, null)
    }

    private fun issue(n: Long, body: String, state: String = "OPEN", reason: String? = null, pulls: List<ClosingPull> = emptyList(),
                      labels: List<String> = emptyList()) = NamedIssue(n, body, state, reason, pulls, labels)

    @Test
    fun `a filed body links each named group as fix filed, and a group with no row sets nothing`() {
        val g1 = group("a"); val g2 = group("b")
        val linked = fixes.filed(REPO, 1300, "## Context\n\n- friction group g$g1 (11 notes, high), Friction Group g$g2, friction group g999999")
        assertEquals(listOf(g1, g2), linked)
        assertEquals(FrictionFix(g1, REPO, 1300, FrictionFix.FILED), groupFixes.fix(g1))
        assertEquals(FrictionFix.FILED, groups.get(g2)!!.state)
        assertFalse(groups.exists(999999))
        assertEquals(emptyList(), fixes.filed(REPO, 1301, "friction group g$g1"), "a group linked to another ticket is not relinked")
        assertEquals(1300L, groupFixes.fix(g1)!!.issue)
    }

    @Test
    fun `the sync is the backfill - first match wins, the second is logged, and each named group links`() {
        val g6 = group("six"); val g9 = group("nine"); val open = group("open")
        details.named[REPO] = mutableListOf(
            issue(1210, "Context: (friction group g$g6, g$g9)"),
            issue(1205, "Context: (friction group g$g6, 11 notes, high)"),
            issue(1220, "nothing to see"))
        assertEquals(2, fixes.sync().linked.size, "the fixture backfill links two groups")
        assertEquals(1205L, groupFixes.fix(g6)!!.issue, "the first ticket by number wins")
        assertEquals(1210L, groupFixes.fix(g9)!!.issue)
        assertNull(groupFixes.fix(open))
        assertEquals(0, fixes.sync().linked.size, "a second pass links nothing more")
    }

    @Test
    fun `an intake issue naming a group is not its fix - the refined ticket is, and an old link to the intake is dropped`() {
        val g6 = group("six"); val g9 = group("nine")
        groupFixes.setFix(g9, REPO, 1136, FrictionFix.FILED, null)   // what the first backfill did on 2026-10-06
        details.named[REPO] = mutableListOf(
            issue(1136, "friction group g$g6, g$g9", "CLOSED", "completed", labels = listOf("intake")),
            issue(1162, "Context: friction group g$g6, friction group g$g9", labels = listOf("chore")))
        assertEquals(2, fixes.sync().linked.size)
        assertEquals(1162L, groupFixes.fix(g6)!!.issue)
        assertEquals(1162L, groupFixes.fix(g9)!!.issue, "relinked from the intake to the ticket")
    }

    @Test
    fun `the sync follows the ticket - fixed with a merged PR, cleared when not planned, filed without a merge`() {
        val gFixed = group("f"); val gDropped = group("d"); val gUnmerged = group("u")
        details.named[REPO] = mutableListOf(
            issue(1, "friction group g$gFixed"), issue(2, "friction group g$gDropped"), issue(3, "friction group g$gUnmerged"))
        fixes.sync()
        assertEquals(listOf(FrictionFix.FILED, FrictionFix.FILED, FrictionFix.FILED), listOf(gFixed, gDropped, gUnmerged).map { groups.get(it)!!.state })
        details.named[REPO] = mutableListOf(
            issue(1, "friction group g$gFixed", "CLOSED", "COMPLETED".lowercase(), listOf(ClosingPull(10, "u", "MERGED", merged))),
            issue(2, "friction group g$gDropped", "CLOSED", "not_planned"),
            issue(3, "friction group g$gUnmerged", "CLOSED", "completed", listOf(ClosingPull(11, "u", "CLOSED"))))
        fixes.sync()
        val fixed = groupFixes.fix(gFixed)!!
        assertEquals(listOf(1L, FrictionFix.FIXED), listOf(fixed.issue, fixed.state))
        assertTrue(fixed.mergedAt!!.isEqual(merged), "the merge time is the PR's")
        assertNull(groupFixes.fix(gDropped), "not planned: open, link cleared")
        assertEquals("open", groups.get(gDropped)!!.state)
        assertEquals(FrictionFix.FILED, groupFixes.fix(gUnmerged)!!.state)
        fixes.sync()
        assertNull(groupFixes.fix(gDropped), "a not-planned ticket is not linked again")
    }

    @Test
    fun `a user's link follows the repo's closed issues, and a cleared link is not relinked by the sync`() {
        val g = group("user")
        fixes.link(g, REPO, 77, "someone")
        details.closed[REPO] = mutableListOf(ClosedIssue(77, "t", "u", emptyList(), merged, "completed", listOf(ClosingPull(12, "u", "MERGED", merged))))
        fixes.sync()
        assertEquals(FrictionFix.FIXED, groups.get(g)!!.state)
        details.named[REPO] = mutableListOf(issue(77, "friction group g$g", "CLOSED", "completed", listOf(ClosingPull(12, "u", "MERGED", merged))))
        fixes.clear(g, "someone")
        fixes.sync()
        assertNull(groupFixes.fix(g), "cleared by a user: the sync leaves it open")
        fixes.link(g, REPO, 77, "someone")
        assertEquals(FrictionFix.FILED, groupFixes.fix(g)!!.state, "a user may link it again")
    }

    @Test
    fun `a link outside the newest closed issues is read by number - an old closed issue or a merged PR is fixed, an unmerged PR reopens`() {
        val old = group("old"); val pr = group("pr"); val shut = group("shut"); val waiting = group("waiting")
        fixes.link(old, REPO, 972, "someone"); fixes.link(pr, REPO, 1127, "someone")
        fixes.link(shut, REPO, 1642, "someone"); fixes.link(waiting, REPO, 1700, "someone")
        val closedAt = merged.minusDays(2) // after the notes at t0, so the group reads fixed, not recurred
        details.closed[REPO] = mutableListOf(ClosedIssue(1, "newer", "u", emptyList(), merged, "completed"))
        details.byNumber[REPO to 972L] = IssueOrPull(972, false, "CLOSED", "completed", closedAt)
        details.byNumber[REPO to 1127L] = IssueOrPull(1127, true, "MERGED", closedAt = merged, mergedAt = merged)
        details.byNumber[REPO to 1642L] = IssueOrPull(1642, true, "CLOSED", closedAt = merged)
        val report = fixes.sync()
        assertEquals(FrictionFix.FIXED, groups.get(old)!!.state, "an old issue closed as completed is fixed")
        assertTrue(groupFixes.fix(old)!!.mergedAt!!.isEqual(closedAt), "at its closedAt")
        assertEquals(FrictionFix.FIXED, groups.get(pr)!!.state, "a merged PR is fixed")
        assertTrue(groupFixes.fix(pr)!!.mergedAt!!.isEqual(merged), "at its mergedAt")
        assertNull(groupFixes.fix(shut), "a PR closed unmerged returns the group to open")
        assertEquals(FrictionFix.OPEN, groups.get(shut)!!.state)
        assertEquals(FrictionFix.FILED, groups.get(waiting)!!.state, "an open issue stays fix filed")
        assertEquals(setOf(old to FrictionFix.FIXED, pr to FrictionFix.FIXED, shut to null),
            report.changed.map { it.groupId to it.to }.toSet())
        assertEquals(emptyList(), fixes.sync().changed, "the next pass changes nothing")
    }

    @Test
    fun `a note after the merge marks a fixed group recurred and keeps the link, a note dated before does not`() {
        val g = group("r", t0)
        fixes.link(g, REPO, 5, "someone")
        details.named[REPO] = mutableListOf(issue(5, "friction group g$g", "CLOSED", "completed", listOf(ClosingPull(13, "u", "MERGED", merged))))
        fixes.sync()
        join(g, "late", merged.minusHours(2))
        assertEquals(FrictionFix.FIXED, groups.get(g)!!.state, "a late grouping of an older note does not recur")
        join(g, "after", merged.plusHours(3))
        val fix = groupFixes.fix(g)!!
        assertTrue(fix.recurredAt!!.isEqual(merged.plusHours(3)))
        assertEquals(FrictionFix.RECURRED, groups.get(g)!!.state)
        assertEquals(5L, fix.issue, "a recurred group keeps its link")
        fixes.sync()
        assertEquals(FrictionFix.RECURRED, groups.get(g)!!.state, "the next sync keeps the recurrence")
    }

    @Test
    fun `the report names what the pass linked, changed and skipped, with the reasons it logs`() {
        val gA = group("ra"); val gB = group("rb"); val gC = group("rc")
        details.named[REPO] = mutableListOf(
            issue(1, "friction group g$gA and friction group g999999"), issue(2, "friction group g$gA, g$gB"), issue(3, "friction group g$gC"))
        val first = fixes.sync()
        assertEquals(listOf(FixSyncReport.Linked(gA, REPO, 1, FrictionFix.FILED), FixSyncReport.Linked(gB, REPO, 2, FrictionFix.FILED),
            FixSyncReport.Linked(gC, REPO, 3, FrictionFix.FILED)), first.linked)
        assertEquals(emptyList(), first.changed); assertEquals(emptyList(), first.recurred)
        assertEquals(listOf(
            FixSyncReport.Skipped(999999, REPO, 1, "names g999999, which has no row"),
            FixSyncReport.Skipped(gA, REPO, 2, "names g$gA, already fixed by $REPO#1; not relinked")),
            first.skipped)

        details.named[REPO] = mutableListOf(
            issue(1, "friction group g$gA", "CLOSED", "completed", listOf(ClosingPull(10, "u", "MERGED", merged))),
            issue(2, "friction group g$gB", "CLOSED", "not_planned"),
            issue(3, "friction group g$gC", "CLOSED", "completed", listOf(ClosingPull(11, "u", "CLOSED"))))
        val second = fixes.sync()
        assertEquals(emptyList(), second.linked)
        assertEquals(2, second.changed.size, second.toString())
        val fixed = second.changed.single { it.groupId == gA }
        assertEquals(listOf<Any?>(REPO, 1L, FrictionFix.FILED, FrictionFix.FIXED), listOf(fixed.repo, fixed.issue, fixed.from, fixed.to))
        assertTrue(fixed.mergedAt!!.isEqual(merged))
        assertEquals(FixSyncReport.Changed(gB, REPO, 2, FrictionFix.FILED, null, null), second.changed.single { it.groupId == gB })
        val unmerged = second.skipped.single()
        assertEquals(listOf<Any?>(gC, REPO, 3L), listOf(unmerged.groupId, unmerged.repo, unmerged.issue))
        assertTrue("no merged PR" in unmerged.reason, unmerged.reason)
        assertEquals(merged.toInstant().toString(), (second.json()["changed"] as List<*>).map { it as Map<*, *> }.single { it["groupId"] == gA }["mergedAt"])
    }

    @Test
    fun `a repo GitHub cannot read is skipped, not an error`() {
        val g = group("down")
        fixes.link(g, "acme/elsewhere", 4, "someone")
        details.failing += REPO; details.failing += "acme/elsewhere"
        val report = fixes.syncNow()
        assertEquals(listOf(REPO to "cannot search the repo's issues: GitHub 502", "acme/elsewhere" to "cannot read the repo's closed issues: GitHub 502"),
            report.skipped.filter { it.repo == REPO || it.repo == "acme/elsewhere" }.map { it.repo to it.reason })
    }

    @Test
    fun `a second pass asked for while one runs is refused at once, and the mark is released after a pass that throws`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var fail = false
        val blocking = object : IssueDetails by details {
            override fun issuesNaming(repo: String, phrase: String): List<NamedIssue> {
                // A list that throws when read: the throw comes after sync's runCatching around the search, so the pass does not catch it.
                if (fail) return object : AbstractList<NamedIssue>() {
                    override val size = 1
                    override fun get(index: Int): NamedIssue = throw IllegalStateException("boom")
                }
                entered.countDown(); release.await(10, TimeUnit.SECONDS)
                return details.issuesNaming(repo, phrase)
            }
        }
        val slow = FrictionFixes(groups, groupFixes, repos, blocking) { IntakeFate.Unknown }
        val g = group("slow")
        details.named[REPO] = mutableListOf(issue(1, "friction group g$g"))
        val pool = Executors.newSingleThreadExecutor()
        try {
            val first = pool.submit<FixSyncReport> { slow.syncNow() }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertFailsWith<FixSyncRunning> { slow.syncNow() }
            release.countDown()
            assertEquals(listOf(g), first.get(10, TimeUnit.SECONDS).linked.map { it.groupId })
        } finally { release.countDown(); pool.shutdownNow() }
        fail = true
        assertEquals("boom", assertFailsWith<IllegalStateException> { slow.syncNow() }.message)
        fail = false
        release.countDown()
        assertEquals(emptyList(), slow.syncNow().linked, "the pass after one that threw runs: the mark was released")
    }

    @Test
    fun `an intake links a group or a note's group as pending, and filing resolves it to fix filed`() {
        val g = group("pa"); val h = group("pb")
        fates["i-1"] = IntakeFate.Running(REPO)
        val pending = byIntake.linkIntake(g, null, "i-1", "someone")
        assertEquals(FrictionFix.PENDING, pending.state)
        assertNull(pending.fix)
        assertEquals(PendingFix(g, "i-1", REPO), pending.pending)
        assertEquals(mapOf("intake" to "i-1", "repo" to REPO, "issue" to null, "state" to "fix-pending"), pending.json { emptyMap() }["fix"])
        assertEquals(PendingFix(h, "i-1", REPO), byIntake.linkIntake(null, "pb", "i-1", "smith run r").pending, "by note: its group")
        assertEquals(listOf(g, h), byIntake.intakeFiled("i-1", REPO, 1400).sorted())
        for (id in listOf(g, h)) {
            assertEquals(FrictionFix(id, REPO, 1400, FrictionFix.FILED), groupFixes.fix(id))
            assertNull(groupFixes.pending(id))
        }
        assertEquals(emptyList(), byIntake.intakeFiled("i-other", REPO, 1401))
    }

    @Test
    fun `unknown group, note or intake, an ended intake and a group with a ticket are refused`() {
        val g = group("ra")
        fates["i-run"] = IntakeFate.Running(REPO); fates["i-end"] = IntakeFate.Ended
        assertTrue(assertFailsWith<FixIntakeRefused> { byIntake.linkIntake(999999, null, "i-run", "x") }.notFound)
        assertTrue(assertFailsWith<FixIntakeRefused> { byIntake.linkIntake(null, "no-such-note", "i-run", "x") }.notFound)
        val unknown = assertFailsWith<FixIntakeRefused> { byIntake.linkIntake(g, null, "i-none", "x") }
        assertTrue(unknown.notFound); assertEquals("no intake i-none", unknown.message)
        assertFalse(assertFailsWith<FixIntakeRefused> { byIntake.linkIntake(g, null, "i-end", "x") }.notFound)
        groupFixes.setFix(g, REPO, 1500, FrictionFix.FILED, null)
        val taken = assertFailsWith<FixIntakeRefused> { byIntake.linkIntake(g, null, "i-run", "x") }
        assertFalse(taken.notFound); assertTrue(taken.message!!.contains("already fixed by $REPO#1500"), taken.message)
        assertEquals(1500L, groupFixes.fix(g)!!.issue, "the ticket link stays")
        assertNull(groupFixes.pending(g))
        fates["i-filed"] = IntakeFate.Filed(REPO, 1600)
        val other = group("rb")
        assertEquals(FrictionFix(other, REPO, 1600, FrictionFix.FILED), byIntake.linkIntake(other, null, "i-filed", "x").fix, "an intake that filed links its ticket at once")
        assertEquals(IntakeFate.Unknown, intakes.of("00000000-0000-0000-0000-000000000000"), "no such intake in the engine's history")
    }

    @Test
    fun `the sync resolves an intake filed but missed, clears one ended without a ticket, and clear removes a pending link`() {
        val a = group("sa"); val b = group("sb"); val c = group("sc"); val d = group("sd")
        for ((g, i) in listOf(a to "i-a", b to "i-b", c to "i-c", d to "i-d")) { fates[i] = IntakeFate.Running(REPO); byIntake.linkIntake(g, null, i, "x") }
        fates["i-a"] = IntakeFate.Filed(REPO, 1700); fates["i-b"] = IntakeFate.Ended
        val report = byIntake.sync()
        assertEquals(FixSyncReport.Linked(a, REPO, 1700, FrictionFix.FILED), report.linked.single { it.groupId == a })
        assertEquals(FixSyncReport.Changed(b, REPO, null, FrictionFix.PENDING, null, null), report.changed.single { it.groupId == b })
        assertEquals(FrictionFix.FILED, groups.get(a)!!.state)
        assertEquals(FrictionFix.OPEN, groups.get(b)!!.state)
        assertEquals(FrictionFix.PENDING, groups.get(c)!!.state, "a running intake waits")
        assertEquals(FrictionFix.OPEN, byIntake.clear(c, "x")!!.state)
        assertNull(groupFixes.pending(c))
        assertEquals(listOf(d), groups.list(FrictionFilter(state = FrictionFix.PENDING)).map { it.id })
    }
}

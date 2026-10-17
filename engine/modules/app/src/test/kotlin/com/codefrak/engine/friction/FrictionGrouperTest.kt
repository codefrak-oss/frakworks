package com.codefrak.engine.friction

import com.codefrak.engine.intake.tickets
import com.codefrak.engine.github.FakeIssueDetails
import com.codefrak.engine.github.ReadFakes
import com.codefrak.engine.intake.TicketRecorder
import com.codefrak.engine.jev.FakeJevDecider
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.Brief
import org.operaton.bpm.engine.RuntimeService
import org.springframework.context.annotation.Import
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The friction grouper (engine/README.md, "Friction notes") against H2, with Jev faked: the design's
 * trace, the retry backoff, the give-up, no jev account, the stages past 254 groups, and a re-drain after a rolled-back
 * group write. The pokes are off (`engine.friction.group-enabled=false` in the test properties), so each test drains
 * by hand on its own thread, with a clock of its own.
 */
@SpringBootTest
@Import(ReadFakes::class)
class FrictionGrouperTest {
    @TestConfiguration
    class Fakes {
        @Bean @Primary fun jev(calls: JevCalls) = FakeJevDecider(calls)
        @Bean @Primary fun github() = TicketRecorder()
        @Bean @Primary fun tickets(github: com.codefrak.engine.intake.TicketRecorder) = github.tickets()
    }

    @Autowired lateinit var bugs: FrictionBugs
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var github: TicketRecorder
    @Autowired lateinit var tickets: com.codefrak.engine.workitems.Tickets
    @Autowired lateinit var details: FakeIssueDetails

    @Autowired lateinit var notes: FrictionNotes
    @Autowired lateinit var groups: FrictionGroups
    @Autowired lateinit var groupBugs: FrictionGroupBugs
    @Autowired lateinit var grouper: FrictionGrouper
    @Autowired lateinit var jev: FakeJevDecider
    @Autowired lateinit var calls: JevCalls
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var transactions: PlatformTransactionManager

    private val t0 = OffsetDateTime.parse("2026-10-04T18:49:35Z")

    @BeforeEach
    fun clear() {
        jdbc.sql("DELETE FROM engine.friction_note").update()
        jdbc.sql("DELETE FROM engine.friction_signature").update()
        jdbc.sql("DELETE FROM engine.friction_group").update()
        jdbc.sql("DELETE FROM engine.jev_call").update()
        jev.reset()
        runtime.createProcessInstanceQuery().processDefinitionKey("intake").list().forEach { runtime.deleteProcessInstance(it.id, "test reset") }
        listOf(ON, OFF, DOCTOR_ONLY).forEach { repos.remove(it); repos.add(it) }
        repos.setFrictionBugs(ON, true)
        repos.setDoctorBugs(DOCTOR_ONLY, true, false)
        github.comments.clear(); details.closed.clear()
    }

    private companion object {
        const val ON = "acme/friction-on"
        const val OFF = "acme/friction-off"
        const val DOCTOR_ONLY = "acme/friction-doctor"
    }

    private fun intakes() = runtime.createProcessInstanceQuery().processDefinitionKey("intake").list()
    private fun bug(group: Long) = groupBugs.bug(group)?.let { listOf(it.repo, it.issue, it.state) }
    private fun twoThings(id: String) = stored(id).let { it.twoThings to it.twoThingsCallId }

    private fun note(id: String, at: OffsetDateTime, summary: String, evidence: String?, issue: Long = 905, kind: String = "tooling", repo: String? = "acme/frakworks") =
        FrictionNote(id, repo, issue, "run-$id", "root-$id", at, FrictionNote.AS_YOU_GO, kind, summary, evidence, null)

    private fun stored(id: String): FrictionNote = notes.list(FrictionFilter(limit = 1000)).single { it.id == id }
    private fun signatures(group: Long): Set<String> =
        jdbc.sql("SELECT signature FROM engine.friction_signature WHERE group_id = :g").param("g", group).query(String::class.java).list().filterNotNull().toSet()
    private fun retry(id: String): Pair<Int, OffsetDateTime?> =
        jdbc.sql("SELECT group_attempts, next_attempt_at FROM engine.friction_note WHERE id = :id").param("id", id)
            .query { rs, _ -> rs.getInt(1) to rs.getObject(2, OffsetDateTime::class.java) }.single()
    private fun actions() = jev.ids.map { calls.get(it)!!.action }
    private fun groupActions() = jev.groupIds.map { calls.get(it)!!.action }
    @Suppress("UNCHECKED_CAST")
    private fun groupOptionsOf(call: Int) = optionsOf(jev.asked.indices.filter { jev.asked[it].first != FrictionGroupCheck.TWO_THINGS_USE_CASE }[call])
    private fun optionsOf(call: Int) = (jev.questionSets[call].getValue(FrictionGroupCheck.QUESTION).criteria as Map<String, String?>).keys.toList()

    // The proposal's trace (§2, "The trace"): four notes about bots/check-acceptance-criteria, in the order they were written.
    private val a = note("44ead9b1", t0,
        "bots/check-acceptance-criteria is not executable in the sandbox clone (Permission denied), and its docstring still names the removed dispatch/ paths; it runs fine as `python3 bots/check-acceptance-criteria FILE`.",
        "\$ bots/check-acceptance-criteria /work/out/body.md\nbash: bots/check-acceptance-criteria: Permission denied")
    private val c = note("385549d4", OffsetDateTime.parse("2026-10-04T19:00:02Z"),
        "bots/check-acceptance-criteria ends the Acceptance criteria section at the first `###` sub-heading, so criteria under sub-headings are reported as none",
        "python3 bots/check-acceptance-criteria /work/out/body.md -> \"The `## Acceptance criteria` section has no checklist items.\" with 4 `- [ ]` items present under ### sub-headings", issue = 910)
    private val a2 = note("d6a709d6", OffsetDateTime.parse("2026-10-04T20:31:38Z"),
        "bots/check-acceptance-criteria is not executable in the sandbox clone; smith.md tells the refiner to run it directly",
        "\$ bots/check-acceptance-criteria /work/out/body.md\n/bin/sh: Permission denied", issue = 917)
    private val b = note("2d9c470c", OffsetDateTime.parse("2026-10-04T20:46:21Z"),
        "bots/check-acceptance-criteria is not executable, and its docstring still points to the removed dispatch/ paths",
        "/work/repo/bots/check-acceptance-criteria /tmp/body.md -> \"Permission denied\" (exit 126); `bash` on it fails since it is Python (shebang python3). Works as python3 bots/check-acceptance-criteria", issue = 920)

    @Test
    fun `the trace - (a) founds G1, (c) founds G2 by jev's new, (a') joins G1 by its key, (b) joins G1 by jev and adds its key`() {
        val now = OffsetDateTime.parse("2026-10-04T21:00:00Z")
        notes.insert(a)
        assertEquals(1, grouper.drain(now))
        val g1 = assertNotNull(stored(a.id).groupId)
        assertEquals("new", stored(a.id).groupedBy)
        assertNull(stored(a.id).jevCallId, "no groups yet: no call")
        assertEquals(a.summary.take(240), groups.list(FrictionFilter()).single().title)

        jev.choice = "new" to 0.9
        notes.insert(c)
        grouper.drain(now)
        val g2 = assertNotNull(stored(c.id).groupId)
        assertTrue(g2 != g1)
        assertEquals("new", stored(c.id).groupedBy)
        assertEquals(jev.groupIds[0], stored(c.id).jevCallId)
        assertEquals(listOf("g$g1", "new"), groupOptionsOf(0))

        notes.insert(a2)
        grouper.drain(now)
        assertEquals(g1 to "exact", stored(a2.id).let { it.groupId to it.groupedBy })
        assertEquals(1, jev.groupAsked.size, "a known key makes no call")

        jev.choice = "g$g1" to 0.91
        notes.insert(b)
        grouper.drain(now)
        assertEquals(g1 to "jev", stored(b.id).let { it.groupId to it.groupedBy })
        assertEquals(jev.groupIds[1], stored(b.id).jevCallId)
        assertEquals(listOf("g$g1", "g$g2", "new"), groupOptionsOf(1), "G1's newest member (a') is newer than G2's")

        assertEquals(2, jev.groupAsked.size, "two group calls for four notes")
        assertTrue(jev.groupAsked.all { it.first == FrictionGroupCheck.USE_CASE })
        assertEquals(setOf(FrictionKey.of(a.summary, a.evidence), FrictionKey.of(b.summary, b.evidence)), signatures(g1), "K(b) added to G1")
        assertEquals(setOf(FrictionKey.of(c.summary, c.evidence)), signatures(g2))
        assertEquals(listOf("new (0.90): new group g$g2", "g$g1 (0.91 vs new 0.09): joined"), groupActions())
        assertTrue(jev.groupIds.all { calls.get(it)!!.threshold == 2.0 }, "every call row gets the beats-new ratio")
        @Suppress("UNCHECKED_CAST")
        val state = jev.groupAsked[1].third as Map<String, Any?>
        assertEquals(listOf("tooling", "as-you-go", "acme/frakworks", b.summary, b.evidence), listOf("kind", "source", "repo", "summary", "evidence").map { state[it] })
        assertEquals(0, groups.ungrouped())
        assertEquals(2, groups.count())
    }

    @Test
    fun `a pick that does not clearly beat new founds a group with the call, and the action says so`() {
        notes.insert(a); grouper.drain(t0)
        val g1 = stored(a.id).groupId!!
        jev.choice = "g$g1" to 0.64
        notes.insert(c); grouper.drain(t0)
        val g2 = stored(c.id).groupId!!
        assertTrue(g2 != g1)
        assertEquals("new" to jev.groupIds[0], stored(c.id).let { it.groupedBy to it.jevCallId })
        assertEquals(listOf("g$g1 (0.64 vs new 0.36): new group g$g2"), groupActions())
    }

    @Test
    fun `no answer backs off 1, 5, 15, 60 minutes then hourly, and after the give-up the note founds its own group as fallback`() {
        notes.insert(a); grouper.drain(t0)
        jev.choice = null
        notes.insert(c)
        var now = c.notedAt
        val waits = listOf(1L, 5L, 15L, 60L, 60L, 60L)
        for ((i, minutes) in waits.withIndex()) {
            assertEquals(1, grouper.drain(now), "attempt ${i + 1} is due")
            assertNull(stored(c.id).groupId)
            val (attempts, next) = retry(c.id)
            assertEquals(i + 1, attempts)
            assertTrue(assertNotNull(next).isEqual(now.plusMinutes(minutes)), "after attempt ${i + 1}: $next")
            assertEquals(0, grouper.drain(now.plusMinutes(minutes - 1)), "not due before its backoff has passed")
            now = next
        }
        assertEquals(waits.size, jev.groupAsked.size)
        assertTrue(groupActions().all { it == "no answer: left ungrouped" }, groupActions().toString())

        val late = c.notedAt.plus(Duration.ofHours(24))
        grouper.drain(late)
        val own = assertNotNull(stored(c.id).groupId)
        assertTrue(own != stored(a.id).groupId)
        assertEquals("fallback" to jev.groupIds.last(), stored(c.id).let { it.groupedBy to it.jevCallId })
        assertEquals("no answer: new group g$own", groupActions().last())
        assertEquals(setOf(FrictionKey.of(c.summary, c.evidence)), signatures(own))
        assertNull(retry(c.id).second, "no retry once grouped")
    }

    @Test
    fun `no usable jev account founds the note's own group at once as no-jev`() {
        notes.insert(a); grouper.drain(t0)
        jev.noAccount = true
        notes.insert(c); grouper.drain(c.notedAt)
        val own = assertNotNull(stored(c.id).groupId)
        assertEquals("no-jev" to jev.groupIds.single(), stored(c.id).let { it.groupedBy to it.jevCallId })
        assertEquals(0 to null, retry(c.id))
        assertEquals(listOf("no answer: new group g$own"), groupActions())
        assertEquals("no usable jev account", calls.get(jev.groupIds.single())!!.error)
    }

    @Test
    fun `past 254 groups the check runs in stages, and a stage-1 no answer leaves the note ungrouped`() {
        // Each seed's key must differ: digits normalise to `#`, so the number is spelled in letters.
        fun letters(i: Int) = i.toString().map { 'a' + it.digitToInt() }.joinToString("")
        (1..255).forEach { i ->
            val n = note("seed$i", t0.plusSeconds(i.toLong()), "seed $i", "seed evidence ${letters(i)}")
            notes.insert(n)
            groups.found(stored(n.id), "new", null, n.notedAt)
        }
        assertEquals(255, groups.count())
        val newest = groups.options().first().id
        jev.choice = "g$newest" to 0.6
        notes.insert(c)
        grouper.drain(t0.plusHours(1))
        assertEquals(3, jev.groupAsked.size, "two stage-1 batches, then stage 2")
        assertEquals(254, groupOptionsOf(0).size - 1); assertEquals("g$newest", groupOptionsOf(0).first(), "newest member first")
        assertEquals(1, groupOptionsOf(1).size - 1)
        assertEquals(listOf("g$newest", "new"), groupOptionsOf(2))
        val founded = stored(c.id).groupId!!
        assertEquals("new" to jev.groupIds[2], stored(c.id).let { it.groupedBy to it.jevCallId })
        assertEquals(listOf("stage 1 (1/2) g$newest (0.60): to stage 2", "stage 1 (2/2) g$newest (0.60): out",
            "stage 2 g$newest (0.60 vs new 0.40): new group g$founded"), groupActions())

        jev.reset()
        notes.insert(b)
        grouper.drain(t0.plusHours(1))
        assertEquals(1, jev.groupAsked.size, "the first batch's no answer ends the check")
        assertNull(stored(b.id).groupId)
        assertEquals(1, retry(b.id).first)
        assertEquals(listOf("stage 1 (1/2) no answer: left ungrouped"), groupActions())
    }

    /** A FrictionGroups whose next group write throws after writing, so the transaction around it rolls back. */
    class FailingGroups(jdbc: JdbcClient) : FrictionGroups(jdbc, FrictionGroupFixes(jdbc)) {
        var failNext = false
        override fun found(note: FrictionNote, by: String, call: String?, now: OffsetDateTime): Long {
            val id = super.found(note, by, call, now)
            if (failNext) { failNext = false; error("the group write failed after the call") }
            return id
        }
    }

    @Test
    fun `a group write that rolls back after jev answered leaves the call and no group, and the re-drain makes one group with a second call`() {
        notes.insert(a); grouper.drain(t0)
        val failing = FailingGroups(jdbc)
        val own = FrictionGrouper(failing, jev, calls, bugs, transactions)
        jev.choice = "new" to 0.9
        notes.insert(c)
        failing.failNext = true
        assertEquals(1, own.drain(t0.plusHours(1)), "the failure is logged; the drain goes on")
        assertNull(stored(c.id).groupId, "rolled back")
        assertEquals(1, groups.count(), "no group from the rolled-back write")
        assertEquals(1, jev.groupAsked.size, "the call row stays: it was made outside the transaction")

        own.drain(t0.plusHours(1))
        assertEquals(2, groups.count(), "one group, not two")
        assertEquals("new" to jev.groupIds[1], stored(c.id).let { it.groupedBy to it.jevCallId })
        assertEquals(2, jev.groupAsked.size, "one duplicate row in the audit")
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM engine.friction_signature WHERE signature = :s").param("s", FrictionKey.of(c.summary, c.evidence))
            .query(Int::class.javaObjectType).single())
    }

    @Test
    fun `a failing note rolls back alone and the next proceeds`() {
        notes.insert(a); grouper.drain(t0)
        val failing = FailingGroups(jdbc)
        val own = FrictionGrouper(failing, jev, calls, bugs, transactions)
        jev.choice = "new" to 0.9
        notes.insert(c); notes.insert(b)
        failing.failNext = true
        assertEquals(2, own.drain(t0.plusHours(3)))
        assertNull(stored(c.id).groupId)
        assertNotNull(stored(b.id).groupId)
        assertEquals("new", stored(b.id).groupedBy)
    }

    private fun backdate(call: String, to: OffsetDateTime) =
        jdbc.sql("UPDATE engine.jev_call SET called_at = :t WHERE id = :id").param("t", to).param("id", call).update()

    @Test
    fun `the sweep at the end of a drain writes kept on 30-day-old friction-group calls whose note still sits where the call put it`() {
        notes.insert(a); grouper.drain(t0)
        val g1 = stored(a.id).groupId!!
        jev.choice = "new" to 0.9
        notes.insert(c); grouper.drain(t0.plusMinutes(1))
        val g2 = stored(c.id).groupId!!
        val founder = jev.groupIds[0]
        jev.choice = "g$g1" to 0.91
        notes.insert(b); grouper.drain(t0.plusMinutes(2))
        val joined = jev.groupIds[1]
        jev.choice = "g$g2" to 0.86
        val d = note("d", t0.plusMinutes(3), "a fourth thing", "fourth evidence")
        notes.insert(d); grouper.drain(t0.plusMinutes(3))
        val moved = jev.groupIds[2]
        assertEquals(g2, stored(d.id).groupId)
        // d is pulled out by a user before the sweep: its call is judged by that, not by the sweep. (The store's primitives, not the endpoint.)
        val g3 = groups.create(d.summary, t0.plusMinutes(4))
        groups.join(d.id, g3, GroupedBy.USER, moved, "someone")
        // c's founder call is the one `new` row the sweep would confirm; an unrelated use case's call is never touched.
        val other = calls.record("intake-duplicate", com.codefrak.engine.jev.JevContext(null, "acme/frakworks", 1), null, "jev-1.13.0", "{}", null, null, 1, 0.8)
        listOf(founder, joined, moved, other).forEach { backdate(it, t0) }

        assertEquals(0, grouper.drain(t0.plusDays(30).minusSeconds(1)), "nothing due")
        assertTrue(jev.groupIds.all { calls.get(it)!!.outcome == null }, "not 30 days old yet")

        grouper.drain(t0.plusDays(30))
        assertEquals("new g$g2: kept", calls.get(founder)!!.outcome)
        assertEquals("joined g$g1: kept", calls.get(joined)!!.outcome)
        assertNull(calls.get(moved)!!.outcome, "a note a user moved is not the sweep's")
        assertNull(calls.get(other)!!.outcome)

        // A note moved by a merge: its call names a group it is no longer in, so the sweep leaves it; a second sweep rewrites nothing.
        jev.choice = "g$g2" to 0.9
        val e = note("e", t0.plusMinutes(5), "a fifth thing", "fifth evidence")
        notes.insert(e); grouper.drain(t0.plusMinutes(5))
        val late = jev.groupIds[3]
        backdate(late, t0)
        groups.merge(g2, g1)
        assertEquals(g1, stored(e.id).groupId)
        assertEquals(0, grouper.sweep(t0.plusDays(31)))
        assertNull(calls.get(late)!!.outcome)
        assertEquals("joined g$g1: kept", calls.get(joined)!!.outcome)
        assertEquals(listOf("g$g2", "g$g1", "g$g2"), listOf(FrictionGroupCheck.placed(calls.get(founder)!!.action), FrictionGroupCheck.placed(calls.get(joined)!!.action),
            FrictionGroupCheck.placed(calls.get(late)!!.action)))
    }

    @Test
    fun `wake does nothing while the pokes are off, so the tests own the drain`() {
        notes.insert(a)
        grouper.wake()
        grouper.settle()
        assertNull(stored(a.id).groupId)
        assertEquals(FrictionGrouper.backoff(1), Duration.ofMinutes(1))
        assertEquals(FrictionGrouper.backoff(4), Duration.ofHours(1))
        assertEquals(FrictionGrouper.backoff(9), Duration.ofHours(1))
    }

    // ---- two things ----

    @Test
    fun `the trace with a noul - (a) is marked by a lone call and files one bug for G1, (a') adds nothing, (b)'s group call marks and comments`() {
        val now = OffsetDateTime.parse("2026-10-04T21:00:00Z")
        jev.noul = 0.86
        notes.insert(a.copy(repo = ON)); grouper.drain(now)
        val g1 = stored(a.id).groupId!!
        assertEquals(1, jev.asked.size, "the first founder makes no group call: the noul goes alone")
        assertEquals(FrictionGroupCheck.TWO_THINGS_USE_CASE, jev.asked[0].first)
        assertEquals(listOf(FrictionGroupCheck.TWO_THINGS), jev.questionSets[0].keys.toList())
        assertEquals("noul", jev.questionSets[0].getValue(FrictionGroupCheck.TWO_THINGS).type)
        assertEquals(0.86 to jev.ids[0], twoThings(a.id))
        assertEquals("marked; two things (0.86)", actions()[0])
        assertEquals(0.8, calls.get(jev.ids[0])!!.threshold)
        assertEquals(listOf(ON, null, "filing"), bug(g1), "the first mark claims G1's bug")
        val intake = intakes().single()
        val vars = runtime.getVariables(intake.id)
        assertEquals(listOf(ON, "friction", "friction", g1, false), listOf(vars["repo"], vars["submittedBy"], vars["authorizedByEngine"], vars["frictionBug"], vars["dryRun"]))
        val ask = Brief.text(vars["ask"])!!
        assertTrue(ask.startsWith("Friction note carries two things: ${a.summary}\nKind: bug\n"), ask)
        assertTrue(a.id in ask && "g$g1" in ask && "https://github.com/$ON/issues/905" in ask && "run-${a.id}" in ask && a.evidence!! in ask, ask)
        assertTrue("why did this run write two things in one friction note" in ask && "`bots/smith.md`, \"Friction notes\": one thing per note; a second thing is a second note." in ask, ask)
        assertEquals(intake.id, jdbc.sql("SELECT bug_intake_id FROM engine.friction_group WHERE id = :g").param("g", g1).query(String::class.java).single())

        // (a') joins by its key: a lone noul below the floor writes the call and no mark, and files nothing.
        jev.noul = 0.4
        notes.insert(a2.copy(repo = ON)); grouper.drain(now)
        assertEquals(g1 to "exact", stored(a2.id).let { it.groupId to it.groupedBy })
        assertEquals(null to null, twoThings(a2.id))
        assertEquals("no mark", actions()[1])
        assertEquals(1, intakes().size)

        // (b) makes a group call carrying both questions; its mark comments on the open bug once the delegates wrote its number.
        bugs.filed(g1, 321)
        assertEquals(listOf(ON, 321L, "open"), bug(g1))
        jev.choice = "g$g1" to 0.91; jev.noul = 0.9
        notes.insert(b.copy(repo = ON)); grouper.drain(now)
        assertEquals(g1 to "jev", stored(b.id).let { it.groupId to it.groupedBy })
        assertEquals(setOf(FrictionGroupCheck.QUESTION, FrictionGroupCheck.TWO_THINGS), jev.questionSets[2].keys)
        assertEquals(0.9 to jev.ids[2], twoThings(b.id))
        assertEquals(jev.ids[2], stored(b.id).jevCallId, "one call for both")
        assertEquals("g$g1 (0.91 vs new 0.09): joined; two things (0.90)", actions()[2])
        assertEquals(1, intakes().size, "one bug per group")
        val comment = github.comments.filter { "Authorized by" !in it.third }.single()
        assertEquals(ON to 321L, comment.first to comment.second)
        assertTrue(b.id in comment.third && "$ON#920" in comment.third && "run-${b.id}" in comment.third, comment.third)

        // No answer to the group question is no mark; a pick that does not beat new founds a group with the mark and files for it.
        jev.choice = null; jev.noul = 0.95
        notes.insert(c.copy(repo = ON)); grouper.drain(now)
        assertNull(stored(c.id).groupId); assertEquals(null to null, twoThings(c.id))
        jev.choice = "g$g1" to 0.6
        grouper.drain(now.plusMinutes(2))
        val g2 = stored(c.id).groupId!!
        assertTrue(g2 != g1)
        assertEquals(0.95 to jev.ids.last(), twoThings(c.id))
        assertEquals("g$g1 (0.60 vs new 0.40): new group g$g2; two things (0.95)", actions().last())
        assertEquals(listOf(ON, null, "filing"), bug(g2))
        assertEquals(2, intakes().size)
    }

    @Test
    fun `with frictionBugs off, or doctorBugs alone on, or no repo, the mark alone is written and nothing is filed, and the switch on later lets the next mark file`() {
        jev.noul = 0.9
        notes.insert(a.copy(repo = OFF)); grouper.drain(t0)
        val g1 = stored(a.id).groupId!!
        assertEquals(0.9, stored(a.id).twoThings)
        assertNull(bug(g1)); assertTrue(intakes().isEmpty())
        notes.insert(a2.copy(repo = DOCTOR_ONLY)); grouper.drain(t0)
        assertEquals(0.9, stored(a2.id).twoThings)
        assertNull(bug(g1)); assertTrue(intakes().isEmpty(), "doctorBugs is not the switch")
        val mate = note("mate", t0.plusMinutes(1), a.summary, a.evidence, repo = null).copy(source = FrictionNote.FIRST_MATE, issue = null, runId = null, login = "someone")
        notes.insert(mate); grouper.drain(t0.plusMinutes(1))
        assertEquals(0.9, stored(mate.id).twoThings); assertNull(bug(g1)); assertTrue(intakes().isEmpty(), "a note with no repo files nothing")
        // Nothing is filed retroactively: the next mark files.
        repos.setFrictionBugs(OFF, true)
        assertTrue(intakes().isEmpty())
        notes.insert(b.copy(repo = OFF)); jev.choice = "g$g1" to 0.9; grouper.drain(t0.plusMinutes(2))
        assertEquals(listOf(OFF, null, "filing"), bug(g1))
        assertEquals(1, intakes().size)
    }

    @Test
    fun `a failed start unclaims, a closed bug lets the next mark file afresh, the sweep writes two things kept, and a merge keeps the target's bug`() {
        jev.noul = 0.9
        // The intake does not start: the ask variable is refused by a message with no subscriber in a repo the flow refuses... simulated by a grouper whose runtime throws.
        val broken = FrictionBugs(groups, groupBugs, repos, BrokenRuntime(runtime), bugs.history(), tickets, details, bugs.events(), transactions)
        val own = FrictionGrouper(groups, jev, calls, broken, transactions)
        notes.insert(a.copy(repo = ON)); own.drain(t0)
        val g1 = stored(a.id).groupId!!
        assertEquals(0.9, stored(a.id).twoThings, "the mark stands")
        assertNull(bug(g1), "a start that fails unclaims")
        assertTrue(intakes().isEmpty())

        // The next mark files; the delegates write the number; the engine sees it closed; the next mark files afresh.
        jev.choice = "g$g1" to 0.9
        notes.insert(b.copy(repo = ON)); grouper.drain(t0.plusMinutes(1))
        assertEquals(listOf(ON, null, "filing"), bug(g1))
        bugs.filed(g1, 321)
        details.closed.getOrPut(ON) { mutableListOf() } += com.codefrak.engine.github.ClosedIssue(321, "t", "u", emptyList(), t0, "completed")
        assertEquals(1, bugs.sync())
        assertEquals(listOf(ON, 321L, "closed"), bug(g1))
        assertEquals(0, bugs.sync(), "settled once")
        notes.insert(c.copy(repo = ON)); grouper.drain(t0.plusMinutes(2))
        assertEquals(listOf(ON, null, "filing"), bug(g1))
        assertEquals(2, intakes().size)
        assertTrue(github.comments.none { "Authorized by" !in it.third }, "a closed bug gets no comment (the intake's own authorization comment aside)")

        // The sweep: the lone friction-two-things call 30 days old gets `two things: kept`; the group call's kept gains nothing.
        val lone = jev.ids[0]; val grouped = jev.ids[1]
        listOf(lone, grouped).forEach { backdate(it, t0) }
        grouper.sweep(t0.plusDays(30))
        assertEquals("two things: kept", calls.get(lone)!!.outcome)
        assertEquals("joined g$g1: kept", calls.get(grouped)!!.outcome)

        // A merge deletes the merged group's bug columns with its row and leaves the target's bug untouched.
        jev.choice = "new" to 0.9
        val d = note("d", t0.plusMinutes(3), "a fourth thing", "fourth evidence", repo = ON)
        notes.insert(d); grouper.drain(t0.plusMinutes(3))
        val g2 = stored(d.id).groupId!!
        assertTrue(g2 != g1)
        assertEquals(listOf(ON, null, "filing"), bug(g2), "its own group, its own bug")
        val before = bug(g1)
        groups.merge(g2, g1)
        assertEquals(before, bug(g1))
        assertNull(groupBugs.bug(g2))
    }

    /** A RuntimeService whose message correlation throws: the intake does not start. */
    class BrokenRuntime(private val real: RuntimeService) : RuntimeService by real {
        override fun createMessageCorrelation(messageName: String?): org.operaton.bpm.engine.runtime.MessageCorrelationBuilder = throw IllegalStateException("no intake today")
    }

    // The recorded calls of #1126 (2026-10-05): the right sibling on top but its probability split among near-duplicates.
    private fun seedGroups(vararg titles: String): List<Long> = titles.mapIndexed { i, t -> groups.create(t, t0.plusMinutes(i.toLong())) }

    /** Answers the group question with [probabilities] (by index into [ids], or `new`), the top one as the choice. */
    private fun answer(ids: List<Long>, vararg probabilities: Pair<Any, Double>) {
        val p = probabilities.associate { (k, v) -> (if (k is Int) "g${ids[k]}" else k.toString()) to v }
        val top = p.maxBy { it.value }
        jev.answers[FrictionGroupCheck.QUESTION] = com.codefrak.engine.jev.JevAnswer("choice", choice = top.key, probabilities = p, confidence = top.value)
    }

    private fun assertJoinedOneOf(cluster: Set<Long>, before: Int) {
        val n = stored(b.id)
        assertEquals("jev", n.groupedBy)
        assertEquals(jev.groupIds.single(), n.jevCallId)
        assertTrue(cluster.contains(n.groupId!!), "${n.groupId} in $cluster")
        assertEquals(cluster.min(), n.groupId, "the cluster's oldest group")
        assertTrue(FrictionKey.of(b.summary, b.evidence) in signatures(n.groupId!!))
        assertEquals(before, groups.count(), "no group founded")
        assertEquals("g${n.groupId}", FrictionGroupCheck.placed(groupActions().single()))
        assertTrue(groupActions().single()!!.length <= 100)
        assertEquals(2.0, calls.get(jev.groupIds.single())!!.threshold)
    }

    @Test
    fun `replay g55 - g9 0,28 g27 0,17 g2 0,16 g32 0,12 new 0,11 joins the cluster`() {
        val (g9, g27, g2, g32) = seedGroups(
            "bots/check-acceptance-criteria is not executable in the sandbox clone",
            "bots/check-acceptance-criteria is not executable (Permission denied)",
            "bots/check-acceptance-criteria not executable in the sandbox",
            "bots/check-acceptance-criteria is not executable; run it with python3")
        val other = seedGroups("the brief's proposal summary is not on the branch").single()
        val ids = listOf(g9, g27, g2, g32, other)
        answer(ids, 0 to 0.28, 1 to 0.17, 2 to 0.16, 3 to 0.12, "new" to 0.11, 4 to 0.16)
        notes.insert(b); grouper.drain(t0.plusHours(1))
        assertJoinedOneOf(setOf(g9, g27, g2, g32), 5)
        val picks = calls.get(jev.groupIds.single())!!.response!!
        assertTrue(picks.contains("\"g$g9\":0.28") && picks.contains("\"new\":0.11"), picks)  // the stored Jev response JSON (#1155)
        assertEquals("g${minOf(g9, g27, g2, g32)} cluster{${listOf(g9, g27, g2, g32).sorted().joinToString(",") { "g$it" }}} (0.73 vs new 0.11): joined", groupActions().single())
    }

    @Test
    fun `replay g57 - g39 0,33 g32 0,30 new 0,10 joins the cluster`() {
        val (g39, g32) = seedGroups("the brief's proposal summary is not on the branch",
            "the brief's proposal summary is not on the work branch")
        val other = seedGroups("no Gradle in the sandbox").single()
        answer(listOf(g39, g32, other), 0 to 0.33, 1 to 0.30, "new" to 0.10, 2 to 0.27)
        notes.insert(b); grouper.drain(t0.plusHours(1))
        assertJoinedOneOf(setOf(g39, g32), 3)
    }

    @Test
    fun `replay g50 - g48 0,48 g47 0,21 new 0,13 g10 0,11 joins the cluster`() {
        val (g48, g47, g10) = seedGroups("no Gradle or Docker in the sandbox", "no Docker in the sandbox", "no Gradle in the sandbox")
        val other = seedGroups("the brief's proposal summary is not on the branch").single()
        answer(listOf(g48, g47, g10, other), 0 to 0.48, 1 to 0.21, "new" to 0.13, 2 to 0.11, 3 to 0.07)
        notes.insert(b); grouper.drain(t0.plusHours(1))
        assertJoinedOneOf(setOf(g48, g47, g10), 4)
    }

    @Test
    fun `new on top founds a group even when its near-duplicates hold the rest`() {
        val (g1, g2) = seedGroups("no Docker in the sandbox", "no Docker in the sandbox clone")
        answer(listOf(g1, g2), "new" to 0.55, 0 to 0.25, 1 to 0.20)
        notes.insert(b); grouper.drain(t0.plusHours(1))
        assertEquals("new" to jev.groupIds.single(), stored(b.id).let { it.groupedBy to it.jevCallId })
        assertEquals(3, groups.count())
        assertEquals("new (0.55): new group g${stored(b.id).groupId}", groupActions().single())
    }

    @Test
    fun `distinct problems - a low pick that does not clearly beat new founds, the same pick that does joins`() {
        val ids = seedGroups("no Docker in the sandbox", "the brief's proposal summary is not on the branch", "bots/check-acceptance-criteria is not executable")
        answer(ids, 0 to 0.30, 1 to 0.25, 2 to 0.25, "new" to 0.20)
        notes.insert(b); grouper.drain(t0.plusHours(1))
        assertEquals("new", stored(b.id).groupedBy)
        assertEquals("g${ids[0]} (0.30 vs new 0.20): new group g${stored(b.id).groupId}", groupActions().single())

        jev.reset()
        answer(ids, 0 to 0.30, 1 to 0.25, 2 to 0.25, "new" to 0.15)
        notes.insert(a2); grouper.drain(t0.plusHours(1))
        assertEquals("jev" to ids[0], stored(a2.id).let { it.groupedBy to it.groupId })
        assertEquals("g${ids[0]} (0.30 vs new 0.15): joined", groupActions().single())
    }

    @Test
    fun `the cluster's best group is its oldest, whichever member the pick named`() {
        val (old, young) = seedGroups("no Docker in the sandbox", "no Docker in the sandbox clone")
        repeat(3) { i -> val n = note("y$i", t0.plusMinutes(10L + i), "young $i", "young evidence $i"); notes.insert(n); groups.join(n.id, young, GroupedBy.USER, null, "u") }
        answer(listOf(old, young), 1 to 0.70, 0 to 0.10, "new" to 0.20)
        notes.insert(b); grouper.drain(t0.plusHours(1))
        assertEquals("jev" to old, stored(b.id).let { it.groupedBy to it.groupId }, "the older group, not the larger one the pick named")
    }

}

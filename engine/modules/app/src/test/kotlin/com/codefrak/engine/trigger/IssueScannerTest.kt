package com.codefrak.engine.trigger

import com.codefrak.engine.chore.FlowStarter
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.events.EventRecorder
import com.codefrak.engine.github.Blocker
import com.codefrak.engine.github.IssueEvent
import com.codefrak.engine.github.IssueSnapshot
import com.codefrak.engine.intake.TicketRecorder
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.Deferred
import com.codefrak.engine.repos.DeferredIssues
import com.codefrak.engine.repos.HandledIssues
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.workitems.FakeTickets
import com.codefrak.engine.workitems.NewWorkItem
import com.codefrak.engine.workitems.TicketState
import com.codefrak.engine.workitems.WorkItem
import com.codefrak.engine.workitems.WorkItemFlags
import com.codefrak.engine.workitems.WorkItemSourceRef
import com.codefrak.engine.workitems.snapshot
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeFlowStarter : FlowStarter {
    val started = mutableListOf<Pair<String, IssueSnapshot>>()
    /** What each start was told beyond the ticket: a feature's mode. */
    val extras = mutableListOf<Map<String, Any>>()
    /** Who each start was authorized by. */
    val authorizers = mutableListOf<String>()
    var fail = false
    /** Intakes: only these issue numbers have an intake waiting. */
    val waitingIntakes = mutableSetOf<Long>()
    /** Instances that ended without a PR: they no longer stand in the way of a new run. */
    val over = mutableSetOf<String>()
    override fun counts(instance: String?) = instance !in over
    /** Instances that ended with their "Done. Merged" close. */
    val merged = mutableSetOf<String>()
    override fun mergedClose(instance: String?) = instance in merged
    /** Instances past their close, still running (a feature at its accept Smith's check). */
    val closedRunning = mutableSetOf<String>()
    override fun closedStillRunning(instance: String?) = instance in closedRunning
    /** Instances that have not ended. */
    val running = mutableSetOf<String>()
    override fun running(instance: String?) = instance in running || instance in closedRunning
    val prs = mutableMapOf<String, Long>()
    /** Steers sent: instance, by, message; only to a running instance. */
    val steered = mutableListOf<Triple<String, String, String>>()
    override fun steer(instance: String, by: String, message: String): Boolean =
        running(instance).also { if (it) steered += Triple(instance, by, message) }
    override fun mergedPr(instance: String?) = prs[instance]
    /** Split features parked at `child closed`, and the wakes they were sent: instance, child, PR. */
    val parked = mutableSetOf<String>()
    val woken = mutableListOf<Triple<String, Long, Long?>>()
    override fun childClosed(instance: String, child: Long, pr: Long?): Boolean {
        if (!parked.remove(instance)) return false
        woken += Triple(instance, child, pr)
        return true
    }
    override fun start(flow: String, repo: String, ticket: IssueSnapshot, authorizedBy: String,
                       model: String?, effort: String?, tier: Int?, extra: Map<String, Any>): String? {
        check(!fail) { "engine down" }
        if (flow == "intake") return if (waitingIntakes.remove(ticket.number)) "intake-${ticket.number}" else null
        started += repo to ticket
        extras += extra
        authorizers += authorizedBy
        return "instance-${started.size}"
    }
}
/**
 * The scan and the approvals on tickets set up in [FakeTickets] (kind, mode, flags, blocked by, state), as the engine's
 * work items hold them since the cutover: an approval is First mate's ([IssueApprovals.approveTicket]), or a fresh one
 * handed to [IssueScanner.consider] as its `firstMate` event with the actor it names. What only a GitHub issue could
 * produce (an `unlabeled` or `renamed` event, a label by someone other than `engine`, a body edit time) is tested pure on
 * hand-built events: ApprovalRefusalTest, LabelEventsTest and EditedAfterTest in engine-flow-delivery.
 */
@SpringBootTest
class IssueScannerTest {
    @TestConfiguration
    class Fakes {
        @Bean @Primary fun github() = TicketRecorder()
        @Bean @Primary fun tickets() = FakeTickets()
        @Bean @Primary fun starter() = FakeFlowStarter()
    }

    @Autowired lateinit var scanner: IssueScanner
    @Autowired lateinit var approvals: IssueApprovals
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var access: Access
    @Autowired lateinit var tickets: FakeTickets
    @Autowired lateinit var starter: FakeFlowStarter
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var handled: HandledIssues
    @Autowired lateinit var deferred: DeferredIssues
    @Autowired lateinit var bus: EngineEvents

    private val repo = "acme/widgets"
    private var nextEvent = 1000L

    @BeforeEach
    fun reset() {
        listOf("engine.handled_issue", "engine.deferred_issue", "engine.engine_access", "engine.engine_access_audit", "engine.target_repo")
            .forEach { jdbc.sql("DELETE FROM $it").update() }
        tickets.reset()
        starter.merged.clear(); starter.closedRunning.clear(); starter.running.clear(); starter.prs.clear(); starter.started.clear(); starter.extras.clear(); starter.authorizers.clear(); starter.over.clear(); starter.fail = false; starter.waitingIntakes.clear(); starter.parked.clear(); starter.woken.clear()
        repos.add(repo)
        access.add("lundmatt1", "test")
    }

    /** Ticket `n` in `r`: "Tidy", filed by `someone` unless `author` says otherwise. */
    private fun ticket(n: Long, kind: String = "chore", mode: String? = null, body: String = "tidy the readme", author: String = "someone",
                       flags: WorkItemFlags = WorkItemFlags(), r: String = repo): WorkItem =
        tickets.create(NewWorkItem(r, "# Tidy\n\n$body", kind, WorkItemSourceRef(WorkItemSourceRef.RAISED), mode = mode, ticket = n,
            author = author, flags = flags))

    private fun item(n: Long, r: String = repo) = assertNotNull(tickets.forTicket(r, n), "no ticket $r#$n")

    /** First mate's `approve` of ticket `n` as `by`, which acted. */
    private fun approve(n: Long, by: String = "lundmatt1", r: String = repo) =
        assertIs<IssueApprovals.TicketApproved.Done>(approvals.approveTicket(r, n, by))

    /** A fresh approval of ticket `n` by `by`, handed to `consider` as First mate's event under label event `id`. */
    private fun consider(n: Long, by: String, id: Long = nextEvent++) =
        scanner.consider(repo, n, IssueEvent(id, "labeled", by, OffsetDateTime.now(), IssueScanner.AUTHORIZED))

    private fun comments(n: Long, r: String = repo) = tickets.conversation(item(n, r).id).map { it.body }

    /** What the engine said on ticket `n`, without First mate's own `Authorized by … through First mate` record. */
    private fun says(n: Long, r: String = repo) = comments(n, r).filterNot { it.startsWith("Authorized by @") && "through First mate" in it }

    @Test
    fun `each kind starts its own flow`() {
        val kinds = listOf("chore" to null, "bug" to null, "proposal" to null, "research" to null, "feature" to "auto")
        kinds.forEachIndexed { i, (kind, mode) -> ticket(1L + i, kind, mode) }
        val done = kinds.indices.map { approve(1L + it) }
        assertEquals(kinds.map { it.first to "started" }, done.map { it.flow to it.outcome })
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), starter.started.map { it.second.number })
        assertEquals(List(4) { emptyMap<String, Any>() } + mapOf<String, Any>("featureMode" to "auto"), starter.extras)
    }

    @Test
    fun `a feature starts with the mode it names, auto or confirm, and is refused without one`() {
        ticket(40, "feature", "confirm"); ticket(41, "feature", "auto"); ticket(42, "feature")
        assertEquals("started", approve(40).outcome)
        assertEquals("started", approve(41).outcome)
        assertEquals(listOf(mapOf<String, Any>("featureMode" to "confirm"), mapOf<String, Any>("featureMode" to "auto")), starter.extras)

        val none = approve(42)
        assertEquals("refused", none.outcome)
        assertTrue("exactly one mode label" in none.detail && "it had none" in none.detail, none.detail)
        assertTrue(says(42).any { "feature:auto" in it }, says(42).toString())
        assertEquals(2, starter.started.size)
    }

    @Test
    fun `the claim records the trailer's story points`() {
        ticket(70, body = "tidy the readme\n\n<!-- engine: intake i · tier 5 · points 3 · p3 · by alice -->", author = "frakworks-bot")
        assertEquals("started", approve(70).outcome)
        assertEquals(3, handled.points(repo, 70))
        assertEquals("alice", handled.askedBy(repo, 70), "points before the priority do not hide `by`")
    }

    @Test
    fun `the claim records who asked - the author, or the trailer's by over it`() {
        ticket(60)
        ticket(61, body = "tidy the readme\n\n<!-- engine: intake i · tier 5 · p3 · by alice -->", author = "frakworks-bot")
        assertEquals(listOf("started", "started"), listOf(approve(60), approve(61)).map { it.outcome })
        val rows = handled.list(repo).associateBy { it.issue }
        assertEquals("someone", rows.getValue(60L).askedBy, "a hand-filed ticket: its author")
        assertEquals("alice", rows.getValue(61L).askedBy, "the trailer's `by` wins over the author")
        assertEquals("alice", handled.askedBy(repo, 61))
        assertEquals(null, handled.points(repo, 61), "a trailer without points records none")
    }

    @Test
    fun `a trusted login's approval starts it once`() {
        ticket(1)
        val done = approve(1, by = "LundMatt1")  // logins are case-insensitive
        assertEquals("started", done.outcome)
        assertTrue(item(1).flags.authorized)
        assertEquals(listOf(repo to IssueSnapshot(1, "Tidy", "tidy the readme", "someone")), starter.started)
        assertIs<IssueApprovals.TicketApproved.Refused>(approvals.approveTicket(repo, 1, "lundmatt1"))
        assertNull(consider(1, "lundmatt1", done.labelEvent), "its approval is handled")
        assertEquals(1, starter.started.size)
    }

    @Test
    fun `the engine's own authorized flag on a ticket it started itself is skipped, with no row and no comment`() {
        // A refactor job with approval `none`: the engine starts the feature (label event 0), and flags it.
        handled.claim(repo, 70, 0, "feature", "started", "refactor job j"); handled.started(repo, 70, 0, "running-70")
        ticket(70, "feature", "auto", author = "engine", flags = WorkItemFlags(authorized = true))
        assertNull(scanner.consider(repo, 70))
        assertEquals(emptyList(), comments(70))
        assertEquals(listOf("started"), handled.list(repo).filter { it.issue == 70L }.map { it.outcome }, "the board still shows the running flow")
        assertEquals(emptyList(), starter.started)
    }

    @Test
    fun `an approval by someone without access is refused once, with a comment, and a later approval still counts`() {
        ticket(2)
        val id = nextEvent
        assertEquals("refused", consider(2, "stranger")?.outcome)
        assertTrue("@stranger" in comments(2).single())
        assertNull(consider(2, "stranger", id))
        assertEquals(emptyList(), starter.started)

        assertEquals("started", approve(2).outcome)
    }

    @Test
    fun `First mate's approve by an untrusted login is refused, with nothing written`() {
        ticket(15, "research")
        val refused = assertIs<IssueApprovals.TicketApproved.Refused>(approvals.approveTicket(repo, 15, "stranger"))
        assertEquals("@stranger is not a trusted login", refused.why)
        assertFalse(item(15).flags.authorized)
        assertEquals(emptyList(), comments(15))
        assertEquals(emptyList(), starter.started)
        assertEquals(emptyList(), handled.list(repo))
    }

    @Test
    fun `a new approval runs it again only once the last run ended without a pr`() {
        ticket(3, "bug")
        assertEquals("started", consider(3, "lundmatt1")?.outcome)

        val refused = assertNotNull(consider(3, "lundmatt1"))
        assertEquals("refused", refused.outcome, "still running")
        assertTrue("still running or has opened a PR" in refused.detail, refused.detail)

        starter.over += "instance-1" // it failed, or ended needs-repro, without a PR
        assertEquals("started", consider(3, "lundmatt1")?.outcome, "nothing to collide with")
    }

    @Test
    fun `a closed ticket is not approved, started or handed to its intake`() {
        ticket(19)
        assertEquals("started", approve(19).outcome)
        starter.over += "instance-1"  // cancelled from the desktop app before it opened a PR
        tickets.setState(item(19).id, TicketState.CANCELLED, "cancelled")
        assertEquals(IssueApprovals.TicketApproved.Refused("$repo#19 is closed"), approvals.approveTicket(repo, 19, "lundmatt1"))
        assertEquals(IssueApprovals.Reauthorized.Refused("$repo#19 is closed"), approvals.reauthorize(repo, 19, "lundmatt1"))

        ticket(13, WorkItem.ASK)
        starter.waitingIntakes += 13
        tickets.setState(item(13).id, TicketState.CANCELLED, "withdrawn")
        assertNull(approvals.approveIntake(repo, 13, "lundmatt1"))
        assertEquals(setOf(13L), starter.waitingIntakes)
        assertEquals(1, starter.started.size)
    }

    @Test
    fun `a failed start is given back and retried`() {
        ticket(8)
        val id = nextEvent
        starter.fail = true
        assertFailsWith<IllegalStateException> { consider(8, "lundmatt1", id) }
        assertEquals(emptyList(), handled.list(repo))
        starter.fail = false
        assertEquals("started", consider(8, "lundmatt1", id)?.outcome)
    }

    @Test
    fun `a chore an intake already started is not started again by its authorized flag`() {
        handled.claim(repo, 9, 0, "chore", "started", "authorized by @lundmatt1 via intake #3")
        ticket(9, flags = WorkItemFlags(authorized = true))
        assertNull(scanner.consider(repo, 9))
        assertEquals(emptyList(), comments(9))
        assertEquals(listOf(0L), handled.list(repo).filter { it.issue == 9L }.map { it.labelEvent })
        // A fresh approval is one like any other, refused while the flow runs.
        assertTrue("still running or has opened a PR" in assertNotNull(consider(9, "lundmatt1")).detail)
        assertEquals(emptyList(), starter.started)
    }

    @Test
    fun `approve hands the waiting intake its authorization as the login, with an entry and no flag`() {
        ticket(12, WorkItem.ASK)
        starter.waitingIntakes += 12
        val done = assertNotNull(approvals.approveIntake(repo, 12, "lundmatt1"))
        assertEquals("intake-12", done.instance)
        assertEquals(emptyList(), done.notes)
        assertFalse(item(12).flags.authorized, "no flag: the ask lives only in the engine")
        assertTrue("@lundmatt1" in comments(12).single(), comments(12).toString())
        assertEquals(emptyList(), handled.list(repo))
    }

    @Test
    fun `approve writes nothing when no intake waits`() {
        ticket(13, WorkItem.ASK)
        assertNull(approvals.approveIntake(repo, 13, "lundmatt1"))
        assertEquals(emptyList(), comments(13))
        assertEquals(emptyList(), handled.list(repo))
    }

    @Test
    fun `First mate's approve starts a filed research ticket as the login, and its own flag is handled`() {
        ticket(14, "research")
        val done = approve(14)
        assertEquals("research" to "started", done.flow to done.outcome)
        assertEquals(listOf("lundmatt1"), starter.authorizers)
        assertTrue(item(14).flags.authorized)
        assertTrue(comments(14).single().startsWith("Authorized by @lundmatt1 through First mate"), comments(14).single())
        val row = handled.list(repo).single()
        assertEquals(done.labelEvent to "started", row.labelEvent to row.outcome)

        assertNull(scanner.consider(repo, 14), "the engine's own flag is already handled")
        assertEquals(1, starter.started.size)
    }

    @Test
    fun `approve with nothing to authorize writes nothing`() {
        ticket(16, WorkItem.ASK)
        assertIs<IssueApprovals.TicketApproved.Refused>(approvals.approveTicket(repo, 16, "lundmatt1"))
        ticket(17, flags = WorkItemFlags(authorized = true))
        assertIs<IssueApprovals.TicketApproved.Refused>(approvals.approveTicket(repo, 17, "lundmatt1"))
        assertFalse(item(16).flags.authorized)
        assertEquals(emptyList(), comments(16) + comments(17))
        assertEquals(emptyList(), starter.started)
        assertEquals(emptyList(), handled.list(repo))
    }

    @Test
    fun `approve re-runs an approval refused for an untrusted approver once the caller is trusted`() {
        ticket(18, flags = WorkItemFlags(authorized = true))
        assertEquals("authorized by @engine${IssueScanner.NOT_TRUSTED}", scanner.consider(repo, 18)?.detail, "engine is no trusted login here")
        val done = approve(18)
        assertEquals("chore" to "started", done.flow to done.outcome)
        assertEquals(listOf("lundmatt1"), starter.authorizers)
        assertEquals("started", handled.list(repo).single().outcome)
        assertNull(scanner.consider(repo, 18))
        assertEquals(1, starter.started.size)
    }

    @Test
    fun `approve still refuses an approval that already took effect`() {
        ticket(19)
        assertEquals("started", approve(19).outcome)
        val refused = assertIs<IssueApprovals.TicketApproved.Refused>(approvals.approveTicket(repo, 19, "lundmatt1"))
        assertTrue(refused.why.contains("already carries"), refused.why)
        assertEquals(1, starter.started.size)
        assertEquals("started", handled.list(repo).single().outcome)
    }

    @Test
    fun `grants and revokes are audited`() {
        access.add("friend", "lundmatt1")
        access.remove("friend", "lundmatt1")
        assertEquals(listOf("grant", "grant", "revoke"), access.history().map { it.change })
        assertEquals(listOf("lundmatt1"), access.list().map { it.login })
    }

    // Blocked by (engine/README.md, "Blocked by").

    private val waiting = "Waiting on #50 (open). Starts on its own when every blocker is closed by a merged PR.\n\n— engine"
    private val stalled = "Blocker #50 was closed without a merge; re-authorize to proceed\n\n— engine"

    /** Ticket `n` waits on each of `blockers`, filed as chores when they are not tickets yet. */
    private fun blockedBy(n: Long, vararg blockers: Long, r: String = repo) {
        for (b in blockers) {
            if (tickets.forTicket(r, b) == null) ticket(b, r = r)
            tickets.block(item(n, r).id, item(b, r).id)
        }
    }
    private fun open(n: Long) = Blocker(repo, n, "open")
    private fun closeByHand(n: Long, r: String = repo) = tickets.setState(item(n, r).id, TicketState.DONE, "closed by hand")

    /** Ticket `n` closed by the engine's own chore on it, which merged its PR (#900+n) and closed the ticket. */
    private fun mergedByEngine(n: Long) {
        if (tickets.forTicket(repo, n) == null) ticket(n)
        handled.claim(repo, n, 1, "chore", "started", null)
        handled.started(repo, n, 1, "done-$n")
        starter.merged += "done-$n"; starter.prs["done-$n"] = 900 + n
        tickets.setState(item(n).id, TicketState.DONE, "merged")
    }

    @Test
    fun `a scan of only some repos resumes nothing in the others, and its report names the ones it covered`() {
        val other = "acme/gizmos"
        repos.add(other)
        ticket(32, r = other); blockedBy(32, 50, r = other)
        assertEquals("deferred", approve(32, r = other).outcome)

        assertEquals(listOf(repo), scanner.scan(only = setOf(repo)).repos)
        assertEquals(listOf(other, repo), scanner.scan().repos.sorted())

        // Its blocker closed by hand: a scan of the other repo says so on #32; one of `repo` alone does not re-read it.
        closeByHand(50, other)
        val before = says(32, other).size
        scanner.scan(only = setOf(repo))
        assertEquals(before, says(32, other).size, "the other repo's waiting approval was not re-read")
        scanner.scan(only = setOf(other))
        assertEquals(before + 1, says(32, other).size)
    }

    @Test
    fun `an open blocker defers the approval, unclaimed, said once`() {
        ticket(20); blockedBy(20, 50)
        val done = approve(20)
        assertEquals("deferred" to "waiting on #50", done.outcome to done.detail)
        assertEquals(listOf(waiting), says(20))
        assertEquals(emptyList(), handled.list(repo), "the approval is not consumed")
        val row = assertNotNull(deferred.get(repo, 20))
        assertEquals(done.labelEvent, row.labelEvent)
        assertEquals("lundmatt1", row.authorizedBy)
        assertEquals(listOf(open(50)), row.blockers)

        assertEquals(emptyList(), scanner.scan().actions)
        assertEquals(listOf(waiting), says(20), "one comment, not one a scan")
        assertEquals(emptyList(), starter.started)
    }

    @Test
    fun `a closed (merged) blocker releases the waiting approval, which starts from what was approved`() {
        ticket(21); blockedBy(21, 50)
        val done = approve(21)
        tickets.editBody(item(21).id, "edited while it waited", "someone")

        mergedByEngine(50)
        assertEquals(listOf("started"), scanner.scan().actions.map { it.outcome })
        assertEquals(listOf(repo to IssueSnapshot(21, "Tidy", "tidy the readme", "someone")), starter.started, "the stored snapshot")
        val row = handled.list(repo).single { it.issue == 21L }
        assertEquals(done.labelEvent to "started", row.labelEvent to row.outcome)
        assertEquals(listOf(waiting, "Started: #50 closed.\n\n— engine"), says(21))
        assertNull(deferred.get(repo, 21))
        assertEquals(emptyList(), scanner.scan().actions)
    }

    @Test
    fun `a blocker closed by hand keeps it waiting, said once, until approved again`() {
        ticket(22); blockedBy(22, 50)
        approve(22)
        closeByHand(50)
        repeat(3) { assertEquals(emptyList(), scanner.scan().actions) }
        assertEquals(listOf(waiting, stalled), says(22))
        assertEquals(emptyList(), starter.started)
        assertEquals(setOf("$repo#50"), assertNotNull(deferred.get(repo, 22)).stalled)

        // A cancelled run of the blocker's own chore is not a merge either.
        handled.claim(repo, 50, 1, "chore", "started", null); handled.started(repo, 50, 1, "cancelled-50")
        assertEquals(emptyList(), scanner.scan().actions)

        assertEquals("started", consider(22, "lundmatt1")?.outcome, "approved again with nothing open")
        assertNull(deferred.get(repo, 22))
    }

    /** The stream (engine/README.md, "Events"): the `reauthorize` row of GET /needs, from the scan that records the stall to the row's end. */
    @Test
    fun `a blocker closed without a merge is a reauthorize need from the scan that sees it until the row goes`() = EventRecorder(bus).use { rec ->
        ticket(23); blockedBy(23, 50)
        approve(23)
        assertEquals(emptyList(), rec.of("needs"), "waiting on an open blocker is no need")
        closeByHand(50)
        repeat(3) { scanner.scan() }
        val added = rec.of("needs").single()
        assertEquals(listOf(repo, "reauthorize", "added", 23L, "https://github.com/$repo/issues/23"),
            listOf(added.str("repo"), added.str("kind"), added.state, added.long("number"), added.str("actionId")))

        assertEquals("started", consider(23, "lundmatt1")?.outcome)
        assertEquals(listOf("added", "removed"), rec.of("needs").map { it.state })
        assertNull(deferred.get(repo, 23))
    }

    @Test
    fun `taking authorized off, or closing the ticket, withdraws a waiting approval`() {
        ticket(24); blockedBy(24, 50); approve(24)
        ticket(25); blockedBy(25, 50); approve(25)
        assertEquals(2, deferred.list(repo).size)

        item(24).let { tickets.setFlags(it.id, it.flags.with(IssueScanner.AUTHORIZED, false)) }
        tickets.setState(item(25).id, TicketState.CANCELLED, "withdrawn")
        mergedByEngine(50)
        val actions = scanner.scan().actions.sortedBy { it.issue }
        assertEquals(listOf("ignored", "ignored"), actions.map { it.outcome })
        assertEquals(listOf("approved again since, or the approval is gone", "closed while it waited"), actions.map { it.detail })
        assertEquals(emptyList(), starter.started)
        assertEquals(emptyList(), deferred.list(repo))
        assertEquals(listOf(50L), handled.list(repo).map { it.issue }, "no claim on either")
    }

    @Test
    fun `a refusal wins over a blocker`() {
        ticket(26); blockedBy(26, 50)
        assertEquals("refused", consider(26, "stranger")?.outcome)
        assertNull(deferred.get(repo, 26))
    }

    @Test
    fun `a waiting approval whose start fails gives its claim back and still waits`() {
        ticket(28); blockedBy(28, 50); approve(28)
        mergedByEngine(50)
        starter.fail = true
        assertEquals(1, scanner.scan().failures.size)
        assertNotNull(deferred.get(repo, 28))
        assertEquals(listOf(50L), handled.list(repo).map { it.issue })
        starter.fail = false
        assertEquals(listOf("started"), scanner.scan().actions.map { it.outcome })
        assertNull(deferred.get(repo, 28))
    }

    // Split features (engine/README.md, "Split features"): re-authorizing a parent parked on a child closed by hand.

    /** Feature `n`, started by First mate's approve as `parent-n`, parked at `child closed` on child `child`, still open. */
    private fun parkedParent(n: Long, child: Long) {
        ticket(n, "feature", "auto")
        val done = approve(n)
        assertEquals("started", done.outcome)
        handled.started(repo, n, done.labelEvent, "parent-$n") // the fake's own instance id, renamed for the test
        blockedBy(n, child)
        deferred.defer(Deferred(repo, n, 0, "lundmatt1", "feature", "auto", tickets.snapshot(repo, n), listOf(open(child)),
            detail = "authorized by @lundmatt1 via parent #$n", waitingInstance = "parent-$n"))
        starter.parked += "parent-$n"
    }

    @Test
    fun `a parallel parent parked on two children is woken once per engine close, lowest first, narrowing its row`() {
        parkedParent(33, 60)
        fun repark(vararg children: Long) {
            deferred.defer(Deferred(repo, 33, 0, "lundmatt1", "feature", "auto", tickets.snapshot(repo, 33), children.map(::open),
                detail = "authorized by @lundmatt1 via parent #33", waitingInstance = "parent-33"))
            starter.parked += "parent-33"
        }
        repark(60, 61)
        blockedBy(33, 61)
        assertEquals(emptyList(), scanner.scan().actions, "both open: no wake")

        // #61 merges first: woken for it alone, its PR with it; #60, still open, does not hold the wake.
        mergedByEngine(61)
        assertEquals(listOf("resumed"), scanner.scan().actions.map { it.outcome })
        assertEquals(listOf(Triple<String, Long, Long?>("parent-33", 61L, 961L)), starter.woken)
        assertEquals(listOf(60L), assertNotNull(deferred.get(repo, 33)).blockers.map { it.number }, "narrowed to the one still open")

        // The parent re-parks on #60 and a new child #62; #60 is closed by hand: said once, the parent waits on #62.
        repark(60, 62)
        blockedBy(33, 62)
        closeByHand(60)
        assertEquals(emptyList(), scanner.scan().actions)
        assertEquals(emptyList(), scanner.scan().actions)
        assertEquals(1, says(33).count { "Child #60 was closed without a merge" in it }, says(33).toString())
        assertEquals(setOf("$repo#60"), assertNotNull(deferred.get(repo, 33)).stalled)

        // #62 merges: woken for it, though #60 stalled; the row keeps #60 and its stall.
        mergedByEngine(62)
        assertEquals(listOf("resumed"), scanner.scan().actions.map { it.outcome })
        assertEquals(Triple<String, Long, Long?>("parent-33", 62L, 962L), starter.woken.last())
        val row = assertNotNull(deferred.get(repo, 33))
        assertEquals(listOf(60L) to setOf("$repo#60"), row.blockers.map { it.number } to row.stalled)

        // Approved again: #60 counts as satisfied and the row, with no blocker left, is gone.
        starter.parked += "parent-33"
        assertEquals("resumed", consider(33, "lundmatt1")?.outcome)
        assertEquals(Triple<String, Long, Long?>("parent-33", 60L, null), starter.woken.last())
        assertNull(deferred.get(repo, 33))
    }

    @Test
    fun `approving a split parent parked on a child closed without a merge again resumes it, the child satisfied`() {
        parkedParent(30, 51)
        val running = assertNotNull(consider(30, "lundmatt1"))
        assertEquals("refused", running.outcome, "its child is still open")
        assertTrue("still running" in running.detail, running.detail)
        assertEquals(emptyList<Triple<String, Long, Long?>>(), starter.woken)

        closeByHand(51)
        assertEquals(emptyList(), scanner.scan().actions)
        assertEquals("Blocker #51 was closed without a merge; re-authorize to proceed. Re-authorizing it (First mate's `reauthorize`) resumes it " +
            "and treats #51 as satisfied\n\n— engine", says(30).last())
        assertEquals(setOf("$repo#51"), assertNotNull(deferred.get(repo, 30)).stalled)

        val approval = nextEvent
        val resumed = assertNotNull(consider(30, "lundmatt1"))
        assertEquals("resumed", resumed.outcome)
        assertTrue("#51" in resumed.detail && "satisfied" in resumed.detail, resumed.detail)
        assertEquals(listOf(Triple<String, Long, Long?>("parent-30", 51L, null)), starter.woken, "child closed, no PR: satisfied")
        assertNull(deferred.get(repo, 30))
        val row = handled.list(repo).single { it.issue == 30L && it.labelEvent == approval }
        assertEquals(Triple("resumed", resumed.detail, "parent-30"), Triple(row.outcome, row.detail, row.processInstance))
        assertEquals(1, starter.started.size, "resumed in place, nothing started")
        assertEquals(emptyList(), scanner.scan().actions)
    }

    @Test
    fun `approving a parked parent again still gets the access check, and a lost wait is retried`() {
        parkedParent(31, 52)
        closeByHand(52)
        scanner.scan()

        assertTrue("@stranger" in assertNotNull(consider(31, "stranger")).detail)
        assertEquals(emptyList<Triple<String, Long, Long?>>(), starter.woken)
        assertNotNull(deferred.get(repo, 31))

        starter.parked.clear() // the flow is not at `child closed`: the claim is given back
        val approval = nextEvent++
        assertFailsWith<IllegalStateException> { consider(31, "lundmatt1", approval) }
        assertTrue(handled.list(repo).none { it.issue == 31L && it.labelEvent == approval })
        starter.parked += "parent-31"
        assertEquals("resumed", consider(31, "lundmatt1", approval)?.outcome)
    }

    @Test
    fun `a parent parked on a child merged and at its acceptance check waits silently, then wakes with its PR`() {
        parkedParent(32, 53)
        handled.claim(repo, 53, 1, "feature", "started", null)
        handled.started(repo, 53, 1, "child-53")
        starter.closedRunning += "child-53"; starter.prs["child-53"] = 955
        closeByHand(53)
        deferred.checked(repo, 32, listOf(Blocker(repo, 53, "closed", "completed")), setOf("$repo#53")) // a stall recorded before the fix
        val before = says(32).size

        assertEquals(emptyList(), scanner.scan().actions)
        assertEquals(before, says(32).size, "no 'closed without a merge'")
        assertEquals(emptySet(), assertNotNull(deferred.get(repo, 32)).stalled, "no reauthorize need")
        assertEquals(emptyList<Triple<String, Long, Long?>>(), starter.woken)

        assertTrue(consider(32, "lundmatt1")?.outcome != "resumed", "not woken with no PR")
        assertEquals(emptyList<Triple<String, Long, Long?>>(), starter.woken)

        starter.closedRunning -= "child-53"; starter.merged += "child-53" // accepted
        scanner.scan()
        assertEquals(listOf(Triple<String, Long, Long?>("parent-32", 53L, 955L)), starter.woken)
        assertTrue(says(32).none { "closed without a merge" in it || "Resumed:" in it }, says(32).toString())
    }

    // First mate's reauthorize: what a fresh approval does, as the login.

    @Test
    fun `First mate's reauthorize resumes a parent parked on a child closed without a merge, and a later scan leaves it`() {
        parkedParent(40, 60)
        closeByHand(60)
        scanner.scan()
        val before = says(40).size

        val done = approvals.reauthorize(repo, 40, "lundmatt1")
        assertEquals(IssueApprovals.Reauthorized.Done("resumed", "parent-40", emptyList()), done)
        assertEquals(listOf(Triple<String, Long, Long?>("parent-40", 60L, null)), starter.woken, "child closed, no PR: satisfied")
        assertNull(deferred.get(repo, 40), "no reauthorize need left")
        val row = handled.list(repo).single { it.issue == 40L && it.outcome == "resumed" }
        assertTrue(row.labelEvent < 0, "no label event: ${row.labelEvent}")
        assertEquals("re-authorized by @lundmatt1 through First mate: child #60, closed without a merge, treated as satisfied", row.detail)
        assertEquals("parent-40", row.processInstance)
        assertEquals(before + 1, says(40).size)
        assertEquals("Resumed (re-authorized by @lundmatt1 through First mate): #60 was closed without a merge and counts as satisfied; " +
            "on to the replan.\n\n— engine", says(40).last())
        assertEquals(1, starter.started.size, "resumed in place, nothing started")

        assertEquals(emptyList(), scanner.scan().actions)
        assertEquals(before + 1, says(40).size, "no second Resumed")
        assertTrue(approvals.reauthorize(repo, 40, "lundmatt1") is IssueApprovals.Reauthorized.Refused)
    }

    @Test
    fun `First mate's reauthorize starts a deferred approval from what was stored, as the login, and a later scan leaves it`() {
        access.add("friend", "test")
        ticket(41); blockedBy(41, 50)
        val approval = approve(41).labelEvent
        tickets.editBody(item(41).id, "edited while it waited", "someone")
        closeByHand(50)
        scanner.scan()
        assertEquals(listOf(waiting, stalled), says(41))

        val done = approvals.reauthorize(repo, 41, "friend")
        assertEquals(IssueApprovals.Reauthorized.Done("started", "instance-1", emptyList()), done)
        assertEquals(listOf(repo to IssueSnapshot(41, "Tidy", "tidy the readme", "someone")), starter.started, "the stored snapshot")
        assertEquals(listOf("friend"), starter.authorizers)
        assertNull(deferred.get(repo, 41))
        val row = handled.list(repo).single { it.issue == 41L }
        assertEquals(Triple(approval, "started", "instance-1"), Triple(row.labelEvent, row.outcome, row.processInstance))
        assertEquals("re-authorized by @friend through First mate: #50 closed without a merge", row.detail)
        assertEquals(listOf(waiting, stalled, "Started: re-authorized by @friend through First mate; #50 was closed without a merge.\n\n— engine"),
            says(41))

        assertEquals(emptyList(), scanner.scan().actions, "the stored approval is handled now")
        assertEquals(3, says(41).size)
        assertEquals(1, starter.started.size)
    }

    @Test
    fun `First mate's reauthorize is refused, with nothing written, for a login that is not trusted`() {
        ticket(42); blockedBy(42, 50); approve(42)
        closeByHand(50)
        scanner.scan()
        val before = comments(42)

        assertEquals(IssueApprovals.Reauthorized.Refused("@stranger is not a trusted login"), approvals.reauthorize(repo, 42, "stranger"))
        assertEquals(before, comments(42))
        assertEquals(emptyList(), starter.started)
        assertEquals(emptyList(), handled.list(repo))
        assertEquals(setOf("$repo#50"), assertNotNull(deferred.get(repo, 42)).stalled, "still waiting on a re-authorization")
    }

    @Test
    fun `First mate's reauthorize says when there is nothing to re-authorize, and writes nothing`() {
        ticket(43)
        ticket(44); blockedBy(44, 50); approve(44)
        ticket(45); blockedBy(45, 51); approve(45)
        ticket(46); blockedBy(46, 52); approve(46)
        mergedByEngine(51)
        tickets.setState(item(46).id, TicketState.CANCELLED, "withdrawn")
        val said = (43L..46L).map { comments(it) }
        val rows = handled.list(repo)

        fun why(n: Long) = (approvals.reauthorize(repo, n, "lundmatt1") as IssueApprovals.Reauthorized.Refused).why
        assertEquals("$repo#43 is not waiting on a blocker (no deferred approval on it): nothing to re-authorize", why(43))
        assertEquals("$repo#44 still waits on #50, which is open", why(44))
        assertTrue("closed by a merge" in why(45), why(45))
        assertEquals("$repo#46 is closed", why(46))

        assertEquals(said, (43L..46L).map { comments(it) })
        assertEquals(rows, handled.list(repo))
        assertEquals(emptyList(), starter.started)
        assertEquals(listOf(44L, 45L, 46L), deferred.list(repo).map { it.issue }.sorted())
    }
}

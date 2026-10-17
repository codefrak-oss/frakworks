package com.codefrak.engine.feature

import com.codefrak.engine.intake.tickets
import com.codefrak.engine.TestTokens
import com.codefrak.engine.accounts.AiAccounts
import com.codefrak.engine.chore.FakeGitHubPulls
import com.codefrak.engine.chore.FakeGitPush
import com.codefrak.engine.chore.ScriptedSandbox
import com.codefrak.engine.github.IssueEvent
import com.codefrak.engine.github.NOT_PLANNED
import com.codefrak.engine.intake.CancelRequest
import com.codefrak.engine.intake.TicketRecorder
import com.codefrak.engine.repos.DeferredIssues
import com.codefrak.engine.repos.HandledIssues
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.brief
import com.codefrak.engine.finished
import com.codefrak.engine.runAllJobs
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.Ticket
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.ensureTestAccount
import com.codefrak.engine.smith.dropTestAccount
import com.codefrak.engine.workitems.FakeTickets
import com.codefrak.engine.workitems.TicketState
import com.codefrak.engine.workitems.NewWorkItem
import com.codefrak.engine.workitems.WorkItem
import com.codefrak.engine.workitems.WorkItemSourceRef
import com.codefrak.engine.workitems.blockers
import com.codefrak.engine.trigger.IssueScanner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.variable.Variables
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A split feature through the fake sandbox seam (engine/README.md, "Split features"); the scan is the real one, run by hand. */
@SpringBootTest
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class SplitFeatureProcessTest {
    @TestConfiguration
    class Fakes {
        @Bean @Primary fun github() = TicketRecorder()
        @Bean @Primary fun tickets(github: com.codefrak.engine.intake.TicketRecorder) = github.tickets(reuseClosed = true)
        @Bean @Primary fun pulls() = FakeGitHubPulls()
        @Bean @Primary fun git() = FakeGitPush()
        @Bean @Primary fun sandbox() = ScriptedSandbox()
    }

    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var accounts: AiAccounts
    @Autowired lateinit var history: HistoryService
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var exits: com.codefrak.engine.smith.SandboxExitNotifier
    @Autowired lateinit var github: TicketRecorder
    @Autowired lateinit var pulls: FakeGitHubPulls
    @Autowired lateinit var sandbox: ScriptedSandbox
    @Autowired lateinit var tickets: FakeTickets
    @Autowired lateinit var scanner: IssueScanner
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var access: Access
    @Autowired lateinit var handled: HandledIssues
    @Autowired lateinit var deferred: DeferredIssues
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var intakes: com.codefrak.engine.intake.IntakeController
    @Autowired lateinit var closeWake: com.codefrak.engine.trigger.CloseWake

    private val repo = "acme/tickets"

    @AfterEach
    fun dropTestAccount() { accounts.dropTestAccount() }

    @BeforeEach
    fun clear() {
        // Every test's first child is #100: a child flow another test left running would be taken for this one's.
        runtime.createProcessInstanceQuery().rootProcessInstances().list().forEach { runtime.deleteProcessInstance(it.id, "test done") }
        pulls.opened.clear(); pulls.merged.clear(); pulls.closed.clear(); github.comments.clear(); github.closed.clear(); github.closedAs.clear(); github.created.clear(); github.blockedBy.clear()
        tickets.reset()
        listOf("engine.engine_access", "engine.engine_access_audit", "engine.target_repo", "engine.intake_question", "engine.handled_issue", "engine.deferred_issue")
            .forEach { jdbc.sql("DELETE FROM $it").update() }
        repos.add(repo)
        access.add("lundmatt1", "test")
        accounts.ensureTestAccount()
        // The close's wake runs on its own thread; the tests wait for it so the woken parent's jobs are there to run.
        closeWake.dispatch = { task -> Thread.ofVirtual().start { task() }.join() }
    }

    private fun runAllJobs() = management.runAllJobs(exits, rounds = 200)

    private fun start(issue: Long, mode: String, body: String = "## Goal\n\nA wide feature.\n") = runtime.createMessageCorrelation("feature filed")
        .setVariables(mapOf("repo" to repo, "issue" to issue, "featureMode" to mode, "tier" to 8, "authorizedBy" to "lundmatt1", "askedBy" to "alice",
            "approvedTicket" to Variables.byteArrayValue(Brief.ticketBytes(Ticket(issue, "Wide feature", body, "lundmatt1")))))
        .correlateStartMessage().also { runAllJobs() }

    private fun incidents() = runtime.createIncidentQuery().list().map { it.incidentMessage }.toString() + " active: " +
        runtime.createProcessInstanceQuery().list().map { runtime.getActiveActivityIds(it.id) }
    private fun split(id: String) = SplitState.parse(
        history.createHistoricVariableInstanceQuery().processInstanceId(id).variableName("split").singleResult().value as ByteArray)

    /**
     * The child closed: by the engine's merge (its flow's PR 7, the fake's only number) or by hand. As the engine
     * records it, the parent stays blocked by every child filed before it.
     */
    private fun childClosed(parent: Long, child: Long, merged: Boolean) {
        val item = blockedBy(parent, child)
        if (item.open) tickets.setState(item.id, if (merged) TicketState.DONE else TicketState.CANCELLED, "closed by the test")
    }

    /** [parent]'s work item blocked by [child]'s, open or ended (an ended one is not adopted again as a fresh chore); answers the child's. */
    private fun blockedBy(parent: Long, child: Long): WorkItem {
        // Once any item for [child] exists, the newest one (forTicket's, with reuseClosed) is the one the child's flow filed
        // and closed: an older open item for the same number (minted by a flow a previous test left behind before this
        // test filed its child) must not be linked in its place, or the parent waits on it forever (scan: [], row kept).
        val item = if (tickets.open(repo).any { it.ticket == child }) tickets.forTicket(repo, child)!!
            else run { tickets.ended += repo.lowercase() to child; tickets.forTicket(repo, child)!! }
        tickets.link(tickets.forTicket(repo, parent)!!.id, item.id)
        return item
    }

    private val plan = """"plan":{"summary":"Two parts.","files":["a","b"],"stages":[],"tests":["gradle test"],"acceptance":["Both parts are in"]}"""
    private fun child(title: String, kind: String = "chore", extra: String = "") =
        """{"title":"$title","body":"## Goal\n\n$title.\n\n## Acceptance criteria\n\n- [ ] $title is in.\n\n## Allowed scope\n\n- May push to main: no\n","kind":"$kind","points":3${if ("dependsOn" in extra) "" else ""","dependsOn":[]"""}$extra}"""
    private val split2 = """{"outcome":"split",$plan,"questions":[],"children":[${child("Part one")},${child("Part two", extra = ""","dependsOn":[0]""")}]}"""
    private val done = """{"outcome":"done","title":"A part","summary":"Built it."}"""
    private val passed = """{"outcome":"passed","summary":"fine","findings":[]}"""
    private val approved = """{"outcome":"approved","summary":"fine","findings":[]}"""
    private val cont = """{"outcome":"continue","summary":"Still fits."}"""
    private val accepted = """{"outcome":"accepted","summary":"Both hold.","criteria":[{"criterion":"Both parts are in","met":true,"how":"read main"}]}"""
    private fun filedChildren(n: Int) = (0 until n).map { FiledChild(it, "Filed $it", 100L + it, "u", "chore", "merged", 7) }
    private val chore = arrayOf("work" to done, "security" to passed, "review" to approved)

    @Test
    fun `a split files only the first child, defers the parent, replans after each merge, and accepts after the last`() {
        sandbox.reset("design" to split2, *chore, *chore, "replan" to cont, "replan" to cont, "accept" to accepted)
        val feature = start(50, "auto")

        // Child 1 filed, blocking the parent, its chore started and run through; the parent waits.
        val (_, first) = github.created.single()
        assertEquals(listOf("chore"), first.labels)
        assertTrue("Part 1 of 2 of #50." in first.body && "<!-- engine: split of #50 · tier 8 · points 3 · p2 · by alice -->" in first.body,
            "the child inherits the parent's asker: ${first.body}")
        assertEquals(listOf(Triple(repo, 50L, 100L)), github.blockedBy)
        assertEquals(listOf("event-child-closed"), runtime.getActiveActivityIds(feature.id))
        assertEquals(listOf("design", "work", "security", "review"), sandbox.started.map { it.mode })
        val row = assertNotNull(deferred.get(repo, 50))
        assertEquals(feature.id to 0L, row.waitingInstance to row.labelEvent)
        assertEquals("authorized by @lundmatt1 via parent #50" to "alice",
            handled.list(repo).single { it.issue == 100L }.let { it.detail to it.askedBy }, "the child's row carries the parent's asker")
        assertEquals(3, handled.points(repo, 100), "the child's points are recorded as an intake-filed ticket's, for GET /ticket and the parent's progress bar")
        assertTrue(github.commentsOn(50).any { "Filed child #100 (part 1 of 2); waiting on it." in it })
        assertEquals(1, split(feature.id).drafts.size)

        // The merge closes it; the scan wakes the parent, which replans and files child 2.
        childClosed(50, 100, merged = true)
        assertEquals(listOf("resumed"), scanner.scan().actions.map { it.outcome })
        runAllJobs()
        val replan = sandbox.started.first { it.mode == "replan" }
        val told = brief(replan)!!.split!!
        assertEquals(100 to 7, told["closedChild"] to told["closedChildPr"])
        assertEquals("Two parts.", brief(replan)!!.plan!!["summary"])
        assertEquals(2, github.created.size)
        assertTrue("Part 2 of 2 of #50." in github.created[1].second.body)
        assertEquals(listOf("event-child-closed"), runtime.getActiveActivityIds(feature.id))
        assertNull(deferred.get(repo, 50)?.takeIf { it.waitingInstance == null })

        // The last child merges: the replan is the coherence check, then the parent's own acceptance.
        childClosed(50, 101, merged = true)
        scanner.scan(); runAllJobs()
        assertTrue(history.finished(feature.id), incidents())
        assertEquals(listOf("design", "work", "security", "review", "replan", "work", "security", "review", "replan", "accept"), sandbox.started.map { it.mode })
        val closing = github.commentsOn(50).last()
        assertTrue("Merged children #100 (PR #7), #101 (PR #7)" in closing && "Accepted: Both hold." in closing, closing)
        assertEquals(listOf("merged", "merged"), split(feature.id).filed.map { it.state })
    }

    @Test
    fun `a child whose work splits it closes as split and the parent's replan files its drafts in its place, woken once`() {
        val childSplit = """{"outcome":"split","summary":"Too big for one run.","children":[${child("Part one a")},${child("Part one b", extra = ""","dependsOn":[0]""")}]}"""
        // The first draft's chore then parks on a question, so the test stops there.
        sandbox.reset("design" to split2, "work" to childSplit, "replan" to cont, "work" to """{"outcome":"ask","question":"Which one?"}""")
        val feature = start(52, "auto")

        // Child #100's work split it: closed as not planned, the drafts listed, the parent's row gone and the parent replanned.
        assertEquals(NOT_PLANNED, github.closedAs[repo to 100L], incidents())
        assertTrue(github.commentsOn(100).any { "Split into 2 parts by the work run; handed back to #52" in it && "Part one b" in it }, github.commentsOn(100).toString())
        assertEquals(listOf("design", "work", "replan", "work"), sandbox.started.map { it.mode })
        assertEquals(52L, brief(sandbox.started[1])!!.parent, "the child's work brief names its parent")
        val told = brief(sandbox.started[2])!!.split!!
        assertEquals(100, told["closedChild"])
        assertEquals(listOf("Part one a", "Part one b"), (told["closedChildSplit"] as List<*>).map { (it as Map<*, *>)["title"] })

        // The first draft is filed next, in the child's place, blocking the parent and started via the parent: no intake.
        assertEquals(listOf("Part one a"), github.created.drop(1).map { it.second.title })
        assertTrue("Part 2 of 4 of #52." in github.created[1].second.body, github.created[1].second.body)
        assertEquals(listOf(Triple(repo, 52L, 100L), Triple(repo, 52L, 101L)), github.blockedBy)
        assertEquals("authorized by @lundmatt1 via parent #52", handled.list(repo).single { it.issue == 101L }.detail)
        assertEquals(listOf("split", "open"), split(feature.id).filed.map { it.state })
        assertEquals(listOf("Part one b", "Part two"), split(feature.id).drafts.map { it.title })
        assertEquals(listOf(listOf(1), listOf(2)), split(feature.id).drafts.map { it.dependsOn }, "b waits on a (#101, index 1), Part two on b")
        assertTrue(github.commentsOn(52).any { "Child #100 split into 2 parts by its work run; replan: continue" in it }, github.commentsOn(52).toString())

        // GitHub shows #100 closed without a merge and #101 open: the scan does not wake the parent again for #100.
        childClosed(52, 100, merged = false)
        blockedBy(52, 101)
        assertEquals(emptyList(), scanner.scan().actions.filter { it.issue == 52L }.map { it.outcome })
        runAllJobs()
        assertEquals(1, sandbox.started.count { it.mode == "replan" })
        assertEquals(listOf("event-child-closed"), runtime.getActiveActivityIds(feature.id))
        assertTrue(github.commentsOn(52).none { "closed without a merge" in it }, "the split child is no stall")
    }

    /**
     * #2593: a child that splits while its parent is not parked on it (the row gone, as between a replan and the next
     * park) closes itself with no incident; the scan's wake, once the parent is parked on it again, hands the drafts over.
     */
    private fun splitWhileNotParked(issue: Long, mode: String) {
        repos.setChildMode(repo, mode)
        val childSplit = """{"outcome":"split","summary":"Too big for one run.","children":[${child("Part one a")},${child("Part one b", extra = ""","dependsOn":[0]""")}]}"""
        sandbox.reset("design" to split2, "work" to childSplit, "replan" to cont, "work" to """{"outcome":"ask","question":"Which one?"}""")
        sandbox.holdMode = "work"
        val feature = start(issue, "auto")
        val row = assertNotNull(deferred.get(repo, issue), incidents())
        deferred.remove(repo, issue)

        sandbox.stop("container-${sandbox.started.indexOfFirst { it.mode == "work" } + 1}"); runAllJobs()
        assertEquals(emptyList(), runtime.createIncidentQuery().list().map { it.incidentMessage })
        assertEquals(NOT_PLANNED, github.closedAs[repo to 100L])
        assertTrue(github.commentsOn(100).any { "Split into 2 parts by the work run; handed back to #$issue" in it }, github.commentsOn(100).toString())
        assertEquals(0, sandbox.started.count { it.mode == "replan" }, "nothing woke the parent yet")

        // Parked again on #100 (closed): the scan wakes it with the drafts, not as a nothing-to-do close.
        deferred.defer(row)
        childClosed(issue, 100, merged = false)
        assertEquals(listOf("resumed"), scanner.scan().actions.filter { it.issue == issue }.map { it.outcome })
        runAllJobs()
        val told = brief(sandbox.started.first { it.mode == "replan" })!!.split!!
        assertEquals(listOf("Part one a", "Part one b"), (told["closedChildSplit"] as List<*>).map { (it as Map<*, *>)["title"] })
        assertEquals("split", split(feature.id).filed.first().state)
        assertEquals("Part one a", github.created[1].second.title)
        assertEquals(emptyList(), runtime.createIncidentQuery().list().map { it.incidentMessage })
    }

    @Test
    fun `a serial child that splits while its parent is not parked on it closes itself, and the scan hands its drafts to the parent`() =
        splitWhileNotParked(56, "serial")

    @Test
    fun `a parallel child that splits while its parent is not parked on it closes itself, and the scan hands its drafts to the parent`() =
        splitWhileNotParked(57, "parallel")

    @Test
    fun `a parallel child that splits while its parent is parked on it hands its drafts over at once`() {
        repos.setChildMode(repo, "parallel")
        val childSplit = """{"outcome":"split","summary":"Too big for one run.","children":[${child("Part one a")},${child("Part one b")}]}"""
        sandbox.reset("design" to split2, "work" to childSplit, "replan" to cont, "work" to """{"outcome":"ask","question":"Which one?"}""",
            "work" to """{"outcome":"ask","question":"Which one?"}""")
        val feature = start(59, "auto")
        assertEquals(emptyList(), runtime.createIncidentQuery().list().map { it.incidentMessage })
        assertEquals(NOT_PLANNED, github.closedAs[repo to 100L])
        assertEquals("split", split(feature.id).filed.first().state)
        assertEquals(listOf("Part one a", "Part one b"), github.created.drop(1).map { it.second.title })
    }

    @Test
    fun `a child that splits after its parent's flow is gone is an incident naming what to do, and closes nothing`() {
        val childSplit = """{"outcome":"split","summary":"Too big for one run.","children":[${child("Part one a")},${child("Part one b")}]}"""
        sandbox.reset("design" to split2, "work" to childSplit)
        sandbox.holdMode = "work"
        val feature = start(58, "auto")
        runtime.deleteProcessInstance(feature.id, "test: the parent is gone")
        sandbox.stop("container-${sandbox.started.indexOfFirst { it.mode == "work" } + 1}"); runAllJobs()
        val message = runtime.createIncidentQuery().list().mapNotNull { it.incidentMessage }.single()
        assertTrue("parent #58 has no feature flow running" in message && "files the drafts" in message, message)
        assertTrue(repo to 100L !in github.closedAs, "nothing closed")
    }

    @Test
    fun `a split result on a ticket with no parent is refused and closes nothing`() {
        val splitAlone = """{"outcome":"split","summary":"Too big.","children":[${child("A")},${child("B")}]}"""
        // Refused once (a rules finding sends the round back), then refused for good: an incident, nothing closed.
        sandbox.reset("design" to """{"outcome":"planned",$plan,"questions":[]}""", "work" to splitAlone, "work" to splitAlone)
        start(53, "auto")
        assertTrue(runtime.createIncidentQuery().list().any { "no parent" in (it.incidentMessage ?: "") }, incidents())
        assertTrue(repo to 53L !in github.closedAs, "nothing closed")
        assertTrue(github.created.isEmpty())
    }

    @Test
    fun `cancelling a split parent stops its running child, closes both as not planned, and drops the deferred rows`() {
        sandbox.reset("design" to split2, "work" to done); sandbox.holdMode = "work"
        val feature = start(70, "auto")
        assertEquals(listOf("event-child-closed"), runtime.getActiveActivityIds(feature.id))
        assertNotNull(deferred.get(repo, 70))

        intakes.cancel(feature.id, CancelRequest(reason = "scope changed"), TestTokens.asLogin("lundmatt1"))

        assertEquals(listOf("container-2"), sandbox.stopped, "the child's work run")
        assertEquals(0L, runtime.createProcessInstanceQuery().count(), incidents())
        assertEquals(NOT_PLANNED, github.closedAs[repo to 100L])
        assertEquals(NOT_PLANNED, github.closedAs[repo to 70L])
        assertNull(deferred.get(repo, 70)); assertNull(deferred.get(repo, 100))
        val child = github.commentsOn(100).last()
        assertTrue("@lundmatt1 cancelled this chore" in child && "with its parent #70: scope changed" in child && "Its running Smith is stopped." in child, child)
        val parent = github.commentsOn(70).last()
        assertTrue("@lundmatt1 cancelled this feature" in parent && "Open child #100 cancelled and closed as not planned." in parent &&
            "Dropped: 1 part not filed yet." in parent, parent)
        assertTrue(pulls.closed.isEmpty())

        // Nothing restarts: both issues are closed, and the scan lists only open ones.
        for (n in listOf(70L, 100L)) {
            tickets.ended += repo.lowercase() to n
            tickets.forTicket(repo, n)!!.takeIf { it.open }?.let { tickets.setState(it.id, TicketState.CANCELLED, "closed by the test") }
        }
        scanner.scan(); runAllJobs()
        assertEquals(0L, runtime.createProcessInstanceQuery().count())
    }

    @Test
    fun `a split of a proposal's build files each child with the parent's Builds line, and the child's work is told the doc`() {
        sandbox.reset("design" to split2, *chore)
        val feature = start(54, "auto", "Builds: docs/proposals/x.md\n\n## Goal\n\nA wide feature.\n")
        val (_, first) = github.created.single()
        assertTrue(first.body.startsWith("Builds: docs/proposals/x.md\n\n## Goal"), first.body)
        assertEquals("docs/proposals/x.md", brief(sandbox.started.first { it.mode == "design" })!!.builds, "the parent's design")
        assertEquals("docs/proposals/x.md", brief(sandbox.started.first { it.mode == "work" })!!.builds, "the child's work")
        runtime.deleteProcessInstance(feature.id, "test done")
    }

    @Test
    fun `revise replaces the remaining drafts, and done drops them and goes straight to acceptance`() {
        val revise = """{"outcome":"revise","summary":"Part two moved.","children":[${child("Part two, revised")}]}"""
        sandbox.reset("design" to split2, *chore, *chore, "replan" to revise, "replan" to """{"outcome":"done","summary":"Enough."}""", "accept" to accepted)
        val feature = start(51, "auto")
        childClosed(51, 100, merged = true); scanner.scan(); runAllJobs()
        val state = split(feature.id)
        assertEquals("Part two, revised", github.created[1].second.title)
        assertEquals(listOf("Part two"), state.dropped)
        assertEquals(listOf("Part one", "Part two, revised"), state.filed.map { it.title })

        childClosed(51, 101, merged = true); scanner.scan(); runAllJobs()
        assertTrue(history.finished(feature.id), incidents())
        assertEquals(2, github.created.size, "done files nothing more")
        assertEquals("accept", sandbox.started.last().mode)
    }

    @Test
    fun `a three-part split whose replan says done after the first part files nothing more`() {
        val split3 = """{"outcome":"split",$plan,"questions":[],"children":[${child("One")},${child("Two")},${child("Three")}]}"""
        sandbox.reset("design" to split3, *chore, "replan" to """{"outcome":"done","summary":"One did it all."}""", "accept" to accepted)
        val feature = start(52, "auto")
        childClosed(52, 100, merged = true); scanner.scan(); runAllJobs()
        assertTrue(history.finished(feature.id), incidents())
        assertEquals(1, github.created.size)
        assertEquals(listOf("Two", "Three"), split(feature.id).dropped)
        assertTrue("Merged children #100 (PR #7)" in github.commentsOn(52).last())
    }

    @Test
    fun `a child closed without a merge parks the parent with one comment and no replan`() {
        sandbox.reset("design" to split2, *chore, "replan" to cont)
        sandbox.failMode = "work"; sandbox.failModeRuns = 2 // the child's chore dies, past its automatic rerun; someone closes the child by hand
        val feature = start(53, "auto")
        childClosed(53, 100, merged = false)
        repeat(3) { assertEquals(emptyList(), scanner.scan().actions) }
        runAllJobs()
        assertEquals(listOf("event-child-closed"), runtime.getActiveActivityIds(feature.id))
        assertEquals(1, github.commentsOn(53).count { "closed without a merge; re-authorize to proceed" in it })
        assertEquals(emptyList(), sandbox.started.filter { it.mode == "replan" })
        assertNotNull(deferred.get(repo, 53))
        runtime.deleteProcessInstance(feature.id, "test done")
    }

    @Test
    fun `re-authorizing a parent parked on a child closed without a merge resumes it to the replan, the child satisfied`() {
        sandbox.reset("design" to split2, *chore, "replan" to cont, *chore, "replan" to cont, "accept" to accepted)
        sandbox.failMode = "work"; sandbox.failModeRuns = 2 // the child's chore dies, past its automatic rerun; someone closes the child by hand
        // The ticket: a feature, auto, whose labels the approval checks read.
        tickets.create(NewWorkItem(repo, "# Wide feature\n\n## Goal\n\nA wide feature.\n", "feature", WorkItemSourceRef(WorkItemSourceRef.RAISED),
            mode = "auto", ticket = 61, author = "lundmatt1"))
        val feature = start(61, "auto")
        // The approval that started it, as the scan records a label start.
        handled.claim(repo, 61, 61_003, "feature", "started", null); handled.started(repo, 61, 61_003, feature.id)

        // Closed by hand: parked, with the comment saying how to resume it.
        childClosed(61, 100, merged = false)
        assertEquals(emptyList(), scanner.scan().actions)
        assertTrue(github.commentsOn(61).any { "Re-authorizing it (First mate's `reauthorize`) resumes it and treats #100 as satisfied" in it }, github.commentsOn(61).toString())

        // First mate's re-authorize.
        val resumed = assertNotNull(scanner.consider(repo, 61, IssueEvent(61_005, "labeled", "lundmatt1", OffsetDateTime.now(), "authorized")),
            "the re-authorize resumes it")
        assertEquals("resumed" to 61L, resumed.outcome to resumed.issue)
        assertTrue("child #100, closed without a merge, treated as satisfied" in resumed.detail, resumed.detail)
        assertEquals("resumed", handled.list(repo).single { it.issue == 61L && it.labelEvent == 61_005L }.outcome)
        runAllJobs()
        val told = brief(sandbox.started.first { it.mode == "replan" })!!.split!!
        assertEquals(100 to null, told["closedChild"] to told["closedChildPr"])
        assertEquals(listOf("satisfied", "open"), split(feature.id).filed.map { it.state }, incidents())
        assertEquals("Part two", github.created[1].second.title, "part two depended on part one, now satisfied")
        assertEquals(listOf("event-child-closed"), runtime.getActiveActivityIds(feature.id))
        assertEquals(feature.id, assertNotNull(deferred.get(repo, 61)).waitingInstance, "parked again, on part two")

        // Part two merges; #61 is still blocked by #100 too. Only the child it waits on now decides.
        val stallsBefore = github.commentsOn(61).count { "closed without a merge" in it }
        childClosed(61, 101, merged = true)
        assertEquals(listOf(100L, 101L), tickets.blockers(repo, 61).map { it.number })
        assertEquals(listOf("child #101 merged (PR #7)"), scanner.scan().actions.map { it.detail })
        runAllJobs()
        assertEquals(stallsBefore, github.commentsOn(61).count { "closed without a merge" in it }, github.commentsOn(61).toString())
        val last = brief(sandbox.started.last { it.mode == "replan" })!!.split!!
        assertEquals(101 to 7, last["closedChild"] to last["closedChildPr"])
        assertEquals(listOf("satisfied", "merged"), split(feature.id).filed.map { it.state })
        assertTrue(history.finished(feature.id), incidents())
        runtime.createProcessInstanceQuery().rootProcessInstances().list().forEach { runtime.deleteProcessInstance(it.id, "test done") }
    }

    @Test
    fun `a child whose work finds nothing to do closes with no pr, and the parent replans and files the part that depended on it`() {
        sandbox.reset("design" to split2, "work" to """{"outcome":"nothing-to-do","reason":"Part one is already on main."}""",
            "replan" to cont, *chore, "replan" to cont, "accept" to accepted)
        val feature = start(60, "auto")
        assertEquals(listOf("design", "work"), sandbox.started.map { it.mode })
        assertTrue(github.commentsOn(100).any { it.startsWith("Closed: nothing to do. Part one is already on main.") }, github.commentsOn(100).toString())
        assertTrue(github.closed.contains(repo to 100L))

        // Closed as completed with no PR; the engine's own close counts as satisfied and wakes the parent: by the close's
        // own wake (CloseWake, joined here; the parent's work item is linked to the child at filing, #2662), which leaves
        // the scan nothing and no row, or by the scan as the backstop. Either way the parent is woken exactly once.
        // The child's flow may still have jobs left after its close (its end), and a scan run before them sees it running
        // and leaves the row: run them first, so the scan sees the child's flow ended.
        blockedBy(60, 100); runAllJobs()
        val scanned = scanner.scan().actions.map { it.detail }
        assertTrue(scanned == listOf("child #100 closed with nothing to do") || (scanned.isEmpty() && deferred.get(repo, 60) == null),
            "scan: $scanned; deferred: ${deferred.get(repo, 60)}; ${incidents()}")
        runAllJobs()
        val told = brief(sandbox.started.first { it.mode == "replan" })!!.split!!
        assertEquals(100 to null, told["closedChild"] to told["closedChildPr"])
        assertEquals(listOf("satisfied", "open"), split(feature.id).filed.map { it.state }, incidents())
        assertTrue(github.commentsOn(60).any { it.startsWith("Child #100 closed with nothing to do; replan: continue") }, github.commentsOn(60).toString())
        assertEquals("Part two", github.created[1].second.title, "part two depended on part one, which is satisfied")

        childClosed(60, 101, merged = true); scanner.scan(); runAllJobs()
        assertTrue(history.finished(feature.id), incidents())
        val closing = github.commentsOn(60).last()
        assertTrue("Merged children #101 (PR #7); closed with nothing to do: #100." in closing, closing)
    }

    @Test
    fun `a work run that overflows its context window re-enters design, told so, instead of retrying`() {
        val planned = """{"outcome":"planned",$plan,"questions":[]}"""
        sandbox.reset("design" to planned, "design" to """{"outcome":"ask","question":"Split how?"}""", "work" to done)
        sandbox.failMode = "work"
        sandbox.logByMode["work"] = "{\"type\":\"assistant\",\"text\":\"Prompt is too long, they say\"}\n" +
            "{\"type\":\"result\",\"subtype\":\"error\",\"is_error\":true,\"terminal_reason\":\"blocking_limit\",\"result\":\"Prompt is too long\"}\n"
        val feature = start(54, "auto")
        assertEquals(listOf("design", "work", "design"), sandbox.started.map { it.mode })
        assertTrue(brief(sandbox.started[2])!!.overflowed)
        assertFalse(brief(sandbox.started[1])!!.overflowed)
        assertTrue(github.commentsOn(54).any { "overflowed its context window" in it })
        assertEquals(listOf("user-task-answer-question"), runtime.getActiveActivityIds(feature.id))
        assertEquals(0L, runtime.createIncidentQuery().processInstanceId(feature.id).count())
        runtime.deleteProcessInstance(feature.id, "test done")
    }

    @Test
    fun `a child's own split is refused with an incident naming the depth limit, and nothing is filed`() {
        sandbox.reset("design" to split2)
        val incidents = runtime.createIncidentQuery().count()
        val feature = start(55, "auto", body = "## Goal\n\nHalf of it.\n\nPart 1 of 2 of #40.\n")
        assertTrue(runtime.createIncidentQuery().count() > incidents)
        val message = runtime.createIncidentQuery().list().map { it.incidentMessage }.last { it != null && "depth limit is 1" in it }
        assertTrue("part 1 of 2 of #40" in message.lowercase(), message)
        assertEquals(emptyList(), github.created)
        assertEquals(emptyList(), github.commentsOn(55))
        runtime.deleteProcessInstance(feature.id, "test done")
    }

    @Test
    fun `a feature child under a confirm parent is filed and started as confirm, whatever the draft says`() {
        // security-1-1: a child must not be looser than its parent.
        val loose = """{"outcome":"split",$plan,"questions":[],"children":[${child("Part one", "feature", ""","featureMode":"auto"""")},${child("Part two")}]}"""
        sandbox.reset("design" to loose, "design" to """{"outcome":"ask","question":"child?"}""")
        val feature = start(56, "confirm")
        val (_, first) = github.created.single()
        assertEquals(listOf("feature", "feature:confirm"), first.labels)
        assertTrue(github.commentsOn(100).any { "Feature started in confirm mode" in it }, github.commentsOn(100).toString())
        val child = handled.list(repo).single { it.issue == 100L }.processInstance!!
        assertEquals("confirm", runtime.getVariable(child, "featureMode"))
        runtime.deleteProcessInstance(feature.id, "test done"); runtime.deleteProcessInstance(child, "test done")
    }

    @Test
    fun `a feature child under an auto parent is filed and started as auto, whatever the draft says`() {
        // #2306: a split feature's children take the parent's mode both ways.
        val strict = """{"outcome":"split",$plan,"questions":[],"children":[${child("Part one", "feature", ""","featureMode":"confirm"""")},${child("Part two")}]}"""
        sandbox.reset("design" to strict, "design" to """{"outcome":"ask","question":"child?"}""")
        val feature = start(57, "auto")
        val (_, first) = github.created.single()
        assertEquals(listOf("feature", "feature:auto"), first.labels)
        val child = handled.list(repo).single { it.issue == 100L }.processInstance!!
        assertEquals("auto", runtime.getVariable(child, "featureMode"))
        runtime.deleteProcessInstance(feature.id, "test done"); runtime.deleteProcessInstance(child, "test done")
    }

    private fun ended(id: String) = history.createHistoricActivityInstanceQuery().processInstanceId(id).activityType("noneEndEvent")
        .list().single { it.activityId != "end-child-wait" }.activityId // the parked sub-process's own end is not the flow's
    private fun userTasks(id: String) = history.createHistoricActivityInstanceQuery().processInstanceId(id).activityType("userTask").list().map { it.activityId }
    private val unmet = """{"outcome":"unmet","summary":"One misses.","criteria":[{"criterion":"Both parts are in","met":false,"how":"part two is not"}]}"""

    @Test
    fun `a confirm-mode split parent checks its own acceptance with an accept smith after the last child, as in auto mode`() {
        val split3 = """{"outcome":"split",$plan,"questions":[],"children":[${child("One")},${child("Two")},${child("Three")}]}"""
        sandbox.reset("design" to split3, *chore, "replan" to """{"outcome":"done","summary":"One did it all."}""", "accept" to accepted)
        val feature = start(57, "confirm")
        childClosed(57, 100, merged = true); scanner.scan(); runAllJobs()
        assertTrue(history.finished(feature.id), incidents())
        assertEquals("end-feature-done", ended(feature.id))
        assertEquals("accept", sandbox.started.last().mode)
        assertEquals(emptyList(), userTasks(feature.id), "nothing after the merge waits on the user")
        assertEquals(repo to 57L, github.closed.last())
        assertTrue(github.commentsOn(57).last().let { "Merged children #100 (PR #7)" in it && "Accepted: Both hold." in it }, github.commentsOn(57).toString())
    }

    @Test
    fun `a confirm-mode split parent whose accept smith finds a criterion unmet is left open`() {
        val split3 = """{"outcome":"split",$plan,"questions":[],"children":[${child("One")},${child("Two")},${child("Three")}]}"""
        sandbox.reset("design" to split3, *chore, "replan" to """{"outcome":"done","summary":"One did it all."}""", "accept" to unmet)
        val feature = start(58, "confirm")
        childClosed(58, 100, merged = true); scanner.scan(); runAllJobs()
        assertTrue(history.finished(feature.id), incidents())
        assertEquals("end-not-accepted", ended(feature.id))
        assertEquals(emptyList(), userTasks(feature.id))
        assertTrue(github.closed.none { it.second == 58L }, "left open for a human")
        assertTrue(github.commentsOn(58).last().let { "found a criterion unmet" in it && "part two is not" in it }, github.commentsOn(58).toString())
    }

    /**
     * #275: a confirm-mode feature child, merged (and so closed on GitHub) while its flow still runs: its accept
     * Smith is checking the criteria, held until the test lets it exit. Returns the parent, the child and the handle.
     */
    private fun confirmChildAtAccept(parent: Long, verdict: String): Triple<String, String, String> {
        val split = """{"outcome":"split",$plan,"questions":[],"children":[${child("Part one", "feature", ""","featureMode":"confirm"""")},${child("Part two", extra = ""","dependsOn":[0]""")}]}"""
        sandbox.reset("design" to split, "design" to """{"outcome":"planned",$plan,"questions":[]}""", *chore, "accept" to verdict, "replan" to cont, *chore)
        sandbox.holdMode = "accept"
        val feature = start(parent, "confirm")
        val child = handled.list(repo).single { it.issue == 100L }.processInstance!!
        assertEquals("accept", sandbox.started.last().mode, incidents())
        assertEquals(1L, pulls.merged.size.toLong(), "the child merged before its accept smith")
        childClosed(parent, 100, merged = true)
        // Closed at the merge but its flow still runs: the scan leaves the parent waiting, with no stall.
        scanner.scan(); runAllJobs()
        assertEquals(listOf("event-child-closed"), runtime.getActiveActivityIds(feature.id))
        assertTrue(sandbox.started.none { it.mode == "replan" })
        return Triple(feature.id, child, "container-${sandbox.started.size}")
    }

    @Test
    fun `a confirm-mode child whose accept smith accepts wakes its parent at once, with no scan`() {
        val (feature, child, accept) = confirmChildAtAccept(70, accepted)
        sandbox.stop(accept)
        runAllJobs()
        assertTrue(history.finished(child), incidents())
        assertEquals(emptyList(), userTasks(child), "a confirm child has no user task after its merge")
        // Woken through `child closed` with the child's PR, as the scan's resume does; the row is gone.
        assertNull(deferred.get(repo, 70)?.takeIf { it.blockers.any { b -> b.number == 100L } })
        val told = brief(sandbox.started.first { it.mode == "replan" })!!.split!!
        assertEquals(100 to 7, told["closedChild"] to told["closedChildPr"])
        assertTrue(github.commentsOn(70).any { it.startsWith("Child #100 merged (PR #7); replan: continue") }, github.commentsOn(70).toString())
        assertEquals("Part two", github.created[1].second.title)
        runtime.deleteProcessInstance(feature, "test done")
    }

    @Test
    fun `a confirm-mode child whose accept smith finds a criterion unmet leaves its parent waiting`() {
        val (feature, child, accept) = confirmChildAtAccept(71, unmet)
        sandbox.stop(accept)
        runAllJobs()
        assertTrue(history.finished(child), incidents())
        assertEquals(listOf("event-child-closed"), runtime.getActiveActivityIds(feature))
        assertEquals(feature, deferred.get(repo, 71)?.waitingInstance)
        assertTrue(sandbox.started.none { it.mode == "replan" })
        runtime.deleteProcessInstance(feature, "test done")
    }

    @Test
    fun `the rules for split, replan and the overflow signal`() {
        assertEquals(emptyList(), DesignResult.parse(split2.toByteArray()).problems())
        val one = """{"outcome":"split",$plan,"children":[${child("Only")}]}"""
        assertTrue(DesignResult.parse(one.toByteArray()).problems().single().contains("needs 2-16 children"))
        val seventeen = """{"outcome":"split",$plan,"children":[${(1..17).joinToString { child("P$it") }}]}"""
        assertTrue(DesignResult.parse(seventeen.toByteArray()).problems().single().contains("not 17"))
        val bad = """{"outcome":"split",$plan,"children":[${child("A", "research")},${child("B", "feature", ""","dependsOn":[1]""")}]}"""
        val problems = DesignResult.parse(bad.toByteArray()).problems()
        assertTrue(problems.any { "kind 'research'" in it } && problems.any { "earlier children only" in it }, problems.toString())
        assertTrue(DesignResult.parse("""{"outcome":"split","children":[${child("A")},${child("B")}]}""".toByteArray()).problems().any { "needs a plan" in it })

        // security-1-2: filed plus revised may not pass 16 parts over the split's life.
        val two = ReplanResult.parse("""{"outcome":"revise","children":[${child("A")},${child("B")}]}""".toByteArray())
        assertEquals(emptyList(), two.problems(filedChildren(14)))
        assertTrue(two.problems(filedChildren(15)).single().contains("at most 16 parts over its life: 15 filed plus 2 revised is 17"))
        assertTrue(ReplanResult.parse("""{"outcome":"revise"}""".toByteArray()).problems(filedChildren(1)).single().contains("needs the remaining children"))
        assertTrue(ReplanResult.parse("""{"outcome":"later"}""".toByteArray()).problems(filedChildren(1)).single().contains("not one of"))

        // A draft left to file is a real ticket, not a filed child restated (#199's replan filed #211 as "Filed as #204, merged in PR #209.").
        val filed = listOf(FiledChild(0, "Part one", 204, "u", "chore", "merged", 209))
        fun revise(vararg drafts: String) = ReplanResult.parse("""{"outcome":"revise","children":[${drafts.joinToString()}]}""".toByteArray()).problems(filed)
        fun draft(title: String, body: String) = """{"title":"$title","body":"$body","kind":"chore","dependsOn":[],"points":2}"""
        val restated = revise(draft("Part two", "Filed as #204, merged in PR #209."))
        assertTrue(restated.any { "no Goal" in it } && restated.any { "no Acceptance criteria" in it } &&
            restated.any { "filed child #204" in it } && restated.any { "merged in PR #209" in it }, restated.toString())
        assertTrue(revise(child("part ONE")).single().contains("title of filed child #204"))
        assertTrue(revise(draft("Two", "## Goal\\n\\n## Acceptance criteria\\n\\n- [ ] x")).single().contains("no Goal"))
        assertTrue(revise(draft("Two", "## Goal\\n\\nDo it.\\n\\n## Acceptance criteria\\n\\nNone listed.\\n")).single().contains("no Acceptance criteria"))
        assertEquals(emptyList(), revise(draft("Two", "## Goal\\n\\nBuilds on #200 (PR #300), after #204.\\n\\n### Acceptance criteria ##\\n\\n1. It works.")))
        // #751: a real ticket that names its merged dependency is kept; a Goal that is only the record is not.
        assertEquals(emptyList(), revise(draft("Two", "## Goal\\n\\nBuilds on the loader merged in PR #209, filed as #204.\\n\\n## Acceptance criteria\\n\\n- [ ] It works.")))
        assertEquals(emptyList(), ReplanResult.parse(cont.toByteArray()).problems(filed, listOf(ChildDraft("Two", "## Goal\n\nRemove the task, building on the loader merged in PR #209.\n\n## Acceptance criteria\n\n- [ ] It is gone.", "chore", dependsOn = emptyList()))))
        val record = revise(draft("Two", "## Goal\\n\\nFiled as #204, merged in PR #209.\\n\\n## Acceptance criteria\\n\\n- [x] Merged."))
        assertTrue(record.size == 2 && record.any { "filed child #204" in it } && record.any { "merged in PR #209" in it }, record.toString())
        // The design drafts the split tests use pass the replan's rules too.
        assertEquals(emptyList(), DesignResult.parse(split2.toByteArray()).children.let { ChildDraft.replanProblems(it, emptyList(), 0) })
        val going = ReplanResult.parse(cont.toByteArray())
        assertEquals(emptyList(), going.problems(filed, DesignResult.parse(split2.toByteArray()).children.drop(1)))
        assertTrue(going.problems(filed, listOf(ChildDraft("Part two", "Filed as #204.", "chore", dependsOn = emptyList()))).any { "filed child #204" in it })
        assertEquals(emptyList(), ReplanResult.parse("""{"outcome":"done"}""".toByteArray()).problems(filed, listOf(ChildDraft("x", "y", "chore", dependsOn = emptyList()))))
        // security-1-1: the heading scan is linear; a 60k-space heading line and a body over GitHub's limit return at once.
        val started = System.nanoTime()
        assertTrue(revise(draft("Two", "#" + " ".repeat(30_000) + "x" + " ".repeat(30_000) + "#")).any { "no Goal" in it })
        assertTrue(ChildDraft.replanProblems(listOf(ChildDraft("Two", "## Goal\n" + " ".repeat(ChildDraft.MAX_BODY), "chore", dependsOn = emptyList())), filed, 1).single().contains("over 65536"))
        assertTrue(System.nanoTime() - started < 2_000_000_000L, "the heading scan took ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    @Test
    fun `a revise past 16 parts in the flow is an incident, and files nothing`() {
        val sixteen = """{"outcome":"revise","summary":"more","children":[${(1..16).joinToString { child("R$it") }}]}"""
        sandbox.reset("design" to split2, *chore, "replan" to sixteen)
        val feature = start(57, "auto")
        childClosed(57, 100, merged = true); scanner.scan()
        val incidents = runtime.createIncidentQuery().count()
        runAllJobs()
        assertTrue(runtime.createIncidentQuery().count() > incidents)
        assertEquals(1, github.created.size)
        runtime.deleteProcessInstance(feature.id, "test done")
    }

    @Test
    fun `a revise that restates the merged child is an incident, and files nothing`() {
        // As #199's replan did: #211 filed with the whole body "Filed as #204, merged in PR #209."
        val restating = """{"outcome":"revise","summary":"more","children":[{"title":"Part two","body":"Filed as #100, merged in PR #7.","kind":"chore","dependsOn":[],"points":2}]}"""
        sandbox.reset("design" to split2, *chore, "replan" to restating)
        val feature = start(58, "auto")
        childClosed(58, 100, merged = true); scanner.scan()
        runAllJobs()
        val message = runtime.createIncidentQuery().processInstanceId(feature.id).list().map { it.incidentMessage }
            .plus(runtime.createIncidentQuery().list().map { it.incidentMessage }).firstOrNull { it != null && "breaks the rules" in it }
        assertNotNull(message, incidents())
        assertTrue("filed child #100" in message && "merged in PR #7" in message && "no Goal" in message, message)
        assertEquals(1, github.created.size)
        runtime.deleteProcessInstance(feature.id, "test done")
    }

    @Test
    fun `a revise whose draft names the merged child as its dependency files it`() {
        // As #751's replan wrote: a real ticket that "builds on the loader merged in PR #761" (here PR 7).
        val building = """{"outcome":"revise","summary":"Part two builds on part one.","children":[{"title":"Part two","body":"## Goal\n\nRemove the task, building on the loader merged in PR #7.\n\n## Acceptance criteria\n\n- [ ] The task is gone.\n","kind":"chore","dependsOn":[],"points":2}]}"""
        sandbox.reset("design" to split2, *chore, "replan" to building)
        val feature = start(62, "auto")
        childClosed(62, 100, merged = true); scanner.scan(); runAllJobs()
        assertEquals(2, github.created.size, incidents())
        assertTrue("merged in PR #7" in github.created[1].second.body, github.created[1].second.body)
        assertEquals(listOf("Part one", "Part two"), split(feature.id).filed.map { it.title })
        runtime.deleteProcessInstance(feature.id, "test done")
    }

    @Test
    fun `a split child needs story points on the scale, at most 13`() {
        fun split(points: String) = DesignResult.parse("""{"outcome":"split",$plan,"children":[${child("A")},{"title":"B","body":"b","kind":"chore","dependsOn":[0]$points}]}""".toByteArray()).problems()
        assertEquals(emptyList(), split(""","points":13"""))
        assertTrue(split("").single().contains("children[1].points null is not one of"), split("").toString())
        assertTrue(split(""","points":21""").single().contains("children[1].points 21"), split(""","points":21""").toString())
        assertTrue(split(""","points":4""").single().contains("children[1].points 4"))
        val revise = ReplanResult.parse("""{"outcome":"revise","children":[{"title":"B","body":"b","kind":"chore","dependsOn":[]}]}""".toByteArray())
        assertTrue(revise.problems(filedChildren(1)).any { "points null" in it })
    }

    @Test
    fun `a continue over an empty draft is an incident, and files nothing`() {
        val empty = """{"outcome":"split",$plan,"questions":[],"children":[${child("Part one")},{"title":"Part two","body":"Later.","kind":"chore","dependsOn":[],"points":2}]}"""
        sandbox.reset("design" to empty, *chore, "replan" to cont)
        val feature = start(59, "auto")
        childClosed(59, 100, merged = true); scanner.scan()
        val incidents = runtime.createIncidentQuery().count()
        runAllJobs()
        assertTrue(runtime.createIncidentQuery().count() > incidents)
        assertTrue(runtime.createIncidentQuery().list().any { "children[1] has no Goal" in it.incidentMessage.orEmpty() }, incidents())
        assertEquals(1, github.created.size)
        runtime.deleteProcessInstance(feature.id, "test done")
    }

    // ---- amend_ticket (#1362) ----

    @Autowired lateinit var ticketAmender: com.codefrak.engine.intake.TicketAmender

    @Test
    fun `an amendment to a split parent parked at child closed cancels nothing, touches no child, and the next replan carries it`() {
        sandbox.reset("design" to split2, *chore, *chore, "replan" to cont)
        val feature = start(52, "auto")
        assertEquals(listOf("event-child-closed"), runtime.getActiveActivityIds(feature.id))
        val children = runtime.createProcessInstanceQuery().rootProcessInstances().list().map { it.id }.toSet()
        val runs = sandbox.started.size

        val amended = ticketAmender.amend(repo, 52, "part two needs a migration", "lundmatt1")
        assertEquals("next replan", amended.work)
        assertNull(amended.lost)
        assertTrue(sandbox.stopped.isEmpty())
        assertEquals(runs, sandbox.started.size)
        assertEquals(listOf("event-child-closed"), runtime.getActiveActivityIds(feature.id))
        assertEquals(children, runtime.createProcessInstanceQuery().rootProcessInstances().list().map { it.id }.toSet(), "no flow cancelled")
        assertTrue(github.edited.none { it.second == 100L } && github.commentsOn(100).none { "added:" in it }, "the child is not touched")
        assertTrue(github.commentsOn(52).any { it.endsWith("— engine, the next replan reads it") })
        assertTrue("## Amendments" in github.edited.last { it.second == 52L }.third)

        childClosed(52, 100, merged = true)
        scanner.scan(); runAllJobs()
        val replan = brief(sandbox.started.first { it.mode == "replan" })!!
        assertEquals(listOf("part two needs a migration"), replan.amendments.map { it.text })
        assertTrue("part two needs a migration" in replan.ticket!!.body)
    }

    // ---- steer (#2517) ----

    @Autowired lateinit var starter: com.codefrak.engine.chore.FlowStarter

    @Test
    fun `a replan's steer reaches an open child as a comment and in its next work brief, its running work untouched`() {
        val changes = """{"outcome":"changes","summary":"one thing","findings":[{"ask":"fix the typo","file":"README.md","line":3}]}"""
        val steered = """{"outcome":"continue","summary":"Still fits.","steer":[{"child":100,"message":"use the v2 loader"}]}"""
        // #100's first work run is held (still running at the replan); #101, filed by the replan, parks on a question.
        sandbox.reset("design" to split2, "work" to done, "work" to """{"outcome":"ask","question":"Which one?"}""", "work" to done,
            "security" to passed, "review" to changes, "replan" to steered)
        sandbox.holdMode = "work"
        val feature = start(53, "auto")
        assertEquals(listOf("event-child-closed"), runtime.getActiveActivityIds(feature.id))

        // Serial mode leaves no sibling open while a replan runs: put a second filed child, #99, beside the open #100,
        // and the remaining draft after it, then close #99.
        val state = split(feature.id)
        runtime.setVariable(feature.id, "split", Variables.byteArrayValue(state.copy(
            drafts = state.drafts.map { it.copy(dependsOn = listOf(1)) },
            filed = state.filed + FiledChild(1, "Part zero", 99, "u", "chore")).bytes()))
        assertTrue(starter.steer("no-such-instance", "#53", "x").not(), "an ended instance is not steerable")
        assertTrue(starter.childClosed(feature.id, 99, 7)); runAllJobs()

        assertTrue(sandbox.stopped.isEmpty(), "no child run is killed")
        assertTrue(github.commentsOn(100).any { it.startsWith("Steer from parent #53: use the v2 loader") }, github.commentsOn(100).toString())
        assertTrue(github.commentsOn(53).any { "Child #99 merged (PR #7); replan: continue" in it && "Steered #100." in it }, github.commentsOn(53).toString())
        val child = handled.startedBefore(repo, 100, "chore").single()!!
        assertEquals(listOf("use the v2 loader"), Brief.steer(runtime.getVariable(child, "steer") as ByteArray).map { it.message })

        // #100's held run finishes; its review asks for changes, and the next work run's brief carries the steer.
        sandbox.holdMode = "work"
        sandbox.stop("container-2"); runAllJobs()
        val next = sandbox.started.last { it.mode == "work" }
        val told = brief(next)!!
        assertEquals(100L, told.ticket!!.number)
        assertEquals(1, told.round)
        assertEquals(listOf("#53" to "use the v2 loader"), told.steer.map { it.by to it.message })
    }

}

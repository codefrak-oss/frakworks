package com.codefrak.engine.feature

import com.codefrak.engine.intake.tickets
import com.codefrak.engine.TestTokens
import com.codefrak.engine.accounts.AiAccounts
import com.codefrak.engine.chore.FakeGitHubPulls
import com.codefrak.engine.chore.FakeGitPush
import com.codefrak.engine.chore.ScriptedSandbox
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
import com.codefrak.engine.smith.ensureTestAccount
import com.codefrak.engine.smith.dropTestAccount
import com.codefrak.engine.workitems.FakeTickets
import com.codefrak.engine.workitems.TicketState
import com.codefrak.engine.workitems.WorkItem
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A split feature under `childMode` `parallel` (engine/README.md, "Split features", "Parallel"); the scan is the real one, run by hand. */
@SpringBootTest
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class ParallelSplitProcessTest {
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
    @Autowired lateinit var exits: com.codefrak.engine.smith.SmithExitNotifier
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
    @Autowired lateinit var starter: com.codefrak.engine.chore.FlowStarter

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
        repos.setChildMode(repo, "parallel")
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
        val item = tickets.open(repo).firstOrNull { it.ticket == child }?.let { tickets.details(it.id) }
            ?: run { tickets.ended += repo.lowercase() to child; tickets.forTicket(repo, child)!! }
        tickets.link(tickets.forTicket(repo, parent)!!.id, item.id)
        return item
    }

    /**
     * [parent] blocked by every child filed, open: a child whose own chore flow already closed it is taken up again
     * as a fresh open item ([FakeTickets.reopen]), so the test closes each child itself, in its own order.
     */
    private fun blockedByOpen(parent: Long, vararg children: Long) {
        children.forEach { tickets.link(tickets.forTicket(repo, parent)!!.id, tickets.reopen(repo, it).id) }
    }

    private val plan = """"plan":{"summary":"Two parts.","files":["a","b"],"stages":[],"tests":["gradle test"],"acceptance":["Both parts are in"]}"""
    private fun child(title: String, kind: String = "chore", extra: String = "") =
        """{"title":"$title","body":"## Goal\n\n$title.\n\n## Acceptance criteria\n\n- [ ] $title is in.\n\n## Allowed scope\n\n- May push to main: no\n","kind":"$kind","points":3${if ("dependsOn" in extra) "" else ""","dependsOn":[]"""}$extra}"""
    private val independent3 = """{"outcome":"split",$plan,"questions":[],"children":[${child("Part one")},${child("Part two")},${child("Part three")}]}"""
    private val chain = """{"outcome":"split",$plan,"questions":[],"children":[${child("Part one")},${child("Part two", extra = ""","dependsOn":[0]""")},${child("Part three")}]}"""
    private val split2 = """{"outcome":"split",$plan,"questions":[],"children":[${child("Part one")},${child("Part two")}]}"""
    private val done = """{"outcome":"done","title":"A part","summary":"Built it."}"""
    private val passed = """{"outcome":"passed","summary":"fine","findings":[]}"""
    private val approved = """{"outcome":"approved","summary":"fine","findings":[]}"""
    private val cont = """{"outcome":"continue","summary":"Still fits."}"""
    private val accepted = """{"outcome":"accepted","summary":"Both hold.","criteria":[{"criterion":"Both parts are in","met":true,"how":"read main"}]}"""
    private val chore = arrayOf("work" to done, "security" to passed, "review" to approved)

    @Test
    fun `three independent drafts are all filed and started before any closes, the parent parked on every one`() {
        sandbox.reset("design" to independent3, *chore, *chore, *chore)
        val feature = start(60, "auto")

        assertEquals(listOf("Part one", "Part two", "Part three"), github.created.map { it.second.title }, incidents())
        assertEquals((1..3).map { "Part $it of 3 of #60." }, github.created.map { c -> Regex("""Part \d of 3 of #60\.""").find(c.second.body)?.value })
        assertEquals(listOf(100L, 101L, 102L).map { Triple(repo, 60L, it) }, github.blockedBy)
        assertEquals(3, sandbox.started.count { it.mode == "work" }, "every child started")
        assertEquals(listOf("event-child-closed"), runtime.getActiveActivityIds(feature.id))
        val row = assertNotNull(deferred.get(repo, 60))
        assertEquals(listOf(100L, 101L, 102L), row.blockers.map { it.number })
        assertEquals(feature.id, row.waitingInstance)
        assertEquals(listOf("open", "open", "open"), split(feature.id).filed.map { it.state })
        assertTrue(split(feature.id).filed.all { it.instance != null }, "each child's flow is recorded")
        assertEquals(emptyList(), split(feature.id).drafts)
    }

    @Test
    fun `a dependency chain files only the independent drafts, and the dependent one after its dependency merges`() {
        sandbox.reset("design" to chain, *chore, *chore, *chore, "replan" to cont)
        val feature = start(61, "auto")
        assertEquals(listOf("Part one", "Part three"), github.created.map { it.second.title }, incidents())
        assertEquals(listOf("Part two"), split(feature.id).drafts.map { it.title })
        blockedByOpen(61, 100, 101) // #102 is filed after the replan

        childClosed(61, 100, merged = true)
        assertEquals(listOf("resumed"), scanner.scan().actions.map { it.outcome })
        runAllJobs()
        assertEquals(listOf("Part one", "Part three", "Part two"), github.created.map { it.second.title }, incidents())
        assertTrue("Part 3 of 3 of #61." in github.created[2].second.body)
        // #102's own chore flow runs to its close before the parent re-parks, so only #101 is still open.
        assertEquals(listOf(101L), assertNotNull(deferred.get(repo, 61)).blockers.map { it.number }, "re-parked on every open child")
        assertEquals(listOf("event-child-closed"), runtime.getActiveActivityIds(feature.id))
    }

    @Test
    fun `two parallel children give two replans in close order, and acceptance only after the last close`() {
        sandbox.reset("design" to split2, *chore, *chore, "replan" to cont, "replan" to cont, "accept" to accepted)
        val feature = start(62, "auto")
        assertEquals(2, github.created.size, incidents())
        blockedByOpen(62, 100, 101)

        // #101 closes first: the parent is woken for it alone and re-parks on #100.
        childClosed(62, 101, merged = true)
        assertEquals(listOf("resumed"), scanner.scan().actions.map { it.outcome })
        runAllJobs()
        val first = brief(sandbox.started.first { it.mode == "replan" })!!.split!!
        assertEquals(101, first["closedChild"])
        assertEquals(listOf(true, true), (first["filed"] as List<*>).map { (it as Map<*, *>).containsKey("running") })
        assertEquals(listOf("event-child-closed"), runtime.getActiveActivityIds(feature.id), "#100 still open: no acceptance yet")
        assertEquals(listOf(100L), assertNotNull(deferred.get(repo, 62)).blockers.map { it.number })
        assertTrue(github.commentsOn(62).any { "Child #101 merged (PR #7); replan: continue, 0 draft(s) left, 1 child(ren) open" in it }, github.commentsOn(62).toString())

        // #100 closes: the second replan sees #101 merged, then the parent's acceptance.
        childClosed(62, 100, merged = true)
        assertEquals(listOf("resumed"), scanner.scan().actions.map { it.outcome })
        runAllJobs()
        val second = brief(sandbox.started.last { it.mode == "replan" })!!.split!!
        assertEquals(100, second["closedChild"])
        val filed = (second["filed"] as List<*>).map { it as Map<*, *> }
        assertEquals("merged", filed.single { it["number"] == 101 }["state"])
        assertEquals(7, filed.single { it["number"] == 101 }["prNumber"])
        assertTrue(history.finished(feature.id), incidents())
        assertEquals(listOf("replan", "replan", "accept"), sandbox.started.map { it.mode }.filter { it in setOf("replan", "accept") })
        assertNull(deferred.get(repo, 62))
    }

    @Test
    fun `a replan's steer reaches an open sibling's next brief`() {
        val changes = """{"outcome":"changes","summary":"one thing","findings":[{"ask":"fix the typo","file":"README.md","line":3}]}"""
        // The first child's work is held (still running at the replan); the other's parks on a question.
        sandbox.reset("design" to split2, "work" to done, "work" to """{"outcome":"ask","question":"Which one?"}""", "work" to done,
            "security" to passed, "review" to changes)
        sandbox.holdMode = "work"
        val feature = start(63, "auto")
        assertEquals(2, github.created.size, incidents())
        val works = sandbox.started.filter { it.mode == "work" }
        val held = brief(works[0])!!.ticket!!.number
        val other = brief(works[1])!!.ticket!!.number
        val steered = """{"outcome":"continue","summary":"Still fits.","steer":[{"child":$held,"message":"use the v2 loader"}]}"""
        sandbox.script.getOrPut("replan") { ArrayDeque() }.addLast(steered)

        // The other child closes; the replan sees the held sibling open and running, and steers it.
        assertTrue(starter.childClosed(feature.id, other, 7)); runAllJobs()
        val told = brief(sandbox.started.single { it.mode == "replan" })!!.split!!
        val sibling = (told["filed"] as List<*>).map { it as Map<*, *> }.single { (it["number"] as Number).toLong() == held }
        assertEquals("open" to true, sibling["state"] to sibling["running"])
        assertTrue(github.commentsOn(held).any { it.startsWith("Steer from parent #63: use the v2 loader") }, github.commentsOn(held).toString())
        assertEquals(listOf(held), assertNotNull(deferred.get(repo, 63)).blockers.map { it.number }, "re-parked on the open sibling")

        // The held run finishes; its review asks for changes, and the next work run's brief carries the steer.
        sandbox.holdMode = "work"
        sandbox.stop("container-2"); runAllJobs()
        val next = brief(sandbox.started.last { it.mode == "work" })!!
        assertEquals(held, next.ticket!!.number)
        assertEquals(listOf("#63" to "use the v2 loader"), next.steer.map { it.by to it.message })
    }
}

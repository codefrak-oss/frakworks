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
import org.junit.jupiter.api.assertThrows
import org.springframework.web.server.ResponseStatusException
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

/** A split feature under `childMode` `live` (engine/README.md, "Split features", "Live"): a parent Smith run after filing, and its fallback. */
@SpringBootTest
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class LiveParentProcessTest {
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
    @Autowired lateinit var starter: com.codefrak.engine.chore.FlowStarter
    @Autowired lateinit var restarter: com.codefrak.engine.board.LiveParentRestarter
    @Autowired lateinit var liveParent: com.codefrak.engine.board.LiveParentController

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
        repos.setChildMode(repo, "live")
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

    /** [parent] blocked by every child filed, open. */
    private fun blockedByOpen(parent: Long, vararg children: Long) {
        children.forEach { blockedBy(parent, it) }
    }

    private val plan = """"plan":{"summary":"Two parts.","files":["a","b"],"stages":[],"tests":["gradle test"],"acceptance":["Both parts are in"]}"""
    private fun child(title: String, kind: String = "chore", extra: String = "") =
        """{"title":"$title","body":"## Goal\n\n$title.\n\n## Acceptance criteria\n\n- [ ] $title is in.\n\n## Allowed scope\n\n- May push to main: no\n","kind":"$kind","points":3${if ("dependsOn" in extra) "" else ""","dependsOn":[]"""}$extra}"""
    private val split2 = """{"outcome":"split",$plan,"questions":[],"children":[${child("Part one")},${child("Part two")}]}"""
    private val done = """{"outcome":"done","title":"A part","summary":"Built it."}"""
    private val passed = """{"outcome":"passed","summary":"fine","findings":[]}"""
    private val approved = """{"outcome":"approved","summary":"fine","findings":[]}"""
    private val cont = """{"outcome":"continue","summary":"Still fits."}"""
    private val accepted = """{"outcome":"accepted","summary":"Both hold.","criteria":[{"criterion":"Both parts are in","met":true,"how":"read main"}]}"""
    private val chore = arrayOf("work" to done, "security" to passed, "review" to approved)

    private val parentContinue = """{"outcome":"continue","summary":"Both parts fit together."}"""
    private val parentEnded = """{"outcome":"ended","summary":"turn budget spent"}"""
    private val parentDone = """{"outcome":"done","summary":"Every part merged; the parts together do what the plan set out to do."}"""
    /** Three parts, the third waiting on the first: under live the first two are filed at once, the third by the parent. */
    private val split3 = """{"outcome":"split",$plan,"questions":[],"children":[${child("Part one")},${child("Part two")},${child("Part three", extra = ""","dependsOn":[0]""")}]}"""

    @Test
    fun `one parent run spans the split - it files the third draft mid-run with file_children, watches it, and its done leads to acceptance`() {
        sandbox.reset("design" to split3, *chore, *chore, *chore, "parent" to parentDone, "accept" to accepted)
        sandbox.holdMode = "parent"
        val feature = start(78, "auto")
        assertEquals(2, github.created.size, incidents())
        assertEquals(listOf("call-parent"), runtime.getActiveActivityIds(feature.id))
        val parentRun = sandbox.started.single { it.mode == "parent" }
        assertEquals(1000, parentRun.turns, "the parent's turn budget, engine.smith.parent-turns")
        assertTrue(sandbox.started.filter { it.mode != "parent" }.all { it.turns == null }, "no other mode gets SMITH_TURNS")
        val tools = com.codefrak.engine.mcp.ParentTools(engineCalls, childWatch)
        val caller = com.codefrak.engine.mcp.McpCaller.Smith(com.codefrak.engine.smith.SmithCaller(parentRun.runId, 78, repo))

        // Nothing is ready while #100 is open: the call changes nothing and says so.
        val early = tools.call("file_children", mapOf("outcome" to "continue"), caller)
        assertFalse(early.isError, early.text)
        assertTrue("no draft is ready" in early.text, early.text)
        assertEquals(2, github.created.size)

        // #100 merged while the parent watched: it files the third part itself, and its split now holds three children.
        childClosed(78, 100, merged = true)
        val filed = tools.call("file_children", mapOf("outcome" to "continue", "summary" to "Part one landed; part three goes as drafted."), caller)
        assertFalse(filed.isError, filed.text)
        assertTrue(""""filed":[{"number":102,"title":"Part three","part":3}]""" in filed.text, filed.text)
        runAllJobs()
        assertEquals(3, github.created.size, incidents())
        assertTrue("Part 3 of 3 of #78." in github.created[2].second.body, github.created[2].second.body)
        assertTrue(github.commentsOn(78).any { "Filed child #102 (part 3 of 3)" in it }, github.commentsOn(78).toString())
        assertTrue(github.commentsOn(78).any { "The live parent replanned: continue, 0 draft(s) left; filed #102; #101, #102 open." in it && "Part one landed" in it }, github.commentsOn(78).toString())
        val mid = split(feature.id)
        assertEquals(listOf("merged", "open", "open"), mid.filed.map { it.state }, "the close was recorded before the draft that waited on it was filed")
        assertEquals(setOf(100L, 101L, 102L), engineCalls.parentSplit(parentRun.runId)!!.children.keys, "the next await_children watches the new child too")
        assertEquals(mid, SplitState.parse(runtime.getVariable(parentRun.runId, "split") as ByteArray), "the run's copy follows, so its return does not undo the filing")
        assertEquals(listOf("call-parent"), runtime.getActiveActivityIds(feature.id), "still the one parent run")

        // The last two close; the parent ends with done, and the flow goes to acceptance with no second parent run.
        childClosed(78, 101, merged = true); childClosed(78, 102, merged = true)
        sandbox.stop("container-${sandbox.started.indexOf(parentRun) + 1}"); runAllJobs()
        assertTrue(history.finished(feature.id), incidents())
        assertEquals(listOf("merged", "merged", "merged"), split(feature.id).filed.map { it.state })
        assertEquals(listOf("parent", "accept"), sandbox.started.map { it.mode }.filter { it in setOf("parent", "replan", "accept") })
        assertTrue(github.commentsOn(78).any { "Every child closed under the live parent; its result: done, nothing more to file" in it }, github.commentsOn(78).toString())
    }

    @Test
    fun `a live repo starts a parent run after filing, and its result after the last close leads to acceptance`() {
        sandbox.reset("design" to split2, *chore, *chore, "parent" to parentContinue, "accept" to accepted)
        sandbox.holdMode = "parent"
        val feature = start(70, "auto")
        assertEquals(2, github.created.size, incidents())
        assertEquals(1, sandbox.started.count { it.mode == "parent" }, "one parent run after filing both")
        assertEquals(listOf("call-parent"), runtime.getActiveActivityIds(feature.id))
        assertNull(deferred.get(repo, 70), "the live parent is not parked for the scan")

        // Both children merged while the parent run watched them; its run ends with continue.
        childClosed(70, 100, merged = true); childClosed(70, 101, merged = true)
        val handle = "container-${sandbox.started.indexOfFirst { it.mode == "parent" } + 1}"
        sandbox.stop(handle); runAllJobs()

        assertTrue(history.finished(feature.id), incidents())
        assertEquals(listOf("merged", "merged"), split(feature.id).filed.map { it.state })
        assertEquals(emptyList(), sandbox.started.map { it.mode }.filter { it == "replan" }, "no replan per close")
        assertEquals(listOf("parent", "accept"), sandbox.started.map { it.mode }.filter { it in setOf("parent", "accept") })
        assertTrue(github.commentsOn(70).any { "Every child closed under the live parent; its result: continue" in it }, github.commentsOn(70).toString())
    }

    @Test
    fun `a parent run that ends with two children open falls back, and the two merges complete the parent serially`() {
        sandbox.reset("design" to split2, *chore, *chore, "parent" to parentEnded, "replan" to cont, "replan" to cont, "accept" to accepted)
        val feature = start(71, "auto")
        assertEquals(2, github.created.size, incidents())

        assertTrue(github.commentsOn(71).any { "The live parent session ended (turn budget spent) with #100, #101 still open; continuing one child at a time" in it },
            github.commentsOn(71).toString())
        assertEquals(true, runtime.getVariable(feature.id, "liveFallback"))
        assertEquals(listOf("event-child-closed"), runtime.getActiveActivityIds(feature.id))
        assertEquals(listOf(100L, 101L), assertNotNull(deferred.get(repo, 71)).blockers.map { it.number })
        blockedByOpen(71, 100, 101)

        childClosed(71, 100, merged = true)
        assertEquals(listOf("resumed"), scanner.scan().actions.map { it.outcome })
        runAllJobs()
        assertEquals(listOf("event-child-closed"), runtime.getActiveActivityIds(feature.id), "#101 still open: no parent run again")
        assertEquals(1, sandbox.started.count { it.mode == "parent" })

        childClosed(71, 101, merged = true)
        assertEquals(listOf("resumed"), scanner.scan().actions.map { it.outcome })
        runAllJobs()
        assertTrue(history.finished(feature.id), incidents())
        assertEquals(listOf("parent", "replan", "replan", "accept"), sandbox.started.map { it.mode }.filter { it in setOf("parent", "replan", "accept") })
    }

    @Test
    fun `a fallen-back split restarted by a trusted login runs a new parent run, which records a child closed in between and leads to acceptance`() {
        sandbox.reset("design" to split2, *chore, *chore, "parent" to parentEnded, "parent" to parentContinue, "accept" to accepted)
        val feature = start(73, "auto")
        assertEquals(true, runtime.getVariable(feature.id, "liveFallback"), incidents())
        assertEquals(listOf("event-child-closed"), runtime.getActiveActivityIds(feature.id))
        assertNotNull(deferred.get(repo, 73))

        // Both children merge while the parent is parked; the scan has not woken it yet.
        childClosed(73, 100, merged = true); childClosed(73, 101, merged = true)
        assertEquals(403, assertThrows<ResponseStatusException> {
            liveParent.restart("acme", "tickets", 73, request = TestTokens.asLogin("mallory"))
        }.statusCode.value())
        val restarted = liveParent.restart("acme", "tickets", 73, request = TestTokens.asLogin("lundmatt1"))
        assertEquals(feature.id, restarted.instanceId)
        assertEquals("lundmatt1", restarted.by)
        runAllJobs()

        assertTrue(github.commentsOn(73).any { "The live parent was restarted by @lundmatt1; it watches #100, #101" in it }, github.commentsOn(73).toString())
        assertNull(deferred.get(repo, 73), "the restarted parent is not parked for the scan")
        assertTrue(history.finished(feature.id), incidents())
        assertEquals(listOf("merged", "merged"), split(feature.id).filed.map { it.state })
        assertEquals(listOf("parent", "parent", "accept"), sandbox.started.map { it.mode }.filter { it in setOf("parent", "replan", "accept") })
    }

    // Children ask the parent (#100049; engine/docs/feature.md, "Children ask the parent").

    @Autowired lateinit var questions: com.codefrak.engine.intake.IntakeQuestions
    @Autowired lateinit var hitlpRequests: com.codefrak.engine.hitlp.HitlpRequests
    @Autowired lateinit var hitlpPoller: com.codefrak.engine.hitlp.HitlpPoller

    private val ask = """{"outcome":"ask","question":"Which one?"}"""
    private val kids = setOf(100L, 101L)

    /** The child's root flow (the chore started for it). */
    private fun childFlow(child: Long): String = handled.startedBefore(repo, child, "chore").last { it != null }!!
    private fun handle(spec: com.codefrak.engine.smith.SmithSpec) = "container-${sandbox.started.indexOf(spec) + 1}"
    /** Lets every held work run exit with its scripted result and runs what follows. */
    private fun releaseWork() { sandbox.started.filter { it.mode == "work" }.forEach { sandbox.stop(handle(it)) }; runAllJobs() }

    /** Both children asked `Which one?` through today's path: a HITLP request each, the row for the user, no `question` frame. */
    private fun assertAskedTheUser(cursor: String) {
        for (n in kids) {
            val id = childFlow(n)
            assertEquals(1, hitlpRequests.openFor(id).size, "#$n: the HITLP request is filed")
            assertNull(assertNotNull(questions.pending(id)).askedOf, "#$n: asked of the user")
            assertTrue(github.commentsOn(n).any { it.startsWith("The work Smith has a question (1) before it can go on:\n\nWhich one?") }, github.commentsOn(n).toString())
            assertEquals(listOf("user-task-answer-question"), runtime.getActiveActivityIds(id))
        }
        assertEquals(kids, questions.open().map { it.issue }.toSet())
        assertTrue(childWatch.await(repo, kids, emptySet(), cursor, java.time.Duration.ZERO).changes.none { it.event == "question" }, "no question frame")
    }

    @Test
    fun `a child's question goes to the running live parent, which answers one and hands the other to the user`() {
        sandbox.reset("design" to split2, "work" to ask, "work" to ask, "work" to done, "work" to done, "security" to passed, "security" to passed,
            "review" to approved, "review" to approved, "parent" to parentContinue)
        sandbox.holdModes += setOf("work", "parent")
        val cursor = childWatch.await(repo, kids, emptySet(), null, java.time.Duration.ZERO).cursor
        val feature = start(80, "auto")
        assertEquals(listOf("call-parent"), runtime.getActiveActivityIds(feature.id), incidents())
        val parentRun = sandbox.started.single { it.mode == "parent" }
        assertTrue(sandbox.started.filter { it.mode == "work" }.all { brief(it)!!.liveParent == mapOf("number" to 80) }, "each child's work brief names the live parent")
        sandbox.holdModes -= "work"
        releaseWork()

        // Asked of the parent: no HITLP request (a question is no /needs row since #100013: the HITLP server holds it), the child told, the parent woken with the text.
        for (n in kids) {
            val id = childFlow(n)
            assertTrue(hitlpRequests.openFor(id).isEmpty(), "#$n: no HITLP request")
            assertEquals("#80", assertNotNull(questions.pending(id)).askedOf)
            assertTrue(github.commentsOn(n).any { "asked of its live parent #80, which answers it or hands it to you:\n\nWhich one?" in it }, github.commentsOn(n).toString())
            assertEquals(listOf("user-task-answer-question"), runtime.getActiveActivityIds(id))
        }
        assertTrue(questions.open().none { it.issue in kids }, "not on the user's list")
        val asked = childWatch.await(repo, kids, emptySet(), cursor, java.time.Duration.ZERO).changes.filter { it.event == "question" }
        assertEquals(kids, asked.map { it.number }.toSet())
        assertTrue(asked.all { it.wakes && it.data["state"] == "asked" && it.data["text"] == "Which one?" && it.data["askedOf"] == "#80" }, asked.toString())

        // answer_child: the answer lands in #100's next work brief, by #80.
        val tools = com.codefrak.engine.mcp.ParentTools(engineCalls, childWatch)
        val caller = com.codefrak.engine.mcp.McpCaller.Smith(com.codefrak.engine.smith.SmithCaller(parentRun.runId, 80, repo))
        val answered = tools.call("answer_child", mapOf("child" to 100, "answer" to "The first one."), caller)
        assertFalse(answered.isError, answered.text)
        runAllJobs()
        assertTrue(github.commentsOn(100).any { it.startsWith("Parent #80 answered:\n\nThe first one.") }, github.commentsOn(100).toString())
        val next = sandbox.started.last { it.mode == "work" && it.issue == 100L }
        assertEquals(listOf(com.codefrak.engine.smith.Answer("Which one?", "The first one.", "#80")), brief(next)!!.answers)
        assertEquals("#80", questions.list(childFlow(100)).single().answeredBy)
        assertTrue(hitlpRequests.openFor(childFlow(100)).isEmpty(), "no HITLP request for an answered question")
        assertTrue(tools.call("answer_child", mapOf("child" to 100, "answer" to "again"), caller).isError, "nothing waits any more")

        // escalate: #101's question is filed for the user as today's path files it, and the child waits for the user.
        val id101 = childFlow(101)
        val escalated = tools.call("answer_child", mapOf("child" to 101, "escalate" to true, "note" to "only the user knows"), caller)
        assertFalse(escalated.isError, escalated.text)
        assertEquals(1, hitlpRequests.openFor(id101).size)
        assertNull(assertNotNull(questions.pending(id101)).askedOf)
        assertEquals(listOf(101L), questions.open().map { it.issue })
        assertEquals(listOf(com.codefrak.engine.hitlp.HitlpAsserted.Kind.QUESTION.name), hitlpRequests.openFor(id101).map { it.kind }, "filed as today's path files it")
        assertTrue(github.commentsOn(101).any { "its live parent #80 handed it to you (only the user knows):\n\nWhich one?" in it && "Decide it on" in it }, github.commentsOn(101).toString())
        assertEquals(listOf("user-task-answer-question"), runtime.getActiveActivityIds(id101))
        com.codefrak.engine.hitlp.decideWait(com.codefrak.engine.hitlp.SharedHitlpStub.STUB, hitlpRequests, hitlpPoller, id101, "The second one.")
        runAllJobs()
        assertEquals(listOf(com.codefrak.engine.smith.Answer("Which one?", "The second one.", "lundmatt1")),
            brief(sandbox.started.last { it.mode == "work" && it.issue == 101L })!!.answers)
        assertEquals(listOf("call-parent"), runtime.getActiveActivityIds(feature.id), "the parent ran on throughout")
    }

    @Test
    fun `under parallel a child's question takes today's path`() {
        repos.setChildMode(repo, "parallel")
        sandbox.reset("design" to split2, "work" to ask, "work" to ask)
        val cursor = childWatch.await(repo, kids, emptySet(), null, java.time.Duration.ZERO).cursor
        start(84, "auto")
        assertTrue(sandbox.started.filter { it.mode == "work" }.all { brief(it)!!.liveParent == null })
        assertAskedTheUser(cursor)
    }

    @Test
    fun `under a live split in fallback a child's question takes today's path`() {
        sandbox.reset("design" to split2, "work" to ask, "work" to ask, "parent" to parentEnded)
        sandbox.holdModes += "work"
        val cursor = childWatch.await(repo, kids, emptySet(), null, java.time.Duration.ZERO).cursor
        val feature = start(85, "auto")
        assertEquals(true, runtime.getVariable(feature.id, "liveFallback"), incidents())
        releaseWork()
        assertAskedTheUser(cursor)
    }

    @Test
    fun `a live parent that ends with questions waiting hands every one to the user before the fallback`() {
        sandbox.reset("design" to split2, "work" to ask, "work" to ask, "parent" to parentEnded)
        sandbox.holdModes += setOf("work", "parent")
        val feature = start(86, "auto")
        assertEquals(listOf("call-parent"), runtime.getActiveActivityIds(feature.id), incidents())
        sandbox.holdModes -= "work"
        releaseWork()
        assertTrue(kids.all { questions.pending(childFlow(it))?.askedOf == "#86" && hitlpRequests.openFor(childFlow(it)).isEmpty() })

        sandbox.stop(handle(sandbox.started.single { it.mode == "parent" })); runAllJobs()
        assertEquals(true, runtime.getVariable(feature.id, "liveFallback"), incidents())
        assertTrue(github.commentsOn(86).any { "The live parent session ended (turn budget spent) with #100, #101 still open" in it }, github.commentsOn(86).toString())
        for (n in kids) {
            val id = childFlow(n)
            assertEquals(1, hitlpRequests.openFor(id).size, "#$n: filed for the user")
            assertNull(assertNotNull(questions.pending(id)).askedOf)
            assertTrue(github.commentsOn(n).any { "its live parent #86 handed it to you (the live parent session ended: turn budget spent):\n\nWhich one?" in it }, github.commentsOn(n).toString())
            assertEquals(listOf("user-task-answer-question"), runtime.getActiveActivityIds(id))
        }
        assertEquals(kids, questions.open().map { it.issue }.toSet())
    }

    private val childSplit = """{"outcome":"split","summary":"Too big for one run.","children":[${child("Part one a")},${child("Part one b")}]}"""

    @Test
    fun `a child that splits under a running live parent closes itself, and its drafts are filed after the parent run`() {
        sandbox.reset("design" to split2, "work" to childSplit, *chore, "parent" to parentContinue, "parent" to parentEnded,
            "work" to """{"outcome":"ask","question":"Which one?"}""", "work" to """{"outcome":"ask","question":"Which one?"}""")
        sandbox.holdMode = "parent"
        val feature = start(76, "auto")
        assertEquals(emptyList(), runtime.createIncidentQuery().list().map { it.incidentMessage }, "#2593: no parent row to hand it to is no incident")
        assertEquals(listOf("call-parent"), runtime.getActiveActivityIds(feature.id))
        val splitChild = listOf(100L, 101L).single { github.closedAs[repo to it] == NOT_PLANNED }
        val merged = 201L - splitChild
        assertTrue(github.commentsOn(splitChild).any { "Split into 2 parts by the work run; handed back to #76" in it })

        // The parent run sees both closed (await_children) and ends; reconcile puts the drafts in the split child's place.
        childClosed(76, splitChild, merged = false); childClosed(76, merged, merged = true)
        sandbox.stop("container-${sandbox.started.indexOfFirst { it.mode == "parent" } + 1}"); runAllJobs()
        assertEquals(emptyList(), runtime.createIncidentQuery().list().map { it.incidentMessage })
        val state = split(feature.id)
        assertEquals("split", state.filed.single { it.number == splitChild }.state)
        assertEquals("merged", state.filed.single { it.number == merged }.state)
        assertEquals(listOf("Part one a", "Part one b"), github.created.drop(2).map { it.second.title })
    }

    @Test
    fun `a child that splits under a fallen-back live parent hands its drafts to it at once`() {
        sandbox.reset("design" to split2, "work" to childSplit, *chore, "parent" to parentEnded, "replan" to cont,
            "work" to """{"outcome":"ask","question":"Which one?"}""", "work" to """{"outcome":"ask","question":"Which one?"}""")
        sandbox.holdMode = "work"
        val feature = start(77, "auto")
        assertEquals(true, runtime.getVariable(feature.id, "liveFallback"), incidents())
        assertNotNull(deferred.get(repo, 77))

        sandbox.stop("container-${sandbox.started.indexOfFirst { it.mode == "work" } + 1}"); runAllJobs()
        assertEquals(emptyList(), runtime.createIncidentQuery().list().map { it.incidentMessage })
        val splitChild = listOf(100L, 101L).single { github.closedAs[repo to it] == NOT_PLANNED }
        val told = brief(sandbox.started.first { it.mode == "replan" })!!.split!!
        assertEquals(splitChild.toInt(), told["closedChild"])
        assertEquals("split", split(feature.id).filed.single { it.number == splitChild }.state)
        assertEquals(listOf("Part one a", "Part one b"), github.created.drop(2).map { it.second.title })
    }

    @Test
    fun `a restart is refused, changing nothing, for a live parent still running and for a parallel split`() {
        sandbox.reset("design" to split2, *chore, *chore, "parent" to parentContinue)
        sandbox.holdMode = "parent"
        val live = start(74, "auto")
        assertEquals(listOf("call-parent"), runtime.getActiveActivityIds(live.id), incidents())
        assertEquals(409, assertThrows<ResponseStatusException> { restarter.restart(repo, 74, "lundmatt1") }.statusCode.value())
        assertEquals(listOf("call-parent"), runtime.getActiveActivityIds(live.id))
        runtime.deleteProcessInstance(live.id, "test done")

        repos.setChildMode(repo, "parallel")
        sandbox.holdMode = null
        sandbox.reset("design" to split2, *chore, *chore)
        val parallel = start(75, "auto")
        assertEquals(listOf("event-child-closed"), runtime.getActiveActivityIds(parallel.id), incidents())
        assertEquals(409, assertThrows<ResponseStatusException> { restarter.restart(repo, 75, "lundmatt1") }.statusCode.value())
        assertEquals(listOf("event-child-closed"), runtime.getActiveActivityIds(parallel.id))
        assertNotNull(deferred.get(repo, 75))
        assertEquals(0, sandbox.started.count { it.mode == "parent" })
    }

    @Test
    fun `a split that froze no childMode, as before live mode, files its child and parks serially with no incident`() {
        sandbox.reset("design" to split2, *chore)
        val feature = start72WithoutChildMode()
        assertEquals(emptyList(), runtime.createIncidentQuery().list().map { it.incidentMessage })
        assertEquals(listOf("event-child-closed"), runtime.getActiveActivityIds(feature.id), incidents())
        assertEquals(1, github.created.size, "serial: one child filed")
        assertEquals(0, sandbox.started.count { it.mode == "parent" })
    }

    /** Runs the flow up to file child's job, drops the frozen `childMode` there (a split from before #2531), then the rest. */
    private fun start72WithoutChildMode(): org.operaton.bpm.engine.runtime.ProcessInstance {
        val feature = runtime.createMessageCorrelation("feature filed")
            .setVariables(mapOf("repo" to repo, "issue" to 72L, "featureMode" to "auto", "tier" to 8, "authorizedBy" to "lundmatt1", "askedBy" to "alice",
                "approvedTicket" to Variables.byteArrayValue(Brief.ticketBytes(Ticket(72, "Wide feature", "## Goal\n\nA wide feature.\n", "lundmatt1")))))
            .correlateStartMessage()
        repeat(200) {
            if (management.createJobQuery().processInstanceId(feature.id).activityId("task-file-child").count() > 0) {
                runtime.removeVariable(feature.id, "childMode")
                runAllJobs()
                return feature
            }
            exits.scanExits()
            management.createJobQuery().withRetriesLeft().list().forEach { runCatching { management.executeJob(it.id) } }
        }
        error("never reached file child: ${incidents()}")
    }

    // The parent's tools on child work items (engine/docs/work-items.md, "Live parent on work items"), driven by hand
    // on the real H2 store.

    @Autowired lateinit var itemStore: com.codefrak.engine.workitems.WorkItemStore
    @Autowired lateinit var itemSplits: com.codefrak.engine.workitems.WorkItemSplits
    @Autowired lateinit var itemGate: com.codefrak.engine.workitems.WorkItemGate
    @Autowired lateinit var childWatch: com.codefrak.engine.mcp.ChildWatch
    @Autowired lateinit var engineCalls: com.codefrak.engine.mcp.EngineCalls

    /** A running parent item with two child items filed for it. */
    private fun liveItems(): Pair<String, List<String>> {
        jdbc.sql("DELETE FROM engine.work_item").update()
        val parent = itemStore.create(com.codefrak.engine.workitems.NewWorkItem(repo, "# Wide feature\n\nA wide feature.", "feature",
            com.codefrak.engine.workitems.WorkItemSourceRef(com.codefrak.engine.workitems.WorkItemSourceRef.ASK, "intake-live-items"), tier = 8, mode = "auto"))
        assertTrue(itemGate.start(parent.id))
        val children = listOf("Part one", "Part two").mapIndexed { n, title ->
            itemSplits.childItem(parent.id, n + 1, "chore", null, 8, "# $title\n\n## Goal\n\n$title.\n\nPart ${n + 1} of 2.")
        }
        return parent.id to children
    }

    /** The parent run's tools on [parent]'s split, through the engine's own calls with only the parent run faked. */
    private fun parentTools(parent: String, children: List<String>): Pair<com.codefrak.engine.mcp.ParentTools, com.codefrak.engine.mcp.McpCaller.Smith> {
        val split = com.codefrak.engine.mcp.ParentSplit("run-live-items", repo, 0, "feature-live-items", emptyMap(),
            items = children.associateWith { "chore" }, parentItem = parent)
        val engine = object : com.codefrak.engine.mcp.FrakworksEngine by engineCalls {
            override fun parentSplit(runId: String) = split.takeIf { runId == it.runId }
        }
        return com.codefrak.engine.mcp.ParentTools(engine, childWatch, workItems = itemStore) to
            com.codefrak.engine.mcp.McpCaller.Smith(com.codefrak.engine.smith.SmithCaller("run-live-items", null, repo))
    }

    @Test
    fun `work items - a live parent watches its child items by id until the last one readies the parent`() {
        val (parent, children) = liveItems()
        val (tools, caller) = parentTools(parent, children)
        val cursor = childWatch.await(repo, emptySet(), children.toSet(), null, java.time.Duration.ZERO).cursor

        itemGate.finish(children[0])
        val first = childWatch.await(repo, emptySet(), children.toSet(), cursor, java.time.Duration.ofMillis(500))
        assertTrue(first.changes.any { it.item == children[0] && it.data["state"] == "done" }, first.changes.toString())
        assertEquals(com.codefrak.engine.workitems.WorkItem.BLOCKED, itemStore.get(parent)!!.state)

        val read = tools.call("read_ticket", mapOf("item" to children[1]), caller)
        assertFalse(read.isError, read.text)
        assertTrue("work item ${children[1]}" in read.text && "Part two" in read.text, read.text)
        assertTrue(tools.call("read_ticket", mapOf("item" to "wi-elsewhere"), caller).isError)

        itemGate.finish(children[1])
        val second = childWatch.await(repo, emptySet(), children.toSet(), first.cursor, java.time.Duration.ofMillis(500))
        assertTrue(second.changes.any { it.item == children[1] && it.data["state"] == "done" }, second.changes.toString())
        assertEquals(com.codefrak.engine.workitems.WorkItem.READY, itemStore.get(parent)!!.state, "the last child done readies the parent")
        assertTrue(github.comments.isEmpty(), github.comments.toString())
    }

    @Test
    fun `work items - steer_child on a child item writes a conversation entry stamped steer from parent, and no comment`() {
        val (parent, children) = liveItems()
        val (tools, caller) = parentTools(parent, children)
        val steered = tools.call("steer_child", mapOf("item" to children[0], "message" to "use the new seam"), caller)
        assertFalse(steered.isError, steered.text)
        val entry = itemStore.entries(children[0]).single { it.kind == com.codefrak.engine.workitems.WorkItemEntry.CONVERSATION }
        assertEquals("steer from parent $parent: use the new seam", entry.body)
        assertEquals("engine", entry.author)
        assertTrue(entry.trusted)
        assertTrue("is not a child of run run-live-items's split" in tools.call("steer_child", mapOf("item" to parent, "message" to "x"), caller).text)
        assertTrue(github.comments.isEmpty(), github.comments.toString())
    }

}

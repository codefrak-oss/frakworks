package com.codefrak.engine.doctor

import com.codefrak.engine.intake.tickets
import com.codefrak.engine.TOKEN_LOGIN_ATTRIBUTE
import com.codefrak.engine.board.Needs
import com.codefrak.engine.chore.ScriptedSandbox
import com.codefrak.engine.github.ClosedIssue
import com.codefrak.engine.github.FakeIssueDetails
import com.codefrak.engine.github.IssueListing
import com.codefrak.engine.github.MergedPull
import com.codefrak.engine.intake.TicketFiler
import com.codefrak.engine.github.ReadFakes
import com.codefrak.engine.intake.TicketRecorder
import com.codefrak.engine.jev.FakeJevDecider
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.trigger.FakeFlowStarter
import com.codefrak.engine.workitems.FakeTickets
import com.codefrak.engine.workitems.NewWorkItem
import com.codefrak.engine.workitems.TicketState
import com.codefrak.engine.workitems.Tickets
import com.codefrak.engine.workitems.WorkItem
import com.codefrak.engine.workitems.WorkItemFlags
import com.codefrak.engine.workitems.WorkItemSourceRef
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.RuntimeService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.context.TestPropertySource
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a sighting does besides its row (engine/README.md, "Doctor"): the exact lump, filing an engine defect through
 * an intake the doctor authorized, the stuck ticket's comment once per episode, the gating on both repos'
 * `doctorBugs`, the closes [DoctorBugSync.sync] reads, `GET /doctor/bugs`, Jev's `doctor-sighting` look before filing
 * with the outcomes it is judged by, `PATCH /doctor/sightings/{id}`, repeat sightings, leftovers and regressions after a fix, the `needs-repro` state and its restart cap, and `where: target` filing.
 * Jobs are not executed, so a started intake stays at its first async step.
 */
@SpringBootTest
@Import(ReadFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class DoctorBugsTest {
    @TestConfiguration
    class Fakes {
        @Bean @Primary fun github() = TicketRecorder()
        @Bean @Primary fun tickets(github: com.codefrak.engine.intake.TicketRecorder) = UnlistableTickets(github.tickets())
        @Bean fun sandbox() = ScriptedSandbox()
        @Bean @Primary fun jev(calls: JevCalls) = FakeJevDecider(calls)
        @Bean @Primary fun flows() = FakeFlowStarter()
    }

    @Autowired lateinit var bugs: DoctorBugs
    @Autowired lateinit var sync: DoctorBugSync
    @Autowired lateinit var store: DoctorBugStore
    @Autowired lateinit var sightings: DoctorSightings
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var github: TicketRecorder
    @Autowired lateinit var details: FakeIssueDetails
    @Autowired lateinit var controller: DoctorController
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var history: DoctorHistory
    @Autowired lateinit var jev: FakeJevDecider
    @Autowired lateinit var calls: JevCalls
    @Autowired lateinit var tickets: UnlistableTickets
    @Autowired lateinit var access: Access
    @Autowired lateinit var flows: FakeFlowStarter
    @Autowired lateinit var needs: Needs

    private val stuck = "acme/doctor-stuck"
    private val home = "acme/doctor-defects"
    private val off = "acme/doctor-off"

    @BeforeEach
    fun reset() {
        listOf("intake", "chore").forEach { key ->
            runtime.createProcessInstanceQuery().processDefinitionKey(key).list().forEach { runtime.deleteProcessInstance(it.id, "test reset") }
        }
        listOf("engine.doctor_sighting", "engine.doctor_signature", "engine.doctor_bug").forEach { jdbc.sql("DELETE FROM $it").update() }
        jdbc.sql("UPDATE engine.target_repo SET engine_defects = FALSE").update()
        listOf(stuck, home, off).forEach { repos.remove(it); repos.add(it) }
        github.comments.clear(); github.edited.clear(); github.labelled.clear()
        flows.started.clear(); flows.authorizers.clear()
        details.closed.clear(); details.restComments.clear(); details.merged.clear(); details.failing.clear()
        jev.reset()
        tickets.fake.reset(); tickets.unlistable.clear()
        access.remove("octo", null)
    }

    private fun on() {
        repos.setDoctorBugs(stuck, true, false)
        repos.setDoctorBugs(home, true, true)
    }

    private val stuckSince = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1)

    private val engineAtSmith = Signature("engine", listOf("chore/task-smith"), listOf("smith-run: exit 137"))

    private fun sight(repo: String = stuck, issue: Long = 5, root: String = "root-$repo-$issue", signature: Signature = engineAtSmith,
                      since: OffsetDateTime = stuckSince, incidentSince: OffsetDateTime? = null): DoctorSighting {
        // A sighting is read back joined to its doctor run, as the doctor process writes the run first.
        val run = UUID.randomUUID().toString()
        history.start(run, repo, issue, root, signature.leaves.joinToString(",") { "$it@none" }, since)
        val new = NewSighting(run, null, repo, issue, root, "unfixable", null, signature.where,
            "The smith-run died under the engine\nmore", signature, incidentSince)
        return bugs.act(new, sightings.record(new), "the engine's container limit")
    }

    private fun intakes() = runtime.createProcessInstanceQuery().processDefinitionKey("intake").list()
    private fun bugCount() = jdbc.sql("SELECT COUNT(*) FROM engine.doctor_bug").query(Long::class.java).single()
    private fun lumpedBy(id: Long) = jdbc.sql("SELECT lumped_by FROM engine.doctor_sighting WHERE id = :id").param("id", id).query(String::class.java).single()

    private fun runOf(id: Long) = jdbc.sql("SELECT doctor_run_id FROM engine.doctor_sighting WHERE id = :id").param("id", id).query(String::class.java).single()

    private fun mergedFix(at: OffsetDateTime, title: String = "Engine: task-smith retries a container kill") =
        MergedPull(1366, title, "Closes #1360", "https://github.com/$home/pull/1366", at, listOf(1360))

    @Test
    fun `a fix merged after the stuck instant files nothing and records the sighting against it`() {
        on()
        details.merged[home] = mutableListOf(mergedFix(stuckSince.plusMinutes(10)))
        val seen = sight()
        assertTrue(intakes().isEmpty())
        val bug = assertNotNull(store.ofSignature(engineAtSmith.hash))
        assertEquals(Triple(home, 1360L, "fixed"), Triple(bug.repo, bug.issue, bug.state))
        assertEquals("merged-fix", lumpedBy(seen.id))
        val detail = assertNotNull(history.get(runOf(seen.id))?.detail)
        assertTrue("$home#1360 (PR #1366)" in detail && "after the next deploy" in detail, detail)
        assertTrue(github.comments.isEmpty())
    }

    @Test
    fun `a fix merged before the stuck instant, or naming another task, is no fix, and the ask is filed`() {
        on()
        details.merged[home] = mutableListOf(mergedFix(stuckSince.minusMinutes(10)), mergedFix(stuckSince.plusMinutes(10), "Engine: task-open-pr retries"))
        sight()
        assertEquals(1, intakes().size)
        assertEquals("filing", store.ofSignature(engineAtSmith.hash)?.state)
    }

    @Test
    fun `merged PRs GitHub cannot read file the ask as before`() {
        on()
        details.failing += home
        sight()
        assertEquals(1, intakes().size)
        assertEquals("filing", store.ofSignature(engineAtSmith.hash)?.state)
    }

    @Test
    fun `a new engine signature files through an intake the doctor authorized, and repeats lump into it`() {
        on()
        val first = sight()
        val bug = assertNotNull(store.ofSignature(engineAtSmith.hash))
        assertEquals(home to "filing", bug.repo to bug.state)
        assertEquals(SightingBug(home, null, "filing"), first.bug)
        assertEquals("new", lumpedBy(first.id))
        val intake = intakes().single()
        val vars = runtime.getVariables(intake.id)
        assertEquals(home, vars["repo"])
        assertEquals("doctor" to "doctor", vars["submittedBy"] to vars["authorizedByEngine"])
        assertEquals(bug.id, (vars["doctorBug"] as Number).toLong())
        val ask = Brief.text(vars["ask"])!!
        assertTrue(ask.startsWith("Engine defect: The smith-run died under the engine\nKind: bug\n"), ask)
        assertTrue(engineAtSmith.hash in ask && "https://github.com/$stuck/issues/5" in ask && "the engine's container limit" in ask, ask)
        assertEquals(intake.id, jdbc.sql("SELECT intake_id FROM engine.doctor_bug WHERE id = :id").param("id", bug.id).query(String::class.java).single())
        val comment = github.comments.single { it.first == stuck }
        assertEquals(5L, comment.second)
        assertTrue(comment.third.startsWith("Stuck at `chore/task-smith` since ") &&
            comment.third.endsWith("; filing a bug in $home.\n\n— the engine's doctor"), comment.third)

        // The same episode: lumped, no second intake, no second comment.
        val again = sight()
        assertEquals("exact", lumpedBy(again.id))
        assertEquals(1, intakes().size)
        assertEquals(1, github.comments.count { it.first == stuck })

        // Filed: another ticket's sighting names the issue and counts the tickets.
        store.filed(bug.id, 42)
        sight(issue = 6)
        val filed = github.comments.single { it.second == 6L }.third
        assertTrue(filed.startsWith("Stuck at `chore/task-smith` since ") &&
            filed.endsWith("; this is $home#42 (seen on 2 tickets).\n\n— the engine's doctor"), filed)

        // Exact lumping is whatever the repo: a ticket in a repo with doctorBugs off is lumped, and not commented on.
        val elsewhere = sight(repo = off, issue = 9)
        assertEquals("exact", lumpedBy(elsewhere.id))
        assertEquals(SightingBug(home, 42, "open"), elsewhere.bug)
        assertTrue(github.comments.none { it.first == off })
        assertEquals(1L, bugCount())
    }

    @Test
    fun `a doctor-failed sighting classed rate-limited is recorded and not filed, an exit 1 one is filed`() {
        on()
        val limited = Signature.doctorFailed(listOf("chore/task-smith"), "rate-limited")
        val seen = sight(signature = limited)
        assertNull(seen.bug)
        assertEquals(limited.hash, seen.signature)
        assertTrue(intakes().isEmpty())
        assertEquals(0L, bugCount())
        sight(issue = 6, signature = Signature.doctorFailed(listOf("chore/task-smith"), "exit:1"))
        assertEquals(1, intakes().size)
    }

    @Test
    fun `a doctor-failed sighting classed sandbox-failed is recorded and not filed, an exit 1 one is filed`() {
        on()
        val failed = Signature.doctorFailed(listOf("chore/task-smith"), "sandbox-failed")
        val seen = sight(signature = failed)
        assertNull(seen.bug)
        assertEquals(failed.hash, seen.signature)
        assertTrue(intakes().isEmpty())
        assertEquals(0L, bugCount())
        sight(issue = 6, signature = Signature.doctorFailed(listOf("chore/task-smith"), "exit:1"))
        assertEquals(1, intakes().size)
    }

    @Test
    fun `with either repo off, or no repo marked, or a target defect in a repo off, nothing is filed, commented, labelled or asked of Jev`() {
        val labelled = github.labelled.size
        listed(bugListing(70))
        jev.choice = "#70" to 0.99
        repos.setDoctorBugs(home, true, true)
        sight()
        sight(issue = 8, signature = Signature("target", listOf("chore/task-smith"), emptyList()))
        repos.setDoctorBugs(stuck, true, false)
        repos.setDoctorBugs(home, false, false)
        sight(issue = 7)
        repos.setDoctorBugs(home, true, false)
        sight(issue = 10)
        assertEquals(0L, bugCount())
        assertTrue(intakes().isEmpty())
        assertTrue(github.comments.none { it.first == stuck })
        assertEquals(labelled, github.labelled.size)
        assertEquals(4L, jdbc.sql("SELECT COUNT(*) FROM engine.doctor_sighting WHERE bug_id IS NULL").query(Long::class.java).single(),
            "each sighting recorded, on no bug")
        assertTrue(jev.asked.isEmpty(), "no Jev call where the sighting may not be filed")
    }

    private val now = OffsetDateTime.now(ZoneOffset.UTC)

    private fun bugListing(n: Long, labels: List<String> = listOf("bug")) =
        IssueListing(n, "Engine bug $n", "someone", labels, "https://github.com/$home/issues/$n", now, now.minusMinutes(n), "The sandbox dies.")

    /** [home]'s open tickets are [listings] alone: each one open before is cancelled, each listing a ticket of its kind and flags. */
    private fun listed(vararg listings: IssueListing) {
        tickets.open(home).forEach { tickets.setState(it.id, TicketState.CANCELLED, "not listed") }
        listings.forEach { l ->
            tickets.create(NewWorkItem(home, "# ${l.title}\n\n${l.body.orEmpty()}", l.labels.firstOrNull { it in WorkItem.KINDS } ?: "chore",
                WorkItemSourceRef(WorkItemSourceRef.RAISED), ticket = l.number, author = l.author, flags = WorkItemFlags.of(l.labels)))
        }
    }

    private fun callOf(id: Long): String? =
        jdbc.sql("SELECT jev_call_id FROM engine.doctor_sighting WHERE id = :id").param("id", id).query(String::class.java).optional().orElse(null)

    @Test
    fun `Jev lumps a new engine signature into one of the home repo's bugs at the floor, and the next sighting is exact`() {
        on()
        listed(bugListing(70), bugListing(71, listOf("chore")))
        jev.choice = "#70" to 0.95
        val first = sight()
        assertEquals("jev", lumpedBy(first.id))
        assertEquals(SightingBug(home, 70, "open"), first.bug)
        assertTrue(intakes().isEmpty(), "no intake for a lump")
        assertEquals(store.ofIssue(home, 70), store.ofSignature(engineAtSmith.hash), "the signature is on #70's doctor bug now")

        val (useCase, context, state) = jev.asked.single()
        assertEquals(SightingCheck.USE_CASE, useCase)
        assertEquals(home to null, context.repo to context.issue)
        state as Map<*, *>
        assertEquals("engine", state["where"])
        assertEquals(listOf("chore/task-smith"), state["leaves"])
        assertEquals(listOf("smith-run: exit 137"), state["incidents"])
        assertEquals("the engine's container limit", state["diagnosis"])
        assertEquals(setOf("#70", "new"), (jev.questionSets.single()[SightingCheck.QUESTION]!!.criteria as Map<*, *>).keys, "only the bug-labelled issue")

        val call = calls.get(assertNotNull(callOf(first.id)))!!
        assertEquals(0.9, call.threshold)
        assertEquals("#70 (0.95): lumped", call.action)
        assertTrue(github.comments.single { it.first == stuck }.third.contains("this is $home#70 (seen on 1 ticket)"))

        val again = sight(issue = 6)
        assertEquals("exact", lumpedBy(again.id))
        assertEquals(1, jev.asked.size, "no Jev call for a known signature")
    }

    @Test
    fun `new, a pick below the floor, no answer, no options or an unreadable repo files through intake`() {
        on()
        listed(bugListing(70))
        listOf(("#70" to 0.71) to "#70 below floor 0.9 (0.71): new intake", ("new" to 0.93) to "new (0.93): new intake",
            null to "no answer: new intake").forEachIndexed { i, (choice, action) ->
            jev.choice = choice
            val seen = sight(issue = 20L + i, signature = Signature("engine", listOf("chore/task-$i"), emptyList()))
            assertEquals("new", lumpedBy(seen.id))
            assertEquals("filing", seen.bug?.state)
            assertEquals(action, calls.get(assertNotNull(callOf(seen.id)))!!.action)
        }
        assertEquals(3, intakes().size)

        val asked = jev.asked.size
        listed(bugListing(71, listOf("chore")))
        val none = sight(issue = 30, signature = Signature("engine", listOf("chore/task-none"), emptyList()))
        tickets.unlistable += home
        val unreadable = sight(issue = 31, signature = Signature("engine", listOf("chore/task-502"), emptyList()))
        listOf(none, unreadable).forEach {
            assertEquals("new", lumpedBy(it.id))
            assertNull(callOf(it.id))
        }
        assertEquals(asked, jev.asked.size, "no options, no call")
        assertEquals(5, intakes().size)
    }

    @Test
    fun `the options take the doctor's bugs closed in the last 30 days, with their titles and summaries`() {
        on()
        sight()
        val recent = store.ofSignature(engineAtSmith.hash)!!
        store.filed(recent.id, 80)
        store.close(recent.id, "fixed", now.minusDays(2))
        val old = Signature("engine", listOf("bug/task-old"), emptyList())
        sight(issue = 11, signature = old)
        val gone = store.ofSignature(old.hash)!!
        store.filed(gone.id, 81)
        store.close(gone.id, "declined", now.minusDays(40))
        details.closed[home] = mutableListOf(closed(80, "completed").copy(title = "Sandbox OOM"))
        listed(bugListing(70))
        jev.reset()
        jev.choice = "new" to 0.95

        sight(issue = 12, signature = Signature("engine", listOf("bug/task-new"), emptyList()))
        val options = jev.questionSets.single()[SightingCheck.QUESTION]!!.criteria as Map<*, *>
        assertEquals(listOf("#70", "#80", "new"), options.keys.toList())
        assertEquals("Closed doctor bug: Sandbox OOM; seen as: The smith-run died under the engine", options["#80"])
    }

    @Test
    fun `closes write the outcome of the Jev calls behind the bug - a lump kept, a new kept, a new closed as a duplicate`() {
        on()
        listed(bugListing(70))
        jev.choice = "#70" to 0.95
        val lumped = sight()
        jev.choice = "new" to 0.92
        val missed = Signature("engine", listOf("bug/task-review"), emptyList())
        val filedDup = sight(issue = 11, signature = missed)
        store.filed(store.ofSignature(missed.hash)!!.id, 90)
        val kept = Signature("engine", listOf("feature/task-merge"), emptyList())
        val filedKept = sight(issue = 12, signature = kept)
        store.filed(store.ofSignature(kept.hash)!!.id, 91)

        details.closed[home] = mutableListOf(closed(70, "completed"), closed(90, "not_planned"), closed(91, "completed"))
        details.restComments[home to 90L] = listOf(mapOf("id" to 1L, "body" to "Duplicate of #70", "user" to mapOf("login" to "octo")))
        assertEquals(3, sync.sync())

        assertEquals("lumped #70: kept", calls.get(callOf(lumped.id)!!)!!.outcome)
        assertEquals("new: filed #90, closed as duplicate of #70 by @octo", calls.get(callOf(filedDup.id)!!)!!.outcome)
        assertEquals("new: filed #91, kept", calls.get(callOf(filedKept.id)!!)!!.outcome)
    }

    @Test
    fun `PATCH doctor sightings moves a sighting and its signature, needs a trusted login, and writes the unlump outcome`() {
        on()
        listed(bugListing(70))
        jev.choice = "#70" to 0.95
        val seen = sight()
        val octo = MockHttpServletRequest().apply { setAttribute(TOKEN_LOGIN_ATTRIBUTE, "octo") }
        fun refused(body: MoveSighting, id: Long = seen.id) = assertFailsWith<ResponseStatusException> { controller.move(id, body, octo) }

        assertEquals(HttpStatus.FORBIDDEN, refused(MoveSighting("$home#75")).statusCode, "not a trusted login")
        access.add("octo", "test")
        assertEquals(HttpStatus.UNAUTHORIZED, assertFailsWith<ResponseStatusException> { controller.move(seen.id, MoveSighting("$home#75")) }.statusCode,
            "no token, no login")
        assertEquals(HttpStatus.NOT_FOUND, refused(MoveSighting("$home#75"), id = -1).statusCode)
        assertEquals(HttpStatus.BAD_REQUEST, refused(MoveSighting("#75")).statusCode)
        val by = refused(MoveSighting("$home#75", "octo"))
        assertEquals(HttpStatus.BAD_REQUEST to "by is not a field: the bearer token names the login", by.statusCode to by.reason)
        assertEquals(HttpStatus.CONFLICT, refused(MoveSighting("$home#70")).statusCode)

        val moved = controller.move(seen.id, MoveSighting("$home#75"), octo)
        assertEquals(SightingBug(home, 75, "open"), moved.bug)
        assertEquals(store.ofIssue(home, 75), store.ofSignature(engineAtSmith.hash), "its signature went with it")
        assertEquals("human", lumpedBy(seen.id))
        assertEquals("lumped #70: moved to #75 by @octo", calls.get(callOf(seen.id)!!)!!.outcome, "the token's login is recorded")

        val off = controller.move(seen.id, MoveSighting(null), octo)
        assertNull(off.bug)
        assertNull(store.ofSignature(engineAtSmith.hash), "off any bug: the next sighting is looked at again")
        assertEquals(HttpStatus.CONFLICT, refused(MoveSighting(null)).statusCode)
    }

    private fun closed(issue: Long, reason: String) =
        ClosedIssue(issue, "Engine defect", "https://github.com/$home/issues/$issue", listOf("bug"), OffsetDateTime.now(ZoneOffset.UTC), reason)

    @Test
    fun `closes - completed is fixed, not planned is declined keeping its signatures, a duplicate moves them`() {
        on()
        sight()
        val fixed = store.ofSignature(engineAtSmith.hash)!!
        store.filed(fixed.id, 50)
        val other = Signature("engine", listOf("bug/task-review"), emptyList())
        sight(issue = 11, signature = other)
        val declined = store.ofSignature(other.hash)!!
        store.filed(declined.id, 51)
        val third = Signature("engine", listOf("feature/task-merge"), emptyList())
        sight(issue = 12, signature = third)
        val duplicate = store.ofSignature(third.hash)!!
        store.filed(duplicate.id, 52)

        details.closed[home] = mutableListOf(closed(50, "completed"), closed(51, "not_planned"), closed(52, "not_planned"))
        details.restComments[home to 52L] = listOf(mapOf("id" to 1L, "body" to "Duplicate of #60"))
        assertEquals(3, sync.sync())

        assertEquals("fixed", store.ofSignature(engineAtSmith.hash)!!.state)
        assertEquals(DoctorBugRef(declined.id, home, 51, "declined"), store.ofSignature(other.hash), "declined, its signature kept")
        val moved = assertNotNull(store.ofIssue(home, 60))
        assertEquals("open", moved.state, "#60's doctor bug, made for the move")
        assertEquals(moved, store.ofSignature(third.hash))
        assertEquals("declined", store.ofIssue(home, 52)!!.state)
        assertEquals(moved.id, jdbc.sql("SELECT bug_id FROM engine.doctor_sighting WHERE issue = 12").query(Long::class.java).single())
        assertEquals(0, sync.sync(), "nothing left to watch but #60, which is open")

        // A declined bug's signature lumps a later sighting as a count, with no comment and no new intake.
        val comments = github.comments.size
        val later = sight(issue = 13, signature = other)
        assertEquals("exact", lumpedBy(later.id))
        assertEquals(comments, github.comments.size)
        assertEquals(3, intakes().size)
    }

    @Test
    fun `GET doctor bugs lists the bugs seen since, newest first, with their signatures and counts`() {
        on()
        sight()
        sight(issue = 6)
        val bug = store.ofSignature(engineAtSmith.hash)!!
        val listed = controller.bugs(null)
        val row = listed.bugs.single()
        assertEquals(bug.id to "filing", row.id to row.state)
        assertNull(row.issue)
        assertEquals(listOf(engineAtSmith.hash), row.signatures.map { it.signature })
        assertEquals(listOf("chore/task-smith"), row.signatures.single().leaves)
        assertEquals(2L to 2L, row.tickets to row.runs)
        assertNotNull(row.intakeId)
        assertTrue(controller.bugs(OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).toString()).bugs.isEmpty())
    }

    /** Files the engine signature [signature] as [issue] in the home repo, its body carrying the intake's trailer at [priority]. */
    private fun filedBug(issue: Long, signature: Signature = engineAtSmith, priority: String = "p4", trailerTail: String = ""): DoctorBugRef {
        sight(issue = 5, signature = signature)
        val bug = store.ofSignature(signature.hash)!!
        store.filed(bug.id, issue)
        tickets.create(NewWorkItem(home, "# Engine defect\n\nThe sandbox dies.\n\n<!-- engine: intake abc · tier 5 · $priority$trailerTail -->", "bug",
            WorkItemSourceRef(WorkItemSourceRef.RAISED), ticket = issue, author = "frakworks-bot"))
        return store.ofIssue(home, issue)!!
    }

    @Test
    fun `the priority step leaves the trailer's by intact`() {
        on()
        filedBug(42, trailerTail = " · by doctor")
        sight(issue = 6)
        sight(issue = 7)
        val (_, _, body) = github.edited.single()
        assertTrue(body.endsWith("<!-- engine: intake abc · tier 5 · p3 · by doctor -->"), body)
        assertEquals("doctor", TicketFiler.askedByOf(body))
    }

    private fun bugComments(issue: Long) = github.comments.filter { it.first == home && it.second == issue }.map { it.third }

    @Test
    fun `repeats count distinct tickets - the 2nd comments, the 3rd steps the priority, the 4th asks for review`() {
        on()
        val bug = filedBug(42)

        sight(issue = 5, root = "root-again")
        assertTrue(bugComments(42).isEmpty(), "the same ticket again only counts")

        sight(issue = 6)
        val second = bugComments(42).single()
        assertTrue(second.startsWith("Seen again: $stuck#6, stuck at `chore/task-smith` since ") &&
            second.endsWith("Seen on 2 tickets (3 runs).\n\n— the engine's doctor"), second)
        assertTrue(github.edited.isEmpty() && github.labelled.isEmpty())

        sight(issue = 7)
        val (repo, number, body) = github.edited.single()
        assertEquals(home to 42L, repo to number)
        assertTrue(body.endsWith("<!-- engine: intake abc · tier 5 · p3 -->"), body)
        assertTrue(bugComments(42).last().contains("Seen on 3 tickets (4 runs); priority stepped up to p3."), bugComments(42).last())

        sight(issue = 8)
        assertEquals(Triple(home, 42L, listOf("needs-review")), github.labelled.single())
        assertTrue(bugComments(42).last().contains("Seen on 4 tickets (5 runs); `needs-review`"), bugComments(42).last())
        val row = needs.list(home).single { it.kind == "sighting" }
        assertEquals(home to 42L, row.repo to row.number)
        assertEquals(Needs.REVIEW_BUG, row.wanted)
        assertEquals("https://github.com/$home/issues/42", row.actionId)

        sight(issue = 9)
        assertEquals(4, bugComments(42).size, "the 5th ticket comments")
        assertTrue(bugComments(42).last().contains("Seen on 5 tickets (6 runs)."), bugComments(42).last())
        assertEquals(1, github.edited.size)
        assertEquals(1, github.labelled.size)
        assertEquals(5, store.marks(bug.id).repeatLevel)

        store.close(bug.id, "fixed", null)
        assertTrue(needs.list(home).none { it.kind == "sighting" }, "a closed bug has no row")
    }

    @Test
    fun `a declined bug only counts, the priority stops at p2, and a running flow gets no second`() {
        on()
        val declined = filedBug(43)
        store.close(declined.id, "declined", null)
        listOf(6L, 7L, 8L).forEach { sight(issue = it) }
        assertTrue(bugComments(43).isEmpty())
        assertEquals(4L to 4L, store.counts(declined.id))

        val signature = Signature("engine", listOf("bug/task-reproduce"), emptyList())
        val bug = filedBug(44, signature, priority = "p2")
        assertTrue(store.needsRepro(home, 44))
        // The bug's own flow, still going.
        runtime.createProcessInstanceByKey("chore").startBeforeActivity("user-task-answer-question")
            .setVariables(mapOf("repo" to home, "issue" to 44L)).execute(true, true)
        listOf(6L, 7L, 8L).forEach { sight(issue = it, signature = signature) }
        assertTrue(flows.started.isEmpty(), "no restart while its flow runs")
        assertTrue(github.edited.isEmpty(), "p2 is the top")
        assertEquals(3, bugComments(44).size)
        assertTrue(bugComments(44).all { "its flow is running, so the doctor starts nothing" in it }, bugComments(44).toString())
        assertTrue(github.labelled.isEmpty(), "the comment only at the 4th while its flow runs")
        assertTrue(needs.list(home).none { it.kind == "sighting" })
        assertEquals(0, store.marks(bug.id).restarts)
    }

    @Test
    fun `a needs-repro bug hit by a new ticket gets its reproduce restarted with every sighting, at most twice, and is open once the label is off`() {
        on()
        val bug = filedBug(45)
        assertTrue(store.needsRepro(home, 45), "the bug flow's label needs repro")
        assertEquals("needs-repro", store.ofIssue(home, 45)!!.state)

        sight(issue = 5, root = "root-again")
        assertTrue(flows.started.isEmpty(), "the same ticket restarts nothing")
        sight(issue = 6)
        val (repo, ticket) = flows.started.single()
        assertEquals(home, repo)
        assertEquals(45L, ticket.number)
        assertTrue(ticket.body.startsWith("The sandbox dies.") && "## Sightings (the engine's doctor)" in ticket.body, ticket.body)
        assertTrue("- $stuck#5, `unfixable`" in ticket.body && "- $stuck#6, `unfixable`" in ticket.body, ticket.body)
        assertEquals("the engine's doctor", flows.authorizers.single())
        assertTrue(bugComments(45).single().contains("reproduce restarted"), bugComments(45).toString())

        sight(issue = 7)
        sight(issue = 8)
        assertEquals(2, flows.started.size, "at most twice per bug")
        assertEquals(2, store.marks(bug.id).restarts)

        listed(bugListing(45, listOf("bug", "needs-repro")))
        sync.sync()
        assertEquals("needs-repro", store.ofIssue(home, 45)!!.state, "still labelled")
        listed(bugListing(45))
        assertEquals(1, sync.sync())
        assertEquals("open", store.ofIssue(home, 45)!!.state, "the label came off")
    }

    @Test
    fun `after a fix - an episode from before the close, or before the engine ran the fix, is a leftover, a later one a regression`() {
        on()
        val hour = OffsetDateTime.now(ZoneOffset.UTC)
        val fixed = filedBug(50)
        store.close(fixed.id, "fixed", hour.minusMinutes(30))
        val comments = github.comments.size

        val before = sight(issue = 6, since = hour.minusHours(1))
        assertEquals("leftover", lumpedBy(before.id))
        val notRunning = sight(issue = 7, since = hour.minusMinutes(10))
        assertEquals("leftover", lumpedBy(notRunning.id), "after the close, but the engine has not started since")
        assertEquals(comments, github.comments.size, "a leftover writes nothing")
        assertEquals(1, intakes().size)

        assertEquals(1, store.fixRunning(hour.minusMinutes(20)))
        assertEquals(0, store.fixRunning(hour), "the first start after the close only")
        val early = sight(issue = 8, since = hour.minusMinutes(25))
        assertEquals("leftover", lumpedBy(early.id), "before the fix ran")

        val regression = sight(issue = 9, since = hour.minusMinutes(5))
        assertEquals("new", lumpedBy(regression.id))
        val bug = store.ofSignature(engineAtSmith.hash)!!
        assertEquals(home to "filing", bug.repo to bug.state)
        assertTrue(bug.id != fixed.id, "the signature moved")
        assertEquals(fixed.id, jdbc.sql("SELECT regression_of FROM engine.doctor_bug WHERE id = :id").param("id", bug.id).query(Long::class.java).single())
        assertEquals("fixed", store.ofIssue(home, 50)!!.state, "the old bug is not reopened")
        val ask = runtime.getVariables(intakes().single { runtime.getVariable(it.id, "doctorBug") == bug.id }.id)["ask"].let(Brief::text)!!
        assertTrue(ask.startsWith("Regression of #50: The smith-run died under the engine\nKind: bug\n"), ask)
        assertTrue("regression of $home#50, closed as fixed" in ask, ask)
        assertTrue(github.comments.single { it.second == 9L }.third.endsWith("; filing a bug in $home.\n\n— the engine's doctor"))

        // A target defect needs no engine start: only the close.
        val target = Signature("target", listOf("chore/task-pr"), emptyList())
        repos.setDoctorBugs(stuck, true, false)
        sight(issue = 10, signature = target)
        val targetBug = store.ofSignature(target.hash)!!
        store.filed(targetBug.id, 60)
        store.close(targetBug.id, "fixed", hour.minusMinutes(30))
        assertEquals("leftover", lumpedBy(sight(issue = 11, signature = target, since = hour.minusHours(1)).id))
        assertEquals("new", lumpedBy(sight(issue = 12, signature = target, since = hour.minusMinutes(10)).id))
        val targetRegression = store.ofSignature(target.hash)!!
        assertEquals(stuck, targetRegression.repo)
        assertEquals(targetBug.id, jdbc.sql("SELECT regression_of FROM engine.doctor_bug WHERE id = :id").param("id", targetRegression.id).query(Long::class.java).single())
    }

    @Test
    fun `after a fix - an incident raised before the engine ran the fix is a leftover, even when the doctor first saw it after`() {
        on()
        val hour = OffsetDateTime.now(ZoneOffset.UTC)
        val fixed = filedBug(50)
        store.close(fixed.id, "fixed", hour.minusMinutes(30))
        assertEquals(1, store.fixRunning(hour.minusMinutes(20)))
        val comments = github.comments.size
        val intakes = intakes().size

        val old = sight(issue = 6, since = hour.minusMinutes(5), incidentSince = hour.minusMinutes(25))
        assertEquals("leftover", lumpedBy(old.id), "the incident predates the fix's start")
        assertEquals(fixed.id, store.ofSignature(engineAtSmith.hash)!!.id, "the signature stays on the fixed bug")
        assertEquals(comments, github.comments.size, "a leftover writes nothing")
        assertEquals(intakes, intakes().size, "nothing filed")

        val late = sight(issue = 7, since = hour.minusMinutes(5), incidentSince = hour.minusMinutes(10))
        assertEquals("new", lumpedBy(late.id), "an incident after the fix ran is a regression")
        assertTrue(store.ofSignature(engineAtSmith.hash)!!.id != fixed.id, "the signature moved")
    }

    @Test
    fun `a bug closed before the engine started and marked fixed by sync has its fix running from that start`() {
        on()
        val start = DoctorBugs.jvmStart()
        val fixed = filedBug(55)
        // Closed while the engine was down: still open at startup, so only sync marks it fixed.
        details.closed[home] = mutableListOf(closed(55, "completed").copy(closedAt = start.minusHours(1)))
        assertEquals(1, sync.sync())
        assertEquals("fixed", store.ofIssue(home, 55)!!.state)
        assertEquals(start.toInstant(), store.marks(fixed.id).fixRunningAt?.toInstant())

        val before = sight(issue = 6, since = start.minusMinutes(10))
        assertEquals("leftover", lumpedBy(before.id), "after the close, before the engine ran the fix")
        val after = sight(issue = 7, since = OffsetDateTime.now(ZoneOffset.UTC))
        assertEquals("new", lumpedBy(after.id), "a regression")
        assertEquals(fixed.id, jdbc.sql("SELECT regression_of FROM engine.doctor_bug WHERE id = :id")
            .param("id", store.ofSignature(engineAtSmith.hash)!!.id).query(Long::class.java).single())
    }

    @Test
    fun `a target defect files in the stuck ticket's repo when it has doctorBugs on, and is only recorded when it is off`() {
        on()
        val target = Signature("target", listOf("feature/task-merge"), listOf("merge: conflict"))
        val seen = sight(signature = target)
        val bug = store.ofSignature(target.hash)!!
        assertEquals(stuck to "filing", bug.repo to bug.state)
        assertEquals("new", lumpedBy(seen.id))
        val vars = runtime.getVariables(intakes().single().id)
        assertEquals(stuck, vars["repo"])
        val ask = Brief.text(vars["ask"])!!
        assertTrue(ask.startsWith("Defect in $stuck: The smith-run died under the engine\nKind: bug\n"), ask)
        assertTrue("a defect in its own repo (`where: target`)" in ask, ask)

        val other = Signature("target", listOf("feature/task-review"), emptyList())
        val offSeen = sight(repo = off, issue = 3, signature = other)
        assertNull(offSeen.bug)
        assertNull(store.ofSignature(other.hash))
        assertEquals(1, intakes().size)
    }
}

/** The doctor tests' [FakeTickets], whose [open] fails for a repo in [unlistable]: a repo whose tickets cannot be listed. */
class UnlistableTickets(val fake: FakeTickets) : Tickets by fake {
    val unlistable: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
    override fun open(repo: String) = if (repo in unlistable) error("$repo cannot be listed") else fake.open(repo)
}

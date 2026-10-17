package com.codefrak.engine.intake

import com.codefrak.engine.TestTokens
import com.codefrak.engine.runAllJobs
import com.codefrak.engine.smith.IntakeRef
import com.codefrak.engine.smith.ensureTestAccount
import com.codefrak.engine.smith.dropTestAccount
import com.codefrak.engine.accounts.AiAccounts
import com.codefrak.engine.camunda.IncidentController
import com.codefrak.engine.camunda.RetryRequest
import com.codefrak.engine.chore.FlowStarter
import com.codefrak.engine.chore.ProcessFlowStarter
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.events.EventRecorder
import com.codefrak.engine.github.Blocker
import com.codefrak.engine.github.IssueListing
import com.codefrak.engine.github.IssueSnapshot
import com.codefrak.engine.github.NewIssue
import com.codefrak.engine.github.NOT_PLANNED
import com.codefrak.engine.jev.FakeJevDecider
import com.codefrak.engine.jev.JevAnswer
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.repos.DeferredIssues
import com.codefrak.engine.repos.HandledIssues
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.BRIEF_FILE
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.OUTPUT_PATH
import com.codefrak.engine.smith.SandboxStatus
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.smith.SmithSandbox
import com.codefrak.engine.smith.SmithSpec
import com.codefrak.engine.smith.Vars
import org.operaton.bpm.engine.variable.Variables
import com.codefrak.engine.trigger.IssueScanner
import com.codefrak.engine.workitems.WorkItem
import com.codefrak.engine.workitems.WorkItemStore
import com.codefrak.engine.workitems.WorkItemTickets
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletRequest
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.TaskService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Smith that exits at once, leaving the result.json its `fakeOutcome` names ("none": no file, "bad": breaks a rule).
 * A refine whose brief carries an answer files, whatever `fakeOutcome` says: the question was answered.
 */
class RefiningSandbox : SmithSandbox {
    val started = mutableListOf<SmithSpec>()
    /** A kind to file instead of the one `fakeOutcome` implies, and a feature's mode. */
    var kindOverride: String? = null
    var featureMode: String? = null
    /** The refiner's `userNamed`, as a JSON array, or null for none. */
    var userNamed: String? = null
    /** The refiner's `blockedBy`, as a JSON array, or null for none; and a ticket body instead of the default. */
    var blockedBy: String? = null
    var ticketBody: String? = null
    /** The refiner's `blocker`, for the `insufficient-permissions` outcome; null for none. */
    var blocker: String? = null
    /** The refiner's `repo`, the target repo it names for the ticket; null for none. */
    var repo: String? = null
    override fun start(spec: SmithSpec): String { started += spec; return "container-${spec.issue}" }
    /** True: every sandbox is still running, so a refine run stays in flight; and the handles stopped. */
    var running = false
    val stopped = mutableListOf<String>()
    override fun stop(handle: String) { stopped += handle }
    override fun status(handle: String) = if (running) SandboxStatus(true, null) else SandboxStatus(false, 0)
    override fun logs(handle: String, maxLines: Int) = "smith: done\n"
    override fun output(handle: String, path: String): ByteArray? {
        if (path != OUTPUT_PATH) return null
        val spec = started.last { "container-${it.issue}" == handle }
        val answered = spec.inputs[BRIEF_FILE]?.let { "\"answers\":[{" in String(it) } ?: false
        val outcome = if (answered) "file" else spec.dryOutcome ?: "file"
        val priority = if (outcome == "bad") "p1" else "p3"
        val kind = kindOverride ?: if (outcome == "bug" || outcome == "feature") outcome else "chore"
        val outcomeField = if (outcome == "bad" || outcome == "bug" || outcome == "feature") "file" else outcome
        return when (outcome) {
            "none" -> null
            "ask" -> """{"outcome":"ask","question":"Which widget?"}"""
            "withdrawn" -> """{"outcome":"withdrawn","reason":"Drop it, #2685 already covers this"}"""
            else -> """{"outcome":"$outcomeField","reason":"needs a new secret","kind":"$kind",
                ${featureMode?.let { "\"featureMode\":\"$it\"," }.orEmpty()}${userNamed?.let { "\"userNamed\":$it," }.orEmpty()}
                ${blockedBy?.let { "\"blockedBy\":$it," }.orEmpty()}${blocker?.let { "\"blocker\":${quote(it)}," }.orEmpty()}${repo?.let { "\"repo\":${quote(it)}," }.orEmpty()}
                "ticket":{"title":"Make the widget blue","body":${ticketBody?.let { quote(it) } ?: "\"## Goal\\n\\nBlue.\\n\""},"tier":2,"priority":"$priority","points":2}}"""
        }?.toByteArray()
    }
    override fun remove(handle: String) {}
    private fun quote(text: String) = tools.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(text)
    /** Smith sandboxes by address, for the frakworks-server tests; `runAtFails`: Docker cannot be asked. */
    val callers = mutableMapOf<String, SmithCaller>()
    var runAtFails = false
    override fun runAt(address: String): SmithCaller? = if (runAtFails) error("docker unreachable") else callers[address]
}

/** What one recorded start carried: who authorized it, the ticket it works from, its tier, and any extra vars. */
/** The duplicate check's open tickets, set by each test; a repo in [unlistable] fails as GitHub would. */
class FakeOpenTickets : com.codefrak.engine.board.OpenTickets {
    val listings = mutableMapOf<String, MutableList<IssueListing>>()
    val unlistable = mutableSetOf<String>()
    override fun openTickets(repo: String): List<IssueListing> {
        check(repo !in unlistable) { "GitHub 502" }
        return listings[repo].orEmpty().sortedByDescending { it.updatedAt }
    }
}

data class Started(val by: String, val ticket: IssueSnapshot, val tier: Int?, val extra: Map<String, Any>)

/** Hands intake authorizations to the real starter; records chore, bug and feature starts instead of running them. */
class RecordingFlowStarter(private val real: ProcessFlowStarter) : FlowStarter {
    val chores = mutableListOf<Started>()
    val bugs = mutableListOf<Started>()
    val features = mutableListOf<Started>()
    val proposals = mutableListOf<Started>()
    val research = mutableListOf<Started>()
    override fun start(flow: String, repo: String, ticket: IssueSnapshot, authorizedBy: String,
                       model: String?, effort: String?, tier: Int?, extra: Map<String, Any>): String? = when (flow) {
        "chore" -> { chores += Started(authorizedBy, ticket, tier, extra); "chore-${chores.size}" }
        "bug" -> { bugs += Started(authorizedBy, ticket, tier, extra); "bug-${bugs.size}" }
        "feature" -> { features += Started(authorizedBy, ticket, tier, extra); "feature-${features.size}" }
        "proposal" -> { proposals += Started(authorizedBy, ticket, tier, extra); "proposal-${proposals.size}" }
        "research" -> { research += Started(authorizedBy, ticket, tier, extra); "research-${research.size}" }
        else -> real.start(flow, repo, ticket, authorizedBy, model, effort, tier, extra)
    }
    override fun counts(instance: String?) = real.counts(instance)
    /** Instances that count as a flow's "Done. Merged" close, beside the real ones. */
    val merged = mutableSetOf<String>()
    override fun mergedClose(instance: String?) = instance in merged || real.mergedClose(instance)
    override fun steer(instance: String, by: String, message: String) = real.steer(instance, by, message)
}

/** Intake through the reusable Smith run and `smith-run`; GitHub and Docker faked, jobs driven by hand. */
@SpringBootTest
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class IntakeProcessTest {
    @TestConfiguration
    class Fakes {
        @Bean @Primary fun github() = TicketRecorder()
        @Bean @Primary fun tickets(github: com.codefrak.engine.intake.TicketRecorder) = github.tickets()
        @Bean @Primary fun sandbox() = RefiningSandbox()
        @Bean @Primary fun starter(real: ProcessFlowStarter) = RecordingFlowStarter(real)
        /** The duplicate check's open tickets, as each test lists them in [reader], not the shared fake tickets' every item. */
        @Bean @Primary fun reader() = FakeOpenTickets()
        @Bean @Primary fun jev(calls: JevCalls) = FakeJevDecider(calls)
        /** No summary: every intake's title is the ask's first line, as the tests below expect, and no call leaves the test. */
        @Bean @Primary fun titles() = com.codefrak.engine.accounts.TitleSummarizer { com.codefrak.engine.accounts.TitleAnswer(null, "the test's fake") }
    }

    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var hitlpRequests: com.codefrak.engine.hitlp.HitlpRequests
    @Autowired lateinit var hitlpPoller: com.codefrak.engine.hitlp.HitlpPoller

    /** Decides the HITLP wait [id] is parked on, as [by], through the shared stub and the poller. */
    private fun decide(id: String, answer: String, by: String = "lundmatt1") =
        com.codefrak.engine.hitlp.decideWait(com.codefrak.engine.hitlp.SharedHitlpStub.STUB, hitlpRequests, hitlpPoller, id, answer, by)

    @Autowired lateinit var accounts: AiAccounts
    @Autowired lateinit var history: HistoryService
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var exits: com.codefrak.engine.smith.SandboxExitNotifier
    @Autowired lateinit var tasks: TaskService
    @Autowired lateinit var github: TicketRecorder
    @Autowired lateinit var sandbox: RefiningSandbox
    @Autowired lateinit var starter: RecordingFlowStarter
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var access: Access
    @Autowired lateinit var handled: HandledIssues
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var intakes: IntakeController
    @Autowired lateinit var questions: IntakeQuestions
    @Autowired lateinit var incidents: IncidentController
    @Autowired lateinit var reader: FakeOpenTickets
    @Autowired lateinit var scanner: IssueScanner
    @Autowired lateinit var deferred: DeferredIssues
    @Autowired lateinit var intakeTier: IntakeTierController
    @Autowired lateinit var jev: FakeJevDecider
    @Autowired lateinit var jevCalls: JevCalls
    @Autowired lateinit var dependencies: IntakeDependencies
    @Autowired lateinit var refs: IntakeRefs
    @Autowired lateinit var notifications: IntakeNotifications
    @Autowired lateinit var bus: EngineEvents
    @Autowired lateinit var amender: IntakeAmender
    @Autowired lateinit var dataSource: javax.sql.DataSource
    @Autowired lateinit var attachments: AnswerAttachments
    @Autowired lateinit var workItems: WorkItemStore
    @Autowired lateinit var itemsApi: com.codefrak.engine.workitems.WorkItemController

    /** acme/tickets takes intakes from lundmatt1; chores only where a test grants them. */
    @AfterEach
    fun dropTestAccount() { accounts.dropTestAccount(); intakeTier.set(mapOf("tier" to 5)) }

    @BeforeEach
    fun reset() {
        listOf("engine.handled_issue", "engine.deferred_issue", "engine.engine_access", "engine.engine_access_audit", "engine.target_repo",
            "engine.intake_question", "engine.intake_dependency", "engine.intake_notification")
            .forEach { jdbc.sql("DELETE FROM $it").update() }
        repos.add("acme/tickets")
        access.add("lundmatt1", "test")
        starter.chores.clear()
        starter.bugs.clear()
        starter.features.clear()
        starter.proposals.clear()
        starter.research.clear()
        starter.merged.clear()
        reader.listings.clear()
        github.afterCreate = null; github.onClose = null; github.onComment = null; github.afterEdit = null
        sandbox.kindOverride = null; sandbox.featureMode = null; sandbox.userNamed = null
        sandbox.blockedBy = null; sandbox.ticketBody = null; sandbox.blocker = null; sandbox.repo = null
        jev.reset()
        sandbox.callers.clear(); sandbox.runAtFails = false
        sandbox.running = false; sandbox.stopped.clear()
        accounts.ensureTestAccount()
        intakeTier.set(mapOf("tier" to 5))
    }

    @Test
    fun `a refine run takes the refiner tier the API set, read as the run starts`() {
        assertEquals(IntakeTierView(3, "claude-sonnet-5", "medium"), intakeTier.set(mapOf("tier" to 3)))
        submit(mapOf("ask" to "hello"))
        val spec = sandbox.started.last()
        assertEquals("refine", spec.mode)
        assertEquals("claude-sonnet-5" to "medium", spec.model to spec.effort, "tier 3's [claude] mapping, not the seeded 5's")
    }

    @Test
    fun `a feature from an intake is filed as a work item in its mode, and its flow starts on the item's ticket`() {
        access.add("lundmatt1", "test")
        sandbox.kindOverride = "feature"; sandbox.featureMode = "confirm"
        val edits = github.edits.size
        val intake = submit(mapOf("ask" to "a first mate strip"))

        val item = itemOf(intake.id)
        assertEquals(Triple("feature", "confirm", 2), Triple(item.kind, item.mode, item.tier), "the refiner's kind, mode and tier as fields")
        assertEquals(item.ticket, (vars(intake.id)["ticket"] as Number).toLong(), "the item's ticket number")
        assertTrue(item.ticket!! >= WorkItemTickets.FIRST)
        assertEquals("confirm", vars(intake.id)[Vars.FEATURE_MODE])
        assertEquals(edits, github.edits.size, "nothing on GitHub is rewritten")
        assertEquals(item.ticket, (vars(intake.id)["issue"] as Number).toLong(), "the ask item, refined in place, is the ticket")
        assertEquals(listOf(item.ticket), starter.features.map { it.ticket.number }, "started on the item's ticket")
    }


    @Test
    fun `a feature a refiner files without a mode breaks the rules and stops the intake`() {
        sandbox.kindOverride = "feature"
        val incidents = runtime.createIncidentQuery().count()
        val filed = github.created.size
        val intake = submit(mapOf("ask" to "a first mate strip"))
        assertTrue(runtime.createIncidentQuery().count() > incidents, "read result refuses a feature with no mode")
        assertEquals(0L, history.createHistoricProcessInstanceQuery().processInstanceId(intake.id).finished().count())
        assertEquals(filed, github.created.size, "nothing is filed on GitHub")
        runtime.deleteProcessInstance(intake.id, "test done")
    }

    @Test
    fun `a refiner that names another target repo gets the work item filed there, and the intake's ask item closed naming it`() {
        repos.add("acme/widgets")
        sandbox.repo = "ACME/Widgets"
        val created = github.created.size
        val intake = submit(mapOf("ask" to "make the widget blue"))
        val intakeIssue = (vars(intake.id)["issue"] as Number).toLong()

        assertEquals(1L, history.createHistoricProcessInstanceQuery().processInstanceId(intake.id).finished().count())
        assertEquals(created, github.created.size, "nothing is filed on GitHub")
        val item = itemOf(intake.id)
        assertEquals("acme/widgets", item.repo, "in the target repo's own spelling")
        assertTrue("Make the widget blue" in item.description && "<!-- engine: intake " in item.description, item.description)
        assertNotEquals(intakeIssue, item.ticket, "a second item, in the target repo")
        assertTrue(github.comments.any { it.first == "acme/tickets" && it.second == intakeIssue && "Done. Filed acme/widgets#${item.ticket}" in it.third },
            github.comments.toString())
        assertTrue(("acme/tickets" to intakeIssue) in github.closed, "the intake's ask item is closed")
    }
    @Test
    fun `a refiner that names a repo that is not a target repo is refused, naming the target repos`() {
        sandbox.repo = "acme/nowhere"
        val filed = github.created.size
        val intake = submit(mapOf("ask" to "make the widget blue"))
        assertTrue(runtime.createIncidentQuery().list().any { "repo 'acme/nowhere' is not a target repo; the target repos are acme/tickets" in (it.incidentMessage ?: "") },
            runtime.createIncidentQuery().list().map { it.incidentMessage }.toString())
        assertEquals(filed, github.created.size, "nothing is filed on GitHub")
        runtime.deleteProcessInstance(intake.id, "test done")
    }




    /** The called runs' jobs too, so every job the engine has; tests run one intake at a time. */
    private fun runAllJobs() = management.runAllJobs(exits, rounds = 50)

    private fun steps(id: String): List<String> {
        val ids = generateSequence(listOf(id)) { parents ->
            parents.flatMap { history.createHistoricProcessInstanceQuery().superProcessInstanceId(it).list().map { c -> c.id } }
                .ifEmpty { null }
        }.flatten().toList()
        return ids.flatMap { history.createHistoricActivityInstanceQuery().processInstanceId(it).list() }.map { it.activityId }
    }

    /** Submitted by lundmatt1, who may authorize intakes, unless the test says otherwise. */
    private fun submit(vars: Map<String, Any>) =
        runtime.createMessageCorrelation("ask submitted").setVariables(mapOf("submittedBy" to "lundmatt1", "repo" to "acme/tickets") + vars)
            .correlateStartMessage().also { runAllJobs() }

    private fun vars(id: String) = history.createHistoricVariableInstanceQuery().processInstanceId(id).list().associate { it.name to it.value }




    // ---- an intake the engine's doctor filed (engine/README.md, "Doctor") ----

    private fun doctorBug(): Long {
        jdbc.sql("INSERT INTO engine.doctor_bug (repo, state) VALUES ('acme/tickets', 'filing')").update()
        return jdbc.sql("SELECT MAX(id) FROM engine.doctor_bug").query(Long::class.java).single()
    }

    private fun bugRow(id: Long) = jdbc.sql("SELECT issue, state FROM engine.doctor_bug WHERE id = :id").param("id", id)
        .query { rs, _ -> rs.getObject(1, Long::class.javaObjectType) to rs.getString(2) }.single()

    private fun submitDoctor(vars: Map<String, Any>) =
        submit(mapOf("submittedBy" to "doctor", "authorizedByEngine" to "doctor") + vars)





    @Test
    fun `a cancelled doctor intake leaves its bug declined with no issue`() {
        val bug = doctorBug()
        val intake = submitDoctor(mapOf("ask" to "Engine defect: a stuck smith-run\nKind: bug", "fakeOutcome" to "ask", "doctorBug" to bug))
        assertNotNull(questions.pending(intake.id))
        assertTrue(intakes.cancel(intake.id, CancelRequest(reason = "not an engine defect"), TestTokens.asLogin("lundmatt1")).ended)
        assertEquals(null to "declined", bugRow(bug))
    }

    @Test
    fun `an intake whose submitter may not authorize it opens no GitHub issue and waits for a store-side start`() {
        val runs = sandbox.started.size
        val created = github.created.size
        val labelled = github.labelled.size
        val intake = submit(mapOf("ask" to "tidy the readme", "submittedBy" to "someone-else"))
        assertEquals(runs, sandbox.started.size, "not refined yet")
        assertEquals(created, github.created.size, "no GitHub issue")
        assertEquals(labelled, github.labelled.size, "no label")
        val issue = (vars(intake.id)["issue"] as Number).toLong()
        assertTrue(issue >= WorkItemTickets.FIRST, "the ask item's ticket number")
        val ask = assertNotNull(workItems.get(vars(intake.id)[ASK_ITEM] as String))
        assertEquals(WorkItem.ASK to issue, ask.kind to ask.ticket)
        assertEquals("someone-else", ask.author)
        assertTrue(github.comments.any { it.second == issue && "Waiting for a trusted login" in it.third && "@someone-else" in it.third })
        assertEquals(listOf("event-intake-authorized"), runtime.getActiveActivityIds(intake.id))

        // What a trusted login's start of the ask item (POST /work-items/{id}/start, First mate's approve) hands the intake:
        val snapshot = IssueSnapshot(issue, "Intake: tidy the readme", "> tidy the readme, as edited before approval", "someone-else")
        assertEquals(intake.id, starter.start("intake", "acme/tickets", snapshot, "lundmatt1"))
        runAllJobs()
        assertEquals("lundmatt1", vars(intake.id)["authorizedBy"])
        val brief = sandbox.started.last().inputs.getValue("brief.json").decodeToString()
        assertTrue("as edited before approval" in brief, "the refiner works from the approved snapshot: $brief")
        assertEquals(1L, history.createHistoricProcessInstanceQuery().processInstanceId(intake.id).finished().count())
        assertEquals(null, starter.start("intake", "acme/tickets", snapshot, "lundmatt1"), "nothing waits for a second approval")
    }

    @Test
    fun `POST work-items start on an untrusted intake's ask item hands it the authorization, and refuses a second start`() {
        val intake = submit(mapOf("ask" to "tidy the readme", "submittedBy" to "someone-else"))
        val issue = (vars(intake.id)["issue"] as Number).toLong()
        val ask = vars(intake.id)[ASK_ITEM] as String
        access.remove("someone-else", "test") // another test's MockMvc request may have trusted it in the shared database
        val refused = assertFailsWith<ResponseStatusException> { itemsApi.start(ask, TestTokens.asLogin("someone-else")) }
        assertEquals(403, refused.statusCode.value())
        assertEquals(listOf("event-intake-authorized"), runtime.getActiveActivityIds(intake.id), "an untrusted login starts nothing")

        val started = itemsApi.start(ask, TestTokens.asLogin("lundmatt1"))
        assertEquals("intake" to intake.id, started["flow"] to started["intake"])
        runAllJobs()
        assertEquals("lundmatt1", vars(intake.id)["authorizedBy"])
        assertTrue(github.comments.any { it.second == issue && "Authorized by @lundmatt1" in it.third }, github.comments.toString())
        assertEquals(1L, history.createHistoricProcessInstanceQuery().processInstanceId(intake.id).finished().count())
        // The refiner files the ticket on the ask item itself, so a second start is the filed ticket's approval (on the
        // store's labels, as WorkItemTickets gives them in production), never a second intake authorization.
        val again = runCatching { itemsApi.start(ask, TestTokens.asLogin("lundmatt1")) }
        again.exceptionOrNull()?.let { assertEquals(409, (it as ResponseStatusException).statusCode.value()) }
        assertTrue(again.getOrNull()?.get("flow") != "intake", "nothing waits for a second intake start: $again")
        assertEquals(1L, history.createHistoricProcessInstanceQuery().processInstanceId(intake.id).finished().count())
    }

    @Test
    fun `authorize true authorizes the intake as its submitter`() {
        val intake = intakes.submit(AskRequest("tidy the readme", submittedBy = "lundmatt1", authorize = true, repo = "acme/tickets"), TestTokens.asLogin("lundmatt1")).also { runAllJobs() }
        assertEquals("lundmatt1", vars(intake.intakeId)["authorizedBy"])
        val issue = (vars(intake.intakeId)["issue"] as Number).toLong()
        assertTrue(github.comments.any { it.second == issue && "Authorized by its submitter, @lundmatt1" in it.third })
    }

    @Test
    fun `POST intake answers the ask item's ticket and url at once, before any job runs`() {
        val intake = intakes.submit(AskRequest("tidy the readme", submittedBy = "lundmatt1", repo = "acme/tickets"), TestTokens.asLogin("lundmatt1"))
        val v = vars(intake.intakeId)
        assertEquals("acme/tickets", intake.repo)
        assertEquals((v["issue"] as Number).toLong(), intake.ticket)
        assertEquals(v["issueUrl"], intake.ticketUrl)
        assertTrue(intake.ticket!! >= 100000)
    }

    @Test
    fun `an ask of 10,000 characters starts, its ask item quotes it whole, and GET intake answers it unchanged`() {
        val ask = "tidy the readme " + (1..10_000).joinToString("") { ('a' + it % 26).toString() }.take(10_000 - 16)
        assertEquals(10_000, ask.length)
        val intake = intakes.submit(AskRequest(ask, submittedBy = "lundmatt1", repo = "acme/tickets"), TestTokens.asLogin("lundmatt1")).also { runAllJobs() }
        assertTrue(ask in askBody(intake.intakeId), "the ask item quotes the whole ask")
        assertEquals(ask, intakes.state(intake.intakeId).variables["ask"])
    }

    @Test
    fun `POST intake stores a given context as text, quotes it in the ask item after the ask, and stores no blank one`() {
        val context = "We had just read IntakeController; " + "c".repeat(5_000)
        val intake = intakes.submit(AskRequest("tidy the readme", submittedBy = "lundmatt1", repo = "acme/tickets", context = context), TestTokens.asLogin("lundmatt1")).also { runAllJobs() }
        val body = askBody(intake.intakeId)
        assertTrue(body.indexOf("### Conversation context") > body.indexOf("> tidy the readme"), body)
        assertTrue(context in body, "the ask item carries the whole context")
        assertEquals(context, intakes.state(intake.intakeId).variables["context"])

        val blank = intakes.submit(AskRequest("tidy the readme", submittedBy = "lundmatt1", repo = "acme/tickets", context = "  "), TestTokens.asLogin("lundmatt1")).also { runAllJobs() }
        assertTrue("context" !in intakes.state(blank.intakeId).variables, "a blank context stores none")
    }

    @Test
    fun `a doctor's ask of 10,000 characters, stored as bytes, files its intake`() {
        val ask = "Engine defect: a stuck smith-run\nKind: bug\n" + "x".repeat(10_000)
        val intake = submitDoctor(mapOf("ask" to Brief.textValue(ask), "fakeOutcome" to "bug", "doctorBug" to doctorBug()))
        assertTrue("> " + "x".repeat(10_000) in askBody(intake.id))
        assertEquals(ask, intakes.state(intake.id).variables["ask"])
    }

    @Test
    fun `authorize true without a submitter who may authorize is refused and starts nothing`() {
        val before = runtime.createProcessInstanceQuery().processDefinitionKey("intake").count() +
            history.createHistoricProcessInstanceQuery().processDefinitionKey("intake").count()
        val missing = assertFailsWith<ResponseStatusException> { intakes.submit(AskRequest("tidy the readme", authorize = true, repo = "acme/tickets")) }
        assertEquals(400, missing.statusCode.value())
        assertTrue("submittedBy" in missing.reason.orEmpty())
        val denied = assertFailsWith<ResponseStatusException> {
            intakes.submit(AskRequest("tidy the readme", submittedBy = "someone-else", authorize = true, repo = "acme/tickets"), TestTokens.asLogin("someone-else"))
        }
        assertEquals(403, denied.statusCode.value())
        assertTrue("someone-else" in denied.reason.orEmpty())
        assertEquals(before, runtime.createProcessInstanceQuery().processDefinitionKey("intake").count() +
            history.createHistoricProcessInstanceQuery().processDefinitionKey("intake").count())
    }

    @Test
    fun `authorize false leaves an intake its submitter could authorize waiting for a start`() {
        val intake = intakes.submit(AskRequest("tidy the readme", submittedBy = "lundmatt1", authorize = false, repo = "acme/tickets"), TestTokens.asLogin("lundmatt1")).also { runAllJobs() }
        assertEquals(null, vars(intake.intakeId)["authorizedBy"])
        assertEquals(listOf("event-intake-authorized"), runtime.getActiveActivityIds(intake.intakeId))
    }

    private fun from(address: String) = MockHttpServletRequest().apply { remoteAddr = address }
    private val smithAddress = "172.18.0.9"
    private fun smithAtAddress() { sandbox.callers[smithAddress] = SmithCaller("run-382", 382, "codefrak-oss/frakworks") }
    private fun intakeCount() = history.createHistoricProcessInstanceQuery().processDefinitionKey("intake").count()

    @Test
    fun `an intake from a Smith sandbox records the run the engine found there, and waits for a start`() {
        smithAtAddress()
        val started = intakes.submit(AskRequest("look up the blocker", fromRun = FromRun("run-382", 382, "codefrak-oss/frakworks"), repo = "acme/tickets"),
            from(smithAddress)).also { runAllJobs() }
        val state = intakes.state(started.intakeId)
        assertEquals("run-382", state.variables[REQUESTED_BY_RUN])
        assertEquals(382L, (state.variables[REQUESTED_BY_ISSUE] as Number).toLong())
        assertEquals("codefrak-oss/frakworks", state.variables[REQUESTED_BY_REPO])
        assertEquals(false, state.variables[AUTHORIZE])
        assertNull(state.variables["authorizedBy"])
        assertEquals(listOf("event-intake-authorized"), runtime.getActiveActivityIds(started.intakeId))
        val body = askBody(started.intakeId)
        assertTrue("Filed by Smith run `run-382` working #382 in codefrak-oss/frakworks" in body, body)
        // No fromRun at all: still that run's, since the engine found it by address.
        val bare = intakes.submit(AskRequest("another", repo = "acme/tickets"), from(smithAddress)).also { runAllJobs() }
        assertEquals("run-382", intakes.state(bare.intakeId).variables[REQUESTED_BY_RUN])
    }

    @Test
    fun `a Smith sandbox may not name a submitter, authorize, or claim another run, and starts nothing`() {
        smithAtAddress()
        val before = intakeCount()
        listOf(
            AskRequest("x", submittedBy = "lundmatt1", repo = "acme/tickets"),
            AskRequest("x", submittedBy = "lundmatt1", authorize = true, repo = "acme/tickets"),
            AskRequest("x", authorize = true, repo = "acme/tickets"),
            AskRequest("x", fromRun = FromRun("run-other", 1, "acme/tickets"), repo = "acme/tickets"),
        ).forEach { ask ->
            val refused = assertFailsWith<ResponseStatusException>(ask.toString()) { intakes.submit(ask, from(smithAddress)) }
            assertEquals(403, refused.statusCode.value(), ask.toString())
        }
        assertEquals(before, intakeCount())
    }

    @Test
    fun `a fromRun from an address with no Smith is refused, and without one the request is as before`() {
        smithAtAddress()
        val before = intakeCount()
        val refused = assertFailsWith<ResponseStatusException> {
            intakes.submit(AskRequest("x", fromRun = FromRun("run-382", 382, "codefrak-oss/frakworks"), repo = "acme/tickets"), from("172.18.0.1"))
        }
        assertEquals(403, refused.statusCode.value())
        assertEquals(before, intakeCount())
        val human = intakes.submit(AskRequest("tidy the readme", submittedBy = "lundmatt1", authorize = true, repo = "acme/tickets"), TestTokens.asLogin("lundmatt1")).also { runAllJobs() }
        assertEquals("lundmatt1", vars(human.intakeId)["authorizedBy"])
        assertNull(vars(human.intakeId)[REQUESTED_BY_RUN])
    }

    @Test
    fun `a Smith sandbox may not cancel, and an unreachable Docker is 503, not a human`() {
        val intake = submit(mapOf("ask" to "hello", "fakeOutcome" to "ask"))
        assertNotNull(questions.pending(intake.id))
        smithAtAddress()
        val cancel = assertFailsWith<ResponseStatusException> { intakes.cancel(intake.id, CancelRequest(reason = "done"), from(smithAddress)) }
        assertEquals(403, cancel.statusCode.value())
        assertNotNull(questions.pending(intake.id), "still waiting")
        sandbox.runAtFails = true
        val down = assertFailsWith<ResponseStatusException> {
            intakes.submit(AskRequest("x", submittedBy = "lundmatt1", authorize = true, repo = "acme/tickets"), from("127.0.0.1"))
        }
        assertEquals(503, down.statusCode.value())
        sandbox.runAtFails = false
        runtime.deleteProcessInstance(intake.id, "test done")
    }

    // First mate's cancel_intake and amend_intake (#1173).

    private fun briefOf(spec: SmithSpec) = String(spec.inputs.getValue(BRIEF_FILE))

    @Test
    fun `cancel_intake's cancel stops the refine run's sandbox, deletes the intake and closes its issue as not planned`() {
        sandbox.running = true
        val intake = submit(mapOf("ask" to "hello"))
        val issue = (vars(intake.id)["issue"] as Number).toLong()
        assertTrue("call-refine-ask" in runtime.getActiveActivityIds(intake.id))
        val state = intakes.cancel(intake.id, CancelRequest(reason = "not needed"), TestTokens.asLogin("lundmatt1"))
        assertTrue(state.ended)
        assertEquals(emptyList(), state.failedWrites)
        assertEquals(listOf("container-$issue"), sandbox.stopped)
        assertNull(runtime.createProcessInstanceQuery().processInstanceId(intake.id).singleResult())
        assertEquals(NOT_PLANNED, github.closedAs["acme/tickets" to issue])
        assertTrue(github.comments.any { it.second == issue && "@lundmatt1" in it.third && "not needed" in it.third })
        val gone = assertFailsWith<ResponseStatusException> { intakes.cancel(intake.id, CancelRequest(reason = "again"), TestTokens.asLogin("lundmatt1")) }
        assertEquals(404, gone.statusCode.value())
        assertEquals("${intake.id} is not running", gone.reason)
    }

    @Test
    fun `a follow-up to a refine run in flight stops it and refines again on the same instance with it in the brief`() {
        sandbox.running = true
        val intake = submit(mapOf("ask" to "hello"))
        val issue = (vars(intake.id)["issue"] as Number).toLong()
        val runs = sandbox.started.count { it.issue == issue }
        assertEquals(1, runs)

        assertEquals(Amended(intake.id, IntakeAmender.RESTARTED), amender.amend(intake.id, "and make it green", "lundmatt1"))
        assertEquals(listOf("container-$issue"), sandbox.stopped)
        assertTrue(github.comments.any { it.second == issue && "@lundmatt1 added:\n\nand make it green" in it.third })
        sandbox.running = false
        runAllJobs()
        assertEquals(2, sandbox.started.count { it.issue == issue && it.mode == "refine" })
        val brief = briefOf(sandbox.started.last())
        assertTrue("\"question\":\"Follow-up from @lundmatt1\",\"answer\":\"and make it green\",\"by\":\"lundmatt1\"" in brief, brief)
        assertTrue(intakes.state(intake.id).ended, "the same instance went on and filed")
        assertEquals(1L, history.createHistoricProcessInstanceQuery().processDefinitionKey("intake")
            .variableValueEquals("issue", issue).count())
        assertNotNull(vars(intake.id)[FILED_WORK_ITEM])
    }

    @Autowired lateinit var ticketAmender: TicketAmender


    @Test
    fun `amend_ticket refuses an intake still refining as an intake, recording nothing`() {
        sandbox.running = true
        val intake = submit(mapOf("ask" to "hello"))
        val issue = (vars(intake.id)["issue"] as Number).toLong()
        assertTrue("call-refine-ask" in runtime.getActiveActivityIds(intake.id))
        val edits = github.edited.size; val comments = github.comments.size
        val e = assertFailsWith<ResponseStatusException> { ticketAmender.amend("acme/tickets", issue, "x", "lundmatt1") }
        assertEquals(400, e.statusCode.value())
        assertEquals("acme/tickets#$issue is an intake; use amend_intake", e.reason)
        assertEquals(edits, github.edited.size); assertEquals(comments, github.comments.size)
        assertEquals(0, questions.list(intake.id).size, "nothing recorded")
        sandbox.running = false
        intakes.cancel(intake.id, CancelRequest(reason = "test done"), TestTokens.asLogin("lundmatt1"))
    }

    @Test
    fun `a follow-up while the refiner's question waits is recorded, the wait stays, and the next round carries both`() {
        val intake = submit(mapOf("ask" to "hello", "fakeOutcome" to "ask"))
        val runs = sandbox.started.size
        assertEquals(Amended(intake.id, IntakeAmender.WAITS), amender.amend(intake.id, "and make it green", "lundmatt1"))
        assertEquals(runs, sandbox.started.size)
        assertTrue(sandbox.stopped.isEmpty())
        assertEquals(listOf(intake.id), intakes.open().map { it.intake })
        assertEquals(1L, tasks.createTaskQuery().processInstanceId(intake.id).taskDefinitionKey(ANSWER_TASK).count())

        decide(intake.id, "the blue one")
        runAllJobs()
        val brief = briefOf(sandbox.started.last())
        val follow = brief.indexOf("Follow-up from @lundmatt1"); val answer = brief.indexOf("\"answer\":\"the blue one\"")
        assertTrue(follow >= 0 && answer > follow && "Which widget?" in brief, brief)
        assertTrue(intakes.state(intake.id).ended)
    }

    @Test
    fun `a follow-up before the refine is recorded, starts nothing, and the first refine run carries it`() {
        val runs = sandbox.started.size
        val intake = submit(mapOf("ask" to "tidy the readme", "submittedBy" to "someone-else"))
        assertEquals(listOf("event-intake-authorized"), runtime.getActiveActivityIds(intake.id))
        val refused = assertFailsWith<ResponseStatusException> { amender.amend(intake.id, "x", "someone-else") }
        assertEquals(403, refused.statusCode.value())
        assertEquals(Amended(intake.id, IntakeAmender.RECORDED), amender.amend(intake.id, "the top section only", "lundmatt1"))
        assertEquals(runs, sandbox.started.size)
        assertEquals(listOf("event-intake-authorized"), runtime.getActiveActivityIds(intake.id))

        val issue = (vars(intake.id)["issue"] as Number).toLong()
        starter.start("intake", "acme/tickets", IssueSnapshot(issue, "Intake: tidy the readme", "> tidy the readme", "someone-else"), "lundmatt1")
        runAllJobs()
        assertTrue("the top section only" in briefOf(sandbox.started.last()))
    }

    @Test
    fun `a follow-up to an intake that is not running, or blank, is refused and records nothing`() {
        val missing = assertFailsWith<ResponseStatusException> { amender.amend("0a1b2c3d-dead", "x", "lundmatt1") }
        assertEquals("intake 0a1b2c3d-dead is not running", missing.reason)
        assertEquals(400, assertFailsWith<ResponseStatusException> { amender.amend("0a1b2c3d-dead", " ", "lundmatt1") }.statusCode.value())
    }

    @Test
    fun `an ask is refined by a smith in its own sandbox and the engine files it as a work item, with no GitHub ticket`() {
        val before = github.created.size
        val edits = github.edits.size
        val intake = submit(mapOf("ask" to "hello"))
        val intakeIssue = (vars(intake.id)["issue"] as Number).toLong()
        assertEquals(before, github.created.size, "no GitHub issue: the ask is a work item")

        assertEquals(1L, history.createHistoricProcessInstanceQuery().processInstanceId(intake.id).finished().count())
        val spec = sandbox.started.last()
        assertEquals("refine", spec.mode)
        assertEquals("claude-opus-5-5" to "medium", spec.model to spec.effort, "the seeded refiner tier 5's [claude] mapping")
        assertEquals("acme/tickets", spec.repo)
        assertEquals(intakeIssue, spec.issue)
        assertEquals(edits, github.edits.size, "nothing on GitHub is rewritten")
        val item = itemOf(intake.id)
        assertEquals(item.id, vars(intake.id)[ASK_ITEM], "the ask item, refined in place")
        assertEquals(Triple("acme/tickets", "chore", 2), Triple(item.repo, item.kind, item.tier))
        assertTrue(item.description.startsWith("# Make the widget blue\n\n## Goal") && "<!-- engine: intake ${intake.id}" in item.description, item.description)
        val v = vars(intake.id)
        assertEquals(item.ticket, (v["ticket"] as Number).toLong(), "the item's ticket number")
        assertNotNull(v["ticketUrl"]); assertNotNull(v[FILED_TICKET])
        assertEquals("chore", v["kind"])
        val said = github.comments.last()
        assertEquals(intakeIssue, said.second)
        assertTrue(said.third.startsWith("The ask above was refined into this chore (outcome: file)") && intake.id in said.third, said.third)
        assertTrue(("acme/tickets" to intakeIssue) !in github.closed, "the ask item is the ticket: it stays open")
        val steps = steps(intake.id)
        listOf("task-open-ask", "call-refine-ask", "call-smith-run", "task-start-smith", "task-collect-result",
            "task-read-result", "task-file-ticket", "task-start-work", "task-close-intake", "task-notify-session", "end-intake-closed")
            .forEach { assertTrue(it in steps, "$it in $steps") }
        assertTrue("task-file-unlabeled" !in steps)
    }

    @Test
    fun `no notification without a session, on a dry run, or when the intake asks instead of filing`() {
        val plain = submit(mapOf("ask" to "tidy the readme"))
        assertNotNull(vars(plain.id)[FILED_WORK_ITEM])
        assertNull(vars(plain.id)["notifySession"])
        assertTrue("task-notify-session" in steps(plain.id), "the step runs and queues nothing")
        assertEquals(emptyList(), notifications.pending(), "no session named")

        val dry = submit(mapOf("ask" to "tidy the readme", "dryRun" to true, "notifySession" to "first-mate"))
        assertNotNull(vars(dry.id)[FILED_WORK_ITEM])
        assertEquals(emptyList(), notifications.pending(), "a dry run tells no one")

        val session = submit(mapOf("ask" to "tidy the readme", "notifySession" to "first-mate"))
        assertNotNull(vars(session.id)[FILED_WORK_ITEM])
        assertEquals(listOf((vars(session.id)["ticket"] as Number).toLong()), notifications.pending().map { it.ticket },
            "the session is told the work item's ticket")
        notifications.pending().forEach { notifications.delivered(it.id, "test", "done") }

        val asking = submit(mapOf("ask" to "hello", "fakeOutcome" to "ask", "notifySession" to "first-mate"))
        assertNotNull(questions.pending(asking.id))
        assertEquals(emptyList(), notifications.pending(), "nothing filed yet")
        runtime.deleteProcessInstance(asking.id, "test done")
    }

    @Test
    fun `the withdrawn outcome closes the ask item as not planned and files nothing`() {
        val created = github.created.size
        val intake = submit(mapOf("ask" to "hello", "fakeOutcome" to "withdrawn", "notifySession" to "first-mate"))
        val issue = (vars(intake.id)["issue"] as Number).toLong()
        assertNull(runtime.createIncidentQuery().processInstanceId(intake.id).singleResult(), "no incident")
        assertEquals(1L, history.createHistoricProcessInstanceQuery().processInstanceId(intake.id).finished().count())
        val ask = assertNotNull(workItems.get(vars(intake.id)[ASK_ITEM] as String))
        assertEquals(NOT_PLANNED, github.closedAs["acme/tickets" to issue])
        assertEquals(created, github.created.size, "no other work item")
        assertEquals(listOf(ask.id), workItems.list("acme/tickets").filter { it.createdAt >= ask.createdAt }.map { it.id })
        assertTrue(workItems.list("acme/tickets").none { TicketFiler.isTriage(it.body) }, "no triage ticket")
        assertTrue(github.comments.any { it.second == issue && "> Drop it, #2685 already covers this" in it.third && "— engine intake `${intake.id}`" in it.third })
        assertNull(vars(intake.id)["ticket"]); assertNull(vars(intake.id)["kind"]); assertNull(vars(intake.id)["startedFlow"])
        val steps = steps(intake.id)
        assertTrue("task-close-withdrawn" in steps && "end-intake-closed" in steps, steps.toString())
        listOf("task-file-unlabeled", "task-file-ticket", "task-start-work", "task-close-intake", "task-notify-session").forEach { assertTrue(it !in steps, "$it in $steps") }
        assertEquals(emptyList(), notifications.pending(), "no session told")
        assertEquals(emptyList(), starter.chores + starter.bugs + starter.features + starter.proposals, "no flow starts")
    }

    @Test
    fun `the insufficient-permissions outcome notifies the session of the unlabeled ticket it filed`() {
        val intake = submit(mapOf("ask" to "hello", "fakeOutcome" to "insufficient-permissions", "notifySession" to "first-mate"))
        assertNull(runtime.createIncidentQuery().processInstanceId(intake.id).singleResult())
        val queued = notifications.pending().single()
        assertEquals(intake.id, queued.intake)
        assertEquals((vars(intake.id)["ticket"] as Number).toLong(), queued.ticket)
        assertEquals(vars(intake.id)["ticketUrl"], queued.ticketUrl)
    }

    @Test
    fun `the insufficient-permissions outcome files the ticket unlabeled for human triage and closes the intake`() {
        sandbox.blocker = "Needs a mac code-signing secret"
        val intake = submit(mapOf("ask" to "hello", "fakeOutcome" to "insufficient-permissions"))
        val intakeIssue = (vars(intake.id)["issue"] as Number).toLong()

        assertNull(runtime.createIncidentQuery().processInstanceId(intake.id).singleResult(), "no incident")
        assertEquals(1L, history.createHistoricProcessInstanceQuery().processInstanceId(intake.id).finished().count())
        val (repo, filed) = github.created.last()
        assertEquals("acme/tickets", repo)
        assertEquals("Make the widget blue", filed.title)
        assertEquals(listOf(WorkItem.TRIAGE), filed.labels, "kind triage, no authorized: nothing starts from it")
        val body = filed.body
        assertTrue(body.startsWith("## Goal"), body)
        val triage = body.indexOf(TicketFiler.TRIAGE_HEADING)
        val trailer = body.indexOf("<!-- engine:")
        assertTrue(triage in 0 until trailer, body)
        assertTrue("**Blocker:** Needs a mac code-signing secret" in body && "**Reason:** needs a new secret" in body, body)
        assertTrue("<!-- engine: intake ${intake.id} · tier 2 · p3 · by lundmatt1 · triage -->" in body, body)
        assertTrue(TicketFiler.isTriage(body))

        val ticket = (vars(intake.id)["ticket"] as Number).toLong()
        assertEquals(github.created.size + 99L, ticket)
        assertEquals("https://github.com/acme/tickets/issues/$ticket", vars(intake.id)["ticketUrl"])
        assertNull(vars(intake.id)["kind"])
        assertTrue(github.comments.any { it.second == intakeIssue && "Done. Filed #$ticket" in it.third }, github.comments.toString())
        assertTrue(("acme/tickets" to intakeIssue) in github.closed)
        assertEquals(ticket, (intakes.state(intake.id).variables["ticket"] as Number).toLong(), "GET /intake/{id} shows the ticket")

        val steps = steps(intake.id)
        listOf("task-file-unlabeled", "task-close-intake", "task-notify-session", "end-intake-closed").forEach { assertTrue(it in steps, "$it in $steps") }
        assertTrue("task-start-work" !in steps && "task-file-ticket" !in steps, steps.toString())
        assertEquals(emptyList(), starter.chores + starter.bugs + starter.features + starter.proposals, "no flow starts")
    }


    @Test
    fun `a cancel of an intake that already filed stops it but leaves its issue, the ticket, open`() {
        // Parked after filing (`ticket` set to its own issue) at an async step that has not run.
        val intake = runtime.createProcessInstanceByKey("intake").startBeforeActivity("task-file-unlabeled")
            .setVariable(Vars.REPO, "acme/tickets").setVariable(Vars.ISSUE, 7302L).setVariable("kind", "chore")
            .setVariable("issueUrl", "https://github.com/acme/tickets/issues/7302")
            .setVariable("ticket", 7302L).setVariable("ticketUrl", "https://github.com/acme/tickets/issues/7302").execute()
        val state = intakes.cancel(intake.id, CancelRequest(reason = "not needed"), TestTokens.asLogin("lundmatt1"))
        assertTrue(state.ended)
        assertNull(runtime.createProcessInstanceQuery().processInstanceId(intake.id).singleResult())
        assertTrue(github.closed.none { it == "acme/tickets" to 7302L }, "the ticket stays open")
        val said = github.comments.last { it.second == 7302L }.third
        assertTrue("not needed" in said && "The ticket it filed is this issue, #7302, and it stays open" in said && "chore" in said, said)
    }

    @Test
    fun `a retried file-unlabeled step with ticket already set files nothing`() {
        val filed = github.created.size
        val intake = runtime.createProcessInstanceByKey("intake").startBeforeActivity("task-file-unlabeled")
            .setVariable(Vars.REPO, "acme/tickets").setVariable(Vars.ISSUE, 7301L).setVariable("outcome", "insufficient-permissions")
            .setVariable("ticket", 42L).setVariable("ticketUrl", "https://github.com/acme/tickets/issues/42").execute()
        runAllJobs()
        assertEquals(filed, github.created.size, "the ticket was filed before the retry")
        assertTrue(github.comments.any { it.second == 7301L && "Done. Filed #42" in it.third }, github.comments.toString())
        assertTrue(("acme/tickets" to 7301L) in github.closed, "a ticket that is not the ask item: the ask item closes")
        assertEquals(1L, history.createHistoricProcessInstanceQuery().processInstanceId(intake.id).finished().count())
    }

    @Test
    fun `retrying an incident whose result broke the rules reruns the smith even though RESULT is success`() {
        val intake = submit(mapOf("ask" to "hello", "fakeOutcome" to "bad"))
        val incident = runtime.createIncidentQuery().list()
            .first { history.createHistoricProcessInstanceQuery().processInstanceId(it.processInstanceId).singleResult()?.rootProcessInstanceId == intake.id }
        assertEquals("success", runtime.getVariable(incident.processInstanceId, "result"),
            "the refiner exited 0; only its parsed output was rejected")
        val before = sandbox.started.size

        val retried = incidents.retry(incident.id, RetryRequest(), TestTokens.asLogin("lundmatt1"))
        runAllJobs()

        assertEquals("smith", retried.action)
        assertEquals(intake.id, retried.rootInstanceId)
        assertTrue(sandbox.started.size > before, "the refiner ran again instead of a plain job retry")
        runtime.deleteProcessInstance(intake.id, "test done")
    }

    @Test
    fun `retrying an incident whose smith exited 0 without result json reruns the smith`() {
        val intake = submit(mapOf("ask" to "hello", "fakeOutcome" to "none"))
        val incident = runtime.createIncidentQuery().list()
            .first { history.createHistoricProcessInstanceQuery().processInstanceId(it.processInstanceId).singleResult()?.rootProcessInstanceId == intake.id }
        assertNotEquals("success", runtime.getVariable(incident.processInstanceId, "result"),
            "exit 0 without result.json is a failed run")
        val before = sandbox.started.size

        val retried = incidents.retry(incident.id, RetryRequest(), TestTokens.asLogin("lundmatt1"))
        runAllJobs()

        assertEquals("smith", retried.action)
        assertTrue(sandbox.started.size > before, "the refiner ran again instead of a plain job retry")
        runtime.deleteProcessInstance(intake.id, "test done")
    }

    @Test
    fun `no result file, or one that breaks a rule, stops the intake before any github write`() {
        listOf("none", "bad").forEach { fake ->
            val incidents = runtime.createIncidentQuery().count()
            val intake = submit(mapOf("ask" to "hello", "fakeOutcome" to fake))
            val issues = github.created.size
            assertEquals(0L, history.createHistoricProcessInstanceQuery().processInstanceId(intake.id).finished().count())
            // one on the failing job, plus one per calling process
            assertTrue(runtime.createIncidentQuery().count() > incidents)
            assertEquals(issues, github.created.size)
            runtime.deleteProcessInstance(intake.id, "test done")
        }
    }

    @Test
    fun `the ask outcome posts the question, waits for an authorized answer, and refines again from it`() {
        val intake = submit(mapOf("ask" to "hello", "fakeOutcome" to "ask"))
        val issue = (vars(intake.id)["issue"] as Number).toLong()

        // Recorded on the ask item as written and in the question table, and the intake waits at the user task: no incident.
        val posted = github.comments.last()
        assertEquals(issue, posted.second)
        assertTrue("Which widget?" in posted.third && "Decide it on " in posted.third, posted.third)
        val pending = assertNotNull(questions.pending(intake.id))
        assertEquals("Which widget?", pending.question)
        assertEquals(listOf(pending), questions.open())
        assertEquals(1L, tasks.createTaskQuery().processInstanceId(intake.id).taskDefinitionKey(ANSWER_TASK).count())
        assertEquals(0L, runtime.createIncidentQuery().processInstanceId(intake.id).count())
        assertEquals(pending, intakes.state(intake.id).pendingQuestion)

        decide(intake.id, "the blue one")
        runAllJobs()
        assertNull(questions.pending(intake.id))
        val answered = questions.list(intake.id).single()
        assertEquals("the blue one" to "lundmatt1", answered.answer to answered.answeredBy)
        assertTrue(github.comments.any { it.second == issue && "@lundmatt1 answered:\n\nthe blue one" in it.third })

        // The second refine run got the exchange in its brief, and filed.
        val brief = String(sandbox.started.last().inputs.getValue(BRIEF_FILE))
        assertTrue("\"question\":\"Which widget?\",\"answer\":\"the blue one\",\"by\":\"lundmatt1\"" in brief, brief)
        assertEquals(2, sandbox.started.count { it.issue == issue && it.mode == "refine" })
        assertEquals("file", vars(intake.id)["outcome"])
        assertTrue(intakes.state(intake.id).ended)
        assertEquals(emptyList(), questions.open())
    }

    private fun b64(text: String) = java.util.Base64.getEncoder().encodeToString(text.toByteArray())

    /** Answer attachments (#1730; engine/README.md, "Answer attachments"): stored against the flow, listed in the next brief by name and size. */
    @Test
    fun `a file attached to an answer is stored against the flow and the next run's brief lists it by name and size, never its content`() {
        val intake = submit(mapOf("ask" to "hello", "fakeOutcome" to "ask"))
        val stored = intakes.attach(intake.id, AttachmentUpload("spec.md", "text/markdown", b64("# Spec\n\nthe secret plan")), TestTokens.asLogin("lundmatt1"))
        assertEquals(AttachmentStored(intake.id, "spec.md", "text/markdown", 23), stored)
        assertEquals("# Spec\n\nthe secret plan", String(assertNotNull(attachments.read(intake.id, "spec.md")).second))

        decide(intake.id, "see the spec")
        runAllJobs()
        val brief = String(sandbox.started.last().inputs.getValue(BRIEF_FILE))
        assertTrue("\"attachments\":[{\"name\":\"spec.md\",\"mime\":\"text/markdown\",\"size\":23}]" in brief, brief)
        assertTrue("secret plan" !in brief, brief)
        assertTrue(github.comments.none { "secret plan" in it.third }, "never written to GitHub")
    }

    @Test
    fun `an attachment is refused with no waiting question, from a Smith, from an untrusted login, over the cap and as a binary`() {
        val gone = assertFailsWith<ResponseStatusException> { intakes.attach("no-such-flow", AttachmentUpload("a.md", null, b64("x")), TestTokens.asLogin("lundmatt1")) }
        assertEquals(409, gone.statusCode.value())
        val intake = submit(mapOf("ask" to "hello", "fakeOutcome" to "ask"))
        smithAtAddress()
        val smith = assertFailsWith<ResponseStatusException> { intakes.attach(intake.id, AttachmentUpload("a.md", null, b64("x")), from(smithAddress)) }
        assertEquals(403, smith.statusCode.value())
        val untrusted = assertFailsWith<ResponseStatusException> { intakes.attach(intake.id, AttachmentUpload("a.md", null, b64("x")), TestTokens.asLogin("someone-else")) }
        assertEquals(403, untrusted.statusCode.value())
        val big = java.util.Base64.getEncoder().encodeToString(ByteArray(AnswerAttachments.MAX_BYTES + 1) { 'a'.code.toByte() })
        val over = assertFailsWith<ResponseStatusException> { intakes.attach(intake.id, AttachmentUpload("big.md", null, big), TestTokens.asLogin("lundmatt1")) }
        assertEquals(413, over.statusCode.value())
        assertTrue("1 MiB" in over.reason!!, over.reason)
        val binary = java.util.Base64.getEncoder().encodeToString(byteArrayOf(0, 1, 2, -1, -2))
        val bin = assertFailsWith<ResponseStatusException> { intakes.attach(intake.id, AttachmentUpload("x.bin", "application/octet-stream", binary), TestTokens.asLogin("lundmatt1")) }
        assertEquals(415, bin.statusCode.value())
        val png = intakes.attach(intake.id, AttachmentUpload("shot.png", "image/png", binary), TestTokens.asLogin("lundmatt1"))
        assertEquals("image/png", png.mime)
        assertEquals(listOf("shot.png"), attachments.list(intake.id).map { it.name })
        runtime.deleteProcessInstance(intake.id, "test done")
    }

    // The stream (engine/README.md, "Events"): one `intake` frame per step, and the question as a `needs` row.

    @Test
    fun `the stream says each step of an intake, and its question is a need from the post until the answer`() = EventRecorder(bus).use { rec ->
        val intake = submit(mapOf("ask" to "hello", "fakeOutcome" to "ask"))
        val issue = (vars(intake.id)["issue"] as Number).toLong()
        val first = rec.of("intake")
        assertEquals(listOf("issue", "question"), first.map { it.state })
        assertEquals(listOf("acme/tickets", intake.id, issue, "https://github.com/acme/tickets/issues/$issue"),
            listOf(first[0].str("repo"), first[0].str("intakeId"), first[0].long("number"), first[0].str("issueUrl")))
        assertEquals("Which widget?", first[1].str("question")); assertEquals(issue, first[1].long("number"))
        // Only the question's rows: a root-cause incident of another flow (an earlier test's failed job) is a `needs`
        // `incident` frame too, published from a thread of its own (`FlowEvents.incident`), so it may land here at any time.
        val need = rec.of("needs").single { it.str("actionId") == intake.id && it.str("kind") == "question" && it.state == "added" }
        assertEquals(listOf("question", "added", issue, intake.id), listOf(need.str("kind"), need.state, need.long("number"), need.str("actionId")))

        decide(intake.id, "the blue one")
        runAllJobs()
        val then = rec.of("intake").drop(2)
        assertEquals(listOf("answered", "ended"), then.map { it.state })
        assertEquals("lundmatt1", then[0].str("by"))
        // The answer's removed; an ended flow's (FlowEvents.flush, debounced, on its own thread) may follow it, for the intake too.
        // Other flows' frames (another flow's incident) are theirs: only
        // the intake's question rows count here.
        val needs = rec.of("needs", java.time.Duration.ofSeconds(2)).filter { it.str("actionId") == intake.id && it.str("kind") == "question" }
        assertEquals(listOf("added", "removed"), needs.take(2).map { it.state })
        assertTrue(needs.drop(1).all { it.state == "removed" }, needs.toString())
    }

    @Test
    fun `11 intakes filing at once at the production pool size all file, and no filing waits on a second connection`() {
        // The 2026-10-07 burst (#1650): 11 asks within 40 s, each filing a ticket, linking friction groups and writing
        // its created frame's board row. Each create used to wait, holding its command's connection, on a board-row read
        // and REQUIRES_NEW friction links that needed another; with all 30 held that way the pool never recovered.
        val pool = dataSource.unwrap(com.zaxxer.hikari.HikariDataSource::class.java)
        assertEquals(30, pool.maximumPoolSize, "the production pool size")
        val waiting = java.util.concurrent.atomic.AtomicInteger()
        val idle = java.util.concurrent.atomic.AtomicInteger(Int.MAX_VALUE)
        github.onComment = { _, _, _ ->
            // Hikari counts a thread as awaiting for the instant of any borrow, even with idle connections to hand, so a
            // waiter alone is the burst's other threads taking theirs; starvation is a waiter with nothing idle.
            val bean = pool.hikariPoolMXBean
            val free = bean.idleConnections
            if (free == 0) waiting.accumulateAndGet(bean.threadsAwaitingConnection, ::maxOf)
            idle.accumulateAndGet(free, ::minOf)
        }
        val workers = java.util.concurrent.Executors.newFixedThreadPool(11)
        try {
            val gate = java.util.concurrent.CountDownLatch(1)
            val ids = (1..11).map { n ->
                workers.submit<String> {
                    gate.await()
                    runtime.createMessageCorrelation("ask submitted").setVariables(mapOf("submittedBy" to "lundmatt1", "repo" to "acme/tickets",
                        "ask" to "burst ask $n")).correlateStartMessage().id
                }
            }.also { gate.countDown() }.map { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
            val deadline = System.currentTimeMillis() + 60_000
            while (ids.any { vars(it)[FILED_WORK_ITEM] == null }) {
                check(System.currentTimeMillis() < deadline) { "not every intake filed within 60 s: ${ids.filter { vars(it)[FILED_WORK_ITEM] == null }}" }
                runCatching { exits.scanExits() }
                // Every pending job at once, on 11 threads: the files and the closes of the burst run together.
                management.createJobQuery().withRetriesLeft().list()
                    .map { job -> workers.submit { runCatching { management.executeJob(job.id) } } }
                    .forEach { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
            }
        } finally {
            github.onComment = null
            workers.shutdownNow()
        }
        assertEquals(0, waiting.get(), "no thread was starved of a connection while an ask item was being opened")
        assertTrue(idle.get() > 0, "the pool always had a connection to spare while opening an ask item: ${idle.get()}")
        // The after-commit links and created frames release theirs: the pool drains back to idle.
        val until = System.currentTimeMillis() + 10_000
        while (pool.hikariPoolMXBean.activeConnections > 0 && System.currentTimeMillis() < until) Thread.sleep(50)
        assertEquals(0, pool.hikariPoolMXBean.activeConnections, "no connection is left held after the burst")
    }

    @Test
    fun `the ask item is a created frame with its board row, read and published off the command's thread`() = EventRecorder(bus).use { rec ->
        // The ask item's ticket number is the sequence's next: this takes one, so the item gets one of the three after it.
        val next = jdbc.sql("SELECT nextval('engine.work_item_ticket')").query(Long::class.java).single() + 1
        val at = java.time.OffsetDateTime.parse("2026-10-05T12:50:57Z")
        for (n in next..next + 2) reader.listings.getOrPut("acme/tickets") { mutableListOf() } +=
            IssueListing(n, "Issue $n", "engine", listOf("chore"), "https://github.com/acme/tickets/issues/$n", at, at)
        val intake = submit(mapOf("ask" to "hello"))
        val issue = (vars(intake.id)["issue"] as Number).toLong()
        // Inside a command the row is read on a thread of its own the command does not wait for (#1650): no order against
        // the commit's frames, only that each comes, with its row.
        val created = rec.await("created") { it.long("number") == issue }
        assertEquals(issue, created.data["issue"]["number"].asLong(), "with its board row")
    }

    @Test
    fun `POST intake says submitted, and a cancel at the question says cancelled by whom and takes the need away`() = EventRecorder(bus).use { rec ->
        val id = intakes.submit(AskRequest("hello", fakeOutcome = "ask", submittedBy = "lundmatt1", repo = "acme/tickets"), TestTokens.asLogin("lundmatt1")).intakeId
        // `openAskItem` runs inside the correlation and publishes on its commit, so `issue` is out before the
        // controller's `submitted`: the controller only knows the id once the correlation has returned.
        val first = rec.of("intake")
        assertEquals(setOf("issue", "submitted"), first.map { it.state }.toSet())
        val submitted = first.single { it.state == "submitted" }
        assertEquals(id to "acme/tickets", submitted.str("intakeId") to submitted.str("repo"))
        assertTrue(first.all { it.str("intakeId") == id })
        runAllJobs()
        assertNotNull(questions.pending(id))
        intakes.cancel(id, CancelRequest(reason = "not needed"), TestTokens.asLogin("lundmatt1"))
        val cancelled = rec.of("intake").last()
        assertEquals("cancelled" to "lundmatt1", cancelled.state to cancelled.str("by"))
        // The cancel's removed; a cancelled flow's (FlowEvents.flush, debounced, on its own thread) may follow it, the question's too.
        val needs = rec.of("needs", java.time.Duration.ofSeconds(2)).filter { it.str("actionId") == id }
        assertEquals(listOf("added", "removed"), needs.take(2).map { it.state })
        assertTrue(needs.drop(1).all { it.state == "removed" && it.str("kind") == "question" }, needs.toString())
    }

    @Test
    fun `an intake given a repo files there, and files its work item there`() {
        repos.add("acme/widgets")
        access.add("lundmatt1", "test")
        val before = github.created.size
        val started = intakes.submit(AskRequest("tidy the readme", submittedBy = "lundmatt1", authorize = true, repo = "acme/widgets"), TestTokens.asLogin("lundmatt1"))
            .also { runAllJobs() }
        val vars = vars(started.intakeId)
        assertEquals("acme/widgets", vars["repo"])
        assertEquals("lundmatt1", vars["authorizedBy"], "authorized by a trusted login")
        assertEquals("acme/widgets", sandbox.started.last().repo, "the refiner runs in that repo")
        assertEquals("acme/widgets", itemOf(started.intakeId).repo, "the ask item, refined in place, is there")
        assertEquals(itemOf(started.intakeId).id, vars[ASK_ITEM])
        assertEquals(listOf(itemOf(started.intakeId).ticket), starter.chores.map { it.ticket.number },
            "its chore starts on the item's ticket, in that repo")
        assertEquals(before, github.created.size, "nothing lands on GitHub")
    }
    @Test
    fun `a repo that is no target repo is 400, and authorize is checked against the trusted logins`() {
        repos.add("acme/widgets")
        val before = intakeCount()
        val unknown = assertFailsWith<ResponseStatusException> { intakes.submit(AskRequest("x", repo = "acme/nowhere")) }
        assertEquals(400, unknown.statusCode.value())
        assertTrue("acme/nowhere" in unknown.reason.orEmpty())
        // stranger is no trusted login: authorize is refused on any repo.
        val denied = assertFailsWith<ResponseStatusException> {
            intakes.submit(AskRequest("x", submittedBy = "stranger", authorize = true, repo = "acme/widgets"), TestTokens.asLogin("stranger"))
        }
        assertEquals(403, denied.statusCode.value())
        assertTrue("@stranger is not a trusted login" in denied.reason.orEmpty(), denied.reason)
        assertEquals(before, intakeCount())
        // Not trusted, the submitter does not authorize it: it waits for a start of its ask item on acme/widgets.
        val waiting = intakes.submit(AskRequest("x", submittedBy = "stranger", repo = "acme/widgets"), TestTokens.asLogin("stranger")).also { runAllJobs() }
        assertNull(vars(waiting.intakeId)["authorizedBy"])
        val issue = (vars(waiting.intakeId)["issue"] as Number).toLong()
        assertTrue(github.comments.any { it.first == "acme/widgets" && it.second == issue && "Waiting for a trusted login" in it.third })
    }

    @Test
    fun `an intake from a Smith with no repo files in the run's repo`() {
        repos.add("acme/widgets")
        sandbox.callers[smithAddress] = SmithCaller("run-7", 7, "acme/widgets")
        val started = intakes.submit(AskRequest("look up the blocker", fromRun = FromRun("run-7", 7, "acme/widgets")), from(smithAddress))
            .also { runAllJobs() }
        assertEquals("acme/widgets", vars(started.intakeId)["repo"])
        assertEquals("acme/widgets", workItems.get(vars(started.intakeId)[ASK_ITEM] as String)?.repo)
        assertEquals(listOf("event-intake-authorized"), runtime.getActiveActivityIds(started.intakeId))
    }

    @Test
    fun `an intake with no repo is refused, naming the target repos, and starts nothing`() {
        val before = intakeCount()
        val refused = assertFailsWith<ResponseStatusException> { intakes.submit(AskRequest("tidy the readme", submittedBy = "lundmatt1", authorize = true), TestTokens.asLogin("lundmatt1")) }
        assertEquals(400, refused.statusCode.value())
        assertTrue("repo is required" in refused.reason.orEmpty() && "acme/tickets" in refused.reason.orEmpty(), refused.reason)
        // A Smith whose run's repo is no target repo has none either.
        smithAtAddress()
        assertEquals(400, assertFailsWith<ResponseStatusException> { intakes.submit(AskRequest("x"), from(smithAddress)) }.statusCode.value())
        assertEquals(before, intakeCount())
    }

    @Test
    fun `open ask opens the ask as a work item, no GitHub issue, and keeps its ticket number`() {
        val before = github.created.size
        val intake = submit(mapOf("ask" to "\nmake the widget blue.\nand shiny", "fakeOutcome" to "ask"))
        assertEquals(before, github.created.size, "no GitHub issue")
        val vars = vars(intake.id)
        val item = assertNotNull(workItems.get(vars[ASK_ITEM] as String))
        assertEquals(Triple("acme/tickets", WorkItem.ASK, (vars["issue"] as Number).toLong()), Triple(item.repo, item.kind, item.ticket))
        assertEquals("Intake: make the widget blue", item.title)
        assertTrue("> make the widget blue.\n> and shiny" in item.body, item.body)
        assertEquals("https://github.com/acme/tickets/issues/${vars["issue"]}", vars["issueUrl"])
        runtime.deleteProcessInstance(intake.id, "test done")
    }

    @Test
    fun `the engine's rules for a refine result`() {
        val ok = DraftTicket("t", "b", 5, "p3", 3)
        assertEquals(emptyList(), RefineResult("file", ok, kind = "chore").problems())
        assertEquals(1, RefineResult("file", ok).problems().size, "no kind")
        assertEquals(1, RefineResult("file", ok, kind = "epic").problems().size)
        assertEquals(1, RefineResult("file", ok.copy(priority = "p1"), kind = "bug").problems().size)
        assertEquals(1, RefineResult("file", ok.copy(points = null), kind = "bug").problems().size, "no points")
        assertEquals(1, RefineResult("file", ok.copy(points = 4), kind = "bug").problems().size, "4 is not on the scale")
        assertEquals(1, RefineResult("file", ok.copy(points = 21), kind = "bug").problems().size, "only a feature goes above 13")
        assertEquals(emptyList(), RefineResult("file", ok.copy(points = 34), kind = "feature", featureMode = "auto").problems(), "a feature may be 34")
        assertEquals(1, RefineResult("file", ok.copy(points = 89), kind = "feature", featureMode = "auto").problems().size, "55 is the top")
        assertEquals(emptyList(), RefineResult("insufficient-permissions", ok.copy(points = null), reason = "r", kind = "bug").problems(),
            "only a ticket filed for work needs points")
        assertEquals(1, RefineResult("file", ok.copy(body = "- May push to `main`: yes"), kind = "bug").problems().size)
        assertEquals(1, RefineResult("insufficient-permissions", ok, kind = "bug").problems().size)
        assertEquals(emptyList(), RefineResult("insufficient-permissions", ok, reason = "needs a secret", kind = "bug").problems(),
            "blocker is optional: an insufficient-permissions result from before #443 has none")
        assertEquals(emptyList(), RefineResult("insufficient-permissions", ok, reason = "needs a secret", blocker = "Needs a signing secret", kind = "bug").problems())
        assertEquals("Needs a signing secret", RefineResult.parse(
            """{"outcome":"insufficient-permissions","reason":"r","blocker":"Needs a signing secret"}""".toByteArray()).blocker)
        assertEquals(1, RefineResult("ask").problems().size)
        assertEquals(1, RefineResult("maybe").problems().size)
        assertEquals(RefineResult("ask", question = "q?"), RefineResult.parse("""{"outcome":"ask","question":"q?"}""".toByteArray()))
    }

    @Test
    fun `a ticket reaching github workflows is refused only when the token's known scopes lack workflow`() {
        val body = "## Goal\n\nBump the image.\n\n## Allowed scope\n\n- `.github/workflows/images.yml`\n- May push to main: no\n\n## Context\n\n- Intake: #1\n"
        val result = RefineResult("file", DraftTicket("t", body, 5, "p3", 3), kind = "chore")
        val refused = result.problems(setOf("repo"))
        assertEquals(1, refused.size, refused.toString())
        assertTrue("workflow" in refused.single(), refused.single())
        assertEquals(emptyList(), result.problems(setOf("repo", "workflow")))
        assertEquals(emptyList(), result.problems(null), "unknown scopes (a fine-grained token): the push is the only check")
        assertEquals(1, RefineResult("file", DraftTicket("t", body.replace("`.github/workflows/images.yml`", "`.github/**`"), 5, "p3", 3),
            kind = "chore").problems(emptySet()).size, "a glob over .github/ takes workflows in")
        val elsewhere = body.replace("`.github/workflows/images.yml`", "`engine/**`").replace("- Intake: #1", "- Intake: #1; see .github/workflows/ci.yml")
        assertEquals(emptyList(), RefineResult("file", DraftTicket("t", elsewhere, 5, "p3", 3), kind = "chore").problems(setOf("repo")),
            "a workflow path outside Allowed scope is not a grant")
        assertEquals(emptyList(), RefineResult("insufficient-permissions", result.ticket, reason = "needs workflow", blocker = "Needs the workflow scope",
            kind = "chore").problems(setOf("repo")), "insufficient-permissions is how the refiner says so")
    }

    @Test
    fun `push-to-main only rejects an affirmative grant, never a denial that shares its colon-plus-truthy-word tail`() {
        val ok = DraftTicket("t", "b", 5, "p3", 3)
        fun problems(body: String) = RefineResult("file", ok.copy(body = body), kind = "bug").problems()

        // The affirmative form: rejected.
        assertEquals(1, problems("## Allowed scope\n\n- Push to main: yes\n").size)

        // Three denial forms sharing the same colon-plus-truthy-word (or falsy-word) tail: all accepted.
        assertEquals(emptyList(), problems("## Allowed scope\n\n- May not push to main: yes\n"))
        assertEquals(emptyList(), problems("## Allowed scope\n\n- Push to main: no\n"))
        assertEquals(emptyList(), problems("## Allowed scope\n\n- **May not** `push to main`: **yes** (PR only).\n"))
    }

    // ---- Jev's pick of kind, feature mode and tier (stubbed: no Jev call is made) ----

    private fun sure(choice: String, p: Double) = JevAnswer("choice", choice = choice, probabilities = mapOf(choice to p), confidence = p)
    private fun tierAt(tier: Int, p: Double) =
        JevAnswer("score", score = (tier - 1).toDouble(), probabilities = mapOf("${tier - 1}" to p, "0" to 1 - p), confidence = p)

    /** The work item this intake filed, by its `workItem` variable. */
    /** The ask as the intake opened its ask item with: the snapshot the refiner works from (the item is refined in place). */
    private fun askBody(intakeId: String): String = Brief.ticket(vars(intakeId)[Vars.TICKET] as ByteArray).body

    private fun itemOf(intakeId: String): WorkItem {
        val id = vars(intakeId)[FILED_WORK_ITEM] as? String ?: error("intake $intakeId filed no work item: ${steps(intakeId)}")
        return assertNotNull(workItems.get(id))
    }

    @Test
    fun `jev's sure pick of kind, mode and tier is what the intake files, with the pick in the description`() {
        access.add("lundmatt1", "test")
        jev.answers += mapOf("kind" to sure("feature", 0.93), "featureMode" to sure("confirm", 0.88), "tier" to tierAt(7, 0.81))
        val intake = submit(mapOf("ask" to "a first mate strip"))

        val (useCase, context, state) = jev.asked.single()
        assertEquals("refine-pick", useCase)
        assertEquals(intake.id, context.processInstance, "the intake's own id, not its smith run's")
        assertEquals("acme/tickets", context.repo)
        assertTrue("a first mate strip" in state.toString() && "Make the widget blue" in state.toString(), "the ask and the draft are the state")
        val item = itemOf(intake.id)
        assertEquals(Triple("feature", "confirm", 7), Triple(item.kind, item.mode, item.tier), "filed at jev's kind, mode and tier, not the refiner's chore")
        assertTrue("Jev (TypeSafe's decision model) picked kind `feature` (0.93)" in item.description, item.description)
        assertTrue("the refiner chose `chore`, tier 2" in item.description, item.description)
        val call = jevCalls.get(jev.ids.single())!!
        assertEquals("filed: kind feature (jev), mode confirm (jev), tier 7 (jev)", call.action)
        assertEquals(0.8, call.threshold)
        assertEquals(listOf(7), starter.features.map { it.tier }, "the feature starts at jev's tier")
    }
    @Test
    fun `with no jev answer, or below the floor, the intake files exactly as the refiner chose`() {
        access.add("lundmatt1", "test")
        val plain = itemOf(submit(mapOf("ask" to "tidy the readme")).id)
        assertEquals("chore" to 2, plain.kind to plain.tier)
        assertTrue("Jev" !in plain.description, plain.description)
        assertEquals("no answer: filed at the refiner's chore tier 2", jevCalls.get(jev.ids.single())!!.action)

        jev.reset()
        jev.answers += mapOf("kind" to sure("feature", 0.6), "tier" to tierAt(9, 0.6))
        val low = itemOf(submit(mapOf("ask" to "tidy the readme")).id)
        assertEquals("chore" to 2, low.kind to low.tier, "below the floor the refiner's kind and tier stand")
        assertTrue("Jev (TypeSafe's decision model) picked kind `feature` (0.60)" in low.description, "a pick below the floor is still shown: ${low.description}")
        assertEquals("filed: kind chore (jev feature<floor), mode -, tier 2 (jev 9<floor)",
            jevCalls.get(jev.ids.single())!!.action)
    }
    @Test
    fun `a tier or kind the user named binds, and jev is not asked about it`() {
        access.add("lundmatt1", "test")
        jev.answers += mapOf("kind" to sure("feature", 0.99), "featureMode" to sure("auto", 0.99), "tier" to tierAt(9, 0.99))
        val bound = submit(mapOf("ask" to "Tier: 4 (opus low) — prescribed by the user; do not re-judge\ntidy the readme"))
        assertEquals(setOf("kind", "featureMode"), jev.questionSets.single().keys, "the ask's Tier: line binds the tier")
        val filed = itemOf(bound.id)
        assertEquals(Triple("feature", "auto", 2), Triple(filed.kind, filed.mode, filed.tier),
            "the kind the user left open is jev's; the refiner's tier (the prescribed one, in life)")

        jev.reset()
        sandbox.userNamed = "[\"kind\",\"tier\"]"
        val closed = submit(mapOf("ask" to "a chore: tidy the readme, at tier 2"))
        assertEquals(emptyList(), jev.asked, "every field the user left open is closed: no call")
        assertEquals("chore" to 2, itemOf(closed.id).let { it.kind to it.tier })
    }

    // ---- Jev's duplicate check before the refine (stubbed: no Jev call is made) ----

    private fun openTickets() {
        val at = java.time.OffsetDateTime.parse("2026-09-26T12:00:00Z")
        reader.listings["acme/tickets"] = mutableListOf(
            IssueListing(7, "Make the widget blue", "someone", listOf("chore"), "https://github.com/acme/tickets/issues/7", at, at,
                "## Goal\n\nThe widget should be blue.\n"),
            IssueListing(8, "Tidy the readme", "someone", listOf("chore"), "https://github.com/acme/tickets/issues/8", at.minusDays(1), at.minusDays(1)),
        )
    }

    @Test
    fun `a sure duplicate asks the user before any refine run, and the answer goes to the refiner with the ticket`() {
        access.add("lundmatt1", "test")
        openTickets()
        jev.answers["duplicate"] = sure("#7", 0.95)
        val runs = sandbox.started.size
        val intake = submit(mapOf("ask" to "make the widget blue"))
        val issue = (vars(intake.id)["issue"] as Number).toLong()

        assertEquals(listOf(DUPLICATE_TASK), runtime.getActiveActivityIds(intake.id), "waiting on the user, not dropped")
        assertEquals(runs, sandbox.started.size, "no refine run before the answer")
        val (useCase, context, state) = jev.asked.single()
        assertEquals("intake-duplicate", useCase)
        assertEquals(intake.id to issue, context.processInstance to context.issue)
        assertTrue("make the widget blue" in state.toString())
        @Suppress("UNCHECKED_CAST")
        val options = jev.questionSets.single().getValue("duplicate").criteria as Map<String, String?>
        assertEquals(listOf("#7", "#8", "none"), options.keys.toList())
        assertEquals("Make the widget blue: The widget should be blue.", options["#7"])
        val call = jevCalls.get(jev.ids.single())!!
        assertEquals("#7 (0.95): asked the user before the refine", call.action)
        assertEquals(0.9, call.threshold)
        val pending = assertNotNull(questions.pending(intake.id))
        assertTrue("#7" in pending.question && "DELETE /intake/${intake.id}" in pending.question, pending.question)
        assertTrue(github.comments.any { it.second == issue && pending.question in it.third && "Decide it on " in it.third })

        decide(intake.id, "no, the knob, not the widget")
        runAllJobs()
        val brief = String(sandbox.started.last().inputs.getValue(BRIEF_FILE))
        assertTrue("This looks like #7" in brief && "no, the knob, not the widget" in brief, brief)
        assertTrue("UNTRUSTED" in brief && "The widget should be blue." in brief, "the snapshot Jev saw rides in the brief: $brief")
        assertEquals(1, sandbox.started.drop(runs).count { it.issue == issue && it.mode == "refine" })
        assertEquals("not cancelled; @lundmatt1 answered: no, the knob, not the widget", jevCalls.get(call.id)!!.outcome)
        assertEquals("file", vars(intake.id)["outcome"])
        assertNotNull(vars(intake.id)[FILED_WORK_ITEM])
    }

    @Test
    fun `cancelling at the duplicate question records it against the call`() {
        openTickets()
        jev.answers["duplicate"] = sure("#8", 0.97)
        val intake = submit(mapOf("ask" to "tidy the readme"))
        assertEquals(listOf(DUPLICATE_TASK), runtime.getActiveActivityIds(intake.id))
        assertTrue(intakes.cancel(intake.id, CancelRequest(reason = "yes, it is #8"), TestTokens.asLogin("lundmatt1")).ended)
        assertEquals("cancelled at the duplicate question by @lundmatt1: yes, it is #8", jevCalls.get(jev.ids.single())!!.outcome)
    }

    @Test
    fun `below the floor, none, no answer or no open tickets, the intake refines exactly as before`() {
        access.add("lundmatt1", "test")
        for ((answer, action) in listOf(sure("#7", 0.8) to "#7 below floor 0.9 (0.80): refined as before",
                sure("none", 0.99) to "none (0.99): refined as before", null to "no answer: refined as before")) {
            jev.reset(); openTickets()
            answer?.let { jev.answers["duplicate"] = it }
            val intake = submit(mapOf("ask" to "make the widget blue"))
            assertEquals(action, jevCalls.get(jev.ids.first())!!.action)
            assertTrue(DUPLICATE_TASK !in steps(intake.id), "no question")
            assertNull(vars(intake.id)["duplicateOf"])
            assertTrue("This looks like" !in String(sandbox.started.last().inputs[BRIEF_FILE] ?: ByteArray(0)))
            assertEquals("file", vars(intake.id)["outcome"])
        }

        jev.reset(); reader.listings.clear()
        val intake = submit(mapOf("ask" to "make the widget blue"))
        assertTrue(jev.asked.none { it.first == "intake-duplicate" }, "no open tickets: no call")
        assertEquals("file", vars(intake.id)["outcome"])

        jev.reset(); reader.unlistable += "acme/tickets"
        try {
            val unreadable = submit(mapOf("ask" to "make the widget blue"))
            assertEquals("file", vars(unreadable.id)["outcome"], "GitHub unreadable: refined as before")
        } finally { reader.unlistable -= "acme/tickets" }
    }


    /** An intake that filed: its id, its ask item's ticket number and its ticket. */
    private data class Earlier(val id: String, val issue: Long, val ticket: Long?)

    private fun earlier(outcome: String? = null): Earlier {
        val intake = submit(mapOf("ask" to "make the widget blue") + (outcome?.let { mapOf("fakeOutcome" to it) } ?: emptyMap()))
        val v = vars(intake.id)
        return Earlier(intake.id, (v["issue"] as Number).toLong(), (v["ticket"] as? Number)?.toLong())
    }

    @Test
    fun `a refine run's brief carries the engine's tier map, the ten rows with full ids, for the refiner to pick from`() {
        submit(mapOf("ask" to "make the widget blue"))
        val brief = sandbox.started.last { it.mode == "refine" }.inputs[BRIEF_FILE]!!
        @Suppress("UNCHECKED_CAST")
        val tiers = tools.jackson.module.kotlin.jacksonObjectMapper().readValue(brief, Map::class.java)["tiers"] as List<Map<String, Any?>>
        assertEquals((1..10).toList(), tiers.map { it["tier"] })
        assertEquals(mapOf("tier" to 8, "model" to "claude-fable-5-1", "effort" to "low"), tiers[7])
    }

    /** The refine brief's `intakes` for the last refine run. */
    @Suppress("UNCHECKED_CAST")
    private fun refineIntakes(): List<Map<String, Any?>> {
        val brief = sandbox.started.last { it.mode == "refine" }.inputs[BRIEF_FILE]!!
        return tools.jackson.module.kotlin.jacksonObjectMapper().readValue(brief, Map::class.java)["intakes"] as List<Map<String, Any?>>
    }



    @Test
    fun `a refine brief lists a still-refining sibling with its ask text`() {
        val a = earlier("ask")
        assertNull(a.ticket, "waiting at its question")
        submit(mapOf("ask" to "The same thing as the other one, see #${a.issue}"))

        val sibling = refineIntakes().single()
        assertEquals(listOf("#${a.issue}", "refining", "make the widget blue"), listOf(sibling["ref"], sibling["state"], sibling["ask"]))
    }

    @Test
    fun `an intakes entry's ask is cut to ASK_CHARS plus an ellipsis, and one at the limit is whole`() {
        val long = "x".repeat(IntakeRefs.ASK_CHARS + 1)
        val exact = "y".repeat(IntakeRefs.ASK_CHARS)
        val l = submit(mapOf("ask" to long))
        val e = submit(mapOf("ask" to exact))
        assertEquals("x".repeat(IntakeRefs.ASK_CHARS) + "…", assertNotNull(refs.resolve("acme/tickets", l.id)).ask)
        assertEquals(exact, assertNotNull(refs.resolve("acme/tickets", e.id)).ask)
    }









}

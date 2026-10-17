package com.codefrak.engine.chore

import com.codefrak.engine.TestTokens
import com.codefrak.engine.ProcessFakes
import com.codefrak.engine.finished
import com.codefrak.engine.brief
import com.codefrak.engine.ci.ConflictRounds
import com.codefrak.engine.runAllJobs
import com.codefrak.engine.smith.ensureTestAccount
import com.codefrak.engine.smith.dropTestAccount
import com.codefrak.engine.accounts.AiAccounts
import com.codefrak.engine.github.BundleChanges
import com.codefrak.engine.github.CreatedPull
import com.codefrak.engine.github.GitHubPulls
import com.codefrak.engine.github.GitPush
import com.codefrak.engine.github.IssueSnapshot
import com.codefrak.engine.github.MergeConflict
import com.codefrak.engine.github.MergeResult
import com.codefrak.engine.github.OpenPull
import com.codefrak.engine.github.PushRejected
import com.codefrak.engine.github.Pushed
import com.codefrak.engine.github.WORKFLOW_REJECTION
import com.codefrak.engine.intake.TicketRecorder
import com.codefrak.engine.intake.IntakeController
import com.codefrak.engine.intake.IntakeQuestions
import com.codefrak.engine.smith.BUNDLE_PATH
import com.codefrak.engine.smith.INPUT_DIR
import com.codefrak.engine.smith.OutputTooLarge
import com.codefrak.engine.smith.REPRO_DIR
import com.codefrak.engine.smith.tarOf
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.OUTPUT_PATH
import com.codefrak.engine.smith.SandboxStatus
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.smith.SmithSandbox
import com.codefrak.engine.smith.SmithSpec
import com.codefrak.engine.smith.Vars
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.impl.util.ClockUtil
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import com.codefrak.engine.camunda.IncidentController
import com.codefrak.engine.camunda.RetryRequest
import com.codefrak.engine.repos.Access
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Each run exits at once with the next result scripted for its mode; a "done" work run and a "drafted"
 * propose run leave a bundle unless told not to. A run that exits 1 leaves its scripted result for the next run of
 * its mode: the engine reruns a failed run once by itself (engine/README.md, "Incidents"), and a retry runs it again.
 */
class ScriptedSandbox : SmithSandbox {
    val started = mutableListOf<SmithSpec>()
    val script = mutableMapOf<String, ArrayDeque<String>>()
    var bundles = true
    /** The next this many work runs leave no bundle, whatever `bundles` says: a rejected `done` followed by an accepted one. */
    var unbundledWork = 0
    private val unbundled = mutableSetOf<String>()
    /** Runs to exit 1 from the next start on, then back to exit 0: 2 parks a step on its incident, past its automatic rerun. */
    var failNext = 0
    /**
     * The next `failModeRuns` runs of this mode exit 1, whatever `failNext` says. A step's first run needs 2 to park on
     * its incident, past its automatic rerun; a retried run, or a mode never rerun (`INCIDENT_MODES`), parks on 1.
     */
    var failMode: String? = null
    var failModeRuns = 1
    /** The log the next run of a mode leaves, instead of `smith: done`. */
    val logByMode = mutableMapOf<String, String>()
    /** The next run of this mode keeps running until it is stopped (a cancel), instead of exiting at once. */
    var holdMode: String? = null
    /** Modes every run of which is held until [stop] (or [release]) lets it exit; cleared by [reset]. */
    val holdModes = mutableSetOf<String>()
    private val held = mutableSetOf<String>()
    /** Every handle `stop` was called with. */
    val stopped = mutableListOf<String>()
    private val exits = mutableMapOf<String, Long>()
    private val logs = mutableMapOf<String, String>()
    private val results = mutableMapOf<String, Pair<String, String>>()
    /** Files every reproduce run leaves under [REPRO_DIR], by path in it, with their modes; `reproTooLarge`: past the cap. */
    val reproFiles = mutableMapOf<String, Pair<ByteArray, Int>>()
    var reproTooLarge = false
    /** While set, every start throws with this message, as `start smith` does with no Smith token for the repo. */
    var startFails: String? = null
    /** A file at [path] in a started run's sandbox, as the run reads it: its inputs under [INPUT_DIR]. */
    fun fileIn(spec: SmithSpec, path: String): ByteArray? = spec.inputs[path.removePrefix("$INPUT_DIR/")]?.takeIf { path.startsWith("$INPUT_DIR/") }
    fun reset(vararg runs: Pair<String, String>) {
        reproFiles.clear(); reproTooLarge = false
        started.clear(); script.clear(); bundles = true; failNext = 0; failMode = null; failModeRuns = 1; logByMode.clear(); callers.clear(); runAtFails = false
        holdMode = null; holdModes.clear(); held.clear(); stopped.clear(); unbundledWork = 0; unbundled.clear(); startFails = null
        runs.forEach { (mode, result) -> script.getOrPut(mode) { ArrayDeque() }.addLast(result) }
    }
    override fun start(spec: SmithSpec): String {
        startFails?.let { throw IllegalStateException(it) }
        started += spec
        val handle = "container-${started.size}"
        if (spec.mode == "work" && unbundledWork > 0) { unbundledWork--; unbundled += handle }
        exits[handle] = when {
            failMode == spec.mode -> 1L.also { if (--failModeRuns <= 0) { failMode = null; failModeRuns = 1 } }
            failNext > 0 -> 1L.also { failNext-- }
            else -> 0L
        }
        val scripted = script.getValue(spec.mode)
        results[handle] = spec.mode to if (exits[handle] == 0L) scripted.removeFirst() else scripted.first()
        logByMode.remove(spec.mode)?.let { logs[handle] = it }
        if (holdMode == spec.mode) { held += handle; holdMode = null }
        if (spec.mode in holdModes) held += handle
        return handle
    }
    override fun status(handle: String) = if (handle in held) SandboxStatus(true, null) else SandboxStatus(false, exits[handle] ?: 0)
    override fun stop(handle: String) { stopped += handle; held -= handle }
    /** A held run exits with its scripted result, as a Smith that was running ends: the next exit scan collects it. */
    fun release(handle: String) { held -= handle }
    override fun logs(handle: String, maxLines: Int) = logs[handle] ?: "smith: done\n"
    override fun output(handle: String, path: String): ByteArray? {
        val (mode, result) = results.getValue(handle)
        return when (path) {
            OUTPUT_PATH -> result.toByteArray()
            BUNDLE_PATH -> if (bundles && handle !in unbundled && (mode == "work" && "\"done\"" in result || mode == "propose" && "\"drafted\"" in result))
                "bundle of $handle".toByteArray() else null
            else -> null
        }
    }
    override fun outputDir(handle: String, path: String, maxBytes: Long): ByteArray? {
        if (path != REPRO_DIR || results.getValue(handle).first != "reproduce" || reproFiles.isEmpty()) return null
        if (reproTooLarge) throw OutputTooLarge(path, maxBytes)
        val dir = REPRO_DIR.substringAfterLast('/')
        return tarOf(dir, reproFiles.entries.associate { "$dir/${it.key}" to it.value.first },
            reproFiles.entries.associate { "$dir/${it.key}" to it.value.second })
    }
    override fun remove(handle: String) {}
    /** Smith sandboxes by address; `runAtFails`: Docker cannot be asked. */
    val callers = mutableMapOf<String, SmithCaller>()
    var runAtFails = false
    override fun runAt(address: String): SmithCaller? = if (runAtFails) error("docker unreachable") else callers[address]
}

/** `mergeDefault` answers from `merges` in order, then `Current`. */
class FakeGitPush : GitPush {
    val pushes = mutableListOf<Triple<String, String, String>>()
    val merges = ArrayDeque<MergeResult>()
    val mergeCalls = mutableListOf<Triple<String, String, String>>()
    /** What every push changes against the default branch; nothing under engine/ unless a test says so. */
    var paths = listOf("README.md", "src/Main.kt")
    /** Every push tried, its bundle, pushed or not; `failures` are thrown by the next pushes, in order. */
    val attempts = mutableListOf<String>()
    val failures = ArrayDeque<RuntimeException>()
    /** Run at every push tried, before its failure is thrown: a test's look at the job's state between tries. */
    var onAttempt: (() -> Unit)? = null
    override fun push(repo: String, branch: String, bundle: ByteArray, default: String): Pushed {
        attempts += String(bundle)
        onAttempt?.invoke()
        failures.removeFirstOrNull()?.let { throw it }
        pushes += Triple(repo, branch, String(bundle))
        return Pushed("sha-${pushes.size}", paths)
    }
    override fun mergeDefault(repo: String, branch: String, default: String): MergeResult {
        mergeCalls += Triple(repo, branch, default)
        return merges.removeFirstOrNull() ?: MergeResult.Current
    }
    /** Each `headBundle` asked for: the branch. */
    val headBundles = mutableListOf<String>()
    override fun headBundle(repo: String, branch: String, default: String): ByteArray {
        headBundles += branch
        return "head of $branch".toByteArray()
    }
    /** What `aheadBundle` answers, by branch: a branch already pushed ahead of the default; none unless a test says so. */
    val ahead = mutableMapOf<String, String>()
    override fun aheadBundle(repo: String, branch: String, default: String): ByteArray? = ahead[branch]?.toByteArray()
    /** `proposalChanges` answers from `changes` in order; with none queued, it has no answer. */
    val changes = ArrayDeque<BundleChanges>()
    val changeCalls = mutableListOf<List<String>>()
    override fun proposalChanges(repo: String, branch: String, default: String, bundle: ByteArray, path: String, pitch: String?): BundleChanges {
        changeCalls += listOfNotNull(repo, branch, default, String(bundle), path, pitch)
        return changes.removeFirstOrNull() ?: error("no proposal changes queued for $path")
    }
    /** Each `commitEdit`: repo, branch, path, message, and the text after the edit, applied to `docs[path]` (or ""). */
    val edits = mutableListOf<List<String>>()
    val docs = mutableMapOf<String, String>()
    override fun commitEdit(repo: String, branch: String, path: String, message: String, edit: (String) -> String): String? {
        val before = docs[path] ?: ""
        val after = edit(before)
        if (after == before) return null
        docs[path] = after
        edits += listOf(repo, branch, path, message, after)
        return "edit-${edits.size}"
    }
    /** Each `commitFiles`: repo, branch, default, the files (path to text) and the message. */
    data class Committed(val repo: String, val branch: String, val default: String, val files: Map<String, String>, val message: String)
    val commits = mutableListOf<Committed>()
    override fun commitFiles(repo: String, branch: String, default: String, files: Map<String, String>, message: String): String {
        require(branch == default) { "the engine commits files to the default branch only, not $branch" }
        commits += Committed(repo, branch, default, files, message)
        return "commit-${commits.size}"
    }
    fun reset() {
        paths = listOf("README.md", "src/Main.kt"); pushes.clear(); merges.clear(); mergeCalls.clear(); changes.clear(); changeCalls.clear()
        edits.clear(); docs.clear(); attempts.clear(); failures.clear(); onAttempt = null; commits.clear(); headBundles.clear(); ahead.clear()
    }
}
class FakeGitHubPulls : GitHubPulls {
    val opened = mutableListOf<List<String>>()
    val merged = mutableListOf<Pair<Long, String>>()
    /** PRs closed unmerged, by number. */
    val closed = mutableListOf<Long>()
    /** Merges to refuse with GitHub's 405 for merge conflicts before one goes through. */
    var conflictNext = 0
    /**
     * Open PRs by repo and head, as [openOnHead] lists them. Only a failed open ([failAfterOpenNext]) adds to it:
     * the bean is shared by every flow test, and a PR left listed would be foreign to a later flow on the same issue.
     */
    val openByHead = mutableMapOf<Pair<String, String>, MutableList<OpenPull>>()
    /** Opens to record and then fail, as when the job's transaction fails after GitHub created the PR. */
    var failAfterOpenNext = 0
    override fun defaultBranch(repo: String) = "main"
    override fun open(repo: String, head: String, base: String, title: String, body: String): CreatedPull {
        opened += listOf(repo, head, base, title, body)
        if (failAfterOpenNext > 0) {
            failAfterOpenNext--
            openByHead.getOrPut(repo to head) { mutableListOf() } += OpenPull(7, "https://github.com/$repo/pull/7", body)
            error("connection pool exhausted after the PR was created")
        }
        return CreatedPull(7, "https://github.com/$repo/pull/7")
    }
    override fun openOnHead(repo: String, head: String): List<OpenPull> = openByHead[repo to head].orEmpty().toList()
    /** The sha each merge answers (the squash commit on the base), as GitHub's merge response carries it. */
    var mergeSha = "merge0000000000000000000000000000000000f"
    override fun merge(repo: String, number: Long, title: String): String {
        if (conflictNext > 0) { conflictNext--; throw MergeConflict("405 Method Not Allowed: Pull Request has merge conflicts") }
        merged += number to title
        return mergeSha
    }
    override fun closePull(repo: String, number: Long) { closed += number }
    /** PRs reopened, by number; [failReopen] fails the call the way a GitHub 502 would. */
    val reopened = mutableListOf<Long>()
    var failReopen = false
    override fun reopenPull(repo: String, number: Long) {
        if (failReopen) error("502 Bad Gateway")
        reopened += number
    }
    /** PR heads moved since the engine's push, by number ([headSha]); a PR not here is still on the engine's pushed head. */
    val heads = mutableMapOf<Long, String>()
    override fun headSha(repo: String, number: Long): String? = heads[number]
    /** Comments on PRs, (repo, number, body), as [comment] posts them. */
    val comments: MutableList<Triple<String, Long, String>> = java.util.Collections.synchronizedList(mutableListOf())
    override fun comment(repo: String, number: Long, body: String) { comments += Triple(repo, number, body) }
    fun commentsOn(number: Long) = comments.filter { it.second == number }.map { it.third }
}
@SpringBootTest
@AutoConfigureMockMvc
@Import(ProcessFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class ChoreProcessTest {
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var hitlpRequests: com.codefrak.engine.hitlp.HitlpRequests
    @Autowired lateinit var hitlpPoller: com.codefrak.engine.hitlp.HitlpPoller

    /** Decides the HITLP wait [id] is parked on, as [by], through the shared stub and the poller. */
    private fun decide(id: String, answer: String, by: String = "lundmatt1") =
        com.codefrak.engine.hitlp.decideWait(com.codefrak.engine.hitlp.SharedHitlpStub.STUB, hitlpRequests, hitlpPoller, id, answer, by)

    @Autowired lateinit var accounts: AiAccounts
    @Autowired lateinit var history: HistoryService
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var exits: com.codefrak.engine.smith.SmithExitNotifier
    @Autowired lateinit var github: TicketRecorder
    @Autowired lateinit var pulls: FakeGitHubPulls
    @Autowired lateinit var git: FakeGitPush
    @Autowired lateinit var sandbox: ScriptedSandbox
    @Autowired lateinit var starter: FlowStarter
    @Autowired lateinit var questions: IntakeQuestions
    @Autowired lateinit var intakes: IntakeController
    @Autowired lateinit var tiers: com.codefrak.engine.tier.TierTable
    @Autowired lateinit var frakworksMcp: com.codefrak.engine.mcp.FrakworksMcp

    @AfterEach
    fun dropTestAccount() { accounts.dropTestAccount() }

    @BeforeEach
    fun clear() { pulls.opened.clear(); pulls.merged.clear(); pulls.closed.clear(); pulls.conflictNext = 0; pulls.openByHead.clear(); pulls.failAfterOpenNext = 0; pulls.comments.clear(); github.comments.clear(); git.reset(); accounts.ensureTestAccount() }

    // 300: the lifetime-cap test runs 7 work rounds and a failing job's retries before it settles.
    private fun runAllJobs() = management.runAllJobs(exits, rounds = 300)

    private fun start(issue: Long = 5, body: String? = null) = runtime.createMessageCorrelation("chore filed")
        .setVariables(mapOf("repo" to "acme/tickets", "issue" to issue) + listOfNotNull(body?.let {
            Vars.TICKET to org.operaton.bpm.engine.variable.Variables.byteArrayValue(
                Brief.ticketBytes(com.codefrak.engine.smith.Ticket(issue, "Tidy the readme", it, "lundmatt1")))
        })).correlateStartMessage().also { runAllJobs() }

    /** Other classes give acme/tickets an engine check on engine/ in the shared database; these paths are under it. */
    private fun noEngineCheck() { targetRepos.update("acme/tickets", null, emptyList(), true) }

    private fun done(vararg addressed: String) =
        """{"outcome":"done","title":"Tidy the readme","summary":"Tidied it.","addressed":[${addressed.joinToString { """{"finding":"$it","how":"fixed"}""" }}]}"""
    private val approved = """{"outcome":"approved","summary":"fine","findings":[]}"""
    private val passed = """{"outcome":"passed","summary":"fine","findings":[]}"""

    @Test
    fun `a chore is worked, pushed, security-reviewed on the pushed round, reviewed, merged and closed`() {
        sandbox.reset("work" to done(), "security" to passed, "review" to approved)
        val chore = start()

        assertTrue(history.finished(chore.id))
        assertEquals(listOf("work", "security", "review"), sandbox.started.map { it.mode })
        assertEquals(emptyMap(), sandbox.started[0].inputs, "round 1 has no brief")
        assertEquals("bundle of container-1", String(sandbox.started[1].inputs.getValue("commits.bundle")), "security reviews the round's commits")
        val security = brief(sandbox.started[1])!!
        assertEquals(1, security.round, "the push came first: security sees round 1 already pushed")
        assertEquals(7L, security.pr?.number, "and the PR open")
        assertEquals(listOf(Triple("acme/tickets", "smith/issue-5", "bundle of container-1")), git.pushes)
        val (_, head, base, title, body) = pulls.opened.single()
        assertEquals(listOf("smith/issue-5", "main", "Tidy the readme"), listOf(head, base, title))
        assertTrue("Closes #5" in body, body)
        val review = brief(sandbox.started[2])!!
        assertEquals(1, review.round)
        assertEquals(7L, review.pr?.number)
        assertEquals(listOf(7L to "Tidy the readme"), pulls.merged)
        assertEquals("acme/tickets" to 5L, github.closed.last())
        assertTrue("Merged #7" in github.comments.last().third)
    }

    /** #100043: a chunk pushed mid-run through push_chunk changes nothing in the flow; the done push runs the pipeline once. */
    @Test
    fun `a chunk pushed by the work run mid-run opens no pr and starts no review, and the done push runs the pipeline once`() {
        sandbox.reset("work" to done(), "security" to passed, "review" to approved); sandbox.holdMode = "work"
        val chore = start()
        assertEquals(listOf("work"), sandbox.started.map { it.mode })
        val run = sandbox.started.single()
        val caller = com.codefrak.engine.mcp.McpCaller.Smith(SmithCaller(run.runId, 5, "acme/tickets"))
        assertTrue(frakworksMcp.tools(caller).any { it.name == "push_chunk" }, "listed to the running work run")

        val chunk = java.util.Base64.getEncoder().encodeToString("chunk of container-1".toByteArray())
        val pushed = frakworksMcp.call("push_chunk", mapOf("bundle" to chunk, "message" to "the store"), caller)
        assertEquals(false, pushed.isError, pushed.text)
        assertEquals("sha-1", com.codefrak.engine.mcp.mcpJson.readValue(pushed.text, Map::class.java)["head"], pushed.text)
        runAllJobs()
        assertEquals(listOf(Triple("acme/tickets", "smith/issue-5", "chunk of container-1")), git.pushes, "the chunk is on the branch")
        assertEquals(emptyList<Any>(), pulls.opened, "no PR on a chunk")
        assertEquals(listOf("work"), sandbox.started.map { it.mode }, "no security or review run on a chunk")
        assertEquals(1L, runtime.createProcessInstanceQuery().processDefinitionKey("smith-run").variableValueEquals("issue", 5L).count(), "the work run is still in progress")
        assertNull(runtime.getVariable(chore.id, Vars.PR), "the flow saw no push")

        sandbox.release("container-1")
        runAllJobs()
        assertTrue(history.finished(chore.id))
        assertEquals(listOf("work", "security", "review"), sandbox.started.map { it.mode }, "the done push runs the pipeline once")
        assertEquals(listOf("chunk of container-1", "bundle of container-1"), git.pushes.map { it.third }, "the done push fast-forwards over the chunk")
        assertEquals(1, pulls.opened.size, "one PR, opened on the done push")
        assertEquals(listOf(7L to "Tidy the readme"), pulls.merged)
    }

    @Test
    fun `a docs-only push takes the docs path - no security run, one review, merged`() {
        git.paths = listOf("README.md", "docs/setup.md")
        sandbox.reset("work" to done(), "review" to approved)
        val chore = start()

        assertTrue(history.finished(chore.id))
        assertEquals(listOf("work", "review"), sandbox.started.map { it.mode }, "engine/README.md, \"Docs path\"")
        assertEquals(listOf(7L to "Tidy the readme"), pulls.merged)
    }

    @Test
    fun `a mixed push keeps the full pipeline, security review included`() {
        git.paths = listOf("README.md", "scripts/run.sh")
        sandbox.reset("work" to done(), "security" to passed, "review" to approved)
        val chore = start()

        assertTrue(history.finished(chore.id))
        assertEquals(listOf("work", "security", "review"), sandbox.started.map { it.mode })
    }

    @Test
    fun `a hitlp change parks for the user's acceptance after an approving review and merges only once accepted`() {
        noEngineCheck()
        git.paths = listOf("engine/modules/hitlp/src/main/kotlin/com/codefrak/engine/hitlp/HitlpTasks.kt", "README.md")
        sandbox.reset("work" to done(), "security" to passed, "review" to approved)
        val chore = start(15)

        assertEquals(listOf("user-task-answer-merge"), runtime.getActiveActivityIds(chore.id))
        assertEquals(emptyList(), pulls.merged, "no merge before the user accepts, whatever the review said")
        assertEquals("acceptance", com.codefrak.engine.intake.needKind("user-task-answer-merge"))
        assertTrue(github.comments.last().let { it.second == 15L && "merges only once you accept it" in it.third && "Decide it on " in it.third })

        decide(chore.id, "accept")
        runAllJobs()

        assertTrue(history.finished(chore.id))
        assertEquals(listOf(7L to "Tidy the readme"), pulls.merged)
    }

    @Test
    fun `a hitlp change the user does not accept goes back to the work with their words, and the new head is asked again`() {
        noEngineCheck()
        git.paths = listOf("engine/modules/app/src/main/resources/db/migration/V70__hitlp_rules.sql")
        sandbox.reset("work" to done(), "security" to passed, "review" to approved,
            "work" to done("merge-1-1"), "security" to passed, "review" to approved)
        val chore = start(16)

        decide(chore.id, "not yet: keep the old table")
        runAllJobs()

        assertEquals(listOf("user-task-answer-merge"), runtime.getActiveActivityIds(chore.id), "the second push needs its own acceptance")
        assertEquals(emptyList(), pulls.merged)
        assertEquals(listOf("work", "security", "review", "work"), sandbox.started.map { it.mode }.take(4))
        val finding = brief(sandbox.started[3])!!.findings.single()
        assertEquals("merge-1-1", finding.id)
        assertTrue("not yet: keep the old table" in finding.ask, finding.ask)

        decide(chore.id, "accepted")
        runAllJobs()

        assertTrue(history.finished(chore.id))
        assertEquals(listOf(7L to "Tidy the readme"), pulls.merged)
    }

    @Autowired lateinit var mcpServers: com.codefrak.engine.repos.RepoMcpServers

    /** Flows left waiting at call-post-deploy-check, deleted after each test: their waits would outlive it. */
    private val checking = mutableListOf<String>()

    @AfterEach
    fun deleteChecking() { checking.forEach { runCatching { runtime.deleteProcessInstance(it, "test done") } }; checking.clear() }

    private fun addOpsServer() { mcpServers.put("acme/tickets", "ops", mapOf("kind" to "http", "audience" to "smith", "role" to "ops", "url" to "https://ops.example/mcp"), "test") }

    /** Runs [block] with an ops-role MCP server on acme/tickets, removed after: the repo row is shared by every flow test. */
    private fun <T> withOpsServer(block: () -> T): T {
        addOpsServer()
        try { return block() } finally { mcpServers.delete("acme/tickets", "ops") }
    }

    /** The PR body's post-deployment check (bots/smith.md, "The post-deployment check"), as a JSON string's escapes. */
    private val postDeploySection = """\n\n## POST-DEPLOYMENT-CHECK\n\nThe next answer lands in the new table.\nRe-check: 4h"""

    @Test
    fun `a pr with a post-deployment check merges and waits at the check with the ticket open`() = withOpsServer {
        sandbox.reset("work" to """{"outcome":"done","title":"Tidy the readme","summary":"Tidied it.$postDeploySection"}""", "security" to passed, "review" to approved)
        val chore = start(117).also { checking += it.id }

        assertEquals(listOf(7L to "Tidy the readme"), pulls.merged)
        assertEquals(listOf("call-post-deploy-check"), runtime.getActiveActivityIds(chore.id).filter { it.startsWith("call-") })
        assertTrue(("acme/tickets" to 117L) !in github.closed, "the post-deployment check closes the ticket, not the chore")
    }

    @Test
    fun `a post-deployment check on a repo with no ops server parks the merge until the user accepts after one is added`() {
        sandbox.reset("work" to """{"outcome":"done","title":"Tidy the readme","summary":"Tidied it.$postDeploySection"}""", "security" to passed, "review" to approved)
        val chore = start(118).also { checking += it.id }

        assertEquals(listOf("user-task-answer-merge"), runtime.getActiveActivityIds(chore.id))
        assertEquals(emptyList(), pulls.merged, "no merge while nothing can say when it is in production")
        val asked = github.comments.last()
        assertTrue(asked.second == 118L && "add_repo_mcp_server(repo: \"acme/tickets\", role: \"ops\"" in asked.third && "ops/1" in asked.third, asked.third)

        decide(chore.id, "accept")
        runAllJobs()
        assertEquals(listOf("user-task-answer-merge"), runtime.getActiveActivityIds(chore.id), "accept with still no server asks again")
        assertEquals(emptyList(), pulls.merged)

        withOpsServer {
            decide(chore.id, "accept")
            runAllJobs()
        }
        assertEquals(listOf(7L to "Tidy the readme"), pulls.merged)
        assertEquals(listOf("call-post-deploy-check"), runtime.getActiveActivityIds(chore.id).filter { it.startsWith("call-") })
    }

    @Test
    fun `a retry after the pr was opened adopts the engine's own pr instead of opening a second`() {
        sandbox.reset("work" to done(), "security" to passed, "review" to approved)
        pulls.failAfterOpenNext = 1
        val chore = start(30)

        assertTrue(history.finished(chore.id))
        assertEquals(1, pulls.opened.size, "one PR opened; the retry adopted it")
        val vars = history.createHistoricVariableInstanceQuery().processInstanceId(chore.id).list().associate { it.name to it.value }
        assertEquals(7L, (vars[Vars.PR] as Number).toLong())
        assertEquals("https://github.com/acme/tickets/pull/7", vars[Vars.PR_URL])
        assertEquals(listOf("work", "security", "review"), sandbox.started.map { it.mode }, "the flow went on to the reviews")
        assertEquals(7L, brief(sandbox.started[2])!!.pr?.number)
        assertTrue(pulls.commentsOn(7).none { it.startsWith("Round 1") }, "no round comment for the adoption")
        assertEquals(listOf(7L to "Tidy the readme"), pulls.merged)
    }

    @Test
    fun `an open pr on the head without this flow's marker is not adopted and is the incident at open pr`() {
        sandbox.reset("work" to done(), "security" to passed, "review" to approved)
        pulls.openByHead["acme/tickets" to "smith/issue-31"] = mutableListOf(OpenPull(41, "https://github.com/acme/tickets/pull/41", "a person's PR"))
        val chore = start(31)

        assertEquals(emptyList(), pulls.opened, "no second PR opened")
        assertNull(runtime.getVariable(chore.id, Vars.PR))
        val incident = incidentsUnder(chore.id).single { it.activityId == "task-open-pr" }
        val message = incident.incidentMessage!!
        assertTrue("#41" in message && "smith/issue-31" in message, message)
        runtime.deleteProcessInstance(chore.id, "test done")
    }

    @Test
    fun `security findings go back to the work after the push, and every round updates the same pr`() {
        sandbox.reset(
            "work" to done(),                                                                        // container-1, push 1
            "security" to """{"outcome":"findings","findings":[{"ask":"drop the token","severity":"high"}]}""", // 2
            "work" to done("security-1-1"),                                                          // 3, push 2
            "security" to passed,                                                                    // 4
            "review" to """{"outcome":"changes","findings":[{"ask":"fix the typo","file":"README.md","line":3}]}""", // 5
            "work" to done("review-3-1"),                                                            // 6, push 3
            "security" to passed,                                                                    // 7
            "review" to approved,                                                                    // 8
        )
        val chore = start()

        assertTrue(history.finished(chore.id))
        assertEquals(listOf("bundle of container-1", "bundle of container-3", "bundle of container-6"), git.pushes.map { it.third },
            "every round is pushed before its security review, the one security sent back included")
        assertTrue(git.pushes.all { it.second == "smith/issue-5" })
        assertEquals(1, pulls.opened.size, "one PR, pushed to three times")
        val prComments = pulls.commentsOn(7)
        assertEquals(2, prComments.size)
        assertTrue(prComments[0].startsWith("Round 2:"), prComments[0])
        assertEquals(emptyList(), github.commentsOn(7), "a PR number is not a ticket: round comments go through GitHubPulls.comment")
        assertTrue("Addressed: security-1-1" in prComments[0], "the round comment names the id, not the finding")
        assertTrue(prComments.none { "drop the token" in it }, "security findings stay off GitHub")

        val works = sandbox.started.filter { it.mode == "work" }.map { brief(it) }
        assertNull(works[0])
        assertEquals(listOf("security-1-1"), works[1]!!.findings.map { it.id })
        assertEquals("security", works[1]!!.findings[0].review)
        assertEquals(1, works[1]!!.round, "the round security sent back is already on the PR")
        assertEquals("smith/issue-5", works[1]!!.pr?.branch, "so the fix continues the PR's branch")
        assertEquals(listOf("review-3-1"), works[2]!!.findings.map { it.id })
        assertEquals("correctness", works[2]!!.findings[0].review)
        assertEquals("smith/issue-5", works[2]!!.pr?.branch)

        val lastReview = brief(sandbox.started.last())!!
        assertEquals(3, lastReview.round)
        assertEquals(emptyList(), lastReview.findings, "security's pass leaves nothing open")
        assertEquals(listOf("security-1-1", "review-3-1"), lastReview.raised.map { it.id }, "but the reviewer can still read what it raised")
        assertEquals("review-3-1", (lastReview.work!!["addressed"] as List<*>).map { (it as Map<*, *>)["finding"] }.single())
        assertEquals(listOf(7L to "Tidy the readme"), pulls.merged)
    }

    @Test
    fun `starting a chore from a ticket body carrying a Tier line resolves the work step's model and effort`() {
        sandbox.reset("work" to done(), "security" to passed, "review" to approved)
        val ticket = IssueSnapshot(20, "Tidy the readme", "## Goal\n\nTidy.\n\n- Tier: 5\n", "author")
        val instance = starter.start("chore", "acme/tickets", ticket, "test")!!
        runAllJobs()

        assertTrue(history.finished(instance))
        assertEquals("claude-opus-5-5" to "medium", sandbox.started[0].model to sandbox.started[0].effort, "tier 5's [claude] mapping")
        assertEquals(5, (history.createHistoricVariableInstanceQuery().processInstanceId(instance).variableName(Vars.TIER).singleResult().value as Number).toInt())
    }

    @Test
    fun `the security-review step always uses security_tier, whatever the reviewed ticket's own tier`() {
        sandbox.reset("work" to done(), "security" to passed, "review" to approved)
        val ticket = IssueSnapshot(21, "Tidy the readme", "## Goal\n\nTidy.\n\nTier: 3\n", "author")
        val instance = starter.start("chore", "acme/tickets", ticket, "test")!!
        runAllJobs()

        assertTrue(history.finished(instance))
        assertEquals("claude-sonnet-5" to "medium", sandbox.started[0].model to sandbox.started[0].effort, "the work step follows the ticket's own tier 3")
        assertEquals("claude-opus-5-5" to "medium", sandbox.started[1].model to sandbox.started[1].effort, "security_tier (5), not tier 3")
    }

    /** The map is the engine's database (TierTable): a write applies to the next run that resolves a tier, no redeploy. */
    @Test
    fun `a tier and the security tier written to the map are what the next runs resolve`() {
        sandbox.reset("work" to done(), "security" to passed, "review" to approved)
        tiers.set(3, "fable", "max")
        tiers.setSecurityTier(9)
        try {
            val ticket = IssueSnapshot(23, "Tidy the readme", "## Goal\n\nTidy.\n\nTier: 3\n", "author")
            val instance = starter.start("chore", "acme/tickets", ticket, "test")!!
            runAllJobs()

            assertTrue(history.finished(instance))
            assertEquals("claude-fable-5-1" to "max", sandbox.started[0].model to sandbox.started[0].effort, "tier 3 as written, not as seeded")
            assertEquals("claude-fable-5-1" to "medium", sandbox.started[1].model to sandbox.started[1].effort, "the security tier as written (9)")
        } finally {
            // The H2 is shared with the other tests of this context: leave the seeded map behind.
            tiers.set(3, "sonnet", "medium")
            tiers.setSecurityTier(5)
        }
    }

    @Test
    fun `a security review does not change the tier of the work rounds after it`() {
        sandbox.reset(
            "work" to done(),
            "security" to """{"outcome":"findings","findings":[{"ask":"drop the token","severity":"high"}]}""",
            "work" to done("security-1-1"),
            "security" to passed, "review" to approved,
        )
        val ticket = IssueSnapshot(23, "Tidy the readme", "## Goal\n\nTidy.\n\nTier: 8\n", "author")
        val instance = starter.start("chore", "acme/tickets", ticket, "test")!!
        runAllJobs()

        assertTrue(history.finished(instance))
        assertEquals("claude-fable-5-1" to "low", sandbox.started[0].model to sandbox.started[0].effort, "tier 8")
        assertEquals("claude-opus-5-5" to "medium", sandbox.started[1].model to sandbox.started[1].effort, "security_tier")
        assertEquals("claude-fable-5-1" to "low", sandbox.started[2].model to sandbox.started[2].effort, "the second work round is still tier 8 (2026-09-25: it fell to 5)")
        assertEquals(8, (history.createHistoricVariableInstanceQuery().processInstanceId(instance).variableName(Vars.TIER).singleResult().value as Number).toInt())
    }

    @Test
    fun `a ticket with no tier anywhere falls back to the sandbox default without failing the flow`() {
        sandbox.reset("work" to done(), "security" to passed, "review" to approved)
        val ticket = IssueSnapshot(22, "Tidy the readme", "## Goal\n\nTidy, no tier line here.\n", "author")
        val instance = starter.start("chore", "acme/tickets", ticket, "test")!!
        runAllJobs()

        assertTrue(history.finished(instance))
        assertEquals("claude-sonnet-5" to "medium", sandbox.started[0].model to sandbox.started[0].effort)
        assertEquals("claude-opus-5-5" to "medium", sandbox.started[1].model to sandbox.started[1].effort, "the security review still runs at security_tier")
        assertEquals(0, history.createHistoricVariableInstanceQuery().processInstanceId(instance).variableName(Vars.TIER).count(), "no tier was ever written to the flow")
    }

    @Test
    fun `a work result that leaves no commits, or ignores a finding, runs the round again once, then stops before its push`() {
        sandbox.reset("work" to done(), "work" to done())
        sandbox.bundles = false
        val noCommits = start()
        assertEquals(2, sandbox.started.size, "the rejected run and its one rerun")
        assertEquals(1, incidentsUnder(noCommits.id).count { it.incidentMessage?.startsWith("the smith's result breaks the rules: ") == true && "left no commits" in it.incidentMessage })
        assertEquals(emptyList(), git.pushes)
        runtime.deleteProcessInstance(noCommits.id, "test done")

        sandbox.reset("work" to done(), "security" to """{"outcome":"findings","findings":[{"ask":"fix it"}]}""", "work" to done(),
            "work" to done("security-1-1"), "security" to passed, "review" to approved)
        val ignored = start(6)
        val rerun = brief(sandbox.started[3])!!
        assertEquals(listOf("security-1-1", "rules-1-1"), rerun.findings.map { it.id }, "the finding the round answered stays")
        assertTrue("findings not addressed or declined: [security-1-1]" in rerun.findings.last().ask)
        assertEquals(emptyList(), incidentsUnder(ignored.id))
        assertEquals(listOf("bundle of container-1", "bundle of container-4"), git.pushes.map { it.third }, "the round that ignored the finding was not pushed")
        assertTrue(history.finished(ignored.id))
    }

    @Test
    fun `a done with no commits on a branch that already holds the ticket's commits takes the pushed head for review`() {
        sandbox.reset("work" to done(), "security" to passed, "review" to approved)
        sandbox.bundles = false
        git.ahead["smith/issue-11"] = "pushed head of smith/issue-11"
        val chore = start(11)

        assertEquals(emptyList(), incidentsUnder(chore.id))
        assertEquals(listOf("work", "security", "review"), sandbox.started.map { it.mode }, "no rules rerun: the run is accepted")
        assertEquals(listOf("pushed head of smith/issue-11"), git.pushes.map { it.third })
        assertTrue(history.finished(chore.id))
    }

    @Test
    fun `a done with no commits reruns the work once with a rules finding, and the flow goes on when the rerun is accepted`() {
        sandbox.reset("work" to done(), "work" to done(), "security" to passed, "review" to approved)
        sandbox.unbundledWork = 1
        val chore = start(9)

        assertEquals(emptyList(), incidentsUnder(chore.id))
        assertEquals(listOf("work", "work", "security", "review"), sandbox.started.map { it.mode })
        val rerun = brief(sandbox.started[1])!!
        val rules = rerun.findings.single()
        assertEquals("rules-0-1", rules.id)
        assertTrue("outcome 'done' left no commits ($BUNDLE_PATH)" in rules.ask, rules.ask)
        assertTrue("`ask`" in rules.ask && "`nothing-to-do`" in rules.ask && "`done`" in rules.ask, rules.ask)
        assertEquals(listOf("rules-0-1"), rerun.raised.map { it.id })
        assertEquals(listOf("bundle of container-2"), git.pushes.map { it.third })
        assertTrue(github.comments.any { it.second == 9L && "rejected the work result" in it.third && "left no commits" in it.third })
        assertEquals("rejected", history.createHistoricVariableInstanceQuery().processInstanceId(
            history.createHistoricProcessInstanceQuery().superProcessInstanceId(chore.id).processDefinitionKey("reusable-smith-run")
                .orderByProcessInstanceStartTime().asc().list().first().id).variableName("outcome").singleResult().value)
        assertTrue(history.finished(chore.id))
    }

    @Test
    fun `a rerun rejected again parks on the incident, and its retry runs a new Smith`() {
        sandbox.reset("work" to done(), "work" to done(), "work" to done(), "security" to passed, "review" to approved)
        sandbox.bundles = false
        val chore = start(10)
        assertEquals(2, sandbox.started.size)
        val incident = incidentsUnder(chore.id).first { it.processInstanceId == chore.id }

        sandbox.bundles = true
        withGrant { incidents.retry(incident.id, RetryRequest(), TestTokens.asLogin("lundmatt1")) }
        runAllJobs()

        assertEquals(listOf("work", "work", "work", "security", "review"), sandbox.started.map { it.mode })
        assertTrue(history.finished(chore.id))
    }

    @Test
    fun `a work result of nothing-to-do closes the chore with its reason, with no review, pr or merge`() {
        sandbox.reset("work" to """{"outcome":"nothing-to-do","reason":"The readme is already tidy (a1b2c3)."}""")
        val chore = start(8)

        assertTrue(history.finished(chore.id))
        assertEquals(listOf("work"), sandbox.started.map { it.mode })
        assertEquals(emptyList(), git.pushes)
        assertEquals(emptyList(), pulls.opened)
        assertEquals(emptyList(), pulls.merged)
        assertEquals("acme/tickets" to 8L, github.closed.last())
        val (_, issue, comment) = github.comments.last()
        assertEquals(8L, issue)
        assertTrue(comment.startsWith("Closed: nothing to do. The readme is already tidy (a1b2c3)."), comment)
        assertTrue(starter.mergedClose(chore.id), "what Blocked by and a split's wake count as satisfied")
        assertNull(starter.mergedPr(chore.id))
    }

    @Test
    fun `a work smith's question is recorded and posted, the answer completes the wait, and the work runs again told the answer`() {
        sandbox.reset("work" to """{"outcome":"ask","question":"Keep the old badge?"}""", "work" to done(), "security" to passed, "review" to approved)
        val chore = start(14)

        // Waiting on the user, not an incident, with the question where GET /questions and the board find it.
        assertEquals(listOf("user-task-answer-question"), runtime.getActiveActivityIds(chore.id))
        assertEquals(0L, runtime.createIncidentQuery().processInstanceId(chore.id).count())
        val pending = questions.pending(chore.id)!!
        assertEquals(Triple("acme/tickets", 14L, "Keep the old badge?"), Triple(pending.repo, pending.issue, pending.question))
        assertEquals(listOf(pending.id), questions.open().filter { it.intake == chore.id }.map { it.id })
        assertTrue(github.comments.last().let { it.second == 14L && "The work Smith has a question (1)" in it.third && "Decide it on " in it.third })

        decide(chore.id, "yes, keep it")
        runAllJobs()

        assertTrue(history.finished(chore.id))
        assertNull(questions.pending(chore.id))
        assertEquals(listOf("work", "work", "security", "review"), sandbox.started.map { it.mode })
        val answer = brief(sandbox.started[1])!!.answers.single()
        assertEquals(listOf("Keep the old badge?", "yes, keep it", "lundmatt1"), listOf(answer.question, answer.answer, answer.by))
        assertEquals(listOf(7L to "Tidy the readme"), pulls.merged)
    }

    @Test
    fun `a nothing-to-do without a reason stops before anything is closed`() {
        sandbox.reset("work" to """{"outcome":"nothing-to-do"}""", "work" to """{"outcome":"nothing-to-do"}""")
        val chore = start(9)
        assertEquals(listOf("work", "work"), sandbox.started.map { it.mode }, "the first rejection reruns the round once")
        assertEquals(1L, runtime.createIncidentQuery().incidentMessageLike("%'nothing-to-do' needs a reason%").count())
        assertTrue(github.closed.none { it == "acme/tickets" to 9L })
        runtime.deleteProcessInstance(chore.id, "test done")
    }

    // ---- bringing the PR up to date with the default branch (RebasePrDelegate) ----

    private fun clean(n: Int) = MergeResult.Clean("head-$n", "merge-$n", "merge bundle $n".toByteArray())

    @Test
    fun `a branch behind main that merges cleanly is merged as is, with no catch-up round`() {
        sandbox.reset("work" to done(), "security" to passed, "review" to approved)
        git.merges.addLast(clean(1))
        val chore = start()

        assertTrue(history.finished(chore.id))
        assertEquals(listOf("work", "security", "review"), sandbox.started.map { it.mode })
        assertEquals(listOf("bundle of container-1"), git.pushes.map { it.third }, "no merge pushed")
        assertEquals(emptyList(), pulls.commentsOn(7), "no catch-up round")
        assertEquals(1, git.mergeCalls.size)
        assertEquals(listOf(7L to "Tidy the readme"), pulls.merged)
    }

    /** Behind and clean, then GitHub's 405 at merge pr: the catch-up round, on the same clean merge. */
    private fun cleanAfter405(n: Int) {
        pulls.conflictNext = 1
        git.merges.addLast(clean(n)); git.merges.addLast(clean(n))
    }

    @Test
    fun `after github's 405 a branch that merges cleanly is pushed, security-reviewed with the merge entry, reviewed again, then merged`() {
        sandbox.reset("work" to done(), "security" to passed, "review" to approved, "security" to passed, "review" to approved)
        cleanAfter405(1)
        val chore = start()

        assertTrue(history.finished(chore.id))
        assertEquals(listOf("work", "security", "review", "security", "review"), sandbox.started.map { it.mode })
        val security = sandbox.started[3]
        assertEquals("merge bundle 1", String(security.inputs.getValue("commits.bundle")), "security reviews the pushed merge")
        assertEquals(2, brief(security)!!.round, "the catch-up was pushed as round 2 before its security review")
        assertEquals(mapOf("default" to "main", "oldHead" to "head-1", "merge" to "merge-1"), brief(security)!!.merge)
        assertNull(brief(sandbox.started[4])!!.merge, "only a security run is told of the merge")
        assertEquals(listOf("bundle of container-1", "merge bundle 1"), git.pushes.map { it.third })
        assertEquals(listOf("Round 2, catch-up: merged main into the branch; no changes from review"), pulls.commentsOn(7))
        assertEquals(3, git.mergeCalls.size, "rebase, then again after the 405, then after the merge's review")
        assertTrue(git.mergeCalls.all { it == Triple("acme/tickets", "smith/issue-5", "main") })
        assertEquals(listOf(7L to "Tidy the readme"), pulls.merged)
        assertEquals("acme/tickets" to 5L, github.closed.last())
    }

    @Test
    fun `a pushed merge with security findings goes back to the work, which continues from it`() {
        sandbox.reset(
            "work" to done(), "security" to passed, "review" to approved,
            "security" to """{"outcome":"findings","findings":[{"ask":"the merge brings in a token","severity":"high"}]}""",
            "work" to done("security-3-1"), "security" to passed, "review" to approved,
        )
        cleanAfter405(1)
        val chore = start()

        assertTrue(history.finished(chore.id))
        assertEquals(listOf("bundle of container-1", "merge bundle 1", "bundle of container-5"), git.pushes.map { it.third },
            "the merge is pushed before its security review")
        val prComments = pulls.commentsOn(7)
        assertEquals(2, prComments.size)
        assertTrue(prComments[0].startsWith("Round 2, catch-up:"), prComments[0])
        assertTrue(prComments[1].startsWith("Round 3: Tidied it."), "a work round's comment, not the merge's")
        val work = brief(sandbox.started[4])!!
        assertNull(work.merge, "the work round after is not told of a merge it never made")
        assertEquals(2, work.round, "it continues from the pushed merge")
        assertEquals(listOf(7L to "Tidy the readme"), pulls.merged)
    }

    @Test
    fun `a conflicted merge goes back to a work round with a finding naming the files`() {
        sandbox.reset("work" to done(), "security" to passed, "review" to approved,
            "work" to done("rebase-1-1"), "security" to passed, "review" to approved)
        git.merges.addLast(MergeResult.Conflict(listOf("a.txt", "b.txt")))
        val chore = start()

        assertTrue(history.finished(chore.id))
        assertEquals(listOf("work", "security", "review", "work", "security", "review"), sandbox.started.map { it.mode })
        val finding = brief(sandbox.started[3])!!.findings.single()
        assertEquals("rebase-1-1", finding.id)
        assertEquals("rebase", finding.review)
        assertTrue(finding.ask.startsWith("merge conflict with the default branch in a.txt, b.txt"), finding.ask)
        assertEquals("smith/issue-5", brief(sandbox.started[3])!!.pr?.branch)
        assertEquals(listOf("bundle of container-1", "bundle of container-4"), git.pushes.map { it.third })
        assertEquals(listOf(7L to "Tidy the readme"), pulls.merged)
    }

    @Test
    fun `the conflict finding tells the smith to merge origin main first and re-run the tests`() {
        val ask = ConflictRounds.conflictAsk("main", listOf("a.txt"))
        assertTrue("merge conflict with the default branch in a.txt" in ask, ask)
        val merge = ask.indexOf("git merge origin/main")
        assertTrue(merge >= 0, ask)
        assertTrue(merge < ask.indexOf("resolve every conflict"), "merge first, then resolve: $ask")
        assertTrue("re-run the tests" in ask, ask)
    }

    @Test
    fun `the third conflict parks the chore on an incident and nothing is merged`() {
        sandbox.reset("work" to done(), "security" to passed, "review" to approved,
            "work" to done("rebase-1-1"), "security" to passed, "review" to approved,
            "work" to done("rebase-2-1"), "security" to passed, "review" to approved)
        repeat(10) { git.merges.addLast(MergeResult.Conflict(listOf("a.txt"))) } // the conflict stays for the failed job's retries
        val chore = start(11)

        assertEquals(3, sandbox.started.count { it.mode == "work" }, "the first round and two conflict rounds")
        val incident = runtime.createIncidentQuery().processInstanceId(chore.id).singleResult()
        assertEquals("task-rebase-pr", incident.activityId)
        assertTrue("after 2 conflict rounds" in incident.incidentMessage, incident.incidentMessage)
        assertEquals(emptyList(), pulls.merged)
        runtime.deleteProcessInstance(chore.id, "test done")
    }

    @Test
    fun `retrying the third-conflict incident grants the work another round, which resolves it and merges`() {
        sandbox.reset(*conflictRounds(4))
        repeat(10) { git.merges.addLast(MergeResult.Conflict(listOf("a.txt"))) }
        val chore = start(13)
        val incident = runtime.createIncidentQuery().processInstanceId(chore.id).singleResult()
        assertEquals("task-rebase-pr", incident.activityId)

        git.merges.clear()
        git.merges.addLast(MergeResult.Conflict(listOf("a.txt"))) // still conflicting at the retry; the round after finds it current
        val retried = withGrant { incidents.retry(incident.id, RetryRequest(), TestTokens.asLogin("lundmatt1")) }
        runAllJobs()

        assertEquals("job", retried.action)
        assertEquals(4, sandbox.started.count { it.mode == "work" }, "the retry granted one more work round")
        val finding = brief(sandbox.started.filter { it.mode == "work" }.last())!!.findings.single()
        assertEquals("rebase-3-1", finding.id)
        assertEquals("rebase", finding.review)
        assertTrue(github.comments.any { "granted ${ConflictRounds.MAX_CONFLICT_ROUNDS} more conflict rounds and retried `task-rebase-pr`" in it.third })
        assertTrue(history.finished(chore.id))
        assertEquals(listOf(7L to "Tidy the readme"), pulls.merged)
    }

    @Test
    fun `retrying the lifetime-cap incident grants the work another round past the cap`() {
        val cap = ConflictRounds.MAX_TOTAL_CONFLICT_ROUNDS
        sandbox.reset(*conflictRounds(cap + 2))
        resolvedConflicts(cap + 1)
        repeat(10) { git.merges.addLast(MergeResult.Conflict(listOf("a.txt"))) }
        val chore = start(23)
        val incident = runtime.createIncidentQuery().processInstanceId(chore.id).singleResult()
        assertTrue("lifetime cap" in incident.incidentMessage, incident.incidentMessage)

        git.merges.clear()
        git.merges.addLast(MergeResult.Conflict(listOf("a.txt")))
        val retried = withGrant { incidents.retry(incident.id, RetryRequest(), TestTokens.asLogin("lundmatt1")) }
        runAllJobs()

        assertEquals("job", retried.action)
        assertEquals(cap + 2, sandbox.started.count { it.mode == "work" }, "one granted round past the cap")
        assertEquals("rebase-${cap + 1}-1", brief(sandbox.started.filter { it.mode == "work" }.last())!!.findings.single().id)
        assertTrue(history.finished(chore.id))
        assertEquals(listOf(7L to "Tidy the readme"), pulls.merged)
    }

    @Test
    fun `the retrier grants as many rounds as the consecutive cap`() {
        assertEquals(ConflictRounds.MAX_CONFLICT_ROUNDS, com.codefrak.engine.camunda.IncidentRetrier.GRANTED_CONFLICT_ROUNDS)
    }

    /** Work rounds 1..n, each security-reviewed and reviewed; round k > 1 addresses `rebase-<k-1>-1`. */
    private fun conflictRounds(n: Int) = (1..n).flatMap { k ->
        listOf("work" to (if (k == 1) done() else done("rebase-${k - 1}-1")), "security" to passed, "review" to approved)
    }.toTypedArray()

    /** [n] conflicts, each resolved by the next work round: the probe after it finds the branch current, then main moves (405). */
    private fun resolvedConflicts(n: Int) {
        git.merges.addLast(MergeResult.Conflict(listOf("a.txt")))
        repeat(n - 1) { git.merges.addLast(MergeResult.Current); git.merges.addLast(MergeResult.Conflict(listOf("a.txt"))) }
        pulls.conflictNext = n - 1
    }

    @Test
    fun `a resolved conflict round starts the count over, so three resolved conflicts still merge`() {
        sandbox.reset(*conflictRounds(4))
        resolvedConflicts(3)
        val chore = start(21)

        assertTrue(history.finished(chore.id))
        assertEquals(emptyList(), runtime.createIncidentQuery().processInstanceId(chore.id).list())
        assertEquals(4, sandbox.started.count { it.mode == "work" }, "the first round and three conflict rounds")
        val ids = sandbox.started.filter { it.mode == "work" }.drop(1).map { brief(it)!!.findings.single().id }
        assertEquals(listOf("rebase-1-1", "rebase-2-1", "rebase-3-1"), ids, "the ids stay distinct across a reset")
        assertEquals(listOf(7L to "Tidy the readme"), pulls.merged)
    }

    @Test
    fun `resolved conflicts past the lifetime cap park the chore on an incident and nothing is merged`() {
        val cap = ConflictRounds.MAX_TOTAL_CONFLICT_ROUNDS
        sandbox.reset(*conflictRounds(cap + 1))
        resolvedConflicts(cap + 1)
        repeat(10) { git.merges.addLast(MergeResult.Conflict(listOf("a.txt"))) } // the conflict stays for the failed job's retries
        val chore = start(22)

        assertEquals(cap + 1, sandbox.started.count { it.mode == "work" }, "the first round and $cap conflict rounds")
        val incident = runtime.createIncidentQuery().processInstanceId(chore.id).singleResult()
        assertEquals("task-rebase-pr", incident.activityId)
        assertTrue("lifetime cap" in incident.incidentMessage, incident.incidentMessage)
        assertTrue("a.txt" in incident.incidentMessage, incident.incidentMessage)
        assertEquals(emptyList(), pulls.merged)
        runtime.deleteProcessInstance(chore.id, "test done")
    }

    @Test
    fun `github's merge conflict at merge pr goes back through the rebase step`() {
        sandbox.reset("work" to done(), "security" to passed, "review" to approved, "security" to passed, "review" to approved)
        pulls.conflictNext = 1
        git.merges.addLast(MergeResult.Current) // git says current, GitHub then refuses: main moved in between
        git.merges.addLast(clean(2))
        val chore = start()

        assertTrue(history.finished(chore.id))
        assertEquals(3, git.mergeCalls.size, "rebase, then again after the 405, then after the merge's review")
        assertEquals(listOf("bundle of container-1", "merge bundle 2"), git.pushes.map { it.third })
        assertEquals(listOf("work", "security", "review", "security", "review"), sandbox.started.map { it.mode })
        assertEquals(listOf(7L to "Tidy the readme"), pulls.merged)
    }

    @Test
    fun `a 405 conflict that git calls current fails rather than loop`() {
        sandbox.reset("work" to done(), "security" to passed, "review" to approved)
        pulls.conflictNext = 1
        val chore = start(12)

        val incident = runtime.createIncidentQuery().processInstanceId(chore.id).singleResult()
        assertEquals("task-rebase-pr", incident.activityId)
        assertEquals(emptyList(), pulls.merged)
        runtime.deleteProcessInstance(chore.id, "test done")
    }

    // ---- retrying an incident (IncidentController); here to share this context, not start another ----

    @Autowired lateinit var incidents: IncidentController
    @Autowired lateinit var access: Access
    @Autowired lateinit var targetRepos: com.codefrak.engine.repos.TargetRepos

    /**
     * A grant for the test's span only: the tests above start chores with no one granted.
     * The repo is registered here too: a grant references it, and since each context has
     * its own database (application.properties) no other test class has added it first.
     */
    private fun <T> withGrant(block: () -> T): T {
        targetRepos.add("acme/tickets")
        access.add("lundmatt1", "test")
        try { return block() } finally { access.remove("lundmatt1", "test") }
    }

    /** The repo's row with security reviews off for the block, then on again: the tests above share the row. */
    private fun <T> withoutSecurityReview(block: () -> T): T {
        targetRepos.add("acme/tickets")
        targetRepos.update("acme/tickets", null, emptyList(), false)
        try { return block() } finally { targetRepos.update("acme/tickets", null, emptyList(), true) }
    }

    @Test
    fun `a repo with security reviews off goes from the push to review pr on every round, the catch-up included, and merges`() = withoutSecurityReview {
        sandbox.reset(
            "work" to done(),                                                                        // push 1
            "review" to """{"outcome":"changes","findings":[{"ask":"fix the typo","file":"README.md","line":3}]}""",
            "work" to done("review-1-1"),                                                            // push 2
            "review" to approved,
            "review" to approved,                                                                    // after the catch-up, push 3
        )
        cleanAfter405(1)
        val chore = start()

        assertTrue(history.finished(chore.id))
        assertEquals(listOf("work", "review", "work", "review", "review"), sandbox.started.map { it.mode })
        assertTrue(sandbox.started.none { it.mode == "security" }, "no security run on any round")
        assertEquals(listOf("bundle of container-1", "bundle of container-3", "merge bundle 1"), git.pushes.map { it.third })
        assertEquals(3, brief(sandbox.started.last())!!.round, "the catch-up push went straight to review pr")
        assertEquals(false, history.createHistoricVariableInstanceQuery().processInstanceId(chore.id).variableName(Vars.SECURITY_REVIEW).singleResult().value)
        assertEquals(listOf(7L to "Tidy the readme"), pulls.merged)
        assertEquals("acme/tickets" to 5L, github.closed.last())
    }

    @Test
    fun `a chore cancelled with its work run in progress has its smith stopped, no instance left, and its issue closed as not planned`() {
        sandbox.reset("work" to done()); sandbox.holdMode = "work"
        val chore = start(33)
        assertEquals(listOf("work"), sandbox.started.map { it.mode })
        assertEquals(1L, runtime.createProcessInstanceQuery().processDefinitionKey("smith-run").variableValueEquals("issue", 33L).count(), "the run is in progress")

        val state = withGrant { intakes.cancel(chore.id, com.codefrak.engine.intake.CancelRequest(reason = "wrong approach"), TestTokens.asLogin("lundmatt1")) }
        assertTrue(state.ended)
        assertEquals(emptyList(), state.failedWrites)
        assertEquals(listOf("container-1"), sandbox.stopped)
        assertEquals(0L, runtime.createProcessInstanceQuery().variableValueEquals("issue", 33L).count(), "neither the chore nor its run is left")
        assertEquals(com.codefrak.engine.github.NOT_PLANNED, github.closedAs["acme/tickets" to 33L])
        val said = github.comments.last { it.second == 33L }.third
        assertTrue("@lundmatt1 cancelled this chore" in said && "wrong approach" in said && "Its running Smith is stopped." in said, said)
        assertEquals(emptyList(), pulls.closed, "it had opened no PR")
    }

    @Test
    fun `a chore cancelled after its push closes its PR unmerged and keeps the branch`() {
        sandbox.reset("work" to done(), "security" to passed); sandbox.holdMode = "security"
        val chore = start(34)
        assertEquals(1, pulls.opened.size)

        val state = withGrant { intakes.cancel(chore.id, com.codefrak.engine.intake.CancelRequest(reason = "not needed"), TestTokens.asLogin("lundmatt1")) }
        assertTrue(state.ended)
        assertEquals(listOf("container-2"), sandbox.stopped)
        assertEquals(listOf(7L), pulls.closed)
        assertTrue("Closed unmerged" in pulls.comments.last { it.second == 7L }.third)
        assertTrue(github.comments.none { it.second == 7L }, "the PR is commented through GitHubPulls, never as a ticket")
        assertEquals(emptyList(), pulls.merged)
        val said = github.comments.last { it.second == 34L }.third
        assertTrue("PR #7 is closed unmerged." in said && "Kept: branch `smith/issue-34`" in said, said)
        assertEquals(com.codefrak.engine.github.NOT_PLANNED, github.closedAs["acme/tickets" to 34L])
    }

    // ---- amend_ticket (#1362) ----

    @Autowired lateinit var ticketAmender: com.codefrak.engine.intake.TicketAmender

    private fun amend(n: Long, text: String, by: String = "lundmatt1") = withGrant { ticketAmender.amend("acme/tickets", n, text, by) }

    @Test
    fun `an amendment to a chore whose work runs stops it and runs the work again on the same instance with it in the brief`() {
        sandbox.reset("work" to done(), "work" to done(), "security" to passed, "review" to approved); sandbox.holdMode = "work"
        val chore = start(40, "## Goal\n\nTidy it.")
        val first = runtime.createProcessInstanceQuery().processInstanceId(chore.id).singleResult()
        val workBefore = history.createHistoricActivityInstanceQuery().processInstanceId(chore.id).activityId("call-do-work").list().map { it.id }

        val done = amend(40, "and the changelog too")
        assertEquals("restarted", done.work)
        assertEquals("the stopped work run's unpushed commits", done.lost, "no PR yet: its commits were never pushed")
        assertEquals(emptyList(), done.failedWrites)
        assertEquals(listOf("container-1"), sandbox.stopped)
        runAllJobs()

        assertTrue(history.finished(chore.id), "the same instance went on and merged")
        assertEquals(first.id, chore.id)
        val workAfter = history.createHistoricActivityInstanceQuery().processInstanceId(chore.id).activityId("call-do-work").list().map { it.id }
        assertEquals(2, workAfter.size); assertTrue(workBefore.single() in workAfter)
        assertEquals(listOf("work", "work", "security", "review"), sandbox.started.map { it.mode })
        val brief = brief(sandbox.started[1])!!
        assertEquals(listOf("lundmatt1" to "and the changelog too"), brief.amendments.map { it.by to it.text })
        assertTrue("## Amendments\n\n- @lundmatt1, " in brief.ticket!!.body && "and the changelog too" in brief.ticket!!.body, brief.ticket!!.body)
        val (_, n, body) = github.edited.last()
        assertEquals(40L, n); assertTrue(body.startsWith("## Goal\n\nTidy it.\n\n## Amendments\n\n- @lundmatt1, "), body)
        val said = github.comments.first { it.second == 40L && "added:" in it.third }.third
        assertEquals("@lundmatt1 added:\n\nand the changelog too\n\n— engine, the work runs again with it", said)
        assertEquals(emptyList(), pulls.closed); assertTrue(github.closedAs["acme/tickets" to 40L] != com.codefrak.engine.github.NOT_PLANNED)
    }

    @Test
    fun `an amendment at the security review or the review cancels it and runs the work again, keeping the PR and the issue open`() {
        for ((n, mode) in listOf(41L to "security", 42L to "review")) {
            sandbox.reset("work" to done(), "security" to passed, "review" to approved, "work" to done(), "security" to passed, "review" to approved)
            sandbox.holdMode = mode
            val chore = start(n, "Tidy it.")
            val active = if (mode == "security") "call-security-review" else "call-review-pr"
            assertTrue(active in runtime.getActiveActivityIds(chore.id), runtime.getActiveActivityIds(chore.id).toString())
            val closedPulls = pulls.closed.size

            val done = amend(n, "one more thing")
            assertEquals("restarted", done.work, mode)
            assertNull(done.lost, "a $mode run's sandbox holds no commits")
            assertEquals(1, sandbox.stopped.size, mode)
            assertEquals(closedPulls, pulls.closed.size, "the PR stays open")
            assertTrue(github.closedAs["acme/tickets" to n] == null, "the issue stays open")
            runAllJobs()
            val works = sandbox.started.filter { it.mode == "work" }
            assertEquals(2, works.size, mode)
            assertEquals(listOf("one more thing"), brief(works.last())!!.amendments.map { it.text })
            assertEquals(7L, brief(works.last())!!.pr?.number, "the new round continues on the PR")
        }
    }

    @Test
    fun `an amendment while the work's question waits is recorded only, and the run after the answer carries it`() {
        sandbox.reset("work" to """{"outcome":"ask","question":"Which file?"}""", "work" to done(), "security" to passed, "review" to approved)
        val chore = start(43, "Tidy it.")
        assertTrue(runtime.getActiveActivityIds(chore.id).any { it.startsWith("user-task-answer-") })
        val runs = sandbox.started.size
        val done = amend(43, "the README only")
        assertEquals("waits on a question", done.work)
        assertNull(done.lost)
        assertEquals(runs, sandbox.started.size); assertTrue(sandbox.stopped.isEmpty())
        assertTrue(github.comments.last { it.second == 43L }.third.endsWith("— engine, the question still waits; the next round reads it"))
        decide(chore.id, "README.md")
        runAllJobs()
        val brief = brief(sandbox.started[runs])!!
        assertEquals(listOf("the README only"), brief.amendments.map { it.text })
        assertTrue("the README only" in brief.ticket!!.body)
    }

    @Test
    fun `two amendments in a row both land in the section and in amendments, oldest first`() {
        sandbox.reset("work" to done(), "work" to done(), "work" to done(), "security" to passed, "review" to approved); sandbox.holdMode = "work"
        val chore = start(44, "Tidy it.")
        amend(44, "first")
        sandbox.holdMode = "work"; runAllJobs()
        amend(44, "second")
        runAllJobs()
        assertTrue(history.finished(chore.id))
        val brief = brief(sandbox.started.filter { it.mode == "work" }.last())!!
        assertEquals(listOf("first", "second"), brief.amendments.map { it.text })
        val body = github.edited.last().third
        assertEquals(1, Regex("## Amendments").findAll(body).count(), body)
        assertTrue(body.indexOf("first") in 0 until body.indexOf("second"), body)
    }

    @Test
    fun `amend_ticket's refusals record nothing`() {
        // Only the refused calls themselves are checked: the flows started below edit their own issues.
        // Issue numbers no other test uses: an intake on the same repo and number elsewhere in the shared engine would answer "is an intake".
        fun refused(repo: String?, n: Long, text: String, by: String = "lundmatt1"): ResponseStatusException {
            val editsBefore = github.edited.size
            val outcome = runCatching { withGrant { ticketAmender.amend(repo, n, text, by) } }
            val e = outcome.exceptionOrNull() as? ResponseStatusException
                ?: fail("$repo#$n: expected a refusal, got $outcome")
            assertEquals(editsBefore, github.edited.size, "nothing recorded on the body")
            return e
        }
        assertEquals("text is blank", refused("acme/tickets", 5, " ").reason)
        assertEquals("text is at most ${com.codefrak.engine.mcp.FrakworksMcp.MAX_ANSWER_CHARS} characters",
            refused("acme/tickets", 5, "x".repeat(com.codefrak.engine.mcp.FrakworksMcp.MAX_ANSWER_CHARS + 1)).reason)
        assertTrue(refused(null, 5, "x").reason!!.startsWith("repo is required (owner/name): there is no default repo; the target repos are "))
        assertEquals("acme/nope is not a target repo", refused("acme/nope", 5, "x").reason)
        assertEquals("@someone-else is not a trusted login", refused("acme/tickets", 5, "x", "someone-else").reason)
        assertEquals("ticket acme/tickets#13640 is merged/closed; file a follow-up", refused("acme/tickets", 13640, "x").reason)

        sandbox.reset("work" to done(), "security" to passed, "review" to approved)
        val chore = start(13645, "Tidy it.")
        if (!history.finished(chore.id)) fail("the chore did not finish: " + runtime.getActiveActivityIds(chore.id))
        val merged = refused("acme/tickets", 13645, "x")
        assertEquals(409, merged.statusCode.value())
        assertEquals("ticket acme/tickets#13645 is merged/closed; file a follow-up", merged.reason)

        // A flow waiting at a service task's async job is between steps.
        val parked = runtime.createProcessInstanceByKey("chore").startBeforeActivity("task-open-pr")
            .setVariables(mapOf("repo" to "acme/tickets", "issue" to 13646L)).execute()
        try {
            assertEquals(listOf("task-open-pr"), runtime.getActiveActivityIds(parked.id))
            assertEquals("ticket acme/tickets#13646 is between steps; try again in a moment", refused("acme/tickets", 13646, "x").reason)
        } finally { runtime.deleteProcessInstance(parked.id, "test done") }
    }

    private fun incidentsUnder(root: String) = runtime.createIncidentQuery().list()
        .filter { history.createHistoricProcessInstanceQuery().processInstanceId(it.processInstanceId).singleResult().rootProcessInstanceId == root }

    /** A chore parked before its first Smith run, with that step's job failed by hand. */
    private fun parkedOnFailedJob(issue: Long): Pair<String, String> {
        val run = runtime.createProcessInstanceByKey("chore").startBeforeActivity("call-do-work")
            .setVariables(mapOf("repo" to "acme/tickets", "issue" to issue)).execute()
        val job = management.createJobQuery().rootProcessInstanceId(run.id).list().first()
        management.setJobRetries(job.id, 0)
        return run.id to job.id
    }

    @Test
    fun `retrying a Smith run that ended failed runs a new Smith, and the flow goes on from there`() {
        sandbox.reset("work" to done(), "security" to passed, "review" to approved)
        sandbox.failNext = 2
        val chore = start(8)
        val named = incidentsUnder(chore.id).first { it.processInstanceId == chore.id }
        assertEquals(2, sandbox.started.size, "the failed run and its one automatic rerun")

        val retried = withGrant { incidents.retry(named.id, RetryRequest(), TestTokens.asLogin("LundMatt1")) }

        assertEquals("smith", retried.action)
        assertEquals(chore.id, retried.rootInstanceId)
        runAllJobs()
        assertEquals(listOf("work", "work", "work", "security", "review"), sandbox.started.map { it.mode })
        assertTrue(history.finished(chore.id), "the chore went on to the end")
        assertTrue(github.comments.any { "started its Smith run again" in it.third })
    }

    @Test
    fun `retrying any other failed job gives it one more try`() {
        val (run, job) = parkedOnFailedJob(9)
        val incident = runtime.createIncidentQuery().list().first { it.configuration == job }

        val retried = withGrant { incidents.retry(incident.id, RetryRequest(), TestTokens.asLogin("lundmatt1")) }

        assertEquals("job", retried.action)
        assertEquals(1, management.createJobQuery().jobId(job).singleResult().retries)
        assertEquals(0, runtime.createIncidentQuery().incidentId(incident.id).count())
        runtime.deleteProcessInstance(run, "test done")
    }

    @Test
    fun `a retry by a login without access is refused, and an unknown incident is a 404`() {
        val (run, job) = parkedOnFailedJob(10)
        val incident = runtime.createIncidentQuery().list().first { it.configuration == job }

        assertEquals(HttpStatus.FORBIDDEN, assertFailsWith<ResponseStatusException> {
            withGrant { incidents.retry(incident.id, RetryRequest(), TestTokens.asLogin("mallory")) } }.statusCode)
        assertEquals(HttpStatus.NOT_FOUND, assertFailsWith<ResponseStatusException> {
            incidents.retry("nope", RetryRequest(), TestTokens.asLogin("lundmatt1")) }.statusCode)
        runtime.deleteProcessInstance(run, "test done")
    }

    @Test
    fun `a push the remote rejects is an incident after one attempt naming the cause and fix, and its retry pushes the stored bundle`() {
        sandbox.reset("work" to done(), "security" to passed, "review" to approved)
        git.failures.addLast(PushRejected.parse(WORKFLOW_REJECTION)!!)
        val chore = start(36)

        assertEquals(listOf("bundle of container-1"), git.attempts, "one attempt, no retries")
        val incident = runtime.createIncidentQuery().processInstanceId(chore.id).singleResult()
        assertEquals("task-open-pr", incident.activityId)
        assertEquals("push rejected: the token lacks the workflow scope for .github/workflows/images.yml; give the repo a token with workflow scope (PUT /repos/{owner}/{name}/token), then retry",
            incident.incidentMessage.lines().first(), "the first line, which the /needs row shows")
        assertEquals(emptyList(), pulls.opened)

        val retried = withGrant { incidents.retry(incident.id, RetryRequest(), TestTokens.asLogin("lundmatt1")) }
        assertEquals("job", retried.action)
        runAllJobs()
        assertEquals(listOf("bundle of container-1"), git.pushes.map { it.third }, "the retry pushed the stored bundle")
        assertEquals(listOf("work", "security", "review"), sandbox.started.map { it.mode }, "no new Smith run")
        assertTrue(history.finished(chore.id))
    }

    @Test
    fun `any other push failure keeps its retries before the incident`() {
        sandbox.reset("work" to done())
        repeat(3) { git.failures.addLast(IllegalStateException("git push failed (128): fatal: unable to access: The requested URL returned error: 502")) }
        val chore = start(37)

        assertEquals(3, git.attempts.size, "the first try and two retries")
        val incident = runtime.createIncidentQuery().processInstanceId(chore.id).singleResult()
        assertEquals("task-open-pr", incident.activityId)
        assertTrue(incident.incidentMessage.startsWith("git push failed (128)"), incident.incidentMessage)
        runtime.deleteProcessInstance(chore.id, "test done")
    }
    @Test
    fun `a transient push failure is retried 30 s apart by the task's cycle, and is the incident after the third try`() {
        val now = java.util.Date(1_800_000_000_000)
        ClockUtil.setCurrentTime(now)
        try {
            sandbox.reset("work" to done())
            repeat(3) { git.failures.addLast(IllegalStateException("git push failed (128): fatal: unable to access: The requested URL returned error: 502")) }
            // Each try after the first sees what the failure before it left on the job: retries and due date.
            val seen = mutableListOf<Pair<Int, java.util.Date?>>()
            git.onAttempt = {
                val job = management.createJobQuery().activityId("task-open-pr").list().single()
                if (job.exceptionMessage != null) seen += job.retries to job.duedate
            }
            val chore = start(38)

            assertEquals(3, git.attempts.size, "the first try and two retries")
            val due = java.util.Date(now.time + 30_000)
            assertEquals<List<Pair<Int, java.util.Date?>>>(listOf(2 to due, 1 to due), seen, "each failure leaves the next try due 30 s later, not at once")
            val incident = runtime.createIncidentQuery().processInstanceId(chore.id).singleResult()
            assertEquals("task-open-pr", incident.activityId)
            runtime.deleteProcessInstance(chore.id, "test done")
        } finally {
            ClockUtil.reset()
        }
    }
}

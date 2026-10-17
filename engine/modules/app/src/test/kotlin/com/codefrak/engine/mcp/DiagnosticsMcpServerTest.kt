package com.codefrak.engine.mcp

import com.codefrak.engine.TestTokens
import com.codefrak.engine.accounts.AiAccounts
import com.codefrak.engine.board.Needs
import com.codefrak.engine.doctor.DoctorVars
import com.codefrak.engine.github.NOT_PLANNED
import com.codefrak.engine.intake.TicketRecorder
import com.codefrak.engine.intake.IntakeProcessTest
import com.codefrak.engine.intake.RefiningSandbox
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.jev.JevContext
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.runAllJobs
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.smith.SmithExitNotifier
import com.codefrak.engine.smith.ensureTestAccount
import com.codefrak.engine.smith.dropTestAccount
import com.codefrak.engine.settings.PublicUrl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RuntimeService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * frakworks-engine-diagnostics over its HTTP endpoint (engine/README.md, "The diagnostics server"): the
 * protocol, and act_on_incident on an intake parked on an incident (its refiner's result broke the rules),
 * on a Smith question, or on nothing (a cancel by ticket), for a Smith the fake sandbox places at an address, and refused for everyone it may not act for.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntakeProcessTest.Fakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class DiagnosticsMcpServerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var publicUrl: PublicUrl
    @Autowired lateinit var runtime: RuntimeService
    @Autowired lateinit var management: ManagementService
    @Autowired lateinit var repository: org.operaton.bpm.engine.RepositoryService
    @Autowired lateinit var exits: SmithExitNotifier
    @Autowired lateinit var accounts: AiAccounts
    @Autowired lateinit var repos: TargetRepos
    @Autowired lateinit var access: Access
    @Autowired lateinit var tokens: com.codefrak.engine.repos.EngineTokens
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var github: TicketRecorder
    @Autowired lateinit var sandbox: RefiningSandbox
    @Autowired lateinit var needs: Needs
    @Autowired lateinit var jevCalls: JevCalls

    private val json = jacksonObjectMapper()
    private val path = "/mcp/${DiagnosticsMcpServer.NAME}"
    private val smith = "172.18.0.7"
    private val elsewhere = "172.18.0.8"

    @BeforeEach
    fun reset() {
        runtime.createProcessInstanceQuery().rootProcessInstances().list().forEach { runtime.deleteProcessInstance(it.id, "test reset") }
        listOf("engine.handled_issue", "engine.deferred_issue", "engine.engine_access", "engine.engine_access_audit", "engine.target_repo",
            "engine.intake_question").forEach { jdbc.sql("DELETE FROM $it").update() }
        repos.add("acme/tickets")
        access.add("lundmatt1", "test")
        accounts.ensureTestAccount()
        sandbox.callers.clear(); sandbox.runAtFails = false
        sandbox.callers[smith] = SmithCaller("run-7", 7, "acme/tickets")
        sandbox.callers[elsewhere] = SmithCaller("run-8", 8, "acme/other")
    }

    @AfterEach
    fun dropTestAccount() { accounts.dropTestAccount() }

    private fun runAllJobs() = management.runAllJobs(exits, rounds = 50)

    /** An intake parked on an incident: its id, its issue, and the incident's id as the needs row gives it. */
    private data class Parked(val intake: String, val issue: Long, val incident: String)

    private fun parked(): Parked {
        val intake = runtime.createMessageCorrelation("ask submitted")
            .setVariables(mapOf("submittedBy" to "lundmatt1", "repo" to "acme/tickets", "ask" to "hello", "fakeOutcome" to "bad")).correlateStartMessage().id
        runAllJobs()
        val issue = (runtime.getVariable(intake, "issue") as Number).toLong()
        val row = needs.list("acme/tickets").single { it.kind == "incident" && it.number == issue }
        return Parked(intake, issue, row.actionId)
    }

    /** An intake started with [vars] and its jobs run: its id and its issue. */
    private fun intake(vars: Map<String, Any>, run: Boolean = true): Pair<String, Long> {
        val intake = runtime.createMessageCorrelation("ask submitted")
            .setVariables(mapOf("submittedBy" to "lundmatt1", "repo" to "acme/tickets", "ask" to "hello") + vars).correlateStartMessage().id
        if (run) runAllJobs()
        return intake to (runtime.getVariable(intake, "issue") as Number).toLong()
    }

    private fun running(id: String) = runtime.createProcessInstanceQuery().processInstanceId(id).count() == 1L

    private fun open(incident: String) = runtime.createIncidentQuery().incidentId(incident).count() == 1L

    /**
     * One request from [from]: with [login]'s token (written untrusted unless trusted already), else [bearer], else the
     * default token, or with no token at all for [token] false.
     */
    private fun rpc(method: String, params: Map<String, Any?> = emptyMap(), from: String = "127.0.0.1", login: String? = null, bearer: String? = null,
                    token: Boolean = true): Map<String, Any?> {
        val res = mvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            header("Accept", "application/json, text/event-stream")
            login?.let { with(TestTokens.bearer(it, trust = false)) }
            if (!token) with(TestTokens.noToken)
            bearer?.let { header("Authorization", "Bearer $it") }
            content = json.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to method, "params" to params))
            with(RequestPostProcessor { it.remoteAddr = from; it })
        }.andExpect { status { isOk() } }.andReturn()
        return json.readValue(res.response.contentAsString)
    }

    /** A tool call's reply: whether it is an error, and its JSON text; a JSON-RPC error (the bearer filter's) as {error, code}. */
    private fun tool(reply: Map<String, Any?>): Pair<Boolean, Map<String, Any?>> {
        (reply["error"] as? Map<*, *>)?.let { return true to mapOf("error" to it["message"], "code" to it["code"]) }
        val result = reply["result"] as Map<*, *>
        val text = ((result["content"] as List<*>).single() as Map<*, *>)["text"] as String
        return (result["isError"] == true) to json.readValue<Map<String, Any?>>(text)
    }

    /** One act_on_incident call: whether it is an error, and its JSON text. */
    private fun act(args: Map<String, Any?>, from: String = smith, login: String? = null, bearer: String? = null, token: Boolean = true): Pair<Boolean, Map<String, Any?>> =
        tool(rpc("tools/call", mapOf("name" to "act_on_incident", "arguments" to args), from, login, bearer, token))

    @Test
    fun `initialize names the server, tools-list is act_on_incident, the three readers and sync_friction_fixes for anyone, and the rest of the protocol answers as MCP says`() {
        val init = rpc("initialize", mapOf("protocolVersion" to "2025-06-18", "capabilities" to emptyMap<String, Any>(),
            "clientInfo" to mapOf("name" to "probe", "version" to "0")))["result"] as Map<*, *>
        assertEquals("frakworks-engine-diagnostics", (init["serverInfo"] as Map<*, *>)["name"])
        assertEquals("2025-06-18", init["protocolVersion"])
        assertEquals(mapOf("tools" to emptyMap<String, Any>()), init["capabilities"])

        val tools = (rpc("tools/list")["result"] as Map<*, *>)["tools"] as List<*>
        assertEquals(listOf("act_on_incident", "read_doctor_history", "read_friction_notes", "read_job_attempts", "read_tier_accounts", "read_review_predictions", "read_jev_calls", "read_public_url", "sync_friction_fixes", "restart_live_parent", "list_jobs", "read_job", "read_job_runs", "register_job", "run_job", "delete_job"), tools.map { (it as Map<*, *>)["name"] })
        val smithTools = (rpc("tools/list", from = smith)["result"] as Map<*, *>)["tools"] as List<*>
        assertEquals(listOf("act_on_incident", "read_doctor_history", "read_friction_notes", "read_job_attempts", "read_tier_accounts", "read_review_predictions", "read_jev_calls", "read_public_url", "sync_friction_fixes", "restart_live_parent", "list_jobs", "read_job", "read_job_runs", "register_job", "run_job", "delete_job"), smithTools.map { (it as Map<*, *>)["name"] })
        val schema = (tools.first() as Map<*, *>)["inputSchema"] as Map<*, *>
        assertEquals(listOf("action", "ticket", "repo", "incident_id", "reason", "rerun_ci"), (schema["properties"] as Map<*, *>).keys.toList())
        assertEquals(listOf("action"), schema["required"])
        assertEquals(false, schema["additionalProperties"])

        for (from in listOf(smith, "127.0.0.1")) {
            val doctor = rpc("tools/call", mapOf("name" to "read_doctor_history", "arguments" to mapOf("ticket" to 4, "repo" to "acme/tickets")), from)["result"] as Map<*, *>
            assertEquals(null, doctor["isError"])
            val text = ((doctor["content"] as List<*>).single() as Map<*, *>)["text"] as String
            assertEquals("acme/tickets", json.readValue<Map<String, Any?>>(text)["repo"])
            val notes = rpc("tools/call", mapOf("name" to "read_friction_notes", "arguments" to mapOf("ticket" to 4)), from)["result"] as Map<*, *>
            assertEquals(null, notes["isError"])
            val notesText = ((notes["content"] as List<*>).single() as Map<*, *>)["text"] as String
            assertTrue("count" in json.readValue<Map<String, Any?>>(notesText))
        }

        assertEquals(-32601, ((rpc("resources/list")["error"]) as Map<*, *>)["code"])
        assertEquals(-32602, ((rpc("tools/call", mapOf("name" to "submit_ask"))["error"]) as Map<*, *>)["code"])
        mvc.post(path) { contentType = MediaType.APPLICATION_JSON; content = """{"jsonrpc":"2.0","method":"notifications/initialized"}""" }
            .andExpect { status { isAccepted() } }
        mvc.get(path).andExpect { status { isMethodNotAllowed() } }
        mvc.post("/mcp/nope") { contentType = MediaType.APPLICATION_JSON; content = """{"jsonrpc":"2.0","id":1,"method":"ping"}""" }
            .andExpect { status { isNotFound() } }
    }

    /** One sync_friction_fixes call: whether it is an error, and its JSON text. */
    private fun syncFixes(from: String, login: String? = null, token: Boolean = true): Pair<Boolean, Map<String, Any?>> =
        tool(rpc("tools/call", mapOf("name" to "sync_friction_fixes", "arguments" to emptyMap<String, Any?>()), from, login, token = token))

    @Test
    fun `read_tier_accounts answers GET tiers accounts' rows, for a Smith and a logged-in caller alike`() {
        val rest: List<Map<String, Any?>> = json.readValue(mvc.get("/tiers/accounts").andExpect { status { isOk() } }.andReturn().response.contentAsString)
        assertEquals((1..10).toList(), rest.map { it["tier"] })
        for ((from, login) in listOf(smith to null, "127.0.0.1" to "lundmatt1")) {
            val result = rpc("tools/call", mapOf("name" to "read_tier_accounts", "arguments" to emptyMap<String, Any?>()), from, login)["result"] as Map<*, *>
            assertEquals(null, result["isError"])
            val text = ((result["content"] as List<*>).single() as Map<*, *>)["text"] as String
            assertEquals(rest, json.readValue<List<Map<String, Any?>>>(text))
        }
        val extra = rpc("tools/call", mapOf("name" to "read_tier_accounts", "arguments" to mapOf("tier" to 1)), smith)["result"] as Map<*, *>
        assertEquals(true, extra["isError"])
    }

    @Test
    fun `read_public_url answers the stored URL, and says plainly when none is set, for a Smith and a logged-in caller alike`() {
        fun read(from: String, login: String?): Map<String, Any?> {
            val result = rpc("tools/call", mapOf("name" to "read_public_url", "arguments" to emptyMap<String, Any?>()), from, login)["result"] as Map<*, *>
            assertEquals(null, result["isError"])
            return json.readValue(((result["content"] as List<*>).single() as Map<*, *>)["text"] as String)
        }
        try {
            publicUrl.set(null)
            for ((from, login) in listOf(smith to null, "127.0.0.1" to "lundmatt1")) {
                val unset = read(from, login)
                assertEquals(null, unset["publicUrl"])
                assertContains(unset["note"] as String, "no public URL is set; set it with PUT /settings/public-url")
            }
            publicUrl.set("https://engine.example.com")
            for ((from, login) in listOf(smith to null, "127.0.0.1" to "lundmatt1"))
                assertEquals(mapOf("publicUrl" to "https://engine.example.com"), read(from, login))
            val extra = rpc("tools/call", mapOf("name" to "read_public_url", "arguments" to mapOf("x" to 1)), smith)["result"] as Map<*, *>
            assertEquals(true, extra["isError"])
        } finally { publicUrl.set(null) }
    }

    /** One read_review_predictions call: whether it is an error, and its JSON text. */
    private fun predictions(args: Map<String, Any?>, from: String = smith, login: String? = null): Pair<Boolean, Any?> {
        val reply = rpc("tools/call", mapOf("name" to "read_review_predictions", "arguments" to args), from, login)
        (reply["error"] as? Map<*, *>)?.let { return true to it["message"] }
        val result = reply["result"] as Map<*, *>
        val text = ((result["content"] as List<*>).single() as Map<*, *>)["text"] as String
        return (result["isError"] == true) to (if (result["isError"] == true) text else json.readValue<Map<String, Any?>>(text))
    }

    /** A review-prediction row on acme/tickets#[issue], called [minutesAgo] minutes ago. */
    private fun prediction(issue: Long, response: String?, minutesAgo: Long, error: String? = null): String {
        val id = jevCalls.record("review-prediction", JevContext(null, "acme/tickets", issue), "test-anthropic", "jev-1", "{}", response, error, 42, null)
        jdbc.sql("UPDATE engine.jev_call SET called_at = :at WHERE id = :id").param("at", java.time.OffsetDateTime.now().minusMinutes(minutesAgo)).param("id", id).update()
        return id
    }

    @Test
    fun `read_review_predictions answers a ticket's review-prediction calls newest first, for a Smith in its own repo and a login that names one`() {
        jdbc.sql("DELETE FROM engine.jev_call WHERE use_case = 'review-prediction'").update()
        val tools = (rpc("tools/list")["result"] as Map<*, *>)["tools"] as List<*>
        val schema = (tools.map { it as Map<*, *> }.single { it["name"] == "read_review_predictions" })["inputSchema"] as Map<*, *>
        assertEquals(listOf("ticket", "repo", "since", "until", "limit"), (schema["properties"] as Map<*, *>).keys.toList())
        assertEquals(listOf("ticket"), schema["required"]); assertEquals(false, schema["additionalProperties"])

        val deny = prediction(901, """{"answers":{"approved":{"type":"noul","noul":0.2}}}""", 30)
        val approve = prediction(901, """{"answers":{"approved":{"type":"noul","noul":0.9,"confidence":0.75}}}""", 20)
        val none = prediction(901, null, 10, error = "no answer: timeout")
        prediction(902, """{"answers":{"approved":{"type":"noul","noul":0.9}}}""", 5)

        for ((from, login) in listOf(smith to null, "127.0.0.1" to "lundmatt1")) {
            val (err, body) = predictions(mapOf("ticket" to 901) + (if (login != null) mapOf("repo" to "acme/tickets") else emptyMap()), from, login)
            assertEquals(false, err, body.toString())
            body as Map<*, *>
            assertEquals("acme/tickets", body["repo"]); assertEquals(901, body["issue"]); assertEquals(3, body["calls"])
            val rows = (body["predictions"] as List<*>).map { it as Map<*, *> }
            assertEquals(listOf(none, approve, deny), rows.map { it["callId"] })
            assertEquals(listOf(null, "approve", "deny"), rows.map { it["verdict"] })
            assertEquals(listOf(null, 0.75, 0.8), rows.map { it["confidence"] })
            assertEquals(listOf(null, 75, -80), rows.map { it["value"] })
            assertEquals(listOf(true, false, false), rows.map { it["noAnswer"] })
            assertEquals(42, rows[1]["latencyMs"]); assertEquals("jev-1", rows[1]["model"])
            assertTrue(rows.none { it.containsKey("request") || it.containsKey("response") || it.containsKey("account") })
        }
        val (_, limited) = predictions(mapOf("ticket" to 901, "limit" to 1))
        assertEquals(3, (limited as Map<*, *>)["calls"]); assertEquals(1, (limited["predictions"] as List<*>).size)

        val (_, window) = predictions(mapOf("ticket" to 901, "since" to java.time.OffsetDateTime.now().minusMinutes(25).toString(),
            "until" to java.time.OffsetDateTime.now().minusMinutes(15).toString()))
        assertEquals(1, (window as Map<*, *>)["calls"], "since and until keep one run's window")
        assertEquals(listOf(approve), (window["predictions"] as List<*>).map { (it as Map<*, *>)["callId"] })

        val (emptyErr, empty) = predictions(mapOf("ticket" to 903))
        assertEquals(false, emptyErr); assertEquals(0, (empty as Map<*, *>)["calls"]); assertEquals(emptyList<Any?>(), empty["predictions"])

        for ((args, named) in listOf(
            mapOf("ticket" to 0) to "ticket", mapOf<String, Any?>() to "ticket", mapOf("ticket" to 901, "repo" to "nope") to "repo",
            mapOf("ticket" to 901, "limit" to 0) to "limit", mapOf("ticket" to 901, "limit" to 501) to "limit",
            mapOf("ticket" to 901, "run" to "x") to "run", mapOf("ticket" to 901, "until" to "soon") to "until",
        )) {
            val (err, text) = predictions(args)
            assertEquals(true, err, args.toString()); assertTrue((text as String).contains(named), text)
        }
        val (loginErr, loginText) = predictions(mapOf("ticket" to 901), "127.0.0.1", "lundmatt1")
        assertEquals(true, loginErr); assertTrue((loginText as String).contains("repo"), loginText)
        sandbox.runAtFails = true
        val (unknownErr, unknownText) = predictions(mapOf("ticket" to 901), smith)
        assertEquals(true, unknownErr); assertTrue((unknownText as String).contains("cannot tell whether the caller is a Smith sandbox"), unknownText)
    }

    /** One read_jev_calls call: whether it is an error, and its JSON text. */
    private fun jevCallsRead(args: Map<String, Any?>, from: String = smith, login: String? = null): Pair<Boolean, Any?> {
        val result = rpc("tools/call", mapOf("name" to "read_jev_calls", "arguments" to args), from, login)["result"] as Map<*, *>
        val text = ((result["content"] as List<*>).single() as Map<*, *>)["text"] as String
        return (result["isError"] == true) to (if (result["isError"] == true) text else json.readValue<Map<String, Any?>>(text))
    }

    @Test
    fun `read_jev_calls counts and lists a ticket's or a flow's Jev calls of any use case, never a body or the account`() {
        jdbc.sql("DELETE FROM engine.jev_call").update()
        val call = { useCase: String, pi: String?, repo: String, issue: Long, minutesAgo: Long ->
            val id = jevCalls.record(useCase, JevContext(pi, repo, issue), "test-anthropic", "jev-1", "{\"secret\":\"req\"}", "{}", null, 7, null)
            jdbc.sql("UPDATE engine.jev_call SET called_at = :at WHERE id = :id")
                .param("at", java.time.OffsetDateTime.now().minusMinutes(minutesAgo)).param("id", id).update()
            id
        }
        val p1 = call("review-prediction", "flow-1", "acme/tickets", 901, 30)
        val p2 = call("review-prediction", "flow-1", "acme/tickets", 901, 20)
        val c1 = call("ci-triage", "flow-2", "acme/tickets", 901, 10)
        call("review-prediction", "flow-3", "acme/tickets", 902, 5)
        call("review-prediction", "flow-4", "acme/other", 901, 5)

        val (err, byTicket) = jevCallsRead(mapOf("ticket" to 901))
        assertEquals(false, err, byTicket.toString()); byTicket as Map<*, *>
        assertEquals(3, byTicket["total"], "a Smith's own repo, the ticket's calls of every use case")
        val rows = (byTicket["calls"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf(c1, p2, p1), rows.map { it["id"] })
        assertEquals(listOf("flow-2", "flow-1", "flow-1"), rows.map { it["processInstance"] })
        assertTrue(rows.none { it.containsKey("request") || it.containsKey("response") || it.containsKey("account") })

        val (_, byFlow) = jevCallsRead(mapOf("process_instance" to "flow-1"))
        assertEquals(2, (byFlow as Map<*, *>)["total"]); assertEquals(listOf(p2, p1), (byFlow["calls"] as List<*>).map { (it as Map<*, *>)["id"] })
        val (_, byUseCase) = jevCallsRead(mapOf("ticket" to 901, "use_case" to "review-prediction", "limit" to 1))
        assertEquals(2, (byUseCase as Map<*, *>)["total"]); assertEquals(1, (byUseCase["calls"] as List<*>).size)
        val (_, window) = jevCallsRead(mapOf("ticket" to 901, "since" to java.time.OffsetDateTime.now().minusMinutes(25).toString(),
            "until" to java.time.OffsetDateTime.now().minusMinutes(5).toString()))
        assertEquals(listOf(c1, p2), ((window as Map<*, *>)["calls"] as List<*>).map { (it as Map<*, *>)["id"] })
        val (_, everyRepo) = jevCallsRead(mapOf("use_case" to "review-prediction"), "127.0.0.1", "lundmatt1")
        assertEquals(4, (everyRepo as Map<*, *>)["total"], "a login with no repo reads every repo")
        val body = rpc("tools/call", mapOf("name" to "read_jev_calls", "arguments" to mapOf("ticket" to 901)), smith).toString()
        assertTrue("secret" !in body && "test-anthropic" !in body, body)

        for ((args, named) in listOf(mapOf("ticket" to 0) to "ticket", mapOf("since" to "yesterday") to "since",
            mapOf("limit" to 501) to "limit", mapOf("run" to "x") to "run")) {
            val (e, text) = jevCallsRead(args)
            assertEquals(true, e, args.toString()); assertTrue((text as String).contains(named), text)
        }
        val (loginErr, loginText) = jevCallsRead(mapOf("ticket" to 901), "127.0.0.1", "lundmatt1")
        assertEquals(true, loginErr); assertTrue((loginText as String).contains("repo"), loginText)
    }

    /** One read_job_attempts call: whether it is an error, and its JSON text. */
    private fun attempts(args: Map<String, Any?>, from: String = smith): Pair<Boolean, Map<String, Any?>> {
        val result = rpc("tools/call", mapOf("name" to "read_job_attempts", "arguments" to args), from)["result"] as Map<*, *>
        val text = ((result["content"] as List<*>).single() as Map<*, *>)["text"] as String
        return (result["isError"] == true) to json.readValue<Map<String, Any?>>(text)
    }

    @Test
    fun `sync_friction_fixes runs one pass for a login and answers its report, and refuses a Smith, an unplaced caller and no login`() {
        val tools = (rpc("tools/list")["result"] as Map<*, *>)["tools"] as List<*>
        val schema = (tools.map { it as Map<*, *> }.single { it["name"] == "sync_friction_fixes" })["inputSchema"] as Map<*, *>
        assertEquals(null, schema["required"])

        val (e1, report) = syncFixes("127.0.0.1", login = "lundmatt1")
        assertEquals(false, e1, report.toString())
        assertEquals(listOf("linked", "changed", "recurred", "skipped"), report.keys.toList())

        val (e2, smithSays) = syncFixes(smith)
        assertTrue(e2); assertTrue("a Smith sandbox (run run-7) may not run the friction fix sync" in smithSays["error"] as String, smithSays.toString())

        val (e3, nobody) = syncFixes("127.0.0.1", token = false)
        assertTrue(e3); assertEquals("no such token", nobody["error"], nobody.toString()); assertEquals(-32001, nobody["code"])

        sandbox.runAtFails = true
        val (e4, down) = syncFixes("10.0.0.5", login = "lundmatt1")
        assertTrue(e4); assertTrue("cannot tell whether the caller is a Smith sandbox" in down["error"] as String, down.toString())
    }

    @Test
    fun `read_job_attempts serves a twice-failed job's attempts in order, by job_id or by its incident, and takes exactly one of them`() {
        val (instance, job) = com.codefrak.engine.camunda.TwiceFailedJob.run(repository, runtime, management)
        val incident = runtime.createIncidentQuery().processInstanceId(instance).singleResult()
        for (args in listOf(mapOf("job_id" to job), mapOf("incident_id" to incident.id))) {
            for (from in listOf(smith, "127.0.0.1")) {
                val (isError, body) = attempts(args, from)
                assertEquals(false, isError, "$args from $from: $body")
                assertEquals(job, body["jobId"])
                @Suppress("UNCHECKED_CAST")
                val rows = body["attempts"] as List<Map<String, Any?>>
                assertEquals(listOf(com.codefrak.engine.camunda.TwiceFailedJob.FIRST, com.codefrak.engine.camunda.TwiceFailedJob.SECOND), rows.map { it["message"] })
                assertTrue(rows.all { it["at"] is String })
            }
        }
        for (bad in listOf(mapOf("job_id" to job, "incident_id" to incident.id), emptyMap(), mapOf("job_id" to job, "ticket" to 4))) {
            val (isError, body) = attempts(bad)
            assertTrue(isError, "$bad is refused")
            assertTrue((body["error"] as String).contains("read_job_attempts takes"), "$bad: $body")
        }
        val custom = runtime.createIncident("custom", instance, "cfg", "not a job")
        assertTrue(attempts(mapOf("incident_id" to custom.id)).first, "an incident that is not a failedJob is an error")
        assertTrue(attempts(mapOf("incident_id" to "no-such-incident")).first, "an incident that is not open is an error")
        assertEquals(emptyList<Any>(), attempts(mapOf("job_id" to "no-such-job")).second["attempts"], "a job that never existed has none")
    }

    @Test
    fun `a Smith in the repo retries an incident by ticket through IncidentRetrier, and the comment names its run`() {
        val p = parked()
        val before = sandbox.started.size
        val (isError, r) = act(mapOf("action" to "retry", "ticket" to p.issue))
        runAllJobs()
        assertEquals(false, isError, r.toString())
        assertEquals(true, r["ok"]); assertEquals("retry", r["action"]); assertEquals(p.incident, r["incident_id"])
        assertEquals("acme/tickets", r["repo"]); assertEquals(p.issue, (r["ticket"] as Number).toLong())
        val engine = r["engine"] as Map<*, *>
        assertEquals("smith", engine["action"], "a rejected result reruns the Smith, as POST /camunda/incidents/{id}/retry does")
        assertEquals(p.intake, engine["rootInstanceId"])
        assertTrue(sandbox.started.size > before, "the refiner was started again")
        val comment = github.comments.last { it.first == "acme/tickets" && it.second == p.issue }.third
        assertTrue(comment.startsWith("Smith run `run-7` working acme/tickets#7 started its Smith run again on this intake"), comment)
    }

    @Test
    fun `a Smith in the repo cancels the flow the incident is on through FlowCanceller, with its reason`() {
        val p = parked()
        val (isError, r) = act(mapOf("action" to "cancel", "incident_id" to p.incident, "reason" to "superseded by #9"))
        assertEquals(false, isError, r.toString())
        assertEquals("cancel", r["action"]); assertEquals(p.intake, r["instanceId"]); assertEquals(emptyList<Any>(), r["failedWrites"])
        assertEquals(0L, runtime.createProcessInstanceQuery().processInstanceId(p.intake).count(), "the intake is gone")
        val comment = github.comments.last { it.first == "acme/tickets" && it.second == p.issue }.third
        assertTrue(comment.startsWith("Smith run `run-7` working acme/tickets#7 cancelled this intake (`${p.intake}`): superseded by #9"), comment)
        assertEquals(NOT_PLANNED, github.closedAs["acme/tickets" to p.issue])
    }

    @Test
    fun `a Smith in another repo, a caller that is no Smith, and an unreachable Docker are refused, and the incident stays open`() {
        val p = parked()
        val before = sandbox.started.size

        val (e1, other) = act(mapOf("action" to "retry", "incident_id" to p.incident), from = elsewhere)
        assertTrue(e1); assertTrue("may retry only flows in its own repo (acme/other)" in other["error"] as String, other.toString())
        assertEquals(403, other["status"])
        val (e2, cancel) = act(mapOf("action" to "cancel", "ticket" to p.issue, "repo" to "acme/tickets", "reason" to "x"), from = elsewhere)
        assertTrue(e2); assertTrue("may cancel only flows in its own repo" in cancel["error"] as String, cancel.toString())

        val (e3, human) = act(mapOf("action" to "retry", "ticket" to p.issue), from = "127.0.0.1", token = false)
        assertTrue(e3); assertEquals("no such token", human["error"], human.toString())

        sandbox.runAtFails = true
        val (e4, down) = act(mapOf("action" to "retry", "ticket" to p.issue))
        assertTrue(e4); assertTrue("cannot tell whether the caller is a Smith sandbox" in down["error"] as String, down.toString())
        sandbox.runAtFails = false

        assertTrue(open(p.incident)); assertEquals(before, sandbox.started.size)
        assertEquals(1L, runtime.createProcessInstanceQuery().processInstanceId(p.intake).count())
    }

    @Test
    fun `a Smith may not act on the flow it runs under, nor cancel without a reason, nor pass what the tool does not take`() {
        val p = parked()
        // The Smith whose run is the parked smith-run itself: its root flow is the intake.
        val run = runtime.createIncidentQuery().incidentId(p.incident).singleResult().processInstanceId
        sandbox.callers[smith] = SmithCaller(run, p.issue, "acme/tickets")
        val (e1, own) = act(mapOf("action" to "retry", "incident_id" to p.incident))
        assertTrue(e1); assertTrue("may not retry the intake it runs under" in own["error"] as String, own.toString())
        val (e2, ownCancel) = act(mapOf("action" to "cancel", "incident_id" to p.incident, "reason" to "x"))
        assertTrue(e2); assertTrue("may not cancel the intake it runs under" in ownCancel["error"] as String, ownCancel.toString())

        sandbox.callers[smith] = SmithCaller("run-7", 7, "acme/tickets")
        listOf(
            mapOf("action" to "cancel", "ticket" to p.issue),
            mapOf("action" to "cancel", "ticket" to p.issue, "reason" to "  "),
            mapOf("action" to "retry", "ticket" to p.issue, "reason" to "x"),
            mapOf("action" to "retire", "ticket" to p.issue),
            mapOf("action" to "retry"),
            mapOf("action" to "retry", "ticket" to p.issue, "incident_id" to p.incident),
            mapOf("action" to "retry", "incident_id" to p.incident, "repo" to "acme/tickets"),
            mapOf("action" to "retry", "ticket" to "7"),
            mapOf("action" to "retry", "ticket" to 0),
            mapOf("action" to "retry", "ticket" to p.issue, "repo" to "nope"),
            mapOf("action" to "retry", "ticket" to p.issue, "force" to true),
            mapOf("action" to "retry", "ticket" to p.issue, "rerun_ci" to "yes"),
            mapOf("action" to "cancel", "ticket" to p.issue, "reason" to "x", "rerun_ci" to true),
            // The parked incident is a refused refine, not a declined engine check: rerun_ci is refused and nothing re-runs.
            mapOf("action" to "retry", "ticket" to p.issue, "rerun_ci" to true),
        ).forEach { args ->
            val (isError, r) = act(args)
            assertTrue(isError, "$args: $r")
        }
        assertTrue(open(p.incident))
        assertEquals(1L, runtime.createProcessInstanceQuery().processInstanceId(p.intake).count())
    }

    @Test
    fun `a cancel by ticket takes the ticket's running flow with no incident and no needs row`() {
        // Its jobs not run: the intake is running, parked on no incident, no question and no authorization.
        val (intake, issue) = intake(emptyMap(), run = false)
        assertTrue(needs.list("acme/tickets").none { it.number == issue }, needs.list("acme/tickets").toString())
        val (isError, r) = act(mapOf("action" to "cancel", "ticket" to issue, "reason" to "not wanted"))
        assertEquals(false, isError, r.toString())
        assertEquals(true, r["ok"]); assertEquals("cancel", r["action"]); assertEquals(intake, r["instanceId"])
        assertEquals(issue, (r["ticket"] as Number).toLong()); assertEquals("acme/tickets", r["repo"])
        assertEquals(emptyList<Any>(), r["failedWrites"]); assertTrue("incident_id" !in r, r.toString())
        assertTrue(!running(intake), "the intake is gone")
        val comment = github.comments.last { it.first == "acme/tickets" && it.second == issue }.third
        assertTrue(comment.startsWith("Smith run `run-7` working acme/tickets#7 cancelled this intake (`$intake`): not wanted"), comment)
        assertEquals(NOT_PLANNED, github.closedAs["acme/tickets" to issue])
    }

    @Test
    fun `a cancel by ticket takes a flow parked on a Smith question, and a retry there finds no incident`() {
        val (intake, issue) = intake(mapOf("fakeOutcome" to "ask"))
        val rows = needs.list("acme/tickets").filter { it.number == issue }
        assertTrue(rows.none { it.kind == "incident" }, rows.toString())

        val before = sandbox.started.size
        val (e1, retry) = act(mapOf("action" to "retry", "ticket" to issue))
        assertTrue(e1); assertEquals("no open incident found for ticket #$issue in acme/tickets", retry["error"], retry.toString())
        assertEquals(before, sandbox.started.size); assertTrue(running(intake))

        val (isError, r) = act(mapOf("action" to "cancel", "ticket" to issue, "reason" to "asked elsewhere"))
        assertEquals(false, isError, r.toString())
        assertEquals(intake, r["instanceId"]); assertEquals(emptyList<Any>(), r["failedWrites"])
        assertTrue(!running(intake), "the intake is gone")
        assertEquals(NOT_PLANNED, github.closedAs["acme/tickets" to issue])
    }

    @Test
    fun `a cancel by ticket on a flow with an incident cancels its root flow and names the incident`() {
        val p = parked()
        val (isError, r) = act(mapOf("action" to "cancel", "ticket" to p.issue, "reason" to "superseded by #9"))
        assertEquals(false, isError, r.toString())
        assertEquals(p.incident, r["incident_id"]); assertEquals(p.intake, r["instanceId"])
        assertTrue(!running(p.intake), "the intake is gone")
        assertEquals(NOT_PLANNED, github.closedAs["acme/tickets" to p.issue])
    }

    @Test
    fun `a cancel by ticket with no running flow, or with several, acts on nothing`() {
        val comments = github.comments.size
        val (e1, none) = act(mapOf("action" to "cancel", "ticket" to 4242, "reason" to "x"))
        assertTrue(e1)
        val text = none["error"] as String
        assertTrue("no flow is running for acme/tickets#4242" in text && "close_ticket" in text, none.toString())
        assertEquals(comments, github.comments.size); assertTrue(("acme/tickets" to 4242L) !in github.closedAs)

        val (first, issue) = intake(emptyMap(), run = false)
        val (second, _) = intake(emptyMap(), run = false)
        runtime.setVariable(second, "issue", issue)
        val before = github.comments.size
        val (e2, several) = act(mapOf("action" to "cancel", "ticket" to issue, "reason" to "x"))
        assertTrue(e2)
        val error = several["error"] as String
        assertTrue(first in error && second in error, several.toString())
        assertEquals(setOf(first, second), (several["matches"] as List<*>).map { (it as Map<*, *>)["instanceId"] }.toSet())
        assertTrue(running(first) && running(second)); assertEquals(before, github.comments.size)
    }

    @Test
    fun `a Smith may not cancel by ticket the flow it runs under, incident or not`() {
        val (intake, issue) = intake(emptyMap(), run = false)
        sandbox.callers[smith] = SmithCaller(intake, issue, "acme/tickets")
        val (isError, r) = act(mapOf("action" to "cancel", "ticket" to issue, "reason" to "x"))
        assertTrue(isError); assertTrue("may not cancel the intake it runs under" in r["error"] as String, r.toString())
        assertEquals(403, r["status"]); assertTrue(running(intake))
        assertTrue(("acme/tickets" to issue) !in github.closedAs)
    }

    @Test
    fun `a login a bearer token proved retries by ticket as that login, with no header, and the comment names it`() {
        jdbc.sql("DELETE FROM engine.engine_token").update()
        val minted = tokens.mint("lundmatt1", "diagnostics test", null, "test")
        val p = parked()
        val (isError, r) = act(mapOf("action" to "retry", "ticket" to p.issue, "repo" to "acme/tickets"), from = "127.0.0.1", bearer = minted.token)
        runAllJobs()
        assertEquals(false, isError, r.toString())
        assertEquals("retry", r["action"]); assertEquals(p.incident, r["incident_id"])
        val comment = github.comments.last { it.first == "acme/tickets" && it.second == p.issue }.third
        assertTrue(comment.startsWith("@lundmatt1 started its Smith run again on this intake"), comment)
        tokens.revoke(minted.row.id)
        val refused = mvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer ${minted.token}")
            content = json.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to "tools/list"))
        }.andReturn()
        assertEquals(200, refused.response.status)
        assertContains(refused.response.contentAsString, "\"code\":-32001", message = "a revoked token is refused by the filter before the server sees the call")
        assertContains(refused.response.contentAsString, "no such token")
    }

    @Test
    fun `a login caller (the first mate) retries by ticket and repo as its login, and the comment names it`() {
        val p = parked()
        val before = sandbox.started.size
        val (isError, r) = act(mapOf("action" to "retry", "ticket" to p.issue, "repo" to "acme/tickets"), from = "127.0.0.1", login = "lundmatt1")
        runAllJobs()
        assertEquals(false, isError, r.toString())
        assertEquals("retry", r["action"]); assertEquals(p.incident, r["incident_id"])
        assertEquals("smith", (r["engine"] as Map<*, *>)["action"])
        assertTrue(sandbox.started.size > before, "the refiner was started again")
        val comment = github.comments.last { it.first == "acme/tickets" && it.second == p.issue }.third
        assertTrue(comment.startsWith("@lundmatt1 started its Smith run again on this intake"), comment)
    }

    @Test
    fun `a login caller cancels by incident id as its login, with its reason`() {
        val p = parked()
        val (isError, r) = act(mapOf("action" to "cancel", "incident_id" to p.incident, "reason" to "not wanted"), from = "127.0.0.1", login = "lundmatt1")
        assertEquals(false, isError, r.toString())
        assertEquals(p.intake, r["instanceId"]); assertTrue(!running(p.intake), "the intake is gone")
        val comment = github.comments.last { it.first == "acme/tickets" && it.second == p.issue }.third
        assertTrue(comment.startsWith("@lundmatt1 cancelled this intake (`${p.intake}`): not wanted"), comment)
        assertEquals(NOT_PLANNED, github.closedAs["acme/tickets" to p.issue])
    }

    /** A doctor run on [p]'s intake whose health check job failed: its id and its incident's. */
    private fun doctorOn(p: Parked): Pair<String, String> {
        val doctor = runtime.startProcessInstanceByKey("doctor", mapOf(DoctorVars.REPO to "acme/tickets", DoctorVars.ISSUE to p.issue,
            DoctorVars.ROOT to p.intake, DoctorVars.POSITION to "intake:task-read-result@none"))
        management.setJobRetries(management.createJobQuery().processInstanceId(doctor.id).singleResult().id, 0)
        return doctor.id to runtime.createIncidentQuery().processInstanceId(doctor.id).singleResult().id
    }

    @Test
    fun `a Smith in the ticket's repo retries a doctor run's incident by incident id, as its ticket's`() {
        val p = parked()
        val (doctor, incident) = doctorOn(p)
        val (isError, r) = act(mapOf("action" to "retry", "incident_id" to incident))
        assertEquals(false, isError, r.toString())
        assertEquals("acme/tickets", r["repo"]); assertEquals(p.issue, (r["ticket"] as Number).toLong())
        assertEquals(doctor, (r["engine"] as Map<*, *>)["rootInstanceId"])
        assertTrue(!open(incident), "the doctor run's job has a try again")
        assertTrue(open(p.incident), "the intake's own incident is left")
        val comment = github.comments.last { it.first == "acme/tickets" && it.second == p.issue }.third
        assertTrue(comment.startsWith("Smith run `run-7` working acme/tickets#7 retried `call-health-check` on the doctor run (`$doctor`) on this intake."), comment)
    }

    @Test
    fun `a login cancels a doctor run by incident id and the doctor run ends alone, the ticket stays open and running`() {
        val p = parked()
        val (doctor, incident) = doctorOn(p)
        val labels = github.labelled.size
        val (isError, r) = act(mapOf("action" to "cancel", "incident_id" to incident, "reason" to "not needed"), from = "127.0.0.1", login = "lundmatt1")
        assertEquals(false, isError, r.toString())
        assertEquals(doctor, r["instanceId"]); assertEquals(emptyList<Any>(), r["failedWrites"])
        assertTrue(!running(doctor), "the doctor run is gone")
        assertTrue(running(p.intake), "the intake still runs"); assertTrue(open(p.incident))
        assertTrue(("acme/tickets" to p.issue) !in github.closedAs, "the ticket is not closed")
        assertEquals(labels, github.labelled.size, "no label is written")
        val comment = github.comments.last { it.first == "acme/tickets" && it.second == p.issue }.third
        assertTrue(comment.startsWith("@lundmatt1 cancelled the doctor run (`$doctor`) on this intake: not needed"), comment)
    }

    @Test
    fun `a login without the ticket's kind on its repo may not act on its doctor run`() {
        val p = parked()
        val (doctor, incident) = doctorOn(p)
        val (e, denied) = act(mapOf("action" to "cancel", "incident_id" to incident, "reason" to "x"), from = "127.0.0.1", login = "mallory")
        assertTrue(e); assertEquals("@mallory is not a trusted login", denied["error"], denied.toString())
        assertTrue(running(doctor)); assertTrue(open(incident))
    }

    @Test
    fun `an untrusted login, a login with a ticket and no repo, and a blank login are refused, and the incident stays open`() {
        val p = parked()
        val before = sandbox.started.size

        val (e1, denied) = act(mapOf("action" to "retry", "incident_id" to p.incident), from = "127.0.0.1", login = "mallory")
        assertTrue(e1); assertEquals("@mallory is not a trusted login", denied["error"], denied.toString())
        assertEquals(-32001, denied["code"], "refused by the bearer filter before the tool runs")
        val (e2, cancel) = act(mapOf("action" to "cancel", "ticket" to p.issue, "repo" to "acme/tickets", "reason" to "x"),
            from = "127.0.0.1", login = "mallory")
        assertTrue(e2); assertEquals("@mallory is not a trusted login", cancel["error"], cancel.toString())

        val (e3, noRepo) = act(mapOf("action" to "retry", "ticket" to p.issue), from = "127.0.0.1", login = "lundmatt1")
        assertTrue(e3); assertEquals("no repo to look for ticket #${p.issue} in: pass \"repo\"", noRepo["error"], noRepo.toString())

        val (e4, none) = act(mapOf("action" to "retry", "incident_id" to p.incident), from = "127.0.0.1", token = false)
        assertTrue(e4); assertEquals("no such token", none["error"], none.toString())

        assertTrue(open(p.incident)); assertEquals(before, sandbox.started.size)
        assertTrue(running(p.intake)); assertTrue(("acme/tickets" to p.issue) !in github.closedAs)
    }
}

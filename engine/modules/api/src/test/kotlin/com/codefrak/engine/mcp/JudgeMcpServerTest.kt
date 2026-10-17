package com.codefrak.engine.mcp

import com.codefrak.engine.BearerTokenFilter
import com.codefrak.engine.TOKEN_LOGIN_ATTRIBUTE
import com.codefrak.engine.hitlp.HitlpHandle
import com.codefrak.engine.hitlp.HitlpRequest
import com.codefrak.engine.hitlp.HitlpRequester
import com.codefrak.engine.hitlp.HitlpRequests
import com.codefrak.engine.hitlp.HitlpSchemas
import com.codefrak.engine.hitlp.JudgeEscalation
import com.codefrak.engine.jev.JudgeRequest
import com.codefrak.engine.jev.Judgment
import com.codefrak.engine.jev.SshJudge
import com.codefrak.engine.smith.SandboxStatus
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.smith.SmithSandbox
import com.codefrak.engine.smith.SmithSpec
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `/mcp/judge` over [EngineMcpController]: its one tool's schema, a call's shape, the `human.approve` an escalation
 * files through [HitlpRequester] and the handle row it stores, the `tasks/get` / `tasks/cancel` proxy for the caller's
 * own escalations and `-32001` for any other task, and the bearer refusal any other task server gets.
 */
class JudgeMcpServerTest {
    private val sandbox = object : SmithSandbox {
        override fun start(spec: SmithSpec): String = error("unused")
        override fun status(handle: String): SandboxStatus = error("unused")
        override fun logs(handle: String, maxLines: Int): String = ""
        override fun output(handle: String, path: String): ByteArray? = null
        override fun remove(handle: String) {}
        override fun runAt(address: String): SmithCaller? = null
    }

    /** Another task server at `/mcp/other`: the refusal is the controller's, before any server is reached. */
    private val other = object : TaskServer {
        override val name = "other"; override val version = "1"
        override fun tools() = listOf(McpTool("human.ask", "ask", mapOf("type" to "object")))
        override fun callTask(tool: String, args: Map<String, Any?>, caller: McpCaller): TaskCall = TaskCall.Ok(emptyMap())
        override fun getTask(taskId: String, caller: McpCaller): TaskCall = TaskCall.Ok(emptyMap())
        override fun listTasks(caller: McpCaller, cursor: String?): TaskCall = TaskCall.Ok(emptyMap())
        override fun cancelTask(taskId: String, caller: McpCaller): TaskCall = TaskCall.Ok(emptyMap())
    }

    private var seen: Pair<JudgeRequest, String>? = null
    private var verdict = "escalate"
    private val judge: SshJudge = mock(SshJudge::class.java) { inv ->
        if (inv.method.name == "judge") {
            seen = inv.getArgument<JudgeRequest>(0) to inv.getArgument<String>(1)
            Judgment("call-1", false, verdict, 0.9, "Restarting a service.", "Verdict escalate ...")
        } else null
    }

    /** What the HITLP server was sent: (method, tool or task id, arguments). */
    private val filed = mutableListOf<Triple<String, String, Map<String, Any?>>>()
    private fun handle(taskId: String, status: String = "input_required") = HitlpHandle(taskId, status, 2500, 86_400_000, "waiting",
        mapOf("io.hitlp/decisionUrl" to "https://hitlp.example/decide/$taskId"), null)
    private val requester: HitlpRequester = mock(HitlpRequester::class.java) { inv ->
        when (inv.method.name) {
            "call" -> { filed += Triple("call", inv.getArgument(0), inv.getArgument(1)); handle("t-9") }
            "get" -> { filed += Triple("get", inv.getArgument(0), emptyMap()); handle(inv.getArgument(0)) }
            "cancel" -> { filed += Triple("cancel", inv.getArgument(0), emptyMap()); handle(inv.getArgument(0), "cancelled") }
            else -> null
        }
    }
    private val rows = mutableMapOf<String, HitlpRequest>()
    private val jdbc = JdbcClient.create(SimpleDriverDataSource())
    private val requests = object : HitlpRequests(jdbc) {
        override fun save(request: HitlpRequest) = request.also { rows[it.taskId] = it }
        override fun get(taskId: String) = rows[taskId]
    }

    private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(EngineMcpController(listOf(object : JudgeMcpServer(judge, requester, requests, jdbc) {
        override fun targetRepos() = listOf("acme/ops", "acme/web")
    }, other), sandbox)).build()

    private fun rpc(server: String, body: String, refusal: String? = null, login: String? = null): ResultActionsDsl = mvc.post("/mcp/$server") {
        contentType = MediaType.APPLICATION_JSON; accept = MediaType.APPLICATION_JSON; content = body
        with(RequestPostProcessor {
            it.remoteAddr = "127.0.0.1"; refusal?.let { r -> it.setAttribute(BearerTokenFilter.REFUSAL_ATTRIBUTE, r) }
            login?.let { l -> it.setAttribute(TOKEN_LOGIN_ATTRIBUTE, l) }; it
        })
    }

    private fun answer(body: String): Map<String, Any?> {
        val result = jacksonObjectMapper().readValue<Map<String, Any?>>(body)["result"] as Map<*, *>
        return jacksonObjectMapper().readValue(((result["content"] as List<*>).first() as Map<*, *>)["text"] as String)
    }

    private fun command(login: String? = "ops", extra: String = "") = answer(rpc("judge",
        """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"judge.command","arguments":${args.trimEnd('}')}$extra}}}""",
        login = login).andReturn().response.contentAsString)

    private fun taskRpc(method: String, taskId: String, login: String?): Map<String, Any?> = jacksonObjectMapper().readValue(
        rpc("judge", """{"jsonrpc":"2.0","id":5,"method":"$method","params":{"taskId":"$taskId"}}""", login = login).andReturn().response.contentAsString)

    private val args = """{"rubric":"## Escalate\n- Restarting a service.","command":"systemctl restart nginx","why":"reload",
        "ticketGoal":"g","priorCommands":[],"files":[],"host":"nas","requester":"run-1","repo":"acme/ops"}"""

    @Test
    fun `tools-list answers one tool judge-command requiring the eight fields`() {
        val body = rpc("judge", """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        val tools = (jacksonObjectMapper().readValue<Map<String, Any?>>(body)["result"] as Map<*, *>)["tools"] as List<*>
        assertEquals(1, tools.size)
        val tool = tools.first() as Map<*, *>
        assertEquals("judge.command", tool["name"])
        assertEquals(listOf("rubric", "command", "why", "ticketGoal", "priorCommands", "files", "host", "requester"),
            (tool["inputSchema"] as Map<*, *>)["required"])
    }

    @Test
    fun `a call answers verdict, confidence, citedPassage, reasoning and callId, filed under the named repo`() {
        verdict = "allow"
        val answer = command()
        assertEquals(setOf("verdict", "confidence", "citedPassage", "reasoning", "callId"), answer.keys)
        assertEquals("allow", answer["verdict"])
        assertEquals("acme/ops", seen?.second)
        assertEquals("systemctl restart nginx", seen?.first?.command)
        assertEquals(emptyList(), filed, "allow files nothing")
        verdict = "deny"
        command()
        assertEquals(emptyList(), filed, "deny files nothing")
    }

    @Test
    fun `an escalation is filed as a human approve for the caller and its handle stored with the judge call id`() {
        val answer = command(extra = ""","rubricPath":"ops/rubric/ssh.md"""")
        assertEquals("escalate", answer["verdict"])
        assertEquals("t-9", answer["taskId"])
        assertEquals("https://hitlp.example/decide/t-9", answer["decisionUrl"])
        assertEquals(2500, answer["pollInterval"])
        val (method, tool, request) = filed.single()
        assertEquals("call" to "human.approve", method to tool)
        assertNull(HitlpSchemas.requesterProblem("approve", request), "the request fits approve.request and sends no requester")
        assertEquals(false, request["requiresHuman"])
        assertEquals("urgent", request["priority"], "a command an agent waits on interrupts")
        assertNull(request["requester"])
        val asserted = request["asserted"] as Map<*, *>
        assertEquals(mapOf("kind" to "person", "id" to "ops", "login" to "ops"), asserted["onBehalfOf"])
        val recommendation = asserted["recommendation"] as Map<*, *>
        assertEquals("escalate", recommendation["verdict"])
        assertEquals(0.9, recommendation["confidence"])
        assertEquals(mapOf("judge" to "ssh-judge", "rubric" to "ops/rubric/ssh.md"), recommendation["by"])
        assertEquals(listOf("Restarting a service.", "Verdict escalate ..."), recommendation["reasons"])
        assertEquals(mapOf("callId" to "call-1", "rubricPath" to "ops/rubric/ssh.md", "repo" to "acme/ops"),
            ((request["context"] as Map<*, *>)["data"] as Map<*, *>)["judge"])
        val row = rows.getValue("t-9")
        assertEquals("call-1", row.judgeCallId)
        assertEquals(JudgeEscalation.KIND, row.kind)
        assertEquals(JudgeEscalation.loginOwner("ops"), row.userTaskKey)
    }

    @Test
    fun `tasks get and cancel are proxied for the caller's own escalation, any other task is -32001`() {
        command()
        val got = taskRpc("tasks/get", "t-9", "ops")
        assertEquals("input_required", (got["result"] as Map<*, *>)["status"])
        val cancelled = taskRpc("tasks/cancel", "t-9", "OPS")
        assertEquals("cancelled", (cancelled["result"] as Map<*, *>)["status"])
        assertEquals(listOf("get" to "t-9", "cancel" to "t-9"), filed.drop(1).map { it.first to it.second })
        for ((taskId, login) in listOf("t-9" to "someone-else", "t-9" to null, "t-unknown" to "ops")) {
            val reply = taskRpc("tasks/get", taskId, login)
            assertEquals(-32001, (reply["error"] as Map<*, *>)["code"], "$taskId for $login")
            assertEquals(-32001, (taskRpc("tasks/cancel", taskId, login)["error"] as Map<*, *>)["code"])
        }
        assertEquals(3, filed.size, "a refused task never reaches the HITLP server")
    }

    @Test
    fun `a missing field is an error and Jev is not asked`() {
        rpc("judge", """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"judge.command","arguments":{"command":"ls"}}}""")
            .andExpect { jsonPath("$.result.isError") { value(true) } }
        assertEquals(null, seen)
    }

    @Test
    fun `a call with no Smith caller and no repo is refused naming the target repos, and Jev is not asked`() {
        val noRepo = args.replace(""","repo":"acme/ops"""", "")
        rpc("judge", """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"judge.command","arguments":$noRepo}}""", login = "ops")
            .andExpect { jsonPath("$.result.isError") { value(true) }
                jsonPath("$.result.content[0].text") { value(org.hamcrest.Matchers.containsString("the target repos: acme/ops, acme/web")) } }
        assertEquals(null, seen)
    }

    @Test
    fun `no or bad bearer token gets what any task server gives`() {
        for (refusal in listOf("no bearer token", "bad bearer token")) {
            val responses = listOf("judge", "other").map { server ->
                rpc(server, """{"jsonrpc":"2.0","id":4,"method":"tools/list"}""", refusal).andReturn().response }
            assertEquals(responses[0].status, responses[1].status)
            assertEquals(responses[0].contentAsString, responses[1].contentAsString)
            rpc("judge", """{"jsonrpc":"2.0","id":4,"method":"tools/list"}""", refusal).andExpect {
                jsonPath("$.error.code") { value(BearerTokenFilter.MCP_UNAUTHORIZED) } }
        }
    }
}

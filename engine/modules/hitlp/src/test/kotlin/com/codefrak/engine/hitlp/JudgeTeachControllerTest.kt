package com.codefrak.engine.hitlp

import com.codefrak.engine.TOKEN_ID_ATTRIBUTE
import com.codefrak.engine.TOKEN_LOGIN_ATTRIBUTE
import com.codefrak.engine.engineJson
import com.codefrak.engine.github.RepoContents
import com.codefrak.engine.github.RepoFile
import com.codefrak.engine.repos.EngineToken
import com.codefrak.engine.repos.EngineTokens
import com.codefrak.engine.smith.FakeSandbox
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.smith.SmithSandbox
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.server.ResponseStatusException
import tools.jackson.module.kotlin.readValue
import java.net.InetSocketAddress
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `POST /judge/teach` with in-memory stores and a stub HITLP server answering `tasks/get`: only a first-mate token
 * teaches, a Smith sandbox never does (and no commit is made for it), the task must be a decided judge escalation the
 * engine filed for a login (found by task id or by Jev call id), its recommendation read from `asserted.recommendation`
 * or `context.data.judge`, and a missing rubric or heading commits nothing.
 */
class JudgeTeachControllerTest {
    private val now = OffsetDateTime.now()
    private val jdbc = JdbcClient.create(SimpleDriverDataSource())
    /** The engine.hitlp_request rows by task id. */
    private val rows = mutableMapOf<String, HitlpRequest>()
    /** What the stub's tasks/get answers, by task id: the Task object. */
    private val served = mutableMapOf<String, Map<String, Any?>>()
    private val gets = mutableListOf<String>()
    private val tokenRows = mapOf(
        "fm" to token("fm", EngineTokens.FIRST_MATE), "plain" to token("plain", null), "pod" to token("pod", EngineTokens.FIRST_MATE_POD))
    private val requests = object : HitlpRequests(jdbc) {
        override fun get(taskId: String) = rows[taskId]
        override fun byJudgeCall(callId: String) = rows.values.lastOrNull { it.judgeCallId == callId }
    }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { createContext("/mcp") { answer(it) }; start() }
    private val requester = HitlpRequester({ HitlpEndpoint("http://127.0.0.1:${server.address.port}/mcp", "t") }, Duration.ofSeconds(5))

    @AfterEach
    fun stop() = server.stop(0)

    private fun answer(x: HttpExchange) {
        val body: Map<String, Any?> = engineJson.readValue(x.requestBody.readBytes())
        val method = body["method"].toString()
        val result: Any? = when (method) {
            "initialize" -> mapOf("protocolVersion" to HitlpRequester.PROTOCOL_VERSION, "capabilities" to emptyMap<String, Any?>())
            "notifications/initialized" -> { x.sendResponseHeaders(202, -1); x.responseBody.close(); return }
            "tasks/get" -> { val id = (body["params"] as Map<*, *>)["taskId"].toString(); gets += id; served[id] }
            else -> null
        }
        val bytes = engineJson.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to body["id"], "result" to result)).toByteArray()
        x.responseHeaders.add("Content-Type", "application/json")
        x.sendResponseHeaders(200, bytes.size.toLong())
        x.responseBody.use { it.write(bytes) }
    }

    private val tokens = object : EngineTokens {
        override fun get(id: String) = tokenRows[id]
        override fun mint(login: String, name: String, expiresInDays: Int?, createdBy: String?, purpose: String?, sessionId: String?) = TODO()
        override fun resolve(token: String) = TODO()
        override fun touch(id: String) = TODO()
        override fun list() = tokenRows.values.toList()
        override fun revoke(id: String) = TODO()
        override fun revokeForSession(sessionId: String) = TODO()
        override fun anyActive() = tokenRows.isNotEmpty()
    }
    private val smithAddress = "10.42.0.9"
    private val sandbox = object : SmithSandbox by FakeSandbox() {
        override fun runAt(address: String) = if (address == smithAddress) SmithCaller("run-1", 7, "o/r") else null
    }
    private var rubric: RepoFile? = RepoFile("# SSH\n\n## Decisions\n\n## After\n", "b10b")
    private val puts = mutableListOf<List<String>>()
    private val contents = object : RepoContents {
        override fun file(repo: String, path: String) =
            if (repo == "x/untokened") throw IllegalStateException("no GitHub token for $repo") else rubric
        override fun putFile(repo: String, path: String, text: String, sha: String, message: String, authorLogin: String): String {
            puts += listOf(repo, path, text, sha, authorLogin); return "c0mm1t"
        }
    }
    private val controller = JudgeTeachController(requests, requester, tokens, contents, sandbox, { })

    private fun token(id: String, purpose: String?) =
        EngineToken(id, "alice", id, "abcdefgh", now, null, null, null, null, purpose = purpose)

    /**
     * Files escalation `t1` of call `j-7` for [owner] and has the stub serve it decided [outcome] (open when null).
     * [asserted] puts the verdict in `asserted.recommendation`; false gives the older `context.data.judge` shape.
     */
    private fun task(outcome: String? = "approved", judge: Boolean = true, owner: String = JudgeEscalation.loginOwner("ops"),
        repo: String = "acme/ops", path: String = "ops/rubric/ssh.md", asserted: Boolean = true) {
        rows["t1"] = HitlpRequest("t1", "judge:j-7", JudgeEscalation.processInstance("j-7"), owner,
            if (judge) JudgeEscalation.KIND else "MERGE", 10, now, now.plusHours(1), judgeCallId = if (judge) "j-7" else null)
        val data = if (asserted) mapOf("callId" to "j-7", "rubricPath" to path, "repo" to repo)
            else mapOf("callId" to "j-7", "verdict" to "escalate", "confidence" to 0.5, "rubricPath" to path, "repo" to repo)
        val request = mapOf("action" to "ssh.exec", "payload" to mapOf("command" to "uptime", "host" to "web-01", "why" to "health check"),
            "context" to mapOf("data" to mapOf("judge" to data))) +
            (if (asserted) mapOf("asserted" to mapOf("recommendation" to mapOf("verdict" to "escalate", "confidence" to 0.5,
                "by" to mapOf("judge" to "ssh-judge", "rubric" to path)))) else emptyMap())
        served["t1"] = if (outcome == null) mapOf("taskId" to "t1", "status" to "input_required", "pollInterval" to 10)
            else mapOf("taskId" to "t1", "status" to HitlpPrimitives.COMPLETED, "pollInterval" to 10, "result" to mapOf("structuredContent" to
                mapOf("requestId" to "t1", "primitive" to "approve", "outcome" to outcome, "request" to request)))
    }

    private fun request(tokenId: String?, address: String = "127.0.0.1") = MockHttpServletRequest().apply {
        remoteAddr = address
        if (tokenId != null) { setAttribute(TOKEN_LOGIN_ATTRIBUTE, "alice"); setAttribute(TOKEN_ID_ATTRIBUTE, tokenId) }
    }

    private fun refused(status: HttpStatus, body: TeachRequest, request: MockHttpServletRequest) {
        val e = assertFailsWith<ResponseStatusException> { controller.teach(body, request) }
        assertEquals(status.value(), e.statusCode.value(), e.reason)
    }

    @Test
    fun `a first-mate token commits one line under Decisions with the repo's token, authored by its login`() {
        task()
        val taught = controller.teach(TeachRequest("t1", "allow", "ok"), request("fm"))
        assertEquals("c0mm1t", taught.commit)
        assertTrue(taught.line.endsWith("allow `uptime` on web-01 — health check; judge: escalate (0.5), call j-7; ok"), taught.line)
        val (repo, path, text, sha, author) = puts.single()
        assertEquals(listOf("acme/ops", "ops/rubric/ssh.md", "b10b", "alice"), listOf(repo, path, sha, author))
        assertEquals("# SSH\n\n## Decisions\n${taught.line}\n\n## After\n", text)
    }

    @Test
    fun `an older request's context data judge teaches the same line, and a call id finds the task`() {
        task(asserted = false)
        val taught = controller.teach(TeachRequest(callId = "j-7", decision = "allow", note = "ok"), request("fm"))
        assertTrue(taught.line.endsWith("allow `uptime` on web-01 — health check; judge: escalate (0.5), call j-7; ok"), taught.line)
        assertEquals(listOf("t1"), gets, "the task is read with tasks/get on the HITLP server")
    }

    @Test
    fun `a Smith sandbox is refused, even with a first-mate token's attributes, and nothing is committed`() {
        task()
        refused(HttpStatus.FORBIDDEN, TeachRequest("t1", "allow"), request("fm", smithAddress))
        refused(HttpStatus.UNAUTHORIZED, TeachRequest("t1", "allow"), request(null, smithAddress.replace("9", "8")))
        assertTrue(puts.isEmpty())
    }

    @Test
    fun `any token but a first-mate one is refused`() {
        task()
        refused(HttpStatus.FORBIDDEN, TeachRequest("t1", "allow"), request("plain"))
        refused(HttpStatus.FORBIDDEN, TeachRequest("t1", "allow"), request("pod"))
        assertTrue(puts.isEmpty())
    }

    @Test
    fun `the task must be a decided judge escalation whose outcome matches`() {
        task(judge = false)
        refused(HttpStatus.UNPROCESSABLE_ENTITY, TeachRequest("t1", "allow"), request("fm"))
        task(outcome = null)
        refused(HttpStatus.CONFLICT, TeachRequest("t1", "allow"), request("fm"))
        task(outcome = "rejected")
        refused(HttpStatus.CONFLICT, TeachRequest("t1", "allow"), request("fm"))
        refused(HttpStatus.BAD_REQUEST, TeachRequest("t1", "maybe"), request("fm"))
        refused(HttpStatus.NOT_FOUND, TeachRequest("nope", "deny"), request("fm"))
        refused(HttpStatus.BAD_REQUEST, TeachRequest(decision = "deny"), request("fm"))
        assertTrue(puts.isEmpty())
    }

    @Test
    fun `a missing rubric file or Decisions heading is an error and nothing is created`() {
        task()
        rubric = null
        refused(HttpStatus.UNPROCESSABLE_ENTITY, TeachRequest("t1", "allow"), request("fm"))
        rubric = RepoFile("# SSH\n## Never\n", "b10b")
        refused(HttpStatus.UNPROCESSABLE_ENTITY, TeachRequest("t1", "allow"), request("fm"))
        assertTrue(puts.isEmpty())
    }

    @Test
    fun `an escalation filed for a Smith may not name where the engine commits, and nothing is committed`() {
        for (owner in listOf(JudgeEscalation.runOwner("run-1"), "smith:o/r#7")) {
            task(owner = owner)
            refused(HttpStatus.UNPROCESSABLE_ENTITY, TeachRequest("t1", "allow"), request("fm"))
        }
        assertTrue(puts.isEmpty())
    }

    @Test
    fun `a rubricPath that is not a plain relative md path, or a repo without a token, is refused`() {
        for (path in listOf("../x.md", "ops/../../x.md", "/ops/ssh.md", "ops//ssh.md", "./ssh.md", "ops/rubric/ssh.txt")) {
            task(path = path)
            refused(HttpStatus.UNPROCESSABLE_ENTITY, TeachRequest("t1", "allow"), request("fm"))
        }
        task(repo = "x/untokened")
        refused(HttpStatus.UNPROCESSABLE_ENTITY, TeachRequest("t1", "allow"), request("fm"))
        assertTrue(puts.isEmpty())
    }
}

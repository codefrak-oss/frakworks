package com.codefrak.engine

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.codefrak.engine.intake.IntakeProcessTest
import com.codefrak.engine.intake.RefiningSandbox
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.EngineTokens
import com.codefrak.engine.smith.SmithCaller
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [BearerTokenFilter] over the full MockMvc stack: the exempt paths, a Smith's header ignored, the 401 texts with the
 * prefix in the log, a request without a token refused (401 on HTTP, `-32001` on MCP, reads included), the bootstrap
 * door, the attributes a valid token sets, `last_used_at`, and a `by` refused with 400 on every endpoint that took
 * one. Every token is minted by the test: no request here carries [TestTokens]' default.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntakeProcessTest.Fakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class BearerTokenFilterTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var tokens: EngineTokens
    @Autowired lateinit var access: Access
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var sandbox: RefiningSandbox

    private val smith = "172.18.0.7"
    private val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    private val logged = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun reset() {
        listOf("engine.engine_token", "engine.engine_access", "engine.engine_access_audit").forEach { jdbc.sql("DELETE FROM $it").update() }
        access.add("lundmatt1", "test")
        sandbox.callers.clear(); sandbox.runAtFails = false
        sandbox.callers[smith] = SmithCaller("run-7", 7, "acme/tickets")
        logged.list.clear(); logged.start(); root.addAppender(logged)
    }

    @AfterEach
    fun release() { root.detachAppender(logged); logged.stop() }

    private fun lines(level: Level = Level.INFO) = logged.list.filter { it.level == level }.map { it.formattedMessage }

    private fun get(path: String, from: String = "127.0.0.1", headers: Map<String, String> = emptyMap()): MvcResult =
        mvc.get(path) { headers.forEach { (k, v) -> header(k, v) }; with(TestTokens.noToken); with(RequestPostProcessor { it.remoteAddr = from; it }) }.andReturn()

    private fun bearer(token: String) = mapOf("Authorization" to "Bearer $token")

    @Test
    fun `health and ready are never filtered, and anything else asks the sandbox provider first, 503 when it cannot say`() {
        sandbox.runAtFails = true
        assertEquals(200, get("/health", headers = bearer("frk_whatever")).response.status)
        assertEquals(200, get("/health").response.status)
        assertEquals(503, get("/access", from = "10.0.0.5").response.status, "no header: still a Smith or not first")
        assertEquals(503, get("/access", from = "10.0.0.5", headers = bearer("frk_whatever")).response.status)
        assertEquals(401, get("/access").response.status, "a loopback caller is never a Smith: the sandbox is not asked")
    }

    @Test
    fun `the frakpanel tile pages and their state polls pass without a token, the enabled switch does not`() {
        sandbox.runAtFails = true
        for (path in listOf("/frakpanel/tiles/ai-accounts", "/frakpanel/tiles/ai-accounts/", "/frakpanel/tiles/ai-accounts/state",
                            "/frakpanel/tiles/frakworks-issues", "/frakpanel/tiles/frakworks-issues/state"))
            assertNotEquals(401, get(path, from = "10.0.0.5").response.status, path)
        assertEquals(401, get("/frakpanel/tiles/enabled").response.status)
        assertEquals(401, get("/frakpanel/tiles/other").response.status)
    }

    @Test
    fun `an unknown, revoked or expired token is 401 no such token with its prefix in the log, and a wrong scheme too`() {
        val r = get("/access", headers = bearer("frk_abcdefghij-not-a-token"))
        assertEquals(401, r.response.status)
        assertEquals("""{"error":"no such token"}""", r.response.contentAsString)
        assertTrue(lines().any { "no such token prefix=abcdefgh" in it }, lines().toString())
        val minted = tokens.mint("lundmatt1", "laptop", null, "test")
        tokens.revoke(minted.row.id)
        assertEquals(401, get("/access", headers = bearer(minted.token)).response.status)
        assertTrue(lines().any { "prefix=${minted.row.prefix}" in it })
        val basic = get("/access", headers = mapOf("Authorization" to "Basic abc"))
        assertEquals(401, basic.response.status)
        assertEquals(401, get("/access", headers = mapOf("Authorization" to "Bearer ")).response.status)
        assertEquals(401, get("/access", headers = mapOf("Authorization" to "Bearer")).response.status)
    }

    @Test
    fun `a token bound to a login no longer trusted is 401 naming it`() {
        val minted = tokens.mint("lundmatt1", "laptop", null, "test")
        access.remove("lundmatt1", "test")
        val r = get("/access", headers = bearer(minted.token))
        assertEquals(401, r.response.status)
        assertEquals("""{"error":"@lundmatt1 is not a trusted login"}""", r.response.contentAsString)
    }

    @Test
    fun `a valid token sets the login and token id on the request and bumps last_used_at`() {
        val minted = tokens.mint("lundmatt1", "laptop", null, "test")
        assertNull(tokens.get(minted.row.id)!!.lastUsedAt)
        val r = get("/repos", headers = bearer(minted.token))
        assertEquals(200, r.response.status)
        assertEquals("lundmatt1", r.request.getAttribute(TOKEN_LOGIN_ATTRIBUTE))
        assertEquals(minted.row.id, r.request.getAttribute(TOKEN_ID_ATTRIBUTE))
        assertNotNull(tokens.get(minted.row.id)!!.lastUsedAt)
    }

    @Test
    fun `a request without a token is 401 no such token, reads and writes alike, and no header names a login any more`() {
        for (path in listOf("/repos", "/access", "/needs", "/whoami")) {
            val r = get(path)
            assertEquals(401, r.response.status, path)
            assertEquals("""{"error":"no such token"}""", r.response.contentAsString, path)
        }
        val claimed = get("/repos", headers = mapOf("X-Frakworks-Login" to "lundmatt1"))
        assertEquals(401, claimed.response.status, "the old login header is nothing")
        assertNull(claimed.request.getAttribute(TOKEN_LOGIN_ATTRIBUTE))
        val write = mvc.post("/access") { contentType = MediaType.APPLICATION_JSON; content = """{"login":"grace"}"""; with(TestTokens.noToken) }.andReturn()
        assertEquals(401, write.response.status)
        assertTrue(lines().none { "login claimed" in it }, lines().toString())
    }

    @Test
    fun `the tokens endpoints need no token while none exists (the bootstrap door), and do once one does`() {
        val first = mvc.post("/tokens") { contentType = MediaType.APPLICATION_JSON; content = """{"login":"lundmatt1","name":"laptop"}"""; with(TestTokens.noToken) }.andReturn()
        assertEquals(201, first.response.status, first.response.contentAsString)
        assertEquals(401, mvc.post("/tokens") { contentType = MediaType.APPLICATION_JSON; content = """{"login":"lundmatt1","name":"desk"}"""; with(TestTokens.noToken) }
            .andReturn().response.status)
        assertEquals(401, get("/tokens").response.status)
    }

    private fun mcp(method: String, headers: Map<String, String> = emptyMap()): MvcResult = mvc.post("/mcp/frakworks") {
        contentType = MediaType.APPLICATION_JSON; accept = MediaType.APPLICATION_JSON
        content = """{"jsonrpc":"2.0","id":42,"method":"$method","params":{}}"""
        headers.forEach { (k, v) -> header(k, v) }; with(TestTokens.noToken)
    }.andReturn()

    @Test
    fun `an MCP request without a valid token is JSON-RPC -32001 with its id, initialize, ping and tools list included`() {
        for (method in listOf("initialize", "ping", "tools/list", "tools/call")) {
            val r = mcp(method)
            assertEquals(200, r.response.status, method)
            assertContains(r.response.contentAsString, """"code":-32001""", message = method)
            assertContains(r.response.contentAsString, """"id":42""", message = method)
            assertContains(r.response.contentAsString, "no such token", message = method)
        }
        val minted = tokens.mint("lundmatt1", "laptop", null, "test")
        assertContains(mcp("tools/list", bearer(minted.token)).response.contentAsString, """"tools"""")
        access.remove("lundmatt1", "test")
        val untrusted = mcp("initialize", bearer(minted.token)).response.contentAsString
        assertContains(untrusted, """"code":-32001""")
        assertContains(untrusted, "@lundmatt1 is not a trusted login")
        assertContains(mcp("initialize", mapOf("Authorization" to "Bearer frk_nope")).response.contentAsString, "no such token")
    }

    @Test
    fun `a by in the body or query is 400 naming it on every endpoint that took one`() {
        val minted = tokens.mint("lundmatt1", "laptop", null, "test")
        val auth = bearer(minted.token)
        fun send(method: String, path: String, body: String?): MvcResult = mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method), path).apply {
                auth.forEach { (k, v) -> header(k, v) }
                if (body != null) { contentType(MediaType.APPLICATION_JSON); content(body) }
            }).andReturn()
        val calls = listOf(
            Triple("POST", "/intake/no-such/attachments", """{"name":"a.md","content":"eA==","by":"lundmatt1"}"""),
            Triple("DELETE", "/intake/no-such", """{"reason":"r","by":"lundmatt1"}"""),
            Triple("POST", "/camunda/incidents/no-such/retry", """{"by":"lundmatt1"}"""),
            Triple("POST", "/ticket/acme/tickets/1/amend", """{"text":"t","by":"lundmatt1"}"""),
            Triple("POST", "/access", """{"login":"grace","by":"lundmatt1"}"""),
            Triple("DELETE", "/access/grace?by=lundmatt1", null),
        )
        for ((method, path, body) in calls) {
            val r = send(method, path, body)
            assertEquals(400, r.response.status, "$method $path: ${r.response.contentAsString}")
            assertContains(r.response.errorMessage.orEmpty(), "by", message = "$method $path")
        }
        assertEquals(201, send("POST", "/access", """{"login":"grace"}""").response.status, "without by, the token's login acts")
    }

    @Test
    fun `a Smith's Authorization header is ignored and its request goes on as the Smith's`() {
        val minted = tokens.mint("lundmatt1", "laptop", null, "test")
        val r = get("/whoami", from = smith, headers = bearer(minted.token))
        assertEquals(401, r.response.status, "a Smith proves no login: the header is ignored")
        assertNull(r.request.getAttribute(TOKEN_LOGIN_ATTRIBUTE))
        assertNotNull(r.request.getAttribute(SMITH_AT_ADDRESS_ATTRIBUTE))
        assertNull(tokens.get(minted.row.id)!!.lastUsedAt, "an ignored header bumps nothing")
        assertNotNull(get("/whoami", from = smith).request.getAttribute(SMITH_AT_ADDRESS_ATTRIBUTE), "and a Smith without one is a Smith too")
    }

    @Test
    fun `a first-mate-pod token reaches only its own session's pod paths, anything else is 403`() {
        val pod = tokens.mint("lundmatt1", "pod", null, "engine", EngineTokens.FIRST_MATE_POD, "s-1").token
        val own = get("/first-mate/pod/s-1/inbox?wait=0", headers = bearer(pod))
        assertEquals(404, own.response.status, "passes the filter; the controller finds no session s-1")
        assertEquals("lundmatt1", own.request.getAttribute(TOKEN_LOGIN_ATTRIBUTE))
        for (path in listOf("/first-mate/pod/s-2/inbox", "/first-mate/pod/s-1", "/first-mate/pod/s-10/inbox", "/first-mate/sessions/s-1/transcript", "/access")) {
            val r = get(path, headers = bearer(pod))
            assertEquals(403, r.response.status, path)
            assertEquals("""{"error":"${BearerTokenFilter.POD_SCOPE}"}""", r.response.contentAsString)
        }
        val other = tokens.mint("lundmatt1", "laptop", null, "test").token
        assertEquals(403, get("/first-mate/pod/s-1/inbox?wait=0", headers = bearer(other)).response.status, "another token passes the filter; the controller refuses it")
    }
}

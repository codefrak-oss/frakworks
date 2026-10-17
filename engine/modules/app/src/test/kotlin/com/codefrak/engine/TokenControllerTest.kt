package com.codefrak.engine

import com.codefrak.engine.firstmate.FirstMateSession
import com.codefrak.engine.firstmate.FirstMateSessionPods
import com.codefrak.engine.firstmate.FirstMateSessions
import com.codefrak.engine.intake.IntakeProcessTest
import com.codefrak.engine.intake.RefiningSandbox
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.EngineTokens
import com.codefrak.engine.smith.SmithCaller
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockingDetails
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `/tokens` and `/whoami` over the full MockMvc stack, the bearer filter included (engine/README.md, "Tokens"):
 * the bootstrap door, the gate once a token exists, and a Smith sandbox refused by address. Every token is minted
 * by the test that uses it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntakeProcessTest.Fakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class TokenControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var tokens: EngineTokens
    @Autowired lateinit var access: Access
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var sandbox: RefiningSandbox
    @Autowired lateinit var sessions: FirstMateSessions
    @MockitoSpyBean lateinit var pods: FirstMateSessionPods

    private val json = jacksonObjectMapper()
    private val smith = "172.18.0.7"

    @BeforeEach
    fun reset() {
        listOf("engine.first_mate_message", "engine.first_mate_session", "engine.engine_token", "engine.engine_access", "engine.engine_access_audit").forEach { jdbc.sql("DELETE FROM $it").update() }
        access.add("lundmatt1", "test")
        sandbox.callers.clear(); sandbox.runAtFails = false
        sandbox.callers[smith] = SmithCaller("run-7", 7, "acme/tickets")
    }

    private fun mint(body: String, bearer: String? = null, from: String = "127.0.0.1"): MvcResult =
        mvc.post("/tokens") {
            contentType = MediaType.APPLICATION_JSON; content = body
            bearer?.let { header("Authorization", "Bearer $it") }
            with(TestTokens.noToken); with(RequestPostProcessor { it.remoteAddr = from; it })
        }.andReturn()

    private fun list(bearer: String? = null, from: String = "127.0.0.1"): MvcResult =
        mvc.get("/tokens") { bearer?.let { header("Authorization", "Bearer $it") }; with(TestTokens.noToken); with(RequestPostProcessor { it.remoteAddr = from; it }) }.andReturn()

    private fun revoke(id: String, bearer: String? = null, from: String = "127.0.0.1"): MvcResult =
        mvc.delete("/tokens/$id") { bearer?.let { header("Authorization", "Bearer $it") }; with(TestTokens.noToken); with(RequestPostProcessor { it.remoteAddr = from; it }) }.andReturn()

    private fun whoami(bearer: String? = null, from: String = "127.0.0.1"): MvcResult =
        mvc.get("/whoami") { bearer?.let { header("Authorization", "Bearer $it") }; with(TestTokens.noToken); with(RequestPostProcessor { it.remoteAddr = from; it }) }.andReturn()

    private fun body(r: MvcResult): Map<String, Any?> = json.readValue(r.response.contentAsString)

    @Test
    fun `the first token is minted through the bootstrap door, then the tokens endpoints need a bearer token`() {
        val first = mint("""{"login":"lundmatt1","name":"laptop"}""")
        assertEquals(201, first.response.status, first.response.contentAsString)
        val minted = body(first)
        val token = minted["token"] as String
        assertEquals(setOf("id", "token", "login", "name", "prefix", "expiresAt", "purpose"), minted.keys)
        assertEquals("lundmatt1", minted["login"]); assertEquals("laptop", minted["name"]); assertNull(minted["expiresAt"])
        assertEquals(token.substring(4, 12), minted["prefix"])
        assertEquals("lundmatt1", tokens.get(minted["id"] as String)!!.createdBy, "created by the minted login through the door")

        val second = mint("""{"login":"lundmatt1","name":"desk","expiresInDays":30}""")
        assertEquals(401, second.response.status)
        assertEquals("no such token", body(second)["error"])
        assertEquals(401, list().response.status)
        assertEquals(401, revoke(minted["id"] as String).response.status)

        val third = mint("""{"login":"lundmatt1","name":"desk","expiresInDays":30}""", bearer = token)
        assertEquals(201, third.response.status, third.response.contentAsString)
        assertNotNull(body(third)["expiresAt"])
        assertEquals("lundmatt1", tokens.get(body(third)["id"] as String)!!.createdBy)

        val listed = list(bearer = token)
        assertEquals(200, listed.response.status)
        val rows: List<Map<String, Any?>> = json.readValue(listed.response.contentAsString)
        assertEquals(2, rows.size)
        assertFalse("hash" in listed.response.contentAsString); assertFalse(token in listed.response.contentAsString)
        assertEquals(setOf("id", "login", "name", "prefix", "createdAt", "createdBy", "expiresAt", "lastUsedAt", "revokedAt", "purpose"), rows[0].keys)
    }

    @Test
    fun `an untrusted login, a bad body and a bad expiry are 400 and mint nothing`() {
        assertEquals(400, mint("""{"login":"stranger","name":"x"}""").response.status)
        assertContains(mint("""{"login":"stranger","name":"x"}""").response.errorMessage ?: "", "@stranger is not a trusted login")
        assertEquals(400, mint("""{"login":"lundmatt1"}""").response.status)
        assertEquals(400, mint("""{"login":"lundmatt1","name":"x","expiresInDays":0}""").response.status)
        assertEquals(400, mint("""{"login":"lundmatt1","name":"x","expiresInDays":1.5}""").response.status)
        assertEquals(400, mint("""{"login":"lundmatt1","name":"x","extra":1}""").response.status)
        assertEquals(400, mint("""[]""").response.status)
        assertEquals(emptyList(), tokens.list())
    }

    @Test
    fun `whoami names the token's login, revoking it makes the next use 401, and a revoke is idempotent`() {
        val token = body(mint("""{"login":"lundmatt1","name":"laptop"}"""))
        val me = whoami(bearer = token["token"] as String)
        assertEquals(200, me.response.status)
        assertEquals(mapOf("login" to "lundmatt1", "tokenId" to token["id"], "name" to "laptop"), body(me))
        assertEquals(401, whoami().response.status)
        assertEquals("no such token", body(whoami())["error"])
        assertEquals(401, whoami(bearer = "frk_" + "0".repeat(43)).response.status)

        val other = body(mint("""{"login":"lundmatt1","name":"other"}""", bearer = token["token"] as String))
        assertEquals(204, revoke(other["id"] as String, bearer = token["token"] as String).response.status)
        assertEquals(200, revoke(other["id"] as String, bearer = token["token"] as String).response.status)
        assertEquals(404, revoke("nope", bearer = token["token"] as String).response.status)
        val revoked = whoami(bearer = other["token"] as String)
        assertEquals(401, revoked.response.status)
        assertEquals("no such token", body(revoked)["error"])
        assertNotNull(tokens.get(other["id"] as String)!!.revokedAt)
    }

    @Test
    fun `a token minted with purpose first-mate says so, and another purpose is 400`() {
        val token = body(mint("""{"login":"lundmatt1","name":"laptop"}"""))
        val phone = mint("""{"login":"lundmatt1","name":"iPhone","purpose":"first-mate"}""", bearer = token["token"] as String)
        assertEquals(201, phone.response.status, phone.response.contentAsString)
        assertEquals("first-mate", body(phone)["purpose"])
        assertEquals("first-mate", tokens.get(body(phone)["id"] as String)!!.purpose)
        assertNull(tokens.get(token["id"] as String)!!.purpose)
        assertEquals(400, mint("""{"login":"lundmatt1","name":"x","purpose":"laptop"}""", bearer = token["token"] as String).response.status)
    }

    @Test
    fun `revoking a token ends its first-mate sessions only, another token's stay open`() {
        val token = body(mint("""{"login":"lundmatt1","name":"laptop"}"""))
        val phone = body(mint("""{"login":"lundmatt1","name":"iPhone","purpose":"first-mate"}""", bearer = token["token"] as String))
        val other = body(mint("""{"login":"lundmatt1","name":"iPad","purpose":"first-mate"}""", bearer = token["token"] as String))
        val a = sessions.start("lundmatt1", phone["id"] as String, "acme/tickets")
        val b = sessions.start("lundmatt1", phone["id"] as String, "acme/tickets")
        val c = sessions.start("lundmatt1", other["id"] as String, "acme/tickets")

        assertEquals(204, revoke(phone["id"] as String, bearer = token["token"] as String).response.status)
        assertEquals("ended", sessions.get(a.id)!!.state); assertNotNull(sessions.get(a.id)!!.endedAt)
        assertEquals("ended", sessions.get(b.id)!!.state)
        assertEquals("open", sessions.get(c.id)!!.state)
        val stopped = mockingDetails(pods).invocations.filter { it.method.name == "stop" }.map { (it.arguments[0] as FirstMateSession).id }
        assertEquals(setOf(a.id, b.id), stopped.toSet())
    }

    @Test
    fun `revoking a plain token ends its open and frozen first-mate sessions, their pods and pod tokens, and leaves another plain token's open`() {
        val laptop = body(mint("""{"login":"lundmatt1","name":"laptop"}"""))
        val desk = body(mint("""{"login":"lundmatt1","name":"desk"}""", bearer = laptop["token"] as String))
        val a = sessions.start("lundmatt1", laptop["id"] as String, "acme/tickets")
        val b = sessions.start("lundmatt1", laptop["id"] as String, "acme/tickets")
        val c = sessions.start("lundmatt1", desk["id"] as String, "acme/tickets")
        sessions.freeze(b.id, java.time.OffsetDateTime.now().plusHours(1), false, java.time.OffsetDateTime.now())
        assertEquals("frozen", sessions.get(b.id)!!.state)
        listOf(a, b, c).forEach { tokens.mint("lundmatt1", "first-mate pod ${it.id}", null, "engine", EngineTokens.FIRST_MATE_POD, it.id) }

        assertEquals(204, revoke(laptop["id"] as String, bearer = desk["token"] as String).response.status)
        assertEquals("ended", sessions.get(a.id)!!.state); assertNotNull(sessions.get(a.id)!!.endedAt)
        assertEquals("ended", sessions.get(b.id)!!.state)
        assertEquals("open", sessions.get(c.id)!!.state)
        val stopped = mockingDetails(pods).invocations.filter { it.method.name == "stop" }.map { (it.arguments[0] as FirstMateSession).id }
        assertEquals(setOf(a.id, b.id), stopped.toSet())
        val podTokens: Map<String?, Boolean> = tokens.list().filter { it.purpose == EngineTokens.FIRST_MATE_POD }.associate { it.sessionId to (it.revokedAt != null) }
        assertEquals(mapOf<String?, Boolean>(a.id to true, b.id to true, c.id to false), podTokens)
    }

    @Test
    fun `a Smith sandbox is 403 on all three, by address, and its whoami is 401 as its header is ignored`() {
        val mintedBySmith = mint("""{"login":"lundmatt1","name":"laptop"}""", from = smith)
        assertEquals(403, mintedBySmith.response.status)
        assertEquals("a Smith sandbox (run run-7) may not mint a token", body(mintedBySmith)["error"])
        assertEquals(403, list(from = smith).response.status)
        assertEquals(403, revoke("x", from = smith).response.status)
        assertEquals(emptyList(), tokens.list())
        val token = body(mint("""{"login":"lundmatt1","name":"laptop"}"""))["token"] as String
        assertEquals(401, whoami(bearer = token, from = smith).response.status)
        sandbox.runAtFails = true
        assertEquals(503, mint("""{"login":"lundmatt1","name":"x"}""", bearer = token, from = "10.0.0.5").response.status)
    }
}

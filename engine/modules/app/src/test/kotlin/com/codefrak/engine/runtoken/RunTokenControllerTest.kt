package com.codefrak.engine.runtoken

import com.codefrak.engine.TestTokens
import com.codefrak.engine.intake.IntakeProcessTest
import com.codefrak.engine.repos.McpAudience
import com.codefrak.engine.repos.McpServerConfig
import com.codefrak.engine.repos.McpServerKind
import com.codefrak.engine.smith.RunTokenCheck
import com.codefrak.engine.smith.RunTokens
import com.codefrak.engine.smith.SmithMcpServers
import com.codefrak.engine.smith.SmithSpec
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
import java.sql.Timestamp
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Run identity tokens end to end (#2453; docs/run-identity-token.md): minted with a sealed key, published in the JWKS
 * and introspected without an engine token, inactive once the run's session ends or the run is revoked, a rotated key
 * still verifying, and a run token refused as a bearer token (so it cannot answer HITLP).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntakeProcessTest.Fakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake", "engine.secrets.key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="])
class RunTokenControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var tokens: RunTokens
    @Autowired lateinit var jdbc: JdbcClient

    private val json = jacksonObjectMapper()
    private val aud = "http://ops-server.ops.svc.cluster.local:8737/mcp"
    private val spec = SmithSpec("run-rt-1", "acme/ops", 37, "m", "e", dryRun = false)
    private val outside = RequestPostProcessor { it.remoteAddr = "10.9.9.9"; it }

    @BeforeEach
    fun session() {
        jdbc.sql("DELETE FROM engine.smith_session WHERE run_id = :r").param("r", spec.runId).update()
        jdbc.sql("DELETE FROM engine.run_token_revoked").update()
        jdbc.sql("""INSERT INTO engine.smith_session (run_id, repo, issue, mode, model, started_at)
                    VALUES (:r, 'acme/ops', 37, 'work', 'm', :t)""").param("r", spec.runId).param("t", Timestamp.from(Instant.now())).update()
    }

    private fun introspect(token: String, audience: String? = aud): Map<String, Any?> {
        val body = json.writeValueAsString(mapOf("token" to token, "audience" to audience))
        val r = mvc.post("/run-tokens/introspect") {
            contentType = MediaType.APPLICATION_JSON; content = body; with(TestTokens.noToken); with(outside)
        }.andReturn().response
        assertEquals(200, r.status, r.contentAsString)
        return json.readValue(r.contentAsString)
    }

    @Test
    fun `the JWKS is public, and a minted token verifies against it and introspects active while the run lives`() {
        val token = tokens.mint(spec, aud)
        val r = mvc.get("/.well-known/jwks.json") { with(TestTokens.noToken); with(outside) }.andReturn().response
        assertEquals(200, r.status)
        val keys: List<Map<String, Any?>> = json.readValue<Map<String, List<Map<String, Any?>>>>(r.contentAsString)["keys"]!!
        val byKid = keys.associateBy { it["kid"] as String }
        val ok = assertIs<RunTokenCheck.Valid>(RunTokens.verify(token, { byKid[it]?.let(RunTokens::publicKey) }, Instant.now(), aud))
        assertEquals("run-rt-1", ok.claims.runId); assertEquals(37L, ok.claims.issue)

        val active = introspect(token)
        assertEquals(true, active["active"]); assertEquals("run:run-rt-1", active["sub"]); assertEquals("acme/ops", active["repo"])
        assertEquals(false, introspect(token, "https://other.example.com/mcp")["active"], "another server's audience")
        assertEquals(false, introspect(token.dropLast(4) + "AAAA")["active"], "forged signature")
    }

    @Test
    fun `a token is inactive once its run's session ends, or the run is revoked`() {
        val token = tokens.mint(spec, aud)
        jdbc.sql("UPDATE engine.smith_session SET ended_at = :t WHERE run_id = :r").param("t", Timestamp.from(Instant.now())).param("r", spec.runId).update()
        assertEquals(mapOf("active" to false), introspect(token))

        val other = spec.copy(runId = "run-rt-2")
        jdbc.sql("DELETE FROM engine.smith_session WHERE run_id = 'run-rt-2'").update()
        jdbc.sql("INSERT INTO engine.smith_session (run_id, repo, issue, mode, model, started_at) VALUES ('run-rt-2', 'acme/ops', 37, 'work', 'm', :t)")
            .param("t", Timestamp.from(Instant.now())).update()
        val second = tokens.mint(other, aud)
        assertEquals(true, introspect(second)["active"])
        tokens.revoke("run-rt-2")
        assertEquals(false, introspect(second)["active"])
    }

    @Test
    fun `a rotated key keeps its tokens verifiable, and new tokens use the new kid`() {
        val before = tokens.mint(spec, aud)
        val kid = tokens.rotate()
        val after = tokens.mint(spec, aud)
        assertIs<RunTokenCheck.Valid>(tokens.verify(before, aud))
        assertIs<RunTokenCheck.Valid>(tokens.verify(after, aud))
        assertNotEquals(before.substringBefore('.'), after.substringBefore('.'))
        assertTrue(@Suppress("UNCHECKED_CAST") (tokens.jwks()["keys"] as List<Map<String, Any?>>).first()["kid"] == kid)
    }

    @Test
    fun `a run token offered as a bearer token is refused, so it cannot reach the engine or answer HITLP`() {
        val token = tokens.mint(spec, aud)
        val r = mvc.get("/needs") { header("Authorization", "Bearer $token"); with(TestTokens.noToken); with(outside) }.andReturn().response
        assertEquals(401, r.status)
        assertContains(r.contentAsString, "run identity token")
    }

    @Test
    fun `SmithMcpServers stamps each http server with its own token, aud its URL, and a stdio entry with none`() {
        val ops = McpServerConfig("ops", McpServerKind.HTTP, aud, mapOf("X-Frakworks-Run-Token" to "spoofed"), null, emptyList(), emptyMap(), McpAudience.SMITH)
        val ev = ops.copy(name = "ev", url = "https://events.example.com/sse", kind = McpServerKind.SSE)
        val stdio = McpServerConfig("tool", McpServerKind.STDIO, null, emptyMap(), "tool", emptyList(), emptyMap(), McpAudience.SMITH)
        val (servers, _) = SmithMcpServers.build(spec.runId, listOf(ops, ev, stdio)) { tokens.mint(spec, it) }
        @Suppress("UNCHECKED_CAST")
        fun tokenOf(name: String) = (servers[name]!!["headers"] as Map<String, String>)[RunTokens.HEADER]!!
        assertEquals(aud, assertIs<RunTokenCheck.Valid>(tokens.verify(tokenOf("ops"))).claims.audience)
        assertEquals("https://events.example.com/sse", assertIs<RunTokenCheck.Valid>(tokens.verify(tokenOf("ev"))).claims.audience)
        assertIs<RunTokenCheck.Invalid>(tokens.verify(tokenOf("ops"), "https://events.example.com/sse"))
        assertEquals(null, servers["tool"]!!["headers"])
    }
}

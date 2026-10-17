package com.codefrak.engine.settings

import com.codefrak.engine.TestTokens
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.github.DeploymentPatterns
import com.codefrak.engine.github.ReadFakes
import com.codefrak.engine.smith.SandboxStatus
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.smith.SmithSandbox
import com.codefrak.engine.smith.SmithSpec
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

private class PatternSandbox(val runs: Map<String, SmithCaller>) : SmithSandbox {
    override fun start(spec: SmithSpec): String = error("unused")
    override fun status(handle: String): SandboxStatus = error("unused")
    override fun logs(handle: String, maxLines: Int): String = ""
    override fun output(handle: String, path: String): ByteArray? = null
    override fun remove(handle: String) {}
    override fun runAt(address: String): SmithCaller? = runs[address]
}

/**
 * `/settings/deployment-patterns` (#100115) over MockMvc: set, read back without the value (in the reply and in the
 * database, sealed), clear by DELETE and by an empty PUT; a bad regex 400s by line number only; a Smith and a
 * token-less caller 403.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ReadFakes::class)
class DeploymentPatternsControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var settings: DeploymentPatterns
    @Autowired lateinit var bus: EngineEvents
    @Autowired lateinit var jdbc: JdbcClient

    private val secret = "secret-host-[0-9]+\\\\.example"
    private val sandbox = PatternSandbox(mapOf("172.18.0.7" to SmithCaller("run-own", 7, "acme/widgets")))
    private lateinit var guarded: MockMvc
    private val json = MediaType.APPLICATION_JSON
    private fun from(address: String) = RequestPostProcessor { it.remoteAddr = address; it }

    @BeforeEach fun setUp() {
        guarded = MockMvcBuilders.standaloneSetup(DeploymentPatternsController(settings, bus, sandbox)).build()
        settings.set(null, null)
    }

    @AfterEach fun clean() = settings.set(null, null)

    private fun put(body: String, as_: String? = "dpc-alice", address: String = "10.0.0.5") = guarded.put("/settings/deployment-patterns") {
        contentType = json; content = body; with(from(address)); as_?.let { with(TestTokens.proven(it)) }
    }

    @Test
    fun `set, read back without the value, clear by DELETE and by an empty PUT`() {
        mvc.get("/settings/deployment-patterns").andExpect { jsonPath("$.patterns.set") { value(false) }; jsonPath("$.allow.set") { value(false) } }

        put("""{"patterns": "# a comment\n$secret\n", "allow": "secret-host-1.example is fine"}""").andExpect {
            status { isOk() }
            jsonPath("$.patterns.set") { value(true) }; jsonPath("$.allow.set") { value(true) }
            content { string(not(containsString("secret-host"))) }
        }
        mvc.get("/settings/deployment-patterns").andExpect {
            jsonPath("$.patterns.set") { value(true) }
            content { string(not(containsString("secret-host"))) }
        }
        val stored = jdbc.sql("SELECT patterns FROM engine.deployment_patterns").query(ByteArray::class.java).single()
        assertFalse(String(stored, Charsets.ISO_8859_1).contains("secret-host"), "sealed, not plain text")
        assertEquals("# a comment\nsecret-host-[0-9]+\\.example\n", settings.rules()!!.patterns)

        guarded.delete("/settings/deployment-patterns") { with(from("10.0.0.5")); with(TestTokens.proven("dpc-alice")) }
            .andExpect { status { isOk() }; jsonPath("$.patterns.set") { value(false) }; jsonPath("$.allow.set") { value(false) } }
        assertNull(settings.rules())

        put("""{"patterns": "x"}""").andExpect { jsonPath("$.patterns.set") { value(true) } }
        put("{}").andExpect { status { isOk() }; jsonPath("$.patterns.set") { value(false) } }
        put("""{"patterns": ""}""").andExpect { jsonPath("$.patterns.set") { value(false) } }
        assertNull(settings.rules())
    }

    @Test
    fun `a bad regex names its line only, an unknown key 400s, a Smith and a token-less caller 403`() {
        put("""{"patterns": "ok\n(secret-unclosed"}""").andExpect {
            status { isBadRequest() }
            content { string(not(containsString("secret-unclosed"))) }
        }
        put("""{"other": "x"}""").andExpect { status { isBadRequest() } }
        put("""{"patterns": "x"}""", address = "172.18.0.7").andExpect { status { isForbidden() } }
        put("""{"patterns": "x"}""", as_ = null).andExpect { status { isForbidden() } }
        assertNull(settings.rules())
    }
}

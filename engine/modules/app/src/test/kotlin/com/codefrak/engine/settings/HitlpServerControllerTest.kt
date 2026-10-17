package com.codefrak.engine.settings

import com.codefrak.engine.TestTokens
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.events.EventRecorder
import com.codefrak.engine.github.ReadFakes
import com.codefrak.engine.hitlp.HitlpServerSettings
import com.codefrak.engine.hitlp.HitlpServerSettings.Field
import com.codefrak.engine.hitlp.HitlpStubSeeder
import com.codefrak.engine.smith.SandboxStatus
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.smith.SmithSandbox
import com.codefrak.engine.smith.SmithSpec
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A sandbox that knows only which address is which run. */
private class AddressSandbox(val runs: Map<String, SmithCaller>) : SmithSandbox {
    override fun start(spec: SmithSpec): String = error("unused")
    override fun status(handle: String): SandboxStatus = error("unused")
    override fun logs(handle: String, maxLines: Int): String = ""
    override fun output(handle: String, path: String): ByteArray? = null
    override fun remove(handle: String) {}
    override fun runAt(address: String): SmithCaller? = runs[address]
}

/**
 * `GET`/`PUT /settings/hitlp` (#2500) over MockMvc: the GET never carries a secret; each field set alone, a bad URL and
 * an unknown key (`surfaceUrl` among them since #100013) 400, null clears; one `setting` frame named `engine.hitlp`
 * naming the fields, no value; a Smith sandbox and a token-less caller 403; the surface credential endpoints are gone.
 * Writes go through a standalone controller whose sandbox is [AddressSandbox]: the context's own is Docker's, which a
 * test cannot ask. The shared stub's row is put back after, for the context's other tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ReadFakes::class)
class HitlpServerControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var settings: HitlpServerSettings
    @Autowired lateinit var seeder: HitlpStubSeeder
    @Autowired lateinit var bus: EngineEvents

    private val login = "hsc-alice"
    private val sandbox = AddressSandbox(mapOf("172.18.0.7" to SmithCaller("run-own", 7, "acme/widgets")))
    private lateinit var guarded: MockMvc
    private val json = MediaType.APPLICATION_JSON
    private fun from(address: String) = RequestPostProcessor { it.remoteAddr = address; it }

    @BeforeEach
    fun setUp() {
        guarded = MockMvcBuilders.standaloneSetup(HitlpServerController(settings, bus, sandbox)).build()
        settings.set(Field.Set(null), Field.Set(null))
    }

    @AfterEach
    fun restore() {
        seeder.seed()
    }

    private fun put(body: String, as_: String? = login, address: String = "10.0.0.5") = guarded.put("/settings/hitlp") {
        contentType = json; content = body; with(from(address)); as_?.let { with(TestTokens.proven(it)) }
    }

    @Test
    fun `GET answers nulls while unset and never a secret, PUT sets each field alone and null clears, with one setting frame naming the fields`() = EventRecorder(bus).use { rec ->
        mvc.get("/settings/hitlp").andExpect {
            status { isOk() }; jsonPath("$.mcpUrl") { value(null) }; jsonPath("$.surfaceUrl") { doesNotExist() }
            jsonPath("$.requesterToken.set") { value(false) }; jsonPath("$.requesterToken.setAt") { value(null) }; jsonPath("$.surfaceCredentials") { doesNotExist() }
        }
        put("""{"mcpUrl": " https://hitlp.example/mcp/ "}""").andExpect { status { isOk() }; jsonPath("$.mcpUrl") { value("https://hitlp.example/mcp") } }
        put("""{"requesterToken": "tok-1"}""").andExpect {
            status { isOk() }; jsonPath("$.requesterToken.set") { value(true) }; jsonPath("$.requesterToken.setAt") { isNotEmpty() }
            content { string(org.hamcrest.Matchers.not(containsString("tok-1"))) }
        }
        assertEquals("tok-1", settings.endpoint()!!.token)
        mvc.get("/settings/hitlp").andExpect {
            status { isOk() }; jsonPath("$.mcpUrl") { value("https://hitlp.example/mcp") }
            jsonPath("$.requesterToken.set") { value(true) }; content { string(org.hamcrest.Matchers.not(containsString("tok-1"))) }
        }
        put("""{"mcpUrl": "https://hitlp.example/mcp"}""").andExpect { status { isOk() } }
        put("""{"mcpUrl": null, "requesterToken": null}""").andExpect { status { isOk() }; jsonPath("$.mcpUrl") { value(null) }; jsonPath("$.requesterToken.set") { value(false) } }
        assertNull(settings.endpoint())
        val frames = rec.of("setting").filter { it.str("name") == HitlpServerSettings.EVENT_NAME }
        assertEquals(listOf(listOf("mcpUrl"), listOf("requesterToken"), listOf("mcpUrl", "requesterToken")),
            frames.map { f -> f.data["value"]!!["changed"]!!.toList().map { it.asString() } }, "an unchanged value is no frame; the token is never in one")
        assertEquals(emptyList(), frames.filter { it.data["value"].toString().contains("tok-1") })
    }

    @Test
    fun `PUT refuses a bad URL, a wrong type, an unknown key and an empty body, and changes nothing`() {
        settings.set(Field.Set("https://hitlp.example/mcp"), Field.Set("tok-1"))
        for (body in listOf("""{"mcpUrl": "hitlp.example/mcp"}""", """{"mcpUrl": "/mcp"}""", """{"surfaceUrl": "https://hitlp.example/surface/v1"}""",
            """{"mcpUrl": 8090}""", """{"requesterToken": 5}""", """{"requesterToken": " "}""", """{"url": "https://a.b"}""", """{"mcpUrl": "https://a.b", "x": 1}""", """{}""")) {
            put(body).andExpect { status { isBadRequest() } }
        }
        val view = settings.get()
        assertEquals("https://hitlp.example/mcp", view.mcpUrl)
        assertEquals("tok-1", settings.endpoint()!!.token)
    }

    @Test
    fun `a Smith sandbox and a caller without a login are 403 on every write`() = EventRecorder(bus).use { rec ->
        put("""{"mcpUrl": "https://evil.example/mcp"}""", address = "172.18.0.7").andExpect { status { isForbidden() }; status { reason(containsString("run-own")) } }
        put("""{"mcpUrl": "https://evil.example/mcp"}""", as_ = null).andExpect { status { isForbidden() }; status { reason(containsString("login")) } }
        assertNull(settings.get().mcpUrl)
        assertEquals(emptyList(), rec.of("setting").filter { it.str("name") == HitlpServerSettings.EVENT_NAME })
    }

    @Test
    fun `the surface credential endpoints are gone`() {
        guarded.put("/settings/hitlp/surface-credential") {
            contentType = json; content = """{"credential": "good"}"""; with(TestTokens.proven(login))
        }.andExpect { status { isNotFound() } }
        guarded.delete("/settings/hitlp/surface-credential") { with(TestTokens.proven(login)) }.andExpect { status { isNotFound() } }
    }
}

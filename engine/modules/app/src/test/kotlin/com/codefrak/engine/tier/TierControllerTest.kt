package com.codefrak.engine.tier

import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.events.EventRecorder
import com.codefrak.engine.github.ReadFakes
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `GET /tiers`, `PUT /tiers/{tier}` and `GET`/`PUT /security/tier` over MockMvc, against the map V42 seeds. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ReadFakes::class)
class TierControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var tiers: TierTable
    @Autowired lateinit var bus: EngineEvents

    // The H2 is shared with the other Spring tests: leave the seeded map behind.
    @AfterEach
    fun reset() { tiers.set(6, "opus", "medium"); tiers.setSecurityTier(5) }

    private fun put(path: String, body: String) = mvc.put(path) { contentType = MediaType.APPLICATION_JSON; content = body }

    @Test
    fun `GET answers the ten tiers in order with full model ids`() {
        mvc.get("/tiers").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(10) }
            jsonPath("$[0].tier") { value(1) }
            jsonPath("$[0].model") { value("claude-haiku-4-5-20251001") }
            jsonPath("$[0].effort") { value("low") }
            jsonPath("$[5].tier") { value(6) }
            jsonPath("$[5].model") { value("claude-opus-5-5") }
            jsonPath("$[5].effort") { value("medium") }
            jsonPath("$[9].model") { value("claude-fable-5-1") }
            jsonPath("$[9].effort") { value("high") }
        }
    }

    @Test
    fun `PUT changes one tier, answers its row, and the next resolution is the new one`() {
        put("/tiers/6", """{"model":"opus","effort":"high"}""").andExpect {
            status { isOk() }
            jsonPath("$.tier") { value(6) }
            jsonPath("$.model") { value("claude-opus-5-5") }
            jsonPath("$.effort") { value("high") }
        }
        assertEquals(Resolved("claude-opus-5-5", "high"), tiers.resolve(6))
        mvc.get("/tiers").andExpect { jsonPath("$[5].effort") { value("high") } }
        put("/tiers/6", """{"model":"claude-fable-5-1","effort":"low"}""").andExpect { status { isOk() } }
        assertEquals(Resolved("claude-fable-5-1", "low"), tiers.resolve(6))
    }

    @Test
    fun `PUT refuses each write the map cannot take with a 400 saying why, and keeps the row`() {
        mapOf(
            "/tiers/0" to """{"model":"opus","effort":"low"}""" to "tier 0 is not 1-10",
            "/tiers/11" to """{"model":"opus","effort":"low"}""" to "tier 11 is not 1-10",
            "/tiers/x" to """{"model":"opus","effort":"low"}""" to "tier x is not 1-10",
            "/tiers/6" to """{"model":"gpt-4","effort":"low"}""" to "unknown model 'gpt-4'",
            "/tiers/6" to """{"model":"haiku","effort":"medium"}""" to "haiku does not take effort 'medium'",
            "/tiers/6" to """{"model":"opus"}""" to "effort is empty",
            "/tiers/6" to """{"model":" ","effort":"low"}""" to "model is empty",
            "/tiers/6" to """{"model":"opus","effort":"low","tier":3}""" to "the body is",
            "/tiers/6" to """{"model":6,"effort":"low"}""" to "model is a string",
        ).forEach { (request, reason) ->
            val res = put(request.first, request.second).andExpect { status { isBadRequest() } }.andReturn()
            assertTrue(reason in res.response.errorMessage.orEmpty(), "${request}: ${res.response.errorMessage} says $reason")
        }
        assertEquals(Resolved("claude-opus-5-5", "medium"), tiers.resolve(6))
        assertEquals(10, tiers.all().size, "always ten tiers")
    }

    /** The stream (engine/README.md, "Events"): a `setting` frame per write, fleet-wide; a refused write publishes nothing. */
    @Test
    fun `a write publishes a setting, and a refused one publishes nothing`() = EventRecorder(bus).use { rec ->
        put("/tiers/6", """{"model":"opus","effort":"high"}""").andExpect { status { isOk() } }
        put("/tiers/6", """{"model":"opus","effort":"loud"}""").andExpect { status { isBadRequest() } }
        put("/security/tier", """{"tier":7}""").andExpect { status { isOk() } }
        put("/security/tier", """{"tier":11}""").andExpect { status { isBadRequest() } }
        val frames = rec.of("setting")
        assertEquals(listOf("tiers", "security.tier"), frames.map { it.str("name") })
        assertEquals(listOf("*", "*"), frames.map { it.str("repo") })
        assertEquals(7L, frames[1].long("value"))
    }

    @Test
    fun `the security tier is 5 as seeded, set by PUT with the 1-10 check, and the next security resolution follows it`() {
        mvc.get("/security/tier").andExpect {
            status { isOk() }
            jsonPath("$.tier") { value(5) }
            jsonPath("$.model") { value("claude-opus-5-5") }
            jsonPath("$.effort") { value("medium") }
        }
        put("/security/tier", """{"tier":8}""").andExpect {
            status { isOk() }
            jsonPath("$.tier") { value(8) }
            jsonPath("$.model") { value("claude-fable-5-1") }
            jsonPath("$.effort") { value("low") }
        }
        assertEquals(Resolved("claude-fable-5-1", "low"), tiers.securityResolved())
        listOf("""{"tier":0}""", """{"tier":11}""", """{"tier":"6"}""", """{"tier":6.5}""", """{}""", """{"tier":6,"extra":1}""")
            .forEach { body -> put("/security/tier", body).andExpect { status { isBadRequest() } } }
        assertEquals(8, tiers.securityTier())
    }

    @Test
    fun `the check tier is 4 as seeded, set by PUT with the 1-10 check, and publishes its own setting frame`() = EventRecorder(bus).use { rec ->
        mvc.get("/check/tier").andExpect {
            status { isOk() }
            jsonPath("$.tier") { value(4) }
            jsonPath("$.model") { value("claude-opus-5-5") }
            jsonPath("$.effort") { value("low") }
        }
        put("/check/tier", """{"tier":2}""").andExpect {
            status { isOk() }
            jsonPath("$.tier") { value(2) }
            jsonPath("$.model") { value("claude-sonnet-5") }
        }
        assertEquals(Resolved("claude-sonnet-5", "low"), tiers.checkResolved())
        listOf("""{"tier":0}""", """{"tier":11}""", """{"tier":"6"}""", """{}""", """{"tier":6,"extra":1}""")
            .forEach { body -> put("/check/tier", body).andExpect { status { isBadRequest() } } }
        assertEquals(2, tiers.checkTier())
        val frame = rec.of("setting").single()
        assertEquals("check.tier" to 2L, frame.str("name") to frame.long("value"))
    }
}

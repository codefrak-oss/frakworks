package com.codefrak.engine.intake

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

/** `GET`/`PUT /intake/tier` over MockMvc, against the tier map V42 seeds (TierTable). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ReadFakes::class)
class IntakeTierControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: IntakeTier
    @Autowired lateinit var bus: EngineEvents

    // The H2 is shared with the other Spring tests: leave the seeded tier behind.
    @AfterEach
    fun reset() { store.set(5) }

    private fun put(body: String) = mvc.put("/intake/tier") { contentType = MediaType.APPLICATION_JSON; content = body }

    @Test
    fun `GET answers the tier with its model and effort, 5 as seeded`() {
        mvc.get("/intake/tier").andExpect {
            status { isOk() }
            jsonPath("$.tier") { value(5) }
            jsonPath("$.model") { value("claude-opus-5-5") }
            jsonPath("$.effort") { value("medium") }
        }
    }

    @Test
    fun `PUT stores a mapped tier and GET then answers it`() {
        put("""{"tier":8}""").andExpect {
            status { isOk() }
            jsonPath("$.tier") { value(8) }
            jsonPath("$.model") { value("claude-fable-5-1") }
            jsonPath("$.effort") { value("low") }
        }
        assertEquals(8, store.get())
        mvc.get("/intake/tier").andExpect { jsonPath("$.tier") { value(8) } }
    }

    /** The stream (engine/README.md, "Events"): a `setting` `intake.tier` frame, fleet-wide, with the value. */
    @Test
    fun `PUT publishes the tier as a setting, and a refused PUT publishes nothing`() = EventRecorder(bus).use { rec ->
        put("""{"tier":7}""").andExpect { status { isOk() } }
        put("""{"tier":99}""").andExpect { status { isBadRequest() } }
        val frame = rec.of("setting").single()
        assertEquals(listOf("*", "intake.tier", 7L), listOf(frame.str("repo"), frame.str("name"), frame.long("value")))
    }

    @Test
    fun `PUT refuses anything but a mapped integer tier with a 400 and keeps the stored one`() {
        store.set(4)
        listOf("""{"tier":0}""", """{"tier":11}""", """{"tier":-1}""", """{"tier":"6"}""", """{"tier":6.5}""",
            """{"tier":null}""", """{}""", """{"tier":6,"extra":1}""", """[6]""", """6""", """{"tier":99999999999}""")
            .forEach { body -> put(body).andExpect { status { isBadRequest() } } }
        assertEquals(4, store.get())
    }
}

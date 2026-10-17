package com.codefrak.engine.frakpanel

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
import kotlin.test.assertFalse

/** `GET`/`PUT /frakpanel/tiles/enabled` over MockMvc. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ReadFakes::class)
class FrakpanelTilesTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: FrakpanelTiles
    @Autowired lateinit var bus: EngineEvents

    // The H2 is shared with the other Spring tests: leave the seeded value behind.
    @AfterEach
    fun reset() { store.set(false) }

    private fun put(body: String) = mvc.put("/frakpanel/tiles/enabled") { contentType = MediaType.APPLICATION_JSON; content = body }

    @Test
    fun `off as V52 seeds it`() {
        mvc.get("/frakpanel/tiles/enabled").andExpect { status { isOk() }; jsonPath("$.enabled") { value(false) } }
    }

    @Test
    fun `PUT turns it on and off and publishes a setting`() = EventRecorder(bus).use { rec ->
        put("""{"enabled":true}""").andExpect { status { isOk() }; jsonPath("$.enabled") { value(true) } }
        mvc.get("/frakpanel/tiles/enabled").andExpect { jsonPath("$.enabled") { value(true) } }
        put("""{"enabled":false}""").andExpect { status { isOk() } }
        assertFalse(store.enabled())
        assertEquals(listOf("frakpanel.tiles", "frakpanel.tiles"), rec.of("setting").map { it.str("name") })
    }

    @Test
    fun `PUT refuses anything but a boolean with a 400 and keeps the setting`() {
        listOf("""{"enabled":"true"}""", """{"enabled":1}""", """{"enabled":null}""", """{}""",
            """{"enabled":true,"extra":1}""", """[true]""", """true""")
            .forEach { body -> put(body).andExpect { status { isBadRequest() } } }
        assertFalse(store.enabled())
    }
}

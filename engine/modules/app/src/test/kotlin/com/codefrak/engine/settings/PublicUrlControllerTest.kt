package com.codefrak.engine.settings

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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The public URL's store and `GET`/`PUT /settings/public-url` over MockMvc (engine/README.md, "The public URL"). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ReadFakes::class)
class PublicUrlControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: PublicUrl
    @Autowired lateinit var bus: EngineEvents

    // The H2 is shared with the other Spring tests: leave no row behind.
    @AfterEach
    fun reset() { store.set(null) }

    private fun put(body: String) = mvc.put("/settings/public-url") { contentType = MediaType.APPLICATION_JSON; content = body }

    @Test
    fun `the store is null until set, keeps a value and deletes it for null`() {
        assertNull(store.get(), "V72 seeds nothing")
        store.set("https://engine.example.com"); assertEquals("https://engine.example.com", store.get())
        store.set("http://10.0.0.5:8090"); assertEquals("http://10.0.0.5:8090", store.get())
        store.set(null); assertNull(store.get())
    }

    @Test
    fun `GET answers null while unset, PUT sets and clears it, and a change is one setting frame`() = EventRecorder(bus).use { rec ->
        mvc.get("/settings/public-url").andExpect { status { isOk() }; jsonPath("$.publicUrl") { value(null) } }
        put("""{"publicUrl": " https://engine.example.com/ "}""").andExpect { status { isOk() }; jsonPath("$.publicUrl") { value("https://engine.example.com") } }
        mvc.get("/settings/public-url").andExpect { status { isOk() }; jsonPath("$.publicUrl") { value("https://engine.example.com") } }
        put("""{"publicUrl": "https://engine.example.com"}""").andExpect { status { isOk() } }
        put("""{"publicUrl": null}""").andExpect { status { isOk() }; jsonPath("$.publicUrl") { value(null) } }
        assertNull(store.get())
        val frames = rec.of("setting").filter { it.str("name") == PublicUrl.NAME }
        assertEquals(listOf("https://engine.example.com", null), frames.map { it.str("value") }, "an unchanged value is no frame")
    }

    @Test
    fun `PUT refuses a relative, non-http(s) or hostless URL, a non-string, another key and an empty body, and changes nothing`() {
        store.set("https://engine.example.com")
        for (body in listOf("""{"publicUrl": "engine.example.com"}""", """{"publicUrl": "/web"}""", """{"publicUrl": "ftp://engine.example.com"}""",
            """{"publicUrl": "https://"}""", """{"publicUrl": "http://bad host"}""", """{"publicUrl": ""}""", """{"publicUrl": 8090}""",
            """{"url": "https://engine.example.com"}""", """{"publicUrl": "https://a.b", "x": 1}""", """{}""")) {
            put(body).andExpect { status { isBadRequest() } }
        }
        assertEquals("https://engine.example.com", store.get())
        assertNotNull(PublicUrl.problem("mailto:x@y.z"))
    }
}

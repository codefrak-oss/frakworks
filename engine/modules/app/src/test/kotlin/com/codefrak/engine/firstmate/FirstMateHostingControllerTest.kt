package com.codefrak.engine.firstmate

import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.events.EventRecorder
import com.codefrak.engine.github.ReadFakes
import com.codefrak.engine.smith.SmithHosting
import com.codefrak.engine.smith.SmithHostingSettings
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `GET`/`PUT /settings/first-mate-hosting` over MockMvc: `local` with no row, `kubernetes` only on a Kubernetes Smith hosting. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ReadFakes::class)
class FirstMateHostingControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: FirstMateHosting
    @Autowired lateinit var smith: SmithHosting
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var events: EngineEvents

    private val k8s = SmithHostingSettings("kubernetes", "frakworks-smith", "registry.example:5000/frakworks-smith:main", "http://192.0.2.10:8090")

    // The H2 is shared with the other Spring tests: leave the seeded Smith rows behind and no firstmate.hosting row.
    @AfterEach
    fun reset() {
        smith.set(SmithHostingSettings("docker", "", "", ""))
        jdbc.sql("DELETE FROM engine.engine_setting WHERE name = :n").param("n", FirstMateHosting.HOSTING).update()
    }

    private fun put(body: String) = mvc.put("/settings/first-mate-hosting") { contentType = MediaType.APPLICATION_JSON; content = body }

    @Test
    fun `GET answers local when no row was ever written`() {
        mvc.get("/settings/first-mate-hosting").andExpect { status { isOk() }; jsonPath("$.hosting") { value("local") } }
        assertEquals("local", store.get())
    }

    @Test
    fun `PUT kubernetes is refused while Smith hosting is docker, and while a Kubernetes setting is blank`() {
        put("""{"hosting": "kubernetes"}""").andExpect {
            status { isBadRequest() }; status { reason(containsString("smith.hosting")) }
        }
        smith.set(k8s.copy(namespace = ""))
        put("""{"hosting": "kubernetes"}""").andExpect {
            status { isBadRequest() }; status { reason(containsString("namespace")) }
        }
        assertEquals("local", store.get())
    }

    @Test
    fun `PUT kubernetes is accepted once the Smith hosting is kubernetes with its settings, and publishes the setting event`() {
        smith.set(k8s)
        EventRecorder(events).use { rec ->
            put("""{"hosting": "kubernetes"}""").andExpect { status { isOk() }; jsonPath("$.hosting") { value("kubernetes") } }
            val frames = rec.of("setting", Duration.ofSeconds(1)).filter { it.str("name") == FirstMateHosting.HOSTING }
            assertEquals(listOf("kubernetes"), frames.map { it.str("value") })
        }
        assertEquals("kubernetes", store.get())
        mvc.get("/settings/first-mate-hosting").andExpect { jsonPath("$.hosting") { value("kubernetes") } }
    }

    @Test
    fun `PUT of another key, a non-string or another value is a 400 naming hosting and changes nothing`() {
        smith.set(k8s)
        for (body in listOf("""{"foo": "x"}""", """{"hosting": 1}""", """{"hosting": "cloud"}""", """{"hosting": "local", "foo": 1}""", "{}")) {
            put(body).andExpect { status { isBadRequest() }; status { reason(containsString("hosting")) } }
        }
        assertEquals("local", store.get())
    }

    @Test
    fun `PUT local is accepted even while Smith hosting is docker`() {
        put("""{"hosting": "local"}""").andExpect { status { isOk() }; jsonPath("$.hosting") { value("local") } }
        assertEquals("local", store.get())
        assertTrue(jdbc.sql("SELECT COUNT(*) FROM engine.engine_setting WHERE name = :n").param("n", FirstMateHosting.HOSTING).query(Int::class.java).single() == 1)
    }
}

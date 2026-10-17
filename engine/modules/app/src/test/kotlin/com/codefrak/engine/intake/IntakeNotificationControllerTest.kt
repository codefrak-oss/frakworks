package com.codefrak.engine.intake

import com.codefrak.engine.github.ReadFakes
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals

/** The host-side watcher's two calls over MockMvc: `GET /intake/notifications` and `POST /intake/notifications/{id}/delivered`. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ReadFakes::class)
class IntakeNotificationControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var notifications: IntakeNotifications
    @Autowired lateinit var jdbc: JdbcClient

    @BeforeEach
    fun reset() { jdbc.sql("DELETE FROM engine.intake_notification").update() }

    private fun delivered(id: Long, body: String) =
        mvc.post("/intake/notifications/$id/delivered") { contentType = MediaType.APPLICATION_JSON; content = body }

    @Test
    fun `GET lists what waits, oldest first, with the message to relay, and a delivery takes it off the list`() {
        mvc.get("/intake/notifications").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(0) }
        }
        notifications.queue("intake-a", "first-mate", "acme/tickets", 928, "https://github.com/acme/tickets/issues/928")
        notifications.queue("intake-b", "frakworks-c2", "acme/tickets", 929, "https://github.com/acme/tickets/issues/929")
        mvc.get("/intake/notifications").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }
            jsonPath("$[0].intake") { value("intake-a") }
            jsonPath("$[0].session") { value("first-mate") }
            jsonPath("$[0].ticket") { value(928) }
            jsonPath("$[0].ticketUrl") { value("https://github.com/acme/tickets/issues/928") }
            jsonPath("$[0].message") { value("Intake intake-a was refined and filed as ticket #928 in acme/tickets: https://github.com/acme/tickets/issues/928. " +
                "Tell the user, in one line, that the ticket you created is #928, with #928 a markdown link to that url.") }
            jsonPath("$[0].deliveredAt") { doesNotExist() }
            jsonPath("$[1].intake") { value("intake-b") }
        }
        val first = notifications.pending().first()
        delivered(first.id, """{"by":"mac","result":"msg_id: abc"}""").andExpect {
            status { isOk() }
            jsonPath("$.id") { value(first.id) }
            jsonPath("$.deliveredBy") { value("mac") }
            jsonPath("$.result") { value("msg_id: abc") }
            jsonPath("$.deliveredAt") { exists() }
        }
        mvc.get("/intake/notifications").andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].intake") { value("intake-b") }
        }
    }

    @Test
    fun `a second delivery is 409, an unknown id 404, a blank watcher 400`() {
        notifications.queue("intake-a", "first-mate", "acme/tickets", 928, "https://github.com/acme/tickets/issues/928")
        val id = notifications.pending().single().id
        delivered(id, """{"by":"mac","result":"msg_id: abc"}""").andExpect { status { isOk() } }
        delivered(id, """{"by":"other-mac","result":"msg_id: def"}""").andExpect { status { isConflict() } }
        assertEquals("mac", notifications.get(id)?.deliveredBy, "the first report stands")
        delivered(id + 1000, """{"by":"mac","result":"x"}""").andExpect { status { isNotFound() } }
        delivered(id, """{"by":" ","result":"x"}""").andExpect { status { isBadRequest() } }
    }
}

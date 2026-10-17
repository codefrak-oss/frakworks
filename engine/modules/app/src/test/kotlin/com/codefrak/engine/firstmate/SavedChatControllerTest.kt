package com.codefrak.engine.firstmate

import com.codefrak.engine.github.ReadFakes
import org.junit.jupiter.api.AfterEach
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `GET`/`PUT`/`DELETE /saved-chats` over MockMvc, against the shared H2. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ReadFakes::class)
class SavedChatControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var chats: SavedChats
    @Autowired lateinit var jdbc: JdbcClient

    // The H2 is shared with the other Spring tests: leave no saved chats behind.
    @AfterEach
    fun reset() { jdbc.sql("DELETE FROM engine.saved_chat").update() }

    private val a = "0b1e2f3a-0000-4000-8000-00000000000a"
    private val b = "0b1e2f3a-0000-4000-8000-00000000000b"

    private fun put(id: String, body: String) = mvc.put("/saved-chats/$id") { contentType = MediaType.APPLICATION_JSON; content = body }

    private fun body(title: String = "Why is the build host hot?", messages: String = MESSAGES, extra: String = "") =
        """{"title":"$title","startedAt":"2026-09-27T10:00:00Z","repo":"acme/app","tier":5,"claudeSessionId":"sess-1","savedFrom":"mac-1","messages":$messages$extra}"""

    private fun count() = jdbc.sql("SELECT COUNT(*) FROM engine.saved_chat").query(Long::class.java).single()

    @Test
    fun `PUT creates with 201, a second PUT of the same id updates the one row with 200`() {
        put(a, body()).andExpect {
            status { isCreated() }
            jsonPath("$.id") { value(a) }
            jsonPath("$.title") { value("Why is the build host hot?") }
            jsonPath("$.turns") { value(2) }
            jsonPath("$.messageCount") { value(3) }
            jsonPath("$.claudeSessionId") { value("sess-1") }
            jsonPath("$.startedAt") { value("2026-09-27T10:00Z") }
        }
        val first = assertNotNull(chats.get(a)).savedAt
        Thread.sleep(5)
        put(a, body(title = "Renamed", messages = """[{"role":"user","text":"only","at":1}]""")).andExpect {
            status { isOk() }
            jsonPath("$.title") { value("Renamed") }
            jsonPath("$.messageCount") { value(1) }
        }
        assertEquals(1, count())
        val second = assertNotNull(chats.get(a))
        assertEquals("Renamed", second.title)
        assertTrue(second.savedAt.isAfter(first))
    }

    @Test
    fun `GET lists newest saved first without messages`() {
        put(a, body(title = "older")).andExpect { status { isCreated() } }
        Thread.sleep(5)
        put(b, body(title = "newer")).andExpect { status { isCreated() } }
        mvc.get("/saved-chats").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }
            jsonPath("$[0].title") { value("newer") }
            jsonPath("$[1].title") { value("older") }
            jsonPath("$[0].messages") { doesNotExist() }
            jsonPath("$[0].savedAt") { exists() }
        }
    }

    @Test
    fun `GET one answers the transcript in order, and 404 when unknown`() {
        put(a, body()).andExpect { status { isCreated() } }
        mvc.get("/saved-chats/$a").andExpect {
            status { isOk() }
            jsonPath("$.messages.length()") { value(3) }
            jsonPath("$.messages[0].text") { value("Why is the build host hot?") }
            jsonPath("$.messages[1].role") { value("assistant") }
            jsonPath("$.messages[2].images") { value(1) }
        }
        mvc.get("/saved-chats/$b").andExpect { status { isNotFound() }; jsonPath("$.error") { exists() } }
    }

    @Test
    fun `DELETE removes the row, and 404 when unknown`() {
        put(a, body()).andExpect { status { isCreated() } }
        mvc.delete("/saved-chats/$a").andExpect { status { isNoContent() } }
        mvc.get("/saved-chats").andExpect { jsonPath("$.length()") { value(0) } }
        mvc.delete("/saved-chats/$a").andExpect { status { isNotFound() } }
    }

    @Test
    fun `PUT refuses a malformed body with a 400 and leaves the stored rows as they were`() {
        put(a, body()).andExpect { status { isCreated() } }
        val before = assertNotNull(chats.get(a))
        val bad = listOf(
            body(messages = "[]"),
            body(messages = """[{"role":"robot","text":"x","at":1}]"""),
            body(messages = """[{"role":"user","text":5,"at":1}]"""),
            body(messages = """[{"role":"user","text":"x"}]"""),
            body(messages = """[{"role":"user","text":"x","at":1,"extra":1}]"""),
            body(messages = """[{"role":"user","text":"x","at":1,"images":"two"}]"""),
            body(messages = "\"no\""),
            body(messages = (1..201).joinToString(",", "[", "]") { """{"role":"user","text":"x","at":$it}""" }),
            body(title = ""),
            body(title = "   "),
            body(title = "x".repeat(201)),
            body(extra = ""","other":1"""),
            body().replace("\"tier\":5", "\"tier\":\"5\""),
            body().replace("\"tier\":5", "\"tier\":5.5"),
            body(messages = """[{"role":"user","text":"${"x".repeat(1024 * 1024)}","at":1}]"""),
            "[1]", "6", "not json",
        )
        bad.forEach { put(a, it).andExpect { status { isBadRequest() } } }
        put("not-a-uuid", body()).andExpect { status { isBadRequest() } }
        assertEquals(1, count())
        assertEquals(before, chats.get(a))
    }

    private companion object {
        const val MESSAGES = """[{"role":"user","text":"Why is the build host hot?","at":1790000000000},""" +
            """{"role":"assistant","text":"Its fans.","at":1790000001000},{"role":"user","text":"See this","at":1790000002000,"images":1}]"""
    }
}

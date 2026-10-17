package com.codefrak.engine.intake

import com.codefrak.engine.TestTokens
import com.codefrak.engine.smith.SandboxStatus
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.smith.SmithSandbox
import com.codefrak.engine.smith.SmithSpec
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** `POST /ticket/{owner}/{repo}/{n}/amend`'s Smith-refusal branch, by its source address. */
@SpringBootTest
class TicketAmendControllerTest {
    @Autowired lateinit var amender: TicketAmender

    private class FakeSandbox(private val smith: SmithCaller?, private val down: Boolean = false) : SmithSandbox {
        override fun start(spec: SmithSpec): String = error("unused")
        override fun status(handle: String): SandboxStatus = error("unused")
        override fun logs(handle: String, maxLines: Int): String = ""
        override fun output(handle: String, path: String): ByteArray? = null
        override fun remove(handle: String) {}
        override fun runAt(address: String) = if (down) error("docker is down") else smith
    }

    private fun from(address: String) = MockHttpServletRequest().apply { remoteAddr = address }

    @Test
    fun `a Smith sandbox may not call amend_ticket`() {
        val controller = TicketAmendController(amender, FakeSandbox(SmithCaller("run-1", 1, "o/r")))
        val e = assertFailsWith<ResponseStatusException> {
            controller.amend("acme", "widgets", 5, AmendTicketRequest("x"), from("10.42.0.9"))
        }
        assertEquals(HttpStatus.FORBIDDEN, e.statusCode)
        assertEquals("a Smith sandbox (run run-1) may not call amend_ticket: tickets are the user's to amend", e.reason)
    }

    @Test
    fun `the sandbox provider failing to say is a 503`() {
        val controller = TicketAmendController(amender, FakeSandbox(null, down = true))
        val e = assertFailsWith<ResponseStatusException> {
            controller.amend("acme", "widgets", 5, AmendTicketRequest("x"), from("10.42.0.9"))
        }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.statusCode)
    }
}

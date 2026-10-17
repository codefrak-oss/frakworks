package com.codefrak.engine

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@WebMvcTest(EchoController::class)
class EchoControllerTest(@Autowired val mvc: MockMvc) {
    @Test
    fun `echoes the message back`() {
        mvc.get("/echo?message=hi there").andExpect {
            status { isOk() }
            jsonPath("$.message") { value("hi there") }
        }
    }

    @Test
    fun `missing message is a 400`() {
        mvc.get("/echo").andExpect { status { isBadRequest() } }
    }
}

// build-time measurement 1

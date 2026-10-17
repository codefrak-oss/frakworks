package com.codefrak.engine

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@WebMvcTest(HealthController::class)
class HealthControllerTest(@Autowired val mvc: MockMvc) {
    @Test
    fun `reports ok`() {
        mvc.get("/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("ok") }
        }
    }
}

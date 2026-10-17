package com.codefrak.engine.db

import org.junit.jupiter.api.Test
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.sql.SQLTransientConnectionException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A request that found the pool empty answers 503 at once (#1650); anything else goes to the next resolver. */
class PoolExhaustedTest {
    private val resolver = PoolExhausted()

    @Test
    fun `Hikari's timeout, however wrapped, is a 503 with Retry-After`() {
        val hikari = SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out after 5000ms")
        for (ex in listOf(CannotGetJdbcConnectionException("Failed to obtain JDBC Connection", hikari),
                RuntimeException("ENGINE-03083 exception while executing Batch Database Operations", IllegalStateException(hikari)))) {
            val response = MockHttpServletResponse()
            assertNotNull(resolver.resolveException(MockHttpServletRequest("GET", "/tiers"), response, null, ex))
            assertEquals(503, response.status)
            assertEquals("5", response.getHeader("Retry-After"))
        }
    }

    @Test
    fun `any other exception is not this resolver's`() {
        val response = MockHttpServletResponse()
        assertNull(resolver.resolveException(MockHttpServletRequest("GET", "/tiers"), response, null, IllegalStateException("boom")))
        assertEquals(200, response.status)
    }
}

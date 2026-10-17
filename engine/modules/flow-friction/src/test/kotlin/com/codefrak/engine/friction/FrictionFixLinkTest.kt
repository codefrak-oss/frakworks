package com.codefrak.engine.friction

import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** [fixLink]'s body check, pulled out of [FrictionController.fix] (#2335). */
class FrictionFixLinkTest {
    private fun badRequest(fix: Any): ResponseStatusException = assertFailsWith<ResponseStatusException> { fixLink(fix) }.also {
        assertEquals(HttpStatus.BAD_REQUEST, it.statusCode)
    }

    @Test
    fun `a valid link trims repo`() {
        assertEquals("acme/a" to 7L, fixLink(mapOf("repo" to " acme/a ", "issue" to 7)))
    }

    @Test
    fun `fix not a Map`() {
        assertEquals("fix is {\"repo\": \"owner/name\", \"issue\": n}, or null", badRequest("acme/a").reason)
    }

    @Test
    fun `repo missing`() {
        assertEquals("fix.repo is owner/name: null", badRequest(mapOf("issue" to 7)).reason)
    }

    @Test
    fun `repo malformed fails FULL_NAME`() {
        assertEquals("fix.repo is owner/name: not-a-repo", badRequest(mapOf("repo" to "not-a-repo", "issue" to 7)).reason)
    }

    @Test
    fun `issue missing`() {
        assertEquals("fix.issue is a ticket number: null", badRequest(mapOf("repo" to "acme/a")).reason)
    }

    @Test
    fun `issue 0 is below 1`() {
        assertEquals("fix.issue is a ticket number: 0", badRequest(mapOf("repo" to "acme/a", "issue" to 0)).reason)
    }

    @Test
    fun `a fractional issue, a Double, is refused`() {
        assertEquals("fix.issue is a ticket number: 1.5", badRequest(mapOf("repo" to "acme/a", "issue" to 1.5)).reason)
    }
}

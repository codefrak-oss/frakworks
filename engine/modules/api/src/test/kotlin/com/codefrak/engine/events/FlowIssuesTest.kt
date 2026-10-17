package com.codefrak.engine.events

import com.codefrak.engine.smith.Vars
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** [FlowIssues.ticketIn], the pure resolver shared with `Needs.issueOf` (#1990). */
class FlowIssuesTest {
    @Test
    fun `repo and issue win over issueUrl`() {
        val vars = mapOf(Vars.REPO to "codefrak-oss/frakworks", Vars.ISSUE to 42L, "issueUrl" to "https://github.com/other/repo/issues/7")
        assertEquals("codefrak-oss/frakworks" to 42L, FlowIssues.ticketIn(vars))
    }

    @Test
    fun `only issueUrl set is matched by the regex`() {
        val vars = mapOf("issueUrl" to "https://github.com/codefrak-oss/frakworks/issues/1990")
        assertEquals("codefrak-oss/frakworks" to 1990L, FlowIssues.ticketIn(vars))
    }

    @Test
    fun `only ticketUrl set is matched by the regex`() {
        val vars = mapOf("ticketUrl" to "https://github.com/codefrak-oss/frakworks/issues/1987")
        assertEquals("codefrak-oss/frakworks" to 1987L, FlowIssues.ticketIn(vars))
    }

    @Test
    fun `a second key pair is found when the first is missing`() {
        val vars = mapOf("doctorRepo" to "codefrak-oss/frakworks", "doctorIssue" to 99L)
        val keys = listOf(Vars.REPO to Vars.ISSUE, "doctorRepo" to "doctorIssue")
        assertEquals("codefrak-oss/frakworks" to 99L, FlowIssues.ticketIn(vars, keys))
    }

    @Test
    fun `neither keys nor a url answers null`() {
        assertNull(FlowIssues.ticketIn(emptyMap()))
    }

    @Test
    fun `a non-string issueUrl is ignored`() {
        val vars = mapOf("issueUrl" to 12345)
        assertNull(FlowIssues.ticketIn(vars))
    }
}

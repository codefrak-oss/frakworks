package com.codefrak.engine.github

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The GitHub issue link [issueUrl] builds, and [parseIssueUrl], its inverse. */
class IssueUrlTest {
    @Test
    fun `builds the GitHub issue URL for a repo and number`() {
        assertEquals("https://github.com/o/r/issues/42", issueUrl("o/r", 42))
    }

    @Test
    fun `parseIssueUrl round-trips a URL issueUrl built`() {
        assertEquals("o/r" to 42L, parseIssueUrl(issueUrl("o/r", 42)))
    }

    @Test
    fun `parseIssueUrl answers null for a URL with a trailing path`() {
        assertNull(parseIssueUrl("https://github.com/o/r/issues/42/comments"))
    }

    @Test
    fun `parseIssueUrl answers null for a pull request URL`() {
        assertNull(parseIssueUrl("https://github.com/o/r/pull/42"))
    }

    @Test
    fun `parseIssueUrl answers null for a non-GitHub host`() {
        assertNull(parseIssueUrl("https://example.com/o/r/issues/42"))
    }

    @Test
    fun `parseIssueUrl answers null for an empty string`() {
        assertNull(parseIssueUrl(""))
    }
}

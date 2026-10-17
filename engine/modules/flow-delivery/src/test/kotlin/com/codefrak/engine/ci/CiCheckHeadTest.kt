package com.codefrak.engine.ci

import com.codefrak.engine.github.CreatedPull
import com.codefrak.engine.github.GitHubPulls
import com.codefrak.engine.github.OpenPull
import com.codefrak.engine.smith.Vars
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A fake [GitHubPulls] where only [headSha] does anything; every other member is unused by [followHead]. */
private class FakeGitHubPulls(private val head: String? = null, private val failure: Throwable? = null) : GitHubPulls {
    override fun defaultBranch(repo: String) = error("not used")
    override fun open(repo: String, head: String, base: String, title: String, body: String): CreatedPull = error("not used")
    override fun openOnHead(repo: String, head: String): List<OpenPull> = error("not used")
    override fun merge(repo: String, number: Long, title: String): String = error("not used")
    override fun closePull(repo: String, number: Long) = error("not used")
    override fun reopenPull(repo: String, number: Long) = error("not used")
    override fun comment(repo: String, number: Long, body: String) = error("not used")
    override fun headSha(repo: String, number: Long): String? {
        failure?.let { throw it }
        return head
    }
}

/** [followHead]'s four cases, on a plain map and a recording setter. Plain JUnit. */
class CiCheckHeadTest {
    private val set = mutableListOf<Pair<String, Any?>>()
    private val setter: (String, Any?) -> Unit = { k, v -> set += k to v }

    @Test
    fun `no pr variable returns the stored head and sets nothing`() {
        val vars = mapOf(Vars.HEAD_SHA to "abc123")
        val result = followHead(FakeGitHubPulls(head = "def456"), "codefrak-oss/frakworks", vars, setter)
        assertEquals("abc123", result)
        assertTrue(set.isEmpty())
    }

    @Test
    fun `headSha throwing returns the stored head and sets nothing`() {
        val vars = mapOf(Vars.HEAD_SHA to "abc123", Vars.PR to 7L)
        val result = followHead(FakeGitHubPulls(failure = RuntimeException("502 Bad Gateway")), "codefrak-oss/frakworks", vars, setter)
        assertEquals("abc123", result)
        assertTrue(set.isEmpty())
    }

    @Test
    fun `the same head returns it and sets nothing`() {
        val vars = mapOf(Vars.HEAD_SHA to "abc123", Vars.PR to 7L)
        val result = followHead(FakeGitHubPulls(head = "abc123"), "codefrak-oss/frakworks", vars, setter)
        assertEquals("abc123", result)
        assertTrue(set.isEmpty())
    }

    @Test
    fun `a moved head returns the new sha and sets headSha and ciRetriggers`() {
        val vars = mapOf(Vars.HEAD_SHA to "abc123", Vars.PR to 7L)
        val result = followHead(FakeGitHubPulls(head = "def456"), "codefrak-oss/frakworks", vars, setter)
        assertEquals("def456", result)
        assertEquals(listOf<Pair<String, Any?>>(Vars.HEAD_SHA to "def456", Vars.CI_RETRIGGERS to null), set)
    }

    @Test
    fun `no stored head fails with no pushed head to check`() {
        val e = assertFailsWith<IllegalStateException> { followHead(FakeGitHubPulls(), "codefrak-oss/frakworks", emptyMap(), setter) }
        assertEquals("no pushed head to check", e.message)
        assertTrue(set.isEmpty())
    }
}

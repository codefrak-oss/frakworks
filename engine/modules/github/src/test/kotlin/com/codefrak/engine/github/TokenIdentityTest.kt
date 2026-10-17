package com.codefrak.engine.github

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Plain JUnit: `GET /user` stubbed by the fake; a login is a field, never a property or a row. */
class TokenIdentityTest {
    private val repo = "acme/widgets"

    /** `acme/widgets` has a token; no other repo does. */
    private val own = RepoTokens { if (it == repo) "own-tok" else null }

    @Test
    fun `a repo's token's login is what GitHub says`() {
        assertEquals("fleet-bot", GitHubTokenIdentity(FakeIssueDetails(), own).login(repo))
    }

    @Test
    fun `a commit is by the token's account, at its noreply address, and by its login when it has no name`() {
        assertEquals(CommitIdentity("Fleet Bot", "7+fleet-bot@users.noreply.github.com"), GitHubTokenIdentity(FakeIssueDetails(), own).commitIdentity(repo))
        for (none in listOf(null, " "))
            assertEquals("fleet-bot", GitHubTokenIdentity(FakeIssueDetails().apply { name = none }, own).commitIdentity(repo).name)
    }

    @Test
    fun `no commit identity stands in for a token GitHub will not name, or for a repo without one`() {
        assertFailsWith<IllegalStateException> { GitHubTokenIdentity(FakeIssueDetails().apply { login = null }, own).commitIdentity(repo) }
        assertFailsWith<IllegalStateException> { GitHubTokenIdentity(FakeIssueDetails(), own).commitIdentity("acme/other") }
    }

    @Test
    fun `a failed GET user leaves it null and does not throw`() {
        val identity = GitHubTokenIdentity(FakeIssueDetails().apply { login = null }, own)
        assertNull(identity.login(repo))
        assertNull(identity.scopes(repo))
    }

    @Test
    fun `a repo without a token has no login, and GitHub is not asked`() {
        val details = FakeIssueDetails()
        val identity = GitHubTokenIdentity(details, own)
        identity.resolve()
        assertNull(identity.login("acme/other"))
        assertNull(identity.scopes("acme/other"))
        assertEquals(emptyList(), details.asked, "GitHub is not asked without a token")
    }

    @Test
    fun `the scopes are what the header says, and unknown without one`() {
        val details = FakeIssueDetails().apply { scopesHeader = "repo, workflow" }
        val identity = GitHubTokenIdentity(details, own)
        assertEquals(setOf("repo", "workflow"), identity.scopes(repo))
        details.scopesHeader = "repo"
        identity.resolve()
        assertEquals(setOf("repo"), identity.scopes(repo))
        details.scopesHeader = null
        identity.resolve()
        assertNull(identity.scopes(repo), "a fine-grained token sends no header")
    }

    @Test
    fun `a token is asked about once`() {
        val details = FakeIssueDetails().apply { scopesHeader = "repo" }
        val identity = GitHubTokenIdentity(details, own)
        assertEquals(setOf("repo"), identity.scopes(repo))
        identity.scopes(repo); identity.login(repo)
        assertEquals(listOf("own-tok"), details.asked, "one GET /user per token")
    }

    @Test
    fun `a failed lookup is not remembered, so the next ask tries again`() {
        val details = FakeIssueDetails().apply { login = null }
        val identity = GitHubTokenIdentity(details, own)
        assertNull(identity.scopes(repo))
        details.login = "fleet-bot"
        assertEquals("fleet-bot", identity.login(repo))
        assertEquals(2, details.asked.size)
    }
}

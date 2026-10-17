package com.codefrak.engine.github

import com.codefrak.engine.repos.TargetRepo
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `user`, `permission` and `closedIssues`: the only three either test in this file or [TokenIdentityTest] calls. */
class FakeIssueDetails : IssueDetails {
    var login: String? = "fleet-bot"
    var name: String? = "Fleet Bot"
    var scopesHeader: String? = null
    val asked = mutableListOf<String>()
    override fun user(token: String): TokenUser {
        asked += token
        return TokenUser(login ?: error("GitHub 401: Bad credentials"), TokenScopes.parse(scopesHeader), 7, name)
    }
    val permissions = mutableMapOf<Pair<String, String>, String>()
    val permissionAsks = mutableListOf<Pair<String, String>>()
    val failing = mutableSetOf<String>()
    override fun permission(repo: String, login: String): String? {
        permissionAsks += repo to login
        check(repo !in failing) { "GitHub 403" }
        return permissions[repo to login]
    }
    override fun closedIssues(repo: String, limit: Int): List<ClosedIssue> = emptyList()
    override fun ticket(repo: String, number: Long): TicketDetail? = throw UnsupportedOperationException("ticket")
    override fun restComments(repo: String, number: Long): List<Map<String, Any?>>? = throw UnsupportedOperationException("restComments")
    override fun restComment(repo: String, id: Long): Map<String, Any?>? = throw UnsupportedOperationException("restComment")
}

/** Plain JUnit: the collaborator permission stubbed by the fake, held for the repo's scan interval. */
class RepoPermissionsTest {
    private val repo = TargetRepo("acme/widgets", OffsetDateTime.now(), hasToken = true, scanIntervalMinutes = 5)
    private val bare = TargetRepo("acme/bare", OffsetDateTime.now())
    private val own = RepoTokens { if (it == repo.fullName) "own-tok" else null }
    private val details = FakeIssueDetails().apply {
        permissions[repo.fullName to "fleet-bot"] = "write"
        permissions[repo.fullName to "lundmatt1"] = "admin"
    }
    private var now = Instant.parse("2026-10-07T12:00:00Z")
    private val permissions = RepoPermissions(details, TokenIdentity(details, own)).apply {
        clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant() = now
        }
    }

    @Test
    fun `a repo with a token reports its account's permission, one without reports null and GitHub is not asked`() {
        assertEquals("write", permissions.token(repo))
        assertNull(permissions.token(bare))
        assertNull(permissions.caller(bare, "lundmatt1"))
        assertEquals(listOf(repo.fullName to "fleet-bot"), details.permissionAsks)
    }

    @Test
    fun `the caller's permission is per login, and an unknown one is null`() {
        assertEquals("admin", permissions.caller(repo, "lundmatt1"))
        assertNull(permissions.caller(repo, "stranger"))
        assertNull(permissions.caller(repo, "../evil"), "not a login: GitHub is not asked")
        assertEquals(2, details.permissionAsks.size)
    }

    @Test
    fun `values are held for the scan interval, then asked again`() {
        permissions.token(repo); permissions.caller(repo, "lundmatt1")
        permissions.token(repo); permissions.caller(repo, "LundMatt1")
        assertEquals(2, details.permissionAsks.size)
        now += Duration.ofMinutes(5)
        permissions.token(repo)
        assertEquals(3, details.permissionAsks.size)
    }

    @Test
    fun `a GitHub refusal is null, not a failed read`() {
        details.failing += repo.fullName
        assertNull(permissions.token(repo))
        assertNull(permissions.caller(repo, "lundmatt1"))
    }
}

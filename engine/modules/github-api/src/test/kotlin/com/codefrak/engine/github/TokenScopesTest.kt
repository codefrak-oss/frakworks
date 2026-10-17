package com.codefrak.engine.github

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `X-OAuth-Scopes` as GitHub sends it for a classic token; none at all for a fine-grained one. */
class TokenScopesTest {
    @Test
    fun `repo and workflow`() = assertEquals(setOf("repo", "workflow"), TokenScopes.parse("repo, workflow"))

    @Test
    fun `repo only`() = assertEquals(setOf("repo"), TokenScopes.parse("repo"))

    @Test
    fun `no header is unknown`() = assertNull(TokenScopes.parse(null))

    @Test
    fun `an empty header is a token with no scopes`() = assertEquals(emptySet(), TokenScopes.parse(""))
}

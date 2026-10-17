package com.codefrak.engine.mcp

import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.tier.SeededTiers
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** read_tokens, mint_token and revoke_token over [FakeEngine] (engine/README.md, "Tokens"): First mate's only, and never the caller's own token. */
class TokenToolTest {
    private val engine = FakeEngine()
    private val mcp = FrakworksMcp(engine, SeededTiers.table(), FakeFrictionLog())
    private val mate = McpCaller.Nobody("127.0.0.1", "someone")
    private val smith = McpCaller.Smith(SmithCaller("run-3", 3, "acme/widgets"))

    private fun call(tool: String, args: Map<String, Any?>, caller: McpCaller = mate) = mcp.call(tool, args, caller)
    private fun body(r: McpToolResult): Map<*, *> = mcpJson.readValue(r.text, Map::class.java)
    private fun error(r: McpToolResult): String { assertTrue(r.isError, r.text); return body(r)["error"] as String }

    @Test
    fun `mint_token mints for a trusted login as the caller, answers the token once, and read_tokens lists the row without it`() {
        engine.trustedLogins += "octocat"
        val r = call("mint_token", mapOf("login" to "octocat", "name" to "laptop", "expiresInDays" to 30))
        assertFalse(r.isError, r.text)
        val minted = body(r)
        assertEquals("octocat", minted["login"]); assertEquals("laptop", minted["name"]); assertEquals("tok-1", minted["id"])
        assertTrue((minted["token"] as String).startsWith("frk_"))
        assertContains(minted["note"] as String, "1Password")
        assertEquals(listOf("mint octocat laptop 30 by someone"), engine.tokenCalls)

        val listed = body(call("read_tokens", emptyMap()))
        val rows = listed["tokens"] as List<*>
        assertEquals(1, rows.size)
        val row = rows.single() as Map<*, *>
        assertEquals(setOf("id", "login", "name", "prefix", "createdAt", "createdBy", "expiresAt", "lastUsedAt", "revokedAt"), row.keys)
        assertFalse("frk_" in call("read_tokens", emptyMap()).text)
        assertEquals("read_tokens takes no arguments, not \"x\"", error(call("read_tokens", mapOf("x" to 1))))
    }

    @Test
    fun `mint_token refuses an untrusted login, a bad name or expiry, and a caller without a login`() {
        assertEquals("@stranger is not a trusted login: trust it first (add_access), then mint; nothing was changed",
            error(call("mint_token", mapOf("login" to "stranger", "name" to "x"))))
        engine.trustedLogins += "octocat"
        assertEquals("name is non-blank and at most 100 characters", error(call("mint_token", mapOf("login" to "octocat", "name" to " "))))
        assertEquals("expiresInDays is a whole number of days from 1 to 3650, or absent", error(call("mint_token", mapOf("login" to "octocat", "name" to "x", "expiresInDays" to 0))))
        assertEquals("login is a GitHub login (letters, digits and dashes, at most 39)", error(call("mint_token", mapOf("login" to "not a login", "name" to "x"))))
        assertContains(error(call("mint_token", mapOf("login" to "octocat", "name" to "x"), McpCaller.Nobody("127.0.0.1"))), "a GitHub login is needed to mint a token")
        assertEquals(emptyList(), engine.tokenCalls)
    }

    @Test
    fun `once a token exists a write needs the call to carry one, and revoke_token refuses the token it carries`() {
        engine.trustedLogins += "octocat"
        val first = body(call("mint_token", mapOf("login" to "octocat", "name" to "first")))
        val id = first["id"] as String
        assertContains(error(call("mint_token", mapOf("login" to "octocat", "name" to "second"))), "a bearer token is needed to mint a token")
        assertContains(error(call("revoke_token", mapOf("id" to id))), "a bearer token is needed to revoke a token")
        val withToken = McpCaller.Nobody("127.0.0.1", "someone", tokenId = id)
        val second = body(call("mint_token", mapOf("login" to "octocat", "name" to "second"), withToken))
        assertEquals("revoke_token refuses the token this call carries (fakepref): that would lock you out; nothing was changed",
            error(call("revoke_token", mapOf("id" to id), withToken)))
        assertEquals(mapOf("ok" to true, "id" to second["id"], "outcome" to "revoked"), body(call("revoke_token", mapOf("id" to second["id"]), withToken)))
        assertEquals("already revoked", body(call("revoke_token", mapOf("id" to second["id"]), withToken))["outcome"])
        assertEquals("no token nope; nothing was changed", error(call("revoke_token", mapOf("id" to "nope"), withToken)))
        assertEquals("revoke_token takes only \"id\", not \"login\"", error(call("revoke_token", mapOf("id" to "x", "login" to "y"), withToken)))
    }

    @Test
    fun `a Smith's call to any of the three is refused naming its run`() {
        for (tool in TokenTool.NAMES) {
            assertEquals("a Smith sandbox (run run-3) may not call $tool: engine tokens are First mate's to manage", error(call(tool, emptyMap(), smith)))
        }
        assertEquals(emptyList(), engine.tokenCalls)
        assertTrue(mcp.tools(mate).map { it.name }.containsAll(TokenTool.NAMES))
        assertTrue(mcp.tools(smith).none { it.name in TokenTool.NAMES })
    }
}

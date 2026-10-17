package com.codefrak.engine.mcp

import com.codefrak.engine.repos.EngineToken
import com.codefrak.engine.repos.LOGIN

/**
 * read_tokens, mint_token and revoke_token, on [FrakworksMcp] for First mate only: GET, POST and DELETE /tokens,
 * in-process ([FrakworksEngine.tokens] and on; engine/README.md, "Tokens"). The caller needs a login, and once a
 * token exists a write needs the call to carry a token of its own (the HTTP endpoints' gate; before that, the
 * bootstrap door is open). revoke_token refuses the token the call itself carries, so First mate cannot lock the
 * user out; mint_token answers the token once, to be put in 1Password, never stored or logged by the engine.
 */
class TokenTool(private val engine: FrakworksEngine) {
    companion object {
        val NAMES = listOf("read_tokens", "mint_token", "revoke_token")

        private val loginProp = mapOf("type" to "string", "description" to "The trusted GitHub login the token is bound to, e.g. octocat (no @).")

        val TOOLS: List<McpTool> = listOf(
            McpTool("read_tokens",
                "Read-only: the engine's bearer tokens, as GET /tokens lists them, newest first, never a token or its hash: " +
                    "each id, login, name, prefix (its first 8 characters after frk_), createdAt, createdBy, expiresAt, lastUsedAt, revokedAt.",
                obj()),
            McpTool("mint_token",
                "Mint a bearer token (POST /tokens) bound to a trusted login, when the user asks to, recorded as by the user's login. " +
                    "Answers the token once: tell the user to store it in 1Password; the engine keeps only its hash.",
                obj("login" to loginProp,
                    "name" to prop("string", "What the token is for, e.g. 'laptop Claude Code' (at most 100 characters)."),
                    "expiresInDays" to prop("integer", "Days until it expires, 1 to 3650; absent: it never expires.", "minimum" to 1, "maximum" to 3650),
                    required = listOf("login", "name"))),
            McpTool("revoke_token",
                "Revoke a bearer token by id (DELETE /tokens/<id>), when the user asks to. Refused for the token this call " +
                    "itself carries: that would lock the user out.",
                obj("id" to prop("string", "The token's id, as read_tokens gives it."), required = listOf("id"))),
        )

        private fun noLogin(what: String) =
            McpToolResult.error("a GitHub login is needed to $what (a bearer token the engine issued, `Authorization: Bearer frk_...`)")

        private fun needsToken(what: String) =
            McpToolResult.error("a bearer token is needed to $what: this call carries none (the first token needs none; after it, every token call does)")

        fun view(t: EngineToken) = linkedMapOf<String, Any?>(
            "id" to t.id, "login" to t.login, "name" to t.name, "prefix" to t.prefix, "createdAt" to iso(t.createdAt),
            "createdBy" to t.createdBy, "expiresAt" to iso(t.expiresAt), "lastUsedAt" to iso(t.lastUsedAt), "revokedAt" to iso(t.revokedAt))
    }

    fun call(tool: String, args: Map<String, Any?>, caller: McpCaller.Nobody): McpToolResult = when (tool) {
        "read_tokens" -> readTokens(args)
        "mint_token" -> mintToken(caller, args)
        else -> revokeToken(caller, args)
    }

    private fun readTokens(args: Map<String, Any?>): McpToolResult {
        refuseOthers("read_tokens", args, emptyList())?.let { return it }
        return McpToolResult.of(linkedMapOf("tokens" to engine.tokens().map(::view)))
    }

    private fun mintToken(caller: McpCaller.Nobody, args: Map<String, Any?>): McpToolResult {
        refuseOthers("mint_token", args, listOf("login", "name", "expiresInDays"))?.let { return it }
        val by = login(caller) ?: return noLogin("mint a token")
        if (caller.tokenId == null && engine.anyActiveToken()) return needsToken("mint a token")
        val target = (args["login"] as? String)?.trim()?.removePrefix("@")
        if (target == null || !LOGIN.matches(target)) return McpToolResult.error("login is a GitHub login (letters, digits and dashes, at most 39)")
        if (!engine.trusted(target)) return McpToolResult.error("@$target is not a trusted login: trust it first (add_access), then mint; nothing was changed")
        val name = (args["name"] as? String)?.trim()
        if (name.isNullOrEmpty() || name.length > 100) return McpToolResult.error("name is non-blank and at most 100 characters")
        val days = if (given(args, "expiresInDays")) wholeNumberOf(args["expiresInDays"])?.takeIf { it in 1..3650 }?.toInt()
            ?: return McpToolResult.error("expiresInDays is a whole number of days from 1 to 3650, or absent") else null
        val minted = engine.mintToken(target, name, days, by)
        return McpToolResult.of(view(minted.row).also {
            it["token"] = minted.token
            it["note"] = "this is the only time the token is shown: store it in 1Password now; the engine keeps only its hash"
        })
    }

    private fun revokeToken(caller: McpCaller.Nobody, args: Map<String, Any?>): McpToolResult {
        refuseOthers("revoke_token", args, listOf("id"))?.let { return it }
        login(caller) ?: return noLogin("revoke a token")
        if (caller.tokenId == null && engine.anyActiveToken()) return needsToken("revoke a token")
        val id = (args["id"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return McpToolResult.error("id is the token's id, as read_tokens gives it")
        if (id == caller.tokenId) {
            val prefix = engine.tokens().firstOrNull { it.id == id }?.prefix ?: id
            return McpToolResult.error("revoke_token refuses the token this call carries ($prefix): that would lock you out; nothing was changed")
        }
        return when (engine.revokeToken(id)) {
            null -> McpToolResult.error("no token $id; nothing was changed")
            true -> McpToolResult.of(linkedMapOf("ok" to true, "id" to id, "outcome" to "revoked"))
            false -> McpToolResult.of(linkedMapOf("ok" to true, "id" to id, "outcome" to "already revoked", "note" to "token $id was already revoked; nothing was changed"))
        }
    }
}

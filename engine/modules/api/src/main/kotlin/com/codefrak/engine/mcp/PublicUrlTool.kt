package com.codefrak.engine.mcp

import com.codefrak.engine.settings.PublicUrl

/**
 * read_public_url (#2253): `GET /settings/public-url` in-process, the address users reach the engine and its web
 * version at. Unset is said plainly, never guessed. Read-only, for the same callers as [TierAccountsTool].
 */
class PublicUrlTool(private val store: PublicUrl) {
    companion object {
        const val NAME = "read_public_url"
        const val UNSET = "no public URL is set; set it with PUT /settings/public-url {\"publicUrl\": \"https://...\"}"

        val TOOL = McpTool(NAME,
            "Read-only: the engine's public URL (what GET /settings/public-url answers), {publicUrl}: the address " +
                "users reach the engine and its web version (/web) at, e.g. a Kubernetes Ingress host. When none is " +
                "set, publicUrl is null and `note` says so and how to set it; never guess localhost.",
            mapOf("type" to "object", "properties" to emptyMap<String, Any>(), "additionalProperties" to false))
    }

    fun call(args: Map<String, Any?>): McpToolResult {
        if (args.isNotEmpty()) return McpToolResult.error("$NAME takes no arguments, not ${args.keys.joinToString(", ") { mcpJson.writeValueAsString(it) }}")
        val url = store.get() ?: return McpToolResult.of(linkedMapOf("publicUrl" to null, "note" to UNSET))
        return McpToolResult.of(mapOf("publicUrl" to url))
    }
}

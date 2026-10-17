package com.codefrak.engine.accounts

import com.codefrak.engine.engineJson

/** Structural checks for an imported Codex CLI ChatGPT login file. Never echo its contents. */
object CodexAuthFile {
    const val MAX_BYTES = 64 * 1024

    /** A safe user-facing problem, or null if this is a usable ChatGPT-mode auth.json snapshot. */
    fun problem(contents: String): String? {
        if (contents.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return "auth.json is over $MAX_BYTES bytes"
        val root = runCatching { engineJson.readTree(contents) }.getOrNull()
            ?: return "auth.json is not valid JSON"
        if (!root.isObject) return "auth.json must be a JSON object"
        if (root.get("auth_mode")?.takeIf { it.isString }?.asString() != "chatgpt")
            return "auth.json must contain ChatGPT CLI sign-in state (auth_mode=chatgpt)"
        val tokens = root.get("tokens")
        if (tokens == null || !tokens.isObject) return "auth.json is missing its tokens object"
        for (field in listOf("access_token", "refresh_token", "id_token", "account_id")) {
            val value = tokens.get(field)
            if (value == null || !value.isString || value.asString().isBlank())
                return "auth.json is missing a non-empty $field"
        }
        return null
    }
}

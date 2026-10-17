package com.codefrak.engine.accounts

val PROVIDERS = setOf("anthropic", "openai", "jev", "codex")

/** A jev account's name and its key in the clear ([AiAccounts.jevCredential]); its `toString` leaves the key out. */
data class JevKey(val account: String, val key: String) {
    override fun toString() = "JevKey(account=$account)"
}

/**
 * The providers a Smith bills to. `jev` (TypeSafe's Jev, a decision-only
 * model) and `codex` (ChatGPT sign-in, not connected to runs yet) are stored
 * like the others but never run a Smith: the picker and limit gates pass over
 * them (engine/README.md, "AI accounts").
 */
val SMITH_PROVIDERS = setOf("anthropic", "openai")

/** The env var a Smith of that provider reads its token from. */
fun credentialEnv(provider: String) = when (provider) {
    "anthropic" -> "CLAUDE_CODE_OAUTH_TOKEN"
    "openai" -> "OPENAI_API_KEY"
    "jev", "codex" -> throw IllegalArgumentException("$provider accounts are not for Smiths")
    else -> throw IllegalArgumentException("unknown provider $provider")
}

/**
 * The start every credential of that provider has: an Anthropic Smith needs the OAuth token `claude setup-token` prints, not an API key.
 * No `jev` entry: TypeSafe documents no key prefix, so a Jev key gets only the empty and whitespace rules.
 */
val CREDENTIAL_PREFIX = mapOf("anthropic" to "sk-ant-oat01-", "openai" to "sk-")

/**
 * Why `token` cannot be a credential for `provider`, or null when it can. No
 * token has whitespace in it: a space or line break is almost always a paste
 * across a terminal's line wrap, which the provider answers with a 401 long
 * after the paste. Refused, not repaired, so the person pasting sees it.
 */
fun credentialProblem(provider: String, token: String): String? {
    if (token.isBlank()) return "token is empty"
    val space = token.indexOfFirst { it.isWhitespace() }
    if (space >= 0) return "token has whitespace at character ${space + 1} of ${token.length} (a copy across a line wrap?); " +
        "paste it as one unbroken line"
    val prefix = CREDENTIAL_PREFIX[provider]
    if (prefix != null && !token.startsWith(prefix)) return "a $provider token starts with $prefix" +
        if (provider == "anthropic") " (the long-lived token from claude setup-token, not an API key)" else ""
    return null
}

/** The provider a model id bills to: `claude-*` is Anthropic, `gpt-*` is OpenAI. */
fun providerOf(model: String) = when {
    model.startsWith("claude-") -> "anthropic"
    model.startsWith("gpt-") -> "openai"
    else -> throw IllegalArgumentException("no provider for model $model")
}

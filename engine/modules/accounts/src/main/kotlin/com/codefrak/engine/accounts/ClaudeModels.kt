package com.codefrak.engine.accounts

/** The short model names a row takes, and the lookups between them and the full Claude model id a Smith runs with. */
object ClaudeModels {
    /** The short model names a row takes, each to the full Claude model id a Smith runs with. */
    val CLAUDE_MODELS = mapOf(
        "haiku" to "claude-haiku-4-5-20251001",
        "sonnet" to "claude-sonnet-5",
        "opus" to "claude-opus-5-5",
        "fable" to "claude-fable-5-1",
    )

    /** A short name or full id to its short name; null for a model the map does not know. */
    fun shortName(model: String): String? =
        model.takeIf { it in CLAUDE_MODELS } ?: CLAUDE_MODELS.entries.firstOrNull { it.value == model }?.key

    fun fullId(short: String): String = CLAUDE_MODELS[short] ?: short
}

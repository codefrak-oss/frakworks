package com.codefrak.engine.refactor

import tools.jackson.databind.JsonNode

/** One proposal as the Smith wrote it; null for one that is not `{key, title, body, lines}` with a slug key, non-blank texts and a non-negative count. */
internal data class RefactorProposal(val key: String, val title: String, val body: String, val lines: Int) {
    companion object {
        private val KEY = Regex("""[A-Za-z0-9._/-]{1,120}""")
        fun of(node: JsonNode): RefactorProposal? {
            if (!node.isObject) return null
            val key = node["key"]?.takeIf { it.isString }?.asString()?.trim()?.takeIf { KEY.matches(it) } ?: return null
            val title = node["title"]?.takeIf { it.isString }?.asString()?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 } ?: return null
            val body = node["body"]?.takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() } ?: return null
            val lines = node["lines"]?.takeIf { it.isIntegralNumber && it.asInt() >= 0 }?.asInt() ?: return null
            return RefactorProposal(key, title, body, lines)
        }
    }
}

/** The Smith's body, then the cap and the values it was inspected against, quoted, so every ticket states both whatever the Smith wrote. */
internal fun ticketBody(proposal: RefactorProposal, config: RefactorConfig, scan: String): String = buildString {
    append(proposal.body.trimEnd()).append("\n\n")
    append(FileRefactorTicketsDelegate.VALUES_HEADING).append("\n\n")
    append("Filed by refactoring scan $scan on ${config.repo}, one of at most ${config.maxTickets} per run, each of ")
    append("**at most ${config.maxLines} changed lines** (this one estimated at ${proposal.lines}; code moved verbatim, as in a file split, counts its moved lines once, not as both added and removed, plus every other line of the new file: its KDoc, package line and imports). The values the code was inspected against, ")
    append("which the Context above says how this change serves:\n\n")
    config.values.trimEnd().lines().forEach { append("> ").append(it).append('\n') }
}

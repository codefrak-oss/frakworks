package com.codefrak.engine.repos

import java.time.OffsetDateTime

/** One private document's listing: its name, its length in characters, when and by whom it was last put. */
data class RepoDocInfo(val name: String, val chars: Int, val updatedAt: OffsetDateTime, val updatedBy: String)

/**
 * Private documents (engine/README.md, "Private documents"): text the user keeps against a target repo without it
 * entering the repo's git history. A Smith reads its own run's repo's through the frakworks MCP server
 * (list_private_docs, read_private_doc); every call names its repo, and nothing here ever reaches GitHub.
 * The engine's is `StoredRepoDocs` (engine-github), `engine.repo_doc`.
 */
interface RepoDocs {
    companion object {
        /** A doc's name: a path segment, letters, digits, `.`, `_` and `-`, not only dots. */
        val NAME = Regex("""^(?!\.+$)[A-Za-z0-9._-]{1,100}$""")
        /** The most characters one doc holds. */
        const val MAX_CHARS = 512 * 1024
    }

    fun list(repo: String): List<RepoDocInfo>

    /** The doc's whole text, or null when the repo has none by that name. */
    fun read(repo: String, name: String): String?

    /** Puts the doc, replacing one by the same name; true when it was new. */
    fun put(repo: String, name: String, content: String, by: String): Boolean

    /** False when the repo has no doc by that name. */
    fun delete(repo: String, name: String): Boolean
}

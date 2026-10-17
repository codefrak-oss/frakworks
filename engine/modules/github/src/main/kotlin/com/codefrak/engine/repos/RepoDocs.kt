package com.codefrak.engine.repos

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** One private document's listing: its name, its length in characters, when and by whom it was last put. */
data class RepoDocInfo(val name: String, val chars: Int, val updatedAt: OffsetDateTime, val updatedBy: String)

/**
 * Private documents (engine/README.md, "Private documents"): text the user keeps against a target repo without it
 * entering the repo's git history, `engine.repo_doc`. A Smith reads its own run's repo's through the frakworks MCP
 * server (list_private_docs, read_private_doc); every call names its repo, and nothing here ever reaches GitHub.
 */
@Repository
class RepoDocs(private val jdbc: JdbcClient) {
    companion object {
        /** A doc's name: a path segment, letters, digits, `.`, `_` and `-`, not only dots. */
        val NAME = Regex("""^(?!\.+$)[A-Za-z0-9._-]{1,100}$""")
        /** The most characters one doc holds. */
        const val MAX_CHARS = 512 * 1024
    }

    fun list(repo: String): List<RepoDocInfo> =
        jdbc.sql("SELECT name, CHAR_LENGTH(content), updated_at, updated_by FROM engine.repo_doc WHERE repo = :r ORDER BY name")
            .param("r", repo)
            .query { rs, _ -> RepoDocInfo(rs.getString(1), rs.getInt(2), rs.getObject(3, OffsetDateTime::class.java), rs.getString(4)) }.list()

    /** The doc's whole text, or null when the repo has none by that name. */
    fun read(repo: String, name: String): String? =
        jdbc.sql("SELECT content FROM engine.repo_doc WHERE repo = :r AND name = :n").param("r", repo).param("n", name)
            .query(String::class.java).optional().orElse(null)

    /** Puts the doc, replacing one by the same name; true when it was new. */
    fun put(repo: String, name: String, content: String, by: String): Boolean {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val updated = jdbc.sql("UPDATE engine.repo_doc SET content = :c, updated_at = :t, updated_by = :b WHERE repo = :r AND name = :n")
            .param("c", content).param("t", now).param("b", by).param("r", repo).param("n", name).update()
        if (updated > 0) return false
        jdbc.sql("INSERT INTO engine.repo_doc (repo, name, content, updated_at, updated_by) VALUES (:r, :n, :c, :t, :b)")
            .param("r", repo).param("n", name).param("c", content).param("t", now).param("b", by).update()
        return true
    }

    /** False when the repo has no doc by that name. */
    fun delete(repo: String, name: String): Boolean =
        jdbc.sql("DELETE FROM engine.repo_doc WHERE repo = :r AND name = :n").param("r", repo).param("n", name).update() == 1
}

package com.codefrak.engine.repos

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** [RepoDocs] in `engine.repo_doc`. */
@Repository
class StoredRepoDocs(private val jdbc: JdbcClient) : RepoDocs {
    override fun list(repo: String): List<RepoDocInfo> =
        jdbc.sql("SELECT name, CHAR_LENGTH(content), updated_at, updated_by FROM engine.repo_doc WHERE repo = :r ORDER BY name")
            .param("r", repo)
            .query { rs, _ -> RepoDocInfo(rs.getString(1), rs.getInt(2), rs.getObject(3, OffsetDateTime::class.java), rs.getString(4)) }.list()

    override fun read(repo: String, name: String): String? =
        jdbc.sql("SELECT content FROM engine.repo_doc WHERE repo = :r AND name = :n").param("r", repo).param("n", name)
            .query(String::class.java).optional().orElse(null)

    override fun put(repo: String, name: String, content: String, by: String): Boolean {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val updated = jdbc.sql("UPDATE engine.repo_doc SET content = :c, updated_at = :t, updated_by = :b WHERE repo = :r AND name = :n")
            .param("c", content).param("t", now).param("b", by).param("r", repo).param("n", name).update()
        if (updated > 0) return false
        jdbc.sql("INSERT INTO engine.repo_doc (repo, name, content, updated_at, updated_by) VALUES (:r, :n, :c, :t, :b)")
            .param("r", repo).param("n", name).param("c", content).param("t", now).param("b", by).update()
        return true
    }

    override fun delete(repo: String, name: String): Boolean =
        jdbc.sql("DELETE FROM engine.repo_doc WHERE repo = :r AND name = :n").param("r", repo).param("n", name).update() == 1
}

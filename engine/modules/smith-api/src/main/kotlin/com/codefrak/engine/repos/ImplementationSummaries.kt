package com.codefrak.engine.repos

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** `engine.implementation_summary` (V103): each ticket's latest work result's `implementationSummary`. */
@Repository
class ImplementationSummaries(private val jdbc: JdbcClient) {
    /** Keeps [summary] as the ticket's, over any earlier round's. */
    fun save(repo: String, issue: Long, summary: String) {
        val updated = jdbc.sql("UPDATE engine.implementation_summary SET summary = :s, updated_at = CURRENT_TIMESTAMP WHERE full_name = :r AND issue = :i")
            .param("s", summary).param("r", repo).param("i", issue).update()
        if (updated == 0) jdbc.sql("INSERT INTO engine.implementation_summary (full_name, issue, summary) VALUES (:r, :i, :s)")
            .param("r", repo).param("i", issue).param("s", summary).update()
    }

    fun of(repo: String, issue: Long): String? =
        jdbc.sql("SELECT summary FROM engine.implementation_summary WHERE full_name = :r AND issue = :i")
            .param("r", repo).param("i", issue).query(String::class.java).optional().orElse(null)
}

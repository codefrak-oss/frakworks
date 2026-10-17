package com.codefrak.engine.research

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/** One round's answer of a research ticket: the summary, `answer.md` and `answer.html` as the Smith wrote them. */
data class ResearchRow(val repo: String, val issue: Long, val round: Int, val processInstance: String, val summary: String,
                       val md: String, val html: String, val answeredAt: OffsetDateTime?)

/**
 * The `engine.research_answer` table (engine/README.md, "Research"): a research ticket's answers, one row a
 * round, kept by the engine and never committed unless the asker says `save`. A table rather than a process
 * variable, so the page outlives the flow's 180-day history and is served after the ticket closes.
 */
@Component
class ResearchAnswers(private val jdbc: JdbcClient) {
    private val row = { rs: java.sql.ResultSet, _: Int ->
        ResearchRow(rs.getString("full_name"), rs.getLong("issue"), rs.getInt("round"), rs.getString("process_instance"),
            rs.getString("summary"), String(rs.getBytes("md"), Charsets.UTF_8), String(rs.getBytes("html"), Charsets.UTF_8),
            rs.getObject("answered_at", OffsetDateTime::class.java))
    }

    /** Keeps the round's answer; a row already there for the round is replaced, so a retried step is idempotent. */
    fun keep(repo: String, issue: Long, round: Int, processInstance: String, summary: String, md: String, html: String) {
        fun bind(sql: String) = jdbc.sql(sql).param("n", repo).param("i", issue).param("r", round).param("p", processInstance)
            .param("s", summary).param("md", md.toByteArray(Charsets.UTF_8)).param("html", html.toByteArray(Charsets.UTF_8))
        val updated = bind("""UPDATE engine.research_answer SET process_instance = :p, summary = :s, md = :md, html = :html,
            answered_at = CURRENT_TIMESTAMP WHERE full_name = :n AND issue = :i AND round = :r""").update()
        if (updated > 0) return
        bind("""INSERT INTO engine.research_answer (full_name, issue, round, process_instance, summary, md, html)
            VALUES (:n, :i, :r, :p, :s, :md, :html)""").update()
    }

    /** The latest round's answer of `repo`#`issue`, or null when none was kept. */
    fun latest(repo: String, issue: Long): ResearchRow? =
        jdbc.sql("SELECT * FROM engine.research_answer WHERE LOWER(full_name) = LOWER(:n) AND issue = :i ORDER BY round DESC")
            .param("n", repo).param("i", issue).query(row).list().firstOrNull()

    /** Every round's answer of `repo`#`issue`, round 1 first. */
    fun thread(repo: String, issue: Long): List<ResearchRow> =
        jdbc.sql("SELECT * FROM engine.research_answer WHERE LOWER(full_name) = LOWER(:n) AND issue = :i ORDER BY round")
            .param("n", repo).param("i", issue).query(row).list()
}

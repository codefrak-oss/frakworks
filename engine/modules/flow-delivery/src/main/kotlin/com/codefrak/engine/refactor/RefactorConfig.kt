package com.codefrak.engine.refactor

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The shipped refactoring values (`bots/refactor-values.md`, copied into the jar as `refactor/refactor-values.md` by
 * `processResources`): what a repo's run inspects the code against until the repo stores its own text.
 */
object RefactorValues {
    val DEFAULT: String by lazy {
        RefactorValues::class.java.getResourceAsStream("/refactor/refactor-values.md")?.use { String(it.readAllBytes()) }
            ?: error("refactor/refactor-values.md is not in the engine jar: bots/refactor-values.md is copied there at build")
    }
}

/** How a filed refactoring ticket is approved: `human` files `feature:confirm` and waits for `authorized`; `none` files `feature:auto` and starts it. */
enum class RefactorApproval {
    HUMAN, NONE;

    val id get() = name.lowercase()

    companion object {
        fun of(id: String): RefactorApproval = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("approval is human or none, not '$id'")
    }
}

/**
 * One repo's refactoring config (`engine.refactor_config`, V55; engine/README.md, "Refactoring jobs"): the row, or the
 * defaults for a repo with none ([RefactorConfigStore.get]). `values` is the text in force: the row's, or the shipped
 * one when the row has none (`valuesDefault`).
 */
data class RefactorConfig(
    val repo: String, val enabled: Boolean, val schedule: Long, val values: String, val valuesDefault: Boolean,
    val maxTickets: Int, val maxLines: Int, val approval: RefactorApproval, val jobId: String?,
    val updatedAt: OffsetDateTime?, val updatedBy: String?,
    /** The tier (1-10) the refactor-scan ticket is filed at and its Smith runs at; null files no scan (the job ends failed). Not the proposals' tier. */
    val tier: Int? = null,
) {
    /** What `GET`/`PUT /repos/{owner}/{name}/refactor` answer. */
    fun view() = RefactorConfigView(repo, enabled, schedule, values, valuesDefault, maxTickets, maxLines, approval.id, jobId, updatedAt, updatedBy, tier)

    companion object {
        const val DEFAULT_SCHEDULE = 604800L
        const val DEFAULT_MAX_TICKETS = 2
        const val DEFAULT_MAX_LINES = 300
        const val MIN_SCHEDULE = 60L
        val TICKETS_RANGE = 1..20
        val LINES_RANGE = 10..5000
        const val MAX_VALUES_LENGTH = 20000
        val TIER_RANGE = 1..10

        /** A repo that never set anything up: off, weekly, the shipped values, 2 tickets of at most 300 lines, a human approves. */
        fun defaults(repo: String) = RefactorConfig(repo, false, DEFAULT_SCHEDULE, RefactorValues.DEFAULT, true, DEFAULT_MAX_TICKETS, DEFAULT_MAX_LINES,
            RefactorApproval.HUMAN, null, null, null)
    }
}

data class RefactorConfigView(
    val repo: String, val enabled: Boolean, val schedule: Long, val values: String, val valuesDefault: Boolean,
    val maxTickets: Int, val maxLines: Int, val approval: String, val jobId: String?,
    val updatedAt: OffsetDateTime?, val updatedBy: String?, val tier: Int?,
)

/** `engine.refactor_config` (V55). */
@Repository
class RefactorConfigStore(private val jdbc: JdbcClient) {
    /** The repo's row, or null when it never set anything up. */
    fun row(repo: String): RefactorConfig? = jdbc.sql("$SELECT WHERE repo = :r").param("r", repo).query(ROW).optional().orElse(null)

    /** The repo's config: its row, or the defaults. */
    fun get(repo: String): RefactorConfig = row(repo) ?: RefactorConfig.defaults(repo)

    /** Writes the row as [config] says, inserting it when the repo had none; `values` is stored as NULL when it is the shipped text. */
    fun upsert(config: RefactorConfig, now: Instant, by: String?) {
        val values = config.values.takeUnless { config.valuesDefault }
        val updated = jdbc.sql("""UPDATE engine.refactor_config SET enabled = :e, schedule_seconds = :s, values_text = :v, max_tickets = :t,
            max_lines = :l, approval = :a, job_id = :j, tier = :tier, updated_at = :at, updated_by = :by WHERE repo = :r""")
            .param("e", config.enabled).param("s", config.schedule).param("v", values).param("t", config.maxTickets).param("l", config.maxLines)
            .param("a", config.approval.id).param("j", config.jobId).param("tier", config.tier).param("at", now.atOffset(ZoneOffset.UTC)).param("by", by).param("r", config.repo).update()
        if (updated == 0) jdbc.sql("""INSERT INTO engine.refactor_config (repo, enabled, schedule_seconds, values_text, max_tickets, max_lines, approval, job_id, tier, updated_at, updated_by)
            VALUES (:r, :e, :s, :v, :t, :l, :a, :j, :tier, :at, :by)""")
            .param("r", config.repo).param("e", config.enabled).param("s", config.schedule).param("v", values).param("t", config.maxTickets)
            .param("l", config.maxLines).param("a", config.approval.id).param("j", config.jobId).param("tier", config.tier).param("at", now.atOffset(ZoneOffset.UTC)).param("by", by).update()
    }

    private companion object {
        const val SELECT = "SELECT repo, enabled, schedule_seconds, values_text, max_tickets, max_lines, approval, job_id, tier, updated_at, updated_by FROM engine.refactor_config"
        val ROW = { rs: ResultSet, _: Int ->
            val stored = rs.getString("values_text")
            RefactorConfig(rs.getString("repo"), rs.getBoolean("enabled"), rs.getLong("schedule_seconds"), stored ?: RefactorValues.DEFAULT, stored == null,
                rs.getInt("max_tickets"), rs.getInt("max_lines"), RefactorApproval.of(rs.getString("approval")), rs.getString("job_id"),
                rs.getObject("updated_at", OffsetDateTime::class.java), rs.getString("updated_by"),
                (rs.getObject("tier") as? Number)?.toInt())
        }
    }
}


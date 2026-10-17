package com.codefrak.engine.jobs

import com.codefrak.engine.engineJson
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.readValue
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * One job run (`engine.job_run`, V59), what `GET /jobs/{id}/runs` answers. `status` is `done`, `nothing` (it ran and filed
 * nothing, `summary` says why) or `failed` (`outcome` says why); null while it runs. A refactor run carries its scan:
 * the ticket, the scan's outcome and summary, the tickets it [filed], what it [skipped] by reason, and [foundNothing]
 * when the scan proposed nothing at all.
 */
data class JobRun(
    val jobId: String, val repo: String, val kind: String, val instance: String, val startedAt: OffsetDateTime, val endedAt: OffsetDateTime?,
    val status: String?, val outcome: String?, val summary: String?,
    val scanIssue: Long?, val scanEndedAt: OffsetDateTime?, val scanOutcome: String?, val scanSummary: String?,
    val filed: List<Long>?, val skipped: Map<String, Int>?, val foundNothing: Boolean?,
)

/** `engine.job_run` (V59): the job flow's delegates and the refactor scan's write it; `/jobs/{id}/runs` reads it. */
@Repository
class JobRuns(private val jdbc: JdbcClient) {
    /** A run started (`noteJobStarted`); a retried step finds its row and leaves it. */
    fun started(instance: String, jobId: String, repo: String, kind: String, at: Instant = Instant.now()) {
        try {
            jdbc.sql("INSERT INTO engine.job_run (process_instance, job_id, repo, kind, started_at) VALUES (:i, :j, :r, :k, :at)")
                .param("i", instance).param("j", jobId).param("r", repo).param("k", kind).param("at", at.atOffset(ZoneOffset.UTC)).update()
        } catch (_: DuplicateKeyException) {}
    }

    /** A run ended (`noteJobEnded`), its status from [statusOf]. */
    fun ended(instance: String, outcome: String, summary: String?, at: Instant = Instant.now()) {
        jdbc.sql("UPDATE engine.job_run SET ended_at = :at, status = :s, outcome = :o, summary = :m WHERE process_instance = :i")
            .param("at", at.atOffset(ZoneOffset.UTC)).param("s", statusOf(outcome, summary)).param("o", outcome).param("m", summary)
            .param("i", instance).update()
    }

    /** The refactor run [instance] filed scan ticket [issue]. */
    fun scanFiled(instance: String, issue: Long) {
        jdbc.sql("UPDATE engine.job_run SET scan_issue = :n WHERE process_instance = :i").param("n", issue).param("i", instance).update()
    }

    /** What scan [issue] on [repo] filed and skipped, on the newest run that filed it. */
    fun scanResult(repo: String, issue: Long, filed: List<Long>, skipped: Map<String, Int>, foundNothing: Boolean) {
        jdbc.sql("UPDATE engine.job_run SET filed = :f, skipped = :s, found_nothing = :n WHERE process_instance = (${newest()})")
            .param("f", engineJson.writeValueAsString(filed)).param("s", engineJson.writeValueAsString(skipped)).param("n", foundNothing)
            .param("r", repo.lowercase()).param("issue", issue).update()
    }

    /** Scan [issue] on [repo] closed with [outcome] and [summary], on the newest run that filed it. */
    fun scanEnded(repo: String, issue: Long, outcome: String?, summary: String?, at: Instant = Instant.now()) {
        jdbc.sql("UPDATE engine.job_run SET scan_ended_at = :at, scan_outcome = :o, scan_summary = :m WHERE process_instance = (${newest()})")
            .param("at", at.atOffset(ZoneOffset.UTC)).param("o", outcome).param("m", summary)
            .param("r", repo.lowercase()).param("issue", issue).update()
    }

    private fun newest() = "SELECT process_instance FROM engine.job_run WHERE LOWER(repo) = :r AND scan_issue = :issue ORDER BY started_at DESC LIMIT 1"

    /** Job [jobId]'s runs, newest first, at most [limit]. */
    fun list(jobId: String, limit: Int): List<JobRun> =
        jdbc.sql("$SELECT WHERE job_id = :j ORDER BY started_at DESC, process_instance LIMIT :l").param("j", jobId).param("l", limit)
            .query { rs, _ -> row(rs) }.list()

    /** The run on [instance], or null. */
    fun get(instance: String): JobRun? =
        jdbc.sql("$SELECT WHERE process_instance = :i").param("i", instance).query { rs, _ -> row(rs) }.optional().orElse(null)

    private fun row(rs: ResultSet) = JobRun(
        rs.getString("job_id"), rs.getString("repo"), rs.getString("kind"), rs.getString("process_instance"),
        rs.getObject("started_at", OffsetDateTime::class.java), rs.getObject("ended_at", OffsetDateTime::class.java),
        rs.getString("status"), rs.getString("outcome"), rs.getString("summary"),
        rs.getObject("scan_issue")?.let { (it as Number).toLong() }, rs.getObject("scan_ended_at", OffsetDateTime::class.java),
        rs.getString("scan_outcome"), rs.getString("scan_summary"),
        rs.getString("filed")?.let { engineJson.readValue<List<Long>>(it) }, rs.getString("skipped")?.let { engineJson.readValue<Map<String, Int>>(it) },
        rs.getObject("found_nothing") as Boolean?,
    )

    companion object {
        /** `failed` for an outcome `failed...`, `nothing` for an outcome `nothing` or a summary `filed nothing...`, else `done`. */
        fun statusOf(outcome: String, summary: String?): String = when {
            outcome.startsWith("failed") -> "failed"
            outcome == "nothing" || summary?.startsWith("filed nothing") == true -> "nothing"
            else -> "done"
        }
        private const val SELECT = """SELECT process_instance, job_id, repo, kind, started_at, ended_at, status, outcome, summary, scan_issue,
            scan_ended_at, scan_outcome, scan_summary, filed, skipped, found_nothing FROM engine.job_run"""
    }
}

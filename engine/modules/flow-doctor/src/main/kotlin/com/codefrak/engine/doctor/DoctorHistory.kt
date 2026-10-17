package com.codefrak.engine.doctor

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime

/**
 * One doctor run (engine/README.md, "Doctor"): the ticket, the position it was stuck at and since when, and what
 * the run made of it. `outcome` is `running` until `apply the remedy` sets `applied`, `none` or `failed`.
 * `smithRunId` is its `health check`'s smith-run instance, which names the run's log
 * (`issue-<issue>-doctor-<smithRunId>.log`); null until there is one. `sighting` is the run's [DoctorSighting]: null for a
 * run still going, a `healthy` one, and every run that ended before V35.
 */
data class DoctorRun(
    val id: String, val repo: String, val issue: Long, val rootInstanceId: String, val position: List<String>,
    val stuckSince: OffsetDateTime, val startedAt: OffsetDateTime, val endedAt: OffsetDateTime?, val diagnosis: String?,
    val remedy: String?, val target: String?, val outcome: String, val detail: String?, val smithRunId: String? = null,
    val sighting: DoctorSighting? = null,
)

/** A ticket with doctor runs: how many, and when and how its newest went. */
data class DoctorTicket(val repo: String, val issue: Long, val runs: Long, val lastStartedAt: OffsetDateTime, val lastOutcome: String)

/** `engine.doctor_run`: the doctor's history, one row per run, written as it starts and finished by [finish]. */
@Repository
class DoctorHistory(private val jdbc: JdbcClient, private val sightings: DoctorSightings) {
    fun start(id: String, repo: String, issue: Long, rootInstanceId: String, position: String, stuckSince: OffsetDateTime) {
        jdbc.sql("""INSERT INTO engine.doctor_run (id, repo, issue, root_instance_id, position, stuck_since, outcome)
            VALUES (:id, :r, :i, :root, :p, :s, 'running')""")
            .param("id", id).param("r", repo).param("i", issue).param("root", rootInstanceId).param("p", position).param("s", stuckSince).update()
    }

    /** Adds [note] to the run [id]'s detail, after what it has. */
    fun note(id: String, note: String) {
        val had = get(id)?.detail
        jdbc.sql("UPDATE engine.doctor_run SET detail = :x WHERE id = :id")
            .param("x", had?.let { "$it; $note" } ?: note).param("id", id).update()
    }

    /**
     * Ends the run with what `apply the remedy` made of it, and the smith-run its `health check` started (a null
     * keeps what the row has); false when there is no such run.
     */
    fun finish(id: String, diagnosis: String?, remedy: String?, target: String?, outcome: String, detail: String?, smithRunId: String? = null): Boolean =
        jdbc.sql("""UPDATE engine.doctor_run SET ended_at = CURRENT_TIMESTAMP, diagnosis = :d, remedy = :m, target = :t, outcome = :o, detail = :x,
                smith_run_id = COALESCE(CAST(:sr AS VARCHAR(64)), smith_run_id)
            WHERE id = :id""")
            .param("d", diagnosis).param("m", remedy).param("t", target).param("o", outcome).param("x", detail).param("sr", smithRunId)
            .param("id", id).update() == 1

    /**
     * One finished row for a plugin doctor's diagnosis (engine/README.md, "Custom jobs and the SDK"): no process
     * instance, so the id is a fresh UUID and `started_at` is `ended_at`; [target] is `plugin:<bean>`. Answers the id.
     */
    fun record(repo: String, issue: Long, rootInstanceId: String, position: String, stuckSince: OffsetDateTime,
               diagnosis: String, remedy: String, target: String, outcome: String, detail: String?): String {
        val id = java.util.UUID.randomUUID().toString()
        jdbc.sql("""INSERT INTO engine.doctor_run (id, repo, issue, root_instance_id, position, stuck_since, ended_at, diagnosis, remedy, target, outcome, detail)
            VALUES (:id, :r, :i, :root, :p, :s, CURRENT_TIMESTAMP, :d, :m, :t, :o, :x)""")
            .param("id", id).param("r", repo).param("i", issue).param("root", rootInstanceId).param("p", position).param("s", stuckSince)
            .param("d", diagnosis).param("m", remedy).param("t", target).param("o", outcome).param("x", detail).update()
        return id
    }

    /** The run [id], with its sighting. */
    fun get(id: String): DoctorRun? = jdbc.sql("$SELECT WHERE id = :id").param("id", id).query(ROW).optional().orElse(null)
        ?.let { run -> sightings.of(id)?.let { run.copy(sighting = it) } ?: run }

    /** The ticket's runs, newest first, each with its sighting. */
    fun of(repo: String, issue: Long): List<DoctorRun> =
        jdbc.sql("$SELECT WHERE repo = :r AND issue = :i ORDER BY started_at DESC, id DESC").param("r", repo).param("i", issue).query(ROW).list()
            .with(sightings.ofTicket(repo, issue))

    /** Every ticket's runs that started at or after [since], newest first, each with its sighting. */
    fun since(since: OffsetDateTime): List<DoctorRun> =
        jdbc.sql("$SELECT WHERE started_at >= :s ORDER BY started_at DESC, id DESC").param("s", since).query(ROW).list()
            .with(sightings.since(since))

    /**
     * The runs on the flow [rootInstanceId] at the place [leaves] (`Signature.leaves` of their position: the leaves
     * without incident ids), newest first, each with its sighting; [except] left out.
     */
    fun at(rootInstanceId: String, leaves: List<String>, except: String? = null): List<DoctorRun> =
        jdbc.sql("$SELECT WHERE root_instance_id = :root ORDER BY started_at DESC, id DESC").param("root", rootInstanceId).query(ROW).list()
            .filter { it.id != except && Signature.leaves(it.position) == leaves }
            .let { runs -> if (runs.isEmpty()) runs else runs.with(sightings.ofRoot(rootInstanceId)) }

    private fun List<DoctorRun>.with(seen: Map<String, DoctorSighting>) = map { run -> seen[run.id]?.let { run.copy(sighting = it) } ?: run }

    /** The tickets that have runs, one row each, newest last run first; none is `[]`. */
    fun tickets(): List<DoctorTicket> =
        jdbc.sql("""SELECT repo, issue, runs, started_at, outcome FROM (
                SELECT repo, issue, started_at, outcome, COUNT(*) OVER (PARTITION BY repo, issue) AS runs,
                    ROW_NUMBER() OVER (PARTITION BY repo, issue ORDER BY started_at DESC, id DESC) AS n
                FROM engine.doctor_run) t
            WHERE n = 1 ORDER BY started_at DESC, repo, issue""")
            .query { rs, _ -> DoctorTicket(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getObject(4, OffsetDateTime::class.java), rs.getString(5)) }
            .list()

    /** Whether a run was already made on this flow for this stuck episode: the same position, stuck since the same time. */
    fun ranFor(rootInstanceId: String, position: String, stuckSince: OffsetDateTime): Boolean =
        jdbc.sql("SELECT COUNT(*) FROM engine.doctor_run WHERE root_instance_id = :root AND position = :p AND stuck_since = :s")
            .param("root", rootInstanceId).param("p", position).param("s", stuckSince).query(Long::class.java).single() > 0

    /** Whether a run on this flow at this position, stuck since any time, reached a verdict: it ended `none` or `applied`. */
    fun settled(rootInstanceId: String, position: String): Boolean =
        jdbc.sql("SELECT COUNT(*) FROM engine.doctor_run WHERE root_instance_id = :root AND position = :p AND outcome IN ('none', 'applied')")
            .param("root", rootInstanceId).param("p", position).query(Long::class.java).single() > 0

    /** Whether a doctor run at [position] of the flow has not ended yet (outcome `running`). */
    fun going(rootInstanceId: String, position: String): Boolean =
        jdbc.sql("SELECT COUNT(*) FROM engine.doctor_run WHERE root_instance_id = :root AND position = :p AND outcome = 'running'")
            .param("root", rootInstanceId).param("p", position).query(Long::class.java).single() > 0

    private companion object {
        const val SELECT = "SELECT id, repo, issue, root_instance_id, position, stuck_since, started_at, ended_at, diagnosis, remedy, target, " +
"outcome, detail, smith_run_id FROM engine.doctor_run"
        val ROW = { rs: ResultSet, _: Int ->
            DoctorRun(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4), TicketPosition.parseKey(rs.getString(5)),
                rs.getObject(6, OffsetDateTime::class.java), rs.getObject(7, OffsetDateTime::class.java), rs.getObject(8, OffsetDateTime::class.java),
                rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14))
        }
    }
}

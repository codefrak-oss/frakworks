package com.codefrak.engine.doctor

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.OffsetDateTime

/** The bug a sighting is lumped into: its repo, issue (null while its intake refines) and state. */
data class SightingBug(val repo: String, val issue: Long?, val state: String)

/**
 * A doctor run's sighting as `GET /doctor/history`, `GET /doctor/runs` and `read_doctor_history` show it: its kind,
 * where, the bug it is lumped into (null until filing), `tickets` and `runs`, the distinct stuck tickets and the
 * sightings on its bug, or on its signature while it has none, and its `signature` (the hash), so a reader counts a
 * repo's distinct defects recorded, on a bug or not.
 */
data class DoctorSighting(val id: Long, val kind: String, val where: String, val bug: SightingBug?, val tickets: Long, val runs: Long, val signature: String)

/** A sighting to record ([DoctorSightings.record]). */
data class NewSighting(
    val doctorRunId: String, val earlierRunId: String?, val repo: String, val issue: Long, val rootInstanceId: String,
    val kind: String, val runClass: String?, val where: String, val summary: String?, val signature: Signature,
    /** The earliest open incident's timestamp on the stuck position, null with none: the episode's start for [DoctorBugs]. */
    val incidentSince: OffsetDateTime? = null,
)

/**
 * `engine.doctor_sighting` and `engine.doctor_signature` (engine/README.md, "Doctor"): one sighting per doctor run on a
 * ticket that was really stuck, and each signature the first time it is seen, with no bug. Nothing here files, asks
 * Jev or writes to GitHub.
 */
@Repository
class DoctorSightings(private val jdbc: JdbcClient) {
    companion object {
        /** The sighting kinds (engine/README.md, "Doctor", "Sightings"). */
        val KINDS = setOf("unfixable", "retried", "not-held", "backstop", "remedy-failed", "doctor-failed")

        private const val SELECT = """SELECT s.doctor_run_id, s.id, s.kind, s."where", b.repo, b.issue, b.state,
                (SELECT COUNT(DISTINCT CONCAT(x.repo, '#', CAST(x.issue AS VARCHAR(20)))) FROM engine.doctor_sighting x
                    WHERE (s.bug_id IS NOT NULL AND x.bug_id = s.bug_id) OR (s.bug_id IS NULL AND x.signature = s.signature)),
                (SELECT COUNT(*) FROM engine.doctor_sighting x
                    WHERE (s.bug_id IS NOT NULL AND x.bug_id = s.bug_id) OR (s.bug_id IS NULL AND x.signature = s.signature)),
                s.signature
            FROM engine.doctor_sighting s JOIN engine.doctor_run r ON r.id = s.doctor_run_id
            LEFT JOIN engine.doctor_bug b ON b.id = s.bug_id"""
        private val ROW = { rs: ResultSet, _: Int ->
            rs.getString(1) to DoctorSighting(rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getString(5)?.let { SightingBug(it, rs.getObject(6, Long::class.javaObjectType), rs.getString(7)) }, rs.getLong(8), rs.getLong(9), rs.getString(10))
        }
    }

    /**
     * Writes the sighting, and its signature when it is new (no bug); answers the sighting. In its own transaction,
     * so a failed write never marks the doctor run's rollback-only on Postgres; a signature two runs record at once
     * fails one of them on the key, and its caller tries once more.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(s: NewSighting): DoctorSighting {
        require(s.kind in KINDS) { "no such sighting kind: ${s.kind}" }
        val hash = s.signature.hash
        val known = jdbc.sql("SELECT COUNT(*) FROM engine.doctor_signature WHERE signature = :h").param("h", hash).query(Long::class.java).single() > 0
        if (!known) jdbc.sql("""INSERT INTO engine.doctor_signature (signature, "where", leaves, shape) VALUES (:h, :w, :l, :sh)""")
            .param("h", hash).param("w", s.signature.where).param("l", s.signature.leaves.joinToString(","))
            .param("sh", s.signature.shape.joinToString("\n").ifEmpty { null }).update()
        jdbc.sql("""INSERT INTO engine.doctor_sighting (doctor_run_id, earlier_run_id, repo, issue, root_instance_id, kind, run_class, "where", summary, signature)
            VALUES (:run, :earlier, :r, :i, :root, :k, :c, :w, :sum, :h)""")
            .param("run", s.doctorRunId).param("earlier", s.earlierRunId).param("r", s.repo).param("i", s.issue).param("root", s.rootInstanceId)
            .param("k", s.kind).param("c", s.runClass).param("w", s.where).param("sum", s.summary?.take(2000)).param("h", hash).update()
        return of(s.doctorRunId)!!
    }

    /** The sighting of the doctor run [doctorRunId], or null when it made none. */
    fun of(doctorRunId: String): DoctorSighting? = jdbc.sql("$SELECT WHERE s.doctor_run_id = :id").param("id", doctorRunId).query(ROW).list().firstOrNull()?.second

    /** The sightings of the ticket's runs, by doctor run id. */
    fun ofTicket(repo: String, issue: Long): Map<String, DoctorSighting> =
        jdbc.sql("$SELECT WHERE r.repo = :r AND r.issue = :i").param("r", repo).param("i", issue).query(ROW).list().toMap()

    /** The sightings of the runs that started at or after [since], by doctor run id. */
    fun since(since: OffsetDateTime): Map<String, DoctorSighting> =
        jdbc.sql("$SELECT WHERE r.started_at >= :s").param("s", since).query(ROW).list().toMap()

    /** The sightings of the runs on the flow [rootInstanceId], by doctor run id. */
    fun ofRoot(rootInstanceId: String): Map<String, DoctorSighting> =
        jdbc.sql("$SELECT WHERE r.root_instance_id = :root").param("root", rootInstanceId).query(ROW).list().toMap()

    /** The signature [hash]'s row, as `where`, leaves and shape; null when it was never seen. */
    fun signature(hash: String): Triple<String, String, String?>? =
        jdbc.sql("""SELECT "where", leaves, shape FROM engine.doctor_signature WHERE signature = :h""").param("h", hash)
            .query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }.optional().orElse(null)

    /** The signature of the sighting [id]. */
    fun signatureOf(id: Long): String? =
        jdbc.sql("SELECT signature FROM engine.doctor_sighting WHERE id = :id").param("id", id).query(String::class.java).optional().orElse(null)

    /** The sighting [id]'s kind, earlier run and run class, as written. */
    fun detail(id: Long): Triple<String, String?, String?>? =
        jdbc.sql("SELECT kind, earlier_run_id, run_class FROM engine.doctor_sighting WHERE id = :id").param("id", id)
            .query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }.optional().orElse(null)
}

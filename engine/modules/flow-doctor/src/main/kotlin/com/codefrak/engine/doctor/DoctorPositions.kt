package com.codefrak.engine.doctor

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime

/** One row of `engine.doctor_position`: where a ticket's flow was at the last tick, and since when. */
data class StoredPosition(
    val rootInstanceId: String, val repo: String, val issue: Long, val position: String, val since: OffsetDateTime, val observedAt: OffsetDateTime,
)

/** `engine.doctor_position` (engine/README.md, "Doctor"): each running ticket flow's position as the last tick saw it. */
@Repository
class DoctorPositions(private val jdbc: JdbcClient) {
    fun get(rootInstanceId: String): StoredPosition? =
        jdbc.sql("SELECT root_instance_id, repo, issue, position, since, observed_at FROM engine.doctor_position WHERE root_instance_id = :id")
            .param("id", rootInstanceId).query(ROW).optional().orElse(null)

    fun all(): List<StoredPosition> =
        jdbc.sql("SELECT root_instance_id, repo, issue, position, since, observed_at FROM engine.doctor_position").query(ROW).list()

    /** The flow's position, there since [since], seen at [observedAt]. */
    fun save(rootInstanceId: String, repo: String, issue: Long, position: String, since: OffsetDateTime, observedAt: OffsetDateTime) {
        val updated = jdbc.sql("""UPDATE engine.doctor_position SET repo = :r, issue = :i, position = :p, since = :s, observed_at = :o
            WHERE root_instance_id = :id""")
            .param("r", repo).param("i", issue).param("p", position).param("s", since).param("o", observedAt).param("id", rootInstanceId).update()
        if (updated == 0) jdbc.sql("""INSERT INTO engine.doctor_position (root_instance_id, repo, issue, position, since, observed_at)
            VALUES (:id, :r, :i, :p, :s, :o)""")
            .param("id", rootInstanceId).param("r", repo).param("i", issue).param("p", position).param("s", since).param("o", observedAt).update()
    }

    /** Drops the rows of flows that are no longer running; answers how many went. */
    fun keepOnly(running: Collection<String>): Int =
        all().map { it.rootInstanceId }.filter { it !in running }.sumOf {
            jdbc.sql("DELETE FROM engine.doctor_position WHERE root_instance_id = :id").param("id", it).update()
        }

    private companion object {
        val ROW = { rs: ResultSet, _: Int ->
            StoredPosition(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                rs.getObject(5, OffsetDateTime::class.java), rs.getObject(6, OffsetDateTime::class.java))
        }
    }
}
